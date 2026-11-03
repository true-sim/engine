package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.ExternalBuffer;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.render.layout.BlendPush;
import com.truesim.engine.render.layout.PackedPoseLayout;
import com.truesim.engine.render.layout.PoseLayout;
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
 * moved in its last step their pose there, once per step; each frame gives one blend factor ({@link #blend}), and a
 * compute pass after the scene upload ({@link SceneData}) writes every instance's pose that far between its pose at the
 * step before and at the last step into the instance table, before anything reads it. So frames between steps cost
 * the CPU nothing per instance, and what reads instances (culling, drawing, shadows, ray tracing) is unchanged.
 *
 * <pre>{@code
 * InstanceMotion motion = gpuScene.motion();
 * int slot = motion.add();                                   // a moving instance's slot
 * motion.beginStep(tick);                                    // once per frame that ran steps, before its writes
 * motion.write(motion.claim(slot, false), id, from..., to...); // after the step that moved it
 * motion.blend(clock.blend());                              // every frame
 * motion.remove(slot);                                      // it stopped: written as it is, drawn as written
 * }</pre>
 *
 * <p>Each slot has a pose in each of two buffers, which turn over every frame that ran steps: the last step's is
 * written, the step before's stays from the frame before, so a moving instance uploads one pose a step, and both only
 * in its first step or after steps it did not upload (D122 measured this against both poses each step). The poses are
 * kept as a {@link Layout} says: positions in two parts, or packed. Poses live in {@link RecordBuffer}s, scattered on
 * the GPU with the instances. The CPU instance write of an instance with a slot is overwritten by the blend each frame;
 * take its slot away first ({@link #remove}) to draw a pose written directly. Allocates nothing once created, except
 * to grow, or for a layout first used.
 */
@AllowAllocation("created rarely")
public final class InstanceMotion implements AutoCloseable {

    /** How a moving instance's poses are kept. */
    public enum Layout {
        /** Positions in two parts as instances keep them: 64 bytes a step, 128 kept. */
        PRECISE(PoseLayout.BYTES),
        /**
         * Packed: positions as floats from the {@link #origin} (within about a millimetre of the true one 10 km from
         * it), rotations as four snorm16, scales as one float where uniform, else three halves: 32 bytes a step, 64
         * kept.
         */
        PACKED(PackedPoseLayout.BYTES);

        private final int bytes;

        Layout(int bytes) {
            this.bytes = bytes;
        }

        /** Bytes of a pose: what a moving instance uploads a step (twice in its first step). */
        public int recordBytes() {
            return bytes;
        }

        /** GPU memory per moving instance: its pose at both steps. */
        public int gpuBytes() {
            return 2 * bytes;
        }
    }

    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat FLOAT =
        ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final int UNIFORM = 0x80000000;

    private final VulkanDevice device;
    private final FrameSlots slots;
    private final RecordBuffer[][] buffers = new RecordBuffer[Layout.values().length][]; // made as layouts are used
    private final ExternalBuffer firstGraph;
    private final ExternalBuffer secondGraph;
    private boolean inPasses;
    private Layout layout;
    private int capacity;
    private long[] used;
    private int count;
    private int highWater;
    private float blend;
    private long frames; // frames blended with slots live: what was built from the instances is stale each one
    // Which buffer holds the last step's poses, the step written, and per slot the step of its last pose
    private int parity;
    private long tick = Long.MIN_VALUE;
    private long[] lastTick;
    // This upload's claims: per claim its entries in the current and previous buffers (-1: none)
    private int[] currentEntry;
    private int[] previousEntry;
    private int claims;
    // The change lists of the buffers, made and to be made
    private RecordBuffer.ChangeList list = RecordBuffer.ChangeList.COPY;
    private long chunkBytes = RecordBuffer.CHUNK_BYTES;
    // The packed layout's origin
    private double originX;
    private double originY;
    private double originZ;

    InstanceMotion(VulkanDevice device, FrameSlots slots, int capacity, Layout layout) {
        this.device = device;
        this.slots = slots;
        this.capacity = capacity;
        this.layout = layout;
        RecordBuffer[] made = make(layout);
        firstGraph = new ExternalBuffer("instance motion", made[0].buffer());
        secondGraph = new ExternalBuffer("instance motion, other step", made[1].buffer());
        used = new long[(capacity + 63) / 64];
        lastTick = new long[capacity];
        currentEntry = new int[capacity];
        previousEntry = new int[capacity];
    }

    /** {@code kept}'s buffers, made if they are not, at the capacity. */
    @AllowAllocation("a layout first used")
    private RecordBuffer[] make(Layout kept) {
        RecordBuffer[] made = buffers[kept.ordinal()];
        if (made == null) {
            made = new RecordBuffer[2];
            for (int b = 0; b < 2; b++) {
                made[b] = new RecordBuffer(device, kept.bytes, capacity, slots);
                made[b].changeList(list, chunkBytes);
                if (inPasses) {
                    made[b].uploadInPasses();
                }
            }
            buffers[kept.ordinal()] = made;
        } else if (made[0].capacity() < capacity) {
            made[0].grow(capacity);
            made[1].grow(capacity);
        }
        return made;
    }

    /** The layout the poses are kept in. */
    public Layout layout() {
        return layout;
    }

    /**
     * Keeps the poses as {@code kept} says from now on. Only with no slot in use ({@link #count()} 0): take every
     * moving instance's slot away first. A layout first used makes its buffers; the graph rebuilds for them.
     */
    public InstanceMotion layout(Layout kept) {
        if (kept == layout) {
            return this;
        }
        if (count > 0) {
            throw new IllegalStateException("The layout changes with no slot in use: " + count + " are");
        }
        layout = kept;
        RecordBuffer[] made = make(kept);
        firstGraph.set(made[0].buffer());
        secondGraph.set(made[1].buffer());
        return this;
    }

    /**
     * The packed layout's origin: positions are kept as floats from it, within about a millimetre of the true one 10 km
     * from it. Only with no slot in use.
     */
    public InstanceMotion origin(double x, double y, double z) {
        if (count > 0) {
            throw new IllegalStateException("The origin moves with no slot in use: " + count + " are");
        }
        originX = x;
        originY = y;
        originZ = z;
        return this;
    }

    /** A free slot (the lowest), growing when there is none. Allocates nothing unless they grow. */
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
        int first = capacity; // every slot below it is in use
        grow();
        return take(first);
    }

    private int take(int slot) {
        used[slot >>> 6] |= 1L << slot;
        count++;
        highWater = Math.max(highWater, slot + 1);
        lastTick[slot] = Long.MIN_VALUE; // its first step uploads both poses
        return slot;
    }

    @AllowAllocation("growing, rarely")
    private void grow() {
        int grown = Math.max(64, 2 * capacity);
        for (RecordBuffer[] made : buffers) {
            if (made != null) {
                made[0].grow(grown);
                made[1].grow(grown);
            }
        }
        RecordBuffer[] current = buffers[layout.ordinal()];
        firstGraph.set(current[0].buffer());
        secondGraph.set(current[1].buffer());
        used = Arrays.copyOf(used, (grown + 63) / 64);
        lastTick = Arrays.copyOf(lastTick, grown);
        Arrays.fill(lastTick, capacity, grown, Long.MIN_VALUE);
        currentEntry = Arrays.copyOf(currentEntry, grown);
        previousEntry = Arrays.copyOf(previousEntry, grown);
        capacity = grown;
    }

    /**
     * A frame that ran steps writes the poses of step {@code step}, its last: claims and writes follow. The buffers turn
     * over: the last step's poses become the step before's. Allocates nothing.
     */
    public void beginStep(long step) {
        tick = step;
        claims = 0;
        parity ^= 1;
    }

    /**
     * Slot {@code slot}'s claim in this step's writes, for {@link #write}: claimed on one thread, in order, so that many
     * can be written on several. Claims the step before's pose too where the GPU does not hold it (the slot's first
     * step, or one after steps it did not upload), or where {@code restart} says the move starts afresh (a teleport).
     * Allocates nothing.
     */
    public int claim(int slot, boolean restart) {
        if (slot >= highWater || (used[slot >>> 6] & 1L << slot) == 0) {
            throw new IllegalArgumentException("No motion slot " + slot);
        }
        RecordBuffer[] made = buffers[layout.ordinal()];
        int c = claims++;
        currentEntry[c] = made[parity].claim(slot);
        previousEntry[c] = lastTick[slot] == tick - 1 && !restart ? -1 : made[parity ^ 1].claim(slot);
        lastTick[slot] = tick;
        return c;
    }

    /**
     * Writes claim {@code claim}: instance {@code instance} drawn between its pose at the step before (position
     * {@code fx, fy, fz}, rotation {@code fq*}, scale {@code fs*}) and at the last step ({@code t*}). Safe from any
     * thread for distinct claims, while nothing claims, removes or uploads. Allocates nothing.
     */
    public void write(int claim, int instance, double fx, double fy, double fz, float fqx, float fqy, float fqz,
        float fqw, float fsx, float fsy, float fsz, double tx, double ty, double tz, float tqx, float tqy, float tqz,
        float tqw, float tsx, float tsy, float tsz) {
        RecordBuffer[] made = buffers[layout.ordinal()];
        RecordBuffer current = made[parity];
        RecordBuffer previous = made[parity ^ 1];
        int before = previousEntry[claim];
        if (layout == Layout.PRECISE) {
            pose(current, currentEntry[claim], instance, tx, ty, tz, tqx, tqy, tqz, tqw, tsx, tsy, tsz);
            if (before >= 0) {
                pose(previous, before, instance, fx, fy, fz, fqx, fqy, fqz, fqw, fsx, fsy, fsz);
            }
        } else {
            packedPose(current, currentEntry[claim], instance, tx, ty, tz, tqx, tqy, tqz, tqw, tsx, tsy, tsz);
            if (before >= 0) {
                packedPose(previous, before, instance, fx, fy, fz, fqx, fqy, fqz, fqw, fsx, fsy, fsz);
            }
        }
    }

    private static void pose(RecordBuffer records, int entry, int instance, double x, double y, double z, float qx,
        float qy, float qz, float qw, float sx, float sy, float sz) {
        MemorySegment m = records.changes(entry);
        long o = records.offset(entry);
        m.set(FLOAT, o + PoseLayout.ROTATION, qx);
        m.set(FLOAT, o + PoseLayout.ROTATION + 4, qy);
        m.set(FLOAT, o + PoseLayout.ROTATION + 8, qz);
        m.set(FLOAT, o + PoseLayout.ROTATION + 12, qw);
        float hx = (float) x;
        float hy = (float) y;
        float hz = (float) z;
        vector(m, o + PoseLayout.POSITION, hx, hy, hz);
        m.set(INT, o + PoseLayout.INSTANCE, instance + 1);
        vector(m, o + PoseLayout.POSITION_LOW, (float) (x - hx), (float) (y - hy), (float) (z - hz));
        vector(m, o + PoseLayout.SCALE, sx, sy, sz);
    }

    private void packedPose(RecordBuffer records, int entry, int instance, double x, double y, double z, float qx,
        float qy, float qz, float qw, float sx, float sy, float sz) {
        MemorySegment m = records.changes(entry);
        long o = records.offset(entry);
        boolean uniform = sx == sy && sy == sz;
        vector(m, o + PackedPoseLayout.POSITION, (float) (x - originX), (float) (y - originY), (float) (z - originZ));
        m.set(INT, o + PackedPoseLayout.INSTANCE, (instance + 1) | (uniform ? UNIFORM : 0));
        m.set(INT, o + PackedPoseLayout.ROTATION, snorm16(qx) | snorm16(qy) << 16);
        m.set(INT, o + PackedPoseLayout.ROTATION + 4, snorm16(qz) | snorm16(qw) << 16);
        if (uniform) {
            m.set(FLOAT, o + PackedPoseLayout.SCALE, sx);
            m.set(INT, o + PackedPoseLayout.SCALE + 4, 0);
        } else {
            m.set(INT, o + PackedPoseLayout.SCALE, half(sx) | half(sy) << 16);
            m.set(INT, o + PackedPoseLayout.SCALE + 4, half(sz));
        }
    }

    private static void vector(MemorySegment m, long at, float x, float y, float z) {
        m.set(FLOAT, at, x);
        m.set(FLOAT, at + 4, y);
        m.set(FLOAT, at + 8, z);
    }

    /** {@code v} in [-1, 1] as a snorm16, in the low 16 bits. */
    static int snorm16(float v) {
        return Math.round(Math.max(-1, Math.min(1, v)) * 32767) & 0xffff;
    }

    private static int half(float v) {
        return Float.floatToFloat16(v) & 0xffff;
    }

    /** Frees slot {@code slot}: a hole from the next upload, its instance as last written. Allocates nothing. */
    public void remove(int slot) {
        if (slot >= highWater || (used[slot >>> 6] & 1L << slot) == 0) {
            return;
        }
        RecordBuffer[] made = buffers[layout.ordinal()];
        made[0].clear(slot);
        made[1].clear(slot);
        used[slot >>> 6] &= ~(1L << slot);
        count--;
        while (highWater > 0 && (used[(highWater - 1) >>> 6] & 1L << (highWater - 1)) == 0) {
            highWater--;
        }
    }

    /** Draws every slot's instance {@code factor} of the way from its pose at the step before to the last (0 to 1). */
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

    /** How many slots are in use. */
    public int count() {
        return count;
    }

    /** Slots up to the highest in use, holes included: the threads of the blend. */
    public int highWater() {
        return highWater;
    }

    /** Poses of the layout's changed since the last upload: what the next scatters. */
    public int pendingCount() {
        RecordBuffer[] made = buffers[layout.ordinal()];
        return made[0].pendingCount() + made[1].pendingCount();
    }

    /** Bytes the next upload scatters: each changed pose and its index. */
    public long pendingBytes() {
        return (long) pendingCount() * (layout.bytes + 4);
    }

    /** Changes with every frame blended while slots are in use. */
    long frames() {
        return frames;
    }

    ExternalBuffer firstGraph() {
        return firstGraph;
    }

    ExternalBuffer secondGraph() {
        return secondGraph;
    }

    /** The buffers' change lists from now on ({@link RecordBuffer#changeList}). */
    void changeList(RecordBuffer.ChangeList list, long chunkBytes) {
        this.list = list;
        this.chunkBytes = chunkBytes;
        for (RecordBuffer[] made : buffers) {
            if (made != null) {
                made[0].changeList(list, chunkBytes);
                made[1].changeList(list, chunkBytes);
            }
        }
    }

    long hostBytes() {
        long bytes = 0;
        for (RecordBuffer[] made : buffers) {
            bytes += made == null ? 0 : made[0].hostBytes() + made[1].hostBytes();
        }
        return bytes;
    }

    long pinnedBytes() {
        long bytes = 0;
        for (RecordBuffer[] made : buffers) {
            bytes += made == null ? 0 : made[0].pinnedBytes() + made[1].pinnedBytes();
        }
        return bytes;
    }

    long uploadNanos() {
        long nanos = 0;
        for (RecordBuffer[] made : buffers) {
            nanos += made == null ? 0 : made[0].uploadNanos() + made[1].uploadNanos();
        }
        return nanos;
    }

    void uploadInPasses() {
        inPasses = true;
        for (RecordBuffer[] made : buffers) {
            if (made != null) {
                made[0].uploadInPasses();
                made[1].uploadInPasses();
            }
        }
    }

    /** Uploads the layout's buffers, those the graph declares (another layout's changes wait until it is used again). */
    void uploadInPass(org.lwjgl.vulkan.VkCommandBuffer commands) {
        RecordBuffer[] made = buffers[layout.ordinal()];
        made[0].uploadInPass(commands);
        made[1].uploadInPass(commands);
    }

    /** The blend pass: a thread per slot, from the buffers at {@code first} and {@code second}, into the instances. */
    void record(PassContext ctx, long first, long second, long instances, Pipeline pipeline, BlendPush push) {
        int n = highWater;
        if (n == 0) {
            return;
        }
        float hx = (float) originX;
        float hy = (float) originY;
        float hz = (float) originZ;
        push.current(parity == 0 ? first : second).previous(parity == 0 ? second : first).instances(instances)
            .count(n).blend(blend).layout(layout.ordinal()).originHigh(hx, hy, hz)
            .originLow((float) (originX - hx), (float) (originY - hy), (float) (originZ - hz));
        ctx.dispatch(pipeline, push, (n + 63) / 64);
    }

    @Override
    public void close() {
        for (RecordBuffer[] made : buffers) {
            if (made != null) {
                made[0].close();
                made[1].close();
            }
        }
    }
}
