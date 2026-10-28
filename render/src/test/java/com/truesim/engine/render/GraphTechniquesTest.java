package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.nvkCmdCopyBufferToImage;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.render.blocks.Blur;
import com.truesim.engine.render.blocks.DepthPyramid;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.VertexFormat;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkImageSubresourceLayers;

/**
 * The renderer's techniques in a frame graph, validated: the meshlet scene drawn straight into the target, and the
 * blur building block, computed once for every technique asking for it.
 */
class GraphTechniquesTest {

    private static final int SIZE = 64;
    private static final float HALF = (float) Math.sqrt(0.5);
    private static final Key EDGE = Key.of("edge");

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private MeshletRenderer renderer;
    private FrameGraph graph;
    private GpuImage target;
    private ExternalImage output;
    private GpuBuffer pixels;
    private GpuBuffer pattern;
    private int quad;

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
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 12, 64, 4));
        quad = pool.add(MeshletRendererTest.grid(1, 2, false, VertexFormat.POSITIONS_ONLY));
        renderer = new MeshletRenderer(device, pool, new MeshletRenderer.Limits(16, 256, 64, 1 << 12),
            VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
        graph = new FrameGraph(device, 2);
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        output = new ExternalImage("target", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
        pixels = GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        // White on the left half, black on the right.
        pattern = GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                pattern.memory().setAtIndex(ValueLayout.JAVA_INT, y * SIZE + x, x < SIZE / 2 ? 0xffffffff : 0xff000000);
            }
        }
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            graph.close();
            pattern.close();
            pixels.close();
            target.close();
            renderer.close();
            pool.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** Provides {@link #EDGE}: the pattern, white left and black right. */
    private final class Edge implements Technique {
        @Override
        public List<Key> provides() {
            return List.of(EDGE);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.create("edge", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
            g.pass("upload edge").write(image, Access.TRANSFER_DST).record(ctx -> {
                try (MemoryStack stack = stackPush()) {
                    long region = stack.ncalloc(4, 1, VkBufferImageCopy.SIZEOF);
                    memPutInt(region + VkBufferImageCopy.IMAGESUBRESOURCE + VkImageSubresourceLayers.ASPECTMASK,
                        VK_IMAGE_ASPECT_COLOR_BIT);
                    memPutInt(region + VkBufferImageCopy.IMAGESUBRESOURCE + VkImageSubresourceLayers.LAYERCOUNT, 1);
                    memPutInt(region + VkBufferImageCopy.IMAGEEXTENT, SIZE);
                    memPutInt(region + VkBufferImageCopy.IMAGEEXTENT + 4, SIZE);
                    memPutInt(region + VkBufferImageCopy.IMAGEEXTENT + 8, 1);
                    nvkCmdCopyBufferToImage(ctx.commands(), pattern.handle(), ctx.image(image),
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, region);
                }
            });
            g.provide(EDGE, image);
        }
    }

    /** Provides {@code key}: the blur of {@link #EDGE}, through the shared request. */
    private record Blurred(Key key, int radius) implements Technique {
        @Override
        public List<Key> provides() {
            return List.of(key);
        }

        @Override
        public void build(GraphBuilder g) {
            g.provide(key, g.request(new Blur(g.require(EDGE), radius, 1)));
        }
    }

    /** Provides {@code out}: the left half of {@code left} beside the right half of {@code right}. */
    private record Halves(Key out, Key left, Key right) implements Technique {
        @Override
        public List<Key> provides() {
            return List.of(out);
        }

        @Override
        public void build(GraphBuilder g) {
            Image l = g.require(left);
            Image r = g.require(right);
            Image image = g.createLike("halves", l, 1);
            g.pass("halves").read(l, Access.TRANSFER_SRC).read(r, Access.TRANSFER_SRC)
                .write(image, Access.TRANSFER_DST).record(ctx -> {
                    copy(ctx.commands(), ctx.image(l), ctx.image(image), 0);
                    copy(ctx.commands(), ctx.image(r), ctx.image(image), SIZE / 2);
                });
            g.provide(out, image);
        }

        private static void copy(org.lwjgl.vulkan.VkCommandBuffer commands, long from, long to, int x) {
            try (MemoryStack stack = stackPush()) {
                org.lwjgl.vulkan.VkImageCopy.Buffer region = org.lwjgl.vulkan.VkImageCopy.calloc(1, stack);
                region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.srcOffset().set(x, 0, 0);
                region.dstOffset().set(x, 0, 0);
                region.extent().set(SIZE / 2, SIZE, 1);
                org.lwjgl.vulkan.VK10.vkCmdCopyImage(commands, from, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, to,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            }
        }
    }

    /** Records a frame and reads the target back. */
    private void frame() {
        device.submitAndWait(device.graphics(), commands -> {
            graph.record(commands);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(SIZE, SIZE, 1);
                vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
    }

    /** The red channel at (x, y), 0 to 1. */
    private double red(int x, int y) {
        return (pixels.memory().getAtIndex(ValueLayout.JAVA_INT, y * SIZE + x) & 0xff) / 255.0;
    }

    /** Channel {@code shift} (0 red, 16 blue) on average in the lower half, columns {@code from} to {@code to}. */
    private double lower(int shift, int from, int to) {
        double sum = 0;
        for (int y = SIZE / 2; y < SIZE; y++) {
            for (int x = from; x < to; x++) {
                sum += (pixels.memory().getAtIndex(ValueLayout.JAVA_INT, y * SIZE + x) >> shift) & 0xff;
            }
        }
        return sum / ((SIZE / 2.0) * (to - from));
    }

    @Test
    void clusteredForwardShadingLightsEachPixelByTheLightsNearIt() {
        // A floor below the camera, a red light over its left side and a blue one over its right.
        renderer.setInstance(0, quad, 0, -1, -4, 0, 0, 0, 1, 4, 4, 4);
        renderer.setInstanceCount(1);
        renderer.setView(new Camera().set(0, -1, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1), 1,
            MeshletRenderer.MODE_LIT);
        try (Lights lights = new Lights(device, 4, 1)) {
            lights.point(0, -1, -0.5f, -1.5f, 3, 6, 0, 0).point(1, 1, -0.5f, -1.5f, 3, 0, 0, 6).setCount(2);
            ClusteredForwardShading scene = MeshletBenchmark.forward(renderer, SIZE, SIZE)
                .lights(lights);
            graph.add(scene).output(Keys.SCENE_COLOR, output);
            frame();
            assertThat(graph.passes()).containsOnlyOnce("light clusters");
            double leftRed = lower(0, 0, SIZE / 4);
            double leftBlue = lower(16, 0, SIZE / 4);
            double rightRed = lower(0, 3 * SIZE / 4, SIZE);
            double rightBlue = lower(16, 3 * SIZE / 4, SIZE);
            assertThat(leftRed).isGreaterThan(2 * leftBlue);
            assertThat(rightBlue).isGreaterThan(2 * rightRed);
            // No lights: the sky alone, gray and dim; the sun is not one of the lights.
            lights.setCount(0);
            frame();
            assertThat(lower(0, 0, SIZE / 4))
                .isCloseTo(lower(16, 0, SIZE / 4), org.assertj.core.data.Offset.offset(2.0))
                .isLessThan(leftRed);
            // Without the lights the scene is lit by the sun again.
            scene.lights(null);
            graph.invalidate();
            frame();
            assertThat(graph.passes()).doesNotContain("light clusters");
        }
    }

    @Test
    void bothShadingsDrawTheSameImage() {
        assumeThat(device.primitiveIds()).as("primitive IDs in fragment shaders").isTrue();
        // The lit floor, and a quad standing on it hiding part of it.
        renderer.setInstance(0, quad, 0, -1, -4, 0, 0, 0, 1, 4, 4, 4);
        renderer.setInstance(1, quad, 0, -0.5f, -2.5f, HALF, 0, 0, HALF, 0.4f, 0.4f, 0.4f);
        renderer.setInstanceCount(2);
        renderer.setView(new Camera().set(0, -1, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1), 1,
            MeshletRenderer.MODE_LIT);
        try (Lights lights = new Lights(device, 4, 1)) {
            lights.point(0, -1, -0.5f, -1.5f, 3, 6, 0, 0).point(1, 1, -0.5f, -1.5f, 3, 0, 0, 6)
                .directional(2, -0.4f, -0.8f, -0.3f, 1, 1, 1).setCount(3);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            ClusteredForwardShading forwardShading = new ClusteredForwardShading(geometry, SIZE, SIZE,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT).lights(lights).clearColor(0, 0, 0.5f);
            VisibilityBufferShading visibilityShading = new VisibilityBufferShading(geometry, SIZE, SIZE,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT).lights(lights).clearColor(0, 0, 0.5f);
            graph.add(forwardShading).output(Keys.SCENE_COLOR, output);
            for (MeshletRenderer.Path path : new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED,
                MeshletRenderer.Path.AUTO}) {
                renderer.setPath(path);
                graph.replace(forwardShading);
                frame();
                frame(); // the late phase has drawn what the early one missed the first time
                int[] forward = new int[SIZE * SIZE];
                for (int i = 0; i < forward.length; i++) {
                    forward[i] = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i);
                }
                graph.replace(visibilityShading);
                frame();
                frame();
                assertThat(graph.passes()).contains("meshlet ids early", "meshlet ids late", "visibility shading");
                double total = 0;
                int far = 0;
                for (int i = 0; i < forward.length; i++) {
                    int p = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i);
                    int worst = 0;
                    for (int shift = 0; shift < 24; shift += 8) {
                        int d = Math.abs(((p >> shift) & 0xff) - ((forward[i] >> shift) & 0xff));
                        total += d;
                        worst = Math.max(worst, d);
                    }
                    far += worst > 8 ? 1 : 0;
                }
                assertThat(total / (3.0 * forward.length)).as("mean difference, %s", path).isLessThan(0.5);
                assertThat(far).as("pixels differing by more than 8, %s", path).isLessThan(SIZE * SIZE / 100);
            }
        }
    }

    @Test
    void sceneDrawsAreSharedAndChecked() {
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        assertThat(geometry.provider(MeshletGeometry.class).renderer()).isSameAs(renderer);
        assertThat(geometry.provider(GeometryProvider.class)).isNotNull();
        assertThat(new SceneGeometry(new GeometryProvider() {
            @Override
            public void setView(int index, Camera camera, float errorPixels, int mode) {
            }

            @Override
            public void drawFirst(GraphBuilder g, SceneDraw draw, SceneDraw.Targets targets, boolean load) {
            }
        }).provider(MeshletGeometry.class)).isNull();
        renderer.setInstance(0, quad, 0, 0, -5, HALF, 0, 0, HALF, 1, 1, 1);
        renderer.setInstanceCount(1);
        Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        geometry.setView(0, camera, 1, MeshletRenderer.MODE_WHITE);
        // Two techniques asking for the same depth-only draw of the camera, with occlusion culling: drawn once.
        Key first = Key.of("first");
        Key second = Key.of("second");
        SceneDraw draw = new SceneDraw(geometry, 0, null, SurfaceOutput.depth(), VK_FORMAT_D32_SFLOAT, SIZE, SIZE,
            true);
        graph.add(new DepthCopy(first, draw)).add(new DepthCopy(second, draw))
            .add(new Halves(EDGE, first, second)).output(EDGE, output);
        frame();
        frame();
        assertThat(graph.passes()).containsOnlyOnce("meshlet depth, view 0 early", "meshlet depth, view 0 late");
        // The quad's depth, 5 units ahead: near / distance = 0.02 (reverse depth).
        assertThat(red(SIZE / 2, SIZE / 2)).isCloseTo(0.02, org.assertj.core.data.Offset.offset(0.005));
        assertThat(red(2, 2)).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> new SceneGeometry());
        assertThatIllegalArgumentException().isThrownBy(() -> new SceneDraw(geometry, 1, null, SurfaceOutput.ids(),
            VK_FORMAT_D32_SFLOAT, 1, 1, true));
        assertThatIllegalArgumentException().isThrownBy(() -> new SceneDraw(geometry, 0, null, SurfaceOutput.ids(),
            VK_FORMAT_D32_SFLOAT, 0, 1, false));
        assertThatIllegalArgumentException().isThrownBy(() -> new VisibilityBufferShading(new SceneGeometry(
            new MeshletGeometry(renderer), new MeshletGeometry(renderer)), SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_D32_SFLOAT));
        // Meshlets come first: another provider before them is not supported yet.
        graph.close();
        graph = new FrameGraph(device, 2).add(new ClusteredForwardShading(new SceneGeometry(
            new MeshletGeometry(renderer), new MeshletGeometry(renderer)), SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_D32_SFLOAT)).output(Keys.SCENE_COLOR, output);
        assertThatIllegalStateException().isThrownBy(this::frame).withMessageContaining("Meshlets are drawn first");
    }

    /** Provides {@code key}: the depth of {@code draw}, copied into the red channel of a color image. */
    private record DepthCopy(Key key, SceneDraw draw) implements Technique {
        @Override
        public List<Key> provides() {
            return List.of(key);
        }

        @Override
        public void build(GraphBuilder g) {
            Image depth = g.request(draw).depth();
            Image image = g.create(key.name(), SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
            Pipeline pipeline = g.cached(DepthCopy.class, device -> {
                try (ShaderModule shader = ShaderModule.load(device, "shaders/test/depthcopy.spv")) {
                    return Pipeline.compute(device, shader, "copyMain", 8);
                }
            });
            g.pass("copy " + key).read(depth, Access.SAMPLED_COMPUTE).write(image, Access.STORAGE_WRITE)
                .record(ctx -> {
                    try (MemoryStack stack = stackPush()) {
                        long push = stack.nmalloc(4, 8);
                        memPutInt(push, ctx.sampled(depth));
                        memPutInt(push + 4, ctx.storage(image));
                        pipeline.bind(ctx.commands());
                        pipeline.push(ctx.commands(), push, 8);
                        org.lwjgl.vulkan.VK10.vkCmdDispatch(ctx.commands(), SIZE / 8, SIZE / 8, 1);
                    }
                });
            g.provide(key, image);
        }
    }

    @Test
    void depthPyramidsKeepTheFarthestDepthOfEveryFootprint() {
        int w = 13;
        int h = 7; // odd sizes: footprints of 3 along the edges
        float[] depth = new float[w * h];
        java.util.SplittableRandom random = new java.util.SplittableRandom(3);
        for (int i = 0; i < depth.length; i++) {
            depth[i] = (float) random.nextDouble();
        }
        // The expected levels, computed the same way on the CPU.
        List<float[]> levels = new java.util.ArrayList<>();
        List<int[]> sizes = new java.util.ArrayList<>();
        float[] below = depth;
        int bw = w;
        int bh = h;
        int lw = (w + 1) / 2;
        int lh = (h + 1) / 2;
        int mips = com.truesim.engine.vulkan.GpuImage.fullMipCount(lw, lh);
        for (int mip = 0; mip < mips; mip++) {
            float[] level = new float[lw * lh];
            for (int y = 0; y < lh; y++) {
                for (int x = 0; x < lw; x++) {
                    int ex = 2 + ((bw & 1) != 0 && x == lw - 1 ? 1 : 0);
                    int ey = 2 + ((bh & 1) != 0 && y == lh - 1 ? 1 : 0);
                    float farthest = 1;
                    for (int dy = 0; dy < ey; dy++) {
                        for (int dx = 0; dx < ex; dx++) {
                            int sx = Math.min(2 * x + dx, bw - 1);
                            int sy = Math.min(2 * y + dy, bh - 1);
                            farthest = Math.min(farthest, below[sy * bw + sx]);
                        }
                    }
                    level[y * lw + x] = farthest;
                }
            }
            levels.add(level);
            sizes.add(new int[] {lw, lh});
            below = level;
            bw = lw;
            bh = lh;
            lw = Math.max(1, lw / 2);
            lh = Math.max(1, lh / 2);
        }
        try (GpuBuffer upload = GpuBuffer.hostVisible(device, depth.length * 4L, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
             GpuBuffer readback = GpuBuffer.hostVisible(device, depth.length * 4L, VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            for (int i = 0; i < depth.length; i++) {
                upload.memory().setAtIndex(ValueLayout.JAVA_FLOAT, i, depth[i]);
            }
            Key depthKey = Key.of("depth");
            graph.add(new Technique() {
                @Override
                public List<Key> provides() {
                    return List.of(depthKey);
                }

                @Override
                public void build(GraphBuilder g) {
                    Image image = g.create("depth", w, h, VK_FORMAT_D32_SFLOAT);
                    g.pass("upload depth").write(image, Access.TRANSFER_DST).record(ctx -> {
                        try (MemoryStack stack = stackPush()) {
                            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1);
                            region.imageExtent().set(w, h, 1);
                            org.lwjgl.vulkan.VK10.vkCmdCopyBufferToImage(ctx.commands(), upload.handle(),
                                ctx.image(image), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                        }
                    });
                    g.provide(depthKey, image);
                }
            }).add(new Technique() {
                @Override
                public List<Key> provides() {
                    return List.of(EDGE);
                }

                @Override
                public void build(GraphBuilder g) {
                    Image pyramid = g.request(new DepthPyramid(g.require(depthKey)));
                    assertThat(pyramid.mips()).isEqualTo(mips);
                    g.pass("read back").read(pyramid, Access.TRANSFER_SRC).sideEffects().record(ctx -> {
                        try (MemoryStack stack = stackPush()) {
                            VkBufferImageCopy.Buffer regions = VkBufferImageCopy.calloc(mips, stack);
                            long offset = 0;
                            for (int mip = 0; mip < mips; mip++) {
                                regions.get(mip).bufferOffset(offset);
                                regions.get(mip).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                    .mipLevel(mip).layerCount(1);
                                regions.get(mip).imageExtent().set(sizes.get(mip)[0], sizes.get(mip)[1], 1);
                                offset += 4L * sizes.get(mip)[0] * sizes.get(mip)[1];
                            }
                            vkCmdCopyImageToBuffer(ctx.commands(), ctx.image(pyramid),
                                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback.handle(), regions);
                        }
                    });
                    Image marker = g.create("marker", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
                    g.pass("marker").write(marker, Access.TRANSFER_DST).record(ctx -> { });
                    g.provide(EDGE, marker);
                }
            }).output(EDGE, output);
            frame();
            assertThat(graph.passes()).contains("depth pyramid of depth", "read back");
            long offset = 0;
            for (int mip = 0; mip < mips; mip++) {
                float[] expected = levels.get(mip);
                for (int i = 0; i < expected.length; i++) {
                    assertThat(readback.memory().get(ValueLayout.JAVA_FLOAT, offset + 4L * i))
                        .as("level %d, texel %d", mip, i).isEqualTo(expected[i]);
                }
                offset += 4L * expected.length;
            }
            device.waitIdle();
            graph.close();
        }
    }

    @Test
    void theForwardShadedSceneDrawsStraightIntoTheTarget() {
        renderer.setInstance(0, quad, 0, 0, -5, HALF, 0, 0, HALF, 1, 1, 1);
        renderer.setInstanceCount(1);
        renderer.setView(new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1), 1,
            MeshletRenderer.MODE_WHITE);
        graph.add(MeshletBenchmark.forward(renderer, SIZE, SIZE)
                .clearColor(0, 0, 1))
            .output(Keys.SCENE_COLOR, output);
        frame();
        assertThat(graph.passes()).containsExactly("meshlet culling", "meshlets early", "depth pyramid of scene depth",
            "meshlets late");
        assertThat(graph.pooledImages()).isEqualTo(2); // depth and its pyramid: the color is the target
        assertThat(red(SIZE / 2, SIZE / 2)).isEqualTo(1);
        assertThat(red(1, 1)).isZero(); // blue clear color
        assertThat(renderer.visibleMeshlets()).isEqualTo(1);
        // Without occlusion culling: one pass.
        graph.close();
        graph = new FrameGraph(device, 2).add(MeshletBenchmark.forward(renderer, SIZE, SIZE).occlusion(false))
            .output(Keys.SCENE_COLOR, output);
        frame();
        assertThat(graph.passes()).containsExactly("meshlet culling", "meshlets");
        assertThat(red(SIZE / 2, SIZE / 2)).isEqualTo(1);
    }

    /** How many pixels are drawn: white, not the blue clear color. */
    private int covered() {
        int count = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                count += red(x, y) > 0.5 ? 1 : 0;
            }
        }
        return count;
    }

    @Test
    void hiddenObjectsAreCulledAndAppearTheFrameTheyShow() {
        for (MeshletRenderer.Path path : device.meshShaders()
            ? new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED, MeshletRenderer.Path.MESH_SHADERS}
            : new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED}) {
            renderer.setPath(path);
            // A wall filling the view 5 units ahead, and 8 quads behind it, 10 units ahead.
            renderer.setInstance(0, quad, 0, 0, -5, HALF, 0, 0, HALF, 6, 6, 6);
            for (int i = 1; i <= 8; i++) {
                renderer.setInstance(i, quad, (i % 4 - 1.5f) * 2.5f, i <= 4 ? -1.5f : 1.5f, -10, HALF, 0, 0, HALF,
                    0.5f, 0.5f, 0.5f);
            }
            renderer.setInstanceCount(9);
            renderer.setView(new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1), 1,
                MeshletRenderer.MODE_WHITE);
            graph.close();
            graph = new FrameGraph(device, 2).add(MeshletBenchmark.forward(renderer, SIZE, SIZE).clearColor(0, 0, 1))
                .output(Keys.SCENE_COLOR, output);
            frame(); // nothing known yet: the late phase draws all
            assertThat(renderer.visibleMeshlets()).as("first frame, %s", path).isEqualTo(9);
            frame(); // all drawn early; the late phase finds the 8 behind the wall hidden
            assertThat(renderer.visibleMeshlets()).isEqualTo(9);
            frame(); // only the wall now
            assertThat(renderer.visibleMeshlets()).as("hidden, %s", path).isEqualTo(1);
            assertThat(covered()).isEqualTo(SIZE * SIZE);
            // The wall moves out of view: the quads behind it are drawn the same frame.
            renderer.setInstance(0, quad, 100, 0, -5, HALF, 0, 0, HALF, 6, 6, 6);
            frame();
            assertThat(renderer.visibleMeshlets()).as("shown, %s", path).isEqualTo(8);
            assertThat(covered()).isPositive().isLessThan(SIZE * SIZE / 2);
            frame();
            assertThat(renderer.visibleMeshlets()).isEqualTo(8); // drawn early now
        }
    }

    @Test
    void blursSmoothTheEdgeAndAreComputedOnceForEveryone() {
        Key soft = Key.of("soft");
        Key alsoSoft = Key.of("also soft");
        Key both = Key.of("both");
        graph.add(new Edge())
            .add(new Blurred(soft, 4))
            .add(new Blurred(alsoSoft, 4))
            .add(new Halves(both, soft, alsoSoft))
            .output(both, output);
        frame();
        assertThat(graph.passes()).containsExactly("upload edge", "blur 4 of edge across", "blur 4 of edge down",
            "halves");
        // Far from the edge nothing changes; across it the value falls smoothly from white to black.
        assertThat(red(2, SIZE / 2)).isEqualTo(1);
        assertThat(red(SIZE - 3, SIZE / 2)).isZero();
        assertThat(red(SIZE / 2 - 1, SIZE / 2)).isCloseTo(0.6, within(0.15));
        assertThat(red(SIZE / 2, SIZE / 2)).isCloseTo(0.4, within(0.15));
        double previous = 2;
        for (int x = SIZE / 2 - 6; x <= SIZE / 2 + 6; x++) {
            assertThat(red(x, SIZE / 2)).isLessThanOrEqualTo(previous);
            previous = red(x, SIZE / 2);
        }
        // A blur at half size: computed apart, a different request.
        graph.close();
        graph = new FrameGraph(device, 2).add(new Edge()).add(new Technique() {
            @Override
            public List<Key> provides() {
                return List.of(soft);
            }

            @Override
            public void build(GraphBuilder g) {
                g.provide(soft, g.request(new Blur(g.require(EDGE), 2, 0.5)));
            }
        }).output(soft, output);
        frame();
        assertThat(graph.passes()).containsExactly("upload edge", "blur 2 of edge across", "blur 2 of edge down",
            "output target");
        assertThat(red(2, 2)).isEqualTo(1);
        assertThat(red(SIZE - 3, 2)).isZero();
        org.assertj.core.api.Assertions.assertThatNullPointerException().isThrownBy(() -> new Blur(null, 1, 1));
    }
}
