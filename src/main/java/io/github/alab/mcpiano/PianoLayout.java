package io.github.alab.mcpiano;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed 88-key geometry for the rear visualizer view. White keys establish
 * the X grid; a black key is centred on the boundary between its adjacent
 * white keys, rather than being assigned an artificial semitone column.
 */
public final class PianoLayout {
    public static final int LOWEST_NOTE = 21;
    public static final int HIGHEST_NOTE = 108;
    public static final int WHITE_KEY_WIDTH = 2;
    public static final int WHITE_KEY_DEPTH = 6;
    public static final int BLACK_KEY_DEPTH = 4;
    public static final int KEYBOARD_WIDTH = 104;
    /** Pedal module coordinates, chosen for the right side when viewing from the backboard. */
    public static final int PEDAL_X = -8;
    public static final int PEDAL_HOUSING_START_X = -9;

    private static final List<Key> KEYS;
    private static final Map<Integer, Key> BY_NOTE;

    static {
        List<Key> keys = new ArrayList<>();
        Map<Integer, Key> byNote = new HashMap<>();
        int whiteIndex = 0;
        for (int note = LOWEST_NOTE; note <= HIGHEST_NOTE; note++) {
            boolean black = isBlackKey(note);
            double unmirroredX = black
                    ? whiteIndex * WHITE_KEY_WIDTH - 0.5
                    : whiteIndex * WHITE_KEY_WIDTH;
            int width = black ? 1 : WHITE_KEY_WIDTH;
            // All consumers use this one mirrored coordinate system. This is
            // the right-to-left layout seen from the visualizer/backboard side.
            double x = KEYBOARD_WIDTH - unmirroredX - width;
            Key key = new Key(note, x, width, black);
            keys.add(key);
            byNote.put(note, key);
            if (!black) whiteIndex++;
        }
        KEYS = Collections.unmodifiableList(keys);
        BY_NOTE = Collections.unmodifiableMap(byNote);
    }

    private PianoLayout() { }

    public static boolean isBlackKey(int midiNote) {
        return switch (Math.floorMod(midiNote, 12)) {
            case 1, 3, 6, 8, 10 -> true;
            default -> false;
        };
    }

    public static Key key(int midiNote) { return BY_NOTE.get(midiNote); }
    public static List<Key> keys() { return KEYS; }
    public static int width() { return KEYBOARD_WIDTH; }

    public record Key(int midiNote, double x, int width, boolean black) { }
}
