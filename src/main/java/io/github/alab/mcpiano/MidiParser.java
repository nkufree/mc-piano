package io.github.alab.mcpiano;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Small dependency-free Standard MIDI File type 0/1 parser. */
public final class MidiParser {
    private static final int DEFAULT_TEMPO_US_PER_QUARTER = 500_000;
    /** Brief visible/audible lift between automatically generated pedal bars. */
    private static final double AUTO_PEDAL_RELEASE_SECONDS = 0.12;

    private MidiParser() { }

    public static MidiSong parse(Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    public static MidiSong parse(byte[] data) throws IOException {
        Cursor cursor = new Cursor(data);
        if (cursor.readInt() != 0x4D546864) throw new IOException("Not a Standard MIDI file (missing MThd)");
        int headerSize = cursor.readInt();
        if (headerSize < 6) throw new IOException("Invalid MIDI header");
        int format = cursor.readUnsignedShort();
        int tracks = cursor.readUnsignedShort();
        int division = cursor.readUnsignedShort();
        cursor.skip(headerSize - 6);
        if (format > 1) throw new IOException("Only MIDI formats 0 and 1 are supported");
        if ((division & 0x8000) != 0) throw new IOException("SMPTE-timed MIDI is not supported");
        if (division == 0) throw new IOException("MIDI ticks-per-quarter-note cannot be zero");

        List<RawEvent> raw = new ArrayList<>();
        List<Tempo> tempos = new ArrayList<>();
        List<TimeSignature> timeSignatures = new ArrayList<>();
        tempos.add(new Tempo(0, DEFAULT_TEMPO_US_PER_QUARTER));
        timeSignatures.add(new TimeSignature(0, 4, 4));
        for (int track = 0; track < tracks; track++) readTrack(cursor, raw, tempos, timeSignatures);

        TempoMap tempoMap = new TempoMap(tempos, division);
        raw.sort(Comparator.comparingLong(RawEvent::tick).thenComparingInt(RawEvent::order));
        List<MidiSong.TimelineEvent> events = new ArrayList<>();
        List<MidiSong.FallingNote> notes = new ArrayList<>();
        Map<Integer, ArrayDeque<RawEvent>> held = new HashMap<>();
        Set<Integer> noteChannels = new TreeSet<>();
        long finalTick = 0;
        for (RawEvent event : raw) {
            finalTick = Math.max(finalTick, event.tick);
            double time = tempoMap.secondsAt(event.tick);
            if (event.kind == Kind.ON) {
                noteChannels.add(event.channel);
                held.computeIfAbsent(event.channel * 128 + event.note, ignored -> new ArrayDeque<>()).addLast(event);
                events.add(new MidiSong.TimelineEvent(time, MidiSong.TimelineEvent.Type.NOTE_ON,
                        event.note, event.value, event.channel, event.value));
            } else if (event.kind == Kind.OFF) {
                ArrayDeque<RawEvent> starts = held.get(event.channel * 128 + event.note);
                if (starts != null && !starts.isEmpty()) {
                    RawEvent start = starts.removeLast();
                    notes.add(new MidiSong.FallingNote(start.note, tempoMap.secondsAt(start.tick), time,
                            start.value, start.channel));
                }
                events.add(new MidiSong.TimelineEvent(time, MidiSong.TimelineEvent.Type.NOTE_OFF,
                        event.note, 0, event.channel, 0));
            } else if (event.kind == Kind.SUSTAIN) {
                events.add(new MidiSong.TimelineEvent(time, MidiSong.TimelineEvent.Type.SUSTAIN,
                        0, 0, event.channel, event.value));
            }
        }
        double duration = tempoMap.secondsAt(finalTick);
        if (raw.stream().noneMatch(event -> event.kind == Kind.SUSTAIN)) {
            addOneBarSustain(events, timeSignatures, tempoMap, division, finalTick, noteChannels);
        }
        for (ArrayDeque<RawEvent> starts : held.values()) {
            while (!starts.isEmpty()) {
                RawEvent start = starts.removeLast();
                notes.add(new MidiSong.FallingNote(start.note, tempoMap.secondsAt(start.tick), duration,
                        start.value, start.channel));
            }
        }
        notes.removeIf(note -> note.note() < PianoLayout.LOWEST_NOTE || note.note() > PianoLayout.HIGHEST_NOTE
                || note.endSeconds() <= note.startSeconds());
        notes.sort(Comparator.comparingDouble(MidiSong.FallingNote::startSeconds));
        events.sort(Comparator.comparingDouble(MidiSong.TimelineEvent::seconds)
                .thenComparingInt(MidiSong.TimelineEvent::sortOrder));
        return new MidiSong(List.copyOf(notes), List.copyOf(events), duration);
    }

    /**
     * Adds a full-bar pedal cycle only when the source never supplied CC64.
     * At each boundary the previous pedal is released before the next one is
     * pressed, matching a pianist changing pedal between bars.
     */
    private static void addOneBarSustain(List<MidiSong.TimelineEvent> events,
                                         List<TimeSignature> timeSignatures, TempoMap tempoMap,
                                         int division, long finalTick, Set<Integer> noteChannels) {
        if (finalTick <= 0) return;
        if (noteChannels.isEmpty()) noteChannels = Set.of(0);
        timeSignatures.sort(Comparator.comparingLong(TimeSignature::tick));
        int signatureIndex = 0;
        TimeSignature signature = timeSignatures.getFirst();
        while (signatureIndex + 1 < timeSignatures.size()
                && timeSignatures.get(signatureIndex + 1).tick() == 0) {
            signature = timeSignatures.get(++signatureIndex);
        }

        long barStart = 0;
        addAutoPedalEvent(events, tempoMap.secondsAt(0), noteChannels, 127);
        while (barStart < finalTick) {
            while (signatureIndex + 1 < timeSignatures.size()
                    && timeSignatures.get(signatureIndex + 1).tick() <= barStart) {
                signature = timeSignatures.get(++signatureIndex);
            }
            long ticksPerBar = Math.max(1L, (long) division * 4L * signature.numerator()
                    / signature.denominator());
            long barEnd = Math.min(finalTick, barStart + ticksPerBar);
            if (signatureIndex + 1 < timeSignatures.size()) {
                long changeTick = timeSignatures.get(signatureIndex + 1).tick();
                if (changeTick > barStart && changeTick < barEnd) barEnd = changeTick;
            }
            addAutoPedalEvent(events, tempoMap.secondsAt(barEnd), noteChannels, 0);
            if (barEnd < finalTick) {
                double pressTime = Math.min(tempoMap.secondsAt(finalTick),
                        tempoMap.secondsAt(barEnd) + AUTO_PEDAL_RELEASE_SECONDS);
                addAutoPedalEvent(events, pressTime, noteChannels, 127);
            }
            barStart = barEnd;
        }
    }

    private static void addAutoPedalEvent(List<MidiSong.TimelineEvent> events, double seconds,
                                          Set<Integer> channels, int value) {
        for (int channel : channels) {
            events.add(new MidiSong.TimelineEvent(seconds, MidiSong.TimelineEvent.Type.SUSTAIN,
                    0, 0, channel, value));
        }
    }

    private static void readTrack(Cursor file, List<RawEvent> out, List<Tempo> tempos,
                                  List<TimeSignature> timeSignatures) throws IOException {
        if (file.readInt() != 0x4D54726B) throw new IOException("Expected MTrk chunk");
        int length = file.readInt();
        int end = file.position() + length;
        if (length < 0 || end > file.length()) throw new IOException("Truncated MIDI track");
        long tick = 0;
        int runningStatus = -1;
        while (file.position() < end) {
            tick += file.readVarInt(end);
            int first = file.readUnsignedByte(end);
            int status;
            int data1 = -1;
            if (first >= 0x80) {
                status = first;
                if (status < 0xF0) runningStatus = status;
            } else {
                if (runningStatus < 0) throw new IOException("MIDI uses running status before a status byte");
                status = runningStatus;
                data1 = first;
            }
            if (status == 0xFF) {
                int type = file.readUnsignedByte(end);
                int metaLength = file.readVarInt(end);
                if (type == 0x51 && metaLength == 3) {
                    int tempo = (file.readUnsignedByte(end) << 16) | (file.readUnsignedByte(end) << 8)
                            | file.readUnsignedByte(end);
                    if (tempo > 0) tempos.add(new Tempo(tick, tempo));
                } else if (type == 0x58 && metaLength == 4) {
                    int numerator = file.readUnsignedByte(end);
                    int denominatorPower = file.readUnsignedByte(end);
                    file.skipChecked(2, end); // MIDI clocks-per-click and 32nd-notes-per-quarter
                    if (numerator > 0 && denominatorPower >= 0 && denominatorPower <= 7) {
                        timeSignatures.add(new TimeSignature(tick, numerator, 1 << denominatorPower));
                    }
                } else {
                    file.skipChecked(metaLength, end);
                }
                continue;
            }
            if (status == 0xF0 || status == 0xF7) {
                file.skipChecked(file.readVarInt(end), end);
                continue;
            }
            if (status >= 0xF0) throw new IOException("Unsupported system MIDI status: " + status);
            int command = status & 0xF0;
            int channel = status & 0x0F;
            if (data1 < 0) data1 = file.readUnsignedByte(end);
            int data2 = (command == 0xC0 || command == 0xD0) ? -1 : file.readUnsignedByte(end);
            if (command == 0x90 && data2 > 0) out.add(new RawEvent(tick, Kind.ON, data1, data2, channel));
            else if (command == 0x80 || (command == 0x90 && data2 == 0)) out.add(new RawEvent(tick, Kind.OFF, data1, 0, channel));
            else if (command == 0xB0 && data1 == 64) out.add(new RawEvent(tick, Kind.SUSTAIN, 0, data2, channel));
        }
        if (file.position() != end) throw new IOException("Invalid MIDI track length");
    }

    private enum Kind { ON, OFF, SUSTAIN }
    private record RawEvent(long tick, Kind kind, int note, int value, int channel) {
        int order() { return kind == Kind.OFF ? 0 : kind == Kind.SUSTAIN ? 1 : 2; }
    }
    private record Tempo(long tick, int microsPerQuarter) { }
    private record TimeSignature(long tick, int numerator, int denominator) { }

    private static final class TempoMap {
        private final List<Segment> segments = new ArrayList<>();
        private final int division;

        TempoMap(List<Tempo> tempos, int division) {
            this.division = division;
            tempos.sort(Comparator.comparingLong(Tempo::tick));
            long previousTick = 0;
            int tempo = DEFAULT_TEMPO_US_PER_QUARTER;
            double seconds = 0;
            segments.add(new Segment(0, seconds, tempo));
            for (Tempo change : tempos) {
                if (change.tick < previousTick) continue;
                seconds += (change.tick - previousTick) * tempo / 1_000_000.0 / division;
                previousTick = change.tick;
                tempo = change.microsPerQuarter;
                if (segments.getLast().tick == change.tick) segments.set(segments.size() - 1, new Segment(change.tick, seconds, tempo));
                else segments.add(new Segment(change.tick, seconds, tempo));
            }
        }

        double secondsAt(long tick) {
            Segment result = segments.getFirst();
            for (Segment segment : segments) {
                if (segment.tick > tick) break;
                result = segment;
            }
            return result.seconds + (tick - result.tick) * result.tempo / 1_000_000.0 / division;
        }
        private record Segment(long tick, double seconds, int tempo) { }
    }

    private static final class Cursor {
        private final ByteBuffer bytes;
        Cursor(byte[] data) { bytes = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN); }
        int length() { return bytes.limit(); }
        int position() { return bytes.position(); }
        int readInt() throws IOException { ensure(4, length()); return bytes.getInt(); }
        int readUnsignedShort() throws IOException { ensure(2, length()); return Short.toUnsignedInt(bytes.getShort()); }
        int readUnsignedByte(int end) throws IOException { ensure(1, end); return Byte.toUnsignedInt(bytes.get()); }
        int readVarInt(int end) throws IOException {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int next = readUnsignedByte(end);
                value = (value << 7) | (next & 0x7F);
                if ((next & 0x80) == 0) return value;
            }
            throw new IOException("MIDI variable-length integer exceeds four bytes");
        }
        void skip(int count) throws IOException { skipChecked(count, length()); }
        void skipChecked(int count, int end) throws IOException { if (count < 0) throw new IOException("Negative length"); ensure(count, end); bytes.position(bytes.position() + count); }
        void ensure(int count, int end) throws IOException { if (bytes.position() + count > end) throw new IOException("Truncated MIDI data"); }
    }
}
