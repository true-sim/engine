package com.truesim.engine.scene.gi;

import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.AmbientOcclusion;
import com.truesim.engine.render.Denoiser;
import com.truesim.engine.render.ProbeVolume;
import com.truesim.engine.render.ProbeVolumeLighting;
import com.truesim.engine.render.RadianceCascades;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * What global illumination costs (TRUESIM_BENCHMARK=true, {@code -PgiSponza=<Sponza.gltf>}): Sponza from its first
 * camera, the visibility buffer, traced sun shadows, at 1920 x 1080 and 3840 x 2160, by every technique; and the probe
 * volume's update by its rays and cascades. Configurations in turns, rounds after a warm-up round, each round's median
 * of the GPU's pass timings; ranges of the rounds' medians. "Frame": every pass; "AO": the ambient occlusion's passes
 * (its denoiser's included); "probes": the volume's update (invalidation, rays, hit shading, blending) and its lighting
 * passes; "shading": the visibility buffer's shading pass, where the volume is sampled.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class GiBenchmark {

    private static final int ROUNDS = 3;
    private static final int WARMUP = 1;
    private static final int SETTLE = 40;
    private static final int TIMED = 30;

    private record Setup(String name, Consumer<FrameGraph> apply) {
    }

    private static Setup variant(GiEvaluation.Variant v) {
        return new Setup(v.name(), v::apply);
    }

    private static Setup volume(String name, int rays, int cascades) {
        return new Setup(name, g -> {
            AmbientOcclusion.SLOT.choose(g.settings(), null);
            ProbeVolumeLighting.SLOT.choose(g.settings(), ProbeVolumeLighting.PROBES).set(ProbeVolume.RAYS, rays)
                .set(ProbeVolume.CASCADES, cascades);
        });
    }

    private static Setup cascades(String name, float spacing, int directions) {
        return new Setup(name, g -> {
            AmbientOcclusion.SLOT.choose(g.settings(), null);
            ProbeVolumeLighting.SLOT.choose(g.settings(), ProbeVolumeLighting.CASCADES)
                .set(RadianceCascades.SPACING, spacing).set(RadianceCascades.DIRECTIONS, directions);
        });
    }

    /** Global illumination of {@code kind} alone, {@code setup} applied after. */
    private static Setup gi(String name, TechniqueKind<?, ProbeVolumeLighting, Technique> kind,
        java.util.function.Consumer<FrameGraph> setup) {
        return new Setup(name, g -> {
            AmbientOcclusion.SLOT.choose(g.settings(), null);
            ProbeVolumeLighting.SLOT.choose(g.settings(), kind);
            setup.accept(g);
        });
    }

    private record Sample(double frame, double span, double ao, double probes, double shading) {
    }

    @Test
    void globalIlluminationCosts() throws Exception {
        String file = System.getProperty("truesim.gi.sponza");
        assumeThat(file).as("no Sponza given: -PgiSponza=<Sponza.gltf>").isNotNull();
        List<Setup> techniques = new ArrayList<>();
        GiEvaluation.variants().forEach(v -> techniques.add(variant(v)));
        for (GiEvaluation.Variant v : GiEvaluation.variants()) {
            if (v.ao() != null) {
                techniques.add(new Setup(v.name() + ", half", g -> {
                    v.apply(g);
                    g.settings().techniques().set(g.settings(), AmbientOcclusion.SLOT, "resolution",
                        AmbientOcclusion.Resolution.HALF);
                }));
            }
        }
        techniques.add(gi("probes, graphics queue", ProbeVolumeLighting.PROBES,
            g -> g.settings().set(ProbeVolume.ASYNC, false)));
        techniques.add(gi("cascades, graphics queue", ProbeVolumeLighting.CASCADES,
            g -> g.settings().set(ProbeVolume.ASYNC, false)));
        techniques.add(gi("cascades, no reach", ProbeVolumeLighting.CASCADES,
            g -> g.settings().set(RadianceCascades.REACH, 0f)));
        techniques.add(gi("probes, no light samples", ProbeVolumeLighting.PROBES,
            g -> g.settings().set(ProbeVolume.EMITTER_SAMPLES, 0)));
        for (float budget : new float[] {0.5f, 0.25f}) {
            techniques.add(gi("probes, budget " + budget, ProbeVolumeLighting.PROBES,
                g -> g.settings().set(ProbeVolume.BUDGET, budget)));
        }
        List<Setup> volumes = List.of(volume("probes, 64 rays, 3 cascades", 64, 3),
            volume("probes, 128 rays, 3 cascades", 128, 3), volume("probes, 256 rays, 3 cascades", 256, 3),
            volume("probes, 128 rays, 1 cascade", 128, 1), volume("probes, 128 rays, 2 cascades", 128, 2),
            volume("probes, 128 rays, 4 cascades", 128, 4), cascades("cascades, 0.5 m, 4 x 4", 0.5f, 4),
            cascades("cascades, 0.5 m, 6 x 6", 0.5f, 6), cascades("cascades, 0.5 m, 8 x 8", 0.5f, 8),
            cascades("cascades, 1 m, 8 x 8", 1, 8));
        String only = System.getProperty("truesim.gi.setups");
        if (only != null && !only.isBlank()) {
            techniques.removeIf(t -> !List.of(only.split(";")).contains(t.name()));
            volumes = volumes.stream().filter(t -> List.of(only.split(";")).contains(t.name())).toList();
        }
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("gi").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
            int[][] sizes = {{1920, 1080}, {3840, 2160}};
            for (int[] size : sizes) {
                List<Setup> setups = new ArrayList<>(techniques);
                if (size[0] == 1920) {
                    setups.addAll(volumes);
                }
                try (SponzaScene sponza = new SponzaScene(device, Path.of(file), size[0], size[1])) {
                    sponza.look(0);
                    sponza.realtime();
                    sponza.frames(120); // loaded, built
                    Sample[][] samples = new Sample[setups.size()][ROUNDS];
                    for (int round = -WARMUP; round < ROUNDS; round++) {
                        for (int c = 0; c < setups.size(); c++) {
                            sponza.graph.settings().set(ProbeVolume.RAYS, ProbeVolume.RAYS.defaultValue())
                                .set(ProbeVolume.CASCADES, ProbeVolume.CASCADES.defaultValue())
                                .set(RadianceCascades.SPACING, RadianceCascades.SPACING.defaultValue())
                                .set(RadianceCascades.DIRECTIONS, RadianceCascades.DIRECTIONS.defaultValue())
                                .set(RadianceCascades.REACH, RadianceCascades.REACH.defaultValue())
                                .set(ProbeVolume.ASYNC, true).set(ProbeVolume.BUDGET, 1f)
                                .set(ProbeVolume.EMITTER_SAMPLES, ProbeVolume.EMITTER_SAMPLES.defaultValue());
                            sponza.graph.settings().techniques().set(sponza.graph.settings(), AmbientOcclusion.SLOT,
                                "denoiser", Denoiser.Mode.SVGF);
                            sponza.graph.settings().techniques().set(sponza.graph.settings(), AmbientOcclusion.SLOT,
                                "resolution", AmbientOcclusion.Resolution.FULL);
                            setups.get(c).apply().accept(sponza.graph);
                            Sample sample = measure(sponza);
                            if (round >= 0) {
                                samples[c][round] = sample;
                            }
                            System.out.printf(Locale.ROOT, "  round %d, %d x %d, %s: frame %.3f, span %.3f, AO %.3f,"
                                + " probes %.3f, shading %.3f ms%n", round, size[0], size[1], setups.get(c).name(),
                                sample.frame, sample.span, sample.ao, sample.probes, sample.shading);
                        }
                    }
                    System.out.printf(Locale.ROOT, "GI costs on %s, Sponza, %d x %d (ranges of the rounds' medians):%n",
                        device.gpu().name(), size[0], size[1]);
                    System.out.println("| Size | Technique | Frame (passes) | Frame (GPU span) | AO passes | Probe passes"
                        + " | Shading pass |");
                    System.out.println("|---|---|---|---|---|---|---|");
                    for (int c = 0; c < setups.size(); c++) {
                        Sample[] all = samples[c];
                        System.out.printf(Locale.ROOT, "| %d x %d | %s | %s | %s | %s | %s | %s |%n", size[0],
                            size[1], setups.get(c).name(), range(all, Sample::frame), range(all, Sample::span),
                            range(all, Sample::ao), range(all, Sample::probes), range(all, Sample::shading));
                    }
                }
            }
        }
    }

    /**
     * What the probes' light samples of emitters cost, and what they leave of the flicker: the GI scene {@code bulbs}
     * (two small bright emissive boxes), and the same with 254 small dim ones more spread over its ceiling, at 1920 x
     * 1080 through the probe volume, every probe each frame (budget 1), by the samples per update and the choice; the
     * probe passes' GPU time (the emitter list's two passes among them) and the image's frame-to-frame change (each
     * pixel off the mean of the frames before and after it, over the light), ranges of the rounds' medians. Sponza,
     * which has no emitters, pays the list alone ({@link #globalIlluminationCosts}, samples 0 against 8).
     */
    @Test
    void emitterSamplingCosts() throws Exception {
        List<Setup> setups = List.of(
            new Setup("no light samples", g -> g.settings().set(ProbeVolume.EMITTER_SAMPLES, 0)),
            new Setup("4 light samples, nearby", g -> g.settings().set(ProbeVolume.EMITTER_SAMPLES, 4)),
            new Setup("8 light samples, nearby", g -> g.settings().set(ProbeVolume.EMITTER_SAMPLES, 8)),
            new Setup("16 light samples, nearby", g -> g.settings().set(ProbeVolume.EMITTER_SAMPLES, 16)),
            new Setup("8 light samples, by power", g -> g.settings().set(ProbeVolume.EMITTER_SAMPLES, 8)
                .set(ProbeVolume.EMITTER_SELECTION, ProbeVolume.EmitterSelection.POWER)));
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("gi").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
            for (int extra : new int[] {0, 254}) {
                try (GiHarness harness = new GiHarness(device, 1920, 1080)) {
                    FrameGraph graph = harness.graph();
                    harness.realtime("bulbs", 0);
                    if (extra > 0) {
                        GiScenes.Stage stage = harness.stage();
                        int dim = stage.matte(0, 0, 0, 6, 5, 4);
                        for (int i = 0; i < extra; i++) {
                            double x = -2.7 + 5.4 * ((i % 17) + 0.5) / 17;
                            double z = -5.7 + 5.4 * ((i / 17) + 0.5) / 15;
                            stage.box(dim, x - 0.03, 2.9, z - 0.03, x + 0.03, 2.96, z + 0.03);
                        }
                        stage.finish();
                    }
                    double[][] probes = new double[setups.size()][ROUNDS];
                    double[][] flicker = new double[setups.size()][ROUNDS];
                    for (int round = -WARMUP; round < ROUNDS; round++) {
                        for (int c = 0; c < setups.size(); c++) {
                            AmbientOcclusion.SLOT.choose(graph.settings(), null);
                            ProbeVolumeLighting.SLOT.choose(graph.settings(), ProbeVolumeLighting.PROBES);
                            graph.settings().set(ProbeVolume.BUDGET, 1f).reset(ProbeVolume.EMITTER_SAMPLES)
                                .reset(ProbeVolume.EMITTER_SELECTION);
                            setups.get(c).apply().accept(graph);
                            harness.render(SETTLE);
                            graph.timings(true);
                            harness.render(4);
                            double[] times = new double[TIMED];
                            float[][] images = new float[TIMED][];
                            for (int i = 0; i < TIMED; i++) {
                                images[i] = harness.render(1);
                                GpuTimings timings = graph.timings();
                                for (int t = 0; timings != null && t < timings.count(); t++) {
                                    double ms = timings.millis(t);
                                    if (!Double.isNaN(ms) && timings.pass(t).startsWith("probe ")) {
                                        times[i] += ms;
                                    }
                                }
                            }
                            graph.timings(false);
                            double off = 0;
                            double light = 0;
                            for (int i = 1; i + 1 < TIMED; i++) {
                                for (int p = 0; p < images[i].length; p += 4) {
                                    off += Math.abs(images[i][p + 1] - (images[i - 1][p + 1] + images[i + 1][p + 1]) / 2);
                                    light += images[i][p + 1];
                                }
                            }
                            if (round >= 0) {
                                probes[c][round] = median(times);
                                flicker[c][round] = off / Math.max(light, 1.0e-30);
                            }
                            System.out.printf(Locale.ROOT, "  round %d, bulbs + %d, %s: probes %.3f ms, flicker %.5f%n",
                                round, extra, setups.get(c).name(), median(times), off / Math.max(light, 1.0e-30));
                        }
                    }
                    System.out.printf(Locale.ROOT, "Light samples on %s, the bulbs scene and %d more emitters, 1920 x"
                        + " 1080, budget 1:%n", device.gpu().name(), extra);
                    System.out.println("| Emitters | Light samples | Probe passes | Frame-to-frame change |");
                    System.out.println("|---|---|---|---|");
                    for (int c = 0; c < setups.size(); c++) {
                        System.out.printf(Locale.ROOT, "| %d | %s | %.3f-%.3f ms | %.5f-%.5f |%n", 2 + extra,
                            setups.get(c).name(), Arrays.stream(probes[c]).min().orElse(0),
                            Arrays.stream(probes[c]).max().orElse(0), Arrays.stream(flicker[c]).min().orElse(0),
                            Arrays.stream(flicker[c]).max().orElse(0));
                    }
                }
            }
        }
    }

    /** A configuration settled, then the medians of its passes' GPU times over the frames timed. */
    private static Sample measure(SponzaScene sponza) throws InterruptedException {
        FrameGraph graph = sponza.graph;
        sponza.frames(SETTLE);
        graph.timings(true);
        sponza.frames(4);
        double[] frame = new double[TIMED];
        double[] ao = new double[TIMED];
        double[] probes = new double[TIMED];
        double[] shading = new double[TIMED];
        double[] span = new double[TIMED];
        java.util.Map<String, double[]> each = new java.util.TreeMap<>();
        for (int i = 0; i < TIMED; i++) {
            sponza.frames(1);
            GpuTimings timings = graph.timings();
            if (timings == null) {
                continue;
            }
            span[i] = timings.spanMillis();
            for (int t = 0; t < timings.count(); t++) {
                String pass = timings.pass(t);
                double ms = timings.millis(t);
                if (Double.isNaN(ms)) {
                    continue;
                }
                frame[i] += ms;
                each.computeIfAbsent(pass, k -> new double[TIMED])[i] += ms;
                if (pass.startsWith("ambient occlusion")) {
                    ao[i] += ms;
                } else if (pass.startsWith("probe ") || pass.startsWith("cascade ")) {
                    probes[i] += ms;
                } else if (pass.equals("visibility shading")) {
                    shading[i] += ms;
                }
            }
        }
        if (Boolean.getBoolean("truesim.gi.passes")) {
            // Each pass's median, its name's digits taken as one (the layers, the cascades)
            java.util.Map<String, Double> grouped = new java.util.TreeMap<>();
            each.forEach((pass, times) -> grouped.merge(pass.replaceAll("[0-9]+", "#"), median(times), Double::sum));
            grouped.forEach((pass, ms) -> {
                if (ms >= 0.02) {
                    System.out.printf(Locale.ROOT, "    pass %s: %.3f ms%n", pass, ms);
                }
            });
        }
        graph.timings(false);
        sponza.device.waitIdle();
        return new Sample(median(frame), median(span), median(ao), median(probes), median(shading));
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static String range(Sample[] samples, java.util.function.ToDoubleFunction<Sample> value) {
        double low = Double.MAX_VALUE;
        double high = 0;
        for (Sample sample : samples) {
            low = Math.min(low, value.applyAsDouble(sample));
            high = Math.max(high, value.applyAsDouble(sample));
        }
        return String.format(Locale.ROOT, "%.3f-%.3f ms", low, high);
    }
}
