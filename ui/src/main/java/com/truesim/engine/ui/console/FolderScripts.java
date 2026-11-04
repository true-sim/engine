package com.truesim.engine.ui.console;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.console.ScriptSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Console scripts ({@code exec autoexec.cfg}) from a folder (the game's config folder): paths relative to it, never
 * outside it ({@code ..} and absolute paths are refused); completion lists the files under it.
 */
public final class FolderScripts implements ScriptSource {

    private final Path folder;

    /** Scripts in {@code folder}. */
    public FolderScripts(Path folder) {
        this.folder = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
    }

    @Override
    @AllowAllocation("read on exec")
    public String read(String path) {
        Path file = resolve(path);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    @AllowAllocation("completion, on events")
    public void list(String prefix, List<String> out) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder, 4)) {
            files.filter(Files::isRegularFile)
                .map(f -> folder.relativize(f).toString().replace('\\', '/'))
                .filter(name -> name.startsWith(prefix))
                .sorted()
                .forEach(out::add);
        } catch (IOException e) {
            // nothing to complete
        }
    }

    /** {@code path} under the folder, or null outside it. */
    private Path resolve(String path) {
        try {
            Path file = folder.resolve(path).normalize();
            return file.startsWith(folder) ? file : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
