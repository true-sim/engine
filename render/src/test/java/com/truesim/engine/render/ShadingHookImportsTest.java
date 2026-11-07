package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What a shading hook may import (README, "Writing a shading hook"): the hook point's module (hooks), bindless access,
 * the surface and the lights (shading, lights), and its own library's modules: those beside it, under the same
 * directory. Never the engine's shading paths (indirect, meshlets, surfaces, ...): a hook runs inside every one of
 * them, at ray hits and probe hits too, and importing one would tie it to that path. Checked over every source of
 * engine-render declaring a hook.
 */
class ShadingHookImportsTest {

    /** The engine modules a hook may import. */
    static final Set<String> ALLOWED = Set.of("hooks", "bindless", "shading", "lights");

    private static final Pattern IMPORT = Pattern.compile("^\s*(?:__exported\s+)?import\s+([\w.]+)\s*;",
        Pattern.MULTILINE);

    @Test
    void hooksImportOnlyThePointsModulesAndTheirOwn() throws IOException {
        List<String> checked = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/slang"), Path.of("src/test/slang"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".slang")).toList()) {
                    String source = Files.readString(file);
                    if (!source.contains("[ShadingHook(\"")) {
                        continue;
                    }
                    Path dir = root.relativize(file).getParent();
                    String own = dir == null ? "" : dir.toString().replace('\', '.').replace('/', '.') + ".";
                    Matcher m = IMPORT.matcher(source);
                    while (m.find()) {
                        String module = m.group(1);
                        assertThat(ALLOWED.contains(module) || !own.isEmpty() && module.startsWith(own))
                            .as("%s imports %s: a hook imports %s or its own library's modules (%s*)", file, module,
                                ALLOWED, own)
                            .isTrue();
                    }
                    checked.add(file.getFileName().toString());
                }
            }
        }
        assertThat(checked).contains("hook.slang", "testFog.slang", "testGlow.slang", "testTint.slang");
    }
}
