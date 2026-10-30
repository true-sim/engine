package com.truesim.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;

import com.truesim.common.graph.Id;
import com.truesim.engine.vulkan.Format;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The frame graph's compiler, without a device: culling, queues, placement into slots, history plans, barriers and
 * batches come from the declared accesses alone.
 */
class GraphCompilerTest {

    static final Key<Image> OUT = Key.image("test.out");
    static final Key<Image> COLOR = Key.image("test.color");

    /** A technique made of a lambda, providing {@code provides}. */
    record Lambda(List<Key<?>> provides, Consumer<GraphBuilder> builder) implements Technique {
        @Override
        public void build(GraphBuilder g) {
            builder.accept(g);
        }
    }

    private final List<String> copies = new ArrayList<>();

    private CompiledGraph compile(Map<Key<Image>, ExternalImage> outputs, boolean async, Technique... techniques) {
        GraphBuilder g = new GraphBuilder(null, List.of(techniques), Map.of()); // no device: none is used
        return GraphCompiler.compile(g, outputs, async, true, 4, (ctx, from, to) -> copies.add(from + " to " + to));
    }

    private static ExternalImage swapchain(int usage) {
        return new ExternalImage("swapchain", 64, 64, Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
    }

    private static List<String> names(CompiledGraph c) {
        List<String> names = new ArrayList<>();
        for (GraphBuilder.PassInfo pass : c.order()) {
            names.add(pass.name);
        }
        return names;
    }

    @Test
    void passesNothingNeedsAreCulledAndTransientImagesShareSlots() {
        Technique draw = new Lambda(List.of(OUT), g -> {
            Image a = g.create("a", ImageDesc.of(Format.R16G16B16A16_SFLOAT, 64, 64));
            Image unused = g.create("unused", ImageDesc.of(Format.R16G16B16A16_SFLOAT, 64, 64));
            Image b = g.create("b", ImageDesc.of(Format.R16G16B16A16_SFLOAT, 64, 64));
            Image out = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, 64, 64));
            g.pass("write a").write(a, Access.STORAGE_WRITE).record(ctx -> { });
            g.pass("write unused").write(unused, Access.STORAGE_WRITE).record(ctx -> { });
            g.pass("a to out").read(a, Access.SAMPLED_COMPUTE).write(out, Access.STORAGE_WRITE).record(ctx -> { });
            g.pass("write b").write(b, Access.STORAGE_WRITE).record(ctx -> { }); // after a's last use: a's slot
            g.pass("b to out").read(b, Access.SAMPLED_COMPUTE).write(out, Access.STORAGE_WRITE).record(ctx -> { });
            g.provide(OUT, out);
        });
        Map<Key<Image>, ExternalImage> outputs = new LinkedHashMap<>();
        outputs.put(OUT, swapchain(VK_IMAGE_USAGE_TRANSFER_DST_BIT));
        CompiledGraph c = compile(outputs, false, draw);
        // Sampled and storage images have bindless slots, which external images lack: the output is copied.
        assertThat(names(c)).containsExactly("write a", "a to out", "write b", "b to out", "output swapchain");
        assertThat(c.builtPasses()).isEqualTo(6);
        assertThat(c.imageSlots()).hasSize(2); // a and b in one slot, out in the other
        assertThat(c.targetOf()[0]).isEqualTo(c.targetOf()[2]);
        assertThat(c.targetOf()[1]).as("unused: culled, nowhere").isEqualTo(Integer.MIN_VALUE);
        assertThat(c.externals()).hasSize(1);
        assertThat(c.queues()).containsOnly(FrameGraph.GRAPHICS);
        // The first pass using the external image waits for it to be acquired: its own batch.
        assertThat(c.batches().queue()).containsExactly(FrameGraph.GRAPHICS, FrameGraph.GRAPHICS);
        assertThat(c.batches().acquire()).containsExactly(false, true);
    }

    @Test
    void outputsThatFitAreDrawnStraightIntoTheirTarget() {
        Technique draw = new Lambda(List.of(OUT), g -> {
            Image out = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, 64, 64));
            g.pass("draw").write(out, Access.COLOR_ATTACHMENT).record(ctx -> { });
            g.provide(OUT, out);
        });
        Map<Key<Image>, ExternalImage> outputs = Map.of(OUT, swapchain(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT));
        CompiledGraph c = compile(outputs, false, draw);
        assertThat(names(c)).containsExactly("draw");
        assertThat(c.imageSlots()).isEmpty();
        assertThat(c.targetOf()[0]).isEqualTo(~0); // the external image itself
        // Barriers: into the attachment layout before the pass, towards presenting after the frame.
        CompiledGraph.BarrierList barriers = c.barriers();
        assertThat(barriers.size()).isEqualTo(2);
        assertThat(barriers.start()).containsExactly(0, 1, 2);
        assertThat(barriers.oldLayout()[0]).isEqualTo(VK_IMAGE_LAYOUT_UNDEFINED);
        assertThat(barriers.newLayout()[0]).isEqualTo(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        assertThat(barriers.newLayout()[1]).isEqualTo(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        assertThat(barriers.kind()).containsOnly(FrameGraph.IMAGE);
    }

    @Test
    void asyncPassesRunInTheirOwnBatches() {
        Technique frame = new Lambda(List.of(OUT), g -> {
            Image ao = g.create("ao", ImageDesc.of(Format.R8G8B8A8_UNORM, 64, 64));
            Image out = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, 64, 64));
            g.pass("ssao").write(ao, Access.STORAGE_WRITE).async().record(ctx -> { });
            g.pass("shade").read(ao, Access.SAMPLED_FRAGMENT).write(out, Access.COLOR_ATTACHMENT).record(ctx -> { });
            g.provide(OUT, out);
        });
        Map<Key<Image>, ExternalImage> outputs = Map.of(OUT, swapchain(VK_IMAGE_USAGE_TRANSFER_DST_BIT));
        CompiledGraph on = compile(outputs, true, frame);
        assertThat(on.queues()[0]).isEqualTo(FrameGraph.COMPUTE);
        assertThat(on.batches().queue()).containsExactly(FrameGraph.COMPUTE, FrameGraph.GRAPHICS, FrameGraph.GRAPHICS);
        assertThat(on.batches().waitFor()[1]).as("shading waits for the compute batch").isEqualTo(1);
        assertThat(on.imageSlots().get(0).shared).as("used by both queues, two families").isTrue();
        CompiledGraph off = compile(outputs, false, frame);
        assertThat(off.queues()).containsOnly(FrameGraph.GRAPHICS);
        assertThat(off.batches().queue()).containsOnly(FrameGraph.GRAPHICS);
    }

    @Test
    void historiesArePlannedAfterTheSlots() {
        Technique remember = new Lambda(List.of(OUT), g -> {
            History history = g.history("colors", ImageDesc.of(Format.R8G8B8A8_UNORM, 64, 64));
            Image out = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, 64, 64));
            g.pass("recall").read(history.previous(), Access.SAMPLED_FRAGMENT)
                .write(out, Access.COLOR_ATTACHMENT).record(ctx -> { });
            g.pass("remember").write(history.current(), Access.TRANSFER_DST).record(ctx -> { });
            g.provide(OUT, out);
        });
        Map<Key<Image>, ExternalImage> outputs = Map.of(OUT, swapchain(VK_IMAGE_USAGE_TRANSFER_DST_BIT));
        CompiledGraph c = compile(outputs, false, remember);
        assertThat(c.histories()).hasSize(1);
        GraphCompiler.HistoryPlan plan = c.histories().get(0);
        assertThat(plan.name()).isEqualTo("colors");
        assertThat(plan.current()).isEqualTo(c.imageSlots().size());
        assertThat(plan.previous()).isEqualTo(c.imageSlots().size() + 1);
        assertThat(c.physicalCount()).isEqualTo(c.imageSlots().size() + 2);
        // Each frame starts with last frame's image where this frame's left it, and ends with the current written.
        assertThat(c.historyStartPrevious()[0]).isEqualTo(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        assertThat(c.historyEndCurrent()[0]).isEqualTo(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        assertThat(c.historyStartCurrent()[0]).isEqualTo(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    /** A modifier of {@code key} with an id and an order, painting a pass named after itself. */
    record Modifier(Id<Modifier> id, Order order, Key<Image> key) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(key);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.modify(key);
            g.pass(id.name()).write(image, Access.TRANSFER_DST).record(ctx -> { });
        }
    }

    private static Id<Modifier> id(String name) {
        return Id.of("test." + name, Modifier.class);
    }

    @Test
    void modifiersRunByAnchorThenAfterAndBeforeElseAsAdded() {
        Id<Modifier> grade = id("grade");
        Id<Modifier> bloom = id("bloom");
        Id<Modifier> exposure = id("exposure");
        Id<Modifier> ui = id("ui");
        Id<Modifier> absent = id("absent");
        Technique base = new Lambda(List.of(OUT), g -> {
            Image out = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, 64, 64));
            g.pass("base").write(out, Access.TRANSFER_DST).record(ctx -> { });
            g.provide(OUT, out);
        });
        Map<Key<Image>, ExternalImage> outputs = Map.of(OUT, swapchain(VK_IMAGE_USAGE_TRANSFER_DST_BIT));
        CompiledGraph c = compile(outputs, false, base,
            new Modifier(ui, Order.at(Anchor.UI), OUT), // added first, runs last: its anchor is the latest
            new Modifier(grade, Order.at(Anchor.POST).after(bloom), OUT),
            new Modifier(bloom, Order.at(Anchor.POST).after(absent), OUT), // absent: ignored
            new Modifier(exposure, Order.at(Anchor.POST).before(bloom), OUT));
        assertThat(names(c)).containsExactly("base", "test.exposure", "test.bloom", "test.grade", "test.ui");
        assertThat(Anchor.POST).hasToString("anchor post");
        assertThat(Order.NONE.anchor()).isEqualTo(Anchor.FIRST);
        // Contradictions and two techniques of one id are errors.
        assertThatIllegalStateException().isThrownBy(() -> compile(outputs, false, base,
            new Modifier(grade, Order.at(Anchor.POST).after(bloom), OUT),
            new Modifier(bloom, Order.at(Anchor.POST).after(grade), OUT)))
            .withMessage("The orders of techniques contradict each other: [Modifier, Modifier]");
        assertThatIllegalStateException().isThrownBy(() -> compile(outputs, false, base,
            new Modifier(grade, Order.NONE, OUT), new Modifier(grade, Order.NONE, OUT)))
            .withMessageStartingWith("Two techniques have the id test.grade");
    }

    @Test
    void mipLevelsHaveBarriersOfTheirOwn() {
        Technique chain = new Lambda(List.of(OUT), g -> {
            Image pyramid = g.create("pyramid", ImageDesc.of(com.truesim.engine.vulkan.Format.R16G16B16A16_SFLOAT, 8, 8).mips(3));
            g.pass("level 0").write(pyramid.mip(0), Access.STORAGE_WRITE).record(ctx -> { });
            g.pass("level 1").read(pyramid.mip(0), Access.STORAGE_READ).write(pyramid.mip(1), Access.STORAGE_WRITE)
                .record(ctx -> { });
            g.pass("level 2").read(pyramid.mip(1), Access.STORAGE_READ).write(pyramid.mip(2), Access.STORAGE_WRITE)
                .record(ctx -> { });
            Image out = g.create("out", ImageDesc.of(com.truesim.engine.vulkan.Format.R8G8B8A8_UNORM, 64, 64));
            g.pass("use").read(pyramid, Access.SAMPLED_COMPUTE).write(out, Access.STORAGE_WRITE).record(ctx -> { });
            g.provide(OUT, out);
        });
        CompiledGraph c = compile(Map.of(OUT, swapchain(VK_IMAGE_USAGE_TRANSFER_DST_BIT)), false, chain);
        CompiledGraph.BarrierList b = c.barriers();
        // Level 1 waits for level 0's write of mip 0 (same layout: a memory barrier) and starts mip 1 alone.
        int level1 = b.start()[1];
        assertThat(b.start()[2] - level1).isEqualTo(2);
        assertThat(b.baseMip()[level1]).isZero();
        assertThat(b.oldLayout()[level1]).isEqualTo(b.newLayout()[level1]); // GENERAL: visibility only
        assertThat(b.baseMip()[level1 + 1]).isEqualTo(1);
        assertThat(b.oldLayout()[level1 + 1]).isEqualTo(VK_IMAGE_LAYOUT_UNDEFINED);
        assertThat(b.mipCount()[level1 + 1]).isEqualTo(1);
        // Sampling the whole pyramid after: one barrier for its three levels, alike.
        int use = b.start()[3];
        assertThat(b.baseMip()[use]).isZero();
        assertThat(b.mipCount()[use]).isEqualTo(3);
        assertThat(b.newLayout()[use]).isEqualTo(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    @Test
    void misuseIsReportedWithoutADevice() {
        Technique reader = new Lambda(List.of(OUT), g -> {
            Image image = g.create("x", ImageDesc.of(Format.R8G8B8A8_UNORM, 64, 64));
            Image out = g.create("out", ImageDesc.of(Format.R8G8B8A8_UNORM, 64, 64));
            g.pass("read early").read(image, Access.TRANSFER_SRC).write(out, Access.TRANSFER_DST).record(ctx -> { });
            g.provide(OUT, out);
        });
        Map<Key<Image>, ExternalImage> outputs = Map.of(OUT, swapchain(VK_IMAGE_USAGE_TRANSFER_DST_BIT));
        assertThatIllegalStateException().isThrownBy(() -> compile(outputs, false, reader))
            .withMessageContaining("reads x before any pass writes it");
        assertThatIllegalStateException().isThrownBy(() -> compile(outputs, false))
            .withMessage("No technique provides test.out");
    }
}
