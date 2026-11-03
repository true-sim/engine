package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;

/**
 * Segments traced through geometry in batches answered later, each crossing named by its surface's material id: what a
 * {@link TracedAudioWorld} occludes through. The renderer's ray tracing is one ({@code SegmentTracing} in
 * engine-render), which knows nothing of audio; the app joins them:
 * {@code SegmentTracer.of(tracing.capacity(), SegmentTracing.WORDS, tracing::submit, tracing::collect)}.
 */
public interface SegmentTracer {

    /** The most segments a batch holds. */
    int capacity();

    /** Ints per segment in {@link #collect}'s results: the crossings kept, then two per crossing (any, the material). */
    int words();

    /**
     * Hands over {@code count} segments: 6 values each in {@code ends}, the world positions of its start and end.
     * Returns the batch's ticket (positive), or 0 when it cannot take a batch now (nothing taken).
     */
    long submit(double[] ends, int count);

    /**
     * Copies batch {@code ticket}'s crossings into {@code out} ({@link #words} per segment, from 0) once they are back:
     * returns how many segments the batch had, or -1 while they are not (or no longer).
     */
    int collect(long ticket, int[] out);

    /** {@link #submit}, as a method of the tracer. */
    @FunctionalInterface
    interface Submit {
        long submit(double[] ends, int count);
    }

    /** {@link #collect}, as a method of the tracer. */
    @FunctionalInterface
    interface Collect {
        int collect(long ticket, int[] out);
    }

    /** A tracer of {@code capacity} segments a batch, {@code words} ints per segment, by its two methods. */
    @AllowAllocation("set up once")
    static SegmentTracer of(int capacity, int words, Submit submit, Collect collect) {
        if (capacity < 1 || words < 1) {
            throw new IllegalArgumentException("A batch of " + capacity + " segments, " + words + " words each");
        }
        java.util.Objects.requireNonNull(submit, "submit");
        java.util.Objects.requireNonNull(collect, "collect");
        return new SegmentTracer() {
            @Override
            public int capacity() {
                return capacity;
            }

            @Override
            public int words() {
                return words;
            }

            @Override
            public long submit(double[] ends, int count) {
                return submit.submit(ends, count);
            }

            @Override
            public int collect(long ticket, int[] out) {
                return collect.collect(ticket, out);
            }
        };
    }
}
