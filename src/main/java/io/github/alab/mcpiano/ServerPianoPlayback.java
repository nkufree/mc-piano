package io.github.alab.mcpiano;

import com.mojang.math.Transformation;
import io.github.alab.mcpiano.mixin.BlockDisplayAccessor;
import io.github.alab.mcpiano.mixin.DisplayAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Server-authoritative visual playback.  Every temporary BlockDisplay is a
 * real server entity, therefore entity spawn, transform and removal packets
 * are recorded by Replay Mod instead of existing only on the live client.
 */
public final class ServerPianoPlayback {
    private static final double FALL_SPEED = 8.0;
    private static final double PREVIEW_SECONDS = 7.0;
    private static final double NOTE_GAP = 0.08;
    private static final double NOTE_SIDE_INSET = 0.06;
    private static final double EDGE_WIDTH = 0.045;
    private static final double TRACK_INSET = 0.035;
    private static final double HIGHLIGHT_INSET = 0.08;
    private static final String RUNTIME_TAG = "mcpiano_runtime";

    private final Map<String, Display.BlockDisplay> displays = new HashMap<>();
    private final float[] keyIntensity = new float[128];
    private final float[][] channelIntensity = new float[16][128];
    private final int[][] heldChannels = new int[16][128];
    private final boolean[] sustainChannels = new boolean[16];

    private MidiSong song;
    private ServerLevel level;
    private BlockPos origin = BlockPos.ZERO;
    private VoicePalette voices = VoicePalette.empty();
    private long startedAtTick;
    private long lastTick;
    private int eventIndex;
    private boolean sustainDown;
    private boolean playing;

    public boolean isPlaying() { return playing; }
    public String status() { return playing && song != null ? String.format("%.2f / %.2f seconds", now(), song.durationSeconds()) : "stopped"; }

    public void start(MidiSong newSong, ServerLevel newLevel, BlockPos newOrigin, long currentTick) {
        stop();
        song = newSong;
        level = newLevel;
        origin = newOrigin;
        voices = VoicePalette.from(newSong);
        startedAtTick = currentTick;
        lastTick = currentTick;
        eventIndex = 0;
        playing = true;
    }

    public void stop() {
        clearDisplays();
        song = null;
        level = null;
        playing = false;
        eventIndex = 0;
        sustainDown = false;
        Arrays.fill(keyIntensity, 0);
        Arrays.fill(sustainChannels, false);
        for (int channel = 0; channel < 16; channel++) {
            Arrays.fill(channelIntensity[channel], 0);
            Arrays.fill(heldChannels[channel], 0);
        }
    }

    /** Removes tagged displays even when they were created before this runtime map existed. */
    public void reset(ServerLevel resetLevel, BlockPos resetOrigin) {
        stop();
        double floor = resetOrigin.getY() - 2;
        double ceiling = resetOrigin.getY() + 2 + FALL_SPEED * PREVIEW_SECONDS;
        AABB bounds = new AABB(resetOrigin.getX() - 3, floor, resetOrigin.getZ() - 1,
                resetOrigin.getX() + PianoLayout.width() + 3, ceiling,
                resetOrigin.getZ() + PianoLayout.WHITE_KEY_DEPTH + 4);
        resetLevel.getEntities((net.minecraft.world.entity.Entity) null, bounds,
                        entity -> entity.entityTags().contains(RUNTIME_TAG))
                .forEach(entity -> entity.discard());
    }

    public void tick(MinecraftServer server) {
        if (!playing || song == null || level == null) return;
        lastTick = server.getTickCount();
        double now = now(server.getTickCount());
        while (eventIndex < song.events().size() && song.events().get(eventIndex).seconds() <= now) {
            apply(song.events().get(eventIndex++));
        }
        decayHighlights();
        render(now);
        if (now >= song.durationSeconds() && eventIndex >= song.events().size()) stop();
    }

    private double now() { return now(lastTick); }
    /**
     * Begin one preview-height early so time-zero notes enter at the top of the
     * board instead of appearing at the keyboard on the first recorded tick.
     */
    private double now(long currentTick) { return (currentTick - startedAtTick) / 20.0 - PREVIEW_SECONDS; }

