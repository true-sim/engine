package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.nvkCmdCopyBuffer;

import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import java.lang.foreign.ValueLayout;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;

/**
 * Light clustering on the GPU against the same boxes computed on the CPU: every light reaching a cluster's box is in
 * it, none that stays clearly away is, directional lights are everywhere, and full clusters keep their first lights.
 */
class ClusteredLightsTest {

    private static final int WIDTH = 256;
    private static final int HEIGHT = 128;
    private static final Key OUT = Key.of("out");

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private MeshletRenderer renderer;
    private Lights lights;

    @BeforeEach
    void start() {
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1024, 16, 2));
        renderer = new MeshletRenderer(device, pool, new MeshletRenderer.Limits(16, 256, 64, 1 << 12),
            VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
        lights = new Lights(device, 64, 4);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            lights.close();
            renderer.close();
            pool.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    @Test
    void lightsGoWhereTheyReach() {
        Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
        renderer.setView(camera, 1, MeshletRenderer.MODE_LIT);
        lights.directional(0, -0.4f, -0.8f, -0.3f, 3, 3, 3)
            .point(1, 0, 0, -10, 2, 10, 10, 10)
            .point(2, 30, 0, -10, 1, 10, 10, 10)           // off to the right: in no cluster
            .spot(3, -3, 1, -30, 5, 10, 10, 10, 0, 0, -1, 0.3, 0.5)
            .shadows(3, 0, 1)
            .shadow(0, camera.viewProjection(), 0, 1024, 1e-3f, 0)
            .point(4, 0.2f, 0.1f, -3, 0.5f, 1, 1, 1);
        // Many small lights in one spot: more than a cluster holds.
        for (int i = 5; i < 16; i++) {
            lights.point(i, 0.5f, 0.5f, -50, 0.2f, 1, 1, 1);
        }
        lights.setCount(16);
        ClusteredLights request = new ClusteredLights(lights, camera, WIDTH, HEIGHT, 64, 8, 0.5f, 100, 8);
        assertThat(request.clusters()).isEqualTo(4 * 2 * 8);
        int[][] gpu = run(request);
        float[] m = camera.viewProjection();
        double[] across = {m[0], m[4], m[8]};
        double[] down = {m[1], m[5], m[9]};
        double[] forward = {m[3], m[7], m[11]};
        double sx = norm(across);
        double sy = norm(down);
        double scale = 8 / Math.log(100 / 0.5);
        int full = 0;
        for (int c = 0; c < request.clusters(); c++) {
            int tx = c % 4;
            int ty = (c / 4) % 2;
            int tz = c / 8;
            double near = tz == 0 ? 0 : 0.5 * Math.exp(tz / scale);
            double far = tz == 7 ? 100 : 0.5 * Math.exp((tz + 1) / scale);
            double x0 = tx * 64.0 / WIDTH * 2 - 1;
            double x1 = Math.min((tx + 1) * 64.0, WIDTH) / WIDTH * 2 - 1;
            double y0 = ty * 64.0 / HEIGHT * 2 - 1;
            double y1 = Math.min((ty + 1) * 64.0, HEIGHT) / HEIGHT * 2 - 1;
            double[] lo = {Math.min(Math.min(x0 * near, x0 * far), Math.min(x1 * near, x1 * far)) / sx,
                Math.min(Math.min(y0 * near, y0 * far), Math.min(y1 * near, y1 * far)) / sy, near};
            double[] hi = {Math.max(Math.max(x0 * near, x0 * far), Math.max(x1 * near, x1 * far)) / sx,
                Math.max(Math.max(y0 * near, y0 * far), Math.max(y1 * near, y1 * far)) / sy, far};
            Set<Integer> got = new HashSet<>();
            for (int i = 0; i < gpu[0][c]; i++) {
                got.add(gpu[1][c * 8 + i]);
            }
            assertThat(got).as("cluster %d", c).contains(0); // the sun
            if (gpu[0][c] == 8) {
                full++;
                continue; // full: which lights made it depends on the order they were found in
            }
            for (int light = 1; light < 16; light++) {
                double[] p = position(light);
                double[] v = {dot(across, p) / sx, dot(down, p) / sy, dot(forward, p)};
                double r = radius(light);
                if (reaches(v, r * 0.99, lo, hi)) {
                    assertThat(got).as("cluster %d, light %d", c, light).contains(light);
                } else if (!reaches(v, r * 1.01, lo, hi)) {
                    assertThat(got).as("cluster %d, light %d", c, light).doesNotContain(light);
                }
            }
        }
        assertThat(full).as("clusters holding the 11 lights in one spot").isPositive();
        // The sun and the 11, and the spot light: its depth slice is long that far away.
        assertThat(gpu[2][0]).as("the most lights a cluster wanted").isEqualTo(13);
        for (int c = 0; c < request.clusters(); c++) {
            assertThat(gpu[0][c]).isLessThanOrEqualTo(8);
        }
    }

    @Test
    void misuseIsReported() {
        Camera any = new Camera();
        assertThatIllegalArgumentException().isThrownBy(() -> new ClusteredLights(lights, any, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new ClusteredLights(lights, any, 1, 1, 64, 8, 1, 1,
            8));
        assertThatIllegalArgumentException().isThrownBy(() -> new ClusteredLights(lights, any, 1, 1, 64, 8, 0, 1,
            8));
        assertThatIllegalArgumentException().isThrownBy(() -> new ClusteredLights(lights, any, 1, 1, 0, 8, 0.1f, 1,
            8));
        assertThatIllegalArgumentException().isThrownBy(() -> new ClusteredLights(lights, any, 1, 1, 64, 0, 0.1f,
            1, 8));
        assertThatIllegalArgumentException().isThrownBy(() -> new ClusteredLights(lights, any, 1, 1, 64, 8, 0.1f,
            1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Lights(device, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Lights(device, 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> lights.setCount(65));
        assertThatIllegalArgumentException().isThrownBy(() -> lights.setCount(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> lights.shadows(0, 3, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> lights.shadows(0, -1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> lights.shadows(0, 0, 0));
        assertThat(lights.count()).isZero();
    }

    private double[] position(int light) {
        return switch (light) {
            case 1 -> new double[] {0, 0, -10};
            case 2 -> new double[] {30, 0, -10};
            case 3 -> new double[] {-3, 1, -30};
            case 4 -> new double[] {0.2, 0.1, -3};
            default -> new double[] {0.5, 0.5, -50};
        };
    }

    private double radius(int light) {
        return switch (light) {
            case 1 -> 2;
            case 2 -> 1;
            case 3 -> 5;
            case 4 -> 0.5;
            default -> 0.2;
        };
    }

    private static boolean reaches(double[] center, double radius, double[] lo, double[] hi) {
        double d2 = 0;
        for (int i = 0; i < 3; i++) {
            double d = center[i] - Math.max(lo[i], Math.min(hi[i], center[i]));
            d2 += d * d;
        }
        return d2 <= radius * radius;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double norm(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    /** Clusters the lights through a graph and reads the light list back: counts per cluster, slots, and {overflow}. */
    private int[][] run(ClusteredLights clustered) {
        int clusters = clustered.clusters();
        long bytes = ClusteredLights.HEADER_BYTES + 4L * clusters * (1 + clustered.maxPerCluster());
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (GpuBuffer list = GpuBuffer.hostVisible(device, bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
             GpuImage target = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
             FrameGraph graph = new FrameGraph(device, 2)) {
            ExternalImage output = new ExternalImage("target", 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
            graph.add(clustered).add(new Technique() {
                @Override
                public List<Key> provides() {
                    return List.of(OUT);
                }

                @Override
                public void build(GraphBuilder g) {
                    com.truesim.engine.graph.Buffer lights = g.requireBuffer(Keys.LIGHT_LIST);
                    g.pass("read back").read(lights, BufferAccess.TRANSFER_SRC).sideEffects()
                        .record(ctx -> copy(ctx.commands(), ctx.buffer(lights), list.handle(), bytes));
                    Image image = g.create("out", 4, 4, VK_FORMAT_R8G8B8A8_UNORM);
                    g.pass("fill").write(image, Access.TRANSFER_DST).record(ctx -> { });
                    g.provide(OUT, image);
                }
            }).output(OUT, output);
            device.submitAndWait(device.graphics(), graph::record);
            assertThat(graph.passes()).containsOnlyOnce("light clusters");
            int[][] out = new int[3][];
            out[0] = new int[clusters];
            for (int c = 0; c < clusters; c++) {
                out[0][c] = list.memory().get(ValueLayout.JAVA_INT, ClusteredLights.HEADER_BYTES + 4L * c);
            }
            out[1] = new int[clusters * clustered.maxPerCluster()];
            long slots = ClusteredLights.HEADER_BYTES + 4L * clusters;
            for (int i = 0; i < out[1].length; i++) {
                out[1][i] = list.memory().get(ValueLayout.JAVA_INT, slots + 4L * i);
            }
            out[2] = new int[] {list.memory().get(ValueLayout.JAVA_INT, 8)};
            return out;
        }
    }

    private static void copy(org.lwjgl.vulkan.VkCommandBuffer commands, long from, long to, long bytes) {
        try (MemoryStack stack = stackPush()) {
            long region = stack.ncalloc(8, 1, VkBufferCopy.SIZEOF);
            memPutLong(region + VkBufferCopy.SIZE, bytes);
            nvkCmdCopyBuffer(commands, from, to, 1, region);
        }
    }
}
