package io.github.alab.mcpiano;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Collectors;

/** Server-side entry point: only static blocks are placed in the world. */
public final class McPiano implements ModInitializer {
    private static final ServerPianoPlayback PLAYBACK = new ServerPianoPlayback();
    private static final Path MIDI_DIRECTORY = FabricLoader.getInstance().getGameDir().resolve("midi").normalize();

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(PLAYBACK::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("piano")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.literal("build").executes(context -> {
                            var player = context.getSource().getPlayerOrException();
                            BlockPos origin = player.blockPosition();
                            PianoBuilder.build(player.level(), origin);
                            PianoOrigin.save(origin);
                            context.getSource().sendSuccess(() -> Component.literal("MC Piano built. Use /piano play <midi> "
                                    + "for Replay-recordable animation; /pianoviz play remains local SF2 audio."), false);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("list").executes(context -> listMidi(context.getSource())))
                        .then(Commands.literal("play")
                                .then(Commands.argument("file", StringArgumentType.greedyString())
                                        .executes(context -> play(context.getSource(),
                                                StringArgumentType.getString(context, "file")))))
                        .then(Commands.literal("stop").executes(context -> {
                            PLAYBACK.stop();
                            context.getSource().sendSuccess(() -> Component.literal("MC Piano server animation stopped."), false);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("reset").executes(context -> {
                            PLAYBACK.stop();
                            context.getSource().sendSuccess(() -> Component.literal("MC Piano panel reset."), false);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("status").executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal("MC Piano server animation: "
                                    + PLAYBACK.status()), false);
                            return Command.SINGLE_SUCCESS;
                        }))
        ));
    }

    private static int listMidi(net.minecraft.commands.CommandSourceStack source) {
        try {
            Files.createDirectories(MIDI_DIRECTORY);
            String names;
            try (var files = Files.list(MIDI_DIRECTORY)) {
                names = files.filter(Files::isRegularFile).map(Path::getFileName).map(Path::toString)
                        .filter(McPiano::isMidi).sorted(Comparator.naturalOrder()).collect(Collectors.joining(", "));
            }
            source.sendSuccess(() -> Component.literal(names.isBlank() ? "No MIDI files in " + MIDI_DIRECTORY
                    : "MIDI: " + names), false);
            return Command.SINGLE_SUCCESS;
        } catch (IOException exception) {
            source.sendFailure(Component.literal("Cannot list MIDI files: " + exception.getMessage()));
            return 0;
        }
    }

    private static int play(net.minecraft.commands.CommandSourceStack source, String rawFile) {
        try {
            Files.createDirectories(MIDI_DIRECTORY);
            Path file = MIDI_DIRECTORY.resolve(commandPath(rawFile)).normalize();
            if (!file.startsWith(MIDI_DIRECTORY) || !Files.isRegularFile(file) || !isMidi(file.getFileName().toString())) {
                throw new IOException("MIDI file not found in " + MIDI_DIRECTORY);
            }
            var player = source.getPlayerOrException();
            BlockPos origin = PianoOrigin.load().orElse(player.blockPosition());
            MidiSong song = MidiParser.parse(file);
            PLAYBACK.start(song, player.level(), origin, source.getServer().getTickCount());
            source.sendSuccess(() -> Component.literal("Server animation started: " + file.getFileName()
                    + ". Replay Mod will record the display entities."), false);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("Could not play MIDI: " + exception.getMessage()));
            return 0;
        }
    }

    private static String commandPath(String rawPath) throws IOException {
        String path = rawPath.trim();
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) return path.substring(1, path.length() - 1);
        if (path.startsWith("\"") || path.endsWith("\"")) throw new IOException("Path has an unmatched double quote");
        return path;
    }

    private static boolean isMidi(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mid") || lower.endsWith(".midi");
    }
}
