package com.truesim.engine.graph;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.vulkan.VK10.nvkCmdClearColorImage;
import static org.lwjgl.vulkan.VK10.nvkCmdCopyImage;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
import static org.lwjgl.vulkan.VK10.nvkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK10.nvkCmdCopyBufferToImage;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;
import static org.lwjgl.system.MemoryUtil.memPutLong;

import com.truesim.engine.vulkan.Frames;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.engine.vulkan.Format;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;

/**
 * The frame graph on a real device, validated: techniques resolved by key and built only when needed, culling,
 * shared requests, pooled images, outputs and every misuse it reports. Passes clear and copy images, so the results
 * can be read back.
 */
class FrameGraphTest {

    private static final int SIZE = 16;
    private static final Key<Image> A = Key.image("test.a");
    private static final Key<Image> B = Key.image("test.b");
    private static final Key<Image> UNUSED = Key.image("test.unused");
    private static final Key<Image> OUT = Key.image("test.out");

    private VulkanInstance instance;
    private VulkanDevice device;
    private FrameGraph graph;
    private GpuImage target;
    private ExternalImage output;
    private GpuBuffer pixels;

    @BeforeEach
    void start() {
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        if ("true".equals(System.getenv("TRUESIM_REQUIRE_VALIDATION")) && !instance.validationEnabled()) {
            throw new AssertionError("Validation is required (TRUESIM_REQUIRE_VALIDATION) but the layer is not active");
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        graph = new FrameGraph(device, 2);
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        output = new ExternalImage("target", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
        pixels = GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            graph.close();
            pixels.close();
            target.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    // ---------------------------------------------------------------- techniques of the tests

    /** A key's name without its namespace, for pass names: {@code test.a} as {@code a}. */
    static String label(Key<?> key) {
        return key.name().substring(key.name().indexOf('.') + 1);
    }

    /** Provides {@code key}: an image cleared to {@code rgba}. */
    record Fill(Key<Image> key, int size, float... rgba) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(key);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.create(label(key), ImageDesc.of(Format.R8G8B8A8_UNORM, size, size));
            g.pass("fill " + label(key)).write(image, Access.TRANSFER_DST).record(ctx -> clear(ctx, image, rgba));
            g.provide(key, image);
        }
    }

    /** Provides {@code out}: the left half from {@code left}, the right half from {@code right}. */
    record Halves(Key<Image> out, Key<Image> left, Key<Image> right) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(out);
        }

