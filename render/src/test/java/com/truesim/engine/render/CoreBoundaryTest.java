package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The core renderer never reaches into what is optional: no class outside {@code render.particles} names particles or
 * scene-effects (the module's {@code scene-effects} dependency is the particles' alone), and no shader outside
 * {@code particles/} imports theirs. Particles move into a library of their own (effects-particles) without the core
 * changing; read from the sources, as the build's working directory has them.
 */
class CoreBoundaryTest {

    private static final Path JAVA = Path.of("src/main/java/com/truesim/engine/render");
    private static final Path SLANG = Path.of("src/main/slang");

    @Test
    void theCoreNamesNoParticlesAndNoSceneEffects() {
        List<String> found = new ArrayList<>();
        for (Path file : files(JAVA, ".java")) {
            if (!JAVA.relativize(file).startsWith("particles")) {
                lines(file, found, "import com.truesim.scene.effects", "com.truesim.engine.render.particles");
            }
        }
        for (Path file : files(SLANG, ".slang")) {
            Path relative = SLANG.relativize(file);
            if (!relative.startsWith("particles") && !relative.startsWith("particle-modules")) {
                lines(file, found, "import particles");
            }
        }
        assertThat(found).as("core sources naming the optional particles").isEmpty();
    }

    private static List<Path> files(Path root, String suffix) {
        assertThat(root).isDirectory();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(suffix)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void lines(Path file, List<String> found, String... needles) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                for (String needle : needles) {
                    if (lines.get(i).contains(needle)) {
                        found.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
