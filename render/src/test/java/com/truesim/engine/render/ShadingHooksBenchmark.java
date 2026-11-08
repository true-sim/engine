package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
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

import com.truesim.common.math.Vec3;
import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.materials.TestFogParameters;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.lang.foreign.MemorySegment;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/**
 * What a view's shading hooks cost by how its pipelines are made ({@link ShadingHookInputs#SPECIALIZE}): 30,000 rocks
 * on the terrain at 1920 x 1080, shaded forward and by the visibility buffer, a fog hook on. Per view (VIEW) its
 * pipelines hold the fog alone; with the union of every view's hooks (UNION) they also hold the probe volume's, an
 * indirect hook and two surface hooks, off in this view by their bits: what one set of pipelines for all views costs a
 * view running fewer hooks. The frames are timed whole on the GPU (timestamps around a batch of frames), configurations
 * taking turns round after round, four uncounted rounds first (pitfalls.md). Per-instance overrides of the fog
 * ({@link ShadingHookOverrides}) on every 10th rock, one entry for them all or one each, against none: what the
 * entries cost the shading (an entry looked up per point) and the CPU recording (each view's block composing every
 * entry's record each frame). Run with {@code TRUESIM_BENCHMARK=true
 * ./gradlew :engine-render:test --tests '*ShadingHooksBenchmark'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class ShadingHooksBenchmark {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int ROUNDS = 5;
    private static final int BATCHES = 16;
    private static final int BATCH = 8;

    /**
     * A configuration: a name, whether the fog runs, the hooks made in besides, how pipelines are made, and override
     * entries on every 10th rock (0: none; 1: one entry shared; more: an entry per rock).
     */
    private record Configuration(String name, boolean fog, boolean union, int entries) {
    }

    private static final Configuration[] CONFIGURATIONS = {
        new Configuration("no hooks", false, false, 0),
        new Configuration("fog, per view", true, false, 0),
        new Configuration("fog, union of 7", true, true, 0),
        new Configuration("no hook on, union of 7", false, true, 0),
        new Configuration("fog, per view, every 10th rock 1 entry", true, false, 1),
        new Configuration("fog, per view, every 10th rock its own entry (3,000)", true, false, 3_000),
    };

    @Test
    void specializedPerViewAgainstTheUnion() {
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("benchmark").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 100_000, 4));
             GpuScene gpu = new GpuScene(device, pool, 40_000);
             MeshletRenderer renderer = MeshletBenchmark.withView(new MeshletRenderer(device, gpu,
                 VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT), 80_000_000);
             Materials materials = new Materials(device, 8);
             GpuImage shaded = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
             MemoryStack stack = stackPush()) {
            int rock = pool.add(MeshletBenchmark.rock(6));
            int ground = pool.add(MeshletBenchmark.terrain(1024));
            materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(0.55f, 0.5f, 0.45f, 1)).withRoughness(0.7f)));
            gpu.setMaterials(materials);
            MeshletBenchmark.rocksOnTerrain(renderer, rock, ground, 30_000, 600, -12);
            Camera camera = new Camera().set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
            RenderView view = MeshletBenchmark.mainView(renderer);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            ExternalImage target = new ExternalImage("shaded", WIDTH, HEIGHT,
                com.truesim.engine.vulkan.Format.R8G8B8A8_UNORM, VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).set(shaded.handle(), 0);
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device.gpu().handle(), properties);
            double period = properties.limits().timestampPeriod();
            LongBuffer handle = stack.mallocLong(1);
            vkCreateQueryPool(device.handle(), org.lwjgl.vulkan.VkQueryPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO).queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2),
                null, handle);
            long queries = handle.get(0);
            ShadingHooks hooks = ShadingHooks.of(device);
            // What the union holds: every hook of the listing, as other views of the device would have run them
            ShadingHooks.Set every = ShadingHooks.Set.NONE;
            for (int i = 0; i < hooks.count(); i++) {
                every = every.with(i);
            }
            ShadingHookValues.of(device).union(every);
            String[] shadings = {"forward", "visibility buffer"};
            Environment environment = Environment.uniform("grey", 0.4f, 0.45f, 0.5f);
            try {
                System.out.printf(Locale.ROOT, "Shading hooks on %s, %d x %d, 30,000 rocks; listing %s:%n",
                    device.gpu().name(), WIDTH, HEIGHT, hooks);
                double[][][] gpuMs = new double[shadings.length][CONFIGURATIONS.length][ROUNDS];
                double[][][] cpuMs = new double[shadings.length][CONFIGURATIONS.length][ROUNDS];
                for (int round = -4; round < ROUNDS; round++) {
                    for (int s = 0; s < shadings.length; s++) {
                        if (s == 1 && !device.primitiveIds()) {
                            continue;
                        }
                        for (int c = 0; c < CONFIGURATIONS.length; c++) {
                            double[] ms = measure(device, renderer, view, camera, target, environment, s == 1,
                                CONFIGURATIONS[c], queries, period);
                            if (round >= 0) {
                                gpuMs[s][c][round] = ms[0];
                                cpuMs[s][c][round] = ms[1];
                            }
                            System.out.printf(Locale.ROOT, "  round %d, %s, %s: frame %.3f ms GPU, recorded in %.3f ms "
                                + "CPU%n", round, shadings[s], CONFIGURATIONS[c].name(), ms[0], ms[1]);
                        }
                    }
                }
                System.out.println("Ranges over the rounds (each round's median frame), GPU and CPU recording:");
                for (int s = 0; s < shadings.length; s++) {
                    for (int c = 0; c < CONFIGURATIONS.length; c++) {
                        double[] all = gpuMs[s][c].clone();
                        Arrays.sort(all);
                        double[] cpu = cpuMs[s][c].clone();
                        Arrays.sort(cpu);
                        System.out.printf(Locale.ROOT, "  | %s | %s | %.3f-%.3f | %.3f-%.3f |%n", shadings[s],
                            CONFIGURATIONS[c].name(), all[0], all[all.length - 1], cpu[0], cpu[cpu.length - 1]);
                    }
                }
            } finally {
                gpu.setMaterials(null);
                vkDestroyQueryPool(device.handle(), queries, null);
            }
        }
    }

    /**
     * The median GPU time of a frame of {@code configuration} and the median CPU time recording one, in milliseconds.
     */
    private static double[] measure(VulkanDevice device, MeshletRenderer renderer, RenderView view, Camera camera,
        ExternalImage target, Environment environment, boolean visibilityBuffer, Configuration configuration,
        long queries, double period) {
        try (FrameGraph graph = new FrameGraph(device, 2)) {
            graph.settings().set(ShadingHookInputs.SPECIALIZE, configuration.union()
                ? ShadingHookInputs.Specialize.UNION : ShadingHookInputs.Specialize.VIEW);
            SceneView main = new SceneView(graph.addView("main").size(WIDTH, HEIGHT), view, camera);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            Technique shading = visibilityBuffer
                ? new VisibilityBufferShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                : new ClusteredForwardShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
            main.port().add(shading).add(new EnvironmentLighting(environment, main))
                .output(Keys.SCENE_COLOR, target);
            if (configuration.fog()) {
                main.port().add(new Fog(device));
            }
            ShadingHookOverrides overrides = renderer.scene().hookOverrides();
            int[] entries = new int[configuration.entries()];
            for (int e = 0; e < entries.length; e++) {
                entries[e] = overrides.add();
                overrides.set(entries[e], "test.fog", new TestFogParameters(new Vec3(0.9, 0.9, 0.9),
                    new Vec3(0.02 + e * 1e-5, 0.02, 0.03)));
            }
            for (int id = 1, n = 0; entries.length > 0 && id <= 30_000; id += 10, n++) {
                overrides.assign(id, entries[n % entries.length]);
            }
            for (int i = 0; i < 10; i++) {
                device.submitAndWait(device.graphics(), graph::record);
            }
            double[] gpu = new double[BATCHES];
            double[] cpu = new double[BATCHES];
            for (int batch = 0; batch < BATCHES; batch++) {
                long[] recorded = new long[1];
                device.submitAndWait(device.graphics(), commands -> {
                    vkCmdResetQueryPool(commands, queries, 0, 2);
                    vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 0);
                    long start = System.nanoTime();
                    for (int i = 0; i < BATCH; i++) {
                        graph.record(commands);
                    }
                    recorded[0] = System.nanoTime() - start;
                    vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 1);
                });
                cpu[batch] = recorded[0] / 1e6 / BATCH;
                try (MemoryStack stack = stackPush()) {
                    LongBuffer stamps = stack.mallocLong(2);
                    vkGetQueryPoolResults(device.handle(), queries, 0, 2, stamps, 8,
                        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
                    gpu[batch] = (stamps.get(1) - stamps.get(0)) * period / 1e6 / BATCH;
                }
            }
            Arrays.sort(gpu);
            Arrays.sort(cpu);
            for (int id = 1; entries.length > 0 && id <= 30_000; id += 10) {
                overrides.assign(id, 0);
            }
            for (int entry : entries) {
                overrides.remove(entry);
            }
            return new double[] {gpu[BATCHES / 2], cpu[BATCHES / 2]};
        }
    }

    /** The test fog: a third of the light through, a blue haze added. */
    private record Fog(VulkanDevice device) implements Technique {
        private static final TestFogParameters FOG = new TestFogParameters(new Vec3(0.7, 0.75, 0.8),
            new Vec3(0.05, 0.06, 0.08));

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(Keys.SHADING_HOOKS);
        }

        @Override
        public void build(GraphBuilder g) {
            ShadingHooks.of(device).add(g, "test.fog", new ShadingHookSource() {
                @Override
                public int bytes() {
                    return TestFogParameters.BYTES;
                }

                @Override
                public void write(PassContext ctx, ShadingHookParameters authored, MemorySegment into, long offset) {
                    FOG.write(into, offset);
                }
            });
        }
    }
}
