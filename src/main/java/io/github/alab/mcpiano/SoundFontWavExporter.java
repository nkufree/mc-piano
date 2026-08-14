package io.github.alab.mcpiano;

import javax.sound.midi.Instrument;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Patch;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Standalone Gervill renderer, launched with an explicit java.desktop export. */
public final class SoundFontWavExporter {
    private static final float SAMPLE_RATE = 44_100.0f;
    private static final double RELEASE_TAIL_SECONDS = 4.0;

    private SoundFontWavExporter() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: <midi> <sf2> <output.wav> <weakest-dynamics>");
        render(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Double.parseDouble(args[3]));
    }

    static void render(Path midiFile, Path soundFontFile, Path outputFile, double weakestDynamics) throws Exception {
        if (!Files.isRegularFile(midiFile)) throw new IllegalArgumentException("MIDI file not found: " + midiFile);
        if (!Files.isRegularFile(soundFontFile)) throw new IllegalArgumentException("SF2 file not found: " + soundFontFile);
        if (!outputFile.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".wav")) {
            throw new IllegalArgumentException("Output file must end in .wav");
        }
        MidiSong song = MidiParser.parse(midiFile);
        Soundbank soundbank = MidiSystem.getSoundbank(soundFontFile.toFile());
        if (soundbank == null || soundbank.getInstruments().length == 0) {
            throw new IllegalArgumentException("The selected SF2 has no readable instruments");
        }

        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
        Synthesizer synthesizer = createOfflineSynthesizer();
        try {
            AudioInputStream generated = openStream(synthesizer, format);
            Instrument piano = soundbank.getInstruments()[0];
            if (!synthesizer.loadInstrument(piano)) throw new IllegalArgumentException("Could not load the SF2 piano preset");
            Patch patch = piano.getPatch();
            for (MidiChannel channel : synthesizer.getChannels()) {
                if (channel != null) {
                    channel.programChange(patch.getBank(), patch.getProgram());
                    channel.controlChange(7, 127);
                    channel.controlChange(11, 127);
                }
            }

            Receiver receiver = synthesizer.getReceiver();
            try {
                for (MidiSong.TimelineEvent event : song.events()) {
                    ShortMessage message = switch (event.type()) {
                        case NOTE_ON -> shortMessage(ShortMessage.NOTE_ON, event.channel(), event.note(),
                                normalizeVelocity(event.velocity(), weakestDynamics));
                        case NOTE_OFF -> shortMessage(ShortMessage.NOTE_OFF, event.channel(), event.note(), 0);
                        case SUSTAIN -> shortMessage(ShortMessage.CONTROL_CHANGE, event.channel(), 64, event.value());
                    };
                    receiver.send(message, Math.round(event.seconds() * 1_000_000.0));
                }
            } finally {
                receiver.close();
            }

            long frames = Math.max(1, Math.round((song.durationSeconds() + RELEASE_TAIL_SECONDS) * SAMPLE_RATE));
            Files.createDirectories(outputFile.toAbsolutePath().normalize().getParent());
            try (AudioInputStream rendered = new AudioInputStream(generated, format, frames)) {
                AudioSystem.write(rendered, AudioFileFormat.Type.WAVE, outputFile.toFile());
            }
        } finally {
            synthesizer.close();
        }
    }

    private static Synthesizer createOfflineSynthesizer() throws Exception {
        Class<?> implementation = Class.forName("com.sun.media.sound.SoftSynthesizer");
        return (Synthesizer) implementation.getConstructor().newInstance();
    }

    private static AudioInputStream openStream(Synthesizer synthesizer, AudioFormat format) throws Exception {
        Method openStream = synthesizer.getClass().getMethod("openStream", AudioFormat.class, Map.class);
        return (AudioInputStream) openStream.invoke(synthesizer, format, Map.of());
    }

    private static ShortMessage shortMessage(int command, int channel, int data1, int data2) throws Exception {
        ShortMessage message = new ShortMessage();
        message.setMessage(command, Math.clamp(channel, 0, 15), Math.clamp(data1, 0, 127), Math.clamp(data2, 0, 127));
        return message;
    }

    private static int normalizeVelocity(int velocity, double weakestDynamics) {
        if (velocity <= 0) return 0;
        double ratio = Math.clamp(weakestDynamics / 100.0, 0, 1);
        return Math.clamp((int) Math.round(ratio * 127.0 + (127.0 - ratio * 127.0) * velocity / 127.0), 1, 127);
    }
}
