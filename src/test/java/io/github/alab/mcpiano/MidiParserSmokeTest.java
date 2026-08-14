package io.github.alab.mcpiano;

import java.nio.file.Path;

/** Dependency-free regression check for the supplied Standard MIDI file. */
public final class MidiParserSmokeTest {
    private MidiParserSmokeTest() { }

    public static void main(String[] args) throws Exception {
        autoPedalRegression();
        retriggerRegression();
        independentTrackVoicesRegression();
        if (PianoLayout.keys().size() != 88 || PianoLayout.key(21).x() != 102
                || PianoLayout.key(108).x() != 0) {
            throw new AssertionError("The 88-key coordinate map is invalid");
        }
        MidiSong song = MidiParser.parse(Path.of(args[0]));
        if (song.notes().isEmpty() || song.durationSeconds() <= 0) {
            throw new AssertionError("The supplied MIDI produced no playable piano notes");
        }
        if (song.notes().stream().anyMatch(note -> note.endSeconds() <= note.startSeconds())) {
            throw new AssertionError("MIDI parser produced a zero/negative duration note");
        }
        System.out.printf("OK: %d notes, %d timeline events, %.3f seconds%n", song.notes().size(),
                song.events().size(), song.durationSeconds());
    }

    private static void autoPedalRegression() throws Exception {
        // Format 0, 480 ticks/quarter, one C4 lasting two 4/4 bars and no CC64.
        byte[] midi = {
                'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, 1, (byte) 0xE0,
                'M', 'T', 'r', 'k', 0, 0, 0, 13,
                0, (byte) 0x90, 60, 64,
                (byte) 0x9E, 0, (byte) 0x80, 60, 0,
                0, (byte) 0xFF, 0x2F, 0
        };
        MidiSong generated = MidiParser.parse(midi);
        long pedalEvents = generated.events().stream()
                .filter(event -> event.type() == MidiSong.TimelineEvent.Type.SUSTAIN).count();
        if (pedalEvents != 4) {
            throw new AssertionError("Expected one down/up pedal cycle per 4/4 bar, got " + pedalEvents);
        }
    }

    private static void retriggerRegression() throws Exception {
        // A second Note On for C4 arrives before the first Note Off.  Exported
        // piano MIDI commonly uses this as a retrigger, so the first note must
        // close at tick 480 rather than extending all the way to song end.
        byte[] midi = {
                'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, 1, (byte) 0xE0,
                'M', 'T', 'r', 'k', 0, 0, 0, 18,
                0, (byte) 0x90, 60, 64,
                (byte) 0x83, 0x60, (byte) 0x90, 60, 64,
                (byte) 0x83, 0x60, (byte) 0x80, 60, 0,
                0, (byte) 0xFF, 0x2F, 0
        };
        MidiSong generated = MidiParser.parse(midi);
        if (generated.notes().size() != 2) {
            throw new AssertionError("Expected two distinct retriggered notes, got " + generated.notes().size());
        }
        MidiSong.FallingNote first = generated.notes().getFirst();
        if (Math.abs(first.endSeconds() - 0.5) > 0.001) {
            throw new AssertionError("Retriggered note should close at tick 480, got " + first.endSeconds());
        }
    }

    private static void independentTrackVoicesRegression() throws Exception {
        // Format 1: both tracks use channel 0 and C4 at the same time, but
        // track 0 releases after one beat while track 1 holds for two.  The
        // falling blocks must retain both independent lengths.
        byte[] midi = {
                'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 1, 0, 2, 1, (byte) 0xE0,
                'M', 'T', 'r', 'k', 0, 0, 0, 13,
                0, (byte) 0x90, 60, 64,
                (byte) 0x83, 0x60, (byte) 0x80, 60, 0,
                0, (byte) 0xFF, 0x2F, 0,
                'M', 'T', 'r', 'k', 0, 0, 0, 13,
                0, (byte) 0x90, 60, 64,
                (byte) 0x87, 0x40, (byte) 0x80, 60, 0,
                0, (byte) 0xFF, 0x2F, 0
        };
        MidiSong generated = MidiParser.parse(midi);
        if (generated.notes().size() != 2) {
            throw new AssertionError("Expected two independent same-pitch notes, got " + generated.notes().size());
        }
        double shortest = generated.notes().stream().mapToDouble(MidiSong.FallingNote::endSeconds).min().orElseThrow();
        double longest = generated.notes().stream().mapToDouble(MidiSong.FallingNote::endSeconds).max().orElseThrow();
        if (Math.abs(shortest - 0.5) > 0.001 || Math.abs(longest - 1.0) > 0.001) {
            throw new AssertionError("Same-pitch track voices did not retain their distinct durations");
        }
    }
}
