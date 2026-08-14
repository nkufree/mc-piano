package io.github.alab.mcpiano;

import io.github.alab.mcpiano.mixin.BlockDisplayAccessor;
import io.github.alab.mcpiano.mixin.DisplayAccessor;
import com.mojang.math.Transformation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;

/**
 * Minecraft 26.2 moved to the extraction-based rendering pipeline. Client-only BlockDisplay
 * entities provide the same model rendering without changing a world block or touching chunks.
 */
public final class PianoRenderer {
    public static final double FALL_SPEED = 8.0;
    /** Seven seconds fills the 16:9 visual backboard from the keyboard to its top frame. */
    public static final double PREVIEW_SECONDS = 7.0;
    /** Visual clearance between successive note blocks; deliberately below one block. */
    private static final double NOTE_GAP = 0.08;
    /** A small inset makes each falling note less blocky; luminous edges fill this space. */
    private static final double NOTE_SIDE_INSET = 0.06;
    private static final double EDGE_WIDTH = 0.045;
    private static final double TRACK_INSET = 0.035;
    private static final double HIGHLIGHT_INSET = 0.08;

    private static final Map<String, Display.BlockDisplay> DISPLAYS = new HashMap<>();
    private static int nextEntityId = -1;

    private PianoRenderer() { }

    /** Immediately removes all client-only preview entities. */
    public static void reset() {
        clear();
    }

