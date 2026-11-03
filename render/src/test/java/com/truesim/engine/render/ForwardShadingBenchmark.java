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
import com.truesim.engine.render.materials.AnisotropyParameters;
import com.truesim.engine.render.materials.CarPaint;
import com.truesim.engine.render.materials.CarPaintParameters;
import com.truesim.engine.render.materials.ClearcoatParameters;
import com.truesim.engine.render.materials.Glow;
import com.truesim.engine.render.materials.GlowParameters;
import com.truesim.engine.render.materials.IorParameters;
import com.truesim.engine.render.materials.IridescenceParameters;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.materials.SheenParameters;
import com.truesim.engine.render.materials.SpecularParameters;
import com.truesim.engine.render.materials.Tinted;
import com.truesim.engine.render.materials.TintedParameters;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

/**
 * Forward shading by material type ({@link ForwardShading#PER_TYPE}: pipelines per shading bin, lists split after
 * culling) against the one shader branching on the type ({@link ForwardShading#SINGLE}), #67: 30,000 rocks on the
 * terrain at 1920 x 1080, their materials spread over 1, 4 or 12 shading bins (types and feature combinations), on both
 * geometry paths. Configurations take turns, round after round, so they see the same clocks (a first round, warming
 * the clocks up, is not counted); each round's medians are printed and the range over the rounds summed up. Run with {@code TRUESIM_BENCHMARK=true ./gradlew
 * :engine-render:test --tests '*ForwardShadingBenchmark'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class ForwardShadingBenchmark {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int ROUNDS = 5;
    private static final int BATCHES = 24;
    private static final int BATCH = 8;
    private static final int TIMED = 60;
    private static final int[] BINS = {1, 4, 12};
    /** Materials every table holds, ids 1 to 12; rock i takes 1 + i % 12, so a table of fewer bins repeats them. */
    private static final int MATERIALS = 12;
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS
        .withBaseColor(new Color(0.55f, 0.5f, 0.45f, 1)).withRoughness(0.7f);

    /** Material {@code bin} of the 12 shading bins: 4 types, then metallic-roughness with 8 feature combinations. */
    private static Material material(int bin) {
        return switch (bin) {
            case 0 -> new Material(MR);
            case 1 -> new Material(GlowParameters.DEFAULTS.withStrength(0.5f));
            case 2 -> new Material(TintedParameters.DEFAULTS);
            case 3 -> new Material(CarPaintParameters.DEFAULTS.withFlakes(0.3f));
            case 4 -> new Material(CarPaintParameters.DEFAULTS).feature(ClearcoatParameters.DEFAULTS);
            case 5 -> new Material(MR).feature(ClearcoatParameters.DEFAULTS);
            case 6 -> new Material(MR).feature(SheenParameters.DEFAULTS);
            case 7 -> new Material(MR).feature(SpecularParameters.DEFAULTS);
            case 8 -> new Material(MR).feature(IorParameters.DEFAULTS);
            case 9 -> new Material(MR).feature(IridescenceParameters.DEFAULTS);
            case 10 -> new Material(MR).feature(AnisotropyParameters.DEFAULTS);
            case 11 -> new Material(MR).feature(ClearcoatParameters.DEFAULTS).feature(SheenParameters.DEFAULTS);
            default -> throw new IllegalArgumentException("bin " + bin);
        };
    }

    /** A table of {@code bins} shading bins over materials 1 to 12 (material j: bin (j - 1) % bins). */
    private static Materials table(VulkanDevice device, int bins) {
        Materials materials = new Materials(device, 32);
        materials.register(Glow.TYPE);
        materials.register(Tinted.TYPE);
        materials.register(CarPaint.TYPE);
        for (int j = 1; j <= MATERIALS; j++) {
            int id = materials.add(material((j - 1) % bins));
            if (id != j) {
                throw new IllegalStateException("material " + j + " got id " + id);
            }
        }
        if (materials.types().indexOf(Glow.TYPE) != 1 || materials.types().indexOf(Tinted.TYPE) != 2
            || materials.types().indexOf(CarPaint.TYPE) != 3) {
            throw new IllegalStateException("types numbered otherwise than the single shader's constants");
        }
        if (materials.binCount() != bins) {
            throw new IllegalStateException(bins + " bins wanted, the table numbers " + materials.binCount());
        }
        return materials;
    }

    /**
     * As {@link MeshletBenchmark#rocksOnTerrain}, rock i (from 1) with material 1 + (i - 1) % 12; the terrain, instance
     * 0, metallic-roughness's defaults.
     */
    private static void rocksOnTerrain(GpuScene scene, int rock, int ground, int count, double extent, double groundY) {
        scene.set(0, ground, 0, (float) groundY, 0, 0, 0, 0, 1, 1, 1, 1);
        java.util.SplittableRandom random = new java.util.SplittableRandom(11);
        for (int i = 1; i <= count; i++) {
            double x = random.nextDouble(-extent / 2, extent / 2);
            double z = random.nextDouble(-extent - 5, -5);
            float scale = (float) random.nextDouble(0.5, 3);
            double qx = random.nextDouble(-1, 1);
            double qy = random.nextDouble(-1, 1);
            double qz = random.nextDouble(-1, 1);
            double qw = random.nextDouble(-1, 1);
            double length = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            scene.set(i, rock, 1 + (i - 1) % MATERIALS, x, groundY + MeshletBenchmark.terrainHeight(x, z) + 0.4 * scale,
                z, (float) (qx / length), (float) (qy / length), (float) (qz / length), (float) (qw / length), scale,
                scale, scale);
        }
        scene.removeFrom(count + 1);
    }

    /** The generated single shader's prototype (test/singleForward.slang): from full vertices, and lean. */
    private static final String SINGLE_FULL = "shaders/test/singleForward.spv";
    private static final String SINGLE_LEAN = "shaders/test/singleForward.spv";

    /**
     * The prototype's specialization constants: FEATURES (unused), then the numbers {@link #table} gives glow, tinted
     * and car paint (registered first, in that order: 1, 2, 3).
     */
    private static int[] singleConstants() {
        return new int[] {0, 1, 2, 3};
    }

    /** One configuration's numbers of one round. */
    private record Sample(double gpu, double culling, double draws, double cpu, int drawCalls) {
    }

    @Test
    void perTypeAgainstSingle() {
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("benchmark").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 100_000, 4));
             GpuScene gpu = new GpuScene(device, pool, 40_000);
             MeshletRenderer renderer = MeshletBenchmark.withView(new MeshletRenderer(device, gpu,
                 VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT), 80_000_000);
             GpuImage shaded = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
             MemoryStack stack = stackPush()) {
            renderer.singleShaderForBenchmark(SINGLE_FULL, SINGLE_LEAN, singleConstants());
            MeshData rockMesh = MeshletBenchmark.rock(6);
            int rock = pool.add(rockMesh);
            int ground = pool.add(MeshletBenchmark.terrain(1024));
            rocksOnTerrain(gpu, rock, ground, 30_000, 600, -12);
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
            vkCreateQueryPool(device.handle(), VkQueryPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO).queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2),
                null, handle);
            long queries = handle.get(0);
            List<Materials> tables = new ArrayList<>();
            try {
                for (int bins : BINS) {
                    tables.add(table(device, bins));
                }
                MeshletRenderer.Path[] paths = device.meshShaders()
                    ? new MeshletRenderer.Path[] {MeshletRenderer.Path.MESH_SHADERS, MeshletRenderer.Path.INDEXED}
                    : new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED};
                ForwardShading[] settings = ForwardShading.values();
                System.out.printf(Locale.ROOT, "Forward shading by type on %s, %d x %d, 30,000 rocks (%,d triangles"
                    + " each) on the terrain, the sun:%n", device.gpu().name(), WIDTH, HEIGHT, 20 * (1L << 12));
                Sample[][][][] samples = new Sample[paths.length][BINS.length][settings.length][ROUNDS];
                for (int round = -1; round < ROUNDS; round++) { // round -1 warms the GPU's clocks up, uncounted
                    for (int p = 0; p < paths.length; p++) {
                        renderer.setPath(paths[p]);
                        for (int b = 0; b < BINS.length; b++) {
                            gpu.setMaterials(tables.get(b));
                            for (int s = 0; s < settings.length; s++) {
                                Sample sample = measure(device, renderer, view, camera, target, queries, period,
                                    settings[s], tables.get(b));
                                if (round >= 0) {
                                    samples[p][b][s][round] = sample;
                                }
                                System.out.printf(Locale.ROOT, "  round %d, %s, %2d bins, %-8s: frame %.3f ms GPU,"
                                    + " culling %.3f ms, draws %.3f ms, record %.3f ms CPU, %d draws, %,d meshlets%n",
                                    round, pathName(paths[p]), BINS[b], settings[s], sample.gpu, sample.culling,
                                    sample.draws, sample.cpu, sample.drawCalls, view.visibleMeshlets());
                            }
                        }
                    }
                }
                System.out.println("Ranges over the rounds (of each round's median):");
                for (int p = 0; p < paths.length; p++) {
                    for (int b = 0; b < BINS.length; b++) {
                        for (int s = 0; s < settings.length; s++) {
                            Sample[] all = samples[p][b][s];
                            System.out.printf(Locale.ROOT, "  | %s | %d | %s | %s | %s | %s | %s | %d |%n",
                                pathName(paths[p]), BINS[b], settings[s], range(all, Sample::gpu),
                                range(all, Sample::culling), range(all, Sample::draws), range(all, Sample::cpu),
                                all[0].drawCalls);
                        }
                    }
                }
                newType(device, renderer, view, camera, target);
            } finally {
                gpu.setMaterials(null);
                for (Materials table : tables) {
                    table.close();
                }
                vkDestroyQueryPool(device.handle(), queries, null);
            }
        }
    }

    /**
     * Compile times of a fresh device (its pipeline cache empty; the driver's own disk cache as earlier runs left it):
     * the renderer's own pipelines, the single shader's among them, and the pipelines by type of 1, 4 and 12 bins, each
     * table on a device of its own.
     */
    @Test
    void compileTimes() {
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("benchmark").surfaces(false).build()) {
            System.out.printf(Locale.ROOT, "Pipeline compile times on %s:%n", instance.bestGpu().name());
            for (int bins : BINS) {
                for (MeshletRenderer.Path path : new MeshletRenderer.Path[] {MeshletRenderer.Path.MESH_SHADERS,
                    MeshletRenderer.Path.INDEXED}) {
                    try (VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
                         GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 12, 64, 4));
                         GpuScene gpu = new GpuScene(device, pool, 16);
                         Materials materials = table(device, bins)) {
                        long t0 = System.nanoTime();
                        try (MeshletRenderer renderer = new MeshletRenderer(device, gpu, VK_FORMAT_R8G8B8A8_UNORM,
                            VK_FORMAT_D32_SFLOAT)) {
                            double made = (System.nanoTime() - t0) / 1e6;
                            if (path == MeshletRenderer.Path.MESH_SHADERS && !device.meshShaders()) {
                                continue;
                            }
                            renderer.setPath(path);
                            gpu.setMaterials(materials);
                            renderer.forwardPipelines();
                            System.out.printf(Locale.ROOT, "  %2d bins, %s: renderer (single shader's pipelines and the"
                                + " rest) %.1f ms; by type: %d pipelines, %.1f ms%n", bins, pathName(path), made,
                                renderer.forwardPipelineCount(), renderer.forwardPipelineNanos() / 1e6);
                            gpu.setMaterials(null);
                        }
                    }
                }
            }
            cold(instance);
        }
    }

    /**
     * Cold compiles, the driver's disk cache bypassed: each pipeline made with a second specialization constant (id 1,
     * which no shader declares) of a value never used before, so the driver cannot find it compiled. The single
     * shader's opaque pipeline against those of one shading bin by type; on the lean mesh path and the indexed path.
     */
    private static void cold(VulkanInstance instance) {
        long salt = System.nanoTime();
        boolean info = instance.bestGpu().supports(org.lwjgl.vulkan.AMDShaderInfo.VK_AMD_SHADER_INFO_EXTENSION_NAME);
        try (VulkanDevice device = info ? VulkanDevice.create(instance.bestGpu(),
            List.of(org.lwjgl.vulkan.AMDShaderInfo.VK_AMD_SHADER_INFO_EXTENSION_NAME))
            : VulkanDevice.createHeadless(instance.bestGpu())) {
            shaderInfo = info;
            String[][] variants = {
                {"single shader (old, wrong)", "", "leanMain", "fragmentMain", "0"},
                {"generated single shader", "single", "singleLeanMain", "singleFragmentMain", "0"},
                {"metallic-roughness", "metallicRoughness", "metallicRoughnessLeanMain",
                    "metallicRoughnessFragmentMain", "0"},
                {"metallic-roughness + coat + sheen", "metallicRoughness", "metallicRoughnessLeanMain",
                    "metallicRoughnessFragmentMain", Integer.toString(Material.FEATURE_CLEARCOAT
                    | Material.FEATURE_SHEEN)},
                {"glow", "glow", "glowLeanMain", "glowFragmentMain", "0"},
            };
            for (int repeat = 0; repeat < 3; repeat++) {
                for (String[] v : variants) {
                    double[] ms = new double[2];
                    String[] registers = {"", ""};
                    for (int lean = 0; lean < 2; lean++) {
                        if (lean == 1 && !(device.meshShaders() && device.barycentrics() && device.primitiveIds())) {
                            ms[lean] = Double.NaN;
                            continue;
                        }
                        String geometry = lean == 1 ? "shaders/meshlets/lean.spv" : "shaders/meshlets/draw.spv";
                        String fragment = v[1].isEmpty() ? geometry : v[1].equals("single") ? SINGLE_FULL
                            : "shaders/material-types/" + v[1] + (lean == 1 ? "ForwardLean.spv" : "Forward.spv");
                        try (var g = com.truesim.engine.vulkan.ShaderModule.load(device, geometry);
                             var f = com.truesim.engine.vulkan.ShaderModule.load(device, fragment)) {
                            long t0 = System.nanoTime();
                            try (var pipeline = com.truesim.engine.vulkan.Pipeline.graphics(device)
                                .stage(lean == 1 ? org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT
                                    : org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT, g,
                                    lean == 1 ? (v[1].isEmpty() || v[1].equals("single") ? "meshLeanMain"
                                        : "meshLeanTypedMain") : "vertexMain")
                                .stage(org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT, f, lean == 1 ? v[2] : v[3])
                                .pushConstants(com.truesim.engine.render.layout.ForwardPush.BYTES)
                                .constants(Integer.parseInt(v[4]), 1, 2, 3, (int) (salt++)) // id 4: no shader's
                                .color(VK_FORMAT_R8G8B8A8_UNORM).depth(VK_FORMAT_D32_SFLOAT).build()) {
                                ms[lean] = (System.nanoTime() - t0) / 1e6;
                                if (repeat == 0) {
                                    registers[lean] = registers(device, pipeline.handle());
                                }
                            }
                        }
                    }
                    System.out.printf(Locale.ROOT, "  cold, %s: lean mesh path %.1f ms%s, indexed %.1f ms%s%n", v[0],
                        ms[1], registers[1], ms[0], registers[0]);
                }
            }
        }
    }

    private static boolean shaderInfo;

    /**
     * The fragment stage's registers of {@code pipeline} (VK_AMD_shader_info): vector and scalar registers used, and
     * the waves a SIMD holds by its vector registers (the physical ones over those used); empty without the extension.
     */
    private static String registers(VulkanDevice device, long pipeline) {
        if (!shaderInfo) {
            return "";
        }
        try (MemoryStack stack = stackPush()) {
            org.lwjgl.vulkan.VkShaderStatisticsInfoAMD info = org.lwjgl.vulkan.VkShaderStatisticsInfoAMD.create(
                stack.ncalloc(org.lwjgl.vulkan.VkShaderStatisticsInfoAMD.ALIGNOF, 1, org.lwjgl.vulkan.VkShaderStatisticsInfoAMD.SIZEOF));
            org.lwjgl.PointerBuffer size = stack.pointers(org.lwjgl.vulkan.VkShaderStatisticsInfoAMD.SIZEOF);
            int result = org.lwjgl.vulkan.AMDShaderInfo.vkGetShaderInfoAMD(device.handle(), pipeline,
                org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT,
                org.lwjgl.vulkan.AMDShaderInfo.VK_SHADER_INFO_TYPE_STATISTICS_AMD, size,
                org.lwjgl.system.MemoryUtil.memByteBuffer(info.address(), org.lwjgl.vulkan.VkShaderStatisticsInfoAMD.SIZEOF));
            if (result != 0) {
                return " (no shader info: " + result + ")";
            }
            int vgprs = info.resourceUsage().numUsedVgprs();
            return String.format(Locale.ROOT, " (%d VGPRs, %d SGPRs, %d waves/SIMD of %d physical VGPRs)", vgprs,
                info.resourceUsage().numUsedSgprs(), Math.min(16, info.numPhysicalVgprs() / Math.max(1, vgprs)),
                info.numPhysicalVgprs());
        }
    }

    /**
     * A shading bin first drawn while frames run: the frame that rebuilds the graph (and by type, makes the bin's
     * pipelines), against the frames around it, CPU time of recording.
     */
    private static void newType(VulkanDevice device, MeshletRenderer renderer, RenderView view, Camera camera,
        ExternalImage target) {
        renderer.setPath(MeshletRenderer.Path.AUTO);
        for (ForwardShading setting : ForwardShading.values()) {
            for (int extra = 0; extra < 3; extra++) {
                try (Materials materials = table(device, 4); FrameGraph graph = new FrameGraph(device, 2)) {
                    renderer.scene().setMaterials(materials);
                    SceneView main = new SceneView(graph.addView("main").size(WIDTH, HEIGHT), view, camera);
                    main.port().add(MeshletBenchmark.forward(main)).output(Keys.SCENE_COLOR, target);
                    graph.settings().set(RenderSettings.FORWARD_SHADING, setting);
                    for (int i = 0; i < 20; i++) {
                        device.submitAndWait(device.graphics(), graph::record);
                    }
                    double before = recordMillis(device, graph);
                    long nanos = renderer.forwardPipelineNanos();
                    int builds = graph.builds();
                    // A bin not drawn yet (a coat over metallic-roughness with another feature each time), on rock 1.
                    materials.set(1, material(5 + extra).feature(ClearcoatParameters.DEFAULTS));
                    double rebuilt = recordMillis(device, graph);
                    double after = recordMillis(device, graph);
                    System.out.printf(Locale.ROOT, "  a new bin, %s: the frame recording it %.2f ms CPU (rebuilt %d"
                        + " times; %.1f ms making pipelines), before %.2f ms, after %.2f ms%n", setting, rebuilt,
                        graph.builds() - builds, (renderer.forwardPipelineNanos() - nanos) / 1e6, before, after);
                    renderer.scene().setMaterials(null);
                }
            }
        }
    }

    /** CPU time of recording one frame of {@code graph}, waited for. */
    private static double recordMillis(VulkanDevice device, FrameGraph graph) {
        long[] took = new long[1];
        device.submitAndWait(device.graphics(), commands -> {
            long t0 = System.nanoTime();
            graph.record(commands);
            took[0] = System.nanoTime() - t0;
        });
        return took[0] / 1e6;
    }

    /**
     * One configuration: a graph of clustered forward shading with {@code setting}, warmed, then GPU time per frame
     * (batches of frames recorded back to back, between timestamps), the culling and draw passes' GPU time (the graph's
     * timings, frame by frame) and CPU time of recording a frame; medians.
     */
    private static Sample measure(VulkanDevice device, MeshletRenderer renderer, RenderView view, Camera camera,
        ExternalImage target, long queries, double period, ForwardShading setting, Materials materials) {
        try (FrameGraph graph = new FrameGraph(device, 2)) {
            SceneView main = new SceneView(graph.addView("main").size(WIDTH, HEIGHT), view, camera);
            main.port().add(MeshletBenchmark.forward(main)).output(Keys.SCENE_COLOR, target);
            graph.settings().set(RenderSettings.FORWARD_SHADING, setting);
            for (int i = 0; i < 10; i++) {
                device.submitAndWait(device.graphics(), graph::record);
            }
            if (setting == ForwardShading.PER_TYPE && view.splitCategories() < materials.binCount()) {
                throw new IllegalStateException("not split by type: " + view.splitCategories());
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
            double[] draws = new double[TIMED];
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
                    } else if (pass.equals("meshlets early") || pass.equals("meshlets late")) {
                        draws[i] += ms;
                    }
                }
            }
            graph.timings(false);
            return new Sample(median(gpu), median(culling), median(draws), median(cpu), drawCalls(setting, materials));
        }
    }

    /**
     * Draws the forward passes record per frame, both phases: the single shader one per opaque raster bin, by type one
     * per shading bin and opaque raster bin its materials draw in.
     */
    private static int drawCalls(ForwardShading setting, Materials materials) {
        if (setting == ForwardShading.SINGLE) {
            return 2 * MeshletRenderer.BIN_BLENDED;
        }
        int calls = 0;
        for (int bin = 0; bin < materials.binCount(); bin++) {
            calls += Integer.bitCount(materials.binRasters(bin) & ((1 << MeshletRenderer.BIN_BLENDED) - 1));
        }
        return 2 * calls;
    }

    private static String pathName(MeshletRenderer.Path path) {
        return path == MeshletRenderer.Path.INDEXED ? "indexed" : "mesh shaders";
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
