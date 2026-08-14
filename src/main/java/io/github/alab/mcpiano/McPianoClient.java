package io.github.alab.mcpiano;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Collectors;

/** Client controls are intentionally named /pianoviz so /piano build remains a server command. */
public final class McPianoClient implements ClientModInitializer {
    private static final Path DEFAULT_SOUND_FONT = Path.of(
            "E:\\Alab\\codes\\mc_piano\\assets\\sf2\\steinway_concert_piano.sf2");
    private static final PlaybackEngine PLAYER = new PlaybackEngine();
    private static Path midiDirectory;
    private static Path soundFontPath;
    private static MidiSong loaded;
    private static String loadedName;

    @Override
    public void onInitializeClient() {
        midiDirectory = FabricLoader.getInstance().getGameDir().resolve("midi").normalize();
        soundFontPath = DEFAULT_SOUND_FONT;
        // The supplied studio SoundFont is very large, so load it off the game
        // thread while the client starts.  Playback is enabled when loading ends.
        PLAYER.loadSoundFont(soundFontPath);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            PLAYER.tick(client);
            PianoRenderer.tick(client, PLAYER);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("pianoviz")
                        .then(ClientCommands.literal("list").executes(context -> listMidi()))
                        .then(ClientCommands.literal("load")
                                .then(ClientCommands.argument("file", StringArgumentType.greedyString())
                                        .executes(context -> load(StringArgumentType.getString(context, "file")))))
                        .then(ClientCommands.literal("soundfont")
                                .executes(context -> soundFontStatus())
                                .then(ClientCommands.argument("path", StringArgumentType.greedyString())
                                        .executes(context -> loadSoundFont(StringArgumentType.getString(context, "path")))))
                        .then(ClientCommands.literal("dynamics")
                                .executes(context -> dynamicsStatus())
                                .then(ClientCommands.argument("weakest_percent", DoubleArgumentType.doubleArg(0, 100))
                                        .executes(context -> setDynamics(DoubleArgumentType.getDouble(
                                                context, "weakest_percent")))))
                        .then(ClientCommands.literal("play").executes(context -> playAtPlayer())
                                .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                                .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                                        .executes(context -> play(new BlockPos(
                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                IntegerArgumentType.getInteger(context, "z"))))))))
                        .then(ClientCommands.literal("pause").executes(context -> { PLAYER.pause(); info("Paused."); return Command.SINGLE_SUCCESS; }))
                        .then(ClientCommands.literal("resume").executes(context -> { PLAYER.resume(); info("Resumed."); return Command.SINGLE_SUCCESS; }))
                        .then(ClientCommands.literal("stop").executes(context -> { PLAYER.stop(); info("Stopped."); return Command.SINGLE_SUCCESS; }))
                        .then(ClientCommands.literal("seek")
                                .then(ClientCommands.argument("seconds", DoubleArgumentType.doubleArg(0))
                                        .executes(context -> seek(DoubleArgumentType.getDouble(context, "seconds")))))
                        .then(ClientCommands.literal("speed")
                                .then(ClientCommands.argument("multiplier", DoubleArgumentType.doubleArg(0.25, 4.0))
                                        .executes(context -> speed(DoubleArgumentType.getDouble(context, "multiplier")))))
                        .then(ClientCommands.literal("status").executes(context -> status()))
        ));
    }

    private static int listMidi() {
        try {
            Files.createDirectories(midiDirectory);
            String names;
            try (var files = Files.list(midiDirectory)) {
                names = files.filter(Files::isRegularFile).map(Path::getFileName).map(Path::toString)
                        .filter(McPianoClient::isMidi).sorted(Comparator.naturalOrder()).collect(Collectors.joining(", "));
            }
            info(names.isBlank() ? "No MIDI files. Put .mid files in " + midiDirectory : "MIDI: " + names);
            return Command.SINGLE_SUCCESS;
        } catch (IOException exception) {
            error("Cannot list MIDI directory: " + exception.getMessage());
            return 0;
        }
    }

    private static int load(String rawName) {
        try {
            Files.createDirectories(midiDirectory);
            Path candidate = midiDirectory.resolve(commandPath(rawName)).normalize();
            if (!candidate.startsWith(midiDirectory)) throw new IOException("File must be inside the midi directory");
            if (!Files.isRegularFile(candidate) || !isMidi(candidate.getFileName().toString())) throw new IOException("MIDI file not found");
            loaded = MidiParser.parse(candidate);
            loadedName = candidate.getFileName().toString();
            info("Loaded " + loadedName + ": " + loaded.notes().size() + " notes, "
                    + String.format("%.1f", loaded.durationSeconds()) + " seconds.");
            return Command.SINGLE_SUCCESS;
        } catch (IOException exception) {
            error("Could not load MIDI: " + exception.getMessage());
            return 0;
        }
    }

    private static int playAtPlayer() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) { error("Join a world first."); return 0; }
        return play(PianoOrigin.load().orElse(client.player.blockPosition()));
    }

    private static int play(BlockPos origin) {
        if (loaded == null) { error("Load a MIDI first: /pianoviz load <file>"); return 0; }
        if (!PLAYER.isSoundFontReady()) {
            if (!PLAYER.isSoundFontLoading()) PLAYER.loadSoundFont(soundFontPath);
            error("SoundFont is not ready. " + PLAYER.soundFontStatus());
            return 0;
        }
        PianoOrigin.save(origin);
        PLAYER.start(loaded, origin);
        info("Playing " + loadedName + " at " + origin.toShortString() + ".");
        return Command.SINGLE_SUCCESS;
    }

    private static int seek(double seconds) {
        if (loaded == null) { error("No MIDI is loaded."); return 0; }
        PLAYER.seek(seconds);
        info("Seeked to " + String.format("%.2f", PLAYER.now()) + " seconds.");
        return Command.SINGLE_SUCCESS;
    }

    private static int speed(double multiplier) {
        if (!PLAYER.setSpeed(multiplier)) { error("Speed must be between 0.25 and 4.0."); return 0; }
        info("Playback speed: " + multiplier + "x");
        return Command.SINGLE_SUCCESS;
    }

    private static int status() {
        String songName = loadedName == null ? "none" : loadedName;
        info("Song: " + songName + "; time: " + String.format("%.2f", PLAYER.now())
                + "; speed: " + PLAYER.speed() + "x; playing: " + PLAYER.isPlaying()
                + "; weak dynamics: " + String.format("%.0f", PLAYER.weakestVelocityPercent()) + "%"
                + "; SF2: " + PLAYER.soundFontStatus());
        return Command.SINGLE_SUCCESS;
    }

    private static int soundFontStatus() {
        info("SF2: " + PLAYER.soundFontStatus());
        return Command.SINGLE_SUCCESS;
    }

    private static int loadSoundFont(String rawPath) {
        try {
            Path requested = Path.of(commandPath(rawPath)).toAbsolutePath().normalize();
            if (!Files.isRegularFile(requested)) throw new IOException("SF2 file not found");
            soundFontPath = requested;
            PLAYER.loadSoundFont(requested);
            info("Loading SF2 in the background: " + requested.getFileName());
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            error("Could not load SF2: " + exception.getMessage());
            return 0;
        }
    }

    private static int dynamicsStatus() {
        info("Weakest velocity: " + String.format("%.0f", PLAYER.weakestVelocityPercent())
                + "% of the strongest; intermediate velocities are smoothed continuously.");
        return Command.SINGLE_SUCCESS;
    }

    private static int setDynamics(double weakestPercent) {
        PLAYER.setWeakestVelocityPercent(weakestPercent);
        return dynamicsStatus();
    }

    /**
     * greedyString intentionally preserves Windows backslashes.  Strip only
     * matching outer quotes so paths such as "E:\\Studio One\\piano.sf2" work
     * without treating normal Windows backslashes as escape sequences.
     */
    private static String commandPath(String rawPath) throws IOException {
        String path = rawPath.trim();
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            return path.substring(1, path.length() - 1);
        }
        if (path.startsWith("\"") || path.endsWith("\"")) {
            throw new IOException("Path has an unmatched double quote");
        }
        return path;
    }

    private static boolean isMidi(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mid") || lower.endsWith(".midi");
    }

    private static void info(String message) { message(message, false); }
    private static void error(String message) { message(message, true); }
    private static void message(String message, boolean error) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) client.player.sendSystemMessage(Component.literal("[MC Piano] " + message));
    }
}
