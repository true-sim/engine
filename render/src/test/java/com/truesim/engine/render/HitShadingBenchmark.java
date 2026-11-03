package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.render.materials.CarPaint;
import com.truesim.engine.render.materials.CarPaintParameters;
import com.truesim.engine.render.materials.ClearcoatParameters;
import com.truesim.engine.render.materials.Dissolve;
import com.truesim.engine.render.materials.DissolveParameters;
import com.truesim.engine.render.materials.Glow;
import com.truesim.engine.render.materials.GlowParameters;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.materials.SheenParameters;
import com.truesim.engine.render.materials.Tinted;
import com.truesim.engine.render.materials.TintedParameters;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Hit shading by type (design D5), timed (TRUESIM_BENCHMARK=true): the reference path tracer's hits sorted by shading
 * bin and shaded by a dispatch of each bin's type entry point (with the bins' layout and scatter), against one
 * dispatch of a shader switching over the types (test/singleHit.slang, as the build would generate it, features read
 * at run time). 3,000 rocks on a terrain, their materials spread over 5 types and 8 shading bins (metallic-roughness
 * plain, with a clear coat and with a sheen; glow, tinted, car paint, dissolve), 2 bounces; a probe-sized batch
 * (256 x 256 rays: 512 probes of 128) and 1920 x 1080. GPU time of each bounce's binning and shading passes, medians
 * over rounds taken in turns after a warm-up round.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class HitShadingBenchmark {

    private static final int ROUNDS = 4;
    private static final int[][] SIZES = {{256, 256}, {1920, 1080}};

    @Test
    void binnedAgainstOneSwitchingShader() {
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("benchmark").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 1 << 16, 4));
             GpuScene scene = new GpuScene(device, pool, 4096);
             Materials materials = new Materials(device, 16);
             MeshletRenderer renderer = MeshletBenchmark.withView(new MeshletRenderer(device, scene,
                 VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_D32_SFLOAT), 1 << 24);
             RayTracing rayTracing = new RayTracing(device, pool, 4096)) {
            if (!device.rayTracing()) {
                System.out.println("No ray tracing on " + device.gpu().name());
                return;
            }
            var rockMesh = MeshletBenchmark.rock(4);
            var groundMesh = MeshletBenchmark.terrain(256);
            int rock = pool.add(rockMesh);
            int ground = pool.add(groundMesh);
            rayTracing.add(rock, rockMesh, rockMesh.levels().size() - 1);
            rayTracing.add(ground, groundMesh, groundMesh.levels().size() - 1);
            scene.setMaterials(materials);
            materials.register(Glow.TYPE); // in test/singleHit.slang's order
            materials.register(Tinted.TYPE);
            materials.register(CarPaint.TYPE);
            materials.register(Dissolve.TYPE);
            MetallicRoughnessParameters mr = MetallicRoughnessParameters.DEFAULTS;
            int[] palette = {
                materials.add(new Material(mr.withBaseColor(new Color(0.6f, 0.6f, 0.6f, 1)).withRoughness(0.8f))),
                materials.add(new Material(mr.withBaseColor(new Color(0.2f, 0.4f, 0.9f, 1))).feature(
                    ClearcoatParameters.DEFAULTS)),
                materials.add(new Material(mr.withBaseColor(new Color(0.7f, 0.2f, 0.2f, 1))).feature(
                    SheenParameters.DEFAULTS)),
                materials.add(new Material(GlowParameters.DEFAULTS.withStrength(0.5f))),
                materials.add(new Material(TintedParameters.DEFAULTS)),
                materials.add(new Material(CarPaint.TYPE, CarPaintParameters.DEFAULTS.withFlakes(0.5f))),
                materials.add(new Material(DissolveParameters.DEFAULTS)),
                materials.add(new Material(mr.withMetallic(1).withRoughness(0.3f))),
            };
            scene.set(0, ground, palette[0], 0, -2, -100, 0, 0, 0, 1, 0.8f, 0.8f, 0.8f);
            SplittableRandom random = new SplittableRandom(3);
            for (int i = 1; i <= 3000; i++) {
                float s = (float) random.nextDouble(0.3, 1.5);
                scene.set(i, rock, palette[i % palette.length], (float) random.nextDouble(-100, 100), 0,
                    (float) random.nextDouble(-200, 0), 0, 0, 0, 1, s, s, s);
            }
            scene.removeFrom(3001);
            RenderView view = MeshletBenchmark.mainView(renderer);
            System.out.printf(Locale.ROOT, "Hit shading on %s, 2 bounces, %d shading bins:%n", device.gpu().name(),
                materials.binCount());
            System.out.println("| Rays | Way | Binning | Shading | Both |");
            System.out.println("|---|---|---|---|---|");
            for (int[] size : SIZES) {
                double[][][] samples = new double[2][ROUNDS][];
                for (int round = -1; round < ROUNDS; round++) {
                    for (int way = 0; way < 2; way++) {
                        double[] sample = measure(device, renderer, rayTracing, view, size[0], size[1], way == 1);
                        if (round >= 0) {
                            samples[way][round] = sample;
                        }
                    }
                }
                for (int way = 0; way < 2; way++) {
                    System.out.printf(Locale.ROOT, "| %d x %d | %s | %s | %s | %s |%n", size[0], size[1],
                        way == 0 ? "binned, a dispatch per bin" : "one shader switching", range(samples[way], 0),
                        range(samples[way], 1), range(samples[way], 2));
                }
            }
        }
    }

    private static double[] measure(VulkanDevice device, MeshletRenderer renderer, RayTracing rayTracing,
        RenderView view, int width, int height, boolean single) {
        Camera camera = new Camera().position(0, 25, 30);
        camera.set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, width, height, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        try (FrameGraph graph = new FrameGraph(device, 2);
             GpuImage target = new GpuImage(device, width, height, VK_FORMAT_R32G32B32A32_SFLOAT,
                 VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT)) {
            SceneView main = new SceneView(graph.addView("main").size(width, height), view, camera);
            ReferencePathTracer reference = new ReferencePathTracer(SceneGeometry.meshlets(renderer), rayTracing, main,
                VK_FORMAT_R32G32B32A32_SFLOAT);
            if (single) {
                reference.singleHitShader("shaders/test/singleHit.spv", "singleHitMain");
            }
            graph.settings().set(ReferencePathTracer.BOUNCES, 2);
            main.port().add(reference).output(Keys.SCENE_COLOR, new ExternalImage("target", width, height,
                Format.R32G32B32A32_SFLOAT, VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
            for (int i = 0; i < 16; i++) {
                device.submitAndWait(device.graphics(), graph::record);
            }
            graph.timings(true);
            int frames = 24;
            double[] binning = new double[frames];
            double[] shading = new double[frames];
            for (int i = -4; i < frames; i++) {
                device.submitAndWait(device.graphics(), graph::record);
                GpuTimings timings = graph.timings();
                if (i < 0 || timings == null) {
                    continue;
                }
                for (int t = 0; t < timings.count(); t++) {
                    double ms = timings.millis(t);
                    if (Double.isNaN(ms)) {
                        continue;
                    }
                    String pass = timings.pass(t);
                    if (pass.startsWith("reference bins") || pass.startsWith("reference scatter")) {
                        binning[i] += ms;
                    } else if (pass.startsWith("reference shade")) {
                        shading[i] += ms;
                    }
                }
            }
            graph.timings(false);
            device.waitIdle();
            return new double[] {median(binning), median(shading), median(binning) + median(shading)};
        }
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static String range(double[][] samples, int field) {
        double low = Double.MAX_VALUE;
        double high = 0;
        for (double[] s : samples) {
            low = Math.min(low, s[field]);
            high = Math.max(high, s[field]);
        }
        return String.format(Locale.ROOT, "%.3f-%.3f ms", low, high);
    }
}
