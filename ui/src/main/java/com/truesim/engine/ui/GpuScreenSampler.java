package com.truesim.engine.ui;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Readback;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Keys;
import com.truesim.engine.ui.layout.SamplePush;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.ui.core.input.ScreenSample;
import com.truesim.ui.core.input.ScreenSampler;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/**
 * The eyedropper's sampler (ui-core's {@link ScreenSampler}) over a view's scene-referred color
 * ({@link Keys#SCENE_COLOR}: linear, before the display transform, so values above 1 are kept): a technique of the
 * view, last among the scene color's modifiers, which each frame a request stands copies the block of up to 31 x 31
 * texels around the asked-for UI pixel into a small buffer (a compute pass, {@code ui/sample.slang}) read back by the
 * graph ({@link GraphBuilder#readback}). The answer arrives when the GPU has finished that frame, frames in flight
 * later; nothing waits for it, and {@link #poll} hands over the newest. Each block carries the number of the frame's
 * copy and the point it was asked for, so an older block is never taken over a newer one (a still pointer over a
 * moving scene gets a new block each frame). With no request standing (or after {@link #stop}) the pass records
 * nothing.
 *
 * <p>UI pixels map to the scene color by the view's size (the UI's) and the render region (a render scale or dynamic
 * resolution's region); texels outside the region read black. It samples the scene behind the UI, not the UI.
 *
 * <pre>{@code
 * GpuScreenSampler sampler = new GpuScreenSampler();
 * view.add(sampler);                                   // the UI plugin does it for its view
 * controls.screenSampler(sampler);                     // colour pickers show their eyedropper
 * }</pre>
 *
 * <p>Use it on the thread that runs the frames. Requests and polls allocate nothing.
 */
public final class GpuScreenSampler implements ScreenSampler, Technique {

    /** Its id. */
    public static final Id<GpuScreenSampler> ID = Id.of("truesim.ui.screenSampler", GpuScreenSampler.class);
    /** Where it runs among the scene color's modifiers: after every other one. */
    static final Anchor LAST = new Anchor("screen sampler", 1000);

    private static final int HEADER = 2; // float4s before the block
    private static final int MARK = 0x53414d50; // sample.slang's MARK
    private static final long BYTES = (HEADER + (long) MAX_SIZE * MAX_SIZE) * 16;

    private final SamplePush push = new SamplePush();
    private final ScreenSample newest = new ScreenSample();
    private final Readback receiver = this::receive;
    private boolean built;
    private boolean standing;   // a request stands: the pass copies
    private float x;
    private float y;
    private int size;
    private int issued;         // the number of the last block copied (one a frame while a request stands)
    private int answered;       // the last answered request's number
    private long answers;       // answers taken, numbering them for ScreenSample
    private ViewPort view;

    /** A sampler; add it to a view to start it. */
    @AllowAllocation("made once")
    public GpuScreenSampler() {
        // fields only
    }

    @Override
    public Id<? extends Technique> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR); // what frames always make: a technique providing nothing is never built
    }

    @Override
    public Order order() {
        return Order.at(LAST);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Pipeline pipeline = g.computeProgram("shaders/ui/sample.spv", "sampleMain", SamplePush.BYTES);
        Image scene = g.modify(Keys.SCENE_COLOR);
        Buffer block = g.persistentBuffer("screen sample", BYTES);
        ViewPort shown = g.view();
        g.pass("screen sample").read(scene, Access.SAMPLED_COMPUTE).write(block, BufferAccess.STORAGE_WRITE)
            .record(ctx -> record(ctx, pipeline, scene, block, shown));
        g.readback("screen sample readback", block, 0, BYTES, receiver);
    }

    /** The frame's copy of the block asked for; the first frame recorded makes requests possible. */
    private void record(PassContext ctx, Pipeline pipeline, Image scene, Buffer block, ViewPort shown) {
        view = shown;
        built = true;
        if (!standing) {
            return;
        }
        int regionWidth = ctx.regionWidth(scene);
        int regionHeight = ctx.regionHeight(scene);
        float toSceneX = view == null || view.width() <= 0 ? 1 : (float) regionWidth / view.width();
        float toSceneY = view == null || view.height() <= 0 ? 1 : (float) regionHeight / view.height();
        push.target(ctx.address(block)).source(ctx.sampled(scene))
            .centerX((int) Math.floor(x * toSceneX)).centerY((int) Math.floor(y * toSceneY)).size(size)
            .regionWidth(regionWidth).regionHeight(regionHeight).uiX(x).uiY(y).sequence(++issued);
        ctx.dispatch(pipeline, push, (size + 7) / 8, (size + 7) / 8, 1);
    }

    /** A block the GPU wrote, frames later: taken when it answers a request newer than the last answered. */
    private void receive(MemorySegment data, long frame) {
        int sequence = data.get(ValueLayout.JAVA_INT_UNALIGNED, 12);
        if (data.get(ValueLayout.JAVA_INT_UNALIGNED, 16) != MARK
            || data.get(ValueLayout.JAVA_INT_UNALIGNED, 20) != ~sequence
            || sequence - answered <= 0 || sequence - issued > 0) {
            return; // nothing written yet, or an answer to a request older than one answered
        }
        int blockSize = data.get(ValueLayout.JAVA_INT_UNALIGNED, 8);
        if (blockSize < 1 || blockSize > MAX_SIZE) {
            return;
        }
        answered = sequence;
        newest.set(++answers, data.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 0),
            data.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 4), blockSize);
        long at = HEADER * 16L;
        for (int row = 0; row < blockSize; row++) {
            for (int column = 0; column < blockSize; column++) {
                newest.pixel(column, row, data.get(ValueLayout.JAVA_FLOAT_UNALIGNED, at),
                    data.get(ValueLayout.JAVA_FLOAT_UNALIGNED, at + 4), data.get(ValueLayout.JAVA_FLOAT_UNALIGNED, at + 8));
                at += 16;
            }
        }
    }

    /**
     * Asks for the block around UI pixel {@code px, py} from the next frame on; false before a frame of the view it
     * was added to recorded, or when the point is outside the view.
     */
    @Override
    public boolean request(float px, float py, int blockSize) {
        if (blockSize < 1 || blockSize > MAX_SIZE || (blockSize & 1) == 0) {
            throw new IllegalArgumentException("an odd block size up to " + MAX_SIZE + ": " + blockSize);
        }
        if (!built || view != null && (px < 0 || py < 0 || px >= view.width() || py >= view.height())) {
            return false;
        }
        standing = true;
        x = px;
        y = py;
        size = blockSize;
        return true;
    }

    @Override
    public boolean poll(ScreenSample out) {
        if (newest.sequence() == 0 || newest.sequence() <= out.sequence()) {
            return false;
        }
        out.copy(newest);
        return true;
    }

    @Override
    public void stop() {
        standing = false;
    }

    /** Whether a request stands (the pass copies each frame). */
    public boolean sampling() {
        return standing;
    }
}
