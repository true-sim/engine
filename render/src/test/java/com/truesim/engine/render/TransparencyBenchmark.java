package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
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
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_MEMORY_BARRIER_2;
import static org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2;
import static org.lwjgl.vulkan.VK13.vkCmdWriteTimestamp2;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.materials.IorParameters;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

/**
 * Sorted against weighted blended transparency at 1920 x 1080: 100, 10,000 and 100,000 glass panes (a 2-triangle quad
 * each, double-sided, alpha 0.3) over 5,000 rocks on the terrain, on both meshlet paths. Configurations take turns, round after round (a first round warms
 * the clocks, uncounted); ranges of the rounds' medians. Run with {@code TRUESIM_BENCHMARK=true ./gradlew
 * :engine-render:test --tests '*TransparencyBenchmark'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class TransparencyBenchmark {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int ROUNDS = 4;
    private static final int BATCHES = 16;
    private static final int BATCH = 8;
    private static final int TIMED = 40;
    private static final int[] PANES = {100, 10_000, 100_000};
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS
        .withBaseColor(new Color(0.55f, 0.5f, 0.45f, 1)).withRoughness(0.7f);

    /** One configuration's numbers of one round: GPU ms of the frame, culling (and sorting), blended draw, resolve. */
    private record Sample(double gpu, double culling, double blended, double resolve, double cpu) {
    }

    private static MeshData pane() {
        float[] positions = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0};
        float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), 4).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).submesh(new int[] {0, 1, 2, 0, 2, 3}).buildFlat();
    }

    /**
     * Rocks of material {@code opaque}, then panes from instance {@code 1 + rocks}, their materials from {@code glass}
     * alternating over {@code bins}.
     */
    private static void place(GpuScene scene, int rock, int ground, int pane, int rocks, int panes, int opaque,
        int glass, int bins) {
        scene.set(0, ground, opaque, 0, -12f, 0, 0, 0, 0, 1, 1, 1, 1);
        SplittableRandom random = new SplittableRandom(11);
        for (int i = 1; i <= rocks; i++) {
            double x = random.nextDouble(-150, 150);
            double z = random.nextDouble(-305, -5);
            float scale = (float) random.nextDouble(0.5, 3);
            scene.set(i, rock, opaque, x, -12 + MeshletBenchmark.terrainHeight(x, z) + 0.4 * scale, z, 0, 0, 0, 1, scale,
                scale, scale);
        }
        for (int i = 0; i < panes; i++) {
            double x = random.nextDouble(-40, 40);
            double y = random.nextDouble(-8, 12);
            double z = random.nextDouble(-90, -6);
            double qx = random.nextDouble(-1, 1);
            double qy = random.nextDouble(-1, 1);
            double qz = random.nextDouble(-1, 1);
            double qw = random.nextDouble(-1, 1);
            double length = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            float scale = (float) random.nextDouble(0.3, 1.2);
            scene.set(1 + rocks + i, pane, glass + i % bins, x, y, z, (float) (qx / length), (float) (qy / length),
                (float) (qz / length), (float) (qw / length), scale, scale, scale);
        }
        scene.removeFrom(1 + rocks + panes);
    }

    @Test
    void sortedAgainstWeighted() {
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("benchmark").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 100_000, 4));
             GpuScene gpu = new GpuScene(device, pool, 110_000);
             MeshletRenderer renderer = MeshletBenchmark.withView(new MeshletRenderer(device, gpu,
                 VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT, gpu.frameSlots(), 1 << 18), 40_000_000);
             Materials materials = new Materials(device, 8);
             GpuImage shaded = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
             MemoryStack stack = stackPush()) {
            int opaque = materials.add(new Material(MR));
            int glass = materials.add(new Material(MR.withBaseColor(new Color(0.2f, 0.5f, 0.9f, 0.3f))
                .withRoughness(0.1f)).alphaBlend().doubleSided(true));
            materials.add(new Material(MR.withBaseColor(new Color(0.9f, 0.3f, 0.2f, 0.3f)).withRoughness(0.1f))
                .alphaBlend().doubleSided(true).feature(IorParameters.DEFAULTS)); // a second shading bin
            gpu.setMaterials(materials);
            int rock = pool.add(MeshletBenchmark.rock(5));
            int ground = pool.add(MeshletBenchmark.terrain(1024));
            int pane = pool.add(pane());
            Camera camera = new Camera().set(0, -0.15, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
            RenderView view = MeshletBenchmark.mainView(renderer);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            ExternalImage target = target(shaded);
            double period = period(device, stack);
            long queries = queries(device, stack);
            try {
                MeshletRenderer.Path[] paths = device.meshShaders()
                    ? new MeshletRenderer.Path[] {MeshletRenderer.Path.MESH_SHADERS, MeshletRenderer.Path.INDEXED}
                    : new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED};
                // (mode, shading bins of the panes): weighted, sorted, sorted with panes of two shading bins mixed
                Object[][] ways = {{Transparency.WEIGHTED, 1}, {Transparency.SORTED, 1},
                    {Transparency.SORTED, 2}};
                System.out.printf(Locale.ROOT, "Transparency on %s, %d x %d, 5,000 rocks on the terrain, glass panes:%n",
                    device.gpu().name(), WIDTH, HEIGHT);
                Sample[][][][] samples = new Sample[paths.length][PANES.length][ways.length][ROUNDS];
                for (int round = -1; round < ROUNDS; round++) {
                    for (int p = 0; p < paths.length; p++) {
                        renderer.setPath(paths[p]);
                        for (int n = 0; n < PANES.length; n++) {
                            for (int w = 0; w < ways.length; w++) {
                                place(gpu, rock, ground, pane, 5_000, PANES[n], opaque, glass, (Integer) ways[w][1]);
                                Sample sample = measure(device, renderer, view, camera, target, queries, period,
                                    (TechniqueKind<?, ChosenTransparency, Technique>) ways[w][0]);
                                if (round >= 0) {
                                    samples[p][n][w][round] = sample;
                                }
                                if (renderer.chunksOverflowed()) {
                                    throw new IllegalStateException("chunks overflowed");
                                }
                                System.out.printf(Locale.ROOT, "  round %d, %s, %,d panes, %s, %d bin(s): frame %.3f ms,"
                                    + " culling %.3f ms, blended %.3f ms, resolve %.3f ms, record %.3f ms CPU,"
                                    + " %,d blended meshlets%n", round, pathName(paths[p]), PANES[n], ways[w][0],
                                    ways[w][1], sample.gpu, sample.culling, sample.blended, sample.resolve,
                                    sample.cpu, view.visibleMeshlets(MeshletRenderer.BIN_BLENDED));
                            }
                        }
                    }
                }
                System.out.println("Ranges over the rounds (of each round's median): path | panes | way | bins | frame"
                    + " | culling (+ sort) | blended draw | resolve | record CPU");
                for (int p = 0; p < paths.length; p++) {
                    for (int n = 0; n < PANES.length; n++) {
                        for (int w = 0; w < ways.length; w++) {
                            Sample[] all = samples[p][n][w];
                            System.out.printf(Locale.ROOT, "  | %s | %,d | %s | %d | %s | %s | %s | %s | %s |%n",
                                pathName(paths[p]), PANES[n], ways[w][0], ways[w][1], range(all, Sample::gpu),
                                range(all, Sample::culling), range(all, Sample::blended), range(all, Sample::resolve),
                                range(all, Sample::cpu));
                        }
                    }
                }
            } finally {
                gpu.setMaterials(null);
                vkDestroyQueryPool(device.handle(), queries, null);
            }
        }
    }

    /**
     * One configuration: forward shading, with transparency drawn as {@code mode}; GPU time per frame (batches between
     * timestamps), passes' GPU times (the graph's), CPU time of recording; medians.
     */
    private static Sample measure(VulkanDevice device, MeshletRenderer renderer, RenderView view, Camera camera,
        ExternalImage target, long queries, double period, TechniqueKind<?, ChosenTransparency, Technique> mode) {
        try (FrameGraph graph = new FrameGraph(device, 2)) {
            SceneView main = new SceneView(graph.addView("main").size(WIDTH, HEIGHT), view, camera);
            main.port().add(MeshletBenchmark.forward(main)).output(Keys.SCENE_COLOR, target);
            Transparency.SLOT.choose(graph.settings(), mode);
            main.port().add(new ChosenTransparency(SceneGeometry.meshlets(renderer), main));
            for (int i = 0; i < 16; i++) {
                device.submitAndWait(device.graphics(), graph::record);
            }
            double[] gpu = new double[BATCHES];
            double[] cpu = new double[BATCHES * BATCH];
            for (int batch = 0; batch < BATCHES; batch++) {
                int first = batch * BATCH;
                device.submitAndWait(device.graphics(), commands -> {
                    vkCmdResetQueryPool(commands, queries, 0, 2);
                    vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 0);
                    for (int i = 0; i < BATCH; i++) {
                        long t0 = System.nanoTime();
                        graph.record(commands);
                        cpu[first + i] = (System.nanoTime() - t0) / 1e6;
                        fullBarrier(commands);
                    }
                    vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 1);
                });
                try (MemoryStack stack = stackPush()) {
                    LongBuffer stamps = stack.mallocLong(2);
                    vkGetQueryPoolResults(device.handle(), queries, 0, 2, stamps, 8,
                        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
                    gpu[batch] = (stamps.get(1) - stamps.get(0)) * period / 1e6 / BATCH;
                }
            }
            graph.timings(true);
            double[] culling = new double[TIMED];
            double[] blended = new double[TIMED];
            double[] resolve = new double[TIMED];
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
                    if (pass.equals("meshlet culling") || pass.equals("meshlets late culling")) {
                        culling[i] += ms;
                    } else if (pass.equals("meshlets blended")) {
                        blended[i] += ms;
                    } else if (pass.equals("scene transparency resolve")) {
                        resolve[i] += ms;
                    }
                }
            }
            graph.timings(false);
            return new Sample(median(gpu), median(culling), median(blended), median(resolve), median(cpu));
        }
    }

    private static ExternalImage target(GpuImage shaded) {
        return new ExternalImage("shaded", WIDTH, HEIGHT, com.truesim.engine.vulkan.Format.R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
            .set(shaded.handle(), 0);
    }

    private static double period(VulkanDevice device, MemoryStack stack) {
        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(device.gpu().handle(), properties);
        return properties.limits().timestampPeriod();
    }

    private static long queries(VulkanDevice device, MemoryStack stack) {
        LongBuffer handle = stack.mallocLong(1);
        vkCreateQueryPool(device.handle(), VkQueryPoolCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO).queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2),
            null, handle);
        return handle.get(0);
    }

    private static String pathName(MeshletRenderer.Path path) {
        return path == MeshletRenderer.Path.INDEXED ? "indexed" : "mesh shaders";
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static String range(Sample[] samples, ToDoubleFunction<Sample> value) {
        double low = Double.MAX_VALUE;
        double high = 0;
        for (Sample sample : samples) {
            low = Math.min(low, value.applyAsDouble(sample));
            high = Math.max(high, value.applyAsDouble(sample));
        }
        return String.format(Locale.ROOT, "%.3f-%.3f ms", low, high);
    }

    private static void fullBarrier(VkCommandBuffer commands) {
        try (MemoryStack stack = stackPush()) {
            vkCmdPipelineBarrier2(commands, VkDependencyInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                .pMemoryBarriers(VkMemoryBarrier2.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                    .srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .srcAccessMask(VK_ACCESS_2_MEMORY_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT)));
        }
    }
}
