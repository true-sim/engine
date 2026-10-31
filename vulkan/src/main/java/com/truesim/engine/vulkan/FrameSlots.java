package com.truesim.engine.vulkan;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * The frames in flight that {@link FrameBuffer}s keep a copy per: which copy the CPU writes now, and when each catches
 * up. A frame graph owns one and opens a slot when a frame begins, once the GPU has finished that slot's previous
 * frame, and closes it when the frame is submitted.
 *
 * <p>While a slot is open, writes go to the CPU's master copy and that slot's GPU copy, so even data written while
 * recording (a shadow table entry, a view's instance count) reaches the frame. Between frames no slot is open: writes
 * reach only the master, so the copies frames in flight still read are never touched. Every copy remembers the range
 * it missed and takes it from the master when its slot opens next.
 *
 * <pre>{@code
 * FrameSlots slots = graph.frameSlots();                      // the graph opens and closes slots
 * FrameBuffer instances = FrameBuffer.create(device, bytes, usage, slots);
 * instances.putFloat(offset, x);                              // any time: per frame, between frames, while recording
 * }</pre>
 *
 * <p>{@link #single()} is one copy, always open, with no master: writes go straight to the GPU, as for a plain
 * host-visible buffer, for users who wait for the GPU between frames. Used from one thread at a time.
 *
 * <p>Work deferred to the frame ({@link FrameUploads}' copies, say) registers a {@link FrameStart}: whoever records the
 * frame calls {@link #recordStart} first thing on the graphics queue (a frame graph does), before any pass reads what
 * it brings.
 */
@AllowAllocation("created rarely; buffers register when they are created")
public final class FrameSlots {

    private final int count;
    private final boolean single;
    private final List<FrameBuffer> buffers = new ArrayList<>();
    private final List<FrameStart> starts = new ArrayList<>();
    private int current = -1;
    private int last = -1;
    private long frames;

    /** {@code count} slots (frames in flight), none open. */
    public FrameSlots(int count) {
        this(count, false);
    }

    private FrameSlots(int count, boolean single) {
        if (count < 1) {
            throw new IllegalArgumentException("At least one frame slot: " + count);
        }
        this.count = count;
        this.single = single;
        if (single) {
            current = 0;
            last = 0;
        }
    }

    /** One copy, always open and with no master: writes go straight to the GPU, which must not be reading it then. */
    public static FrameSlots single() {
        return new FrameSlots(1, true);
    }

    /** How many copies each buffer keeps. */
    public int count() {
        return count;
    }

    /** Whether these are {@link #single()}: one copy, no frames counted, the caller waiting for the GPU between frames. */
    public boolean isSingle() {
        return single;
    }

    /**
     * Opens {@code slot} for the frame about to be recorded, once the GPU no longer reads its copies: each buffer's
     * copy first takes what it missed from the master. Allocates nothing.
     */
    public void begin(int slot) {
        if (slot < 0 || slot >= count) {
            throw new IllegalArgumentException("No frame slot " + slot + " of " + count);
        }
        if (single) {
            return;
        }
        for (int i = 0; i < buffers.size(); i++) {
            buffers.get(i).catchUp(slot);
        }
        current = slot;
        last = slot;
        frames++;
    }

    /** Closes the open slot: the frame was submitted, and its copies are the GPU's until the slot opens again. */
    public void end() {
        if (!single) {
            current = -1;
        }
    }

    /** The open slot, or -1 between frames. */
    public int current() {
        return current;
    }

    /** How many frames have begun. */
    public long frames() {
        return frames;
    }

    /**
     * For a GPU resource no longer used from now on (a mesh, a material, a texture slot), when it can be freed: pass
     * the result to {@link #isFinished}. Every frame that may still read it has begun by now, so it is free once
     * {@link #count()} more frames have begun, as beginning a frame waits for the frame that used its slot last.
     */
    public long retireAt() {
        return frames + count;
    }

    /**
     * Whether no frame in flight can read what {@link #retireAt()} was asked for, then: always for {@link #single()},
     * whose callers wait for the GPU between frames.
     */
    public boolean isFinished(long retireAt) {
        return single || frames >= retireAt;
    }

    /** The slot opened last (the open one while a frame records), or -1 before the first frame. */
    public int last() {
        return last;
    }

    /** Commands recorded at the start of each frame, before its passes, on the graphics queue. */
    @FunctionalInterface
    public interface FrameStart {
        /** Records what the frame needs first into {@code commands}; nothing if nothing is waiting. */
        void record(VkCommandBuffer commands);
    }

    /** Has {@code start} record at the start of every frame from now on, after those registered before. */
    public void register(FrameStart start) {
        starts.add(start);
    }

    /** Stops {@code start} recording. */
    public void unregister(FrameStart start) {
        starts.remove(start);
    }

    /**
     * Records every registered {@link FrameStart} into {@code commands}, the frame's first graphics commands, before its
     * passes. A frame graph calls it; record frames otherwise, and call it first. Allocates nothing.
     */
    public void recordStart(VkCommandBuffer commands) {
        for (int i = 0; i < starts.size(); i++) {
            starts.get(i).record(commands);
        }
    }

    void register(FrameBuffer buffer) {
        buffers.add(buffer);
    }

    void unregister(FrameBuffer buffer) {
        buffers.remove(buffer);
    }
}