        @Override
        public void build(GraphBuilder g) {
            Image l = g.require(left);
            Image r = g.require(right);
            Image image = g.create(label(out), ImageDesc.like(l, Ratio.ONE));
            g.pass("halves " + label(out))
                .read(l, Access.TRANSFER_SRC)
                .read(r, Access.TRANSFER_SRC)
                .write(image, Access.TRANSFER_DST)
                .record(ctx -> {
                    copy(ctx, l, image, 0, SIZE / 2);
                    copy(ctx, r, image, SIZE / 2, SIZE / 2);
                });
            g.provide(out, image);
        }
    }

    /** A building block: a copy of an image, by request. */
    record Copy(Image source) implements Request<Image> {
        @Override
        public Image build(GraphBuilder g) {
            Image copy = g.create("copy of " + source.name(), ImageDesc.like(source, Ratio.ONE));
            g.pass("copy " + source.name())
                .read(source, Access.TRANSFER_SRC)
                .write(copy, Access.TRANSFER_DST)
                .record(ctx -> copy(ctx, source, copy, 0, SIZE));
            return copy;
        }
    }

    /** A copy on the async compute queue. */
    record AsyncCopy(Image source) implements Request<Image> {
        @Override
        public Image build(GraphBuilder g) {
            Image copy = g.create("async copy of " + source.name(), ImageDesc.like(source, Ratio.ONE));
            g.pass("async copy " + source.name())
                .read(source, Access.TRANSFER_SRC)
                .write(copy, Access.TRANSFER_DST)
                .async()
                .record(ctx -> copy(ctx, source, copy, 0, SIZE));
            return copy;
        }
    }

    /**
     * Provides {@code out}: the async copy of {@code a}, left, beside {@code b}, right. Built as a, the copy, b, so
     * filling b on the graphics queue does not wait for the copy.
     */
    record Overlapping(Key<Image> out, Key<Image> a, Key<Image> b) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(out);
        }

        @Override
        public void build(GraphBuilder g) {
            Image copied = g.request(new AsyncCopy(g.require(a)));
            Image right = g.require(b);
            Image image = g.create(label(out), ImageDesc.like(copied, Ratio.ONE));
            g.pass("halves " + label(out)).read(copied, Access.TRANSFER_SRC).read(right, Access.TRANSFER_SRC)
                .write(image, Access.TRANSFER_DST).record(ctx -> {
                    copy(ctx, copied, image, 0, SIZE / 2);
                    copy(ctx, right, image, SIZE / 2, SIZE / 2);
                });
            g.provide(out, image);
        }
    }

    /** Provides {@code out}: {@code from} copied {@code times} times over, each copy its own pass. */
    record Chain(Key<Image> out, Key<Image> from, int times) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(out);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.require(from);
            for (int i = 0; i < times; i++) {
                Image next = g.create(label(out) + " " + i, ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
                Image source = image;
                g.pass("chain " + i).read(source, Access.TRANSFER_SRC).write(next, Access.TRANSFER_DST)
                    .record(ctx -> copy(ctx, source, next, 0, SIZE));
                image = next;
            }
            g.provide(out, image);
        }
    }

    /** Provides {@code out}: halves of the shared copy of {@code from} and of {@code other}. */
    record UsesCopy(Key<Image> out, Key<Image> from, Key<Image> other) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(out);
        }

        @Override
        public void build(GraphBuilder g) {
            Image copied = g.request(new Copy(g.require(from)));
            Image second = g.require(other);
            Image image = g.create(label(out), ImageDesc.like(copied, Ratio.ONE));
            g.pass("use " + label(out))
                .read(copied, Access.TRANSFER_SRC)
                .read(second, Access.TRANSFER_SRC)
                .write(image, Access.TRANSFER_DST)
                .record(ctx -> {
                    copy(ctx, copied, image, 0, SIZE / 2);
                    copy(ctx, second, image, SIZE / 2, SIZE / 2);
                });
            g.provide(out, image);
        }
    }

    /** Provides {@code key}: an image copied from a buffer filled with {@code rgba}, maybe on the async queue. */
    record Packed(Key<Image> key, int rgba, boolean async) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(key);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer buffer = g.createBuffer(label(key) + " texels", 4L * SIZE * SIZE);
            GraphBuilder.PassBuilder fill = g.pass("fill " + label(key) + " texels").write(buffer, BufferAccess.TRANSFER_DST);
            (async ? fill.async() : fill).record(ctx -> ctx.fill(buffer, rgba));
            Image image = g.create(label(key), ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("unpack " + label(key)).read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                .record(ctx -> unpack(ctx, buffer, image));
            g.provide(key, image);
        }
    }

    /** A filled buffer, by request. */
    record Filled(int value) implements Request<Buffer> {
        @Override
        public Buffer build(GraphBuilder g) {
            Buffer buffer = g.createBuffer("filled", 64);
            g.pass("fill").write(buffer, BufferAccess.TRANSFER_DST)
                .record(ctx -> ctx.fill(buffer, value));
            return buffer;
        }
    }

    /** Copies the first {@code bytes} of {@code from} to the start of {@code to}; field by field, allocating nothing. */
    static void copy(PassContext ctx, Buffer from, Buffer to, long bytes) {
        try (MemoryStack stack = stackPush()) {
            long region = stack.ncalloc(8, 1, VkBufferCopy.SIZEOF);
            memPutLong(region + VkBufferCopy.SIZE, bytes);
            nvkCmdCopyBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(from), ctx.unsafe().buffer(to), 1, region);
        }
    }

    /** Copies {@code buffer}'s first SIZE x SIZE texels into {@code image}. */
    static void unpack(PassContext ctx, Buffer buffer, Image image) {
        ctx.copy(buffer, 0, image.whole());
    }

    /** A technique built from a lambda, for the misuse tests. */
    record Lambda(List<Key<?>> provides, java.util.function.Consumer<GraphBuilder> builder) implements Technique {
        @Override
        public void build(GraphBuilder g) {
            builder.accept(g);
        }
    }

    /** Clears {@code image} to {@code rgba}. */
    static void clear(PassContext ctx, Image image, float... rgba) {
        ctx.clear(image.whole(), rgba[0], rgba[1], rgba[2], rgba[3]);
    }

    /** Copies columns {@code x} to {@code x + width} of {@code from} into {@code to}. */
    static void copy(PassContext ctx, Image from, Image to, int x, int width) {
        try (MemoryStack stack = stackPush()) {
            long region = stack.ncalloc(4, 1, VkImageCopy.SIZEOF);
            memPutInt(region + VkImageCopy.SRCSUBRESOURCE + VkImageSubresourceLayers.ASPECTMASK,
                VK_IMAGE_ASPECT_COLOR_BIT);
            memPutInt(region + VkImageCopy.SRCSUBRESOURCE + VkImageSubresourceLayers.LAYERCOUNT, 1);
            memPutInt(region + VkImageCopy.DSTSUBRESOURCE + VkImageSubresourceLayers.ASPECTMASK,
                VK_IMAGE_ASPECT_COLOR_BIT);
            memPutInt(region + VkImageCopy.DSTSUBRESOURCE + VkImageSubresourceLayers.LAYERCOUNT, 1);
            memPutInt(region + VkImageCopy.SRCOFFSET, x);
            memPutInt(region + VkImageCopy.DSTOFFSET, x);
            memPutInt(region + VkImageCopy.EXTENT, width);
            memPutInt(region + VkImageCopy.EXTENT + 4, SIZE);
            memPutInt(region + VkImageCopy.EXTENT + 8, 1);
            nvkCmdCopyImage(ctx.unsafe().commands(), ctx.unsafe().image(from), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, ctx.unsafe().image(to),
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, region);
        }
    }

    /** Records a frame and reads the target back: one RGBA value per texel. */
    private int[] frame() {
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
        int[] texels = new int[SIZE * SIZE];
        for (int i = 0; i < texels.length; i++) {
            texels[i] = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i);
        }
        return texels;
    }

    private static final int RED = 0xff0000ff;
    private static final int GREEN = 0xff00ff00;
    private static final int BLUE = 0xffff0000;

    // ---------------------------------------------------------------- tests

    @Test
    void passesRenderIntoColorAndDepthAndCopyBuffers() {
        graph.add(new Lambda(List.of(OUT), g -> {
            Image color = g.create("color", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            Image depth = g.create("depth", ImageDesc.of(Format.D32_SFLOAT, SIZE, SIZE));
            g.pass("clear both").attach(color, Clear.color(0, 0, 1, 1)).attachDepth(depth, Clear.FAR)
                .record(ctx -> { });
            g.pass("keep color").attach(color, Load.LOAD, Store.STORE).record(ctx -> { }); // loads: stays blue
            g.pass("keep depth").attachDepth(depth, Load.LOAD, Store.STORE).sideEffects().record(ctx -> { });
            Buffer a = g.createBuffer("a", 64);
            Buffer b = g.createBuffer("b", 64);
            g.pass("fill a").write(a, BufferAccess.TRANSFER_DST).record(ctx -> ctx.fill(a, 7));
            g.pass("copy a to b").read(a, BufferAccess.TRANSFER_SRC).write(b, BufferAccess.TRANSFER_DST).sideEffects()
                .record(ctx -> {
                    ctx.copy(a, b, 32);
                    ctx.copy(a, 32, b, 32, 32);
                    ctx.dispatch(null, null, 0); // no workgroups: nothing
                });
            g.provide(OUT, color);
        })).output(OUT, output);
        int[] texels = frame();
        assertThat(texels).containsOnly(BLUE);
    }

    @Test
    void passesRenderIntoLayersAndBlitThem() {
        graph.add(new Lambda(List.of(OUT), g -> {
            Image layers = g.create("layers", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE).layers(2));
            g.pass("clear layer 0").attach(layers.layer(0), Load.CLEAR, Store.STORE, Clear.color(1, 0, 0, 1))
                .record(ctx -> { });
            g.pass("clear layer 1").attach(layers.layer(1), Load.CLEAR, Store.STORE, Clear.color(new float[] {0, 1, 0}))
                .record(ctx -> ctx.viewport(0, 0, SIZE, SIZE));
            Image out = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("blit layer 1").read(layers.layer(1), Access.TRANSFER_SRC).write(out, Access.TRANSFER_DST)
                .record(ctx -> ctx.blit(layers.layer(1), out.whole(), false));
            g.provide(OUT, out);
        })).output(OUT, output);
        assertThat(frame()).containsOnly(GREEN);
    }

    @Test
    void imagesCopyToBuffersAndBack() {
        graph.add(new Lambda(List.of(OUT), g -> {
            Image cleared = g.create("cleared", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("clear").write(cleared, Access.TRANSFER_DST).record(ctx -> ctx.clear(cleared.whole(), 0, 0, 1, 1));
            Buffer texels = g.createBuffer("texels", 4L * SIZE * SIZE);
            g.pass("pack").read(cleared, Access.TRANSFER_SRC).write(texels, BufferAccess.TRANSFER_DST)
                .record(ctx -> ctx.copy(cleared.whole(), texels, 0));
            Image out = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("unpack").read(texels, BufferAccess.TRANSFER_SRC).write(out, Access.TRANSFER_DST)
                .record(ctx -> ctx.copy(texels, 0, out.whole()));
            g.provide(OUT, out);
        })).output(OUT, output);
        assertThat(frame()).containsOnly(BLUE);
    }

    @Test
    void bodiesUseOnlyWhatTheirPassDeclared() {
        assumeTrue(instance.validationEnabled(), "the check runs with validation");
        graph.add(new Lambda(List.of(OUT), g -> {
            Buffer other = g.createBuffer("other", 64);
            g.pass("fill other").write(other, BufferAccess.TRANSFER_DST).sideEffects().record(ctx -> ctx.fill(other, 0));
            Image out = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("peek").write(out, Access.TRANSFER_DST).record(ctx -> ctx.fill(other, 1));
            g.provide(OUT, out);
        })).output(OUT, output);
        assertThatIllegalStateException().isThrownBy(this::frame).withMessage("peek uses other (64 bytes) without declaring it");
    }

    @Test
    void techniquesAreResolvedByKeyAndBuiltOnlyWhenNeeded() {
        // Added consumer first: order of adding does not matter.
        graph.add(new Halves(OUT, A, B))
            .add(new Fill(B, SIZE, 0, 1, 0, 1))
            .add(new Fill(UNUSED, SIZE, 0, 0, 1, 1))
            .add(new Fill(A, SIZE, 1, 0, 0, 1))
            .output(OUT, output);
        int[] texels = frame();
        assertThat(graph.passes()).containsExactly("fill a", "fill b", "halves out");
        assertThat(texels[0]).isEqualTo(RED);
        assertThat(texels[SIZE - 1]).isEqualTo(GREEN);
        // The output fits its target, so it was drawn straight into it: only the two fills are pooled.
        assertThat(graph.pooledImages()).isEqualTo(2);
        // Recording again does not rebuild.
        frame();
        assertThat(graph.builds()).isEqualTo(1);
    }

    @Test
    void equalRequestsShareOneResult() {
        Key<Image> other = Key.image("test.other");
        graph.add(new Fill(A, SIZE, 1, 0, 0, 1))
            .add(new Fill(B, SIZE, 0, 0, 1, 1))
            .add(new UsesCopy(other, A, B))
            .add(new UsesCopy(Key.image("test.second"), A, B)) // requests the same copy, but nothing needs it
            .add(new Halves(OUT, other, A))
            .output(OUT, output);
        int[] texels = frame();
        assertThat(graph.passes()).containsExactly("fill a", "copy a", "fill b", "use other", "halves out");
        assertThat(texels[0]).isEqualTo(RED);
        assertThat(texels[SIZE - 1]).isEqualTo(RED);

        // Two consumers of one copy: the copy runs once.
        Key<Image> both = Key.image("test.both");
        Key<Image> second = Key.image("test.second2");
        graph.close();
        graph = new FrameGraph(device, 2)
            .add(new Fill(A, SIZE, 0, 0, 1, 1))
            .add(new Fill(B, SIZE, 0, 1, 0, 1))
            .add(new UsesCopy(other, A, B))
            .add(new UsesCopy(second, A, A))
            .add(new Halves(both, other, second))
            .output(both, output);
        texels = frame();
        assertThat(graph.passes()).containsExactly("fill a", "copy a", "fill b", "use other", "use second2",
            "halves both");
        assertThat(texels[0]).isEqualTo(BLUE);
        assertThat(texels[SIZE - 1]).isEqualTo(BLUE);
    }

    @Test
    void imagesWhoseLifetimesDoNotOverlapShareMemory() {
        // fill a -> copy -> copy -> copy: the first and third images never live at once.
        Key<Image> chained = Key.image("test.chained");
        graph.add(new Fill(A, SIZE, 0, 1, 0, 1))
            .add(new Lambda(List.of(chained), g -> {
                Image one = g.request(new Copy(g.require(A)));
                Image two = g.request(new Copy(one));
                Image three = g.request(new Copy(two));
                g.provide(chained, three);
            }))
            .output(chained, output);
        int[] texels = frame();
        assertThat(texels[SIZE * SIZE - 1]).isEqualTo(GREEN);
        assertThat(graph.passes()).hasSize(4);
        assertThat(graph.pooledImages()).isEqualTo(2); // a and the second copy share; the third is the target
    }

    @Test
    void buffersCarryDataBetweenPasses() {
        graph.add(new Packed(OUT, RED, false)).output(OUT, output);
        int[] texels = frame();
        assertThat(texels).containsOnly(RED);
        assertThat(graph.passes()).containsExactly("fill out texels", "unpack out");
        assertThat(graph.pooledBuffers()).isEqualTo(1);
        assertThat(frame()).containsOnly(RED); // again: the frame before's reads are waited for
    }

    @Test
    void importedBuffersKeepTheirContentsAndAreOrdered() {
        int usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
            | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        try (GpuBuffer first = GpuBuffer.deviceLocal(device, 4L * SIZE * SIZE, usage);
             GpuBuffer second = GpuBuffer.deviceLocal(device, 8L * SIZE * SIZE, usage)) {
            ExternalBuffer state = new ExternalBuffer("state", first);
            assertThat(state.name()).isEqualTo("state");
            assertThat(state.size()).isEqualTo(4L * SIZE * SIZE);
            device.submitAndWait(device.graphics(), commands -> {
                vkCmdFillBuffer(commands, first.handle(), 0, VK_WHOLE_SIZE, RED);
                vkCmdFillBuffer(commands, second.handle(), 0, VK_WHOLE_SIZE, GREEN);
            });
            int[] fill = {BLUE};
            // Read, then written: nothing in the frame reads the write, which is kept, as the buffer outlives the frame.
            graph.add(new Lambda(List.of(OUT), g -> {
                Buffer buffer = g.importBuffer(state);
                assertThat(g.importBuffer(state)).as("the same each time").isSameAs(buffer);
                Image image = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
                g.pass("read state").read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                    .record(ctx -> unpack(ctx, buffer, image));
                g.pass("write state").write(buffer, BufferAccess.TRANSFER_DST).record(ctx -> {
                    assertThat(ctx.address(buffer)).isEqualTo(state.address);
                    ctx.fill(buffer, fill[0]);
                });
                g.provide(OUT, image);
            })).output(OUT, output);
            assertThat(frame()[0]).as("what came before the frame").isEqualTo(RED);
            assertThat(graph.passes()).containsExactly("read state", "write state");
            assertThat(graph.pooledBuffers()).as("not pooled").isZero();
            assertThat(frame()[0]).as("what the frame before wrote").isEqualTo(BLUE);
            // Another buffer, of another size: the graph rebuilds and uses it.
            state.set(second);
            fill[0] = GREEN;
            assertThat(frame()[SIZE * SIZE - 1]).isEqualTo(GREEN);
            // Only the reader now: what the frames before wrote is still there.
            graph.close();
            graph = new FrameGraph(device, 2).add(new Lambda(List.of(OUT), g -> {
                Buffer buffer = g.importBuffer(state);
                Image image = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
                g.pass("read state").read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                    .record(ctx -> unpack(ctx, buffer, image));
                g.provide(OUT, image);
            })).output(OUT, output);
            assertThat(frame()[0]).isEqualTo(GREEN);
            // Async passes stay off it: it is the graphics queue's.
            graph.close();
            graph = new FrameGraph(device, 2).add(new Lambda(List.of(OUT), g -> {
                Buffer buffer = g.importBuffer(state);
                g.pass("async write").write(buffer, BufferAccess.TRANSFER_DST).async().record(ctx -> { });
                Image image = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
                g.pass("read state").read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                    .record(ctx -> unpack(ctx, buffer, image));
                g.provide(OUT, image);
            })).output(OUT, output);
            boolean twoFamilies = device.graphics().family() != device.compute().family();
            if (twoFamilies) { // one queue family owns it: the graph transfers no ownership
                assertThatIllegalStateException().isThrownBy(() -> device.submitAndWait(device.graphics(),
                    graph::record)).withMessageContaining("external buffer").withMessageContaining("concurrent");
            }
        }
        // Shared by both families: async passes use it, ordered with the graphics queue's.
        try (GpuBuffer shared = GpuBuffer.deviceLocal(device, 4L * SIZE * SIZE, usage, device.graphics().family(),
            device.compute().family())) {
            ExternalBuffer state = new ExternalBuffer("shared state", shared);
            graph.close();
            graph = new FrameGraph(device, 2).add(new Lambda(List.of(OUT), g -> {
                Buffer buffer = g.importBuffer(state);
                g.pass("async write").write(buffer, BufferAccess.TRANSFER_DST).async()
                    .record(ctx -> ctx.fill(buffer, GREEN));
                Image image = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
                g.pass("read state").read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                    .record(ctx -> unpack(ctx, buffer, image));
                g.provide(OUT, image);
            })).output(OUT, output);
            assertThat(frame()[0]).isEqualTo(GREEN);
            assertThat(frame()[SIZE * SIZE - 1]).isEqualTo(GREEN);
        }
    }

    /** Copies {@code key}'s image into a new one, as the view's {@code out}: a view's technique reading a key. */
    record Copies(Key<Image> key, Key<Image> out) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(out);
        }

        @Override
        public void build(GraphBuilder g) {
            Image from = g.require(key);
            Image to = g.create("copy of " + label(key), ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("copy " + label(key) + " in " + g.view().name()).read(from, Access.TRANSFER_SRC)
                .write(to, Access.TRANSFER_DST).record(ctx -> copy(ctx, from, to, 0, SIZE));
            g.provide(out, to);
        }
    }

    /** The first texel of {@code image}, a target in transfer-source layout after a frame. */
    private int firstTexel(GpuImage image) {
        device.submitAndWait(device.graphics(), commands -> {
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(SIZE, SIZE, 1);
                vkCmdCopyImageToBuffer(commands, image.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        return pixels.memory().getAtIndex(ValueLayout.JAVA_INT, 0);
    }

    @Test
    void viewsHaveTheirOwnKeysAndOutputsAndShareTheFrames() {
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (GpuImage second = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage,
            VK_IMAGE_ASPECT_COLOR_BIT)) {
            ExternalImage mirror = new ExternalImage("mirror", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(second.handle(), second.view());
            int[] builds = new int[1];
            Fill blue = new Fill(A, SIZE, 0, 0, 1, 1);
            // The frame's technique, built once for both views: work no camera changes.
            graph.add(new Technique() {
                @Override
                public List<Key<?>> provides() {
                    return blue.provides();
                }

                @Override
                public void build(GraphBuilder g) {
                    assertThat(g.view()).as("a frame technique").isNull();
                    builds[0]++;
                    blue.build(g);
                }
            });
            // Each view provides the same key its own way: one from the frame's, one its own color.
            ViewPort main = graph.addView("main").add(new Copies(A, OUT)).output(OUT, output);
            ViewPort side = graph.addView("side").add(new Fill(OUT, SIZE, 1, 0, 0, 1)).output(OUT, mirror);
            assertThat(graph.views()).containsExactly(main, side);
            assertThat(main).hasToString("view main");
            assertThatIllegalArgumentException().isThrownBy(() -> graph.addView("main"))
                .withMessageContaining("named main already");
            assertThat(frame()[0]).as("main: the frame's blue").isEqualTo(BLUE);
            assertThat(firstTexel(second)).as("side: its own red").isEqualTo(RED);
            assertThat(builds[0]).as("the frame's technique, once").isEqualTo(1);
            assertThat(graph.passes()).contains("copy a in main");
            // A view removed: its techniques and output go.
            graph.removeView(side);
            frame();
            assertThat(graph.passes()).noneMatch(p -> p.contains("fill out"));
        }
    }

    @Test
    void theFrameReadsTheViewsResults() {
        // A frame technique reading one view's result, and counting every view's: e.g. one pass for all the views.
        ViewPort[] views = new ViewPort[2];
        views[0] = graph.addView("left").add(new Fill(OUT, SIZE, 0, 1, 0, 1));
        views[1] = graph.addView("right").add(new Fill(OUT, SIZE, 1, 0, 0, 1));
        graph.add(new Lambda(List.of(B), g -> {
            assertThat(g.requireAll(OUT)).hasSize(2);
            assertThat(g.requireAll(A)).as("no view provides it").isEmpty();
            Image right = g.require(views[1], OUT);
            Image b = g.create("b", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("take right").read(right, Access.TRANSFER_SRC).write(b, Access.TRANSFER_DST)
                .record(ctx -> copy(ctx, right, b, 0, SIZE));
            g.provide(B, b);
        })).output(B, output);
        assertThat(frame()[0]).isEqualTo(RED);
    }

    @Test
    void viewsMayHoldTheSameTechniqueAndHistoryNames() {
        // The same kind of technique, with one id and one history name, in two views: each keeps its own.
        com.truesim.common.graph.Id<Technique> id = com.truesim.common.graph.Id.of("test.remember", Technique.class);
        java.util.function.Supplier<Technique> remembering = () -> {
            Remember inner = new Remember();
            return new Technique() {
                @Override
                public List<Key<?>> provides() {
                    return inner.provides();
                }

                @Override
                public void build(GraphBuilder g) {
                    inner.build(g);
                }

                @Override
                public com.truesim.common.graph.Id<Technique> id() {
                    return id;
                }
            };
        };
        graph.addView("left").add(remembering.get()).output(OUT, output);
        graph.addView("right").add(remembering.get());
        frame();
        frame();
        assertThat(graph.passes()).contains("recall", "remember");
    }

    @Test
    void aViewsSizeIsReadWhenItsTechniquesBuildAndRebuildsWhenItChanges() {
        int[] sizes = new int[1];
        ViewPort main = graph.addView("main");
        assertThatIllegalStateException().isThrownBy(main::width).withMessageContaining("view main has no size");
        assertThatIllegalArgumentException().isThrownBy(() -> main.size(0, SIZE));
        main.size(SIZE, SIZE).add(new Technique() {
            @Override
            public List<Key<?>> provides() {
                return List.of(OUT);
            }

            @Override
            public void build(GraphBuilder g) {
                sizes[0] = g.view().width();
                new Fill(OUT, g.view().height(), 1, 0, 0, 1).build(g);
            }
        }).output(OUT, output);
        assertThat(frame()[0]).isEqualTo(RED);
        assertThat(sizes[0]).isEqualTo(SIZE);
        int builds = graph.builds();
        main.size(SIZE, SIZE);
        frame();
        assertThat(graph.builds()).as("the same size: no rebuild").isEqualTo(builds);
        // Another technique for the same key replaces the first.
        main.replace(new Fill(OUT, SIZE, 0, 0, 1, 1));
        assertThat(frame()[0]).isEqualTo(BLUE);
        assertThat(graph.builds()).isEqualTo(builds + 1);
    }

    @Test
    void perViewTechniquesGoToEveryViewAndViewsCloseWhatTheyOwn() {
        com.truesim.common.graph.Id<Technique> id = com.truesim.common.graph.Id.of("test.fill", Technique.class);
        List<String> made = new java.util.ArrayList<>();
        ViewPort main = graph.addView("main");
        graph.addPerView(view -> {
            made.add(view.name());
            Fill red = new Fill(OUT, SIZE, 1, 0, 0, 1);
            return new Technique() {
                @Override
                public List<Key<?>> provides() {
                    return red.provides();
                }

                @Override
                public void build(GraphBuilder g) {
                    red.build(g);
                }

                @Override
                public com.truesim.common.graph.Id<Technique> id() {
                    return id;
                }
            };
        });
        ViewPort side = graph.addView("side");
        assertThat(made).as("one for the view there, one for the view added").containsExactly("main", "side");
        main.output(OUT, output);
        assertThat(frame()[0]).isEqualTo(RED);
        // A view drops one by id, and takes another.
        main.remove(id).add(new Fill(OUT, SIZE, 0, 0, 1, 1));
        assertThat(frame()[0]).isEqualTo(BLUE);
        // What a view owns closes with it, newest first, and with the graph.
        List<String> closed = new java.util.ArrayList<>();
        side.own(() -> closed.add("side, first"));
        side.own(() -> closed.add("side, second"));
        main.own(() -> closed.add("main"));
        graph.removeView(side);
        assertThat(closed).containsExactly("side, second", "side, first");
        graph.close();
        assertThat(closed).endsWith("main");
        graph = new FrameGraph(device, 2);
    }

    @Test
    void aTechniqueThatWouldBuildDifferentlyRebuildsTheGraph() {
        long[] version = {1};
        Fill red = new Fill(OUT, SIZE, 1, 0, 0, 1);
        graph.add(new Technique() {
            @Override
            public List<Key<?>> provides() {
                return red.provides();
            }

            @Override
            public void build(GraphBuilder g) {
                red.build(g);
            }

            @Override
            public long buildVersion() {
                return version[0];
            }
        }).output(OUT, output);
        frame();
        frame();
        int builds = graph.builds();
        version[0] = 2; // e.g. a material type registered: the technique would build another pass
        frame();
        assertThat(graph.builds()).as("rebuilt once").isEqualTo(builds + 1);
        frame();
        assertThat(graph.builds()).isEqualTo(builds + 1);
    }

    @Test
    void openPassesDeclareUntilTheBuildEnds() {
        // A pass recorded first, its write declared later (as one culling views other techniques add): it runs before
        // the reader, which sees what it wrote.
        graph.add(new Lambda(List.of(OUT), g -> {
            Buffer buffer = g.createBuffer("late declared", 4L * SIZE * SIZE);
            GraphBuilder.PassBuilder fill = g.pass("fill").open();
            fill.record(ctx -> ctx.fill(buffer, BLUE));
            assertThatIllegalStateException().isThrownBy(() -> fill.record(ctx -> { }))
                .withMessageContaining("recorded already");
            fill.write(buffer, BufferAccess.TRANSFER_DST);
            Image image = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("read").read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                .record(ctx -> unpack(ctx, buffer, image));
            g.provide(OUT, image);
        })).output(OUT, output);
        assertThat(frame()[0]).isEqualTo(BLUE);
        assertThat(graph.passes()).containsExactly("fill", "read");
        // Not open: declaring after recording is a mistake.
        rejects(List.of(OUT), g -> {
            Buffer buffer = g.createBuffer("too late", 16);
            GraphBuilder.PassBuilder pass = g.pass("closed");
            pass.record(ctx -> { });
            pass.write(buffer, BufferAccess.TRANSFER_DST);
        }, "is recorded: declare before record(), or open() it");
    }

    @Test
    void importsOfOneBufferAreOneAndAKeyGivesThemAnOwner() {
        int usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
            | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        Key<Buffer> data = Key.buffer("test.data");
        try (GpuBuffer shared = GpuBuffer.deviceLocal(device, 4L * SIZE * SIZE, usage)) {
            ExternalBuffer engines = new ExternalBuffer("engine's", shared);
            ExternalBuffer games = new ExternalBuffer("game's", shared); // its own wrapper of the same buffer
            device.submitAndWait(device.graphics(),
                commands -> vkCmdFillBuffer(commands, shared.handle(), 0, VK_WHOLE_SIZE, RED));
            Technique provider = new Lambda(List.of(data), g -> {
                Buffer buffer = g.importBuffer(engines);
                g.pass("write data").write(buffer, BufferAccess.TRANSFER_DST).record(ctx -> ctx.fill(buffer, BLUE));
                g.provide(data, buffer);
            });
            // The game's import is the engine's buffer: its read is ordered after the engine's write.
            graph.add(provider).add(new Lambda(List.of(OUT), g -> {
                Buffer provided = g.require(data);
                Buffer buffer = g.importBuffer(games);
                assertThat(buffer).isSameAs(provided);
                Image image = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
                g.pass("read data").read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                    .record(ctx -> unpack(ctx, buffer, image));
                g.provide(OUT, image);
            })).output(OUT, output);
            assertThat(frame()[0]).isEqualTo(BLUE);
            // Provided under a key, it is a result: only its provider and the key's modifiers write it.
            fails(OUT, "results are written only by whoever makes them", provider, new Lambda(List.of(OUT), g -> {
                g.require(data);
                Buffer buffer = g.importBuffer(games);
                g.pass("game's write").write(buffer, BufferAccess.TRANSFER_DST).record(ctx -> { });
                Image image = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
                g.pass("read data").read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                    .record(ctx -> unpack(ctx, buffer, image));
                g.provide(OUT, image);
            }));
            graph.close();
            graph = new FrameGraph(device, 2).add(provider)
                .add(new Mod(List.of(), List.of(data), g -> {
                    Buffer buffer = g.modify(data);
                    g.pass("modify data").write(buffer, BufferAccess.TRANSFER_DST)
                        .record(ctx -> ctx.fill(buffer, GREEN));
                }))
                .add(new Lambda(List.of(OUT), g -> {
                    Buffer buffer = g.require(data);
                    Image image = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
                    g.pass("read data").read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                        .record(ctx -> unpack(ctx, buffer, image));
                    g.provide(OUT, image);
                })).output(OUT, output);
            assertThat(frame()[0]).as("the modifier's write, after the provider's").isEqualTo(GREEN);
        }
    }

    @Test
    void buffersWhoseLifetimesDoNotOverlapShareMemory() {
        long half = 2L * SIZE * SIZE;
        graph.add(new Lambda(List.of(OUT), g -> {
            Buffer a = g.createBuffer("a", 2 * half);
            Buffer b = g.createBuffer("b", 2 * half);
            Buffer c = g.createBuffer("c", 4 * half); // lives after a: shares it, grown to its size
            Buffer unused = g.createBuffer("unused", 16);
            g.pass("fill a").write(a, BufferAccess.TRANSFER_DST)
                .record(ctx -> ctx.fill(a, RED));
            g.pass("fill unused").write(unused, BufferAccess.TRANSFER_DST).record(ctx -> { });
            g.pass("a to b").read(a, BufferAccess.TRANSFER_SRC).write(b, BufferAccess.TRANSFER_DST)
                .record(ctx -> copy(ctx, a, b, 2 * half));
            g.pass("fill c").write(c, BufferAccess.TRANSFER_DST)
                .record(ctx -> ctx.fill(c, GREEN));
            g.pass("b to c").read(b, BufferAccess.TRANSFER_SRC).write(c, BufferAccess.TRANSFER_DST)
                .record(ctx -> copy(ctx, b, c, half));
            Image image = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("unpack").read(c, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                .record(ctx -> unpack(ctx, c, image));
            g.provide(OUT, image);
        })).output(OUT, output);
        int[] texels = frame();
        assertThat(texels[0]).isEqualTo(RED); // the first half from b
        assertThat(texels[SIZE * SIZE - 1]).isEqualTo(GREEN);
        assertThat(graph.passes()).doesNotContain("fill unused");
        assertThat(graph.pooledBuffers()).isEqualTo(2);
        assertThat(frame()[0]).isEqualTo(RED);
        // Buffers left in the pool retire once their frames are over.
        graph.removeOutput(OUT);
        for (int i = 0; i < 3; i++) {
            device.submitAndWait(device.graphics(), graph::record);
        }
        assertThat(graph.pooledBuffers()).isZero();
    }

    @Test
    void buffersPassBetweenQueues() {
        graph.add(new Packed(A, BLUE, true))
            .add(new Fill(B, SIZE, 1, 0, 0, 1))
            .add(new Halves(OUT, A, B))
            .output(OUT, output)
            .asyncCompute(true);
        int[] texels = submitted(4);
        assertThat(texels[0]).isEqualTo(BLUE);
        assertThat(texels[SIZE - 1]).isEqualTo(RED);
        assertThat(graph.batches()).containsExactly(
            "compute 1: fill a texels",
            "graphics 1 (after compute 1): unpack a, fill b",
            "graphics 2 (after acquire): halves out");
        graph.asyncCompute(false);
        assertThat(submitted(2)[0]).isEqualTo(BLUE);
    }

    @Test
    void outputsThatDoNotFitTheirTargetAreBlitted() {
        graph.add(new Fill(A, SIZE / 2, 0, 0, 1, 1)).output(A, output);
        int[] texels = frame();
        assertThat(graph.passes()).containsExactly("fill a", "output target");
        assertThat(texels[SIZE * SIZE - 1]).isEqualTo(BLUE);
    }

    @Test
    void theGraphRebuildsWhenItChanges() {
        Fill red = new Fill(A, SIZE, 1, 0, 0, 1);
        graph.add(red).output(A, output);
        assertThat(frame()[0]).isEqualTo(RED);
        graph.remove(red).add(new Fill(A, SIZE, 0, 1, 0, 1));
        assertThat(frame()[0]).isEqualTo(GREEN);
        output.resize(SIZE, SIZE); // the same size: nothing to do
        frame();
        assertThat(graph.builds()).isEqualTo(2);
        output.resize(SIZE / 2, SIZE / 2); // now the fill no longer fits: blitted
        device.submitAndWait(device.graphics(), graph::record);
        assertThat(graph.builds()).isEqualTo(3);
        assertThat(graph.passes()).containsExactly("fill a", "output target");
        graph.invalidate();
        graph.removeOutput(A);
        device.submitAndWait(device.graphics(), graph::record);
        assertThat(graph.passes()).isEmpty();
        // Images left in the pool retire once their frames are over.
        for (int i = 0; i < 3; i++) {
            device.submitAndWait(device.graphics(), graph::record);
        }
        assertThat(graph.pooledImages()).isZero();
        assertThat(device.bindless().slotsInUse()).isZero();
    }

    @Test
    void techniquesAndImplementationsSwap() {
        Key<Image> copied = Key.image("test.copied");
        graph.add(new Fill(A, SIZE, 1, 0, 0, 1))
            .add(new UsesCopy(copied, A, A))
            .output(copied, output);
        assertThat(frame()[0]).isEqualTo(RED);
        // Another technique providing a: everything requiring a now gets it.
        graph.replace(new Fill(A, SIZE, 0, 1, 0, 1));
        assertThat(frame()[0]).isEqualTo(GREEN);
        assertThat(graph.passes()).containsExactly("fill a", "copy a", "use copied");
        // Another way to compute copies: here, a "copy" that is plain blue.
        graph.implement(Copy.class, (request, g) -> {
            Image blue = g.create("blue", ImageDesc.like(request.source(), Ratio.ONE));
            g.pass("blue").write(blue, Access.TRANSFER_DST).record(ctx -> clear(ctx, blue, 0, 0, 1, 1));
            return blue;
        });
        assertThat(frame()[0]).isEqualTo(BLUE);
        assertThat(graph.passes()).containsExactly("fill a", "blue", "use copied");
        graph.implement(Copy.class, null);
        assertThat(frame()[0]).isEqualTo(GREEN);
        assertThat(graph.builds()).isEqualTo(4);
    }

    /** Submits {@code frames} frames the graph's way, then reads the target back. */
    private int[] submitted(int frames) {
        for (int i = 0; i < frames; i++) {
            graph.beginFrame();
            graph.submit(0, 0);
        }
        device.waitIdle();
        device.submitAndWait(device.graphics(), commands -> {
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(SIZE, SIZE, 1);
                vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        int[] texels = new int[SIZE * SIZE];
        for (int i = 0; i < texels.length; i++) {
            texels[i] = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i);
        }
        return texels;
    }

    @Test
    void closingGoesOnPastACachedObjectThatFails() {
        boolean[] closed = {false};
        graph.add(new Lambda(List.of(OUT), g -> {
            g.cached("fails", d -> (AutoCloseable) () -> {
                throw new java.io.IOException("stuck");
            });
            g.cached("closes", d -> (AutoCloseable) () -> closed[0] = true);
            Image image = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("clear").write(image, Access.TRANSFER_DST).record(ctx -> clear(ctx, image, 1, 0, 0, 1));
            g.provide(OUT, image);
        })).output(OUT, output);
        frame();
        assertThatIllegalStateException().isThrownBy(graph::close).withMessageStartingWith("Could not close")
            .withCauseInstanceOf(java.io.IOException.class);
        assertThat(closed[0]).as("the other cached object").isTrue();
    }

    @Test
    void passesRecordInParallelChunksSubmittedInOrder() {
        Key<Image> copied = Key.image("test.copied");
        graph.add(new Fill(A, SIZE, 1, 0, 0, 1))
            .add(new Fill(B, SIZE, 0, 1, 0, 1))
            .add(new Chain(copied, A, 9))
            .add(new Halves(OUT, copied, B))
            .output(OUT, output);
        try (com.truesim.common.jobs.JobSystem jobs = new com.truesim.common.jobs.JobSystem(4)) {
            graph.parallel(jobs, 1);
            int[] texels = submitted(5); // more frames than in flight: every slot reused
            assertThat(texels[0]).isEqualTo(RED);
            assertThat(texels[SIZE - 1]).isEqualTo(GREEN);
            assertThat(graph.chunks()).isEqualTo(12); // a pass each: fill a, 9 copies, fill b, halves
            graph.parallel(jobs, 5);
            texels = submitted(3);
            assertThat(texels[0]).isEqualTo(RED);
            assertThat(graph.chunks()).isEqualTo(4); // 11 passes in 3 chunks, then the output pass
            graph.parallel(null, 4); // back on the calling thread
            assertThat(submitted(2)[SIZE - 1]).isEqualTo(GREEN);
            assertThat(graph.batches()).containsExactly("graphics 1: fill a, chain 0, chain 1, chain 2, chain 3, "
                + "chain 4, chain 5, chain 6, chain 7, chain 8, fill b", "graphics 2 (after acquire): halves out");
        }
        assertThatIllegalArgumentException().isThrownBy(() -> graph.parallel(null, 0));
    }

    @Test
    void asyncPassesRunBesideGraphicsWorkThatDoesNotNeedThem() {
        graph.add(new Fill(A, SIZE, 1, 0, 0, 1))
            .add(new Fill(B, SIZE, 0, 0, 1, 1))
            .add(new Overlapping(OUT, A, B))
            .output(OUT, output)
            .asyncCompute(true); // on devices without a compute queue too: the same batches, on one queue
        int[] texels = submitted(4);
        assertThat(texels[0]).isEqualTo(RED);
        assertThat(texels[SIZE - 1]).isEqualTo(BLUE);
        // Filling b does not wait for the copy; only the pass using both does.
        assertThat(graph.batches()).containsExactly(
            "graphics 1: fill a",
            "compute 1 (after graphics 1): async copy a",
            "graphics 2: fill b",
            "graphics 3 (after compute 1, acquire): halves out");
        // Binary semaphores: the first frame signals one, the next waits for it as if it were an acquired image.
        try (MemoryStack stack = stackPush()) {
            java.nio.LongBuffer semaphore = stack.mallocLong(1);
            org.lwjgl.vulkan.VK10.vkCreateSemaphore(device.handle(), org.lwjgl.vulkan.VkSemaphoreCreateInfo
                .calloc(stack).sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO), null, semaphore);
            graph.beginFrame();
            graph.submit(0, semaphore.get(0));
            graph.beginFrame();
            graph.submit(semaphore.get(0), 0);
            device.waitIdle();
            org.lwjgl.vulkan.VK10.vkDestroySemaphore(device.handle(), semaphore.get(0), null);
        }
        // Off, async passes run on the graphics queue in their place.
        graph.asyncCompute(false);
        assertThat(submitted(1)[0]).isEqualTo(RED);
        assertThat(graph.batches()).containsExactly("graphics 1: fill a, async copy a, fill b",
            "graphics 2 (after acquire): halves out");
        // Async passes compute, sample, store and copy only, and never touch external images.
        rejects(List.of(OUT), g -> {
            Image image = g.create("x", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("draw").write(image, Access.COLOR_ATTACHMENT).async().record(ctx -> { });
            g.provide(OUT, image);
        }, "async passes compute, sample, store and copy only");
        rejects(List.of(OUT), g -> {
            g.pass("write target").write(g.external(output), Access.TRANSFER_DST).async().sideEffects()
                .record(ctx -> { });
            g.provide(OUT, g.require(A));
        }, "uses the external image");
    }

    @Test
    void graphsSharingSlotsLeaveThemToTheCaller() {
        FrameSlots shared = new FrameSlots(2);
        try (FrameGraph other = new FrameGraph(device, shared)) {
            assertThat(other.frameSlots()).isSameAs(shared);
            other.add(new Fill(A, SIZE, 0, 1, 0, 1)).output(A, output);
            for (int frame = 0; frame < 3; frame++) {
                int slot = other.beginFrame();
                assertThat(slot).isEqualTo(frame % 2);
                assertThat(shared.current()).isEqualTo(-1); // the caller opens it, once every graph has begun
                shared.begin(slot);
                other.submit(0, 0);
                assertThat(shared.current()).isEqualTo(slot); // and closes it, once every graph has submitted
                shared.end();
            }
            device.waitIdle();
        }
        assertThat(shared.frames()).isEqualTo(3);
    }

    @Test
    void recordingOutsideFramesUsesSlotZero() {
        int[] seen = {-2};
        graph.add(new Lambda(List.of(OUT), g -> {
            Image image = g.create("x", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("look").write(image, Access.TRANSFER_DST).record(ctx -> {
                seen[0] = graph.frameSlots().current();
                clear(ctx, image, 0, 0, 0, 1);
            });
            g.provide(OUT, image);
        })).output(OUT, output);
        frame();
        assertThat(seen[0]).isZero(); // record() opened slot 0 for its passes
        assertThat(graph.frameSlots().current()).isEqualTo(-1); // and closed it again
    }

    @Test
    void framesAreBegunThenSubmitted() {
        graph.add(new Fill(A, SIZE, 1, 0, 0, 1)).output(A, output);
        FrameSlots slots = graph.frameSlots();
        assertThat(slots.count()).isEqualTo(2);
        assertThat(slots.current()).isEqualTo(-1);
        assertThatIllegalStateException().isThrownBy(() -> graph.submit(0, 0)).withMessageContaining("beginFrame");
        assertThat(graph.beginFrame()).isZero();
        assertThat(slots.current()).isZero(); // per-frame data writes to this frame's copies now
        assertThatIllegalStateException().isThrownBy(graph::beginFrame).withMessageContaining("submit()");
        graph.submit(0, 0);
        assertThat(slots.current()).isEqualTo(-1); // in flight: no copy is written until the next frame begins
        assertThat(graph.beginFrame()).isEqualTo(1);
        assertThat(slots.current()).isEqualTo(1);
        graph.submit(0, 0);
        assertThat(graph.beginFrame()).isZero(); // two in flight
        graph.submit(0, 0);
        assertThat(slots.last()).isZero();
        // Nothing to do: an empty graphics batch still signals the frame.
        graph.removeOutput(A);
        graph.beginFrame();
        graph.submit(0, 0);
        assertThat(graph.batches()).containsExactly("graphics 1: ");
        // Only async work: compute, then the output's blit on the graphics queue.
        graph.close();
        graph = new FrameGraph(device, 2).asyncCompute(true).add(new Lambda(List.of(OUT), g -> {
            Image image = g.create("small", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE / 2, SIZE / 2));
            g.pass("only compute").write(image, Access.TRANSFER_DST).async()
                .record(ctx -> clear(ctx, image, 1, 1, 1, 1));
            g.provide(OUT, image);
        })).output(OUT, output);
        assertThat(submitted(3)[0]).isEqualTo(0xffffffff);
        assertThat(graph.batches()).containsExactly("compute 1: only compute",
            "graphics 1 (after compute 1, acquire): output target");
    }

    @Test
    void readbacksArriveOnceTheFrameIsDone() {
        int[] buffered = new int[2]; // the value read, and from which frame
        int[] texel = new int[2];
        graph.add(new Lambda(List.of(OUT), g -> {
            Buffer buffer = g.createBuffer("counts", 64);
            g.pass("fill counts").write(buffer, BufferAccess.TRANSFER_DST).record(ctx -> ctx.fill(buffer, 7));
            g.readback("read counts", buffer, 16, 8, (data, frame) -> {
                buffered[0] = data.get(ValueLayout.JAVA_INT, 4);
                buffered[1] = (int) frame;
            });
            Image image = g.create("cleared", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("clear").write(image, Access.TRANSFER_DST).record(ctx -> ctx.clear(image.whole(), 0, 1, 0, 1));
            g.readback("read texels", image.whole(), (data, frame) -> {
                assertThat(data.byteSize()).isEqualTo(4L * SIZE * SIZE);
                texel[0] = data.get(ValueLayout.JAVA_INT, 4L * (SIZE * SIZE - 1));
                texel[1] = (int) frame;
            });
            g.provide(OUT, image);
        })).output(OUT, output);
        frame();
        assertThat(buffered[1]).as("not before the frame is done").isZero();
        frame(); // the caller waited for the first: it is delivered as the second records
        assertThat(buffered).containsExactly(7, 1);
        assertThat(texel).containsExactly(GREEN, 1);
    }

    @Test
    void readbacksStayInsideWhatTheyRead() {
        graph.add(new Lambda(List.of(OUT), g -> {
            g.readback("bad", g.createBuffer("small", 8), 4, 8, (data, frame) -> { });
            g.provide(OUT, g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE)));
        })).output(OUT, output);
        assertThatIllegalArgumentException().isThrownBy(this::frame).withMessageStartingWith("Reading back 8 bytes at 4");
    }

    @Test
    void timingsAndReadbacksOfSubmittedFramesAllocateNothing() {
        long[] delivered = new long[2];
        graph.add(new Fill(A, SIZE, 1, 0, 0, 1))
            .add(new Fill(B, SIZE, 0, 1, 0, 1))
            .add(new Overlapping(OUT, A, B))
            .add(new Lambda(List.of(UNUSED), g -> {
                Buffer buffer = g.createBuffer("probe", 16);
                g.pass("fill probe").write(buffer, BufferAccess.TRANSFER_DST).record(ctx -> ctx.fill(buffer, 3));
                g.readback("read probe", buffer, 0, 16, (data, frame) -> {
                    delivered[0]++;
                    delivered[1] = data.get(ValueLayout.JAVA_INT, 12);
                });
                Image out = g.require(OUT);
                Image copy = g.create("copy", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
                g.pass("copy").read(out, Access.TRANSFER_SRC).write(copy, Access.TRANSFER_DST)
                    .record(ctx -> ctx.blit(out.whole(), copy.whole(), false));
                g.provide(UNUSED, copy);
            }))
            .output(UNUSED, output)
            .asyncCompute(true)
            .timings(true);
        assertThat(graph.timings()).isNotNull();
        assertAllocationFree(() -> {
            graph.beginFrame();
            graph.submit(0, 0);
        }, 2_000, 200, 10);
        assertThat(graph.builds()).isEqualTo(1);
        GpuTimings timings = graph.timings();
        assertThat(timings.frame()).isPositive();
        assertThat(timings.count()).isEqualTo(graph.passes().size());
        assertThat(timings.pass(0)).isEqualTo(graph.passes().get(0));
        assertThat(timings.totalMillis()).isNotNegative();
        for (int i = 0; i < timings.count(); i++) {
            assertThat(timings.millis(i)).as(timings.pass(i)).satisfiesAnyOf(m -> assertThat(m).isNaN(),
                m -> assertThat(m).isNotNegative());
        }
        assertThat(delivered[0]).isPositive();
        assertThat(delivered[1]).isEqualTo(3);
        graph.timings(false);
        assertThat(graph.timings()).isNull();
        graph.beginFrame();
        graph.submit(0, 0); // rebuilt without timings: the readbacks waiting are delivered first
        assertThat(graph.builds()).isEqualTo(2);
    }

    @Test
    void submittingAllocatesNothing() {
        graph.add(new Fill(A, SIZE, 1, 0, 0, 1))
            .add(new Fill(B, SIZE, 0, 1, 0, 1))
            .add(new Overlapping(OUT, A, B))
            .output(OUT, output)
            .asyncCompute(true);
        try (com.truesim.common.jobs.JobSystem jobs = new com.truesim.common.jobs.JobSystem(2)) {
            graph.parallel(jobs, 1);
            assertAllocationFree(() -> {
                graph.beginFrame();
                graph.submit(0, 0);
            }, 20_000, 1_000, 10);
        }
        assertThat(graph.builds()).isEqualTo(1);
    }

    @Test
    void recordingAllocatesNothing() {
        graph.add(new Fill(A, SIZE, 1, 0, 0, 1))
            .add(new Packed(B, GREEN, false))
            .add(new Halves(OUT, A, B))
            .output(OUT, output);
        try (Frames frames = new Frames(device, device.graphics(), 2, 1)) {
            assertAllocationFree(() -> {
                Frames.Frame frame = frames.next();
                graph.record(frame.begin(0));
                frame.end(0);
                frame.submit(device.graphics(), 0, false);
            }, 20_000, 1_000, 10);
            device.waitIdle();
        }
        assertThat(graph.builds()).isEqualTo(1);
    }

    /** Modifies {@code key} at {@code stage}: its columns {@code x} to {@code x + width} cleared to {@code rgba}. */
    record Paint(Key<Image> key, int stage, int x, int width, float... rgba) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(key);
        }

        @Override
        public Order order() {
            return Order.at(new Anchor("stage " + stage, stage)); // lower stages first
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.modify(key);
            Image color = g.create("paint", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("paint " + stage).write(color, Access.TRANSFER_DST).record(ctx -> clear(ctx, color, rgba));
            g.pass("paint over " + stage).read(color, Access.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                .record(ctx -> copy(ctx, color, image, x, width));
        }
    }

    /** Modifies {@code key} at {@code stage} by providing a new image: a copy, its columns from x cleared. */
    record Replace(Key<Image> key, int stage, int x, float... rgba) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(key);
        }

        @Override
        public Order order() {
            return Order.at(new Anchor("stage " + stage, stage)); // lower stages first
        }

        @Override
        public void build(GraphBuilder g) {
            Image before = g.modify(key);
            Image fill = g.create("replace fill", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            Image after = g.create("replaced", ImageDesc.like(before, Ratio.ONE));
            g.pass("replace fill " + stage).write(fill, Access.TRANSFER_DST).record(ctx -> clear(ctx, fill, rgba));
            g.pass("replace " + stage).read(before, Access.TRANSFER_SRC).read(fill, Access.TRANSFER_SRC)
                .write(after, Access.TRANSFER_DST).record(ctx -> {
                    copy(ctx, before, after, 0, x);
                    copy(ctx, fill, after, x, SIZE - x);
                });
            g.provide(key, after);
        }
    }

    @Test
    void modifiersChangeAResultInStageOrder() {
        // Added out of order: stage 20 paints the right quarter green in place; stage 10 replaces the right half
        // with blue. Red on the left from the provider.
        graph.add(new Paint(A, 20, 3 * SIZE / 4, SIZE / 4, 0, 1, 0, 1))
            .add(new Replace(A, 10, SIZE / 2, 0, 0, 1, 1))
            .add(new Fill(A, SIZE, 1, 0, 0, 1))
            .add(new UsesCopy(OUT, A, A))
            .output(OUT, output);
        int[] texels = frame();
        assertThat(texels[0]).isEqualTo(RED);
        assertThat(texels[SIZE / 2 + 2]).isEqualTo(BLUE);
        assertThat(texels[SIZE - 1]).isEqualTo(GREEN);
        assertThat(graph.passes()).containsSubsequence("fill a", "replace 10", "paint over 20", "copy replaced");
        // A modifier nothing needs costs nothing: it runs only when the key is required.
        graph.removeOutput(OUT).output(B, output).add(new Fill(B, SIZE, 0, 0, 1, 1));
        assertThat(frame()[0]).isEqualTo(BLUE);
        assertThat(graph.passes()).containsExactly("fill b");
    }

    /** Provides {@code key}: a buffer of SIZE x SIZE texels of {@code rgba}. */
    record FillBuffer(Key<Buffer> key, int rgba) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(key);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer buffer = g.createBuffer(label(key), 4L * SIZE * SIZE);
            g.pass("fill " + label(key)).write(buffer, BufferAccess.TRANSFER_DST)
                .record(ctx -> ctx.fill(buffer, rgba));
            g.provide(key, buffer);
        }
    }

    /** Provides {@code out}: the texels of buffer {@code from}. */
    record Unpack(Key<Image> out, Key<Buffer> from) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(out);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer buffer = g.require(from);
            Image image = g.create(label(out), ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("unpack " + label(out)).read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                .record(ctx -> unpack(ctx, buffer, image));
            g.provide(out, image);
        }
    }

    @Test
    void keysNameBuffersToo() {
        Key<Buffer> texels = Key.buffer("test.texels");
        graph.add(new FillBuffer(texels, RED)).add(new Unpack(OUT, texels)).output(OUT, output);
        assertThat(frame()).containsOnly(RED);
        // Modified in place, like images.
        graph.add(new Mod(List.of(), List.of(texels), g -> {
            Buffer buffer = g.modify(texels);
            g.pass("paint texels").write(buffer, BufferAccess.TRANSFER_DST)
                .record(ctx -> ctx.fill(buffer, GREEN));
        }));
        assertThat(frame()).containsOnly(GREEN);
        rejects(List.of(OUT), g -> g.provide(OUT, g.optional(Key.image("test.none"))), "Not an image of this build");
        rejects(List.of(OUT), g -> g.require(Key.image("test.none")), "No technique provides test.none");
    }

    /** A technique providing and modifying the keys given, built by {@code builder}. */
    record Mod(List<Key<?>> provides, List<Key<?>> modifies, java.util.function.Consumer<GraphBuilder> builder)
        implements Technique {
        @Override
        public void build(GraphBuilder g) {
            builder.accept(g);
        }
    }

    /** Building {@code techniques}, outputting {@code key}, fails so. */
    private void fails(Key<Image> key, String message, Technique... techniques) {
        graph.close();
        graph = new FrameGraph(device, 2);
        for (Technique technique : techniques) {
            graph.add(technique);
        }
        graph.output(key, output);
        assertThatIllegalStateException().isThrownBy(this::frame).withMessageContaining(message);
    }

    @Test
    void modifiersAreChecked() {
        rejects(List.of(OUT), g -> g.modify(A), "cannot modify test.a");
        Fill red = new Fill(A, SIZE, 1, 0, 0, 1);
        fails(OUT, "both provides and modifies test.out", red, new Mod(List.of(OUT), List.of(OUT), g -> { }));
        fails(A, "cycle", red, new Mod(List.of(), List.of(A), g -> g.require(A))); // the version after itself
        fails(OUT, "No technique provides test.a", new Mod(List.of(), List.of(A), g -> g.modify(A)),
            new UsesCopy(OUT, B, B), new Fill(B, SIZE, 1, 0, 0, 1), new Mod(List.of(), List.of(B), g -> g.require(A)));
        fails(OUT, "No technique provides test.a", new UsesCopy(OUT, B, B), new Fill(B, SIZE, 1, 0, 0, 1),
            new Mod(List.of(), List.of(A, B), g -> g.modify(A)));
        // A modifier that takes nothing changes nothing.
        graph.close();
        graph = new FrameGraph(device, 2).add(red).add(new Mod(List.of(), List.of(A), g -> { })).output(A, output);
        assertThat(frame()[0]).isEqualTo(RED);
        rejects(List.of(OUT), g -> g.provide(Key.image("test.other"), g.create("x", ImageDesc.of(Format.R8G8B8A8_UNORM, 1, 1))), "cannot provide test.other");
    }

    /** Keeps a color per frame in a history and outputs last frame's (white when there is none). */
    final class Remember implements Technique {
        int frames;
        boolean sampled; // also samples the history: another usage, so other images

        @Override
        public List<Key<?>> provides() {
            return List.of(OUT);
        }

        @Override
        public void build(GraphBuilder g) {
            History history = g.history("colors", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            assertThat(history.name()).isEqualTo("colors");
            assertThat(history).hasToString("history colors");
            Image out = g.create("recalled", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("recall").read(history.previous(), Access.TRANSFER_SRC).write(out, Access.TRANSFER_DST)
                .record(ctx -> {
                    if (ctx.historyValid(history)) {
                        copy(ctx, history.previous(), out, 0, SIZE);
                    } else {
                        clear(ctx, out, 1, 1, 1, 1);
                    }
                });
            g.pass("remember").write(history.current(), Access.TRANSFER_DST)
                .record(ctx -> clear(ctx, history.current(), frames % 2, 0, 1 - frames++ % 2, 1));
            if (sampled) {
                g.pass("peek").read(history.previous(), Access.SAMPLED_COMPUTE).sideEffects().record(ctx -> { });
            }
            g.provide(OUT, out);
        }
    }

    /** Shared work counting the frames it runs in: one pass, whichever views ask for it. */
    record Shared(int[] runs, java.util.function.BooleanSupplier[] running) implements Request<Boolean> {
        @Override
        public Boolean build(GraphBuilder g) {
            running[0] = g.running();
            g.pass("shared").sideEffects().record(ctx -> runs[0]++);
            return true;
        }
    }

    /** {@code inner}, asking for {@code request} too. */
    record Asking(Request<?> request, Technique inner) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return inner.provides();
        }

        @Override
        public void build(GraphBuilder g) {
            g.request(request);
            inner.build(g);
        }
    }

    @Test
    void viewsAreSkippedOrRunLessOftenWithoutARebuildAndKeepTheirHistories() {
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (GpuImage second = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage,
            VK_IMAGE_ASPECT_COLOR_BIT)) {
            ExternalImage mirror = new ExternalImage("mirror", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(second.handle(), second.view());
            int[] shared = {0};
            java.util.function.BooleanSupplier[] running = new java.util.function.BooleanSupplier[1];
            java.util.function.BooleanSupplier[] frameRunning = new java.util.function.BooleanSupplier[1];
            Remember remember = new Remember(); // each frame it runs: shows last frame's color, writes the other one
            ViewPort main = graph.addView("main").add(new Asking(new Shared(shared, running), remember))
                .output(OUT, output);
            ViewPort side = graph.addView("side")
                .add(new Asking(new Shared(shared, running), new Copies(A, OUT))).output(OUT, mirror);
            graph.add(new Lambda(List.of(A), g -> {
                frameRunning[0] = g.running();
                new Fill(A, SIZE, 1, 0, 0, 1).build(g); // red, for the side view
            }));
            assertThat(frame()[0]).isEqualTo(0xffffffff);
            assertThat(frame()[0]).isEqualTo(BLUE);
            assertThat(shared[0]).as("shared work, once a frame").isEqualTo(2);
            int builds = graph.builds();
            // Off: none of its passes run, its history stays as the last frame it ran left it.
            main.enabled(false);
            frame();
            assertThat(main.active()).isFalse();
            assertThat(remember.frames).as("skipped").isEqualTo(2);
            assertThat(firstTexel(second)).as("the other view runs").isEqualTo(RED);
            assertThat(shared[0]).as("still asked for by the other view").isEqualTo(3);
            assertThat(running[0].getAsBoolean()).isTrue();
            side.enabled(false);
            frame();
            assertThat(shared[0]).as("no view asking for it runs").isEqualTo(3);
            assertThat(running[0].getAsBoolean()).as("what records it knows").isFalse();
            assertThat(frameRunning[0].getAsBoolean()).as("a frame technique's, always").isTrue();
            main.enabled(true);
            assertThat(frame()[0]).as("what it wrote the last frame it ran").isEqualTo(RED);
            assertThat(shared[0]).isEqualTo(4);
            // Every 3rd frame, starting with the next.
            main.rate(3);
            assertThat(frame()[0]).isEqualTo(BLUE);
            frame();
            frame();
            assertThat(remember.frames).as("ran once in three frames").isEqualTo(4);
            assertThat(frame()[0]).isEqualTo(RED);
            assertThat(remember.frames).isEqualTo(5);
            assertThat(graph.builds()).as("no rebuild").isEqualTo(builds);
            // Rebuilt while skipped: the history keeps what it held.
            graph.invalidate();
            frame();
            assertThat(remember.frames).isEqualTo(5);
            main.rate(1);
            assertThat(frame()[0]).isEqualTo(BLUE);
            assertThat(main.rate()).isEqualTo(1);
            assertThatIllegalArgumentException().isThrownBy(() -> main.rate(0));
        }
    }

    @Test
    void aViewSkippedFromItsStartHasNoLastFrameUntilItRuns() {
        ViewPort late = graph.addView("late").add(new Remember()).output(OUT, output).enabled(false);
        assertThat(late.enabled()).isFalse();
        frame();
        submitted(2);
        late.enabled(true);
        assertThat(frame()[0]).as("no last frame yet").isEqualTo(0xffffffff);
        assertThat(frame()[0]).isEqualTo(BLUE);
        // Submitted with frames in flight, every other frame: what the last frame it ran wrote.
        late.rate(2);
        assertThat(submitted(4)[0]).isIn(RED, BLUE);
    }

    @Test
    void viewsRenderIntoTexturesThatOthersReadAndThatKeepTheirContentsWhileSkipped() {
        float[] color = {1, 0, 0, 1};
        Texture screen = graph.texture("screen", Format.R8G8B8A8_UNORM, SIZE, SIZE);
        Texture green = graph.texture("green", Format.R8G8B8A8_UNORM, SIZE, SIZE);
        assertThat(screen.slot()).isNotNegative().isNotEqualTo(green.slot());
        assertThat(screen).hasToString("texture screen");
        assertThat(screen.width()).isEqualTo(SIZE);
        // A security camera renders into one texture, the frame into another; the main view shows both.
        ViewPort camera = graph.addView("camera").add(new Fill(OUT, SIZE, color)).output(OUT, screen);
        graph.add(new Fill(A, SIZE, 0, 1, 0, 1)).output(A, green);
        int[] slot = {-2};
        graph.addView("main").add(new Lambda(List.of(OUT), g -> {
            Image left = g.texture(screen);
            Image right = g.texture(green);
            assertThat(g.texture(screen)).isSameAs(left);
            Image out = g.create("shown", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("show").read(left, Access.TRANSFER_SRC).read(right, Access.TRANSFER_SRC)
                .write(out, Access.TRANSFER_DST).record(ctx -> {
                    copy(ctx, left, out, 0, SIZE / 2);
                    copy(ctx, right, out, SIZE / 2, SIZE / 2);
                });
            g.pass("peek").read(left, Access.SAMPLED_COMPUTE).sideEffects().record(ctx -> slot[0] = ctx.sampled(left));
            g.provide(OUT, out);
        })).output(OUT, output);
        int[] texels = frame();
        assertThat(texels[0]).as("this frame's, copied before it is read").isEqualTo(RED);
        assertThat(texels[SIZE - 1]).isEqualTo(GREEN);
        assertThat(slot[0]).as("sampled at its slot").isEqualTo(screen.slot());
        assertThat(graph.passes()).containsOnlyOnce("output screen").containsOnlyOnce("output green");
        // A view drawing its result as an attachment that would fit the texture still copies into it: drawn into
        // straight, a frame it is skipped would discard the texture's contents.
        Texture mirror = graph.texture("mirror", Format.R8G8B8A8_UNORM, SIZE, SIZE);
        graph.addView("mirror").add(new Lambda(List.of(OUT), g -> {
            Image drawn = g.create("drawn", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("draw mirror").attach(drawn, Clear.color(0, 1, 0, 1)).record(ctx -> { });
            g.provide(OUT, drawn);
        })).output(OUT, mirror);
        frame();
        assertThat(graph.passes()).contains("draw mirror", "output mirror");
        // The camera off: the texture keeps the last frame it rendered.
        color[0] = 0;
        color[2] = 1;
        camera.enabled(false);
        assertThat(frame()[0]).isEqualTo(RED);
        camera.enabled(true);
        assertThat(frame()[0]).isEqualTo(BLUE);
        // Its next frames at a lower rate, submitted with frames in flight: still this frame's or the last one rendered.
        camera.rate(2);
        assertThat(submitted(3)[0]).isEqualTo(BLUE);
        // Rendered once and its view gone, a texture keeps its image: a one-shot render.
        graph.removeView(camera);
        assertThat(frame()[0]).as("kept after its view went").isEqualTo(BLUE);
        // Removed, it is freed once no frame in flight samples it; outputs into it go, and reading it fails.
        assertThat(graph.textures()).contains(screen, green, mirror);
        graph.removeTexture(mirror).removeTexture(mirror);
        assertThat(graph.textures()).doesNotContain(mirror);
        frame();
        assertThat(graph.passes()).doesNotContain("output mirror");
        graph.removeTexture(screen);
        assertThatIllegalStateException().isThrownBy(this::frame).withMessageContaining("texture screen was removed");
    }

    @Test
    void historiesWrittenFirstOnTheComputeQueueAreJoinedAhead() {
        int[] frames = {0};
        graph.asyncCompute(true).add(new Lambda(List.of(OUT), g -> {
            History history = g.history("async colors", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            // The frame's first pass, on the compute queue: the seam is submitted ahead of it.
            g.pass("remember").write(history.current(), Access.TRANSFER_DST).async()
                .record(ctx -> clear(ctx, history.current(), frames[0] % 2, 0, 1 - frames[0]++ % 2, 1));
            Image out = g.create("recalled", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("recall").read(history.previous(), Access.TRANSFER_SRC).write(out, Access.TRANSFER_DST)
                .record(ctx -> {
                    if (ctx.historyValid(history)) {
                        copy(ctx, history.previous(), out, 0, SIZE);
                    } else {
                        clear(ctx, out, 1, 1, 1, 1);
                    }
                });
            g.provide(OUT, out);
        })).output(OUT, output);
        submitted(2);
        graph.invalidate();
        assertThat(submitted(2)[0]).isIn(RED, BLUE); // kept across the rebuild
    }

    @Test
    void historiesKeepLastFramesImage() {
        Remember remember = new Remember();
        graph.add(remember).output(OUT, output);
        assertThat(frame()[0]).isEqualTo(0xffffffff); // no last frame yet
        assertThat(frame()[0]).isEqualTo(BLUE); // frame 0 remembered blue
        assertThat(frame()[0]).isEqualTo(RED);
        assertThat(frame()[0]).isEqualTo(BLUE);
        // Submitted the graph's way, frames in flight, the roles still alternate.
        int[] last = submitted(5);
        assertThat(last[0]).isIn(RED, BLUE);
        // Rebuilt: the same history keeps its images and contents, without waiting for the GPU (the seam).
        graph.invalidate();
        int afterRebuild = frame()[0];
        assertThat(afterRebuild).isIn(RED, BLUE);
        assertThat(frame()[0]).isIn(RED, BLUE).isNotEqualTo(afterRebuild);
        graph.invalidate();
        assertThat(submitted(3)[0]).isIn(RED, BLUE); // rebuilt between submitted frames too
        // A history used differently gets other images: their contents start over.
        remember.sampled = true;
        graph.invalidate();
        assertThat(frame()[0]).isEqualTo(0xffffffff);
        assertThat(frame()[0]).isIn(RED, BLUE);
        rejects(List.of(OUT), g -> {
            g.history("x", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
            g.history("x", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
        }, "Two histories are named x");
        rejects(List.of(OUT), g -> {
            History history = g.history("x", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("scribble").write(history.previous(), Access.TRANSFER_DST).record(ctx -> { });
        }, "last frame's image is read only");
    }

    @Test
    void techniquesAreRemovedById() {
        com.truesim.common.graph.Id<Fill> redId = com.truesim.common.graph.Id.of("test.red", Fill.class);
        graph.add(new Technique() {
            @Override
            public com.truesim.common.graph.Id<Fill> id() {
                return redId;
            }

            @Override
            public List<Key<?>> provides() {
                return List.of(OUT);
            }

            @Override
            public void build(GraphBuilder g) {
                new Fill(OUT, SIZE, 1, 0, 0, 1).build(g);
            }
        }).output(OUT, output);
        assertThat(frame()[0]).isEqualTo(RED);
        graph.remove(com.truesim.common.graph.Id.of("test.red", Fill.class)); // another id of the name: not it
        assertThat(frame()[0]).isEqualTo(RED);
        graph.remove(redId);
        assertThatIllegalStateException().isThrownBy(this::frame).withMessageContaining("No technique provides test.out");
        assertThatNullPointerException().isThrownBy(() -> graph.remove((com.truesim.common.graph.Id<Fill>) null));
    }

    @Test
    void misuseIsReportedWithTheTechniquesInvolved() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FrameGraph(device, 0));
        // A key nobody provides.
        graph.add(new Halves(OUT, A, B)).output(OUT, output);
        assertThatIllegalStateException().isThrownBy(this::frame).withMessageContaining("No technique provides test.a");
        // Two providers of one key.
        graph.add(new Fill(A, SIZE, 1, 0, 0, 1)).add(new Fill(B, SIZE, 1, 0, 0, 1)).add(new Fill(A, SIZE, 1, 1, 1, 1));
        assertThatIllegalStateException().isThrownBy(this::frame).withMessageContaining("Two techniques provide test.a");
        Fill twice = new Fill(UNUSED, SIZE, 1, 1, 1, 1);
        graph.add(twice);
        assertThatIllegalArgumentException().isThrownBy(() -> graph.add(twice));
        rejects(List.of(OUT), g -> g.require(OUT), "cycle"); // requires its own result
        rejects(List.of(OUT), g -> { }, "did not provide [test.out]");
        rejects(List.of(OUT), g -> g.provide(A, g.create("x", ImageDesc.of(Format.R8G8B8A8_UNORM, 1, 1))), "cannot provide test.a");
        rejects(List.of(OUT), g -> {
            Image image = g.create("x", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.provide(OUT, image);
            g.provide(OUT, image);
        }, "provides test.out twice");
        rejects(List.of(OUT), g -> {
            Image foreign = g.require(A);
            g.pass("scribble").write(foreign, Access.TRANSFER_DST).record(ctx -> { });
        }, "results are written only by whoever makes them");
        rejects(List.of(OUT), g -> {
            Image image = g.create("x", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("early").read(image, Access.TRANSFER_SRC).write(image, Access.TRANSFER_DST).record(ctx -> { });
            g.provide(OUT, image);
        }, "in two layouts");
        rejects(List.of(OUT), g -> {
            Image image = g.create("x", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            Image other = g.create("y", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("read").read(image, Access.TRANSFER_SRC).write(other, Access.TRANSFER_DST).record(ctx -> { });
            g.provide(OUT, other);
        }, "reads x before any pass writes it");
        rejects(List.of(OUT), g -> g.request(new Request<Image>() {
            @Override
            public Image build(GraphBuilder builder) {
                return builder.request(this);
            }
        }), "needs its own result");
        rejects(List.of(OUT), g -> g.create("x", ImageDesc.of(Format.R8G8B8A8_UNORM, 0, 1)), "are positive");
        rejects(List.of(OUT), g -> g.create("x", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4).mips(4)), "1 to 3 mip levels");
        assertThatIllegalArgumentException().isThrownBy(() -> Ratio.of(0, 1)).withMessageContaining("positive fraction");
        rejects(List.of(OUT), g -> g.pass("x").read(g.require(A), Access.TRANSFER_DST), "declare it with write()");
        rejects(List.of(OUT), g -> g.pass("x").write(g.require(A), Access.TRANSFER_SRC), "declare it with read()");
        // Buffers.
        rejects(List.of(OUT), g -> g.createBuffer("x", 0), "must be positive");
        rejects(List.of(OUT), g -> {
            Buffer buffer = g.createBuffer("x", 64);
            Image image = g.create("y", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("read").read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                .record(ctx -> { });
            g.provide(OUT, image);
        }, "reads x before any pass writes it");
        rejects(List.of(OUT), g -> {
            Buffer foreign = g.request(new Filled(7));
            g.pass("scribble").write(foreign, BufferAccess.TRANSFER_DST).record(ctx -> { });
        }, "results are written only by whoever makes them");
        rejects(List.of(OUT), g -> {
            Buffer buffer = g.createBuffer("x", 64);
            g.pass("indices").write(buffer, BufferAccess.TRANSFER_DST).record(ctx -> { });
            Image image = g.create("y", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("draw").read(buffer, BufferAccess.INDEX).write(image, Access.TRANSFER_DST).async()
                .record(ctx -> { });
            g.provide(OUT, image);
        }, "async passes compute, store and copy only");
        rejects(List.of(OUT), g -> g.pass("x").read(g.createBuffer("x", 4), BufferAccess.STORAGE_WRITE),
            "declare it with write()");
        rejects(List.of(OUT), g -> g.pass("x").write(g.createBuffer("x", 4), BufferAccess.STORAGE_READ),
            "declare it with read()");
        rejects(List.of(OUT), g -> g.pass("x").read(new Buffer(3, "stray", 4), BufferAccess.INDIRECT),
            "Not a buffer of this build");
        assertThat(new Buffer(0, "b", 8)).hasToString("b (8 bytes)");
        assertThat(BufferAccess.DRAW_WRITE.writes()).isTrue();
        assertThat(BufferAccess.DRAW_READ.writes()).isFalse();
        // An external image used beyond its usage, or sampled.
        rejects(List.of(OUT), g -> {
            Image image = g.external(output);
            g.pass("draw").write(image, Access.COLOR_ATTACHMENT).sideEffects().record(ctx -> { });
            g.provide(OUT, g.require(A));
        }, "beyond the usage");
        assertThatIllegalArgumentException().isThrownBy(() -> output.resize(0, 1));
        graph.close();
        graph = new FrameGraph(device, 2).add(new Lambda(List.of(OUT), g -> g.provide(OUT, (Image) null)))
            .output(OUT, output);
        assertThatIllegalArgumentException().isThrownBy(this::frame).withMessageContaining("Not an image of this build");
    }

    /** Building with only {@code A} and a technique providing {@code keys} with {@code builder} fails so. */
    private void rejects(List<Key<?>> keys, java.util.function.Consumer<GraphBuilder> builder, String message) {
        graph.close();
        graph = new FrameGraph(device, 2).add(new Fill(A, SIZE, 1, 0, 0, 1)).add(new Lambda(keys, builder))
            .output(OUT, output);
        assertThatThrownBy(this::frame).isInstanceOf(RuntimeException.class).hasMessageContaining(message);
    }
}
