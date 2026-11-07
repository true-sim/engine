package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutLong;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.render.layout.IndirectLightLayout;
import com.truesim.engine.render.layout.ShadingHookBlockLayout;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Specialization;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A probe volume's own shading hook block, for its rays' hits (paths.slang's probe hits read it through the volume's
 * own indirect-light table): the hooks of the view the volume is built in, as that view's block has them (surface hooks
 * are the same in every view; indirect ones light the hits as they light the view), with the probe volume hook's
 * record its own (the volume lights its own further bounces, from its frame before) and on. Host-visible, its
 * anchor's ({@link RenderView#probeHooks}); written with the view's blocks, by the view's hook inputs' pass, before
 * the frame is submitted. Its pipelines are made with every hook of the listing, the block's bits choosing (a hook
 * added to the view later needs no new pipelines).
 */
final class ProbeHooks {

    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED;

    private final ShadingHooks hooks;
    private final int hook;
    private final RenderView anchor;
    private final ShadingHookBlock block;
    private final Buffer data;
    private final Camera camera;
    private final double coarsest;
    private final Specialization every;

    /**
     * The own block of a volume whose data is {@code data}, its origin {@code camera} snapped to {@code coarsest},
     * anchored on {@code anchor}: registered with the hooks of the view building in {@code g}.
     */
    @AllowAllocation("building the graph, when it changes")
    ProbeHooks(GraphBuilder g, RenderView anchor, Buffer data, Camera camera, double coarsest) {
        this.anchor = anchor;
        this.data = data;
        this.camera = camera;
        this.coarsest = coarsest;
        hooks = ShadingHooks.of(anchor.renderer().device());
        hook = hooks.require(ProbeVolume.HOOK);
        block = anchor.probeHooks();
        ShadingHooks.Set all = ShadingHooks.Set.NONE;
        for (int i = 0; i < hooks.count(); i++) {
            all = all.with(i);
        }
        every = hooks.specialization(all);
        g.request(new ShadingHookInputs(g.view())).probe(this);
    }

    /** A hit pipeline's constants: its shader's own ({@code own}), and every hook's switch on. */
    Specialization specialization(int... own) {
        return Specialization.of(own).with(every);
    }

    /** The volume's data, which the pass writing the block reads the address of. */
    Buffer data() {
        return data;
    }

    /** Points the volume's own table {@code table} at the block's copy for the frame. Allocates nothing. */
    void point(PassContext ctx, Buffer table) {
        try (var stack = stackPush()) {
            long address = stack.nmalloc(8, 8);
            memPutLong(address, block.address());
            Commands.updateBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(table), IndirectLightLayout.HOOKS, 8,
                address);
        }
    }

    /**
     * The block for the frame recording in {@code ctx}: {@code view}'s hooks composed, the volume's own record, the
     * anchor's camera. Allocates nothing.
     */
    void publish(PassContext ctx, ShadingHookInputs.Set view) {
        MemorySegment staging = block.staging();
        staging.copyFrom(hooks.defaultBlock());
        view.compose(ctx, staging);
        long record = staging.address() + hooks.recordOffset(hook);
        ProbeVolume.putParameters(record, ctx.address(data),
            anchor.position(0) - ProbeVolume.Volume.origin(camera.position(0), coarsest),
            anchor.position(1) - ProbeVolume.Volume.origin(camera.position(1), coarsest),
            anchor.position(2) - ProbeVolume.Volume.origin(camera.position(2), coarsest));
        long word = ShadingHooks.activeWord(hook);
        staging.set(U32, word, staging.get(U32, word) | 1 << (hook % 32));
        for (int axis = 0; axis < 3; axis++) {
            double p = anchor.position(axis);
            float high = (float) p;
            staging.set(ValueLayout.JAVA_FLOAT_UNALIGNED, ShadingHookBlockLayout.CAMERA + 4L * axis, high);
            staging.set(ValueLayout.JAVA_FLOAT_UNALIGNED, ShadingHookBlockLayout.CAMERA_LOW + 4L * axis,
                (float) (p - high));
        }
        block.publishAll();
    }
}
