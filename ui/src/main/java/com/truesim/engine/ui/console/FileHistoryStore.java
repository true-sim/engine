package com.truesim.engine.ui.console;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.console.HistoryStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The console's history in a folder, by default the user's settings folder of the app
 * ({@code SettingsFile.folder(engine.id())}): one UTF-8 file per app id, {@code console-history-<id>.txt}, a command
 * line per line (lines with breaks are not kept). Written to a temporary file and moved over the old one, so a crash
 * mid-write keeps the last history. Read when the console opens, written after each command run.
 */
public final class FileHistoryStore implements HistoryStore {

    private final Path folder;

    /** A store in {@code folder} (made when first written). */
    public FileHistoryStore(Path folder) {
        this.folder = Objects.requireNonNull(folder, "folder");
    }

    /** The file of {@code appId}. */
    @AllowAllocation("on a command run")
    public Path file(String appId) {
        return folder.resolve("console-history-" + appId.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt");
    }

    @Override
    @AllowAllocation("read when the console is made")
    public List<String> load(String appId) {
        Path file = file(appId);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (IOException e) {
            return List.of(); // an unreadable history is an empty one
        }
    }

    @Override
    @AllowAllocation("written after a command run")
    public void save(String appId, List<String> lines) {
        Path file = file(appId);
        try {
            Files.createDirectories(folder);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            List<String> kept = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (line.indexOf('\n') < 0 && line.indexOf('\r') < 0) {
                    kept.add(line);
                }
            }
            Files.write(temporary, kept, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the console history " + file, e);
        }
    }
}
