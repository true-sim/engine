package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_64_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_WAIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_TYPE_TIMESTAMP;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCmdResetQueryPool;
import static org.lwjgl.vulkan.VK10.vkCreateQueryPool;
import static org.lwjgl.vulkan.VK10.vkDestroyQueryPool;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetQueryPoolResults;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK13.vkCmdWriteTimestamp2;

import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.Images;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;
import java.lang.foreign.ValueLayout;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexedIndirect;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.vkCmdSetViewport;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_INFO;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;
import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

/**
 * GPU time of the meshlet renderer at 1920 x 1080 on both paths, measured with timestamps: a field of rocks (a 65k
 * triangle mesh with a cluster hierarchy, many instances) and a 2-million-triangle terrain at a grazing angle, both with
 * 1 pixel of error allowed. Run with {@code TRUESIM_BENCHMARK=true ./gradlew :engine-render:test --tests
 * '*MeshletBenchmark'}; results are printed.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class MeshletBenchmark {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int FRAMES = 60;

    /** A bumpy sphere: a latitude-longitude grid of {@code rings} x {@code 2 rings}, with noise on the radius. */
    static MeshData rock(int rings) {
        return rockBuilder(rings).buildHierarchy();
    }

    /** The rock's builder, to build it as a hierarchy or as discrete LODs. */
    static MeshBuilder rockBuilder(int rings) {
        int columns = rings * 2;
        int count = (rings + 1) * (columns + 1);
        float[] positions = new float[count * 3];
        float[] normals = new float[count * 3];
        for (int r = 0; r <= rings; r++) {
            for (int c = 0; c <= columns; c++) {
                double theta = Math.PI * r / rings;
                double phi = 2 * Math.PI * (c % columns) / columns;
                double nx = Math.sin(theta) * Math.cos(phi);
                double ny = Math.cos(theta);
                double nz = Math.sin(theta) * Math.sin(phi);
                double radius = 1 + 0.15 * Math.sin(5 * nx + 3 * ny) * Math.cos(4 * nz - 2 * nx) + 0.05 * Math.sin(17
                    * ny + 11 * nz);
                int v = r * (columns + 1) + c;
                positions[3 * v] = (float) (nx * radius);
                positions[3 * v + 1] = (float) (ny * radius);
                positions[3 * v + 2] = (float) (nz * radius);
                normals[3 * v] = (float) nx;
                normals[3 * v + 1] = (float) ny;
                normals[3 * v + 2] = (float) nz;
            }
        }
        int[] indices = new int[rings * columns * 6];
        int at = 0;
        for (int r = 0; r < rings; r++) {
            for (int c = 0; c < columns; c++) {
                int a = r * (columns + 1) + c;
                int[] quad = {a, a + 1, a + columns + 1, a + 1, a + columns + 2, a + columns + 1};
                System.arraycopy(quad, 0, indices, at, 6);
                at += 6;
            }
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), count).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).submesh(indices);
    }

    /** A bumpy {@code n} x {@code n} grid, 1 unit per quad, centered, facing +y. */
    static MeshData terrain(int n) {
        int count = (n + 1) * (n + 1);
        float[] positions = new float[count * 3];
        float[] normals = new float[count * 3];
        for (int z = 0; z <= n; z++) {
            for (int x = 0; x <= n; x++) {
                int v = z * (n + 1) + x;
                positions[3 * v] = x - n / 2f;
                positions[3 * v + 1] = (float) (3 * Math.sin(x / 9.0) * Math.cos(z / 13.0) + Math.sin(x * z / 4000.0));
                positions[3 * v + 2] = z - n / 2f;
                normals[3 * v + 1] = 1;
            }
        }
        int[] indices = new int[n * n * 6];
        int at = 0;
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int a = z * (n + 1) + x;
                int[] quad = {a, a + n + 1, a + 1, a + 1, a + n + 1, a + n + 2};
                System.arraycopy(quad, 0, indices, at, 6);
                at += 6;
            }
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), count).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).submesh(indices).buildHierarchy();
    }

    @Test
    void renderLargeScenes() {
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("benchmark").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 100_000, 4));
             GpuImage color = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
             GpuImage depth = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_D32_SFLOAT,
                 VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
             MeshletRenderer renderer = new MeshletRenderer(device, pool, new MeshletRenderer.Limits(100_000,
                 1 << 20, 65_535, 80_000_000), VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
             MemoryStack stack = stackPush()) {
            MeshData rockMesh = rock(128);
            MeshData terrainMesh = terrain(1024);
            int rock = pool.add(rockMesh);
            int ground = pool.add(terrainMesh);
            MeshData rockLods = rockBuilder(128).buildDiscrete(8, 0.5);
            int rockLod = pool.add(rockLods);
            System.out.printf(Locale.ROOT, "Meshlet benchmark on %s (%s), %d x %d, 1 pixel of error:%n",
                device.gpu().name(), device.meshShaders() ? "mesh shaders" : "no mesh shaders", WIDTH, HEIGHT);
            System.out.printf(Locale.ROOT, "  rock: %,d triangles, %,d meshlets in %d levels; terrain: %,d triangles,"
                + " %,d meshlets in %d levels%n", triangles(rockMesh),
                rockMesh.meshletCount(), rockMesh.levels().size(), triangles(terrainMesh),
                terrainMesh.meshletCount(), terrainMesh.levels().size());
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device.gpu().handle(), properties);
            double period = properties.limits().timestampPeriod();
            LongBuffer pool2 = stack.mallocLong(1);
            vkCreateQueryPool(device.handle(), VkQueryPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                .queryType(VK_QUERY_TYPE_TIMESTAMP)
                .queryCount(2), null, pool2);
            long queries = pool2.get(0);
            Camera camera = new Camera();
            Classic classic = new Classic(device);
            float[] placed = new float[30_000 * 8]; // x, y, z, qx, qy, qz, qw, scale
            try {
                for (int count : new int[] {1000, 3000, 10_000, 30_000}) {
                    SplittableRandom random = new SplittableRandom(7);
                    for (int i = 0; i < count; i++) {
                        double qx = random.nextDouble(-1, 1);
                        double qy = random.nextDouble(-1, 1);
                        double qz = random.nextDouble(-1, 1);
                        double qw = random.nextDouble(-1, 1);
                        double length = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
                        float scale = (float) random.nextDouble(1, 6);
                        float[] p = {(float) random.nextDouble(-300, 300), (float) random.nextDouble(-150, 150),
                            (float) random.nextDouble(-1500, -10), (float) (qx / length), (float) (qy / length),
                            (float) (qz / length), (float) (qw / length), scale};
                        System.arraycopy(p, 0, placed, 8 * i, 8);
                        renderer.setInstance(i, rock, p[0], p[1], p[2], p[3], p[4], p[5], p[6], scale, scale, scale);
                    }
                    renderer.setInstanceCount(count);
                    renderer.setView(camera.set(0, 0, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1), 1,
                        MeshletRenderer.MODE_LIT);
                    String scene = String.format(Locale.ROOT, "%,d rocks (%,d triangles in all)", count,
                        (long) count * triangles(rockMesh));
                    measure(scene, device, renderer, color, depth, queries, period);
                    // Without meshlets: the whole mesh per instance, no culling.
                    var finest = rockMesh.levels().getLast();
                    int drawn = classic.draws(pool, rock, rockMesh, count, i -> finest);
                    double brute = time(device, color, depth, queries, period,
                        commands -> classic.record(commands, renderer, pool, color, depth, drawn));
                    System.out.printf(Locale.ROOT, "  %s, without meshlets, full detail: %.3f ms median, %,d "
                        + "triangles drawn%n", scene, brute, (long) drawn * triangles(rockMesh));
                    // The classic engine: discrete LODs picked per instance and frustum culling, on the CPU.
                    for (int i = 0; i < count; i++) {
                        float[] p = java.util.Arrays.copyOfRange(placed, 8 * i, 8 * i + 8);
                        renderer.setInstance(i, rockLod, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[7], p[7]);
                    }
                    long cpu = 0;
                    int lodDrawn = 0;
                    long[] lodTriangles = new long[1];
                    for (int repeat = 0; repeat < 20; repeat++) {
                        long t0 = System.nanoTime();
                        lodTriangles[0] = 0;
                        lodDrawn = classic.draws(pool, rockLod, rockLods, count,
                            i -> pickLevel(rockLods, placed, i, camera, lodTriangles));
                        cpu = repeat == 0 ? Long.MAX_VALUE : Math.min(cpu, System.nanoTime() - t0);
                    }
                    int lodCount = lodDrawn;
                    double lod = time(device, color, depth, queries, period,
                        commands -> classic.record(commands, renderer, pool, color, depth, lodCount));
                    System.out.printf(Locale.ROOT, "  %s, without meshlets, discrete LODs and culling on the CPU: "
                        + "%.3f ms median (CPU %.3f ms), %,d instances, %,d triangles drawn%n", scene, lod, cpu / 1e6,
                        lodCount, lodTriangles[0]);
                }
                renderer.setInstance(0, ground, 0, -6, 0, 0, 0, 0, 1, 1, 1, 1);
                renderer.setInstanceCount(1);
                renderer.setView(camera.set(0, -0.08, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1), 1,
                    MeshletRenderer.MODE_LIT);
                measure("terrain at a grazing angle", device, renderer, color, depth, queries, period);
                var terrainFinest = terrainMesh.levels().getLast();
                int terrainDraws = classic.draws(pool, ground, terrainMesh, 1, i -> terrainFinest);
                System.out.printf(Locale.ROOT, "  terrain at a grazing angle, without meshlets, full detail: %.3f ms median,"
                    + " %,d triangles drawn%n", time(device, color, depth, queries, period,
                        commands -> classic.record(commands, renderer, pool, color, depth, terrainDraws)),
                    triangles(terrainMesh));
            } finally {
                vkDestroyQueryPool(device.handle(), queries, null);
            }
        }
    }

    /**
     * The classic choice for instance {@code i}: null outside the frustum, else the coarsest discrete level whose
     * error, seen from the instance's nearest point, is at most a pixel. Adds the triangles drawn.
     */
    private static com.truesim.scene.assets.mesh.MeshLevel pickLevel(MeshData mesh, float[] placed, int i,
        Camera camera, long[] triangles) {
        double x = placed[8 * i];
        double y = placed[8 * i + 1];
        double z = placed[8 * i + 2];
        double scale = placed[8 * i + 7];
        double radius = mesh.bounds().radius() * scale;
        float[] planes = camera.planes();
        for (int p = 0; p < 6; p++) {
            if (planes[4 * p] * x + planes[4 * p + 1] * y + planes[4 * p + 2] * z + planes[4 * p + 3] < -radius) {
                return null;
            }
        }
        double distance = Math.sqrt(x * x + y * y + z * z) - radius;
        var chosen = mesh.levels().getLast();
        if (distance > 0) {
            for (var level : mesh.levels()) {
                if (level.error() * scale * camera.projectionScale() / distance <= 1) {
                    chosen = level;
                    break;
                }
            }
        }
        for (int m = chosen.firstMeshlet(); m < chosen.firstMeshlet() + chosen.meshletCount(); m++) {
            triangles[0] += mesh.meshletTriangleCount(m);
        }
        return chosen;
    }

    /** GPU time of a frame {@code record} records, median of {@link #FRAMES}, in milliseconds. */
    private static double time(VulkanDevice device, GpuImage color, GpuImage depth, long queries, double period,
        java.util.function.Consumer<VkCommandBuffer> record) {
        double[] times = new double[FRAMES];
        for (int frame = -10; frame < FRAMES; frame++) {
            device.submitAndWait(device.graphics(), commands -> {
                vkCmdResetQueryPool(commands, queries, 0, 2);
                Images.transition(commands, color.handle(), VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, VK_ACCESS_2_NONE,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
                Images.transition(commands, depth.handle(), VK_IMAGE_ASPECT_DEPTH_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, VK_ACCESS_2_NONE,
                    VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                    VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 0);
                record.accept(commands);
                vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 1);
            });
            if (frame >= 0) {
                try (MemoryStack stack = stackPush()) {
                    LongBuffer stamps = stack.mallocLong(2);
                    vkGetQueryPoolResults(device.handle(), queries, 0, 2, stamps, 8,
                        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
                    times[frame] = (stamps.get(1) - stamps.get(0)) * period / 1e6;
                }
            }
        }
        Arrays.sort(times);
        return times[FRAMES / 2];
    }

    /** Draws whole meshes, one indexed indirect draw per instance: the way without meshlets. */
    private static final class Classic {
        private final Pipeline pipeline;
        private final GpuBuffer commands;

        Classic(VulkanDevice device) {
            try (ShaderModule shaders = ShaderModule.load(device, "shaders/test/classic.spv")) {
                pipeline = Pipeline.graphics(device)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT, shaders, "vertexMain")
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shaders, "fragmentMain")
                    .color(VK_FORMAT_R8G8B8A8_UNORM)
                    .depth(VK_FORMAT_D32_SFLOAT)
                    .pushConstants(40)
                    .build();
            }
            commands = GpuBuffer.hostVisible(device, 20L * 100_000, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
        }

        /** Writes a draw of {@code levelOf}'s level (none for null) per instance; returns how many. */
        int draws(GeometryPool pool, int mesh, MeshData data, int count,
            java.util.function.IntFunction<com.truesim.scene.assets.mesh.MeshLevel> levelOf) {
            var table = pool.meshTable().memory();
            int vertexBase = table.get(ValueLayout.JAVA_INT, (long) GeometryPool.MESH_ENTRY_BYTES * mesh + 12);
            int indexBase = table.get(ValueLayout.JAVA_INT, (long) GeometryPool.MESH_ENTRY_BYTES * mesh + 56);
            int drawn = 0;
            for (int i = 0; i < count; i++) {
                var level = levelOf.apply(i);
                if (level == null) {
                    continue;
                }
                int last = level.firstMeshlet() + level.meshletCount() - 1;
                int first = data.meshletTriangleOffset(level.firstMeshlet());
                int end = data.meshletTriangleOffset(last) + 3 * data.meshletTriangleCount(last);
                long o = 20L * drawn++;
                var m = commands.memory();
                m.set(ValueLayout.JAVA_INT, o, end - first);
                m.set(ValueLayout.JAVA_INT, o + 4, 1);
                m.set(ValueLayout.JAVA_INT, o + 8, indexBase + first);
                m.set(ValueLayout.JAVA_INT, o + 12, vertexBase);
                m.set(ValueLayout.JAVA_INT, o + 16, i);
            }
            return drawn;
        }

        void record(VkCommandBuffer buffer, MeshletRenderer renderer, GeometryPool pool, GpuImage color,
            GpuImage depth, int draws) {
            try (MemoryStack stack = stackPush()) {
                VkRenderingAttachmentInfo.Buffer colorAttachment = VkRenderingAttachmentInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                    .imageView(color.view())
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                VkRenderingAttachmentInfo depthAttachment = VkRenderingAttachmentInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                    .imageView(depth.view())
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                VkRenderingInfo rendering = VkRenderingInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
                    .layerCount(1)
                    .pColorAttachments(colorAttachment)
                    .pDepthAttachment(depthAttachment);
                rendering.renderArea().extent().set(WIDTH, HEIGHT);
                vkCmdBeginRendering(buffer, rendering);
                pipeline.bind(buffer);
                vkCmdSetViewport(buffer, 0, VkViewport.calloc(1, stack).width(WIDTH).height(HEIGHT).maxDepth(1));
                vkCmdSetScissor(buffer, 0, VkRect2D.calloc(1, stack).extent(e -> e.set(WIDTH, HEIGHT)));
                long push = stack.nmalloc(8, 40);
                MemoryUtil.memPutLong(push, renderer.viewBuffer().deviceAddress());
                MemoryUtil.memPutLong(push + 8, pool.meshTable().deviceAddress());
                MemoryUtil.memPutLong(push + 16, renderer.instanceBuffer().deviceAddress());
                MemoryUtil.memPutLong(push + 24, pool.positions().deviceAddress());
                MemoryUtil.memPutLong(push + 32, pool.attributes().deviceAddress());
                pipeline.push(buffer, push, 40);
                vkCmdBindIndexBuffer(buffer, pool.indices().handle(), 0, VK_INDEX_TYPE_UINT32);
                if (draws > 0) {
                    vkCmdDrawIndexedIndirect(buffer, commands.handle(), 0, draws, 20);
                }
                vkCmdEndRendering(buffer);
            }
        }
    }

    private static long triangles(MeshData mesh) {
        long full = 0;
        var finest = mesh.levels().getLast();
        for (int m = finest.firstMeshlet(); m < finest.firstMeshlet() + finest.meshletCount(); m++) {
            full += mesh.meshletTriangleCount(m);
        }
        return full;
    }

    private static void measure(String scene, VulkanDevice device, MeshletRenderer renderer, GpuImage color,
        GpuImage depth, long queries, double period) {
        for (MeshletRenderer.Path path : device.meshShaders()
            ? new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED, MeshletRenderer.Path.MESH_SHADERS}
            : new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED}) {
            renderer.setPath(path);
            double[] times = new double[FRAMES];
            for (int frame = -10; frame < FRAMES; frame++) {
                device.submitAndWait(device.graphics(), commands -> {
                    vkCmdResetQueryPool(commands, queries, 0, 2);
                    Images.transition(commands, color.handle(), VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                        VK_ACCESS_2_NONE, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
                    Images.transition(commands, depth.handle(), VK_IMAGE_ASPECT_DEPTH_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                        VK_ACCESS_2_NONE, VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT
                            | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                        VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                    vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 0);
                    renderer.record(commands, color.view(), depth.view(), WIDTH, HEIGHT, 0.1f, 0.12f, 0.15f);
                    vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 1);
                });
                if (frame >= 0) {
                    try (MemoryStack stack = stackPush()) {
                        LongBuffer stamps = stack.mallocLong(2);
                        vkGetQueryPoolResults(device.handle(), queries, 0, 2, stamps, 8,
                            VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
                        times[frame] = (stamps.get(1) - stamps.get(0)) * period / 1e6;
                    }
                }
            }
            Arrays.sort(times);
            System.out.printf(Locale.ROOT, "  %s, %s: %.3f ms median, %.3f ms best, %,d meshlets drawn%n", scene,
                path == MeshletRenderer.Path.INDEXED ? "indexed" : "mesh shaders", times[FRAMES / 2], times[0],
                renderer.visibleMeshlets());
        }
    }
}
