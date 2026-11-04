package com.truesim.engine.scene.gi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.render.AmbientOcclusion;
import com.truesim.engine.render.Denoiser;
import com.truesim.engine.render.ProbeVolume;
import com.truesim.engine.render.ProbeVolumeLighting;
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
 * Global illumination's techniques against the path-traced references of the GI test scenes (TRUESIM_SCREENSHOTS=true):
 * each scene's real-time image by every technique ({@link #VARIANTS}: no GI, each ambient occlusion, the probe
 * volume, the volume with each occlusion) against its reference ({@link GiError}), the occlusion alone against the
 * occlusion traced with 512 rays a pixel, and the lag of the dynamic scenes' changes (design D3), as tables; each
 * image as a PNG at the reference's exposure. References are read from {@code -PgiReferences=<directory>} (as
 * {@link GiReferences} wrote them) where present, else traced; images go to {@code -PgiOut=<directory>}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class GiEvaluation {

    /** A technique: an ambient occlusion, global illumination, the denoiser of rays. */
    record Variant(String name, AmbientOcclusion.Mode ao, ProbeVolumeLighting.Mode gi, Denoiser.Mode denoiser) {

        void apply(FrameGraph graph) {
            graph.settings().set(AmbientOcclusion.MODE, ao).set(ProbeVolumeLighting.MODE, gi)
                .set(AmbientOcclusion.DENOISER, denoiser);
        }
    }

    static final List<Variant> VARIANTS = List.of(
        new Variant("raster", AmbientOcclusion.Mode.OFF, ProbeVolumeLighting.Mode.OFF, Denoiser.Mode.SVGF),
        new Variant("gtao", AmbientOcclusion.Mode.GTAO, ProbeVolumeLighting.Mode.OFF, Denoiser.Mode.SVGF),
        new Variant("bitmask", AmbientOcclusion.Mode.BITMASK, ProbeVolumeLighting.Mode.OFF, Denoiser.Mode.SVGF),
        new Variant("rays-svgf", AmbientOcclusion.Mode.RAYS, ProbeVolumeLighting.Mode.OFF, Denoiser.Mode.SVGF),
        new Variant("rays-reblur", AmbientOcclusion.Mode.RAYS, ProbeVolumeLighting.Mode.OFF, Denoiser.Mode.REBLUR),
        new Variant("probes", AmbientOcclusion.Mode.OFF, ProbeVolumeLighting.Mode.PROBES, Denoiser.Mode.SVGF),
        new Variant("probes+gtao", AmbientOcclusion.Mode.GTAO, ProbeVolumeLighting.Mode.PROBES, Denoiser.Mode.SVGF),
        new Variant("probes+bitmask", AmbientOcclusion.Mode.BITMASK, ProbeVolumeLighting.Mode.PROBES,
            Denoiser.Mode.SVGF),
        new Variant("probes+rays-svgf", AmbientOcclusion.Mode.RAYS, ProbeVolumeLighting.Mode.PROBES,
            Denoiser.Mode.SVGF),
        new Variant("probes+rays-reblur", AmbientOcclusion.Mode.RAYS, ProbeVolumeLighting.Mode.PROBES,
            Denoiser.Mode.REBLUR));

    /** Frames each image settles in: past the probes' and the denoiser's 30 frames of history. */
    static final int SETTLE = 72;

    @Test
    void everyTechniqueAgainstTheReferences() throws IOException {
        Path out = Files.createDirectories(Path.of(System.getProperty("truesim.gi.out", "build/gi-scenes")));
        String references = System.getProperty("truesim.gi.references");
        int samples = Integer.getInteger("truesim.gi.samples", 4096);
        int width = GiReferences.WIDTH;
        int height = GiReferences.HEIGHT;
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("gi").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
            List<String> errors = new ArrayList<>();
            List<String> occlusion = new ArrayList<>();
            List<String> lags = new ArrayList<>();
            try (GiHarness harness = new GiHarness(device, width, height)) {
                FrameGraph graph = harness.graph();
                GiScenes.forEach(System.getProperty("truesim.gi.scenes"), scene -> {
                    String name = scene[0] + (GiScenes.DYNAMIC.contains(scene[0]) ? "-" + scene[1] : "");
                    int state = Integer.parseInt(scene[1]);
                    float[] reference = reference(harness, references, name, scene[0], state, samples);
                    float exposure = exposure(reference, width, height);
                    harness.realtime(scene[0], state);
                    for (Variant v : VARIANTS) {
                        v.apply(graph);
                        float[] image = harness.render(SETTLE);
                        png(image, width, height, exposure, out.resolve(name + "-" + v.name() + ".png"));
                        errors.add(String.format(Locale.ROOT, "| %s | %s | %s |", name, v.name(),
                            GiError.of(image, reference, width, height)));
                    }
                    occlusion.addAll(occlusion(harness, name, out));
                    graph.settings().set(AmbientOcclusion.MODE, AmbientOcclusion.Mode.OFF);
                });
                for (String dynamic : GiScenes.DYNAMIC) {
                    if (selected(dynamic)) {
                        lags.addAll(lags(harness, dynamic));
                    }
                }
            }
            System.out.printf(Locale.ROOT, "GI techniques against the references, %d x %d, on %s:%n", width, height,
                device.gpu().name());
            System.out.println("| Scene | Technique | Error |");
            System.out.println("|---|---|---|");
            errors.forEach(System.out::println);
            System.out.println();
            System.out.println("Ambient occlusion against 512 rays a pixel (mean absolute difference, 95th percentile):");
            System.out.println("| Scene | Technique | Mean | 95th |");
            System.out.println("|---|---|---|---|");
            occlusion.forEach(System.out::println);
            System.out.println();
            System.out.println("Lag of the dynamic scenes' changes: frames until within 10 % of the change, above the "
                + "settled image's noise (D3); the change and the noise as mean absolute luminance:");
            System.out.println("| Scene | Response | Frames | Change | Noise |");
            System.out.println("|---|---|---|---|---|");
            lags.forEach(System.out::println);
            assertThat(instance.validationErrors()).isZero();
        }
    }

    private static boolean selected(String scene) {
        String names = System.getProperty("truesim.gi.scenes");
        return names == null || names.isBlank() || List.of(names.split(",")).contains(scene);
    }

    /** The reference of {@code name}: read where given, else traced. */
    static float[] reference(GiHarness harness, String directory, String name, String scene, int state, int samples) {
        try {
            if (directory != null) {
                Path file = Path.of(directory, name + "-reference.f32");
                if (Files.exists(file)) {
                    int[] size = new int[2];
                    float[] read = GiError.read(file, size);
                    if (size[0] == harness.width() && size[1] == harness.height()) {
                        return read;
                    }
                }
            }
            return harness.reference(scene, state, samples, 32);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static float exposure(float[] reference, int width, int height) {
        double mean = 0;
        for (int p = 0; p < width * height; p++) {
            mean += GiError.luminance(reference, p);
        }
        mean /= width * height;
        return mean > 0 ? (float) Math.min(16, Math.max(0.25, 0.18 / mean)) : 1;
    }

    static void png(float[] image, int width, int height, float exposure, Path file) {
        try {
            GiError.png(image, width, height, exposure, file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Each occlusion alone against 512 rays a pixel, unfiltered (the exact occlusion at the radius). */
    private static List<String> occlusion(GiHarness harness, String name, Path out) {
        FrameGraph graph = harness.graph();
        int width = harness.width();
        int height = harness.height();
        graph.settings().set(ProbeVolumeLighting.MODE, ProbeVolumeLighting.Mode.OFF)
            .set(AmbientOcclusion.DEBUG, AmbientOcclusion.Show.OCCLUSION)
            .set(AmbientOcclusion.MODE, AmbientOcclusion.Mode.RAYS).set(AmbientOcclusion.RAYS, 512)
            .set(Denoiser.PASSES, 0).set(Denoiser.MAX_FRAMES, 1);
        float[] exact = harness.render(4);
        graph.settings().set(AmbientOcclusion.RAYS, 1).set(Denoiser.PASSES, Denoiser.PASSES.defaultValue())
            .set(Denoiser.MAX_FRAMES, Denoiser.MAX_FRAMES.defaultValue());
        png(exact, width, height, 1, out.resolve(name + "-ao-exact.png"));
        List<String> rows = new ArrayList<>();
        for (Variant v : VARIANTS.subList(1, 5)) {
            v.apply(graph);
            graph.settings().set(ProbeVolumeLighting.MODE, ProbeVolumeLighting.Mode.OFF);
            float[] ao = harness.render(SETTLE);
            png(ao, width, height, 1, out.resolve(name + "-ao-" + v.name() + ".png"));
            double[] differences = new double[width * height];
            double sum = 0;
            for (int p = 0; p < width * height; p++) {
                differences[p] = Math.abs(ao[4 * p] - exact[4 * p]);
                sum += differences[p];
            }
            java.util.Arrays.sort(differences);
            rows.add(String.format(Locale.ROOT, "| %s | %s | %.4f | %.4f |", name, v.name(), sum / differences.length,
                differences[(int) (differences.length * 0.95)]));
        }
        graph.settings().set(AmbientOcclusion.DEBUG, AmbientOcclusion.Show.SCENE);
        return rows;
    }

    /**
     * The probe volume's lag in dynamic scene {@code scene} (design D3): settled in state 0, then changed to state 1;
     * frames until the image is within 10 % of the change from what it settles at (the mean of its last 30 frames,
     * their noise about it allowed for), with the volume told of the change
     * (invalidation), finding it alone (adaptive), and by its history cap alone.
     */
    private static List<String> lags(GiHarness harness, String scene) {
        FrameGraph graph = harness.graph();
        List<String> rows = new ArrayList<>();
        String[] responses = {"told (invalidation)", "found (adaptive)", "history cap alone"};
        for (int r = 0; r < responses.length; r++) {
            graph.settings().set(AmbientOcclusion.MODE, AmbientOcclusion.Mode.OFF)
                .set(ProbeVolumeLighting.MODE, ProbeVolumeLighting.Mode.PROBES)
                .set(ProbeVolume.INVALIDATE, r == 0).set(ProbeVolume.ADAPTIVE, r <= 1);
            harness.realtime(scene, 0);
            float[] before = harness.render(SETTLE);
            harness.state(scene, 1);
            float[][] seen = new float[90][];
            for (int f = 0; f < seen.length; f++) {
                seen[f] = harness.render(1);
            }
            // What it settles at: the mean of the last 30 frames, and their noise about it
            int pixels = harness.width() * harness.height();
            float[] after = new float[seen[0].length];
            int settled = 30;
            for (int f = seen.length - settled; f < seen.length; f++) {
                for (int i = 0; i < after.length; i++) {
                    after[i] += seen[f][i] / settled;
                }
            }
            double noise = 0;
            for (int f = seen.length - settled; f < seen.length; f++) {
                noise += difference(seen[f], after, pixels) / settled;
            }
            double change = difference(before, after, pixels);
            int lag = seen.length + 1;
            for (int f = 0; f < seen.length; f++) {
                if (difference(seen[f], after, pixels) <= 0.1 * change + noise) {
                    lag = f + 1;
                    break;
                }
            }
            rows.add(String.format(Locale.ROOT, "| %s | %s | %d | %.5f | %.5f |", scene, responses[r], lag, change,
                noise));
        }
        graph.settings().set(ProbeVolume.INVALIDATE, true).set(ProbeVolume.ADAPTIVE, true);
        return rows;
    }

    /** The mean absolute luminance difference of two images. */
    static double difference(float[] a, float[] b, int pixels) {
        double sum = 0;
        for (int p = 0; p < pixels; p++) {
            sum += Math.abs(GiError.luminance(a, p) - GiError.luminance(b, p));
        }
        return sum / pixels;
    }
}
