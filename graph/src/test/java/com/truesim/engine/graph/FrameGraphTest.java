package com.truesim.engine.graph;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
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
    private static final Key A = Key.of("a");
    private static final Key B = Key.of("b");
    private static final Key UNUSED = Key.of("unused");
    private static final Key OUT = Key.of("out");

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
        output = new ExternalImage("target", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
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

    /** Provides {@code key}: an image cleared to {@code rgba}. */
    record Fill(Key key, int size, float... rgba) implements Technique {
        @Override
        public List<Key> provides() {
            return List.of(key);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.create(key.name(), size, size, VK_FORMAT_R8G8B8A8_UNORM);
            g.pass("fill " + key).write(image, Access.TRANSFER_DST).record(ctx -> clear(ctx, image, rgba));
            g.provide(key, image);
        }
    }

    /** Provides {@code out}: the left half from {@code left}, the right half from {@code right}. */
    record Halves(Key out, Key left, Key right) implements Technique {
        @Override
        public List<Key> provides() {
            return List.of(out);
        }

        @Override
        public void build(GraphBuilder g) {
            Image l = g.require(left);
            Image r = g.require(right);
            Image image = g.createLike(out.name(), l, 1);
            g.pass("halves " + out)
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
            Image copy = g.createLike("copy of " + source.name(), source, 1);
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
            Image copy = g.createLike("async copy of " + source.name(), source, 1);
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
    record Overlapping(Key out, Key a, Key b) implements Technique {
        @Override
        public List<Key> provides() {
            return List.of(out);
        }

        @Override
        public void build(GraphBuilder g) {
            Image copied = g.request(new AsyncCopy(g.require(a)));
            Image right = g.require(b);
            Image image = g.createLike(out.name(), copied, 1);
            g.pass("halves " + out).read(copied, Access.TRANSFER_SRC).read(right, Access.TRANSFER_SRC)
                .write(image, Access.TRANSFER_DST).record(ctx -> {
                    copy(ctx, copied, image, 0, SIZE / 2);
                    copy(ctx, right, image, SIZE / 2, SIZE / 2);
                });
            g.provide(out, image);
        }
    }

    /** Provides {@code out}: {@code from} copied {@code times} times over, each copy its own pass. */
    record Chain(Key out, Key from, int times) implements Technique {
        @Override
        public List<Key> provides() {
            return List.of(out);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.require(from);
            for (int i = 0; i < times; i++) {
                Image next = g.create(out.name() + " " + i, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
                Image source = image;
                g.pass("chain " + i).read(source, Access.TRANSFER_SRC).write(next, Access.TRANSFER_DST)
                    .record(ctx -> copy(ctx, source, next, 0, SIZE));
                image = next;
            }
            g.provide(out, image);
        }
    }

    /** Provides {@code out}: halves of the shared copy of {@code from} and of {@code other}. */
    record UsesCopy(Key out, Key from, Key other) implements Technique {
        @Override
        public List<Key> provides() {
            return List.of(out);
        }

        @Override
        public void build(GraphBuilder g) {
            Image copied = g.request(new Copy(g.require(from)));
            Image second = g.require(other);
            Image image = g.createLike(out.name(), copied, 1);
            g.pass("use " + out)
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
    record Packed(Key key, int rgba, boolean async) implements Technique {
        @Override
        public List<Key> provides() {
            return List.of(key);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer buffer = g.createBuffer(key.name() + " texels", 4L * SIZE * SIZE);
            GraphBuilder.PassBuilder fill = g.pass("fill " + key + " texels").write(buffer, BufferAccess.TRANSFER_DST);
            (async ? fill.async() : fill).record(ctx -> vkCmdFillBuffer(ctx.commands(), ctx.buffer(buffer), 0,
                VK_WHOLE_SIZE, rgba));
            Image image = g.create(key.name(), SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
            g.pass("unpack " + key).read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
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
                .record(ctx -> vkCmdFillBuffer(ctx.commands(), ctx.buffer(buffer), 0, VK_WHOLE_SIZE, value));
            return buffer;
        }
    }

    /** Copies the first {@code bytes} of {@code from} to the start of {@code to}; field by field, allocating nothing. */
    static void copy(PassContext ctx, Buffer from, Buffer to, long bytes) {
        try (MemoryStack stack = stackPush()) {
            long region = stack.ncalloc(8, 1, VkBufferCopy.SIZEOF);
            memPutLong(region + VkBufferCopy.SIZE, bytes);
            nvkCmdCopyBuffer(ctx.commands(), ctx.buffer(from), ctx.buffer(to), 1, region);
        }
    }

    /** Copies {@code buffer}'s first SIZE x SIZE texels into {@code image}. */
    static void unpack(PassContext ctx, Buffer buffer, Image image) {
        try (MemoryStack stack = stackPush()) {
            long region = stack.ncalloc(8, 1, VkBufferImageCopy.SIZEOF);
            memPutInt(region + VkBufferImageCopy.IMAGESUBRESOURCE + VkImageSubresourceLayers.ASPECTMASK,
                VK_IMAGE_ASPECT_COLOR_BIT);
            memPutInt(region + VkBufferImageCopy.IMAGESUBRESOURCE + VkImageSubresourceLayers.LAYERCOUNT, 1);
            memPutInt(region + VkBufferImageCopy.IMAGEEXTENT, SIZE);
            memPutInt(region + VkBufferImageCopy.IMAGEEXTENT + 4, SIZE);
            memPutInt(region + VkBufferImageCopy.IMAGEEXTENT + 8, 1);
            nvkCmdCopyBufferToImage(ctx.commands(), ctx.buffer(buffer), ctx.image(image),
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, region);
        }
    }

    /** A technique built from a lambda, for the misuse tests. */
    record Lambda(List<Key> provides, java.util.function.Consumer<GraphBuilder> builder) implements Technique {
        @Override
        public void build(GraphBuilder g) {
            builder.accept(g);
        }
    }

    /** Clears {@code image} to {@code rgba}; written field by field, so recording allocates nothing. */
    static void clear(PassContext ctx, Image image, float... rgba) {
        try (MemoryStack stack = stackPush()) {
            long color = stack.nmalloc(4, 16);
            for (int i = 0; i < 4; i++) {
                memPutFloat(color + 4L * i, rgba[i]);
            }
            long range = stack.ncalloc(4, 1, VkImageSubresourceRange.SIZEOF);
            memPutInt(range + VkImageSubresourceRange.ASPECTMASK, VK_IMAGE_ASPECT_COLOR_BIT);
            memPutInt(range + VkImageSubresourceRange.LEVELCOUNT, 1);
            memPutInt(range + VkImageSubresourceRange.LAYERCOUNT, 1);
            nvkCmdClearColorImage(ctx.commands(), ctx.image(image), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, 1,
                range);
        }
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
            nvkCmdCopyImage(ctx.commands(), ctx.image(from), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, ctx.image(to),
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
        Key other = Key.of("other");
        graph.add(new Fill(A, SIZE, 1, 0, 0, 1))
            .add(new Fill(B, SIZE, 0, 0, 1, 1))
            .add(new UsesCopy(other, A, B))
            .add(new UsesCopy(Key.of("second"), A, B)) // requests the same copy, but nothing needs it
            .add(new Halves(OUT, other, A))
            .output(OUT, output);
        int[] texels = frame();
        assertThat(graph.passes()).containsExactly("fill a", "copy a", "fill b", "use other", "halves out");
        assertThat(texels[0]).isEqualTo(RED);
        assertThat(texels[SIZE - 1]).isEqualTo(RED);

        // Two consumers of one copy: the copy runs once.
        Key both = Key.of("both");
        Key second = Key.of("second2");
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
        Key chained = Key.of("chained");
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
                Image image = g.create("out", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
                g.pass("read state").read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                    .record(ctx -> unpack(ctx, buffer, image));
                g.pass("write state").write(buffer, BufferAccess.TRANSFER_DST).record(ctx -> {
                    assertThat(ctx.address(buffer)).isEqualTo(state.address);
                    vkCmdFillBuffer(ctx.commands(), ctx.buffer(buffer), 0, VK_WHOLE_SIZE, fill[0]);
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
                Image image = g.create("out", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
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
                Image image = g.create("out", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
                g.pass("read state").read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                    .record(ctx -> unpack(ctx, buffer, image));
                g.provide(OUT, image);
            })).output(OUT, output);
            assertThatIllegalStateException().isThrownBy(() -> device.submitAndWait(device.graphics(), graph::record))
                .withMessageContaining("external buffer");
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
                .record(ctx -> vkCmdFillBuffer(ctx.commands(), ctx.buffer(a), 0, VK_WHOLE_SIZE, RED));
            g.pass("fill unused").write(unused, BufferAccess.TRANSFER_DST).record(ctx -> { });
            g.pass("a to b").read(a, BufferAccess.TRANSFER_SRC).write(b, BufferAccess.TRANSFER_DST)
                .record(ctx -> copy(ctx, a, b, 2 * half));
            g.pass("fill c").write(c, BufferAccess.TRANSFER_DST)
                .record(ctx -> vkCmdFillBuffer(ctx.commands(), ctx.buffer(c), 0, VK_WHOLE_SIZE, GREEN));
            g.pass("b to c").read(b, BufferAccess.TRANSFER_SRC).write(c, BufferAccess.TRANSFER_DST)
                .record(ctx -> copy(ctx, b, c, half));
            Image image = g.create("out", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
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
        Key copied = Key.of("copied");
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
            Image blue = g.createLike("blue", request.source(), 1);
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
    void passesRecordInParallelChunksSubmittedInOrder() {
        Key copied = Key.of("copied");
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
            Image image = g.create("x", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
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
            Image image = g.create("x", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
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
            Image image = g.create("small", SIZE / 2, SIZE / 2, VK_FORMAT_R8G8B8A8_UNORM);
            g.pass("only compute").write(image, Access.TRANSFER_DST).async()
                .record(ctx -> clear(ctx, image, 1, 1, 1, 1));
            g.provide(OUT, image);
        })).output(OUT, output);
        assertThat(submitted(3)[0]).isEqualTo(0xffffffff);
        assertThat(graph.batches()).containsExactly("compute 1: only compute",
            "graphics 1 (after compute 1, acquire): output target");
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
    record Paint(Key key, int stage, int x, int width, float... rgba) implements Technique {
        @Override
        public List<Key> provides() {
            return List.of();
        }

        @Override
        public List<Key> modifies() {
            return List.of(key);
        }

        @Override
        public int stage() {
            return stage;
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.modify(key);
            Image color = g.create("paint", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
            g.pass("paint " + stage).write(color, Access.TRANSFER_DST).record(ctx -> clear(ctx, color, rgba));
            g.pass("paint over " + stage).read(color, Access.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                .record(ctx -> copy(ctx, color, image, x, width));
        }
    }

    /** Modifies {@code key} at {@code stage} by providing a new image: a copy, its columns from x cleared. */
    record Replace(Key key, int stage, int x, float... rgba) implements Technique {
        @Override
        public List<Key> provides() {
            return List.of();
        }

        @Override
        public List<Key> modifies() {
            return List.of(key);
        }

        @Override
        public int stage() {
            return stage;
        }

        @Override
        public void build(GraphBuilder g) {
            Image before = g.modify(key);
            Image fill = g.create("replace fill", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
            Image after = g.createLike("replaced", before, 1);
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
    record FillBuffer(Key key, int rgba) implements Technique {
        @Override
        public List<Key> provides() {
            return List.of(key);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer buffer = g.createBuffer(key.name(), 4L * SIZE * SIZE);
            g.pass("fill " + key).write(buffer, BufferAccess.TRANSFER_DST)
                .record(ctx -> vkCmdFillBuffer(ctx.commands(), ctx.buffer(buffer), 0, VK_WHOLE_SIZE, rgba));
            g.provide(key, buffer);
        }
    }

    /** Provides {@code out}: the texels of buffer {@code from}. */
    record Unpack(Key out, Key from) implements Technique {
        @Override
        public List<Key> provides() {
            return List.of(out);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer buffer = g.requireBuffer(from);
            Image image = g.create(out.name(), SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
            g.pass("unpack " + out).read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                .record(ctx -> unpack(ctx, buffer, image));
            g.provide(out, image);
        }
    }

    @Test
    void keysNameBuffersToo() {
        Key texels = Key.of("texels");
        graph.add(new FillBuffer(texels, RED)).add(new Unpack(OUT, texels)).output(OUT, output);
        assertThat(frame()).containsOnly(RED);
        // Modified in place, like images.
        graph.add(new Mod(List.of(), List.of(texels), g -> {
            Buffer buffer = g.modifyBuffer(texels);
            g.pass("paint texels").write(buffer, BufferAccess.TRANSFER_DST)
                .record(ctx -> vkCmdFillBuffer(ctx.commands(), ctx.buffer(buffer), 0, VK_WHOLE_SIZE, GREEN));
        }));
        assertThat(frame()).containsOnly(GREEN);
        rejects(List.of(OUT), g -> g.provide(OUT, g.requireBuffer(A)), "a is an image, not a buffer");
        rejects(List.of(OUT, texels), g -> {
            g.provide(texels, g.createBuffer("t", 4));
            g.provide(OUT, g.require(texels));
        }, "texels is a buffer, not an image");
        rejects(List.of(OUT), g -> g.provide(OUT, g.optionalBuffer(Key.of("none"))), "Not a buffer of this build");
        rejects(List.of(OUT), g -> g.requireBuffer(Key.of("none")), "No technique provides none");
    }

    /** A technique providing and modifying the keys given, built by {@code builder}. */
    record Mod(List<Key> provides, List<Key> modifies, java.util.function.Consumer<GraphBuilder> builder)
        implements Technique {
        @Override
        public void build(GraphBuilder g) {
            builder.accept(g);
        }
    }

    /** Building {@code techniques}, outputting {@code key}, fails so. */
    private void fails(Key key, String message, Technique... techniques) {
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
        rejects(List.of(OUT), g -> g.modify(A), "cannot modify a");
        Fill red = new Fill(A, SIZE, 1, 0, 0, 1);
        fails(OUT, "both provides and modifies out", red, new Mod(List.of(OUT), List.of(OUT), g -> { }));
        fails(A, "another kind of result", red, new Mod(List.of(), List.of(A), g -> {
            g.modify(A);
            g.provide(A, g.createBuffer("x", 4));
        }));
        fails(A, "cycle", red, new Mod(List.of(), List.of(A), g -> g.require(A))); // the version after itself
        fails(OUT, "No technique provides a", new Mod(List.of(), List.of(A), g -> g.modify(A)),
            new UsesCopy(OUT, B, B), new Fill(B, SIZE, 1, 0, 0, 1), new Mod(List.of(), List.of(B), g -> g.require(A)));
        fails(OUT, "No technique provides a", new UsesCopy(OUT, B, B), new Fill(B, SIZE, 1, 0, 0, 1),
            new Mod(List.of(), List.of(A, B), g -> g.modify(A)));
        // A modifier that takes nothing changes nothing.
        graph.close();
        graph = new FrameGraph(device, 2).add(red).add(new Mod(List.of(), List.of(A), g -> { })).output(A, output);
        assertThat(frame()[0]).isEqualTo(RED);
        rejects(List.of(OUT), g -> g.provide(Key.of("other"), g.createBuffer("x", 4)), "cannot provide other");
    }

    /** Keeps a color per frame in a history and outputs last frame's (white when there is none). */
    final class Remember implements Technique {
        int frames;

        @Override
        public List<Key> provides() {
            return List.of(OUT);
        }

        @Override
        public void build(GraphBuilder g) {
            History history = g.history("colors", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
            assertThat(history.name()).isEqualTo("colors");
            assertThat(history).hasToString("history colors");
            Image out = g.create("recalled", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
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
            g.provide(OUT, out);
        }
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
        // Rebuilt: kept images, but the contents start over.
        graph.invalidate();
        assertThat(frame()[0]).isEqualTo(0xffffffff);
        assertThat(frame()[0]).isIn(RED, BLUE);
        rejects(List.of(OUT), g -> {
            g.history("x", 4, 4, VK_FORMAT_R8G8B8A8_UNORM);
            g.history("x", 4, 4, VK_FORMAT_R8G8B8A8_UNORM);
        }, "Two histories are named x");
        rejects(List.of(OUT), g -> {
            History history = g.history("x", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
            g.pass("scribble").write(history.previous(), Access.TRANSFER_DST).record(ctx -> { });
        }, "last frame's image is read only");
    }

    @Test
    void misuseIsReportedWithTheTechniquesInvolved() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FrameGraph(device, 0));
        // A key nobody provides.
        graph.add(new Halves(OUT, A, B)).output(OUT, output);
        assertThatIllegalStateException().isThrownBy(this::frame).withMessageContaining("No technique provides a");
        // Two providers of one key.
        graph.add(new Fill(A, SIZE, 1, 0, 0, 1)).add(new Fill(B, SIZE, 1, 0, 0, 1)).add(new Fill(A, SIZE, 1, 1, 1, 1));
        assertThatIllegalStateException().isThrownBy(this::frame).withMessageContaining("Two techniques provide a");
        Fill twice = new Fill(UNUSED, SIZE, 1, 1, 1, 1);
        graph.add(twice);
        assertThatIllegalArgumentException().isThrownBy(() -> graph.add(twice));
        rejects(List.of(OUT), g -> g.require(OUT), "cycle"); // requires its own result
        rejects(List.of(OUT), g -> { }, "did not provide [out]");
        rejects(List.of(OUT), g -> g.provide(A, g.create("x", 1, 1, VK_FORMAT_R8G8B8A8_UNORM)), "cannot provide a");
        rejects(List.of(OUT), g -> {
            Image image = g.create("x", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
            g.provide(OUT, image);
            g.provide(OUT, image);
        }, "provides out twice");
        rejects(List.of(OUT), g -> {
            Image foreign = g.require(A);
            g.pass("scribble").write(foreign, Access.TRANSFER_DST).record(ctx -> { });
        }, "results are written only by whoever makes them");
        rejects(List.of(OUT), g -> {
            Image image = g.create("x", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
            g.pass("early").read(image, Access.TRANSFER_SRC).write(image, Access.TRANSFER_DST).record(ctx -> { });
            g.provide(OUT, image);
        }, "in two layouts");
        rejects(List.of(OUT), g -> {
            Image image = g.create("x", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
            Image other = g.create("y", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
            g.pass("read").read(image, Access.TRANSFER_SRC).write(other, Access.TRANSFER_DST).record(ctx -> { });
            g.provide(OUT, other);
        }, "reads x before any pass writes it");
        rejects(List.of(OUT), g -> g.request(new Request<Image>() {
            @Override
            public Image build(GraphBuilder builder) {
                return builder.request(this);
            }
        }), "needs its own result");
        rejects(List.of(OUT), g -> g.create("x", 0, 1, VK_FORMAT_R8G8B8A8_UNORM), "must be positive");
        rejects(List.of(OUT), g -> g.create("x", 4, 4, VK_FORMAT_R8G8B8A8_UNORM, 4), "1 to 3 mip levels");
        rejects(List.of(OUT), g -> g.createLike("x", g.require(A), 0), "must be positive");
        rejects(List.of(OUT), g -> g.pass("x").read(g.require(A), Access.TRANSFER_DST), "declare it with write()");
        rejects(List.of(OUT), g -> g.pass("x").write(g.require(A), Access.TRANSFER_SRC), "declare it with read()");
        // Buffers.
        rejects(List.of(OUT), g -> g.createBuffer("x", 0), "must be positive");
        rejects(List.of(OUT), g -> {
            Buffer buffer = g.createBuffer("x", 64);
            Image image = g.create("y", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
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
            Image image = g.create("y", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM);
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
    private void rejects(List<Key> keys, java.util.function.Consumer<GraphBuilder> builder, String message) {
        graph.close();
        graph = new FrameGraph(device, 2).add(new Fill(A, SIZE, 1, 0, 0, 1)).add(new Lambda(keys, builder))
            .output(OUT, output);
        assertThatThrownBy(this::frame).isInstanceOf(RuntimeException.class).hasMessageContaining(message);
    }
}