    public static void tick(Minecraft client, PlaybackEngine engine) {
        if (client.level == null || !engine.shouldRender()) {
            clear();
            return;
        }

        Set<String> wanted = new HashSet<>();
        MidiSong song = engine.song();
        VoicePalette voices = VoicePalette.from(song);
        BlockPos origin = engine.origin();
        double now = engine.now();
        double floor = origin.getY() + 1;
        double ceiling = floor + FALL_SPEED * PREVIEW_SECONDS;
        double noteZ = origin.getZ() + PianoLayout.WHITE_KEY_DEPTH;

        // A restrained lane texture sits against the dark physical backboard.
        // It becomes a little brighter with the active key, without competing
        // with the descending note itself.
        for (PianoLayout.Key key : PianoLayout.keys()) {
            float intensity = engine.intensity(key.midiNote());
            double trackX = origin.getX() + key.x();
            int channel = voices.strongestChannel(engine, key.midiNote());
            BlockState track = intensity > 0.05f
                    ? highlightCore(key.black(), intensity, channel, voices)
                    : (key.black() ? Blocks.STAINED_GLASS.black().defaultBlockState()
                    : Blocks.STAINED_GLASS.gray().defaultBlockState());
            showBox(client, wanted, "track:" + key.midiNote(), track,
                    trackX + TRACK_INSET, floor, noteZ + 0.985,
                    key.width() - TRACK_INSET * 2.0, ceiling - floor, 0.025, intensity > 0.65f);
        }

        for (int index = 0; index < song.notes().size(); index++) {
            MidiSong.FallingNote note = song.notes().get(index);
            if (note.endSeconds() <= now || note.startSeconds() > now + PREVIEW_SECONDS) continue;
            PianoLayout.Key key = PianoLayout.key(note.note());
            if (key == null) continue;
            // Keep both ends attached to their time positions. Once a long note's
            // front reaches the keyboard, only its tail remains above the floor,
            // so the display naturally shortens until note-off.
            double bottom = Math.max(floor, floor + (note.startSeconds() - now) * FALL_SPEED);
            double top = Math.min(ceiling, floor + (note.endSeconds() - now) * FALL_SPEED - NOTE_GAP);
            if (top <= bottom) continue;
            double visibleHeight = top - bottom;
            NoteStyle style = noteStyle(key.black(), note.channel(), voices);
            BlockState state = style.body();
            BlockState outerEdge = style.outerEdge();
            BlockState innerEdge = style.innerEdge();
            // Fall, key highlight, and physical keyboard share this exact X
            // coordinate so narrow half-block black keys remain aligned.
            double noteX = origin.getX() + key.x();
            double visualWidth = key.black() ? key.width() : key.width() * 0.70;
            double visualX = noteX + (key.width() - visualWidth) / 2.0;
            showTransformed(client, wanted, "note:" + index, state,
                    visualX + NOTE_SIDE_INSET, bottom, noteZ,
                    visualWidth - NOTE_SIDE_INSET * 2.0, visibleHeight, false);
            showGlowEdge(client, wanted, "edge:" + index + ":outer:left", outerEdge,
                    visualX, bottom, noteZ, EDGE_WIDTH, visibleHeight);
            showGlowEdge(client, wanted, "edge:" + index + ":inner:left", innerEdge,
                    visualX + EDGE_WIDTH, bottom, noteZ, NOTE_SIDE_INSET - EDGE_WIDTH, visibleHeight);
            showGlowEdge(client, wanted, "edge:" + index + ":outer:right", outerEdge,
                    visualX + visualWidth - EDGE_WIDTH, bottom, noteZ, EDGE_WIDTH, visibleHeight);
            showGlowEdge(client, wanted, "edge:" + index + ":inner:right", innerEdge,
                    visualX + visualWidth - NOTE_SIDE_INSET, bottom, noteZ,
                    NOTE_SIDE_INSET - EDGE_WIDTH, visibleHeight);
            // A thin bright cap makes long-note tails look like energy columns
            // rather than a plain, abruptly cut block.
            showBox(client, wanted, "note:" + index + ":cap", innerEdge,
                    visualX + NOTE_SIDE_INSET, Math.max(bottom, top - 0.075), noteZ,
                    visualWidth - NOTE_SIDE_INSET * 2.0, Math.min(0.075, visibleHeight), 1.0, true);
        }

        for (PianoLayout.Key key : PianoLayout.keys()) {
            float intensity = engine.intensity(key.midiNote());
            if (intensity <= 0) continue;
            int channel = voices.strongestChannel(engine, key.midiNote());
            BlockState glow = highlightCore(key.black(), intensity, channel, voices);
            BlockState edge = highlightEdge(key.black(), intensity, channel, voices);
            int depth = key.black() ? PianoLayout.BLACK_KEY_DEPTH : PianoLayout.WHITE_KEY_DEPTH;
            int startZ = key.black() ? 2 : 0;
            // Glass wraps the complete key volume. Opaque black keys naturally
            // hide the covered portion of a white key, leaving only its exposed
            // top and side surfaces lit.
            double y = origin.getY() + (key.black() ? 1.002 : 0.002);
            double keyHeight = 0.998;
            double highlightX = origin.getX() + key.x();
            double highlightZ = origin.getZ() + startZ;
            // Low, inset core plus a full-bright four-sided rim: the key reads
            // as lit from within instead of being covered by a solid cube.
            showBox(client, wanted, "key:" + key.midiNote() + ":core", glow,
                    highlightX + HIGHLIGHT_INSET, y, highlightZ + HIGHLIGHT_INSET,
                    key.width() - HIGHLIGHT_INSET * 2.0, keyHeight, depth - HIGHLIGHT_INSET * 2.0, intensity > 0.7f);
            showBox(client, wanted, "key:" + key.midiNote() + ":left", edge,
                    highlightX, y, highlightZ, HIGHLIGHT_INSET, keyHeight, depth, true);
            showBox(client, wanted, "key:" + key.midiNote() + ":right", edge,
                    highlightX + key.width() - HIGHLIGHT_INSET, y, highlightZ,
                    HIGHLIGHT_INSET, keyHeight, depth, true);
            showBox(client, wanted, "key:" + key.midiNote() + ":front", edge,
                    highlightX + HIGHLIGHT_INSET, y, highlightZ,
                    key.width() - HIGHLIGHT_INSET * 2.0, keyHeight, HIGHLIGHT_INSET, true);
            showBox(client, wanted, "key:" + key.midiNote() + ":back", edge,
                    highlightX + HIGHLIGHT_INSET, y, highlightZ + depth - HIGHLIGHT_INSET,
                    key.width() - HIGHLIGHT_INSET * 2.0, keyHeight, HIGHLIGHT_INSET, true);
        }

        int pedalX = origin.getX() + PianoLayout.PEDAL_X;
        for (int part = 0; part < 5; part++) {
            show(client, wanted, "pedal:" + part, Blocks.GOLD_BLOCK.defaultBlockState(), pedalX + part,
                    origin.getY() + (engine.isSustainDown() ? 0 : part), origin.getZ() + 2);
        }
        show(client, wanted, "pedal:light", (engine.isSustainDown() ? Blocks.CONCRETE.lime()
                : Blocks.CONCRETE.gray()).defaultBlockState(), pedalX + 2, origin.getY() + 1, origin.getZ() + 4);
        removeUnwanted(wanted);
    }

