package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
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

import com.truesim.engine.graph.Ratio;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.ResolutionSettings;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.render.blocks.Blur;
import com.truesim.engine.render.blocks.DepthPyramid;
import com.truesim.engine.render.materials.CarPaint;
import com.truesim.engine.render.materials.CarPaintParameters;
import com.truesim.engine.render.materials.ClearcoatParameters;
import com.truesim.engine.render.materials.VolumeParameters;
import com.truesim.engine.render.materials.TransmissionParameters;
import com.truesim.engine.render.materials.SpecularParameters;
import com.truesim.engine.render.materials.SheenParameters;
import com.truesim.engine.render.materials.IorParameters;
import com.truesim.common.math.Vec4;
import com.truesim.engine.render.materials.TextureTransformParameters;
import com.truesim.engine.render.materials.UnlitParameters;
import com.truesim.engine.render.materials.Glow;
import com.truesim.engine.render.materials.GlowParameters;
import com.truesim.engine.render.materials.Tinted;
import com.truesim.engine.render.materials.TintedParameters;
import com.truesim.engine.render.materials.MetallicRoughnessMaterial;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;
import com.truesim.engine.vulkan.Format;
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
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS;
    private static final float HALF = (float) Math.sqrt(0.5);
    private static final Key<Image> EDGE = Key.image("test.edge");

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private RenderView view;
    private FrameGraph graph;
    private GpuImage target;
    private ExternalImage output;
    private GpuBuffer pixels;
    private GpuBuffer pattern;
    private int quad;
    private MeshData quadMesh;
    private int hill;
    private MeshData hillMesh;

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
        quadMesh = MeshletRendererTest.grid(1, 2, false, VertexFormat.POSITIONS_ONLY);
        quad = pool.add(quadMesh);
        hillMesh = MeshletRendererTest.grid(16, 0.1f, true, VertexFormat.POSITIONS_ONLY); // a cluster hierarchy
        hill = pool.add(hillMesh);
        assertThat(hillMesh.levels()).as("the hill's levels").hasSizeGreaterThan(1);
        scene = new GpuScene(device, pool, 16);
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
        view = renderer.createView(ViewOptions.of(256, 1 << 12).withOcclusion(true));
        graph = new FrameGraph(device, 2);
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        output = new ExternalImage("target", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
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
            scene.close();
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
        public List<Key<?>> provides() {
            return List.of(EDGE);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.create("edge", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("upload edge").write(image, Access.TRANSFER_DST).record(ctx -> {
                try (MemoryStack stack = stackPush()) {
                    long region = stack.ncalloc(4, 1, VkBufferImageCopy.SIZEOF);
                    memPutInt(region + VkBufferImageCopy.IMAGESUBRESOURCE + VkImageSubresourceLayers.ASPECTMASK,
                        VK_IMAGE_ASPECT_COLOR_BIT);
                    memPutInt(region + VkBufferImageCopy.IMAGESUBRESOURCE + VkImageSubresourceLayers.LAYERCOUNT, 1);
                    memPutInt(region + VkBufferImageCopy.IMAGEEXTENT, SIZE);
                    memPutInt(region + VkBufferImageCopy.IMAGEEXTENT + 4, SIZE);
                    memPutInt(region + VkBufferImageCopy.IMAGEEXTENT + 8, 1);
                    nvkCmdCopyBufferToImage(ctx.unsafe().commands(), pattern.handle(), ctx.unsafe().image(image),
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, region);
                }
            });
            g.provide(EDGE, image);
        }
    }

    /** Provides {@code key}: the blur of {@link #EDGE}, through the shared request. */
    private record Blurred(Key<Image> key, int radius) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(key);
        }

        @Override
        public void build(GraphBuilder g) {
            g.provide(key, g.request(new Blur(g.require(EDGE), radius, Ratio.ONE)));
        }
    }

    /** Provides {@code out}: the left half of {@code left} beside the right half of {@code right}. */
    private record Halves(Key<Image> out, Key<Image> left, Key<Image> right) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(out);
        }

        @Override
        public void build(GraphBuilder g) {
            Image l = g.require(left);
            Image r = g.require(right);
            Image image = g.create("halves", ImageDesc.like(l, Ratio.ONE));
            g.pass("halves").read(l, Access.TRANSFER_SRC).read(r, Access.TRANSFER_SRC)
                .write(image, Access.TRANSFER_DST).record(ctx -> {
                    copy(ctx.unsafe().commands(), ctx.unsafe().image(l), ctx.unsafe().image(image), 0);
                    copy(ctx.unsafe().commands(), ctx.unsafe().image(r), ctx.unsafe().image(image), SIZE / 2);
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

    /** {@link #view} seen through {@code camera}, in a new view "main" of the graph, {@link #SIZE} square. */
    private SceneView main(Camera camera) {
        return new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera);
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

    /** Copies {@link Keys#SCENE_COLOR} of each frame into the readback buffer of the frame's slot. */
    private record Readback(FrameGraph graph, GpuBuffer[] readbacks) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(Keys.SCENE_COLOR);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            g.pass("readback").read(color, Access.TRANSFER_SRC).sideEffects().record(ctx -> {
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.imageExtent().set(SIZE, SIZE, 1);
                    vkCmdCopyImageToBuffer(ctx.unsafe().commands(), ctx.unsafe().image(color), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        readbacks[graph.frameSlots().current()].handle(), region);
                }
            });
            g.provide(Keys.SCENE_COLOR, color);
        }
    }

    /** The mean column of the lit (non-black) pixels in {@code readback}. */
    private static double litColumn(GpuBuffer readback) {
        double sum = 0;
        int count = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if ((readback.memory().getAtIndex(ValueLayout.JAVA_INT, y * SIZE + x) & 0xff) > 128) {
                    sum += x;
                    count++;
                }
            }
        }
        assertThat(count).as("lit pixels").isPositive();
        return sum / count;
    }

    @Test
    void programsAreBuiltOnceAndShared() {
        Pipeline[] programs = new Pipeline[4];
        graph.add(new Technique() {
            @Override
            public List<Key<?>> provides() {
                return List.of(EDGE);
            }

            @Override
            public void build(GraphBuilder g) {
                programs[0] = g.computeProgram("shaders/test/depthcopy.spv", "copyMain", 8);
                programs[1] = g.computeProgram("shaders/test/depthcopy.spv", "copyMain", 8);
                programs[2] = g.computeProgram("shaders/test/depthcopy.spv", "copyMain", 8, 1); // another variant
                programs[3] = g.computeProgram("shaders/test/depthcopy.spv", "copyMain", 8, 1);
                Image image = g.create("cleared", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
                g.pass("clear").write(image, Access.TRANSFER_DST).record(ctx -> ctx.clear(image.whole(), 1, 0, 0, 1));
                g.provide(EDGE, image);
            }
        }).output(EDGE, output);
        frame();
        assertThat(programs[1]).isSameAs(programs[0]);
        assertThat(programs[3]).isSameAs(programs[2]).isNotSameAs(programs[0]);
        assertThat(red(0, 0)).isEqualTo(1);
    }

    @Test
    void customPassesReadTheInstancesThroughTheGraph() {
        // A pass of a game's own, reading the engine's instances as an external buffer of the graph: ordered after
        // the scatter of the frame's changes, which the frame's first commands upload.
        try (GpuScene shared = new GpuScene(device, pool, SIZE * SIZE * 4 / GpuScene.INSTANCE_BYTES,
            graph.frameSlots())) {
            com.truesim.engine.graph.ExternalBuffer instances = new com.truesim.engine.graph.ExternalBuffer("instances",
                shared.instanceBuffer());
            assertThat(shared.instanceAddress()).isEqualTo(shared.instanceBuffer().deviceAddress());
            assertThat(shared.materialsAddress()).isEqualTo(shared.materials().deviceAddress());
            assertThat(pool.meshTable().deviceAddress()).isNotZero();
            assertThat(pool.vertexBase(quad)).isNotNegative();
            graph.add(new Technique() {
                @Override
                public List<Key<?>> provides() {
                    return List.of(EDGE);
                }

                @Override
                public void build(GraphBuilder g) {
                    com.truesim.engine.graph.Buffer buffer = g.importBuffer(instances);
                    Image image = g.create("instances as texels", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
                    g.pass("copy instances").read(buffer, com.truesim.engine.graph.BufferAccess.TRANSFER_SRC)
                        .write(image, Access.TRANSFER_DST).record(ctx -> ctx.copy(buffer, 0, image.whole()));
                    g.provide(EDGE, image);
                }
            }).output(EDGE, output);
            shared.set(2, quad, 0, 4, false, 1.5, -2, 3, 0, 0, 0, 1, 1, 1, 1);
            frame();
            int[] words = new int[SIZE * SIZE];
            for (int i = 0; i < words.length; i++) {
                words[i] = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i);
            }
            int record = 2 * GpuScene.INSTANCE_BYTES / 4;
            assertThat(words[record + 7]).as("mesh word: the mesh, live").isEqualTo(quad | 1 << 24);
            assertThat(Float.intBitsToFloat(words[record + 4])).as("x").isEqualTo(1.5f);
            assertThat(words[record + 15]).as("layers").isEqualTo(4);
            assertThat(words[7]).as("instance 0, a hole").isZero();
        }
    }

    @Test
    void theSceneIsOneSetOfBuffersForTheEngineAndAGame() {
        // The engine's culling and drawing declare what they read of the scene; a game's pass reading it, through the
        // shared request or its own import, gets the same buffers, so the graph orders the two.
        long[] address = new long[1];
        SceneView main = main(new Camera());
        main.port().add(new ClusteredForwardShading(SceneGeometry.meshlets(renderer), main, VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_D32_SFLOAT)).add(new Technique() {
                @Override
                public List<Key<?>> provides() {
                    return List.of();
                }

                @Override
                public List<Key<?>> modifies() {
                    return List.of(Keys.SCENE_COLOR);
                }

                @Override
                public void build(GraphBuilder g) {
                    g.modify(Keys.SCENE_COLOR);
                    SceneData.Buffers scene = g.request(new SceneData(renderer));
                    assertThat(g.importBuffer(new com.truesim.engine.graph.ExternalBuffer("game's instances",
                        renderer.scene().instanceBuffer()))).isSameAs(scene.instances());
                    assertThat(g.importBuffer(new com.truesim.engine.graph.ExternalBuffer("game's positions",
                        pool.positions()))).isSameAs(scene.positions());
                    scene.read(g.pass("game reads the scene"), com.truesim.engine.graph.BufferAccess.STORAGE_READ)
                        .sideEffects().record(ctx -> address[0] = ctx.address(scene.instances()));
                }
            }).output(Keys.SCENE_COLOR, output);
        frame();
        assertThat(graph.passes()).contains("meshlet culling", "meshlets early", "game reads the scene");
        assertThat(address[0]).isEqualTo(renderer.scene().instanceAddress());
    }

    @Test
    void aPoolGrowingWhileFramesAreInFlightKeepsItsMeshes() {
        // A pool with room for one quad grows with every mesh added while frames are in flight; the first mesh, copied
        // into each bigger buffer on the GPU, draws throughout.
        GpuBuffer[] readbacks = {GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT),
            GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT)};
        try (GeometryPool growing = new GeometryPool(device, GeometryPool.Capacity.of(4, 1, 1), graph.frameSlots());
             GpuScene moving = new GpuScene(device, growing, 4, graph.frameSlots());
             MeshletRenderer drawing = new MeshletRenderer(device, moving, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, graph.frameSlots())) {
            SceneGeometry geometry = SceneGeometry.meshlets(drawing);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            ViewPort port = graph.addView("main").size(SIZE, SIZE);
            SceneView movingView = geometry.createView(port, camera, ViewOptions.of(256, 1 << 12));
            port.add(new ClusteredForwardShading(geometry, movingView, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0, 0, 0))
                .add(new Readback(graph, readbacks)).output(Keys.SCENE_COLOR, output);
            int first = -1;
            for (int frame = 0; frame < 8; frame++) {
                graph.beginFrame();
                int added = growing.add(quadMesh);
                first = frame == 0 ? added : first;
                moving.set(0, first, frame % 2 == 0 ? -1 : 1, 0, -3, HALF, 0, 0, HALF, 0.5f, 0.5f, 0.5f);
                movingView.set(1, MeshletRenderer.MODE_WHITE);
                graph.submit(0, 0);
                if (frame == 7) {
                    device.waitIdle();
                    assertThat(litColumn(readbacks[1])).as("the last frame").isGreaterThan(SIZE / 2.0 + 4);
                    assertThat(litColumn(readbacks[0])).as("the frame before").isLessThan(SIZE / 2.0 - 4);
                }
            }
            assertThat(growing.meshCount()).isEqualTo(8);
            assertThat(growing.capacity().meshes()).isGreaterThanOrEqualTo(8);
            assertThat(growing.capacity().vertices()).isGreaterThanOrEqualTo(32);
        } finally {
            device.waitIdle();
            for (GpuBuffer readback : readbacks) {
                readback.close();
            }
        }
    }

    @Test
    void aSceneGrowingWhileFramesAreInFlightKeepsItsInstances() {
        // A scene with room for one instance grows as one is added every frame (behind the camera) while frames are in
        // flight; the first, set once, is copied into each bigger table on the GPU and draws throughout, culled with
        // occlusion, whose per-instance history grows along.
        GpuBuffer[] readbacks = {GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT),
            GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT)};
        try (GpuScene growing = new GpuScene(device, pool, 1, graph.frameSlots());
             MeshletRenderer drawing = new MeshletRenderer(device, growing, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, graph.frameSlots())) {
            SceneGeometry geometry = SceneGeometry.meshlets(drawing);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            ViewPort port = graph.addView("main").size(SIZE, SIZE);
            SceneView growingView = geometry.createView(port, camera, ViewOptions.of(256, 1 << 12).withOcclusion(true));
            port.add(new ClusteredForwardShading(geometry, growingView, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0, 0, 0))
                .add(new Readback(graph, readbacks)).output(Keys.SCENE_COLOR, output);
            for (int frame = 0; frame < 8; frame++) {
                graph.beginFrame();
                if (frame == 0) {
                    growing.set(0, quad, 1, 0, -3, HALF, 0, 0, HALF, 0.5f, 0.5f, 0.5f);
                } else {
                    growing.add(quad, 0, 1, true, 0, 0, 5, 0, 0, 0, 1, 1, 1, 1); // behind the camera
                }
                growingView.set(1, MeshletRenderer.MODE_WHITE);
                graph.submit(0, 0);
            }
            device.waitIdle();
            assertThat(litColumn(readbacks[1])).as("the first instance, set once").isGreaterThan(SIZE / 2.0 + 4);
            assertThat(litColumn(readbacks[0])).isGreaterThan(SIZE / 2.0 + 4);
            assertThat(growing.count()).isEqualTo(8);
            assertThat(growing.capacity()).isGreaterThanOrEqualTo(8);
        } finally {
            device.waitIdle();
            for (GpuBuffer readback : readbacks) {
                readback.close();
            }
        }
    }

    @Test
    void chunksGrowBetweenFramesToWhatFramesWanted() {
        // Room for one chunk of 64 meshlets, three quads culled: the frame wants three, leaves two out and says so, and
        // the chunks grow as it ends; the frames after find all three.
        GpuBuffer[] readbacks = {GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT),
            GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT)};
        try (GpuScene few = new GpuScene(device, pool, 4, graph.frameSlots());
             MeshletRenderer drawing = new MeshletRenderer(device, few, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, graph.frameSlots(), 1)) {
            for (int i = 0; i < 3; i++) {
                few.set(i, quad, i - 1, 0, -4, HALF, 0, 0, HALF, 0.4f, 0.4f, 0.4f);
            }
            SceneGeometry geometry = SceneGeometry.meshlets(drawing);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            ViewPort port = graph.addView("main").size(SIZE, SIZE);
            SceneView fewView = geometry.createView(port, camera, ViewOptions.of(256, 1 << 12));
            port.add(new ClusteredForwardShading(geometry, fewView, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0, 0, 0))
                .add(new Readback(graph, readbacks)).output(Keys.SCENE_COLOR, output);
            fewView.set(1, MeshletRenderer.MODE_WHITE);
            assertThat(drawing.chunkCapacity()).isEqualTo(1);
            graph.beginFrame();
            graph.submit(0, 0);
            device.waitIdle();
            assertThat(drawing.wantedChunks()).as("one of three fitted").isEqualTo(3);
            // Grown as a frame ends once the GPU has said what it wanted: that frame's, or the next one's.
            for (int frame = 0; frame < 3; frame++) {
                graph.beginFrame();
                graph.submit(0, 0);
            }
            device.waitIdle();
            assertThat(drawing.chunkCapacity()).isGreaterThanOrEqualTo(3);
            assertThat(drawing.chunksOverflowed()).isFalse();
            assertThat(fewView.view().visibleMeshlets()).as("all three found").isEqualTo(3);
        } finally {
            device.waitIdle();
            for (GpuBuffer readback : readbacks) {
                readback.close();
            }
        }
    }

    @Test
    void meshesAddedWhileFramesAreInFlightDrawTheirFirstFrame() {
        // Every frame a mesh is added (and the one from two frames before removed) while frames are in flight, and the
        // quad draws with it at once: the upload is staged without waiting and recorded first thing in the frame.
        GpuBuffer[] readbacks = {GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT),
            GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT)};
        try (GeometryPool streamed = new GeometryPool(device, GeometryPool.Capacity.of(64, 8, 4), graph.frameSlots());
             GpuScene moving = new GpuScene(device, streamed, 4, graph.frameSlots());
             MeshletRenderer drawing = new MeshletRenderer(device, moving, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, graph.frameSlots())) {
            SceneGeometry geometry = SceneGeometry.meshlets(drawing);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            ViewPort port = graph.addView("main").size(SIZE, SIZE);
            SceneView movingView = geometry.createView(port, camera, ViewOptions.of(256, 1 << 12));
            port.add(new ClusteredForwardShading(geometry, movingView, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0, 0, 0))
                .add(new Readback(graph, readbacks)).output(Keys.SCENE_COLOR, output);
            int[] meshes = new int[8];
            for (int frame = 0; frame < 8; frame++) {
                graph.beginFrame();
                if (frame >= 2) {
                    streamed.remove(meshes[frame - 2]); // its frames may still be in flight: kept until they are not
                }
                meshes[frame] = streamed.add(quadMesh);
                moving.set(0, meshes[frame], frame % 2 == 0 ? -1 : 1, 0, -3, HALF, 0, 0, HALF, 0.5f, 0.5f, 0.5f);
                movingView.set(1, MeshletRenderer.MODE_WHITE);
                graph.submit(0, 0);
                if (frame == 7) {
                    device.waitIdle();
                    assertThat(litColumn(readbacks[1])).as("the last frame's new mesh").isGreaterThan(SIZE / 2.0 + 4);
                    assertThat(litColumn(readbacks[0])).as("the frame before").isLessThan(SIZE / 2.0 - 4);
                }
            }
            assertThat(streamed.meshCount()).isEqualTo(2);
            assertThat(meshes).as("ranges and numbers come back once their frames are done").contains(meshes[0], 
                meshes[2]);
        } finally {
            device.waitIdle();
            for (GpuBuffer readback : readbacks) {
                readback.close();
            }
        }
    }

    @Test
    void instancesChangeEveryFrameWithFramesInFlight() {
        // A quad moving left and right every frame, two frames in flight: each frame draws where it was set, as the
        // instance data has a copy per frame slot.
        GpuBuffer[] readbacks = {GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT),
            GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT)};
        try (GpuScene moving = new GpuScene(device, pool, 16, graph.frameSlots());
             MeshletRenderer drawing = new MeshletRenderer(device, moving, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, graph.frameSlots())) {
            SceneGeometry geometry = SceneGeometry.meshlets(drawing);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            ViewPort port = graph.addView("main").size(SIZE, SIZE);
            SceneView movingView = geometry.createView(port, camera, ViewOptions.of(256, 1 << 12).withOcclusion(true));
            port.add(new ClusteredForwardShading(geometry, movingView, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0, 0, 0))
                .add(new Readback(graph, readbacks)).output(Keys.SCENE_COLOR, output);
            for (int frame = 0; frame < 6; frame++) {
                int slot = graph.beginFrame();
                assertThat(slot).isEqualTo(frame % 2);
                // Facing the camera, a quarter of the view wide, left on even frames and right on odd ones.
                moving.set(0, quad, frame % 2 == 0 ? -1 : 1, 0, -3, HALF, 0, 0, HALF, 0.5f, 0.5f, 0.5f);
                movingView.set(1, MeshletRenderer.MODE_WHITE);
                graph.submit(0, 0);
                // Changing the instance while both frames are in flight does not reach them.
                moving.set(0, quad, 0, 0, -3, HALF, 0, 0, HALF, 0.5f, 0.5f, 0.5f);
            }
            device.waitIdle();
            assertThat(litColumn(readbacks[0])).as("the last even frame").isLessThan(SIZE / 2.0 - 4);
            assertThat(litColumn(readbacks[1])).as("the last odd frame").isGreaterThan(SIZE / 2.0 + 4);
        } finally {
            device.waitIdle();
            for (GpuBuffer readback : readbacks) {
                readback.close();
            }
        }
    }

    @Test
    void recordingOnSeveralThreadsDrawsWhatOneThreadDraws() {
        // Both shadings, occlusion culled, a quad moving every frame, recorded on the calling thread and then a pass
        // per chunk on four threads: the same images, as pass bodies share nothing they change and the frame start
        // (the instances' upload) is recorded before any of them.
        GpuBuffer[] readbacks = {GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT),
            GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT)};
        try (GpuScene moving = new GpuScene(device, pool, 16, graph.frameSlots());
             MeshletRenderer drawing = new MeshletRenderer(device, moving, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, graph.frameSlots());
             Lights lights = new Lights(device, 4, 1, graph.frameSlots());
             com.truesim.common.jobs.JobSystem jobs = new com.truesim.common.jobs.JobSystem(4)) {
            SceneGeometry geometry = SceneGeometry.meshlets(drawing);
            Camera camera = new Camera().set(0, -1, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            ViewPort port = graph.addView("main").size(SIZE, SIZE);
            SceneView movingView = geometry.createView(port, camera, ViewOptions.of(256, 1 << 12).withOcclusion(true));
            lights.point(0, -1, -0.5f, -1.5f, 3, 6, 0, 0).point(1, 1, -0.5f, -1.5f, 3, 0, 0, 6)
                .directional(2, -0.4f, -0.8f, -0.3f, 1, 1, 1).setCount(3);
            moving.set(0, quad, 0, -1, -4, 0, 0, 0, 1, 4, 4, 4);
            Technique[] shadings = {
                new ClusteredForwardShading(geometry, movingView, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0, 0, 0.5f),
                new VisibilityBufferShading(geometry, movingView, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0, 0, 0.5f)};
            port.add(shadings[0]).add(new ClusteredLights(lights, movingView))
                .add(new Readback(graph, readbacks)).output(Keys.SCENE_COLOR, output);
            for (Technique shading : shadings) {
                if (shading instanceof VisibilityBufferShading && !device.primitiveIds()) {
                    continue;
                }
                port.replace(shading);
                int[][] images = new int[2][];
                for (int run = 0; run < 2; run++) {
                    graph.parallel(run == 0 ? null : jobs, 1);
                    for (int frame = 0; frame < 6; frame++) {
                        graph.beginFrame();
                        // A quad standing on the floor, moving left and right: the instances change every frame.
                        moving.set(1, quad, frame % 2 == 0 ? -0.5f : 0.5f, -0.5f, -2.5f, HALF, 0, 0, HALF, 0.4f,
                            0.4f, 0.4f);
                        movingView.set(1, MeshletRenderer.MODE_LIT);
                        graph.submit(0, 0);
                    }
                    device.waitIdle();
                    images[run] = new int[SIZE * SIZE];
                    for (int i = 0; i < SIZE * SIZE; i++) {
                        images[run][i] = readbacks[1].memory().getAtIndex(ValueLayout.JAVA_INT, i);
                    }
                }
                assertThat(graph.chunks()).as("a chunk per pass").isGreaterThan(4);
                assertThat(images[1]).as("%s on four threads", shading.getClass().getSimpleName())
                    .isEqualTo(images[0]);
                assertThat(java.util.Arrays.stream(images[1]).distinct().count()).as("something drawn")
                    .isGreaterThan(2);
            }
        } finally {
            device.waitIdle();
            for (GpuBuffer readback : readbacks) {
                readback.close();
            }
        }
    }

    @Test
    void clusteredForwardShadingLightsEachPixelByTheLightsNearIt() {
        // A floor below the camera, a red light over its left side and a blue one over its right.
        scene.set(0, quad, 0, -1, -4, 0, 0, 0, 1, 4, 4, 4);
        scene.removeFrom(1);
        Camera camera = new Camera().set(0, -1, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        try (Lights lights = new Lights(device, 4, 1)) {
            lights.point(0, -1, -0.5f, -1.5f, 3, 6, 0, 0).point(1, 1, -0.5f, -1.5f, 3, 0, 0, 6).setCount(2);
            SceneView main = main(camera);
            ClusteredLights clustered = new ClusteredLights(lights, main);
            main.port().add(MeshletBenchmark.forward(main)).add(clustered).output(Keys.SCENE_COLOR, output);
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
            // Without a light list the scene is lit by the sun again.
            main.port().remove(clustered);
            frame();
            assertThat(graph.passes()).doesNotContain("light clusters");
        }
    }

    @Test
    void bothShadingsDrawTheSameImage() {
        assumeThat(device.primitiveIds()).as("primitive IDs in fragment shaders").isTrue();
        // The lit floor, and a quad standing on it hiding part of it.
        scene.set(0, quad, 0, -1, -4, 0, 0, 0, 1, 4, 4, 4);
        scene.set(1, quad, 0, -0.5f, -2.5f, HALF, 0, 0, HALF, 0.4f, 0.4f, 0.4f);
        scene.removeFrom(2);
        Camera camera = new Camera().set(0, -1, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        try (Lights lights = new Lights(device, 4, 1)) {
            lights.point(0, -1, -0.5f, -1.5f, 3, 6, 0, 0).point(1, 1, -0.5f, -1.5f, 3, 0, 0, 6)
                .directional(2, -0.4f, -0.8f, -0.3f, 1, 1, 1).setCount(3);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            SceneView main = main(camera);
            ClusteredForwardShading forwardShading = new ClusteredForwardShading(geometry, main,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0.5f);
            VisibilityBufferShading visibilityShading = new VisibilityBufferShading(geometry, main,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0.5f);
            main.port().add(forwardShading).add(new ClusteredLights(lights, main)).output(Keys.SCENE_COLOR, output);
            for (MeshletRenderer.Path path : new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED,
                MeshletRenderer.Path.AUTO}) {
                renderer.setPath(path);
                main.port().replace(forwardShading);
                frame();
                frame(); // the late phase has drawn what the early one missed the first time
                int[] forward = new int[SIZE * SIZE];
                for (int i = 0; i < forward.length; i++) {
                    forward[i] = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i);
                }
                main.port().replace(visibilityShading);
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
    void theVisibilityBufferShadesEachMaterialTypeWithItsOwnShader() {
        assumeThat(device.primitiveIds()).as("primitive IDs in fragment shaders").isTrue();
        // The lit floor, metallic-roughness, and a quad standing on it, of the type under test.
        scene.set(0, quad, 0, -1, -4, 0, 0, 0, 1, 4, 4, 4);
        scene.removeFrom(2);
        Camera camera = new Camera().set(0, -1, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        try (Materials materials = new Materials(device, 4)) {
            int quadMaterial = materials.add(new Material(MR.withBaseColor(new Color(1, 0, 1, 1))));
            scene.setMaterials(materials);
            scene.set(1, quad, quadMaterial, 0, -0.5f, -2.5f, HALF, 0, 0, HALF, 0.4f, 0.4f, 0.4f);
            assertThatIllegalArgumentException().as("no type takes it yet")
                .isThrownBy(() -> materials.set(quadMaterial, new Material(GlowParameters.DEFAULTS)));
            SceneView main = main(camera);
            main.port().add(new VisibilityBufferShading(SceneGeometry.meshlets(renderer), main,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0.5f))
                .output(Keys.SCENE_COLOR, output);
            frame();
            frame();
            assertThat(graph.passes()).containsSubsequence("material tiles reset", "material classification",
                "visibility shading");
            int[] before = pixels.memory().toArray(ValueLayout.JAVA_INT);
            assertThat(glowing(before)).as("the metallic-roughness quad is lit, not glowing").isZero();
            // Registered after the graph built: it rebuilds with the type's shading pass before the quad draws as it.
            int builds = graph.builds();
            materials.register(Glow.TYPE);
            assertThat(materials.types()).containsExactly(MetallicRoughnessMaterial.TYPE, Glow.TYPE);
            assertThat(materials.type("glow")).isSameAs(Glow.TYPE);
            materials.set(quadMaterial, new Material(GlowParameters.DEFAULTS));
            frame();
            assertThat(graph.builds()).as("rebuilt for the new type").isEqualTo(builds + 1);
            // Bins: a combination of type and features each, numbered as they first appear, never twice.
            assertThat(materials.binCount()).isEqualTo(2);
            assertThat(materials.binType(0)).isSameAs(MetallicRoughnessMaterial.TYPE);
            assertThat(materials.binType(1)).isSameAs(Glow.TYPE);
            assertThat(materials.binFeatures(1)).isZero();
            int coated = materials.add(new Material(MR).feature(ClearcoatParameters.DEFAULTS));
            materials.add(new Material(MR.withRoughness(0.2f)).feature(ClearcoatParameters.DEFAULTS));
            assertThat(materials.binCount()).as("metallic-roughness with a clear coat, once").isEqualTo(3);
            assertThat(materials.binFeatures(2)).isEqualTo(Material.FEATURE_CLEARCOAT);
            assertThat(materials.features(coated)).isEqualTo(Material.FEATURE_CLEARCOAT);
            assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> materials.binType(3));
            int[] after = pixels.memory().toArray(ValueLayout.JAVA_INT);
            assertThat(glowing(after)).as("the quad's pixels").isGreaterThan(SIZE * SIZE / 40);
            for (int i = 0; i < after.length; i++) {
                if (!glowing(after[i])) {
                    assertThat(after[i]).as("pixel %d, metallic-roughness as before", i).isEqualTo(before[i]);
                }
            }
            // Its parameters, read by its shader: dimmed in place, then back by the record.
            materials.update(quadMaterial, GlowParameters.STRENGTH, 0.5f);
            frame();
            assertThat(glowing(pixels.memory().toArray(ValueLayout.JAVA_INT))).isZero();
            materials.set(quadMaterial, new Material(GlowParameters.DEFAULTS.withStrength(1)));
            frame();
            assertThat(pixels.memory().toArray(ValueLayout.JAVA_INT)).isEqualTo(after);
            assertThatIllegalArgumentException().as("out of its range")
                .isThrownBy(() -> materials.update(quadMaterial, GlowParameters.STRENGTH, 17f));
            assertThatIllegalArgumentException().isThrownBy(() -> new GlowParameters(new Color(1, 1, 1, 1), -1));
            assertThatIllegalArgumentException().as("glow allows no features").isThrownBy(() -> materials.set(
                quadMaterial, new Material(GlowParameters.DEFAULTS).feature(ClearcoatParameters.DEFAULTS)));
            assertThatIllegalArgumentException().as("not its field")
                .isThrownBy(() -> materials.update(quadMaterial, MetallicRoughnessParameters.ROUGHNESS, 0.5f));
            assertThatIllegalArgumentException().isThrownBy(() -> materials.register(Glow.TYPE));
            assertThatIllegalArgumentException().isThrownBy(() -> new MaterialType("half", GlowParameters.class,
                GlowParameters::fromValues, 0, "a.spv", "a", "b.spv", null));
            // At half the resolution: the glow type's bin, past bin 0, is shaded from the list classification wrote
            // into, though the render region has fewer tiles than the images the lists are spaced for.
            graph.settings().set(ResolutionSettings.DYNAMIC, true).set(ResolutionSettings.HEADROOM, 1.5f);
            main.port().renderScale(0.5f);
            frame();
            frame();
            assertThat(main.port().regionWidth()).isLessThan(main.port().width());
            assertThat(glowing(pixels.memory().toArray(ValueLayout.JAVA_INT))).as("the quad's pixels, at half")
                .isGreaterThan(SIZE * SIZE / 40);
            main.port().renderScale(1);
            graph.settings().set(ResolutionSettings.DYNAMIC, false);
            scene.setMaterials(null);
        }
    }

    @Test
    void materialsNameBuffersTheHostOrTheGraphWrites() {
        assumeThat(device.primitiveIds()).as("primitive IDs in fragment shaders").isTrue();
        scene.set(0, quad, 0, -1, -4, 0, 0, 0, 1, 4, 4, 4);
        scene.removeFrom(2);
        Camera camera = new Camera().set(0, -1, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
            | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        try (Materials materials = new Materials(device, 4);
             GpuBuffer green = GpuBuffer.hostVisible(device, 16, usage);
             GpuBuffer written = GpuBuffer.deviceLocal(device, 16, usage)) {
            // The host's: green, written once.
            green.memory().copyFrom(java.lang.foreign.MemorySegment.ofArray(new float[] {0, 1, 0, 1}));
            materials.register(Tinted.TYPE);
            int quadMaterial = materials.add(new Material(TintedParameters.DEFAULTS.withTint(green)));
            scene.setMaterials(materials);
            scene.set(1, quad, quadMaterial, 0, -0.5f, -2.5f, HALF, 0, 0, HALF, 0.4f, 0.4f, 0.4f);
            SceneView main = main(camera);
            main.port().add(new VisibilityBufferShading(SceneGeometry.meshlets(renderer), main,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0.5f));
            // A game's pass writing blue into a buffer of the graph every frame, after the shading: the next frame's
            // shading reads it, ordered by the graph, as the passes reading scene data read it.
            com.truesim.engine.graph.ExternalBuffer tint = new com.truesim.engine.graph.ExternalBuffer("tint", written);
            main.port().add(new Technique() {
                @Override
                public List<Key<?>> provides() {
                    return List.of();
                }

                @Override
                public List<Key<?>> modifies() {
                    return List.of(Keys.SCENE_COLOR);
                }

                @Override
                public void build(GraphBuilder g) {
                    g.modify(Keys.SCENE_COLOR);
                    com.truesim.engine.graph.Buffer buffer = g.importBuffer(tint);
                    g.pass("tint").write(buffer, com.truesim.engine.graph.BufferAccess.TRANSFER_DST).record(ctx ->
                        org.lwjgl.vulkan.VK10.vkCmdUpdateBuffer(ctx.unsafe().commands(), written.handle(), 0,
                            new float[] {0, 0, 1, 1}));
                }
            }).output(Keys.SCENE_COLOR, output);
            frame();
            frame();
            assertThat(tinted(0xff00ff00)).as("green, the host's buffer").isGreaterThan(SIZE * SIZE / 40);
            assertThat(materials.graphBuffers()).isEmpty();
            int builds = graph.builds();
            materials.update(quadMaterial, TintedParameters.TINT, tint);
            assertThat(materials.graphBuffers()).containsExactly(tint);
            frame();
            frame();
            assertThat(graph.builds()).as("rebuilt to import it").isEqualTo(builds + 1);
            assertThat(tinted(0xffff0000)).as("blue, the graph's").isGreaterThan(SIZE * SIZE / 40);
            assertThat(graph.passes()).containsSubsequence("visibility shading", "tint");
            // Set anew without it: no longer imported.
            materials.set(quadMaterial, new Material(TintedParameters.DEFAULTS));
            assertThat(materials.graphBuffers()).isEmpty();
            frame();
            assertThat(tinted(0xffff0000)).as("no buffer: black").isZero();
            assertThatIllegalArgumentException().as("not a buffer field")
                .isThrownBy(() -> materials.update(quadMaterial, TintedParameters.STRENGTH, tint));
            scene.setMaterials(null);
        }
    }

    /** How many pixels are {@code abgr}, to a few levels. */
    private int tinted(int abgr) {
        int count = 0;
        for (int pixel : pixels.memory().toArray(ValueLayout.JAVA_INT)) {
            boolean near = true;
            for (int shift = 0; shift < 24; shift += 8) {
                near &= Math.abs((pixel >> shift & 0xff) - (abgr >> shift & 0xff)) <= 5;
            }
            count += near ? 1 : 0;
        }
        return count;
    }

    @Test
    void typesBuildOnMetallicRoughnessByNestingItsParameters() {
        assumeThat(device.primitiveIds()).as("primitive IDs in fragment shaders").isTrue();
        scene.removeFrom(1);
        Camera camera = new Camera().set(0, -1, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        try (Materials materials = new Materials(device, 4)) {
            materials.register(CarPaint.TYPE);
            MetallicRoughnessParameters red = MR.withBaseColor(new Color(0.6, 0.05, 0.05, 1)).withRoughness(0.4f);
            int floor = materials.add(new Material(red));
            scene.setMaterials(materials);
            scene.set(0, quad, floor, 0, -1, -4, 0, 0, 0, 1, 4, 4, 4);
            SceneView main = main(camera);
            main.port().add(new VisibilityBufferShading(SceneGeometry.meshlets(renderer), main,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)).output(Keys.SCENE_COLOR, output);
            frame();
            frame();
            int[] plain = pixels.memory().toArray(ValueLayout.JAVA_INT);
            // Car paint without flakes is its nested metallic-roughness, to the bit: the layouts agree.
            CarPaintParameters paint = CarPaintParameters.DEFAULTS.withBase(red);
            assertThat(paint.maskAlpha()).as("masked by its base's alpha").isEqualTo(1);
            materials.set(floor, new Material(paint));
            frame();
            assertThat(pixels.memory().toArray(ValueLayout.JAVA_INT)).isEqualTo(plain);
            // Its own field, and its base's by path, in place.
            materials.update(floor, CarPaintParameters.FLAKES, 1f);
            frame();
            int[] flakes = pixels.memory().toArray(ValueLayout.JAVA_INT);
            assertThat(brightest(flakes)).as("white flakes").isGreaterThan(brightest(plain) + 100);
            materials.update(floor, CarPaintParameters.BASE_ROUGHNESS, 0.9f);
            frame();
            assertThat(pixels.memory().toArray(ValueLayout.JAVA_INT)).isNotEqualTo(flakes);
            // Its type allows a clear coat: the engine lays it over car paint as over metallic-roughness.
            materials.set(floor, new Material(paint).feature(ClearcoatParameters.DEFAULTS.withRoughness(0.1f)));
            assertThat(materials.features(floor)).isEqualTo(Material.FEATURE_CLEARCOAT);
            frame();
            assertThat(pixels.memory().toArray(ValueLayout.JAVA_INT)).isNotEqualTo(plain);
            scene.setMaterials(null);
        }
    }

    @Test
    void clearCoatsShineAlikeInBothShadings() {
        assumeThat(device.primitiveIds()).as("primitive IDs in fragment shaders").isTrue();
        // A dark, rough floor, a light over it in front of the camera: its mirror image shows only in a glossy coat.
        scene.removeFrom(1);
        Camera camera = new Camera().set(0, -1, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        try (Lights lights = new Lights(device, 4, 1); Materials materials = new Materials(device, 4)) {
            lights.point(0, 0, -0.5f, -3, 6, 4, 4, 4).setCount(1);
            Material base = new Material(MR.withBaseColor(new Color(0.1, 0.1, 0.1, 1)).withRoughness(1));
            int rough = materials.add(base);
            int coated = materials.add(base.feature(ClearcoatParameters.DEFAULTS.withRoughness(0.1f)));
            assertThat(base.featureMask()).isZero();
            assertThat(materials.features(coated)).isEqualTo(Material.FEATURE_CLEARCOAT);
            assertThat(materials.contains(coated)).isTrue();
            scene.setMaterials(materials);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            SceneView main = main(camera);
            ClusteredForwardShading forwardShading = new ClusteredForwardShading(geometry, main,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
            VisibilityBufferShading visibilityShading = new VisibilityBufferShading(geometry, main,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
            main.port().add(forwardShading).add(new ClusteredLights(lights, main)).output(Keys.SCENE_COLOR, output);
            int[][] images = new int[4][];
            int i = 0;
            for (int material : new int[] {rough, coated}) {
                scene.set(0, quad, material, 0, -1, -4, 0, 0, 0, 1, 4, 4, 4);
                for (Technique shading : new Technique[] {forwardShading, visibilityShading}) {
                    main.port().replace(shading);
                    frame();
                    frame();
                    images[i++] = pixels.memory().toArray(ValueLayout.JAVA_INT);
                }
            }
            assertThat(brightest(images[2])).as("forward, coated").isGreaterThan(brightest(images[0]) + 60);
            assertThat(brightest(images[3])).as("visibility, coated").isGreaterThan(brightest(images[1]) + 60);
            assertThat(meanDifference(images[0], images[1])).as("both shadings, rough").isLessThan(0.5);
            assertThat(meanDifference(images[2], images[3])).as("both shadings, coated").isLessThan(0.5);
            scene.setMaterials(null);
        }
    }

    /** The brightest pixel's red + green + blue. */
    private static int brightest(int[] image) {
        int best = 0;
        for (int p : image) {
            best = Math.max(best, (p & 0xff) + (p >> 8 & 0xff) + (p >> 16 & 0xff));
        }
        return best;
    }

    private static double meanDifference(int[] a, int[] b) {
        double total = 0;
        for (int i = 0; i < a.length; i++) {
            for (int shift = 0; shift < 24; shift += 8) {
                total += Math.abs((a[i] >> shift & 0xff) - (b[i] >> shift & 0xff));
            }
        }
        return total / (3.0 * a.length);
    }

    private static boolean glowing(int pixel) {
        return (pixel & 0xff) >= 250 && (pixel >> 8 & 0xff) <= 5 && (pixel >> 16 & 0xff) >= 250;
    }

    private static int glowing(int[] image) {
        int count = 0;
        for (int pixel : image) {
            count += glowing(pixel) ? 1 : 0;
        }
        return count;
    }

    @Test
    void sceneDrawsAreSharedAndChecked() {
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        assertThat(geometry.provider(MeshletGeometry.class).renderer()).isSameAs(renderer);
        assertThat(geometry.provider(GeometryProvider.class)).isNotNull();
        GeometryProvider own = new GeometryProvider() { // a kind of geometry of its own: drawn through the renderer
            @Override
            public MeshletRenderer renderer() {
                return renderer;
            }

            @Override
            public void drawFirst(GraphBuilder g, SceneDraw draw, SceneDraw.Targets targets, boolean load) {
            }
        };
        assertThat(new SceneGeometry(own).provider(MeshletGeometry.class)).isNull();
        assertThat(new SceneGeometry(own).renderer()).isSameAs(renderer);
        scene.set(0, quad, 0, 0, -5, HALF, 0, 0, HALF, 1, 1, 1);
        scene.removeFrom(1);
        Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_WHITE);
        // Two techniques asking for the same depth-only draw of the camera, with occlusion culling: drawn once.
        Key<Image> first = Key.image("test.first");
        Key<Image> second = Key.image("test.second");
        SceneDraw draw = new SceneDraw(geometry, view, null, SurfaceOutput.depth(), VK_FORMAT_D32_SFLOAT, SIZE, SIZE,
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
        RenderView plain = geometry.createView(ViewOptions.of(16, 64));
        assertThatIllegalArgumentException().as("occlusion in a view without").isThrownBy(() -> new SceneDraw(
            geometry, plain, null, SurfaceOutput.ids(), VK_FORMAT_D32_SFLOAT, 1, 1, true));
        assertThatIllegalArgumentException().isThrownBy(() -> new SceneDraw(geometry, view, null, SurfaceOutput.ids(),
            VK_FORMAT_D32_SFLOAT, 0, 1, false));
        // Meshlets come first: another provider before them is not supported yet.
        graph.close();
        graph = new FrameGraph(device, 2);
        SceneView main = main(camera);
        assertThatIllegalArgumentException().isThrownBy(() -> new VisibilityBufferShading(new SceneGeometry(
            new MeshletGeometry(renderer), new MeshletGeometry(renderer)), main, VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_D32_SFLOAT));
        main.port().add(new ClusteredForwardShading(new SceneGeometry(new MeshletGeometry(renderer),
            new MeshletGeometry(renderer)), main, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT))
            .output(Keys.SCENE_COLOR, output);
        assertThatIllegalStateException().isThrownBy(this::frame).withMessageContaining("Meshlets are drawn first");
    }

    /** Provides {@code key}: the depth of {@code draw}, copied into the red channel of a color image. */
    private record DepthCopy(Key<Image> key, SceneDraw draw) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(key);
        }

        @Override
        public void build(GraphBuilder g) {
            Image depth = g.request(draw).depth();
            Image image = g.create(key.name(), ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            Pipeline pipeline = g.computeProgram("shaders/test/depthcopy.spv", "copyMain", 8);
            g.pass("copy " + key).read(depth, Access.SAMPLED_COMPUTE).write(image, Access.STORAGE_WRITE)
                .record(ctx -> {
                    try (MemoryStack stack = stackPush()) {
                        long push = stack.nmalloc(4, 8);
                        memPutInt(push, ctx.sampled(depth));
                        memPutInt(push + 4, ctx.storage(image));
                        pipeline.bind(ctx.unsafe().commands());
                        pipeline.push(ctx.unsafe().commands(), push, 8);
                        org.lwjgl.vulkan.VK10.vkCmdDispatch(ctx.unsafe().commands(), SIZE / 8, SIZE / 8, 1);
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
            Key<Image> depthKey = Key.image("test.depth");
            graph.add(new Technique() {
                @Override
                public List<Key<?>> provides() {
                    return List.of(depthKey);
                }

                @Override
                public void build(GraphBuilder g) {
                    Image image = g.create("depth", ImageDesc.of(Format.D32_SFLOAT, w, h));
                    g.pass("upload depth").write(image, Access.TRANSFER_DST).record(ctx -> {
                        try (MemoryStack stack = stackPush()) {
                            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1);
                            region.imageExtent().set(w, h, 1);
                            org.lwjgl.vulkan.VK10.vkCmdCopyBufferToImage(ctx.unsafe().commands(), upload.handle(),
                                ctx.unsafe().image(image), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                        }
                    });
                    g.provide(depthKey, image);
                }
            }).add(new Technique() {
                @Override
                public List<Key<?>> provides() {
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
                            vkCmdCopyImageToBuffer(ctx.unsafe().commands(), ctx.unsafe().image(pyramid),
                                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback.handle(), regions);
                        }
                    });
                    Image marker = g.create("marker", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
                    g.pass("marker").write(marker, Access.TRANSFER_DST).record(ctx -> { });
                    g.provide(EDGE, marker);
                }
            }).output(EDGE, output);
            frame();
            assertThat(graph.passes()).contains("read back").anyMatch(p -> p.startsWith("depth pyramid of depth"));
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
        scene.set(0, quad, 0, 0, -5, HALF, 0, 0, HALF, 1, 1, 1);
        scene.removeFrom(1);
        Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_WHITE);
        SceneView main = main(camera);
        main.port().add(MeshletBenchmark.forward(main).clearColor(0, 0, 1)).output(Keys.SCENE_COLOR, output);
        frame();
        java.util.List<String> passes = graph.passes();
        assertThat(passes).startsWith("scene upload", "meshlet culling", "meshlets early",
            "depth pyramid of scene depth, level 0").endsWith("meshlets late culling", "meshlets late"); // a pass per level
        assertThat(passes.subList(3, passes.size() - 2)).allMatch(p -> p.startsWith("depth pyramid of scene depth, level "));
        assertThat(graph.pooledImages()).isEqualTo(2); // depth and its pyramid: the color is the target
        assertThat(red(SIZE / 2, SIZE / 2)).isEqualTo(1);
        assertThat(red(1, 1)).isZero(); // blue clear color
        assertThat(view.visibleMeshlets()).isEqualTo(1);
        // Without occlusion culling: one pass.
        graph.close();
        graph = new FrameGraph(device, 2);
        main = main(camera);
        main.port().add(MeshletBenchmark.forward(main).occlusion(false)).output(Keys.SCENE_COLOR, output);
        frame();
        assertThat(graph.passes()).containsExactly("scene upload", "meshlet culling", "meshlets");
        assertThat(red(SIZE / 2, SIZE / 2)).isEqualTo(1);
        // Off through the graph's settings instead, and on again: rebuilt each time.
        graph.close();
        graph = new FrameGraph(device, 2);
        main = main(camera);
        main.port().add(MeshletBenchmark.forward(main)).output(Keys.SCENE_COLOR, output);
        graph.settings().set(RenderSettings.OCCLUSION, false);
        frame();
        assertThat(graph.passes()).containsExactly("scene upload", "meshlet culling", "meshlets");
        graph.settings().set(RenderSettings.OCCLUSION, true);
        frame();
        assertThat(graph.passes()).endsWith("meshlets late culling", "meshlets late");
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

    /** The target as read back by the last {@link #frame()}. */
    private int[] readPixels() {
        int[] read = new int[SIZE * SIZE];
        for (int i = 0; i < read.length; i++) {
            read[i] = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i);
        }
        return read;
    }

    /** The mean difference of the channels of {@code a} and {@code b}, 0 to 255. */
    private static double difference(int[] a, int[] b) {
        double total = 0;
        for (int i = 0; i < a.length; i++) {
            for (int shift = 0; shift < 24; shift += 8) {
                total += Math.abs(((a[i] >> shift) & 0xff) - ((b[i] >> shift) & 0xff));
            }
        }
        return total / (3.0 * a.length);
    }

    @Test
    void dynamicResolutionDrawsWhatAFixedResolutionDraws() {
        // The lit floor, and a quad standing on it hiding part of it.
        scene.set(0, quad, 0, -1, -4, 0, 0, 0, 1, 4, 4, 4);
        scene.set(1, quad, 0, -0.5f, -2.5f, HALF, 0, 0, HALF, 0.4f, 0.4f, 0.4f);
        scene.removeFrom(2);
        Camera camera = new Camera().set(0, -1, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        try (Lights lights = new Lights(device, 4, 1)) {
            lights.point(0, -1, -0.5f, -1.5f, 3, 6, 0, 0).point(1, 1, -0.5f, -1.5f, 3, 0, 0, 6)
                .directional(2, -0.4f, -0.8f, -0.3f, 1, 1, 1).setCount(3);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            SceneView main = main(camera);
            List<Technique> shadings = new java.util.ArrayList<>(List.of(new ClusteredForwardShading(geometry, main,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0.5f)));
            if (device.primitiveIds()) {
                shadings.add(new VisibilityBufferShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0.5f));
            }
            main.port().add(shadings.getFirst()).add(new ClusteredLights(lights, main))
                .output(Keys.SCENE_COLOR, output);
            for (Technique shading : shadings) {
                main.port().replace(shading);
                graph.settings().set(ResolutionSettings.DYNAMIC, false);
                frame();
                frame(); // the late phase has drawn what the early one missed the first time
                int[] fixed = readPixels();
                // Images larger than the view, its region all of what it shows: the same image, to the bit.
                graph.settings().set(ResolutionSettings.DYNAMIC, true).set(ResolutionSettings.HEADROOM, 1.5f);
                frame();
                frame();
                assertThat(main.port().width()).isGreaterThan(SIZE);
                assertThat(main.port().regionWidth()).isEqualTo(SIZE);
                assertThat(readPixels()).as("%s, the region all of the view", shading).isEqualTo(fixed);
                // Half the pixels each way, scaled up: near the same image.
                main.port().renderScale(0.5f);
                frame();
                frame();
                assertThat(main.port().regionWidth()).isEqualTo(SIZE / 2);
                assertThat(difference(readPixels(), fixed)).as("%s, at half the resolution", shading)
                    .isLessThan(12);
                main.port().renderScale(1);
            }
        }
    }

    @Test
    void detailFollowsTheRenderScale() {
        // The hill near the camera: fewer, coarser meshlets for the pixels a smaller render scale draws.
        int field = pool.add(MeshletRendererTest.grid(24, 0.12f, true, VertexFormat.POSITIONS_ONLY)); // 1,152 triangles
        scene.set(0, field, 0, -0.3f, -1.5f, 0, 0, 0, 1, 1, 1, 1);
        scene.removeFrom(1);
        Camera camera = new Camera().set(0, -0.3, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        view.set(camera, 0.5f, MeshletRenderer.MODE_LIT);
        graph.settings().set(ResolutionSettings.DYNAMIC, true).set(ResolutionSettings.MIN_SCALE, 0.25f);
        SceneView main = main(camera);
        main.port().add(MeshletBenchmark.forward(main)).output(Keys.SCENE_COLOR, output);
        frame();
        frame();
        int full = view.visibleMeshlets();
        assertThat(view.detail()).isEqualTo(1);
        main.port().renderScale(0.25f);
        frame();
        frame();
        assertThat(view.detail()).isEqualTo(0.25f);
        assertThat(view.visibleMeshlets()).as("against %d at full size", full).isLessThan(full);
        assertThatIllegalArgumentException().isThrownBy(() -> view.detail(0));
    }

    @Test
    void drawsThroughACameraOfTheirOwnTakeTheCasterDetail() {
        // A depth draw through a camera of its own, as a shadow map's, of a bumpy grid: coarser at a larger error.
        int field = pool.add(MeshletRendererTest.grid(24, 0.12f, true, VertexFormat.POSITIONS_ONLY));
        scene.set(0, field, 0, -0.3f, -1.5f, 0, 0, 0, 1, 1, 1, 1);
        scene.removeFrom(1);
        Camera camera = new Camera().set(0, -0.3, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        SceneDraw draw = new SceneDraw(SceneGeometry.meshlets(renderer), view, camera, SurfaceOutput.depth(),
            VK_FORMAT_D32_SFLOAT, SIZE, SIZE, false);
        Key<Image> copied = Key.image("test.casterDepth");
        graph.add(new DepthCopy(copied, draw)).output(copied, output);
        frame();
        int fine = view.visibleMeshlets();
        graph.settings().set(com.truesim.engine.render.shadows.ShadowSettings.CASTER_DETAIL, 8f);
        frame();
        assertThat(view.visibleMeshlets()).as("against %d at 1 pixel", fine).isLessThan(fine);
    }

    @Test
    void aViewSetOnceFollowsItsCameraAsItMoves() {
        // A wall ahead and 8 quads behind it; the camera is set on the view once, blank, and moved afterwards only.
        scene.set(0, quad, 0, 0, -5, HALF, 0, 0, HALF, 6, 6, 6);
        for (int i = 1; i <= 8; i++) {
            scene.set(i, quad, (i % 4 - 1.5f) * 2.5f, i <= 4 ? -1.5f : 1.5f, -10, HALF, 0, 0, HALF, 0.5f, 0.5f, 0.5f);
        }
        scene.removeFrom(9);
        Camera camera = new Camera();
        view.set(camera, 1, MeshletRenderer.MODE_WHITE); // before the camera is anywhere
        SceneView main = main(camera);
        main.port().add(MeshletBenchmark.forward(main).clearColor(0, 0, 1)).output(Keys.SCENE_COLOR, output);
        camera.set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        frame();
        frame();
        frame();
        assertThat(view.visibleMeshlets()).as("the wall, from where the camera is when the frames cull").isEqualTo(1);
        assertThat(covered()).isEqualTo(SIZE * SIZE);
        camera.position(100, 0, 0); // moved away: nothing ahead
        frame();
        assertThat(view.visibleMeshlets()).isZero();
        assertThat(covered()).isZero();
    }

    @Test
    void occlusionCullingHidesWhatIsBehindAtADynamicResolution() {
        // A wall filling the view 5 units ahead, and 8 quads behind it, 10 units ahead.
        scene.set(0, quad, 0, 0, -5, HALF, 0, 0, HALF, 6, 6, 6);
        for (int i = 1; i <= 8; i++) {
            scene.set(i, quad, (i % 4 - 1.5f) * 2.5f, i <= 4 ? -1.5f : 1.5f, -10, HALF, 0, 0, HALF, 0.5f, 0.5f, 0.5f);
        }
        scene.removeFrom(9);
        Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_WHITE);
        graph.settings().set(ResolutionSettings.DYNAMIC, true).set(ResolutionSettings.HEADROOM, 1.5f);
        SceneView main = main(camera);
        main.port().renderScale(0.5f);
        main.port().add(MeshletBenchmark.forward(main).clearColor(0, 0, 1)).output(Keys.SCENE_COLOR, output);
        frame();
        frame();
        frame();
        assertThat(view.visibleMeshlets()).as("hidden by the wall, in the region's pyramid").isEqualTo(1);
        assertThat(covered()).isEqualTo(SIZE * SIZE);
        scene.set(0, quad, 100, 0, -5, HALF, 0, 0, HALF, 6, 6, 6);
        frame();
        assertThat(view.visibleMeshlets()).as("shown the frame the wall moves").isEqualTo(8);
    }

    @Test
    void hiddenObjectsAreCulledAndAppearTheFrameTheyShow() {
        for (MeshletRenderer.Path path : device.meshShaders()
            ? new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED, MeshletRenderer.Path.MESH_SHADERS}
            : new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED}) {
            renderer.setPath(path);
            // A wall filling the view 5 units ahead, and 8 quads behind it, 10 units ahead.
            scene.set(0, quad, 0, 0, -5, HALF, 0, 0, HALF, 6, 6, 6);
            for (int i = 1; i <= 8; i++) {
                scene.set(i, quad, (i % 4 - 1.5f) * 2.5f, i <= 4 ? -1.5f : 1.5f, -10, HALF, 0, 0, HALF,
                    0.5f, 0.5f, 0.5f);
            }
            scene.removeFrom(9);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
            view.set(camera, 1, MeshletRenderer.MODE_WHITE);
            graph.close();
            graph = new FrameGraph(device, 2);
            SceneView main = main(camera);
            main.port().add(MeshletBenchmark.forward(main).clearColor(0, 0, 1)).output(Keys.SCENE_COLOR, output);
            frame(); // nothing known yet: the late phase draws all
            assertThat(view.visibleMeshlets()).as("first frame, %s", path).isEqualTo(9);
            frame(); // all drawn early; the late phase finds the 8 behind the wall hidden
            assertThat(view.visibleMeshlets()).isEqualTo(9);
            frame(); // only the wall now
            assertThat(view.visibleMeshlets()).as("hidden, %s", path).isEqualTo(1);
            assertThat(covered()).isEqualTo(SIZE * SIZE);
            // The wall moves out of view: the quads behind it are drawn the same frame.
            scene.set(0, quad, 100, 0, -5, HALF, 0, 0, HALF, 6, 6, 6);
            frame();
            assertThat(view.visibleMeshlets()).as("shown, %s", path).isEqualTo(8);
            assertThat(covered()).isPositive().isLessThan(SIZE * SIZE / 2);
            frame();
            assertThat(view.visibleMeshlets()).isEqualTo(8); // drawn early now
        }
    }

    @Test
    void viewsCullOccludedInstancesEachByItsOwnHistory() {
        // The wall and the 8 quads behind it, seen by the camera and by a second camera behind the quads looking back:
        // the wall hides the quads from the first, while the second sees them (and the wall's back, culled). Both
        // views cull occlusion in one pass, each from what it saw itself the frame before.
        scene.set(0, quad, 0, 0, -5, HALF, 0, 0, HALF, 6, 6, 6);
        for (int i = 1; i <= 8; i++) {
            scene.set(i, quad, (i % 4 - 1.5f) * 2.5f, i <= 4 ? -1.5f : 1.5f, -10, -HALF, 0, 0, HALF, 0.5f, 0.5f,
                0.5f); // facing -z, the second camera
        }
        view.set(new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1), 1, MeshletRenderer.MODE_WHITE);
        RenderView back = renderer.createView(ViewOptions.of(256, 1 << 12).withOcclusion(true));
        back.set(new Camera().position(0, 0, -20).set(0, 0, 1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1), 1,
            MeshletRenderer.MODE_WHITE);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        Key<Image> first = Key.image("test.first");
        Key<Image> second = Key.image("test.second");
        graph.add(new DepthCopy(first, new SceneDraw(geometry, view, null, SurfaceOutput.depth(), VK_FORMAT_D32_SFLOAT,
                SIZE, SIZE, true)))
            .add(new DepthCopy(second, new SceneDraw(geometry, back, null, SurfaceOutput.depth(),
                VK_FORMAT_D32_SFLOAT, SIZE, SIZE, true)))
            .add(new Halves(EDGE, first, second)).output(EDGE, output);
        for (int frame = 0; frame < 3; frame++) {
            frame();
        }
        assertThat(graph.passes()).containsOnlyOnce("meshlet culling");
        // Each drew what it sees early, from its own history, and found nothing new late.
        assertThat(early(view)).as("the camera, early").isEqualTo(1);
        assertThat(view.visibleMeshlets()).as("the camera").isEqualTo(1);
        assertThat(early(back)).as("the second camera, early").isEqualTo(8);
        assertThat(back.visibleMeshlets()).as("the second camera").isEqualTo(8);
        // The wall moves away: the camera finds the quads late the same frame, the second camera sees no change.
        scene.set(0, quad, 100, 0, -5, HALF, 0, 0, HALF, 6, 6, 6);
        scene.set(1, quad, -1.25f, -1.5f, -10, HALF, 0, 0, HALF, 0.5f, 0.5f, 0.5f); // turned to the camera
        frame();
        assertThat(view.visibleMeshlets()).as("the camera, the wall gone").isEqualTo(1);
        assertThat(early(back)).as("the second camera, early").isEqualTo(7);
    }

    @Test
    void eachViewShadesTheSceneThroughItsOwnCameraAtItsOwnSize() {
        // Two graph views, each shading the scene: the camera facing the quad, and a half-size view looking away. Each
        // shades its own scene color into its own target; the culling of both is one pass.
        scene.set(0, quad, 0, 0, -5, HALF, 0, 0, HALF, 1, 1, 1);
        scene.removeFrom(1);
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (GpuImage away = new GpuImage(device, SIZE / 2, SIZE / 2, VK_FORMAT_R8G8B8A8_UNORM, usage,
            VK_IMAGE_ASPECT_COLOR_BIT); Lights lights = new Lights(device, 4, 1)) {
            ExternalImage awayTarget = new ExternalImage("away", SIZE / 2, SIZE / 2, Format.R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(away.handle(), away.view());
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            SceneView main = main(new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1))
                .set(1, MeshletRenderer.MODE_WHITE);
            ViewPort awayPort = graph.addView("away").size(SIZE / 2, SIZE / 2);
            SceneView turned = geometry.createView(awayPort,
                new Camera().set(0, 0, 1, 0, 1, 0, Math.PI / 3, SIZE / 2, SIZE / 2, 0.1), ViewOptions.of(256, 1 << 12))
                .set(1, MeshletRenderer.MODE_WHITE);
            main.port().add(new ClusteredForwardShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT).occlusion(false).clearColor(0, 0, 1)).output(Keys.SCENE_COLOR, output);
            awayPort.add(new ClusteredForwardShading(geometry, turned, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 1)).output(Keys.SCENE_COLOR, awayTarget);
            frame();
            assertThat(red(SIZE / 2, SIZE / 2)).as("the camera sees the quad").isEqualTo(1);
            assertThat(main.view().visibleMeshlets()).isEqualTo(1);
            assertThat(turned.view().visibleMeshlets()).as("the view looking away").isZero();
            assertThat(graph.passes()).containsOnlyOnce("meshlet culling");
            // A view turned off is left out of the shared culling: turned to the quad, it finds nothing until on.
            turned.camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE / 2, SIZE / 2, 0.1);
            turned.set(1, MeshletRenderer.MODE_WHITE);
            awayPort.enabled(false);
            frame();
            assertThat(turned.view().visibleMeshlets()).as("off: not culled").isZero();
            assertThat(main.view().visibleMeshlets()).as("the camera, culled").isEqualTo(1);
            awayPort.enabled(true);
            frame();
            assertThat(turned.view().visibleMeshlets()).as("on again").isEqualTo(1);
            // A view resized: the graph rebuilds at the new size.
            int builds = graph.builds();
            awayPort.removeOutput(Keys.SCENE_COLOR).size(SIZE / 4, SIZE / 4);
            frame();
            assertThat(graph.builds()).isEqualTo(builds + 1);
            assertThat(red(SIZE / 2, SIZE / 2)).as("the camera, unchanged").isEqualTo(1);
            // A technique of one view added elsewhere says so when the graph builds.
            graph.add(new ClusteredLights(lights, main));
            assertThatIllegalStateException().isThrownBy(this::frame)
                .withMessageContaining("ClusteredLights of view main was added to the frame");
        }
    }

    @Test
    void sceneViewsGiveEveryViewItsTechniquesAndCloseTheirRenderViews() {
        // Views made through SceneViews: each gets the shading every view gets, and its render view goes with it.
        scene.set(0, quad, 0, 0, -5, HALF, 0, 0, HALF, 1, 1, 1);
        scene.removeFrom(1);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        SceneViews views = new SceneViews(graph, geometry)
            .addPerView(v -> new ClusteredForwardShading(geometry, v, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                .occlusion(false).clearColor(0, 0, 1));
        Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        SceneView main = views.add("main", camera, ViewOptions.of(256, 1 << 12)).set(1, MeshletRenderer.MODE_WHITE);
        main.port().size(SIZE, SIZE).output(Keys.SCENE_COLOR, output);
        SceneView side = views.add("side", camera, ViewOptions.of(256, 1 << 12)).set(1, MeshletRenderer.MODE_WHITE);
        side.port().size(SIZE / 2, SIZE / 2);
        frame();
        assertThat(red(SIZE / 2, SIZE / 2)).as("the main view's shading").isEqualTo(1);
        assertThat(views.views()).containsExactly(main, side);
        assertThat(renderer.views()).contains(main.view(), side.view());
        // A view removed: its port leaves the graph, and its render view closes.
        views.remove(side);
        assertThat(graph.views()).containsExactly(main.port());
        assertThat(renderer.views()).contains(main.view()).doesNotContain(side.view());
        frame();
        assertThat(red(SIZE / 2, SIZE / 2)).isEqualTo(1);
        // The graph closing closes the render views its ports own.
        graph.close();
        assertThat(renderer.views()).doesNotContain(main.view());
        graph = new FrameGraph(device, 2);
    }

    /** The meshlets {@code view} drew in the early phase of the last frame. */
    private static int early(RenderView view) {
        return view.drawCount().memory().get(ValueLayout.JAVA_INT, 0);
    }

    @Test
    void blursSmoothTheEdgeAndAreComputedOnceForEveryone() {
        Key<Image> soft = Key.image("test.soft");
        Key<Image> alsoSoft = Key.image("test.alsoSoft");
        Key<Image> both = Key.image("test.both");
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
            public List<Key<?>> provides() {
                return List.of(soft);
            }

            @Override
            public void build(GraphBuilder g) {
                g.provide(soft, g.request(new Blur(g.require(EDGE), 2, Ratio.of(1, 2))));
            }
        }).output(soft, output);
        frame();
        assertThat(graph.passes()).containsExactly("upload edge", "blur 2 of edge across", "blur 2 of edge down",
            "output target");
        assertThat(red(2, 2)).isEqualTo(1);
        assertThat(red(SIZE - 3, 2)).isZero();
        org.assertj.core.api.Assertions.assertThatNullPointerException().isThrownBy(() -> new Blur(null, 1, Ratio.ONE));
    }

    /** The target's pixels as last read back. */
    private int[] image() {
        int[] image = new int[SIZE * SIZE];
        for (int i = 0; i < image.length; i++) {
            image[i] = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i);
        }
        return image;
    }

    /** How many pixels of the target differ from {@code expected} by more than 8 in a channel. */
    private int differing(int[] expected) {
        int far = 0;
        for (int i = 0; i < expected.length; i++) {
            int p = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i);
            int worst = 0;
            for (int shift = 0; shift < 24; shift += 8) {
                worst = Math.max(worst, Math.abs(((p >> shift) & 0xff) - ((expected[i] >> shift) & 0xff)));
            }
            far += worst > 8 ? 1 : 0;
        }
        return far;
    }

    /** A bumpy 7 x 7 grid, 0.3 a cell, as discrete LODs: each level one meshlet. */
    static MeshData bumpyLods() {
        int n = 7;
        int count = (n + 1) * (n + 1);
        float[] positions = new float[count * 3];
        for (int z = 0; z <= n; z++) {
            for (int x = 0; x <= n; x++) {
                int v = z * (n + 1) + x;
                positions[3 * v] = (x - n / 2f) * 0.3f;
                positions[3 * v + 1] = (float) (0.25 * Math.sin(x * 1.3) * Math.cos(z * 0.9));
                positions[3 * v + 2] = (z - n / 2f) * 0.3f;
            }
        }
        int[] indices = new int[n * n * 6];
        int at = 0;
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int a = z * (n + 1) + x;
                int[] cell = {a, a + n + 1, a + 1, a + 1, a + n + 1, a + n + 2};
                System.arraycopy(cell, 0, indices, at, 6);
                at += 6;
            }
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), count).positions(positions)
            .attribute(VertexAttribute.NORMAL, MeshletBenchmark.normals(positions, indices)).submesh(indices)
            .buildDiscrete(6, 0.5);
    }

    /**
     * A quad facing the camera, a quarter of the view at 2 m, its first UV set across it (0 to 1) and its second at
     * (0.75, 0.5) throughout.
     */
    private static MeshData twoUvQuad() {
        float[] positions = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0};
        float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        float[] uv0 = {0, 1, 1, 1, 1, 0, 0, 0};
        float[] uv1 = {0.75f, 0.5f, 0.75f, 0.5f, 0.75f, 0.5f, 0.75f, 0.5f};
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.UV0, VertexAttribute.UV1), 4)
            .positions(positions).attribute(VertexAttribute.NORMAL, normals).attribute(VertexAttribute.UV0, uv0)
            .attribute(VertexAttribute.UV1, uv1).submesh(new int[] {0, 1, 2, 0, 2, 3}).buildFlat();
    }

    /** How many pixels are mostly red, and how many mostly green. */
    private int[] redAndGreen() {
        int[] counts = new int[2];
        for (int pixel : image()) {
            int r = pixel & 0xff;
            int g = (pixel >> 8) & 0xff;
            counts[0] += r > 2 * g + 20 ? 1 : 0;
            counts[1] += g > 2 * r + 20 ? 1 : 0;
        }
        return counts;
    }

    @Test
    void texturesFollowTheirTransformAndUvSet() {
        MeshData mesh = twoUvQuad();
        int quadMesh = pool.add(mesh);
        // Two texels, nearest: red on the left half of UV space, green on the right.
        java.lang.foreign.MemorySegment texels = java.lang.foreign.Arena.ofAuto().allocate(8, 4);
        texels.setAtIndex(ValueLayout.JAVA_INT, 0, 0xff0000ff);
        texels.setAtIndex(ValueLayout.JAVA_INT, 1, 0xff00ff00);
        try (Textures textures = new Textures(device); Materials materials = new Materials(device, 8)) {
            int texture = TextureSampler.NEAREST.of(textures.color(2, 1, texels));
            MetallicRoughnessParameters glowing = MR.withBaseColor(new Color(0, 0, 0, 1)).withRoughness(1)
                .withEmissive(new Color(1, 1, 1, 1));
            int plain = materials.add(new Material(glowing.withEmissiveTexture(texture)));
            // Moved by half: the green half's texels where the red were, and the other way round.
            int moved = materials.add(new Material(glowing.withEmissiveTexture(TextureSampler.transform(texture, 1)))
                .feature(TextureTransformParameters.DEFAULTS.withTransform1(new Vec4(0.5, 0, 1, 1))));
            // The second UV set, (0.75, 0.5) throughout: green everywhere.
            int second = materials.add(new Material(glowing.withEmissiveTexture(TextureSampler.secondUv(texture))));
            assertThat(TextureSampler.usesSecondUv(TextureSampler.secondUv(texture))).isTrue();
            assertThat(TextureSampler.transformOf(TextureSampler.transform(texture, 3))).isEqualTo(3);
            assertThat(TextureSampler.from(TextureSampler.secondUv(TextureSampler.transform(texture, 8))))
                .isEqualTo(TextureSampler.NEAREST);
            assertThatIllegalArgumentException().isThrownBy(() -> TextureSampler.transform(texture, 9));
            scene.setMaterials(materials);
            scene.set(0, quadMesh, plain, 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
            scene.removeFrom(1);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            SceneView main = main(camera);
            main.port().add(MeshletBenchmark.forward(main).occlusion(false).clearColor(0, 0, 0))
                .output(Keys.SCENE_COLOR, output);
            List<Technique> shadings = new java.util.ArrayList<>(List.of());
            if (device.primitiveIds()) {
                shadings.add(new VisibilityBufferShading(SceneGeometry.meshlets(renderer), main,
                    VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0));
            }
            for (int pass = 0; pass <= shadings.size(); pass++) {
                if (pass > 0) {
                    main.port().replace(shadings.get(pass - 1));
                }
                scene.set(0, quadMesh, plain, 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
                frame();
                int[] counts = redAndGreen();
                int[] left = halves();
                assertThat(counts[0]).as("pass %d: red pixels", pass).isGreaterThan(SIZE * SIZE / 16);
                assertThat(counts[1]).as("pass %d: green pixels", pass).isGreaterThan(SIZE * SIZE / 16);
                assertThat(left[0]).as("pass %d: red on the left", pass).isGreaterThan(left[1]);
                scene.set(0, quadMesh, moved, 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
                frame();
                left = halves();
                assertThat(left[1]).as("pass %d: moved, green on the left", pass).isGreaterThan(left[0]);
                scene.set(0, quadMesh, second, 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
                frame();
                counts = redAndGreen();
                assertThat(counts[0]).as("pass %d: the second UV set, no red", pass).isZero();
                assertThat(counts[1]).as("pass %d: the second UV set, green", pass).isGreaterThan(SIZE * SIZE / 8);
            }
            scene.setMaterials(null);
        }
    }

    @Test
    void unlitMaterialsShowTheirBaseColorAsItIs() {
        MeshData mesh = twoUvQuad();
        int quadMesh = pool.add(mesh);
        try (Materials materials = new Materials(device, 8)) {
            MetallicRoughnessParameters blue = MR.withBaseColor(new Color(0.2, 0.6, 0.9, 1));
            int lit = materials.add(new Material(blue));
            int unlit = materials.add(new Material(blue).feature(UnlitParameters.DEFAULTS));
            int half = materials.add(new Material(blue).feature(UnlitParameters.DEFAULTS.withAmount(0.5f)));
            scene.setMaterials(materials);
            scene.removeFrom(1);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            SceneView main = main(camera);
            main.port().add(MeshletBenchmark.forward(main).occlusion(false).clearColor(0, 0, 0))
                .output(Keys.SCENE_COLOR, output);
            List<Technique> shadings = new java.util.ArrayList<>();
            if (device.primitiveIds()) {
                shadings.add(new VisibilityBufferShading(SceneGeometry.meshlets(renderer), main,
                    VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0));
            }
            int center = (SIZE / 2) * SIZE + SIZE / 2;
            for (int pass = 0; pass <= shadings.size(); pass++) {
                if (pass > 0) {
                    main.port().replace(shadings.get(pass - 1));
                }
                scene.set(0, quadMesh, unlit, 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
                frame();
                int shown = image()[center];
                assertThat(shown & 0xff).as("pass %d: red", pass).isCloseTo(51, within(2));
                assertThat((shown >> 8) & 0xff).as("pass %d: green", pass).isCloseTo(153, within(2));
                assertThat((shown >> 16) & 0xff).as("pass %d: blue", pass).isCloseTo(230, within(2));
                scene.set(0, quadMesh, lit, 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
                frame();
                int litColor = image()[center];
                assertThat(litColor).as("pass %d: lit differs", pass).isNotEqualTo(shown);
                scene.set(0, quadMesh, half, 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
                frame();
                int blended = image()[center];
                for (int shift = 0; shift < 24; shift += 8) {
                    int expected = (((litColor >> shift) & 0xff) + ((shown >> shift) & 0xff)) / 2;
                    assertThat((blended >> shift) & 0xff).as("pass %d: halfway", pass).isCloseTo(expected,
                        within(3));
                }
            }
            scene.setMaterials(null);
        }
    }

    /** The red of {@code pixel} and its blue. */
    private static int red(int pixel) {
        return pixel & 0xff;
    }

    private static int blue(int pixel) {
        return (pixel >> 16) & 0xff;
    }

    /** Asserts the image's center shows half red, half blue, and its corner the red quad alone. */
    private void assertHalfBlue(String what) {
        int center = (SIZE / 2) * SIZE + SIZE / 2;
        int corner = (SIZE / 2 - SIZE / 3) * SIZE + SIZE / 2 - SIZE / 3;
        int mixed = image()[center];
        assertThat(red(mixed)).as("%s: red behind, half", what).isCloseTo(128, within(6));
        assertThat(blue(mixed)).as("%s: blue in front, half", what).isCloseTo(128, within(6));
        int behind = image()[corner];
        assertThat(red(behind)).as("%s: red alone", what).isGreaterThan(240);
        assertThat(blue(behind)).as("%s: nothing blue there", what).isLessThan(8);
    }

    /** Red, glowing, opaque, and a half-transparent glowing blue: the materials of the blending tests. */
    private static int[] redAndBlendedBlue(Materials materials) {
        MetallicRoughnessParameters dark = MR.withBaseColor(new Color(0, 0, 0, 1)).withRoughness(1);
        int red = materials.add(new Material(dark.withEmissive(new Color(1, 0, 0, 1))));
        int blue = materials.add(new Material(dark.withBaseColor(new Color(0, 0, 0, 0.5))
            .withEmissive(new Color(0, 0, 1, 1))).alphaBlend());
        return new int[] {red, blue};
    }

    @Test
    void blendedMaterialsShowWhatIsBehindThem() {
        MeshData mesh = twoUvQuad();
        int quadMesh = pool.add(mesh);
        try (Materials materials = new Materials(device, 8);
             ClassicLodGeometry classic = new ClassicLodGeometry(device, renderer, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, 16)) {
            int[] ids = redAndBlendedBlue(materials);
            int blue = ids[1];
            assertThat(materials.hasBlended()).isTrue();
            assertThat(materials.material(blue).blended()).isTrue();
            assertThatIllegalArgumentException().as("masked or blended")
                .isThrownBy(() -> new Material(null, MR, List.of(), false, 0.5f, true));
            assertThat(new Material(MR).alphaBlend().alphaMask(0.5f).blended()).as("masked instead").isFalse();
            assertThat(new Material(MR).alphaBlend().opaque().blended()).isFalse();
            scene.setMaterials(materials);
            scene.set(0, quadMesh, ids[0], 0, 0, -3, 0, 0, 0, 1, 2, 2, 2);
            scene.set(1, quadMesh, blue, 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
            scene.removeFrom(2);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            SceneView main = main(camera);
            SceneGeometry meshlets = SceneGeometry.meshlets(renderer);
            main.port().add(MeshletBenchmark.forward(main).occlusion(false).clearColor(0, 0, 0))
                .add(new WeightedBlendedTransparency(meshlets, main)).output(Keys.SCENE_COLOR, output);
            frame();
            assertThat(graph.passes()).containsSubsequence("meshlets", "meshlets blended",
                "scene transparency resolve");
            assertHalfBlue("meshlets, forward");
            // Swapped for the opaque way by its id: the blue covers the red.
            main.port().replace(new OpaqueTransparency(meshlets, main));
            frame();
            assertThat(graph.passes()).doesNotContain("scene transparency resolve");
            int center = (SIZE / 2) * SIZE + SIZE / 2;
            assertThat(red(image()[center])).as("opaque blue").isLessThan(8);
            assertThat(blue(image()[center])).as("opaque blue").isGreaterThan(240);
            // Classic LOD, and the visibility buffer: the same transparency, with their geometry.
            SceneGeometry classics = new SceneGeometry(classic.add(quadMesh, mesh));
            main.port().replace(new ClusteredForwardShading(classics, main, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0)).replace(new WeightedBlendedTransparency(classics, main));
            frame();
            assertThat(graph.passes()).contains("classic LOD blended");
            assertHalfBlue("classic LOD, forward");
            if (device.primitiveIds()) {
                main.port().replace(new VisibilityBufferShading(classics, main, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0));
                frame();
                assertHalfBlue("classic LOD, visibility buffer");
            }
            // No transparency technique: no blended surfaces.
            main.port().remove(Transparency.ID);
            frame();
            assertThat(red(image()[center])).as("blended not drawn").isGreaterThan(240);
            assertThat(blue(image()[center])).isLessThan(8);
            // Two in one view: the build refuses.
            main.port().add(new WeightedBlendedTransparency(classics, main))
                .add(new OpaqueTransparency(classics, main));
            assertThatIllegalStateException().isThrownBy(this::frame);
            main.port().remove(Transparency.ID).add(new WeightedBlendedTransparency(classics, main));
            frame();
            assertHalfBlue("one again");
            // No blended material left: no transparency passes.
            materials.set(blue, new Material(MR.withEmissive(new Color(0, 0, 1, 1))));
            assertThat(materials.hasBlended()).isFalse();
            frame();
            assertThat(graph.passes()).doesNotContain("scene transparency resolve", "classic LOD blended");
            scene.setMaterials(null);
        }
    }

    @Test
    void transmissiveMaterialsShowTheSceneBehindThroughThem() {
        MeshData mesh = twoUvQuad();
        int quadMesh = pool.add(mesh);
        try (Materials materials = new Materials(device, 8);
             ClassicLodGeometry classic = new ClassicLodGeometry(device, renderer, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, 16)) {
            // A red wall behind, clear glass in front: white, smooth, letting everything through.
            int wall = materials.add(new Material(MR.withBaseColor(new Color(0, 0, 0, 1)).withRoughness(1)
                .withEmissive(new Color(1, 0, 0, 1))));
            MetallicRoughnessParameters white = MR.withBaseColor(new Color(1, 1, 1, 1)).withRoughness(0.05f)
                .withMetallic(0);
            TransmissionParameters clear = TransmissionParameters.DEFAULTS.withTransmissionFactor(1);
            int glass = materials.add(new Material(white).feature(clear));
            assertThat(materials.hasTransmissive()).isTrue();
            assertThat(materials.hasBlended()).as("drawn with the blended").isTrue();
            assertThat(materials.material(glass).drawnBlended()).isTrue();
            assertThat(materials.material(glass).blended()).as("not alpha-blended").isFalse();
            scene.setMaterials(materials);
            scene.set(0, quadMesh, wall, 0, 0, -3, 0, 0, 0, 1, 2, 2, 2);
            scene.set(1, quadMesh, glass, 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
            scene.removeFrom(2);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            SceneView main = main(camera);
            SceneGeometry meshlets = SceneGeometry.meshlets(renderer);
            main.port().add(MeshletBenchmark.forward(main).occlusion(false).clearColor(0, 0, 0))
                .add(new WeightedBlendedTransparency(meshlets, main)).output(Keys.SCENE_COLOR, output);
            int center = (SIZE / 2) * SIZE + SIZE / 2;
            frame();
            assertThat(graph.passes()).anyMatch(name -> name.startsWith("scene behind"));
            assertThat(graph.passes()).containsSubsequence("meshlets", "meshlets blended",
                "scene transparency resolve");
            int through = image()[center];
            assertThat(red(through)).as("the red wall through the glass").isGreaterThan(200);
            assertThat(green(through)).as("little reflected").isLessThan(red(through) - 120);
            // Nothing let through: the white glass hides the wall.
            materials.set(glass, new Material(white).feature(clear.withTransmissionFactor(0)));
            frame();
            int none = image()[center];
            assertThat(green(none)).as("lit white").isGreaterThan(100);
            assertThat(red(none)).isCloseTo(green(none), within(12));
            // A volume attenuating red away: thick, tinted cyan.
            materials.set(glass, new Material(white).feature(clear).feature(VolumeParameters.DEFAULTS
                .withThicknessFactor(0.1f).withAttenuationDistance(0.05f)
                .withAttenuationColor(new Color(0.3, 1, 1, 1))));
            frame();
            assertThat(red(image()[center])).as("attenuated: 0.3 squared of the red").isLessThan(red(through) / 4);
            // Refraction: red on the left, green on the right, the glass turned 45 degrees one way and the other. Thin,
            // both show what is straight behind; thick, the ray bends through the volume, to opposite sides.
            int green = materials.add(new Material(MR.withBaseColor(new Color(0, 0, 0, 1)).withRoughness(1)
                .withEmissive(new Color(0, 1, 0, 1))));
            scene.set(0, quadMesh, wall, -1, 0, -3, 0, 0, 0, 1, 1, 2, 1);
            scene.set(2, quadMesh, green, 1, 0, -3, 0, 0, 0, 1, 1, 2, 1);
            int[][] seen = new int[2][2];
            for (int thick = 0; thick < 2; thick++) {
                materials.set(glass, new Material(white).feature(clear).feature(VolumeParameters.DEFAULTS
                    .withThicknessFactor(thick)));
                for (int turn = 0; turn < 2; turn++) {
                    float sin = (turn == 0 ? 1 : -1) * 0.38268343f; // 45 degrees about y
                    scene.set(1, quadMesh, glass, 0, 0, -2, 0, sin, 0, 0.92387953f, 1, 1, 1);
                    frame();
                    seen[thick][turn] = image()[center];
                }
            }
            assertThat(red(seen[0][0]) > green(seen[0][0])).as("thin: straight behind, either way")
                .isEqualTo(red(seen[0][1]) > green(seen[0][1]));
            assertThat(red(seen[1][0]) > green(seen[1][0])).as("thick: bent to opposite sides")
                .isNotEqualTo(red(seen[1][1]) > green(seen[1][1]));
            scene.set(0, quadMesh, wall, 0, 0, -3, 0, 0, 0, 1, 2, 2, 2);
            scene.set(1, quadMesh, glass, 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
            scene.removeFrom(2);
            // Swapped for the opaque way, and classic LOD with the visibility buffer: the same glass.
            materials.set(glass, new Material(white).feature(clear));
            main.port().replace(new OpaqueTransparency(meshlets, main));
            frame();
            assertThat(red(image()[center])).as("opaque transparency").isGreaterThan(200);
            SceneGeometry classics = new SceneGeometry(classic.add(quadMesh, mesh));
            main.port().replace(new ClusteredForwardShading(classics, main, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0)).replace(new WeightedBlendedTransparency(classics, main));
            frame();
            assertThat(red(image()[center])).as("classic LOD").isGreaterThan(200);
            if (device.primitiveIds()) {
                main.port().replace(new VisibilityBufferShading(classics, main, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0));
                frame();
                assertThat(red(image()[center])).as("visibility buffer").isGreaterThan(200);
            }
            // At half the resolution: the render region smaller than the images, the scene behind too.
            graph.settings().set(ResolutionSettings.DYNAMIC, true).set(ResolutionSettings.HEADROOM, 1.5f);
            main.port().renderScale(0.5f);
            frame();
            frame();
            assertThat(main.port().regionWidth()).isLessThan(main.port().width());
            int half = image()[center];
            assertThat(red(half)).as("at half").isGreaterThan(200);
            assertThat(green(half)).isLessThan(red(half) - 120);
            main.port().renderScale(1);
            graph.settings().set(ResolutionSettings.DYNAMIC, false);
            // No transmissive material left: no copy of the scene behind.
            materials.set(glass, new Material(white));
            assertThat(materials.hasTransmissive()).isFalse();
            assertThat(materials.hasBlended()).isFalse();
            frame();
            assertThat(graph.passes()).noneMatch(name -> name.startsWith("scene behind"));
            scene.setMaterials(null);
        }
    }

    /** The green of {@code pixel}. */
    private static int green(int pixel) {
        return (pixel >> 8) & 0xff;
    }

    @Test
    void blendedMeshletsStayAcrossOcclusionPhasesAndRenderScales() {
        MeshData mesh = twoUvQuad();
        int quadMesh = pool.add(mesh);
        try (Materials materials = new Materials(device, 8)) {
            int[] ids = redAndBlendedBlue(materials);
            scene.setMaterials(materials);
            scene.set(0, quadMesh, ids[0], 0, 0, -3, 0, 0, 0, 1, 2, 2, 2);
            scene.set(1, quadMesh, ids[1], 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
            scene.removeFrom(2);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            for (MeshletRenderer.Path path : device.meshShaders()
                ? new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED, MeshletRenderer.Path.MESH_SHADERS}
                : new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED}) {
                renderer.setPath(path);
                graph.close();
                graph = new FrameGraph(device, 2);
                SceneView main = main(camera);
                SceneGeometry meshlets = SceneGeometry.meshlets(renderer);
                // Occlusion on: frame one finds both late, later frames early, from last frame's visibility.
                main.port().add(MeshletBenchmark.forward(main).clearColor(0, 0, 0))
                    .add(new WeightedBlendedTransparency(meshlets, main)).output(Keys.SCENE_COLOR, output);
                for (int f = 1; f <= 3; f++) {
                    frame();
                    assertHalfBlue(path + ", frame " + f);
                }
                // At half the resolution: the render region smaller than the images.
                graph.settings().set(ResolutionSettings.DYNAMIC, true).set(ResolutionSettings.HEADROOM, 1.5f);
                main.port().renderScale(0.5f);
                frame();
                frame();
                assertThat(main.port().regionWidth()).isLessThan(main.port().width());
                assertHalfBlue(path + ", at half");
                main.port().renderScale(1);
                graph.settings().set(ResolutionSettings.DYNAMIC, false);
            }
            renderer.setPath(MeshletRenderer.Path.AUTO);
            // Bright layers (HDR highlights) stay finite: the opaque scene shows where the sum would overflow.
            MetallicRoughnessParameters dark = MR.withBaseColor(new Color(0, 0, 0, 0.9)).withRoughness(1);
            materials.set(ids[1], new Material(dark.withEmissive(new Color(0, 0, 1000, 1))).alphaBlend());
            for (int layer = 2; layer < 5; layer++) {
                scene.set(layer, quadMesh, ids[1], 0, 0, -2 + 0.1 * layer, 0, 0, 0, 1, 1, 1, 1);
            }
            scene.removeFrom(5);
            frame();
            int center = image()[(SIZE / 2) * SIZE + SIZE / 2];
            assertThat(blue(center)).as("bright blue, not NaN black").isGreaterThan(240);
            scene.setMaterials(null);
        }
    }

    @Test
    void classicLodDrawnAfterMeshletsWritesDepth() {
        // A meshlet quad behind, a classic one in front: the classic one wins, drawn over the meshlets' depth.
        MeshData far = twoUvQuad();
        MeshData near = twoUvQuad();
        int farMesh = pool.add(far);
        int nearMesh = pool.add(near);
        try (Materials materials = new Materials(device, 8);
             ClassicLodGeometry classic = new ClassicLodGeometry(device, renderer, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, 16)) {
            MetallicRoughnessParameters dark = MR.withBaseColor(new Color(0, 0, 0, 1)).withRoughness(1);
            int red = materials.add(new Material(dark.withEmissive(new Color(1, 0, 0, 1))));
            int blue = materials.add(new Material(dark.withEmissive(new Color(0, 0, 1, 1))));
            classic.add(nearMesh, near);
            scene.setMaterials(materials);
            scene.set(0, farMesh, red, 0, 0, -3, 0, 0, 0, 1, 2, 2, 2);
            scene.set(1, nearMesh, blue, 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
            scene.removeFrom(2);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            SceneView main = main(camera);
            main.port().add(new ClusteredForwardShading(new SceneGeometry(new MeshletGeometry(renderer), classic),
                main, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT).occlusion(false).clearColor(0, 0, 0))
                .output(Keys.SCENE_COLOR, output);
            frame();
            frame();
            int center = image()[(SIZE / 2) * SIZE + SIZE / 2];
            assertThat(blue(center)).as("the classic quad in front").isGreaterThan(240);
            assertThat(red(center)).isLessThan(8);
            scene.setMaterials(null);
        }
    }

    @Test
    void specularIorAndSheenChangeWhatADielectricReflects() {
        MeshData mesh = twoUvQuad();
        int quadMesh = pool.add(mesh);
        try (Materials materials = new Materials(device, 16)) {
            // A black, smooth dielectric: what shows is what it reflects, its reflectance head-on (f0) times the sky.
            MetallicRoughnessParameters black = MR.withBaseColor(new Color(0, 0, 0, 1)).withRoughness(0.3f)
                .withMetallic(0);
            int plain = materials.add(new Material(black));
            int dense = materials.add(new Material(black).feature(IorParameters.DEFAULTS.withIor(3)));
            int sameAsPlain = materials.add(new Material(black).feature(IorParameters.DEFAULTS));
            int noSpecular = materials.add(new Material(black).feature(SpecularParameters.DEFAULTS
                .withSpecularFactor(0)));
            int redSpecular = materials.add(new Material(black).feature(IorParameters.DEFAULTS.withIor(3))
                .feature(SpecularParameters.DEFAULTS.withSpecularColorFactor(new Color(1, 0, 0, 1))));
            int sheen = materials.add(new Material(black).feature(SheenParameters.DEFAULTS
                .withSheenColorFactor(new Color(0, 1, 0, 1)).withSheenRoughnessFactor(0.5f)));
            scene.setMaterials(materials);
            scene.removeFrom(1);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            SceneView main = main(camera);
            main.port().add(MeshletBenchmark.forward(main).occlusion(false).clearColor(0, 0, 0))
                .output(Keys.SCENE_COLOR, output);
            List<Technique> shadings = new java.util.ArrayList<>();
            if (device.primitiveIds()) {
                shadings.add(new VisibilityBufferShading(SceneGeometry.meshlets(renderer), main,
                    VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0));
            }
            int center = (SIZE / 2) * SIZE + SIZE / 2;
            for (int pass = 0; pass <= shadings.size(); pass++) {
                if (pass > 0) {
                    main.port().replace(shadings.get(pass - 1));
                }
                int[] shown = new int[6];
                int[] list = {plain, dense, sameAsPlain, noSpecular, redSpecular, sheen};
                for (int i = 0; i < list.length; i++) {
                    scene.set(0, quadMesh, list[i], 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
                    frame();
                    shown[i] = image()[center];
                }
                int brightness = (shown[0] & 0xff) + ((shown[0] >> 8) & 0xff) + ((shown[0] >> 16) & 0xff);
                assertThat(brightness).as("pass %d: a plain dielectric reflects some sky", pass).isPositive();
                assertThat(sum(shown[1])).as("pass %d: a denser dielectric reflects more", pass)
                    .isGreaterThan(sum(shown[0]) + 6);
                assertThat(sum(shown[2])).as("pass %d: IOR 1.5, as without", pass).isCloseTo(sum(shown[0]),
                    within(3));
                assertThat(sum(shown[3])).as("pass %d: no specular, less reflected", pass).isLessThan(sum(shown[0]));
                assertThat(shown[4] & 0xff).as("pass %d: red specular: red", pass)
                    .isGreaterThan(((shown[4] >> 16) & 0xff) + 4);
                assertThat((shown[5] >> 8) & 0xff).as("pass %d: green sheen: green", pass)
                    .isGreaterThan(((shown[0] >> 8) & 0xff) + 4);
            }
            scene.setMaterials(null);
        }
    }

    /** The red, green and blue of {@code pixel}, added. */
    private static int sum(int pixel) {
        return (pixel & 0xff) + ((pixel >> 8) & 0xff) + ((pixel >> 16) & 0xff);
    }

    /** Of the image's left half: how many pixels are mostly red, and how many mostly green. */
    private int[] halves() {
        int[] counts = new int[2];
        int[] image = image();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE / 2; x++) {
                int pixel = image[y * SIZE + x];
                int r = pixel & 0xff;
                int g = (pixel >> 8) & 0xff;
                counts[0] += r > 2 * g + 20 ? 1 : 0;
                counts[1] += g > 2 * r + 20 ? 1 : 0;
            }
        }
        return counts;
    }

    @Test
    void classicLodFollowsMaskedAndDoubleSidedMaterialsAsMeshletsDo() {
        // A masked checker quad facing the camera on the left, a double-sided quad facing away on the right: classic
        // LOD draws them through the same raster bins, the same image as meshlets.
        MeshData texturedMesh = MeshletRendererTest.texturedQuad();
        int textured = pool.add(texturedMesh);
        java.lang.foreign.MemorySegment texels = java.lang.foreign.Arena.ofAuto().allocate(4 * 8 * 8, 4);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                texels.setAtIndex(ValueLayout.JAVA_INT, y * 8 + x, (x / 2 + y / 2) % 2 == 0 ? 0xff40c0ff : 0x0040c0ff);
            }
        }
        try (Textures textures = new Textures(device); Materials materials = new Materials(device, 8);
             ClassicLodGeometry classic = new ClassicLodGeometry(device, renderer, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, 16)) {
            Material checkered = new Material(MR.withBaseColorTexture(textures.color(8, 8, texels))).alphaMask(0.5f);
            int masked = materials.addAll(List.of(checkered, checkered));
            Material twoSided = new Material(MR.withBaseColor(new Color(0.9, 0.5, 0.2, 1))).doubleSided(true);
            int doubleSided = materials.addAll(List.of(twoSided, twoSided));
            scene.setMaterials(materials);
            classic.add(textured, texturedMesh);
            scene.set(0, textured, masked, -1.2, 0, -4, 0, 0, 0, 1, 1, 1, 1);
            scene.set(1, textured, doubleSided, 1.2, 0, -4, 0, 1, 0, 0, 1, 1, 1); // facing away
            scene.removeFrom(2);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            SceneView main = main(camera);
            main.port().add(MeshletBenchmark.forward(main).occlusion(false).clearColor(0, 0, 0))
                .output(Keys.SCENE_COLOR, output);
            frame();
            int[] expected = image();
            assertThat(view.visibleMeshlets(1)).as("meshlets: the double-sided quad").isPositive();
            assertThat(view.visibleMeshlets(2)).as("meshlets: the masked one").isPositive();
            int drawn = 0;
            for (int pixel : expected) {
                drawn += (pixel & 0xffffff) != 0 ? 1 : 0;
            }
            assertThat(drawn).as("pixels drawn").isPositive();
            SceneGeometry geometry = new SceneGeometry(classic);
            main.port().replace(new ClusteredForwardShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0));
            frame();
            assertThat(differing(expected)).as("classic forward against meshlets").isLessThan(SIZE * SIZE / 200);
            if (device.primitiveIds()) {
                main.port().replace(new VisibilityBufferShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0));
                frame();
                assertThat(differing(expected)).as("classic visibility buffer against meshlets")
                    .isLessThan(SIZE * SIZE / 50);
            }
            scene.setMaterials(null);
        }
    }

    @Test
    void classicLodDrawsWhatMeshletsDraw() {
        // The lit floor, and a quad standing on it hiding part of it.
        scene.set(0, quad, 0, -1, -4, 0, 0, 0, 1, 4, 4, 4);
        scene.set(1, quad, 0, -0.5f, -2.5f, HALF, 0, 0, HALF, 0.4f, 0.4f, 0.4f);
        scene.removeFrom(2);
        Camera camera = new Camera().set(0, -1, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        try (ClassicLodGeometry classic = new ClassicLodGeometry(device, renderer, VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_D32_SFLOAT, 64)) {
            assertThat(classic.add(quad, quadMesh).renderer()).isSameAs(renderer);
            SceneGeometry geometry = new SceneGeometry(classic);
            SceneView main = main(camera);
            ClusteredForwardShading meshlets = MeshletBenchmark.forward(main).clearColor(0, 0, 0.5f);
            main.port().add(meshlets).output(Keys.SCENE_COLOR, output);
            frame();
            frame();
            int[] expected = image();
            ClusteredForwardShading forward = new ClusteredForwardShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0.5f);
            main.port().replace(forward);
            frame();
            assertThat(graph.passes()).contains("classic LOD, view 0 reset", "classic LOD, view 0 selection", "classic LOD")
                .doesNotContain("meshlet culling");
            assertThat(differing(expected)).as("classic forward against meshlets").isZero();
            if (device.primitiveIds()) {
                main.port().replace(new VisibilityBufferShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0.5f));
                frame();
                assertThat(graph.passes()).contains("classic LOD ids", "visibility shading");
                assertThat(differing(expected)).as("classic visibility buffer against meshlets")
                    .isLessThan(SIZE * SIZE / 100);
                main.port().replace(forward);
            }
            // Instances of meshes not added are not drawn: the floor is all that is left.
            scene.set(1, hill, 0, -0.5f, -2.5f, 0, 0, 0, 1, 1, 1, 1);
            frame();
            scene.set(1, quad, 0, -0.5f, -2.5f, 0, 0, 0, 1, 1, 1, 1);
            scene.removeFrom(1);
            int[] floorOnly = image();
            frame();
            assertThat(differing(floorOnly)).as("the hill is not drawn").isZero();
            assertThatIllegalStateException().as("added twice").isThrownBy(() -> classic.add(quad, quadMesh));
            assertThatIllegalArgumentException().as("a cluster hierarchy")
                .isThrownBy(() -> classic.add(hill, hillMesh));
            assertThatIllegalArgumentException().as("another mesh's data")
                .isThrownBy(() -> classic.add(hill, quadMesh));
            // The pool growing under it: its indices copied into a bigger buffer, its mesh table grown, and a quad added
            // after drawn in the first one's place as that was.
            MeshData wide = MeshletRendererTest.grid(64, 1, false, VertexFormat.POSITIONS_ONLY);
            classic.add(pool.add(wide), wide);
            int later = quad;
            for (int i = 0; i < 4; i++) {
                later = pool.add(quadMesh);
            }
            assertThat(pool.capacity().meshes()).isGreaterThan(4);
            classic.add(later, quadMesh);
            scene.set(1, later, 0, -0.5f, -2.5f, HALF, 0, 0, HALF, 0.4f, 0.4f, 0.4f);
            frame();
            frame();
            assertThat(differing(expected)).as("after the pool grew").isZero();
            // A mesh removed from the pool is forgotten here too: its number, reused, is added anew.
            scene.removeFrom(1);
            pool.remove(later);
            int reused = pool.add(quadMesh);
            assertThat(reused).as("the number reused").isEqualTo(later);
            classic.add(reused, quadMesh); // not "drawn the classic way already"
            classic.close();
            assertThatIllegalStateException().isThrownBy(() -> classic.add(hill, hillMesh));
        }
        GeometryProvider meshlets = SceneGeometry.meshlets(renderer).providers().getFirst();
        SceneView other = new SceneView(graph.addView("other").size(SIZE, SIZE), view, camera);
        assertThatIllegalArgumentException().isThrownBy(() -> new VisibilityBufferShading(
            new SceneGeometry(meshlets, meshlets), other, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT));
        assertThatIllegalArgumentException().isThrownBy(() -> new ClassicLodGeometry(device, renderer,
            VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT, 0));
    }

    @Test
    void classicLodPicksTheLevelsMeshletsPick() {
        MeshData lods = bumpyLods();
        assertThat(lods.levels()).as("levels").hasSizeGreaterThan(2);
        int mesh = pool.add(lods);
        Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_MESHLETS); // a color per meshlet: per level here
        try (ClassicLodGeometry classic = new ClassicLodGeometry(device, renderer, VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_D32_SFLOAT, 64)) {
            classic.add(mesh, lods);
            SceneView main = main(camera);
            ClusteredForwardShading meshlets = MeshletBenchmark.forward(main);
            ClusteredForwardShading classicForward = new ClusteredForwardShading(new SceneGeometry(classic), main,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
            main.port().add(meshlets).output(Keys.SCENE_COLOR, output);
            java.util.Set<Integer> colors = new java.util.HashSet<>();
            for (float distance : new float[] {1.2f, 3, 7, 16, 36}) {
                // Facing the camera, a little off the middle so the middle pixel is not on an edge.
                scene.set(0, mesh, 0.05f, 0.03f, -distance, HALF, 0, 0, HALF, 1, 1, 1);
                scene.removeFrom(1);
                main.port().replace(meshlets);
                frame();
                frame();
                int expected = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, SIZE / 2 * SIZE + SIZE / 2);
                main.port().replace(classicForward);
                frame();
                int drawn = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, SIZE / 2 * SIZE + SIZE / 2);
                assertThat(Integer.toHexString(drawn)).as("at %s", distance)
                    .isEqualTo(Integer.toHexString(expected));
                colors.add(drawn);
            }
            assertThat(colors).as("levels drawn").hasSizeGreaterThan(2);
        }
    }
}
