package io.github.alab.mcpiano;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Persists the last built piano position so client playback targets the actual keyboard. */
public final class PianoOrigin {
    private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("config").resolve("mcpiano-origin.txt");

    private PianoOrigin() { }

    public static boolean save(BlockPos origin) {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, origin.getX() + " " + origin.getY() + " " + origin.getZ());
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    public static Optional<BlockPos> load() {
        try {
            String[] parts = Files.readString(FILE).trim().split("\\s+");
            if (parts.length != 3) return Optional.empty();
            return Optional.of(new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
        } catch (IOException | NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
