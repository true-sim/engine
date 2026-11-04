package com.truesim.engine.ui.console;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.console.AssetProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The assets console commands name ({@code spawn models/crate.gltf}), as files under the game's asset folder (the
 * asset cache has no listing of its own): each asset type has its file extensions ({@link #type}); completion lists the
 * matching paths (relative, with {@code /}) under the folder, and a path exists when its file does.
 */
public final class FolderAssets implements AssetProvider {

    private final Path folder;
    private final Map<String, String[]> extensions = new HashMap<>();

    /** Assets under {@code folder}. */
    public FolderAssets(Path folder) {
        this.folder = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
    }

    /** Assets of {@code type} are the files ending in one of {@code fileExtensions} ({@code ".gltf"}); returns this. */
    @AllowAllocation("set up")
    public FolderAssets type(String type, String... fileExtensions) {
        extensions.put(type, fileExtensions.clone());
        return this;
    }

    @Override
    @AllowAllocation("completion, on events")
    public void complete(String type, String prefix, List<String> out) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder, 8)) {
            files.filter(Files::isRegularFile)
                .map(f -> folder.relativize(f).toString().replace('\\', '/'))
                .filter(name -> name.startsWith(prefix) && ofType(type, name))
                .sorted()
                .forEach(out::add);
        } catch (IOException e) {
            // nothing to complete
        }
    }

    @Override
    public boolean exists(String type, String path) {
        Path file = folder.resolve(path).normalize();
        return file.startsWith(folder) && ofType(type, path) && Files.isRegularFile(file);
    }

    private boolean ofType(String type, String name) {
        String[] ends = extensions.get(type);
        if (ends == null) {
            return true; // a type without extensions: any file
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String end : ends) {
            if (lower.endsWith(end.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
