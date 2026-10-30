package com.truesim.engine.vulkan;

import com.truesim.annotations.AllowAllocation;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Data the CPU writes for frames the GPU reads later, such as instances, views and lights: one host-visible copy per
 * frame in flight ({@link FrameSlots}), so writing the next frame never changes what a frame in flight reads. Writes
 * go to a master copy in CPU memory and, while a frame records, to that frame's copy; the other copies catch up from
 * the master when their slot opens. Values are little-endian, as shaders read them.
 *
 * <pre>{@code
 * FrameBuffer lights = FrameBuffer.create(device, 64L * capacity, usage, graph.frameSlots());
 * lights.putFloat(16, x);
 * pass.record(ctx -> push(lights.deviceAddress()));    // the copy of the frame being recorded
 * }</pre>
 *
 * <p>With {@link FrameSlots#single()} there is one copy and no master: writes go straight to the GPU. Allocates nothing
 * once created. Used from one thread at a time.
 */
@AllowAllocation("created rarely")
public final class FrameBuffer implements AutoCloseable {

    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong U64 = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final FrameSlots slots;
    private final long size;
    private final GpuBuffer[] copies;
    private final MemorySegment[] memory;
    private final Arena arena;
    /** The CPU's copy: the latest of every byte. With one slot and no master, the GPU copy itself. */
    private final MemorySegment master;
    /** Whether there is a master apart from the GPU copies, which writes keep in step. */
    private final boolean mirrored;
    /** Per slot, the range its copy missed: [dirtyStart, dirtyEnd), empty when start >= end. */
    private final long[] dirtyStart;
    private final long[] dirtyEnd;
    private boolean closed;

    private FrameBuffer(VulkanDevice device, long size, int usage, FrameSlots slots, int... families) {
        this.slots = slots;
        this.size = size;
        int count = slots.count();
        copies = new GpuBuffer[count];
        memory = new MemorySegment[count];
        dirtyStart = new long[count];
        dirtyEnd = new long[count];
        try {
            for (int s = 0; s < count; s++) {
                copies[s] = GpuBuffer.hostVisible(device, size, usage, families);
                memory[s] = copies[s].memory();
                memory[s].fill((byte) 0);
            }
        } catch (RuntimeException e) {
            for (GpuBuffer copy : copies) {
                if (copy != null) {
                    copy.close();
                }
            }
            throw e;
        }
        mirrored = !slots.isSingle();
        if (!mirrored) {
            arena = null;
            master = memory[0];
        } else {
            arena = Arena.ofShared();
            master = arena.allocate(size, 16);
            slots.register(this);
        }
    }

    /** {@code size} bytes of zeros per frame slot of {@code slots}, host-visible with {@code usage}. */
    public static FrameBuffer create(VulkanDevice device, long size, int usage, FrameSlots slots) {
        return create(device, size, usage, slots, new int[0]);
    }

    /**
     * Like {@link #create(VulkanDevice, long, int, FrameSlots)}, each copy used by the queues of {@code families} at
     * once (concurrent sharing), e.g. data the graphics queue and async compute both read.
     */
    public static FrameBuffer create(VulkanDevice device, long size, int usage, FrameSlots slots, int... families) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive: " + size);
        }
        return new FrameBuffer(device, size, usage, slots, families);
    }

    public long size() {
        return size;
    }

    public FrameSlots slots() {
        return slots;
    }

    public void putFloat(long offset, float value) {
        master.set(F32, offset, value);
        if (mirrored) {
            int current = slots.current();
            if (current >= 0) {
                memory[current].set(F32, offset, value);
            }
            missed(current, offset, 4);
        }
    }

    public void putInt(long offset, int value) {
        master.set(U32, offset, value);
        if (mirrored) {
            int current = slots.current();
            if (current >= 0) {
                memory[current].set(U32, offset, value);
            }
            missed(current, offset, 4);
        }
    }

    public void putLong(long offset, long value) {
        master.set(U64, offset, value);
        if (mirrored) {
            int current = slots.current();
            if (current >= 0) {
                memory[current].set(U64, offset, value);
            }
            missed(current, offset, 8);
        }
    }

    /** Copies {@code bytes} bytes from {@code source} at {@code sourceOffset} to {@code offset}. */
    public void put(long offset, MemorySegment source, long sourceOffset, long bytes) {
        MemorySegment.copy(source, sourceOffset, master, offset, bytes);
        if (mirrored) {
            int current = slots.current();
            if (current >= 0) {
                MemorySegment.copy(source, sourceOffset, memory[current], offset, bytes);
            }
            missed(current, offset, bytes);
        }
    }

    /**
     * Takes what {@code source} holds (its latest bytes, as many as both have), e.g. when a bigger buffer replaces it:
     * the frame recording gets them now, the other copies when their slots open.
     */
    public void copyFrom(FrameBuffer source) {
        put(0, source.master, 0, Math.min(size, source.size));
    }

    public float getFloat(long offset) {
        return master.get(F32, offset);
    }

    public int getInt(long offset) {
        return master.get(U32, offset);
    }

    public long getLong(long offset) {
        return master.get(U64, offset);
    }

    /**
     * The address of the copy shaders of the frame being recorded read: the open slot's, or between frames the one
     * opened last (slot 0 before the first frame).
     */
    public long deviceAddress() {
        return copies[copySlot()].deviceAddress();
    }

    /** The buffer of the copy {@link #deviceAddress()} names, e.g. for an indirect command or a copy. */
    public GpuBuffer buffer() {
        return copies[copySlot()];
    }

    /** Slot {@code slot}'s copy, e.g. for tests. */
    GpuBuffer copy(int slot) {
        return copies[slot];
    }

    private int copySlot() {
        int current = slots.current();
        if (current >= 0) {
            return current;
        }
        return Math.max(0, slots.last());
    }

    /** Every copy but the open slot's missed [offset, offset + bytes). */
    private void missed(int current, long offset, long bytes) {
        long end = offset + bytes;
        for (int s = 0; s < dirtyStart.length; s++) {
            if (s == current) {
                continue;
            }
            if (dirtyStart[s] >= dirtyEnd[s]) {
                dirtyStart[s] = offset;
                dirtyEnd[s] = end;
            } else {
                dirtyStart[s] = Math.min(dirtyStart[s], offset);
                dirtyEnd[s] = Math.max(dirtyEnd[s], end);
            }
        }
    }

    /** Slot {@code slot} opens: its copy takes the range it missed from the master. */
    void catchUp(int slot) {
        long start = dirtyStart[slot];
        long end = dirtyEnd[slot];
        if (start < end) {
            MemorySegment.copy(master, start, memory[slot], start, end - start);
            dirtyStart[slot] = 0;
            dirtyEnd[slot] = 0;
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (arena != null) {
                slots.unregister(this);
                arena.close();
            }
            for (GpuBuffer copy : copies) {
                copy.close();
            }
        }
    }
}
