package com.truesim.engine.scene.gi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.render.ReferencePathTracer;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Sponza (the Khronos glTF sample, under its own licence: given by path, {@code -PgiSponza=<Sponza.gltf>}, never
 * committed or copied) as GI's large interior: the sun through the courtyard, a blue sky above, path traced from a few
 * cameras (or the references read from {@code -PgiReferences=<directory>}) beside the real-time image of each view by
 * every technique of {@link GiEvaluation#VARIANTS}, with each one's error, into {@code -PgiOut}
 * (TRUESIM_SCREENSHOTS=true).
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class GiSponza {

    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;

    @Test
    void sponzaFromAFewCameras() throws Exception {
        String file = System.getProperty("truesim.gi.sponza");
        assumeThat(file).as("no Sponza given: -PgiSponza=<Sponza.gltf>").isNotNull();
        Path out = Files.createDirectories(Path.of(System.getProperty("truesim.gi.out", "build/gi-scenes")));
        String references = System.getProperty("truesim.gi.references");
        int samples = Integer.getInteger("truesim.gi.samples", 2048);
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("gi").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
            List<String> table = new ArrayList<>();
            try (SponzaScene sponza = new SponzaScene(device, Path.of(file), WIDTH, HEIGHT)) {
                sponza.graph.settings().set(ReferencePathTracer.SAMPLES, samples).set(ReferencePathTracer.BOUNCES,
                    Integer.getInteger("truesim.gi.bounces", 32));
                for (int c = 0; c < SponzaScene.CAMERAS.length; c++) {
                    String name = "sponza-" + (c + 1);
                    sponza.look(c);
                    float[] traced = null;
                    if (references != null && Files.exists(Path.of(references, name + "-reference.f32"))) {
                        int[] size = new int[2];
                        traced = GiError.read(Path.of(references, name + "-reference.f32"), size);
                        if (size[0] != WIDTH || size[1] != HEIGHT) {
                            traced = null;
                        }
                    }
                    if (traced == null) {
                        // Loads settle first (textures, bottom levels), then the samples from a restart
                        ReferencePathTracer reference = sponza.traced();
                        sponza.frames(60);
                        reference.restart();
                        long start = System.nanoTime();
                        sponza.frames(samples + 2);
                        double seconds = (System.nanoTime() - start) / 1e9;
                        traced = sponza.read();
                        GiError.write(traced, WIDTH, HEIGHT, out.resolve(name + "-reference.f32"));
                        table.add(String.format(Locale.ROOT, "| %s | reference | traced in %.1f s |", name, seconds));
                    }
                    float exposure = GiEvaluation.exposure(traced, WIDTH, HEIGHT);
                    GiError.png(traced, WIDTH, HEIGHT, exposure, out.resolve(name + "-reference.png"));
                    sponza.realtime();
                    for (GiEvaluation.Variant v : GiEvaluation.variants()) {
                        v.apply(sponza.graph);
                        sponza.frames(GiEvaluation.SETTLE);
                        float[] drawn = sponza.read();
                        GiError.png(drawn, WIDTH, HEIGHT, exposure, out.resolve(name + "-" + v.name() + ".png"));
                        table.add(String.format(Locale.ROOT, "| %s | %s | %s |", name, v.name(),
                            GiError.of(drawn, traced, WIDTH, HEIGHT)));
                    }
                }
            }
            System.out.printf(Locale.ROOT, "Sponza, %d x %d, on %s; every technique against the reference:%n", WIDTH,
                HEIGHT, device.gpu().name());
            System.out.println("| View | Technique | Error |");
            System.out.println("|---|---|---|");
            table.forEach(System.out::println);
            assertThat(instance.validationErrors()).isZero();
        }
    }
}
