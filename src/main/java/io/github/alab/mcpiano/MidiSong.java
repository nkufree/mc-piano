package io.github.alab.mcpiano;

import java.util.List;

/** Immutable, seconds-based MIDI timeline consumed by the client playback and renderer. */
public record MidiSong(List<FallingNote> notes, List<TimelineEvent> events, double durationSeconds) {
    public record FallingNote(int note, double startSeconds, double endSeconds, int velocity, int channel) { }

    public record TimelineEvent(double seconds, Type type, int note, int velocity, int channel, int value) {
        public enum Type { NOTE_ON, NOTE_OFF, SUSTAIN }

        /** Note-off precedes a new note-on at the same tick, preventing stuck retriggered keys. */
        public int sortOrder() {
            return switch (type) {
                case NOTE_OFF -> 0;
                case SUSTAIN -> 1;
                case NOTE_ON -> 2;
            };
        }
    }
}
