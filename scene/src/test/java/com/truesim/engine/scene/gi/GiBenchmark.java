package com.truesim.engine.scene.gi;

import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.render.AmbientOcclusion;
import com.truesim.engine.render.Denoiser;
import com.truesim.engine.render.ProbeVolume;
import com.truesim.engine.render.ProbeVolumeLighting;
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

    private record Sample(double frame, double ao, double probes, double shading) {
    }

    @Test
    void globalIlluminationCosts() throws Exception {
        String file = System.getProperty("truesim.gi.sponza");
        assumeThat(file).as("no Sponza given: -PgiSponza=<Sponza.gltf>").isNotNull();
        List<Setup> techniques = new ArrayList<>();
        GiEvaluation.VARIANTS.forEach(v -> techniques.add(variant(v)));
        List<Setup> volumes = List.of(volume("probes, 64 rays, 3 cascades", 64, 3),
            volume("probes, 128 rays, 3 cascades", 128, 3), volume("probes, 256 rays, 3 cascades", 256, 3),
            volume("probes, 128 rays, 1 cascade", 128, 1), volume("probes, 128 rays, 2 cascades", 128, 2),
            volume("probes, 128 rays, 4 cascades", 128, 4));
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
                                .set(ProbeVolume.CASCADES, ProbeVolume.CASCADES.defaultValue());
                            sponza.graph.settings().techniques().set(sponza.graph.settings(), AmbientOcclusion.SLOT,
                                "denoiser", Denoiser.Mode.SVGF);
                            setups.get(c).apply().accept(sponza.graph);
                            Sample sample = measure(sponza);
                            if (round >= 0) {
                                samples[c][round] = sample;
                            }
                            System.out.printf(Locale.ROOT, "  round %d, %d x %d, %s: frame %.3f, AO %.3f, probes %.3f,"
                                + " shading %.3f ms%n", round, size[0], size[1], setups.get(c).name(), sample.frame,
                                sample.ao, sample.probes, sample.shading);
                        }
                    }
                    System.out.printf(Locale.ROOT, "GI costs on %s, Sponza, %d x %d (ranges of the rounds' medians):%n",
                        device.gpu().name(), size[0], size[1]);
                    System.out.println("| Size | Technique | Frame (passes) | AO passes | Probe passes | Shading pass |");
                    System.out.println("|---|---|---|---|---|---|");
                    for (int c = 0; c < setups.size(); c++) {
                        Sample[] all = samples[c];
                        System.out.printf(Locale.ROOT, "| %d x %d | %s | %s | %s | %s | %s |%n", size[0], size[1],
                            setups.get(c).name(), range(all, Sample::frame), range(all, Sample::ao),
                            range(all, Sample::probes), range(all, Sample::shading));
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
        for (int i = 0; i < TIMED; i++) {
            sponza.frames(1);
            GpuTimings timings = graph.timings();
            if (timings == null) {
                continue;
            }
            for (int t = 0; t < timings.count(); t++) {
                String pass = timings.pass(t);
                double ms = timings.millis(t);
                if (Double.isNaN(ms)) {
                    continue;
                }
                frame[i] += ms;
                if (pass.startsWith("ambient occlusion")) {
                    ao[i] += ms;
                } else if (pass.startsWith("probe ")) {
                    probes[i] += ms;
                } else if (pass.equals("visibility shading")) {
                    shading[i] += ms;
                }
            }
        }
        graph.timings(false);
        sponza.device.waitIdle();
        return new Sample(median(frame), median(ao), median(probes), median(shading));
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
