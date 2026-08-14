package io.github.alab.mcpiano;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Starts a separate Java process for Gervill's module-protected offline renderer. */
public final class SoundFontWavExport {
    private static final AtomicBoolean EXPORTING = new AtomicBoolean();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mc-piano-wav-export");
        thread.setDaemon(true);
        return thread;
    });

    private SoundFontWavExport() { }

    public static boolean exportAsync(Path midi, Path soundFont, Path output, double weakestDynamics,
                                      Consumer<String> completed, Consumer<String> failed) {
        if (!EXPORTING.compareAndSet(false, true)) return false;
        EXECUTOR.execute(() -> {
            try {
                Files.createDirectories(output.toAbsolutePath().normalize().getParent());
                List<String> command = List.of(
                        Path.of(System.getProperty("java.home"), "bin", javaExecutable()).toString(),
                        "--add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED",
                        "-cp", ownCodeSource().toString(),
                        SoundFontWavExporter.class.getName(),
                        midi.toAbsolutePath().normalize().toString(),
                        soundFont.toAbsolutePath().normalize().toString(),
                        output.toAbsolutePath().normalize().toString(),
                        Double.toString(weakestDynamics));
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                String detail;
                try (var stream = process.getInputStream()) {
                    detail = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
                }
                if (process.waitFor() != 0) throw new IOException(detail.isBlank() ? "renderer process failed" : detail);
                completed.accept(output.toAbsolutePath().normalize().toString());
            } catch (Exception exception) {
                failed.accept(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            } finally {
                EXPORTING.set(false);
            }
        });
        return true;
    }

    private static Path ownCodeSource() throws Exception {
        return Path.of(SoundFontWavExport.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static String javaExecutable() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
    }
}
