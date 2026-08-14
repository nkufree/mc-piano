package io.github.alab.mcpiano;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persistent, client-only settings shown through Mod Menu. */
public final class PianoClientConfig {
    public static final String DEFAULT_MIDI_DIRECTORY = "midi";
    public static final String DEFAULT_SOUND_FONT = "sf2/steinway_concert_piano.sf2";
    public static final double DEFAULT_WEAKEST_DYNAMICS = 45.0;
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mcpiano-client.properties");

    private String midiDirectoryPath = DEFAULT_MIDI_DIRECTORY;
    private String soundFontPath = DEFAULT_SOUND_FONT;
    private double weakestDynamics = DEFAULT_WEAKEST_DYNAMICS;

    private PianoClientConfig() { }

    public static PianoClientConfig load() {
        PianoClientConfig config = new PianoClientConfig();
        if (!Files.isRegularFile(FILE)) return config;
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(FILE)) {
            values.load(reader);
            config.midiDirectoryPath = values.getProperty("midiDirectoryPath", DEFAULT_MIDI_DIRECTORY).trim();
            config.soundFontPath = values.getProperty("soundFontPath", DEFAULT_SOUND_FONT).trim();
            config.weakestDynamics = clamp(parseDouble(values.getProperty("weakestDynamics"), DEFAULT_WEAKEST_DYNAMICS));
        } catch (IOException ignored) {
            // Keep defaults if a manually edited config cannot be read.
        }
        return config;
    }

    public void save() throws IOException {
        Files.createDirectories(FILE.getParent());
        Properties values = new Properties();
        values.setProperty("midiDirectoryPath", midiDirectoryPath);
        values.setProperty("soundFontPath", soundFontPath);
        values.setProperty("weakestDynamics", Double.toString(weakestDynamics));
        try (Writer writer = Files.newBufferedWriter(FILE)) {
            values.store(writer, "MC Piano client settings");
        }
    }

    public String midiDirectoryPath() { return midiDirectoryPath; }
    public String soundFontPath() { return soundFontPath; }
    public double weakestDynamics() { return weakestDynamics; }
    public void setMidiDirectoryPath(String value) {
        midiDirectoryPath = value == null || value.isBlank() ? DEFAULT_MIDI_DIRECTORY : value.trim();
    }
    public void setSoundFontPath(String value) {
        soundFontPath = value == null || value.isBlank() ? DEFAULT_SOUND_FONT : value.trim();
    }
    public void setWeakestDynamics(double value) { weakestDynamics = clamp(value); }

    public Path resolveSoundFont() {
        return resolveGamePath(soundFontPath, DEFAULT_SOUND_FONT);
    }

    public Path resolveMidiDirectory() {
        return resolveGamePath(midiDirectoryPath, DEFAULT_MIDI_DIRECTORY);
    }

    private static Path resolveGamePath(String configuredPath, String fallback) {
        try {
            Path configured = Path.of(configuredPath);
            return configured.isAbsolute() ? configured.normalize()
                    : FabricLoader.getInstance().getGameDir().resolve(configured).normalize();
        } catch (RuntimeException ignored) {
            return FabricLoader.getInstance().getGameDir().resolve(fallback).normalize();
        }
    }

    private static double parseDouble(String value, double fallback) {
        try { return Double.parseDouble(value); } catch (RuntimeException ignored) { return fallback; }
    }

    private static double clamp(double value) { return Math.clamp(value, 0, 100); }
}
