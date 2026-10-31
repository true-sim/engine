package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * Ranges of a buffer handed out first fit and given back for reuse, merged with free neighbors: where a
 * {@link GeometryPool} puts each mesh's vertices, meshlets and triangles. Deterministic: the same calls give the same
 * ranges. Units are whatever the caller counts in (vertices, meshlets, bytes).
 */
@AllowAllocation("growing the free list, when meshes are added or removed")
final class RangeAllocator {

    private long capacity;
    // Free ranges, sorted by start, never touching (touching ones are merged).
    private long[] starts = new long[8];
    private long[] lengths = new long[8];
    private int count;
    private long free;

    RangeAllocator(long capacity) {
        this.capacity = capacity;
        if (capacity > 0) {
            starts[0] = 0;
            lengths[0] = capacity;
            count = 1;
        }
        free = capacity;
    }

    long capacity() {
        return capacity;
    }

    /** Grows to {@code capacity}: the new room is free, joined to a free range at the old end. */
    void grow(long capacity) {
        if (capacity <= this.capacity) {
            throw new IllegalArgumentException("Grows to more than " + this.capacity + ": " + capacity);
        }
        long old = this.capacity;
        this.capacity = capacity;
        release(old, capacity - old);
    }

    /** How much is free in all, though perhaps not in one piece. */
    long free() {
        return free;
    }

    /** Whether {@link #allocate} would find {@code length} units in a row (alignment 1). Allocates nothing. */
    boolean fits(long length) {
        for (int i = 0; i < count; i++) {
            if (lengths[i] >= length) {
                return true;
            }
        }
        return length == 0;
    }

    /**
     * The start of a free range of {@code length} units beginning at a multiple of {@code alignment}, now in use; -1
     * when none is free. The first such range wins.
     */
    long allocate(long length, long alignment) {
        if (length == 0) {
            return 0;
        }
        for (int i = 0; i < count; i++) {
            long start = (starts[i] + alignment - 1) / alignment * alignment;
            long end = starts[i] + lengths[i];
            if (start + length <= end) {
                take(i, start, length);
                return start;
            }
        }
        return -1;
    }

    /** Takes [start, start + length) out of free range {@code i}, which holds it. */
    private void take(int i, long start, long length) {
        long rangeStart = starts[i];
        long rangeEnd = rangeStart + lengths[i];
        long end = start + length;
        free -= length;
        if (start == rangeStart && end == rangeEnd) {
            remove(i);
        } else if (start == rangeStart) {
            starts[i] = end;
            lengths[i] = rangeEnd - end;
        } else if (end == rangeEnd) {
            lengths[i] = start - rangeStart;
        } else { // the alignment leaves a gap before: split
            lengths[i] = start - rangeStart;
            insert(i + 1, end, rangeEnd - end);
        }
    }

    /** Gives [start, start + length) back, merging it with free neighbors. */
    void release(long start, long length) {
        if (length == 0) {
            return;
        }
        if (start < 0 || start + length > capacity) {
            throw new IllegalArgumentException("Range " + start + " + " + length + " is outside " + capacity);
        }
        int i = 0;
        while (i < count && starts[i] < start) {
            i++;
        }
        boolean joinsPrevious = i > 0 && starts[i - 1] + lengths[i - 1] == start;
        boolean joinsNext = i < count && start + length == starts[i];
        if ((i > 0 && starts[i - 1] + lengths[i - 1] > start) || (i < count && start + length > starts[i])) {
            throw new IllegalStateException("Range " + start + " + " + length + " is free already");
        }
        free += length;
        if (joinsPrevious && joinsNext) {
            lengths[i - 1] += length + lengths[i];
            remove(i);
        } else if (joinsPrevious) {
            lengths[i - 1] += length;
        } else if (joinsNext) {
            starts[i] = start;
            lengths[i] += length;
        } else {
            insert(i, start, length);
        }
    }

    private void insert(int i, long start, long length) {
        if (count == starts.length) {
            starts = Arrays.copyOf(starts, count * 2);
            lengths = Arrays.copyOf(lengths, count * 2);
        }
        System.arraycopy(starts, i, starts, i + 1, count - i);
        System.arraycopy(lengths, i, lengths, i + 1, count - i);
        starts[i] = start;
        lengths[i] = length;
        count++;
    }

    private void remove(int i) {
        System.arraycopy(starts, i + 1, starts, i, count - i - 1);
        System.arraycopy(lengths, i + 1, lengths, i, count - i - 1);
        count--;
    }

    /** How many free pieces there are, e.g. for tests. */
    int pieces() {
        return count;
    }
}
