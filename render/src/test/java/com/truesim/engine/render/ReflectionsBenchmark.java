package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_64_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_WAIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_TYPE_TIMESTAMP;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCmdResetQueryPool;
import static org.lwjgl.vulkan.VK10.vkCreateQueryPool;
import static org.lwjgl.vulkan.VK10.vkDestroyQueryPool;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetQueryPoolResults;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.vkCmdWriteTimestamp2;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/**
 * Screen-space reflections' cost: 30,000 rocks (glossy metal, roughness 0.35) on a glossy terrain (roughness 0.2), so
 * nearly every pixel traces a ray, lit by a uniform environment, in half floats, at 1920 x 1080 and 3840 x 2160, by
 * forward shading on both meshlet paths and the visibility buffer: no reflections; the surface outputs alone (asked
 * for by a technique reading nothing, their cost in the shading passes); each technique at full and half trace
 * resolution. Configurations take turns, round after round, after uncounted warm-up rounds; ranges of the rounds'
 * medians. Run with {@code TRUESIM_BENCHMARK=true ./gradlew :engine-render:test --tests '*ReflectionsBenchmark'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class ReflectionsBenchmark {

    private static final int ROUNDS = 4;
    private static final int WARMUP = 2;
    private static final int BATCHES = 16;
    private static final int BATCH = 8;
    private static final int TIMED = 40;
    private static final int[][] SIZES = {{1920, 1080}, {3840, 2160}};
    private static final int COLOR = VK_FORMAT_R16G16B16A16_SFLOAT;

    /** What a configuration adds to the view. */
    private record Setup(String name, ScreenSpaceReflections.Mode mode, float scale, boolean surfaces) {
    }

    private static final List<Setup> SETUPS = List.of(
        new Setup("off", ScreenSpaceReflections.Mode.OFF, 1, false),
        new Setup("surface outputs only", ScreenSpaceReflections.Mode.OFF, 1, true),
        new Setup("LINEAR", ScreenSpaceReflections.Mode.LINEAR, 1, false),
        new Setup("HIZ", ScreenSpaceReflections.Mode.HIZ, 1, false),
        new Setup("LINEAR, half", ScreenSpaceReflections.Mode.LINEAR, 0.5f, false),
        new Setup("HIZ, half", ScreenSpaceReflections.Mode.HIZ, 0.5f, false));

    /** A shading of the view. */
    private record Shading(String name, MeshletRenderer.Path path, boolean visibility) {
    }

    /** A technique wanting the surface outputs and reading nothing: what writing them costs. */
    private static final class Asking implements Technique, SurfaceOutputs.Consumer {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public boolean wantsSurfaces(GraphBuilder g) {
            return true;
        }

        @Override
        public void build(GraphBuilder g) {
        }
    }

    /** One configuration's numbers of one round: GPU frame, shading passes, reflection passes. */
    private record Sample(double frame, double shading, double reflections) {
    }

    @Test
    void reflections() {
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("benchmark").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 100_000, 4));
             GpuScene gpu = new GpuScene(device, pool, 40_000);
             MeshletRenderer renderer = MeshletBenchmark.withView(new MeshletRenderer(device, gpu, COLOR,
                 VK_FORMAT_D32_SFLOAT), 80_000_000);
             Materials materials = new Materials(device, 8);
             MemoryStack stack = stackPush()) {
            MetallicRoughnessParameters mr = MetallicRoughnessParameters.DEFAULTS;
            int ground = materials.add(new Material(mr.withBaseColor(new Color(0.6, 0.6, 0.65, 1)).withRoughness(0.2f)
                .withMetallic(0.8f)));
            int rockMaterial = materials.add(new Material(mr.withBaseColor(new Color(0.8, 0.5, 0.3, 1))
                .withRoughness(0.35f).withMetallic(1)));
            gpu.setMaterials(materials);
            int rock = pool.add(MeshletBenchmark.rock(6));
            int terrain = pool.add(MeshletBenchmark.terrain(1024));
            gpu.set(0, terrain, ground, 0, -12, 0, 0, 0, 0, 1, 1, 1, 1);
            java.util.SplittableRandom random = new java.util.SplittableRandom(11);
            for (int i = 1; i <= 30_000; i++) {
                double x = random.nextDouble(-300, 300);
                double z = random.nextDouble(-605, -5);
                float scale = (float) random.nextDouble(0.5, 3);
                gpu.set(i, rock, rockMaterial, x, -12 + MeshletBenchmark.terrainHeight(x, z) + 0.4 * scale, z, 0, 0,
                    0, 1, scale, scale, scale);
            }
            Environment environment = Environment.uniform("sky", 0.5f, 0.6f, 0.8f);
            RenderView view = MeshletBenchmark.mainView(renderer);
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device.gpu().handle(), properties);
            double period = properties.limits().timestampPeriod();
            LongBuffer handle = stack.mallocLong(1);
            vkCreateQueryPool(device.handle(), timestamps(stack), null, handle);
            long queries = handle.get(0);
            List<Shading> shadings = new java.util.ArrayList<>();
            if (device.meshShaders()) {
                shadings.add(new Shading("forward, mesh shaders", MeshletRenderer.Path.MESH_SHADERS, false));
            }
            shadings.add(new Shading("forward, indexed", MeshletRenderer.Path.INDEXED, false));
            if (device.primitiveIds()) {
                shadings.add(new Shading("visibility buffer", MeshletRenderer.Path.AUTO, true));
            }
            try {
                for (int[] size : SIZES) {
                    int width = size[0];
                    int height = size[1];
                    Camera camera = new Camera().set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, width, height, 0.1);
                    view.set(camera, 1, MeshletRenderer.MODE_LIT);
                    System.out.printf(Locale.ROOT, "Screen-space reflections on %s, %d x %d, 30,000 rocks:%n",
                        device.gpu().name(), width, height);
                    Sample[][][] samples = new Sample[shadings.size()][SETUPS.size()][ROUNDS];
                    try (GpuImage shaded = new GpuImage(device, width, height, COLOR, VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                        VK_IMAGE_ASPECT_COLOR_BIT)) {
                        ExternalImage target = new ExternalImage("shaded", width, height, Format.R16G16B16A16_SFLOAT,
                            VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).set(shaded.handle(), 0);
                        for (int round = -WARMUP; round < ROUNDS; round++) {
                            for (int s = 0; s < shadings.size(); s++) {
                                Shading shading = shadings.get(s);
                                renderer.setPath(shading.path());
                                for (int c = 0; c < SETUPS.size(); c++) {
                                    Setup setup = SETUPS.get(c);
                                    Sample sample = measure(device, renderer, view, camera, target, queries, period,
                                        width, height, environment, setup, shading);
                                    if (round >= 0) {
                                        samples[s][c][round] = sample;
                                    }
                                    System.out.printf(Locale.ROOT, "  round %d, %s, %s: frame %.3f ms, shading %.3f"
                                        + " ms, reflections %.3f ms%n", round, shading.name(), setup.name(),
                                        sample.frame, sample.shading, sample.reflections);
                                }
                            }
                        }
                    }
                    System.out.println("Ranges over the rounds (of each round's median):");
                    for (int s = 0; s < shadings.size(); s++) {
                        for (int c = 0; c < SETUPS.size(); c++) {
                            Sample[] all = samples[s][c];
                            System.out.printf(Locale.ROOT, "  | %d x %d | %s | %s | %s | %s | %s |%n", width, height,
                                shadings.get(s).name(), SETUPS.get(c).name(), range(all, Sample::frame),
                                range(all, Sample::shading), range(all, Sample::reflections));
                        }
                    }
                }
            } finally {
                renderer.setPath(MeshletRenderer.Path.AUTO);
                gpu.setMaterials(null);
                vkDestroyQueryPool(device.handle(), queries, null);
            }
        }
    }

    private static org.lwjgl.vulkan.VkQueryPoolCreateInfo timestamps(MemoryStack stack) {
        return org.lwjgl.vulkan.VkQueryPoolCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
            .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2);
    }

    /** One configuration, warmed, then GPU time per frame (batches between timestamps) and per pass; medians. */
    private static Sample measure(VulkanDevice device, MeshletRenderer renderer, RenderView view, Camera camera,
        ExternalImage target, long queries, double period, int width, int height, Environment environment, Setup setup,
        Shading shading) {
        try (FrameGraph graph = new FrameGraph(device, 2)) {
            graph.settings().set(ScreenSpaceReflections.MODE, setup.mode())
                .set(ScreenSpaceReflections.RESOLUTION_SCALE, setup.scale());
            SceneView main = new SceneView(graph.addView("main").size(width, height), view, camera);
            Function<SceneView, Technique> make = shading.visibility()
                ? v -> new VisibilityBufferShading(SceneGeometry.meshlets(renderer), v, COLOR, VK_FORMAT_D32_SFLOAT)
                : v -> new ClusteredForwardShading(SceneGeometry.meshlets(renderer), v, COLOR, VK_FORMAT_D32_SFLOAT);
            main.port().add(make.apply(main)).add(new EnvironmentLighting(environment, main))
                .add(new SkyBackground(environment, main)).add(new ScreenSpaceReflections(main))
                .output(Keys.SCENE_COLOR, target);
            if (setup.surfaces()) {
                main.port().add(new Asking());
            }
            for (int i = 0; i < 12; i++) {
                device.submitAndWait(device.graphics(), graph::record);
            }
            double[] frame = new double[BATCHES];
            for (int batch = 0; batch < BATCHES; batch++) {
                device.submitAndWait(device.graphics(), commands -> {
                    vkCmdResetQueryPool(commands, queries, 0, 2);
                    vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 0);
                    for (int i = 0; i < BATCH; i++) {
                        graph.record(commands);
                    }
                    vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 1);
                });
                try (MemoryStack stack = stackPush()) {
                    LongBuffer stamps = stack.mallocLong(2);
                    vkGetQueryPoolResults(device.handle(), queries, 0, 2, stamps, 8,
                        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
                    frame[batch] = (stamps.get(1) - stamps.get(0)) * period / 1e6 / BATCH;
                }
            }
            graph.timings(true);
            double[] shaded = new double[TIMED];
            double[] reflected = new double[TIMED];
            for (int i = -4; i < TIMED; i++) {
                device.submitAndWait(device.graphics(), graph::record);
                GpuTimings timings = graph.timings();
                if (i < 0 || timings == null) {
                    continue;
                }
                for (int t = 0; t < timings.count(); t++) {
                    String pass = timings.pass(t);
                    double ms = timings.millis(t);
                    if (Double.isNaN(ms)) {
                        continue;
                    }
                    if (pass.equals("meshlets early") || pass.equals("meshlets late")
                        || pass.equals("visibility shading")) {
                        shaded[i] += ms;
                    } else if (pass.startsWith("reflection") || pass.startsWith("reflected scene")
                        || pass.startsWith("nearest depth pyramid")) {
                        reflected[i] += ms;
                    }
                }
            }
            graph.timings(false);
            return new Sample(median(frame), median(shaded), median(reflected));
        }
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
        return String.format(Locale.ROOT, "%.3f-%.3f", low, high);
    }
}
