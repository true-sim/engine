package com.truesim.engine.render.particles;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.FrameBuffer;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * A table written field by field on the CPU, then handed to its {@link FrameBuffer} in one copy: a frame buffer's own
 * writes keep its master, the open slot's copy and the other slots' missed pages in step on every call (a lock each),
 * which costs about as much per field as the table's whole copy. Tables rewritten every frame (emitter records, spawn
 * records, CPU emitters' particles, a view's draws) are written here and {@link #copyTo copied} once. Grows to what it
 * is asked to hold; allocates nothing once it has.
 */
final class Staging {

    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private MemorySegment memory;

    /** A table of {@code bytes} to begin with. */
    @AllowAllocation("made once")
    Staging(long bytes) {
        memory = Arena.ofAuto().allocate(Math.max(bytes, 16), 16);
    }

    /** Makes room for {@code bytes}, keeping what it holds. */
    void room(long bytes) {
        if (memory.byteSize() < bytes) {
            grow(bytes);
        }
    }

    @AllowAllocation("a table growing")
    private void grow(long bytes) {
        MemorySegment larger = Arena.ofAuto().allocate(Math.max(bytes, 2 * memory.byteSize()), 16);
        MemorySegment.copy(memory, 0, larger, 0, memory.byteSize());
        memory = larger;
    }

    void putInt(long offset, int value) {
        memory.set(U32, offset, value);
    }

    void putFloat(long offset, float value) {
        memory.set(F32, offset, value);
    }

    /** Three floats from {@code offset} on. */
    void putVector(long offset, float x, float y, float z) {
        memory.set(F32, offset, x);
        memory.set(F32, offset + 4, y);
        memory.set(F32, offset + 8, z);
    }

    /** Copies its first {@code bytes} (if any) to the start of {@code target}, for the frame recording. */
    void copyTo(FrameBuffer target, long bytes) {
        if (bytes > 0) {
            target.put(0, memory, 0, bytes);
        }
    }
}