    private void apply(MidiSong.TimelineEvent event) {
        int channel = event.channel();
        switch (event.type()) {
            case NOTE_ON -> {
                if (!inRange(event.note())) return;
                float intensity = 0.35f + 0.65f * event.velocity() / 127f;
                if (channel >= 0 && channel < 16) {
                    heldChannels[channel][event.note()]++;
                    channelIntensity[channel][event.note()] = Math.max(channelIntensity[channel][event.note()], intensity);
                }
                keyIntensity[event.note()] = Math.max(keyIntensity[event.note()], intensity);
            }
            case NOTE_OFF -> {
                if (inRange(event.note()) && channel >= 0 && channel < 16) {
                    heldChannels[channel][event.note()] = Math.max(0, heldChannels[channel][event.note()] - 1);
                }
            }
            case SUSTAIN -> {
                if (channel >= 0 && channel < 16) sustainChannels[channel] = event.value() >= 64;
                sustainDown = false;
                for (boolean down : sustainChannels) sustainDown |= down;
            }
        }
    }

    private void decayHighlights() {
        for (int note = PianoLayout.LOWEST_NOTE; note <= PianoLayout.HIGHEST_NOTE; note++) {
            float strongest = 0;
            for (int channel = 0; channel < 16; channel++) {
                if (heldChannels[channel][note] == 0) {
                    channelIntensity[channel][note] = Math.max(0, channelIntensity[channel][note] - 0.05f / 0.16f);
                }
                strongest = Math.max(strongest, channelIntensity[channel][note]);
            }
            keyIntensity[note] = strongest;
        }
    }

    private void render(double now) {
        Set<String> wanted = new HashSet<>();
        double floor = origin.getY() + 1;
        double ceiling = floor + FALL_SPEED * PREVIEW_SECONDS;
        double noteZ = origin.getZ() + PianoLayout.WHITE_KEY_DEPTH;

        for (PianoLayout.Key key : PianoLayout.keys()) {
            float intensity = keyIntensity[key.midiNote()];
            int channel = voices.strongestChannel(channelIntensity, key.midiNote());
            BlockState track = intensity > 0.05f ? highlightCore(key.black(), intensity, channel)
                    : (key.black() ? Blocks.STAINED_GLASS.black() : Blocks.STAINED_GLASS.gray()).defaultBlockState();
            showBox(wanted, "track:" + key.midiNote(), track,
                    origin.getX() + key.x() + TRACK_INSET, floor, noteZ + 0.985,
                    key.width() - TRACK_INSET * 2.0, ceiling - floor, 0.025, intensity > 0.65f);
        }

        for (int index = 0; index < song.notes().size(); index++) {
            MidiSong.FallingNote note = song.notes().get(index);
            if (note.endSeconds() <= now || note.startSeconds() > now + PREVIEW_SECONDS) continue;
            PianoLayout.Key key = PianoLayout.key(note.note());
            if (key == null) continue;
            double bottom = Math.max(floor, floor + (note.startSeconds() - now) * FALL_SPEED);
            double top = Math.min(ceiling, floor + (note.endSeconds() - now) * FALL_SPEED - NOTE_GAP);
            if (top <= bottom) continue;
            double height = top - bottom;
            NoteStyle style = noteStyle(key.black(), note.channel());
            double noteX = origin.getX() + key.x();
            double visualWidth = key.black() ? key.width() : key.width() * 0.70;
            double visualX = noteX + (key.width() - visualWidth) / 2.0;
            showBox(wanted, "note:" + index, style.body(), visualX + NOTE_SIDE_INSET, bottom, noteZ,
                    visualWidth - NOTE_SIDE_INSET * 2.0, height, 1.0, false);
            showBox(wanted, "edge:" + index + ":outer:left", style.outerEdge(), visualX, bottom, noteZ, EDGE_WIDTH, height, 1.0, true);
            showBox(wanted, "edge:" + index + ":inner:left", style.innerEdge(), visualX + EDGE_WIDTH, bottom, noteZ, NOTE_SIDE_INSET - EDGE_WIDTH, height, 1.0, true);
            showBox(wanted, "edge:" + index + ":outer:right", style.outerEdge(), visualX + visualWidth - EDGE_WIDTH, bottom, noteZ, EDGE_WIDTH, height, 1.0, true);
            showBox(wanted, "edge:" + index + ":inner:right", style.innerEdge(), visualX + visualWidth - NOTE_SIDE_INSET, bottom, noteZ, NOTE_SIDE_INSET - EDGE_WIDTH, height, 1.0, true);
            showBox(wanted, "note:" + index + ":cap", style.innerEdge(), visualX + NOTE_SIDE_INSET,
                    Math.max(bottom, top - 0.075), noteZ, visualWidth - NOTE_SIDE_INSET * 2.0,
                    Math.min(0.075, height), 1.0, true);
        }

        for (PianoLayout.Key key : PianoLayout.keys()) {
            float intensity = keyIntensity[key.midiNote()];
            if (intensity <= 0) continue;
            int channel = voices.strongestChannel(channelIntensity, key.midiNote());
            BlockState glow = highlightCore(key.black(), intensity, channel);
            BlockState edge = highlightEdge(key.black(), intensity, channel);
            int depth = key.black() ? PianoLayout.BLACK_KEY_DEPTH : PianoLayout.WHITE_KEY_DEPTH;
            int startZ = key.black() ? 2 : 0;
            double y = origin.getY() + (key.black() ? 1.002 : 0.002);
            double x = origin.getX() + key.x();
            double z = origin.getZ() + startZ;
            showBox(wanted, "key:" + key.midiNote() + ":core", glow, x + HIGHLIGHT_INSET, y, z + HIGHLIGHT_INSET,
                    key.width() - HIGHLIGHT_INSET * 2.0, 0.998, depth - HIGHLIGHT_INSET * 2.0, intensity > 0.7f);
            showBox(wanted, "key:" + key.midiNote() + ":left", edge, x, y, z, HIGHLIGHT_INSET, 0.998, depth, true);
            showBox(wanted, "key:" + key.midiNote() + ":right", edge, x + key.width() - HIGHLIGHT_INSET, y, z, HIGHLIGHT_INSET, 0.998, depth, true);
            showBox(wanted, "key:" + key.midiNote() + ":front", edge, x + HIGHLIGHT_INSET, y, z, key.width() - HIGHLIGHT_INSET * 2.0, 0.998, HIGHLIGHT_INSET, true);
            showBox(wanted, "key:" + key.midiNote() + ":back", edge, x + HIGHLIGHT_INSET, y, z + depth - HIGHLIGHT_INSET, key.width() - HIGHLIGHT_INSET * 2.0, 0.998, HIGHLIGHT_INSET, true);
        }

        int pedalX = origin.getX() + PianoLayout.PEDAL_X;
        for (int part = 0; part < 5; part++) {
            showBox(wanted, "pedal:" + part, Blocks.GOLD_BLOCK.defaultBlockState(), pedalX + part,
                    origin.getY() + (sustainDown ? 0 : part), origin.getZ() + 2, 1, 1, 1, false);
        }
        showBox(wanted, "pedal:light", (sustainDown ? Blocks.CONCRETE.lime() : Blocks.CONCRETE.gray()).defaultBlockState(),
                pedalX + 2, origin.getY() + 1, origin.getZ() + 4, 1, 1, 1, false);
        removeUnwanted(wanted);
    }

