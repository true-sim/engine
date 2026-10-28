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
import java.nio.LongBuffer;
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
    private static MeshData rock(int rings) {
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
            .attribute(VertexAttribute.NORMAL, normals).submesh(indices).buildHierarchy();
    }

    /** A bumpy {@code n} x {@code n} grid, 1 unit per quad, centered, facing +y. */
    private static MeshData terrain(int n) {
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
                 1 << 20, 65_535), VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
             MemoryStack stack = stackPush()) {
            MeshData rockMesh = rock(128);
            MeshData terrainMesh = terrain(1024);
            int rock = pool.add(rockMesh);
            int ground = pool.add(terrainMesh);
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
                        renderer.setInstance(i, rock, (float) random.nextDouble(-300, 300),
                            (float) random.nextDouble(-150, 150), (float) random.nextDouble(-1500, -10),
                            (float) (qx / length), (float) (qy / length), (float) (qz / length),
                            (float) (qw / length), scale, scale, scale);
                    }
                    renderer.setInstanceCount(count);
                    renderer.setView(camera.set(0, 0, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1), 1,
                        MeshletRenderer.MODE_LIT);
                    measure(String.format(Locale.ROOT, "%,d rocks (%,d triangles in all)", count,
                        (long) count * triangles(rockMesh)), device, renderer, color, depth, queries, period);
                }
                renderer.setInstance(0, ground, 0, -6, 0, 0, 0, 0, 1, 1, 1, 1);
                renderer.setInstanceCount(1);
                renderer.setView(camera.set(0, -0.08, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1), 1,
                    MeshletRenderer.MODE_LIT);
                measure("terrain at a grazing angle", device, renderer, color, depth, queries, period);
            } finally {
                vkDestroyQueryPool(device.handle(), queries, null);
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
