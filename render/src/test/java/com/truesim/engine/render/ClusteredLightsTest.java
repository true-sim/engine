package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIndexOutOfBoundsException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.nvkCmdCopyBuffer;

import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.engine.vulkan.Format;
import java.lang.foreign.ValueLayout;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
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
    private static final Key<Image> OUT = Key.image("test.out");

    private VulkanInstance instance;
    private VulkanDevice device;
    private Lights lights;
    private ClusteredLights made; // by the last run

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
        lights = new Lights(device, 64, 4);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            lights.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    @Test
    void lightsAreAddedRemovedAndGrow() {
        try (Lights few = new Lights(device, 2, 4)) {
            ShadowTable table = few.shadowTable(null);
            int sun = few.add();
            int lamp = few.add();
            int third = few.add(); // past the room: grown, the shadow table's lights with it
            assertThat(new int[] {sun, lamp, third}).containsExactly(0, 1, 2);
            assertThat(few.capacity()).isEqualTo(4);
            assertThat(few.count()).isEqualTo(3);
            few.directional(sun, 0, -1, 0, 1, 1, 1).point(third, 1, 2, 3, 5, 1, 1, 1);
            table.light(third, 0, 1); // its range in the grown table
            assertThat(few.position(third, 1)).isEqualTo(2);
            few.remove(lamp); // a hole: the count stays
            assertThat(few.count()).isEqualTo(3);
            few.remove(third);
            assertThat(few.count()).as("past the holes").isEqualTo(1);
            assertThat(few.add()).as("the lowest free id again").isEqualTo(1);
            assertThatIllegalArgumentException().isThrownBy(() -> few.remove(2));
        }
    }

    @Test
    void lightsGoWhereTheyReach() {
        Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
        lights.directional(0, -0.4f, -0.8f, -0.3f, 3, 3, 3)
            .point(1, 0, 0, -10, 2, 10, 10, 10)
            .point(2, 30, 0, -10, 1, 10, 10, 10)           // off to the right: in no cluster
            .spot(3, -3, 1, -30, 5, 10, 10, 10, 0, 0, -1, 0.3, 0.5)
            .point(4, 0.2f, 0.1f, -3, 0.5f, 1, 1, 1);
        lights.shadowTable(null).shadowMap(0, camera, 0, 1024, 1e-3f, 0).shadowMask(1, 0, 0)
            .shadowRay(2, 0, 2e-3f, 0, 0, 0).clear(3).light(3, 0, 1);
        assertThat(lights.shadowTable(null).capacity()).isEqualTo(4);
        // Many small lights in one spot: more than a cluster holds.
        for (int i = 5; i < 16; i++) {
            lights.point(i, 0.5f, 0.5f, -50, 0.2f, 1, 1, 1);
        }
        lights.setCount(16);
        int[][] gpu = run(port -> new ClusteredLights(lights, port, camera, 64, 8, 0.5f, 100, 8));
        ClusteredLights request = made;
        assertThat(request.clusters()).isEqualTo(4 * 2 * 8);
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
        // The sun and the 11: the spot light's cone (its sphere, not its range's) no longer reaches their cluster.
        assertThat(gpu[2][0]).as("the most lights a cluster wanted").isEqualTo(12);
        for (int c = 0; c < request.clusters(); c++) {
            assertThat(gpu[0][c]).isLessThanOrEqualTo(8);
        }
    }

    @Test
    void lightsFarFromTheOriginBinAsNearIt() {
        // The same lights around a camera near the origin and 7,000 km away: binned the same, as a light list hands
        // them to shading relative to its camera.
        int[][][] runs = new int[2][][];
        double[][] at = {{0, 0, 0}, {3_123_456.5, -45_678.25, -7_000_123.75}};
        for (int run = 0; run < 2; run++) {
            double[] w = at[run];
            Camera camera = new Camera().position(w[0], w[1], w[2])
                .set(0, 0, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
                lights.point(0, w[0], w[1], w[2] - 10, 2, 10, 10, 10)
                .point(1, w[0] + 6, w[1] + 1, w[2] - 25, 3, 10, 10, 10)
                .spot(2, w[0] - 3, w[1] + 1, w[2] - 30, 5, 10, 10, 10, 0, 0, -1, 0.3, 0.5)
                .setCount(3);
            assertThat(lights.position(1, 0)).isEqualTo(w[0] + 6);
            runs[run] = run(port -> new ClusteredLights(lights, port, camera, 64, 8, 0.5f, 100, 8));
        }
        assertThat(runs[1][0]).isEqualTo(runs[0][0]);
        assertThat(runs[1][1]).isEqualTo(runs[0][1]);
        assertThat(java.util.Arrays.stream(runs[0][0]).sum()).as("lights binned").isPositive();
    }

    @Test
    void clustersFollowTheGraphsSettings() {
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        lights.directional(0, -0.4f, -0.8f, -0.3f, 3, 3, 3).setCount(1);
        try (FrameGraph graph = new FrameGraph(device, 2);
             GpuImage target = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT)) {
            graph.settings().set(ClusteredLights.TILE_SIZE, 32).set(ClusteredLights.MAX_PER_CLUSTER, 16);
            ViewPort port = graph.addView("main").size(WIDTH, HEIGHT);
            ClusteredLights clustered = new ClusteredLights(lights, port,
                new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1));
            assertThat(clustered.tilesAcross()).as("its graph's settings, read now").isEqualTo((WIDTH + 31) / 32);
            ExternalImage output = new ExternalImage("target", 4, 4, Format.R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
            port.add(clustered).add(new Technique() {
                @Override
                public List<Key<?>> provides() {
                    return List.of(OUT);
                }

                @Override
                public void build(GraphBuilder g) {
                    com.truesim.engine.graph.Buffer list = g.require(Keys.LIGHT_LIST);
                    Image image = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
                    g.pass("use").read(list, BufferAccess.STORAGE_READ).write(image, Access.TRANSFER_DST)
                        .record(ctx -> { });
                    g.provide(OUT, image);
                }
            }).output(OUT, output);
            device.submitAndWait(device.graphics(), graph::record);
            assertThat(clustered.tilesAcross()).isEqualTo((WIDTH + 31) / 32);
            assertThat(clustered.maxPerCluster()).isEqualTo(16);
            int builds = graph.builds();
            graph.settings().set(ClusteredLights.TILE_SIZE, 128);
            device.submitAndWait(device.graphics(), graph::record);
            assertThat(graph.builds()).as("rebuilt for the new tiles").isEqualTo(builds + 1);
            assertThat(clustered.tilesAcross()).isEqualTo((WIDTH + 127) / 128);
            assertThat(RenderSettings.ALL).containsAll(ClusteredLights.ALL).contains(RenderSettings.OCCLUSION);
        }
    }

    @Test
    void misuseIsReported() {
        Camera any = new Camera();
        try (FrameGraph graph = new FrameGraph(device, 2)) {
            ViewPort port = graph.addView("main").size(1, 1);
            assertThatIllegalArgumentException().isThrownBy(() -> new ClusteredLights(lights, port, any, 64, 8, 1, 1,
                8));
            assertThatIllegalArgumentException().isThrownBy(() -> new ClusteredLights(lights, port, any, 64, 8, 0, 1,
                8));
            assertThatIllegalArgumentException().isThrownBy(() -> new ClusteredLights(lights, port, any, 0, 8, 0.1f,
                1, 8));
            assertThatIllegalArgumentException().isThrownBy(() -> new ClusteredLights(lights, port, any, 64, 0, 0.1f,
                1, 8));
            assertThatIllegalArgumentException().isThrownBy(() -> new ClusteredLights(lights, port, any, 64, 8, 0.1f,
                1, 0));
            // Its keys and size are its view's: built in the frame, it says so.
            graph.add(new ClusteredLights(lights, port, any)).add(new Technique() {
                @Override
                public List<Key<?>> provides() {
                    return List.of(OUT);
                }

                @Override
                public void build(GraphBuilder g) {
                    g.require(Keys.LIGHT_LIST);
                }
            }).output(OUT, new ExternalImage("target", 1, 1, Format.R8G8B8A8_UNORM, VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL));
            assertThatIllegalStateException().isThrownBy(() -> device.submitAndWait(device.graphics(), graph::record))
                .withMessageContaining("ClusteredLights of view main was added to the frame");
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new Lights(device, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Lights(device, 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> lights.setCount(65));
        assertThatIllegalArgumentException().isThrownBy(() -> lights.setCount(-1));
        ShadowTable table = lights.shadowTable(null);
        assertThatIllegalArgumentException().isThrownBy(() -> table.light(0, 3, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> table.light(0, -1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> table.light(0, 0, 0));
        assertThatIndexOutOfBoundsException().isThrownBy(() -> table.light(64, 0, 1));
        table.light(0, 0, 1).noShadows(0);
        assertThat(lights.count()).isZero();
    }

    private double[] position(int light) {
        return switch (light) {
            case 1 -> new double[] {0, 0, -10};
            case 2 -> new double[] {30, 0, -10};
            case 3 -> new double[] {-3, 1, -30 - spotRadius()}; // the sphere around its cone, along -z
            case 4 -> new double[] {0.2, 0.1, -3};
            default -> new double[] {0.5, 0.5, -50};
        };
    }

    private double radius(int light) {
        return switch (light) {
            case 1 -> 2;
            case 2 -> 1;
            case 3 -> spotRadius();
            case 4 -> 0.5;
            default -> 0.2;
        };
    }

    /** The spot light's (range 5, 0.5 radians out): the sphere around a cone that narrow, ahead of the light. */
    private static double spotRadius() {
        return 5 / (2 * Math.cos(0.5));
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

    /**
     * Clusters the lights through a graph, with the clustering {@code make} makes for a view of {@value #WIDTH} by
     * {@value #HEIGHT} (kept in {@link #made}), and reads the light list back: counts per cluster, slots, and
     * {overflow}.
     */
    private int[][] run(Function<ViewPort, ClusteredLights> make) {
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (FrameGraph graph = new FrameGraph(device, 2)) {
            ViewPort port = graph.addView("main").size(WIDTH, HEIGHT);
            ClusteredLights clustered = make.apply(port);
            made = clustered;
            return run(graph, port, clustered, usage);
        }
    }

    private int[][] run(FrameGraph graph, ViewPort port, ClusteredLights clustered, int usage) {
        int clusters = clustered.clusters();
        long bytes = ClusteredLights.HEADER_BYTES + 4L * clusters * (1 + clustered.maxPerCluster());
        try (GpuBuffer list = GpuBuffer.hostVisible(device, bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
             GpuImage target = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT)) {
            ExternalImage output = new ExternalImage("target", 4, 4, Format.R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
            port.add(clustered).add(new Technique() {
                @Override
                public List<Key<?>> provides() {
                    return List.of(OUT);
                }

                @Override
                public void build(GraphBuilder g) {
                    com.truesim.engine.graph.Buffer lights = g.require(Keys.LIGHT_LIST);
                    g.pass("read back").read(lights, BufferAccess.TRANSFER_SRC).sideEffects()
                        .record(ctx -> copy(ctx.unsafe().commands(), ctx.unsafe().buffer(lights), list.handle(), bytes));
                    Image image = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
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
