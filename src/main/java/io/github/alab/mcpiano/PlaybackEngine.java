package io.github.alab.mcpiano;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Arrays;

/** Clock and MIDI state machine. It never changes world blocks. */
public final class PlaybackEngine {
    private MidiSong song;
    private BlockPos origin = BlockPos.ZERO;
    private final float[] keyIntensity = new float[128];
    private final int[] heldKeyCounts = new int[128];
    private final float[][] channelKeyIntensity = new float[16][128];
    private final int[][] heldChannelKeyCounts = new int[16][128];
    private final boolean[] sustainChannels = new boolean[16];
    private final SoundFontPlayer soundFont = new SoundFontPlayer();
    private boolean sustainDown;
    private boolean playing;
    private boolean paused;
    private double speed = 1.0;
    private double offsetSeconds;
    private long startedAtNanos;
    private long lastVisualUpdateNanos = System.nanoTime();
    private int eventIndex;

    public MidiSong song() { return song; }
    public BlockPos origin() { return origin; }
    public boolean isSustainDown() { return sustainDown; }
    public boolean isPlaying() { return playing && !paused; }
    public boolean shouldRender() { return song != null && (playing || paused); }
    public double speed() { return speed; }
    public boolean isSoundFontReady() { return soundFont.isReady(); }
    public boolean isSoundFontLoading() { return soundFont.isLoading(); }
    public String soundFontStatus() { return soundFont.status(); }
    public void loadSoundFont(java.nio.file.Path file) { soundFont.loadAsync(file); }
    public void setWeakestVelocityPercent(double percent) { soundFont.setWeakestVelocityPercent(percent); }
    public double weakestVelocityPercent() { return soundFont.weakestVelocityPercent(); }
    public float intensity(int note) { return note >= 0 && note < keyIntensity.length ? keyIntensity[note] : 0; }
    public boolean isKeyHeld(int note) { return note >= 0 && note < heldKeyCounts.length && heldKeyCounts[note] > 0; }
    public float intensity(int note, int channel) {
        return note >= 0 && note < 128 && channel >= 0 && channel < 16 ? channelKeyIntensity[channel][note] : 0;
    }

    public double now() {
        if (!playing || paused) return offsetSeconds;
        return Math.min(song.durationSeconds(), offsetSeconds + (System.nanoTime() - startedAtNanos) / 1_000_000_000.0 * speed);
    }

    public void start(MidiSong newSong, BlockPos newOrigin) {
        soundFont.stopAll();
        song = newSong;
        origin = newOrigin;
        playing = true;
        paused = false;
        offsetSeconds = 0;
        startedAtNanos = System.nanoTime();
        lastVisualUpdateNanos = startedAtNanos;
        // Re-send events at the exact song start.  rebuildState normally only
        // reconstructs visuals, but skipping time-zero Note On events would
        // make the first chord silent in the SF2 synth.
        rebuildState(0, true);
    }

    public void stop() {
        soundFont.stopAll();
        playing = false;
        paused = false;
        offsetSeconds = 0;
        eventIndex = 0;
        sustainDown = false;
        Arrays.fill(sustainChannels, false);
        Arrays.fill(keyIntensity, 0);
        Arrays.fill(heldKeyCounts, 0);
        clearChannelHighlights();
    }

    public void pause() {
        if (playing && !paused) {
            offsetSeconds = now();
            paused = true;
            soundFont.stopAll();
        }
    }

    public void resume() {
        if (playing && paused) {
            startedAtNanos = System.nanoTime();
            paused = false;
        }
    }

    public void seek(double seconds) {
        if (song == null) return;
        soundFont.stopAll();
        offsetSeconds = Math.clamp(seconds, 0, song.durationSeconds());
        startedAtNanos = System.nanoTime();
        rebuildState(offsetSeconds, false);
    }

    public boolean setSpeed(double requested) {
        if (requested < 0.25 || requested > 4.0) return false;
        double current = song == null ? 0 : now();
        speed = requested;
        offsetSeconds = current;
        startedAtNanos = System.nanoTime();
        return true;
    }

