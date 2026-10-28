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
import static org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_MEMORY_BARRIER_2;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.render.shadows.RayTracedShadows;
import com.truesim.engine.render.shadows.ShadowMapShadows;
import com.truesim.engine.render.shadows.CascadedShadowMaps;
import com.truesim.engine.render.shadows.InlineRayShadows;
import com.truesim.engine.graph.Technique;
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
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

/**
 * GPU time of the meshlet renderer at 1920 x 1080 on both paths, measured with timestamps: a field of rocks (an 82k
 * triangle mesh with a cluster hierarchy, many instances) and a 2-million-triangle terrain at a grazing angle, both with
 * 1 pixel of error allowed. Run with {@code TRUESIM_BENCHMARK=true ./gradlew :engine-render:test --tests
 * '*MeshletBenchmark'}; results are printed.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class MeshletBenchmark {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int FRAMES = 60;

    /**
     * A bumpy rock: an icosahedron subdivided {@code subdivisions} times (20 x 4^subdivisions triangles), with noise on
     * the radius. Welded, without poles or seams, so simplification is not pinned anywhere.
     */
    static MeshData rock(int subdivisions) {
        return rockBuilder(subdivisions).buildHierarchy();
    }

    /** The rock's builder, to build it as a hierarchy or as discrete LODs. */
    static MeshBuilder rockBuilder(int subdivisions) {
        double t = (1 + Math.sqrt(5)) / 2;
        List<double[]> vertices = new ArrayList<>(List.of(new double[] {-1, t, 0}, new double[] {1, t, 0},
            new double[] {-1, -t, 0}, new double[] {1, -t, 0}, new double[] {0, -1, t}, new double[] {0, 1, t},
            new double[] {0, -1, -t}, new double[] {0, 1, -t}, new double[] {t, 0, -1}, new double[] {t, 0, 1},
            new double[] {-t, 0, -1}, new double[] {-t, 0, 1}));
        int[] faces = {0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11, 1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1,
            8, 3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9, 4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1};
        for (int s = 0; s < subdivisions; s++) {
            Map<Long, Integer> middles = new HashMap<>();
            int[] next = new int[faces.length * 4];
            int at = 0;
            for (int f = 0; f < faces.length; f += 3) {
                int a = faces[f];
                int b = faces[f + 1];
                int c = faces[f + 2];
                int ab = middle(vertices, middles, a, b);
                int bc = middle(vertices, middles, b, c);
                int ca = middle(vertices, middles, c, a);
                for (int i : new int[] {a, ab, ca, b, bc, ab, c, ca, bc, ab, bc, ca}) {
                    next[at++] = i;
                }
            }
            faces = next;
        }
        float[] positions = new float[vertices.size() * 3];
        for (int v = 0; v < vertices.size(); v++) {
            double[] p = vertices.get(v);
            double length = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            double x = p[0] / length;
            double y = p[1] / length;
            double z = p[2] / length;
            double radius = 1 + 0.15 * Math.sin(5 * x + 3 * y) * Math.cos(4 * z - 2 * x) + 0.05 * Math.sin(17 * y
                + 11 * z);
            positions[3 * v] = (float) (x * radius);
            positions[3 * v + 1] = (float) (y * radius);
            positions[3 * v + 2] = (float) (z * radius);
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), vertices.size()).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals(positions, faces)).submesh(faces);
    }

    /** The vertex halfway between a and b, made once per edge. */
    private static int middle(List<double[]> vertices, Map<Long, Integer> middles, int a, int b) {
        return middles.computeIfAbsent((long) Math.min(a, b) << 32 | Math.max(a, b), k -> {
            double[] p = vertices.get(a);
            double[] q = vertices.get(b);
            vertices.add(new double[] {(p[0] + q[0]) / 2, (p[1] + q[1]) / 2, (p[2] + q[2]) / 2});
            return vertices.size() - 1;
        });
    }

    /** Smooth normals of the surface: per vertex, the normalized sum of its triangles' area-weighted normals. */
    static float[] normals(float[] positions, int[] indices) {
        double[] sums = new double[positions.length];
        for (int i = 0; i < indices.length; i += 3) {
            int a = 3 * indices[i];
            int b = 3 * indices[i + 1];
            int c = 3 * indices[i + 2];
            double ux = positions[b] - positions[a];
            double uy = positions[b + 1] - positions[a + 1];
            double uz = positions[b + 2] - positions[a + 2];
            double vx = positions[c] - positions[a];
            double vy = positions[c + 1] - positions[a + 1];
            double vz = positions[c + 2] - positions[a + 2];
            double nx = uy * vz - uz * vy;
            double ny = uz * vx - ux * vz;
            double nz = ux * vy - uy * vx;
            for (int corner : new int[] {a, b, c}) {
                sums[corner] += nx;
                sums[corner + 1] += ny;
                sums[corner + 2] += nz;
            }
        }
        float[] normals = new float[positions.length];
        for (int v = 0; v < normals.length; v += 3) {
            double length = Math.sqrt(sums[v] * sums[v] + sums[v + 1] * sums[v + 1] + sums[v + 2] * sums[v + 2]);
            for (int i = 0; i < 3; i++) {
                normals[v + i] = (float) (sums[v + i] / length);
            }
        }
        return normals;
    }

    /** A bumpy {@code n} x {@code n} grid, 1 unit per quad, centered, facing +y. */
    static MeshData terrain(int n) {
        int count = (n + 1) * (n + 1);
        float[] positions = new float[count * 3];
        for (int z = 0; z <= n; z++) {
            for (int x = 0; x <= n; x++) {
                int v = z * (n + 1) + x;
                positions[3 * v] = x - n / 2f;
                positions[3 * v + 1] = (float) (3 * Math.sin(x / 9.0) * Math.cos(z / 13.0) + Math.sin(x * z / 4000.0));
                positions[3 * v + 2] = z - n / 2f;
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
            .attribute(VertexAttribute.NORMAL, normals(positions, indices)).submesh(indices).buildHierarchy();
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
                 1 << 20, 65_535, 80_000_000, 4), VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
             MemoryStack stack = stackPush()) {
            MeshData rockMesh = rock(6);
            MeshData terrainMesh = terrain(1024);
            int rock = pool.add(rockMesh);
            int ground = pool.add(terrainMesh);
            MeshData rockLods = rockBuilder(6).buildDiscrete(8, 0.5);
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
            Classic classic = new Classic(device, new int[] {rock, rockLod, ground}, rockMesh, rockLods,
                terrainMesh);
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
                    double brute = time(device, color, depth, queries, period, 1,
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
                    double lod = time(device, color, depth, queries, period, BATCH,
                        commands -> classic.record(commands, renderer, pool, color, depth, lodCount));
                    System.out.printf(Locale.ROOT, "  %s, without meshlets, discrete LODs and culling on the CPU: "
                        + "%.3f ms median (CPU %.3f ms), %,d instances, %,d triangles drawn%n", scene, lod, cpu / 1e6,
                        lodCount, lodTriangles[0]);
                }
                occlusion(device, renderer, color, depth, queries, period, rock, ground, placed, camera);
                classicLod(device, renderer, color, depth, queries, period, rock, rockLod, rockLods, placed, camera);
                shadows(device, pool, renderer, color, depth, queries, period, rock, rockMesh, ground, terrainMesh);
                lighting(device, renderer, color, depth, queries, period, rock, ground);
                renderer.setInstance(0, ground, 0, -6, 0, 0, 0, 0, 1, 1, 1, 1);
                renderer.setInstanceCount(1);
                renderer.setView(camera.set(0, -0.08, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1), 1,
                    MeshletRenderer.MODE_LIT);
                measure("terrain at a grazing angle", device, renderer, color, depth, queries, period);
                var terrainFinest = terrainMesh.levels().getLast();
                int terrainDraws = classic.draws(pool, ground, terrainMesh, 1, i -> terrainFinest);
                System.out.printf(Locale.ROOT, "  terrain at a grazing angle, without meshlets, full detail: %.3f ms median,"
                    + " %,d triangles drawn%n", time(device, color, depth, queries, period, BATCH,
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

    /**
     * GPU time of a frame {@code record} records, median of {@link #FRAMES}, in milliseconds; {@code batch} frames
     * back to back per timed submission (1 for long frames).
     */
    private static double time(VulkanDevice device, GpuImage color, GpuImage depth, long queries, double period,
        int batch, java.util.function.Consumer<VkCommandBuffer> record) {
        double[] times = new double[FRAMES];
        for (int frame = -5; frame < FRAMES; frame++) {
            device.submitAndWait(device.graphics(), commands -> {
                vkCmdResetQueryPool(commands, queries, 0, 2);
                vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 0);
                for (int i = 0; i < batch; i++) {
                    Images.transition(commands, color.handle(), VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                        VK_ACCESS_2_NONE, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
                    Images.transition(commands, depth.handle(), VK_IMAGE_ASPECT_DEPTH_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                        VK_ACCESS_2_NONE, VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT
                            | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                        VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                    record.accept(commands);
                    fullBarrier(commands);
                }
                vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 1);
            });
            if (frame >= 0) {
                try (MemoryStack stack = stackPush()) {
                    LongBuffer stamps = stack.mallocLong(2);
                    vkGetQueryPoolResults(device.handle(), queries, 0, 2, stamps, 8,
                        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
                    times[frame] = (stamps.get(1) - stamps.get(0)) * period / 1e6 / batch;
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
        private final GpuBuffer indices;
        private final java.util.Map<Integer, Integer> indexBases = new java.util.HashMap<>();

        /** Draws pool meshes {@code meshes}, as {@code data}: their 32-bit indices, in a buffer of its own. */
        Classic(VulkanDevice device, int[] meshes, MeshData... data) {
            long total = 0;
            for (MeshData mesh : data) {
                total += 3L * mesh.triangleCount();
            }
            java.lang.foreign.MemorySegment all = java.lang.foreign.Arena.ofAuto().allocate(4 * total, 4);
            long at = 0;
            for (int i = 0; i < meshes.length; i++) {
                indexBases.put(meshes[i], (int) at);
                java.lang.foreign.MemorySegment indexes = data[i].expandIndices();
                java.lang.foreign.MemorySegment.copy(indexes, 0, all, 4 * at, indexes.byteSize());
                at += indexes.byteSize() / 4;
            }
            indices = GpuBuffer.upload(device, all, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            try (ShaderModule shaders = ShaderModule.load(device, "shaders/test/classic.spv")) {
                pipeline = Pipeline.graphics(device)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT, shaders, "vertexMain")
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shaders, "fragmentMain")
                    .color(VK_FORMAT_R8G8B8A8_UNORM)
                    .depth(VK_FORMAT_D32_SFLOAT)
                    .pushConstants(48)
                    .build();
            }
            commands = GpuBuffer.hostVisible(device, 20L * 100_000, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
        }

        /** Writes a draw of {@code levelOf}'s level (none for null) per instance; returns how many. */
        int draws(GeometryPool pool, int mesh, MeshData data, int count,
            java.util.function.IntFunction<com.truesim.scene.assets.mesh.MeshLevel> levelOf) {
            var table = pool.meshTable().memory();
            int vertexBase = table.get(ValueLayout.JAVA_INT, (long) GeometryPool.MESH_ENTRY_BYTES * mesh + 12);
            int indexBase = indexBases.get(mesh);
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
                long push = stack.nmalloc(8, 48);
                MemoryUtil.memPutLong(push, renderer.viewBuffer().deviceAddress());
                MemoryUtil.memPutLong(push + 8, pool.meshTable().deviceAddress());
                MemoryUtil.memPutLong(push + 16, renderer.instanceBuffer().deviceAddress());
                MemoryUtil.memPutLong(push + 24, pool.positions().deviceAddress());
                MemoryUtil.memPutLong(push + 32, pool.attributes().deviceAddress());
                MemoryUtil.memPutLong(push + 40, renderer.materialsAddress());
                pipeline.push(buffer, push, 48);
                vkCmdBindIndexBuffer(buffer, indices.handle(), 0, VK_INDEX_TYPE_UINT32);
                if (draws > 0) {
                    vkCmdDrawIndexedIndirect(buffer, commands.handle(), 0, draws, 20);
                }
                vkCmdEndRendering(buffer);
            }
        }
    }

    /**
     * {@code count} point lights scattered over the nearest 200 x 200 units of {@link #rocksOnTerrain}, a little above
     * the ground: ranges of 6 to 15 units, random colors, irradiance up to {@code brightness} at 1 unit.
     */
    static void scatterLights(Lights lights, int count, double groundY, double brightness) {
        SplittableRandom random = new SplittableRandom(5);
        for (int i = 0; i < count; i++) {
            double x = random.nextDouble(-100, 100);
            double z = random.nextDouble(-205, -5);
            double y = groundY + terrainHeight(x, z) + random.nextDouble(1, 4);
            float range = (float) random.nextDouble(6, 15);
            lights.point(i, (float) x, (float) y, (float) z, range, (float) (brightness * random.nextDouble(0.1, 1)),
                (float) (brightness * random.nextDouble(0.1, 1)), (float) (brightness * random.nextDouble(0.1, 1)));
        }
        lights.setCount(count);
    }

    /**
     * Shading many lights, through the frame graph: 30,000 rocks on the terrain from above, as for the shadows, lit by
     * the sun alone or by hundreds to thousands of point lights through light clusters; shaded as the meshlets are
     * drawn (clustered forward) or once per pixel after drawing IDs (visibility buffer). Both write a storage-capable
     * image blitted into the target, so they finish the same way.
     */
    private static void lighting(VulkanDevice device, MeshletRenderer renderer, GpuImage color, GpuImage depth,
        long queries, double period, int rock, int ground) {
        if (!device.primitiveIds()) {
            return;
        }
        double extent = 600;
        double groundY = -12;
        rocksOnTerrain(renderer, rock, ground, 30_000, extent, groundY);
        Camera camera = new Camera().set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
        renderer.setView(camera, 1, MeshletRenderer.MODE_LIT);
        try (GpuImage shaded = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
            org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
             Lights lights = new Lights(device, 4096, 1)) {
            ExternalImage target = new ExternalImage("shaded", WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).set(shaded.handle(), 0);
            for (int count : new int[] {0, 256, 4096}) {
                scatterLights(lights, count, groundY, 20);
                SceneGeometry geometry = SceneGeometry.meshlets(renderer);
                ClusteredLights clustered = new ClusteredLights(lights, camera, WIDTH, HEIGHT);
                for (boolean forward : new boolean[] {true, false}) {
                    try (FrameGraph graph = new FrameGraph(device, 2)) {
                        graph.add(forward ? new ClusteredForwardShading(geometry, WIDTH, HEIGHT,
                            VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                            : new VisibilityBufferShading(geometry, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                                VK_FORMAT_D32_SFLOAT))
                            .output(Keys.SCENE_COLOR, target);
                        if (count > 0) {
                            graph.add(clustered);
                        }
                        System.out.printf(Locale.ROOT, "  30,000 rocks on the terrain, %s, %s: %.3f ms median%n",
                            count == 0 ? "the sun" : String.format(Locale.ROOT, "%,d point lights", count),
                            forward ? "clustered forward"
                                : "visibility buffer",
                            time(device, color, depth, queries, period, BATCH, graph::record));
                    }
                }
            }
        }
    }

    /** Clustered forward shading of {@code renderer}'s meshlets into RGBA8 and D32 images. */
    static ClusteredForwardShading forward(MeshletRenderer renderer, int width, int height) {
        return new ClusteredForwardShading(SceneGeometry.meshlets(renderer), width, height, VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_D32_SFLOAT);
    }

    /** The height of {@link #terrain}(1024), placed at the origin, at x, z. */
    static double terrainHeight(double x, double z) {
        double gx = x + 512;
        double gz = z + 512;
        return 3 * Math.sin(gx / 9.0) * Math.cos(gz / 13.0) + Math.sin(gx * gz / 4000.0);
    }

    /**
     * The terrain at {@code groundY} as instance 0, and {@code count} rocks resting on it (half sunk, scaled 0.5 to 3,
     * turned at random) in a square {@code extent} wide ahead of the camera, from 5 units in front of it.
     */
    static void rocksOnTerrain(MeshletRenderer renderer, int rock, int ground, int count, double extent,
        double groundY) {
        renderer.setInstance(0, ground, 0, (float) groundY, 0, 0, 0, 0, 1, 1, 1, 1);
        SplittableRandom random = new SplittableRandom(11);
        for (int i = 1; i <= count; i++) {
            double x = random.nextDouble(-extent / 2, extent / 2);
            double z = random.nextDouble(-extent - 5, -5);
            float scale = (float) random.nextDouble(0.5, 3);
            double qx = random.nextDouble(-1, 1);
            double qy = random.nextDouble(-1, 1);
            double qz = random.nextDouble(-1, 1);
            double qw = random.nextDouble(-1, 1);
            double length = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            renderer.setInstance(i, rock, (float) x, (float) (groundY + terrainHeight(x, z) + 0.4 * scale), (float) z,
                (float) (qx / length), (float) (qy / length), (float) (qz / length), (float) (qw / length), scale,
                scale, scale);
        }
        renderer.setInstanceCount(count + 1);
    }

    /**
     * The sun's orthographic view of a square {@code extent} wide around the middle of {@link #rocksOnTerrain}'s
     * rocks, for a {@code size} x {@code size} shadow map.
     */
    static Camera sun(double extent, double groundY, int size) {
        double cz = -extent / 2 - 5;
        double back = extent;
        return new Camera().setOrthographic(back * Keys.SUN_X, groundY + back * Keys.SUN_Y, cz + back * Keys.SUN_Z,
            -Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z, 1, 0, 0, extent * 0.75, extent * 0.75, 2 * back, size, size);
    }

    /** A shadow map bias of about two texels' depth, for {@link #sun}. */
    static float sunBias(double extent, int size) {
        return (float) (2 * (1.5 * extent / size) / (2 * extent));
    }

    /**
     * Sun shadows, through the frame graph: 30,000 rocks resting on the terrain, the camera looking down at them, drawn
     * with occlusion culling; the frame without shadows, with ray-traced shadows (where the device has ray tracing) and
     * with shadow maps of two sizes, each with the composite.
     */
    private static void shadows(VulkanDevice device, GeometryPool pool, MeshletRenderer renderer, GpuImage color,
        GpuImage depth, long queries, double period, int rock, MeshData rockMesh, int ground, MeshData terrainMesh) {
        double extent = 600;
        double groundY = -12;
        rocksOnTerrain(renderer, rock, ground, 30_000, extent, groundY);
        Camera camera = new Camera().set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
        renderer.setView(camera, 1, MeshletRenderer.MODE_LIT);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        try (GpuImage shaded = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
            org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
             Lights lights = new Lights(device, 1, 1);
             Lights cascadeLights = new Lights(device, 1, 3);
             RayTracing rayTracing = device.rayTracing() ? new RayTracing(device, pool, 100_000) : null) {
            ExternalImage target = new ExternalImage("shaded", WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).set(shaded.handle(), 0);
            lights.directional(0, -Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z, 3, 3, 3).setCount(1);
            ClusteredLights sun = new ClusteredLights(lights, camera, WIDTH, HEIGHT);
            cascadeLights.directional(0, -Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z, 3, 3, 3).setCount(1);
            ClusteredLights cascadeSun = new ClusteredLights(cascadeLights, camera, WIDTH, HEIGHT);
            if (rayTracing != null) {
                long t0 = System.nanoTime();
                rayTracing.add(rock, rockMesh, rockMesh.levels().size() - 1);
                rayTracing.add(ground, terrainMesh, terrainMesh.levels().size() - 1);
                System.out.printf(Locale.ROOT, "  bottom levels (rock and terrain at full detail, %s vertices): %,d "
                    + "bytes compacted, built in %.0f ms%n", rayTracing.quantizedVertices() ? "quantized" : "decoded",
                    rayTracing.bottomLevelBytes(), (System.nanoTime() - t0) / 1e6);
            }
            SceneDraw depthOnly = new SceneDraw(geometry, 2, camera, SurfaceOutput.depth(), VK_FORMAT_D32_SFLOAT, WIDTH,
                HEIGHT, false); // forward's depth for the mask: the camera as a view of its own
            SceneDraw ids = new SceneDraw(geometry, 0, null, SurfaceOutput.ids(), VK_FORMAT_D32_SFLOAT, WIDTH, HEIGHT,
                true);
            for (boolean forward : new boolean[] {true, false}) {
                Technique shading = forward ? forward(renderer, WIDTH, HEIGHT) : new VisibilityBufferShading(geometry,
                    WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
                String scene = "30,000 rocks on the terrain, from above, "
                    + (forward ? "forward" : "visibility buffer");
                time(device, color, depth, queries, period, scene + ", the sun unshadowed", target, shading, sun);
                for (int size : new int[] {2048, 4096}) {
                    time(device, color, depth, queries, period, scene + ", shadow map " + size + " x " + size, target,
                        shading, sun, new ShadowMapShadows(geometry, lights, 0, 0, sun(extent, groundY, size), size, 1,
                            sunBias(extent, size)));
                }
                CascadedShadowMaps cascades = new CascadedShadowMaps(geometry, cascadeLights, 0, 0, camera, 3, 2048, 1,
                    300);
                cascades.update(0, 0, 0);
                time(device, color, depth, queries, period, scene + ", 3 cascades of 2048 x 2048 to 300 units", target,
                    shading, cascadeSun, cascades);
                if (rayTracing != null) {
                    time(device, color, depth, queries, period, scene + ", ray-traced mask", target, shading, sun,
                        new RayTracedShadows(rayTracing, renderer, lights, 0, 0, forward ? depthOnly : ids, camera));
                    if (!forward) {
                        time(device, color, depth, queries, period, scene + ", inline rays", target, shading, sun,
                            new InlineRayShadows(rayTracing, renderer, lights, 0, 0));
                    }
                }
            }
        }
    }

    /** Times a graph of {@code techniques} outputting the scene's color into {@code target}, and prints it. */
    private static void time(VulkanDevice device, GpuImage color, GpuImage depth, long queries, double period,
        String what, ExternalImage target, Technique... techniques) {
        try (FrameGraph graph = new FrameGraph(device, 2)) {
            for (Technique technique : techniques) {
                graph.add(technique);
            }
            graph.output(Keys.SCENE_COLOR, target);
            System.out.printf(Locale.ROOT, "  %s: %.3f ms median%n", what,
                time(device, color, depth, queries, period, BATCH, graph::record));
        }
    }

    /**
     * Occlusion culling, through the frame graph: the 30,000 rocks drawn by {@link ClusteredForwardShading} without
     * and with two-phase occlusion culling, in the open and behind a wall (the terrain, upright, 80 units ahead).
     */
    private static void occlusion(VulkanDevice device, MeshletRenderer renderer, GpuImage color, GpuImage depth,
        long queries, double period, int rock, int ground, float[] placed, Camera camera) {
        int count = 30_000;
        for (int i = 0; i < count; i++) {
            int o = 8 * i;
            renderer.setInstance(i, rock, placed[o], placed[o + 1], placed[o + 2], placed[o + 3], placed[o + 4],
                placed[o + 5], placed[o + 6], placed[o + 7], placed[o + 7], placed[o + 7]);
        }
        renderer.setView(camera.set(0, 0, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1), 1, MeshletRenderer.MODE_LIT);
        ExternalImage target = new ExternalImage("target", WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
            .set(color.handle(), color.view());
        float half = (float) Math.sqrt(0.5);
        for (boolean wall : new boolean[] {false, true}) {
            renderer.setInstance(count, ground, 0, 0, -80, half, 0, 0, half, 1, 1, 1);
            renderer.setInstanceCount(wall ? count + 1 : count);
            for (boolean occlusion : new boolean[] {false, true}) {
                try (FrameGraph graph = new FrameGraph(device, 2)) {
                    graph.add(forward(renderer, WIDTH, HEIGHT)
                        .occlusion(occlusion)).output(Keys.SCENE_COLOR, target);
                    double time = time(device, color, depth, queries, period, BATCH, graph::record);
                    System.out.printf(Locale.ROOT, "  %,d rocks%s, through the graph, occlusion culling %s: %.3f ms "
                        + "median, %,d meshlets drawn%n", count, wall ? " behind a wall" : "", occlusion ? "on" : "off",
                        time, renderer.visibleMeshlets());
                }
            }
        }
    }

    /**
     * Classic LOD against meshlets, through the frame graph, occlusion culling off: the 30,000 scattered rocks as
     * meshlets (the cluster hierarchy, and the discrete LODs), and the discrete LODs drawn the classic way
     * ({@link ClassicLodGeometry}: a level per instance chosen on the GPU, indexed indirect draws).
     */
    private static void classicLod(VulkanDevice device, MeshletRenderer renderer, GpuImage color, GpuImage depth,
        long queries, double period, int rock, int rockLod, MeshData rockLods, float[] placed, Camera camera) {
        int count = 30_000;
        renderer.setView(camera.set(0, 0, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1), 1, MeshletRenderer.MODE_LIT);
        ExternalImage target = new ExternalImage("target", WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
            .set(color.handle(), color.view());
        try (ClassicLodGeometry classic = new ClassicLodGeometry(device, renderer, VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_D32_SFLOAT, count)) {
            classic.add(rockLod, rockLods);
            for (int mesh : new int[] {rock, rockLod, -1}) {
                for (int i = 0; i < count; i++) {
                    int o = 8 * i;
                    renderer.setInstance(i, mesh < 0 ? rockLod : mesh, placed[o], placed[o + 1], placed[o + 2],
                        placed[o + 3], placed[o + 4], placed[o + 5], placed[o + 6], placed[o + 7], placed[o + 7],
                        placed[o + 7]);
                }
                renderer.setInstanceCount(count);
                SceneGeometry geometry = mesh < 0 ? new SceneGeometry(classic) : SceneGeometry.meshlets(renderer);
                time(device, color, depth, queries, period, String.format(Locale.ROOT, "%,d rocks, through the graph, "
                    + "%s", count, mesh == rock ? "meshlets, cluster hierarchy" : mesh == rockLod
                    ? "meshlets, discrete LODs" : "classic LOD (discrete LODs, a level per instance)"), target,
                    new ClusteredForwardShading(geometry, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                        VK_FORMAT_D32_SFLOAT).occlusion(false));
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

    /** Frames recorded back to back per timed submission, so the GPU stays busy and its clocks steady. */
    private static final int BATCH = 32;

    /**
     * GPU time per frame on each path, median of {@link #FRAMES} batches. The paths take turns batch by batch, so they
     * see the same clocks.
     */
    private static void measure(String scene, VulkanDevice device, MeshletRenderer renderer, GpuImage color,
        GpuImage depth, long queries, double period) {
        MeshletRenderer.Path[] paths = device.meshShaders()
            ? new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED, MeshletRenderer.Path.MESH_SHADERS}
            : new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED};
        double[][] times = new double[paths.length][FRAMES];
        for (int frame = -5; frame < FRAMES; frame++) {
            for (int v = 0; v < paths.length; v++) {
                renderer.setPath(paths[v]);
                device.submitAndWait(device.graphics(), commands -> {
                    vkCmdResetQueryPool(commands, queries, 0, 2);
                    vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 0);
                    for (int i = 0; i < BATCH; i++) {
                        Images.transition(commands, color.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_NONE,
                            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
                        Images.transition(commands, depth.handle(), VK_IMAGE_ASPECT_DEPTH_BIT,
                            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL,
                            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_NONE,
                            VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                            VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                        renderer.record(commands, color.view(), depth.view(), WIDTH, HEIGHT, 0.1f, 0.12f, 0.15f);
                        fullBarrier(commands); // the next frame resets what this one reads
                    }
                    vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 1);
                });
                if (frame >= 0) {
                    try (MemoryStack stack = stackPush()) {
                        LongBuffer stamps = stack.mallocLong(2);
                        vkGetQueryPoolResults(device.handle(), queries, 0, 2, stamps, 8,
                            VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
                        times[v][frame] = (stamps.get(1) - stamps.get(0)) * period / 1e6 / BATCH;
                    }
                }
            }
        }
        for (int v = 0; v < paths.length; v++) {
            renderer.setPath(paths[v]);
            Arrays.sort(times[v]);
            System.out.printf(Locale.ROOT, "  %s, %s: %.3f ms median, %.3f ms best%n", scene, paths[v] == MeshletRenderer.Path.INDEXED ? "indexed" : "mesh shaders",
                times[v][FRAMES / 2], times[v][0]);
        }
        System.out.printf(Locale.ROOT, "  %s: %,d meshlets drawn%n", scene, renderer.visibleMeshlets());
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
