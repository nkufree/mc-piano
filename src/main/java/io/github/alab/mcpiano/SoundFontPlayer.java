package io.github.alab.mcpiano;

import javax.sound.midi.Instrument;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Patch;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Playback backend based on the open-source Gervill software synthesizer that
 * ships with OpenJDK.  It loads a real SF2 and receives MIDI note and CC64
 * messages directly, rather than simulating a piano from short game samples.
 */
public final class SoundFontPlayer {
    private static final int CHANNEL_COUNT = 16;

    private final ExecutorService loadingExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mc-piano-soundfont-loader");
        thread.setDaemon(true);
        return thread;
    });
    private final Object deviceLock = new Object();

    private volatile State state = State.NOT_LOADED;
    private volatile String detail = "No SoundFont loaded.";
    private volatile Path configuredPath;
    /** 0 = preserve source dynamics; 1 = every note reaches the same peak. */
    private volatile float weakestVelocityRatio = 45.0f / 100.0f;
    private Synthesizer synthesizer;
    private MidiChannel[] channels;

    public void loadAsync(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (state == State.LOADING && normalized.equals(configuredPath)) return;
        configuredPath = normalized;
        state = State.LOADING;
        detail = "Loading " + normalized.getFileName() + "...";
        CompletableFuture.runAsync(() -> load(normalized), loadingExecutor);
    }

    public boolean isReady() {
        return state == State.READY;
    }

    public boolean isLoading() {
        return state == State.LOADING;
    }

    public String status() {
        return detail;
    }

    public void setWeakestVelocityPercent(double percent) {
        weakestVelocityRatio = (float) Math.clamp(percent / 100.0, 0.0, 1.0);
    }

    public double weakestVelocityPercent() {
        return weakestVelocityRatio * 100.0;
    }

    public void noteOn(int channel, int note, int velocity) {
        MidiChannel midiChannel = channel(channel);
        if (midiChannel != null) midiChannel.noteOn(note, normalizeVelocity(velocity));
    }

    public void noteOff(int channel, int note) {
        MidiChannel midiChannel = channel(channel);
        if (midiChannel != null) midiChannel.noteOff(note);
    }

    public void controlChange(int channel, int controller, int value) {
        MidiChannel midiChannel = channel(channel);
        if (midiChannel != null) midiChannel.controlChange(controller, value);
    }

    public void stopAll() {
        MidiChannel[] activeChannels = channels;
        if (activeChannels == null) return;
        for (MidiChannel channel : activeChannels) {
            if (channel != null) {
                channel.allSoundOff();
                channel.resetAllControllers();
            }
        }
    }

    private MidiChannel channel(int index) {
        MidiChannel[] activeChannels = channels;
        if (!isReady() || activeChannels == null || index < 0 || index >= CHANNEL_COUNT) return null;
        return activeChannels[index];
    }

    private void load(Path file) {
        try {
            if (!Files.isRegularFile(file)) throw new IOException("SF2 file not found: " + file);
            Soundbank soundbank = MidiSystem.getSoundbank(file.toFile());
            if (soundbank == null) throw new IOException("The file is not a readable SF2 SoundFont.");
            Instrument[] instruments = soundbank.getInstruments();
            if (instruments.length == 0) throw new IOException("The SoundFont has no instruments.");

            synchronized (deviceLock) {
                closeDevice();
                Synthesizer openedSynthesizer = MidiSystem.getSynthesizer();
                if (!openedSynthesizer.isSoundbankSupported(soundbank)) {
                    throw new IOException("The OpenJDK Gervill synthesizer does not support this SoundFont.");
                }
                openedSynthesizer.open();

                // This is a piano visualizer: load only its first piano preset.
                // Loading every preset from a multi-gigabyte studio SF2 would be
                // needlessly slow and can exhaust Minecraft's heap.
                Instrument piano = instruments[0];
                if (!openedSynthesizer.loadInstrument(piano)) {
                    openedSynthesizer.close();
                    throw new IOException("Could not load piano preset " + piano.getName() + ".");
                }
                Patch patch = piano.getPatch();
                MidiChannel[] openedChannels = openedSynthesizer.getChannels();
                for (int index = 0; index < Math.min(CHANNEL_COUNT, openedChannels.length); index++) {
                    if (openedChannels[index] != null) {
                        openedChannels[index].programChange(patch.getBank(), patch.getProgram());
                        // Studio SF2 presets honour both CC7 and note velocity.
                        // Keep the channel gain at unity; weak MIDI passages are
                        // made audible below without flattening normal accents.
                        openedChannels[index].controlChange(7, 127);
                        openedChannels[index].controlChange(11, 127);
                    }
                }
                synthesizer = openedSynthesizer;
                channels = openedChannels;
                detail = "Ready: " + soundbank.getName() + " / " + piano.getName() + ".";
                state = State.READY;
            }
        } catch (Exception exception) {
            synchronized (deviceLock) {
                closeDevice();
                detail = "SoundFont error: " + exception.getMessage();
                state = State.FAILED;
            }
        }
    }

    private void closeDevice() {
        channels = null;
        if (synthesizer != null) {
            synthesizer.close();
            synthesizer = null;
        }
    }

    private int normalizeVelocity(int velocity) {
        if (velocity <= 0) return 0;
        // The command controls the weakest end of the range. Every value in
        // between is linearly interpolated, keeping crescendos smooth rather
        // than replacing MIDI dynamics with discrete volume steps.
        float minimum = weakestVelocityRatio * 127.0f;
        return Math.clamp(Math.round(minimum + (127.0f - minimum) * velocity / 127.0f), 1, 127);
    }

    private enum State { NOT_LOADED, LOADING, READY, FAILED }
}