    private static void show(Minecraft client, Set<String> wanted, String key, BlockState state,
                             double x, double y, double z) {
        showScaled(client, wanted, key, state, x, y, z, 1.0);
    }

    private static void showScaled(Minecraft client, Set<String> wanted, String key, BlockState state,
                                   double x, double y, double z, double height) {
        showTransformed(client, wanted, key, state, x, y, z, 1.0, height, false);
    }

    private static void showGlowEdge(Minecraft client, Set<String> wanted, String key, BlockState state,
                                     double x, double y, double z, double width, double height) {
        showTransformed(client, wanted, key, state, x, y, z, width, height, true);
    }

    private static void showTransformed(Minecraft client, Set<String> wanted, String key, BlockState state,
                                        double x, double y, double z, double width, double height, boolean bright) {
        showBox(client, wanted, key, state, x, y, z, width, height, 1.0, bright);
    }

    private static void showBox(Minecraft client, Set<String> wanted, String key, BlockState state,
                                double x, double y, double z, double width, double height, double depth,
                                boolean bright) {
        wanted.add(key);
        Display.BlockDisplay display = DISPLAYS.get(key);
        if (display == null || display.isRemoved()) {
            display = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, client.level);
            display.setId(nextEntityId--);
            DisplayAccessor accessor = (DisplayAccessor) display;
            accessor.mcpiano$setTransformationInterpolationDuration(0);
            accessor.mcpiano$setTransformationInterpolationDelay(0);
            accessor.mcpiano$setPosRotInterpolationDuration(0);
            client.level.addEntity(display);
            DISPLAYS.put(key, display);
        }
        ((BlockDisplayAccessor) display).mcpiano$setBlockState(state);
        ((DisplayAccessor) display).mcpiano$setTransformation(new Transformation(
                new Vector3f(), new Quaternionf(),
                new Vector3f((float) width, (float) height, (float) depth), new Quaternionf()));
        ((DisplayAccessor) display).mcpiano$setBrightnessOverride(bright ? Brightness.FULL_BRIGHT : null);
        display.setPos(x, y, z);
    }

    private static BlockState activeCore(boolean blackKey, float intensity) {
        if (blackKey) return (intensity > 0.72f ? Blocks.STAINED_GLASS.magenta() : Blocks.STAINED_GLASS.purple())
                .defaultBlockState();
        return (intensity > 0.72f ? Blocks.STAINED_GLASS.orange() : Blocks.STAINED_GLASS.yellow())
                .defaultBlockState();
    }

    private static BlockState activeEdge(boolean blackKey, float intensity) {
        if (blackKey) return (intensity > 0.72f ? Blocks.STAINED_GLASS.white() : Blocks.STAINED_GLASS.magenta())
                .defaultBlockState();
        return (intensity > 0.72f ? Blocks.STAINED_GLASS.white() : Blocks.STAINED_GLASS.orange())
                .defaultBlockState();
    }

    private static BlockState highlightCore(boolean blackKey, float intensity, int channel, VoicePalette voices) {
        return voices.multipleVoices() ? noteStyle(blackKey, channel, voices).outerEdge()
                : activeCore(blackKey, intensity);
    }

    private static BlockState highlightEdge(boolean blackKey, float intensity, int channel, VoicePalette voices) {
        if (!voices.multipleVoices()) return activeEdge(blackKey, intensity);
        NoteStyle style = noteStyle(blackKey, channel, voices);
        return intensity > 0.72f ? style.innerEdge() : style.outerEdge();
    }

    private static NoteStyle noteStyle(boolean blackKey, int channel, VoicePalette voices) {
        if (!voices.multipleVoices()) {
            return blackKey
                    ? new NoteStyle(Blocks.CONCRETE.purple().defaultBlockState(),
                    Blocks.STAINED_GLASS.magenta().defaultBlockState(), Blocks.STAINED_GLASS.purple().defaultBlockState())
                    : new NoteStyle(Blocks.CONCRETE.yellow().defaultBlockState(),
                    Blocks.STAINED_GLASS.orange().defaultBlockState(), Blocks.STAINED_GLASS.white().defaultBlockState());
        }
        return switch (voices.slotFor(channel) % 6) {
            case 0 -> new NoteStyle(Blocks.CONCRETE.blue().defaultBlockState(),
                    Blocks.STAINED_GLASS.blue().defaultBlockState(), Blocks.STAINED_GLASS.lightBlue().defaultBlockState());
            case 1 -> new NoteStyle(Blocks.CONCRETE.red().defaultBlockState(),
                    Blocks.STAINED_GLASS.red().defaultBlockState(), Blocks.STAINED_GLASS.orange().defaultBlockState());
            case 2 -> new NoteStyle(Blocks.CONCRETE.green().defaultBlockState(),
                    Blocks.STAINED_GLASS.green().defaultBlockState(), Blocks.STAINED_GLASS.lime().defaultBlockState());
            case 3 -> new NoteStyle(Blocks.CONCRETE.cyan().defaultBlockState(),
                    Blocks.STAINED_GLASS.cyan().defaultBlockState(), Blocks.STAINED_GLASS.lightBlue().defaultBlockState());
            case 4 -> new NoteStyle(Blocks.CONCRETE.magenta().defaultBlockState(),
                    Blocks.STAINED_GLASS.magenta().defaultBlockState(), Blocks.STAINED_GLASS.pink().defaultBlockState());
            default -> new NoteStyle(Blocks.CONCRETE.orange().defaultBlockState(),
                    Blocks.STAINED_GLASS.orange().defaultBlockState(), Blocks.STAINED_GLASS.yellow().defaultBlockState());
        };
    }

    private record NoteStyle(BlockState body, BlockState outerEdge, BlockState innerEdge) { }

    /** Maps the MIDI channels actually containing notes to a stable, compact palette. */
    private record VoicePalette(int[] slotByChannel, int voiceCount) {
        static VoicePalette from(MidiSong song) {
            boolean[] used = new boolean[16];
            for (MidiSong.FallingNote note : song.notes()) {
                if (note.channel() >= 0 && note.channel() < used.length) used[note.channel()] = true;
            }
            int[] slots = new int[16];
            Arrays.fill(slots, -1);
            int count = 0;
            for (int channel = 0; channel < slots.length; channel++) {
                if (used[channel]) slots[channel] = count++;
            }
            return new VoicePalette(slots, count);
        }

        boolean multipleVoices() { return voiceCount > 1; }

        int slotFor(int channel) {
            return channel >= 0 && channel < slotByChannel.length && slotByChannel[channel] >= 0
                    ? slotByChannel[channel] : 0;
        }

        int strongestChannel(PlaybackEngine engine, int note) {
            int winner = -1;
            float strongest = -1;
            for (int channel = 0; channel < slotByChannel.length; channel++) {
                if (slotByChannel[channel] < 0) continue;
                float intensity = engine.intensity(note, channel);
                if (intensity > strongest) {
                    strongest = intensity;
                    winner = channel;
                }
            }
            return winner >= 0 ? winner : 0;
        }
    }

    private static void removeUnwanted(Set<String> wanted) {
        Iterator<Map.Entry<String, Display.BlockDisplay>> iterator = DISPLAYS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Display.BlockDisplay> entry = iterator.next();
            if (!wanted.contains(entry.getKey())) {
                entry.getValue().discard();
                iterator.remove();
            }
        }
    }

    private static void clear() {
        for (Display.BlockDisplay display : DISPLAYS.values()) display.discard();
        DISPLAYS.clear();
    }
}