    public void tick(Minecraft client) {
        // Client ticks continue on the title screen.  Do not leave Gervill's
        // independent audio thread sounding after the player disconnects.
        if (client.level == null || client.player == null) {
            if (playing || paused) stop();
            return;
        }
        if (!playing || paused || song == null) return;
        decayReleasedKeyHighlights();
        double current = now();
        while (eventIndex < song.events().size() && song.events().get(eventIndex).seconds() <= current) {
            apply(song.events().get(eventIndex++), client, true);
        }
        if (current >= song.durationSeconds() && eventIndex >= song.events().size()) {
            playing = false;
            Arrays.fill(keyIntensity, 0);
            clearChannelHighlights();
            soundFont.stopAll();
            sustainDown = false;
            Arrays.fill(sustainChannels, false);
        }
    }

    private void rebuildState(double seconds, boolean replayAudio) {
        eventIndex = 0;
        sustainDown = false;
        Arrays.fill(sustainChannels, false);
        Arrays.fill(keyIntensity, 0);
        Arrays.fill(heldKeyCounts, 0);
        clearChannelHighlights();
        if (song == null) return;
        while (eventIndex < song.events().size() && song.events().get(eventIndex).seconds() <= seconds) {
            apply(song.events().get(eventIndex++), null, replayAudio);
        }
        lastVisualUpdateNanos = System.nanoTime();
    }

    private void decayReleasedKeyHighlights() {
        long currentNanos = System.nanoTime();
        float elapsedSeconds = (currentNanos - lastVisualUpdateNanos) / 1_000_000_000f;
        lastVisualUpdateNanos = currentNanos;
        for (int note = PianoLayout.LOWEST_NOTE; note <= PianoLayout.HIGHEST_NOTE; note++) {
            float strongest = 0;
            for (int channel = 0; channel < 16; channel++) {
                if (heldChannelKeyCounts[channel][note] == 0) {
                    // A short release tail avoids harsh one-frame highlighter flicker.
                    channelKeyIntensity[channel][note] = Math.max(0,
                            channelKeyIntensity[channel][note] - elapsedSeconds / 0.16f);
                }
                strongest = Math.max(strongest, channelKeyIntensity[channel][note]);
            }
            keyIntensity[note] = strongest;
        }
    }

    private void clearChannelHighlights() {
        for (int channel = 0; channel < 16; channel++) {
            Arrays.fill(channelKeyIntensity[channel], 0);
            Arrays.fill(heldChannelKeyCounts[channel], 0);
        }
    }

    private void apply(MidiSong.TimelineEvent event, Minecraft client, boolean sound) {
        switch (event.type()) {
            case NOTE_ON -> {
                if (event.note() >= PianoLayout.LOWEST_NOTE && event.note() <= PianoLayout.HIGHEST_NOTE) {
                    int channel = event.channel();
                    heldKeyCounts[event.note()]++;
                    float intensity = 0.35f + 0.65f * event.velocity() / 127f;
                    if (channel >= 0 && channel < 16) {
                        heldChannelKeyCounts[channel][event.note()]++;
                        channelKeyIntensity[channel][event.note()] = Math.max(
                                channelKeyIntensity[channel][event.note()], intensity);
                    }
                    keyIntensity[event.note()] = Math.max(keyIntensity[event.note()], intensity);
                    if (sound) soundFont.noteOn(channel, event.note(), event.velocity());
                }
            }
            case NOTE_OFF -> {
                if (event.note() >= PianoLayout.LOWEST_NOTE && event.note() <= PianoLayout.HIGHEST_NOTE) {
                    heldKeyCounts[event.note()] = Math.max(0, heldKeyCounts[event.note()] - 1);
                    if (event.channel() >= 0 && event.channel() < 16) {
                        heldChannelKeyCounts[event.channel()][event.note()] = Math.max(0,
                                heldChannelKeyCounts[event.channel()][event.note()] - 1);
                    }
                    if (sound) soundFont.noteOff(event.channel(), event.note());
                }
            }
            case SUSTAIN -> {
                int channel = event.channel();
                if (channel >= 0 && channel < sustainChannels.length) {
                    sustainChannels[channel] = event.value() >= 64;
                }
                sustainDown = false;
                for (boolean channelSustain : sustainChannels) sustainDown |= channelSustain;
                if (sound) soundFont.controlChange(event.channel(), 64, event.value());
            }
        }
    }
}
