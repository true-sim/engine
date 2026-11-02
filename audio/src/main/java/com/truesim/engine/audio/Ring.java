package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A single-producer single-consumer ring of fixed-size records (AU2: the audio thread takes no lock): an opcode, a
 * slot, a frame stamp, a tag, eight doubles and two object references. The producer fills the record at
 * {@link #claim()} then {@link #publish()}es it; the consumer reads at {@link #peek()} then {@link #release()}s.
 * The indices are ordered by release and acquire, so a record is complete when seen. Allocates nothing once made.
 */
final class Ring {

    private static final VarHandle HEAD;
    private static final VarHandle TAIL;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            HEAD = lookup.findVarHandle(Ring.class, "head", long.class);
            TAIL = lookup.findVarHandle(Ring.class, "tail", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static final int VALUES = 8;

    private final int mask;
    final int[] opcodes;
    final int[] slots;
    final long[] frames;
    final long[] tags;
    final double[] values;
    final Object[] refs;
    final Object[] refs2;
    @SuppressWarnings("unused") // through HEAD
    private volatile long head;
    @SuppressWarnings("unused") // through TAIL
    private volatile long tail;
    private long overflows;

    @AllowAllocation("made once")
    Ring(int capacity) {
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("A ring's capacity is a power of two: " + capacity);
        }
        mask = capacity - 1;
        opcodes = new int[capacity];
        slots = new int[capacity];
        frames = new long[capacity];
        tags = new long[capacity];
        values = new double[capacity * VALUES];
        refs = new Object[capacity];
        refs2 = new Object[capacity];
    }

    int capacity() {
        return mask + 1;
    }

    /** Producer: the index of a free record to fill, or -1 when full (counted as an overflow). */
    int claim() {
        long t = (long) TAIL.getOpaque(this);
        if (t - (long) HEAD.getAcquire(this) > mask) {
            overflows++;
            return -1;
        }
        return (int) (t & mask);
    }

    /** Producer: makes the record claimed visible to the consumer. */
    void publish() {
        TAIL.setRelease(this, (long) TAIL.getOpaque(this) + 1);
    }

    /** Consumer: the index of the next record, or -1 when empty. */
    int peek() {
        long h = (long) HEAD.getOpaque(this);
        return h == (long) TAIL.getAcquire(this) ? -1 : (int) (h & mask);
    }

    /** Consumer: frees the record peeked, dropping its reference. */
    void release() {
        long h = (long) HEAD.getOpaque(this);
        refs[(int) (h & mask)] = null;
        refs2[(int) (h & mask)] = null;
        HEAD.setRelease(this, h + 1);
    }

    /** How many records waited: for meters and tests. */
    int size() {
        return (int) ((long) TAIL.getAcquire(this) - (long) HEAD.getAcquire(this));
    }

    /** Records the producer found no room for. */
    long overflows() {
        return overflows;
    }
}
