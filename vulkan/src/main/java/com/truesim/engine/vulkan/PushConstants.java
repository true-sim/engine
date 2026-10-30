package com.truesim.engine.vulkan;

import com.truesim.annotations.AllowAllocation;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Push constant bytes, as {@link Pipeline#push} sets them: written field by field in order ({@link Push}), or each field
 * at its offset by a writer generated from the shader's push struct (build-logic's {@code truesim.slang}), which knows
 * the layout and the field types, so nothing is counted by hand. Made once and refilled each time a pass records, so
 * recording allocates nothing. Holds {@value #MAX_BYTES} bytes, the most push constants every device takes. Use it from
 * one thread at a time.
 */
@AllowAllocation("made once")
public abstract class PushConstants {

    /** The push constant bytes every Vulkan device takes. */
    public static final int MAX_BYTES = 128;

    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat FLOAT =
        ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    final MemorySegment memory = Arena.ofAuto().allocate(MAX_BYTES, 16);

    protected PushConstants() {
    }

    /** The bytes to set. */
    public abstract int size();

    /** The native address of the bytes, for {@code vkCmdPushConstants}. */
    public final long address() {
        return memory.address();
    }

    /** The bytes, e.g. to inspect. */
    public final MemorySegment memory() {
        return memory.asSlice(0, size());
    }

    /** Writes the 32-bit integer {@code value} at byte {@code offset}. */
    protected final void putInt(long offset, int value) {
        memory.set(INT, offset, value);
    }

    /** Writes the 32-bit float {@code value} at byte {@code offset}. */
    protected final void putFloat(long offset, float value) {
        memory.set(FLOAT, offset, value);
    }

    /** Writes the 64-bit integer (or device address) {@code value} at byte {@code offset}. */
    protected final void putLong(long offset, long value) {
        memory.set(LONG, offset, value);
    }
}
