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

import com.truesim.engine.vulkan.Frames;
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
            instance = VulkanInstance.builder().applicationName("tests").validation(true).surfaces(false).build();
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

    @Test
    void recordingAllocatesNothing() {
        graph.add(new Fill(A, SIZE, 1, 0, 0, 1))
            .add(new Fill(B, SIZE, 0, 1, 0, 1))
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
        rejects(List.of(OUT), g -> g.createLike("x", g.require(A), 0), "must be positive");
        rejects(List.of(OUT), g -> g.pass("x").read(g.require(A), Access.TRANSFER_DST), "declare it with write()");
        rejects(List.of(OUT), g -> g.pass("x").write(g.require(A), Access.TRANSFER_SRC), "declare it with read()");
        // An external image used beyond its usage, or sampled.
        rejects(List.of(OUT), g -> {
            Image image = g.external(output);
            g.pass("draw").write(image, Access.COLOR_ATTACHMENT).sideEffects().record(ctx -> { });
            g.provide(OUT, g.require(A));
        }, "beyond the usage");
        assertThatIllegalArgumentException().isThrownBy(() -> output.resize(0, 1));
        graph.close();
        graph = new FrameGraph(device, 2).add(new Lambda(List.of(OUT), g -> g.provide(OUT, null))).output(OUT, output);
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