    private void showBox(Set<String> wanted, String key, BlockState state, double x, double y, double z,
                         double width, double height, double depth, boolean bright) {
        wanted.add(key);
        Display.BlockDisplay display = displays.get(key);
        if (display == null || display.isRemoved()) {
            display = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
            display.addTag(RUNTIME_TAG);
            DisplayAccessor accessor = (DisplayAccessor) display;
            // Server updates a falling note every tick.  Explicit zero-duration
            // interpolation makes the shortened scale and position arrive in
            // the same tick for both a live client and Replay Mod playback.
            accessor.mcpiano$setTransformationInterpolationDuration(0);
            accessor.mcpiano$setTransformationInterpolationDelay(0);
            accessor.mcpiano$setPosRotInterpolationDuration(0);
            level.addFreshEntity(display);
            displays.put(key, display);
        }
        ((BlockDisplayAccessor) display).mcpiano$setBlockState(state);
        ((DisplayAccessor) display).mcpiano$setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                new Vector3f((float) width, (float) height, (float) depth), new Quaternionf()));
        ((DisplayAccessor) display).mcpiano$setBrightnessOverride(bright ? Brightness.FULL_BRIGHT : null);
        display.setPos(x, y, z);
    }

    private void removeUnwanted(Set<String> wanted) {
        Iterator<Map.Entry<String, Display.BlockDisplay>> iterator = displays.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Display.BlockDisplay> entry = iterator.next();
            if (!wanted.contains(entry.getKey())) {
                entry.getValue().discard();
                iterator.remove();
            }
        }
    }

    private void clearDisplays() {
        for (Display.BlockDisplay display : displays.values()) display.discard();
        displays.clear();
    }

    private static boolean inRange(int note) { return note >= PianoLayout.LOWEST_NOTE && note <= PianoLayout.HIGHEST_NOTE; }

    private BlockState highlightCore(boolean black, float intensity, int channel) {
        return voices.multipleVoices() ? noteStyle(black, channel).outerEdge() : activeCore(black, intensity);
    }
    private BlockState highlightEdge(boolean black, float intensity, int channel) {
        if (!voices.multipleVoices()) return activeEdge(black, intensity);
        NoteStyle style = noteStyle(black, channel);
        return intensity > 0.72f ? style.innerEdge() : style.outerEdge();
    }
    private static BlockState activeCore(boolean black, float intensity) {
        return (black ? (intensity > 0.72f ? Blocks.STAINED_GLASS.magenta() : Blocks.STAINED_GLASS.purple())
                : (intensity > 0.72f ? Blocks.STAINED_GLASS.orange() : Blocks.STAINED_GLASS.yellow())).defaultBlockState();
    }
    private static BlockState activeEdge(boolean black, float intensity) {
        return (black ? (intensity > 0.72f ? Blocks.STAINED_GLASS.white() : Blocks.STAINED_GLASS.magenta())
                : (intensity > 0.72f ? Blocks.STAINED_GLASS.white() : Blocks.STAINED_GLASS.orange())).defaultBlockState();
    }
    private NoteStyle noteStyle(boolean black, int channel) {
        if (!voices.multipleVoices()) return black
                ? new NoteStyle(Blocks.CONCRETE.purple().defaultBlockState(), Blocks.STAINED_GLASS.magenta().defaultBlockState(), Blocks.STAINED_GLASS.purple().defaultBlockState())
                : new NoteStyle(Blocks.CONCRETE.yellow().defaultBlockState(), Blocks.STAINED_GLASS.orange().defaultBlockState(), Blocks.STAINED_GLASS.white().defaultBlockState());
        return switch (voices.slotFor(channel) % 6) {
            case 0 -> new NoteStyle(Blocks.CONCRETE.blue().defaultBlockState(), Blocks.STAINED_GLASS.blue().defaultBlockState(), Blocks.STAINED_GLASS.lightBlue().defaultBlockState());
            case 1 -> new NoteStyle(Blocks.CONCRETE.red().defaultBlockState(), Blocks.STAINED_GLASS.red().defaultBlockState(), Blocks.STAINED_GLASS.orange().defaultBlockState());
            case 2 -> new NoteStyle(Blocks.CONCRETE.green().defaultBlockState(), Blocks.STAINED_GLASS.green().defaultBlockState(), Blocks.STAINED_GLASS.lime().defaultBlockState());
            case 3 -> new NoteStyle(Blocks.CONCRETE.cyan().defaultBlockState(), Blocks.STAINED_GLASS.cyan().defaultBlockState(), Blocks.STAINED_GLASS.lightBlue().defaultBlockState());
            case 4 -> new NoteStyle(Blocks.CONCRETE.magenta().defaultBlockState(), Blocks.STAINED_GLASS.magenta().defaultBlockState(), Blocks.STAINED_GLASS.pink().defaultBlockState());
            default -> new NoteStyle(Blocks.CONCRETE.orange().defaultBlockState(), Blocks.STAINED_GLASS.orange().defaultBlockState(), Blocks.STAINED_GLASS.yellow().defaultBlockState());
        };
    }

    private record NoteStyle(BlockState body, BlockState outerEdge, BlockState innerEdge) { }
    private record VoicePalette(int[] slotByChannel, int voiceCount) {
        static VoicePalette empty() { int[] slots = new int[16]; Arrays.fill(slots, -1); return new VoicePalette(slots, 0); }
        static VoicePalette from(MidiSong song) {
            VoicePalette result = empty();
            int[] slots = result.slotByChannel.clone();
            int count = 0;
            for (MidiSong.FallingNote note : song.notes()) {
                int channel = note.channel();
                if (channel >= 0 && channel < slots.length && slots[channel] < 0) slots[channel] = count++;
            }
            return new VoicePalette(slots, count);
        }
        boolean multipleVoices() { return voiceCount > 1; }
        int slotFor(int channel) { return channel >= 0 && channel < slotByChannel.length && slotByChannel[channel] >= 0 ? slotByChannel[channel] : 0; }
        int strongestChannel(float[][] intensity, int note) {
            int winner = 0;
            float strongest = -1;
            for (int channel = 0; channel < slotByChannel.length; channel++) {
                if (slotByChannel[channel] >= 0 && intensity[channel][note] > strongest) {
                    strongest = intensity[channel][note]; winner = channel;
                }
            }
            return winner;
        }
    }
}
