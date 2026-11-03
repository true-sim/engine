package com.truesim.engine.scene.gi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The GI test scenes' reference images (TRUESIM_SCREENSHOTS=true): each scene and state path traced and rendered by
 * the real-time shading of the same view, both as PNGs and the reference as raw floats ({@link GiError#write}) for
 * later stages to compare against, and a table of how far the real-time image is from the reference. Into
 * {@code -PgiOut=<directory>} (build/gi-scenes without); {@code -PgiScenes=a,b} picks scenes, {@code -PgiSamples=n}
 * the samples (4,096). Sponza ({@code -PgiSponza=<Sponza.gltf>}, a local asset never committed) is {@link GiSponza}'s.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class GiReferences {

    static final int WIDTH = 640;
    static final int HEIGHT = 400;

    @Test
    void referencesOfEveryScene() throws IOException {
        Path out = Files.createDirectories(Path.of(System.getProperty("truesim.gi.out", "build/gi-scenes")));
        int samples = Integer.getInteger("truesim.gi.samples", 4096);
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("gi").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
            List<String> table = new ArrayList<>();
            try (GiHarness harness = new GiHarness(device, WIDTH, HEIGHT)) {
                GiScenes.forEach(System.getProperty("truesim.gi.scenes"), scene -> {
                    String name = scene[0] + (GiScenes.DYNAMIC.contains(scene[0]) ? "-" + scene[1] : "");
                    int state = Integer.parseInt(scene[1]);
                    long start = System.nanoTime();
                    float[] reference = harness.reference(scene[0], state, samples, 32);
                    double seconds = (System.nanoTime() - start) / 1e9;
                    float[] raster = harness.raster(scene[0], state);
                    double mean = 0;
                    for (int p = 0; p < WIDTH * HEIGHT; p++) {
                        mean += GiError.luminance(reference, p);
                    }
                    // Both images at the reference's exposure: its mean at mid grey (black ones as they are)
                    float exposure = mean > 0 ? (float) Math.min(16, Math.max(0.25, 0.18 / (mean / (WIDTH * HEIGHT))))
                        : 1;
                    try {
                        GiError.png(reference, WIDTH, HEIGHT, exposure, out.resolve(name + "-reference.png"));
                        GiError.png(raster, WIDTH, HEIGHT, exposure, out.resolve(name + "-raster.png"));
                        GiError.write(reference, WIDTH, HEIGHT, out.resolve(name + "-reference.f32"));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    table.add(String.format(Locale.ROOT, "| %s | %.4f | %.1f s | %s |", name, mean / (WIDTH * HEIGHT),
                        seconds, GiError.of(raster, reference, WIDTH, HEIGHT)));
                });
            }
            System.out.printf(Locale.ROOT, "GI scenes, %d x %d, %d samples, on %s; the real-time image (no GI) against "
                + "the reference:%n", WIDTH, HEIGHT, samples, device.gpu().name());
            System.out.println("| Scene | Reference's mean luminance | Traced in | Real-time against it |");
            System.out.println("|---|---|---|---|");
            table.forEach(System.out::println);
            assertThat(instance.validationErrors()).isZero();
        }
    }
}
