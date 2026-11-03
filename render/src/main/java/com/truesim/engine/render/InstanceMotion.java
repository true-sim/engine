package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.ExternalBuffer;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.render.layout.BlendPush;
import com.truesim.engine.render.layout.MotionLayout;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.RecordBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Instances drawn between two poses (application-api.md D91): a world stepping at a fixed rate gives the instances it
 * moved in its last step their pose at the step before and at the last step ({@link #set}), once per step; each frame
 * gives one blend factor ({@link #blend}), and a compute pass after the scene upload ({@link SceneData}) writes every
 * instance's pose that far between the two into the instance table, before anything reads it. So frames between steps
 * cost the CPU nothing per instance, and what reads instances (culling, drawing, shadows, ray tracing) is unchanged.
 *
 * <pre>{@code
 * InstanceMotion motion = gpuScene.motion();
 * int slot = motion.add();                                   // a moving instance's record
 * motion.set(slot, id, from..., to...);                      // after a step that moved it
 * motion.blend(clock.blend());                              // every frame
 * motion.remove(slot);                                      // it stopped: written as it is, drawn as written
 * }</pre>
 *
 * <p>Records live in a {@link RecordBuffer}: setting one writes its {@value #RECORD_BYTES} bytes once into the changes,
 * scattered on the GPU with the instances. The CPU instance write of an instance with a record is overwritten by the
 * blend each frame; take its record away first ({@link #remove}) to draw a pose written directly. Allocates nothing
 * once created, except to grow the records.
 */
@AllowAllocation("created rarely")
public final class InstanceMotion implements AutoCloseable {

    /** Bytes per record: the GPU memory per moving instance. */
    public static final int RECORD_BYTES = MotionLayout.BYTES;
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat FLOAT =
        ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final RecordBuffer records;
    private final ExternalBuffer graphBuffer;
    private int capacity;
    private long[] used;
    private int count;
    private int highWater;
    private float blend;
    private long frames; // frames blended with records live: what was built from the instances is stale each one

    InstanceMotion(VulkanDevice device, FrameSlots slots, int capacity) {
        this.capacity = capacity;
        records = new RecordBuffer(device, RECORD_BYTES, capacity, slots);
        graphBuffer = new ExternalBuffer("instance motion", records.buffer());
        used = new long[(capacity + 63) / 64];
    }

    /** A free record (the lowest), growing the records when there is none. Allocates nothing unless they grow. */
    public int add() {
        for (int w = 0; w < used.length; w++) {
            long free = ~used[w];
            if (free != 0) {
                int slot = (w << 6) + Long.numberOfTrailingZeros(free);
                if (slot < capacity) {
                    return take(slot);
                }
            }
        }
        int first = capacity; // every record below it is in use
        grow();
        return take(first);
    }

    private int take(int slot) {
        used[slot >>> 6] |= 1L << slot;
        count++;
        highWater = Math.max(highWater, slot + 1);
        return slot;
    }

    @AllowAllocation("growing, rarely")
    private void grow() {
        int grown = Math.max(64, 2 * capacity);
        records.grow(grown);
        used = Arrays.copyOf(used, (grown + 63) / 64);
        capacity = grown;
        graphBuffer.set(records.buffer());
    }

    /**
     * Record {@code slot}: instance {@code instance} drawn between its pose at the step before (position {@code fx,
     * fy, fz}, rotation {@code fq*}, scale {@code fs*}) and at the last step ({@code t*}), by the blend factor of each
     * frame from the next upload on. Allocates nothing.
     */
    public void set(int slot, int instance, double fx, double fy, double fz, float fqx, float fqy, float fqz, float fqw,
        float fsx, float fsy, float fsz, double tx, double ty, double tz, float tqx, float tqy, float tqz, float tqw,
        float tsx, float tsy, float tsz) {
        if (slot >= highWater || (used[slot >>> 6] & 1L << slot) == 0) {
            throw new IllegalArgumentException("No motion record " + slot);
        }
        MemorySegment m = records.changes();
        long o = records.offset(records.claim(slot));
        m.set(FLOAT, o + MotionLayout.ROTATION_FROM, fqx);
        m.set(FLOAT, o + MotionLayout.ROTATION_FROM + 4, fqy);
        m.set(FLOAT, o + MotionLayout.ROTATION_FROM + 8, fqz);
        m.set(FLOAT, o + MotionLayout.ROTATION_FROM + 12, fqw);
        m.set(FLOAT, o + MotionLayout.ROTATION_TO, tqx);
        m.set(FLOAT, o + MotionLayout.ROTATION_TO + 4, tqy);
        m.set(FLOAT, o + MotionLayout.ROTATION_TO + 8, tqz);
        m.set(FLOAT, o + MotionLayout.ROTATION_TO + 12, tqw);
        float hx = (float) fx;
        float hy = (float) fy;
        float hz = (float) fz;
        m.set(FLOAT, o + MotionLayout.POSITION, hx);
        m.set(FLOAT, o + MotionLayout.POSITION + 4, hy);
        m.set(FLOAT, o + MotionLayout.POSITION + 8, hz);
        m.set(INT, o + MotionLayout.INSTANCE, instance + 1);
        m.set(FLOAT, o + MotionLayout.POSITION_LOW, (float) (fx - hx));
        m.set(FLOAT, o + MotionLayout.POSITION_LOW + 4, (float) (fy - hy));
        m.set(FLOAT, o + MotionLayout.POSITION_LOW + 8, (float) (fz - hz));
        m.set(FLOAT, o + MotionLayout.MOVE, (float) (tx - fx));
        m.set(FLOAT, o + MotionLayout.MOVE + 4, (float) (ty - fy));
        m.set(FLOAT, o + MotionLayout.MOVE + 8, (float) (tz - fz));
        m.set(FLOAT, o + MotionLayout.SCALE_FROM, fsx);
        m.set(FLOAT, o + MotionLayout.SCALE_FROM + 4, fsy);
        m.set(FLOAT, o + MotionLayout.SCALE_FROM + 8, fsz);
        m.set(FLOAT, o + MotionLayout.SCALE_TO, tsx);
        m.set(FLOAT, o + MotionLayout.SCALE_TO + 4, tsy);
        m.set(FLOAT, o + MotionLayout.SCALE_TO + 8, tsz);
    }

    /** Frees record {@code slot}: a hole from the next upload, its instance as last written. Allocates nothing. */
    public void remove(int slot) {
        if (slot >= highWater || (used[slot >>> 6] & 1L << slot) == 0) {
            return;
        }
        records.clear(slot);
        used[slot >>> 6] &= ~(1L << slot);
        count--;
        while (highWater > 0 && (used[(highWater - 1) >>> 6] & 1L << (highWater - 1)) == 0) {
            highWater--;
        }
    }

    /** Draws every record's instance {@code factor} of the way from its pose at the step before to the last (0 to 1). */
    public void blend(double factor) {
        blend = (float) Math.max(0, Math.min(1, factor));
        if (count > 0) {
            frames++;
        }
    }

    /** The blend factor of the frame. */
    public double blendFactor() {
        return blend;
    }

    /** How many records are in use. */
    public int count() {
        return count;
    }

    /** Records up to the highest in use, holes included: the threads of the blend. */
    public int highWater() {
        return highWater;
    }

    /** Records changed since the last upload: what the next scatters, {@value #RECORD_BYTES} bytes each. */
    public int pendingCount() {
        return records.pendingCount();
    }

    /** Changes with every frame blended while records are in use. */
    long frames() {
        return frames;
    }

    ExternalBuffer graphBuffer() {
        return graphBuffer;
    }

    void uploadInPasses() {
        records.uploadInPasses();
    }

    void uploadInPass(org.lwjgl.vulkan.VkCommandBuffer commands) {
        records.uploadInPass(commands);
    }

    /** The blend pass: a thread per record, into the instance table at {@code instances}. */
    void record(PassContext ctx, long motions, long instances, Pipeline pipeline, BlendPush push) {
        int n = highWater;
        if (n == 0) {
            return;
        }
        push.motions(motions).instances(instances).count(n).blend(blend);
        ctx.dispatch(pipeline, push, (n + 63) / 64);
    }

    @Override
    public void close() {
        records.close();
    }
}
