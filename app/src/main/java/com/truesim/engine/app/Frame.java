package com.truesim.engine.app;

/**
 * The frame running: its number and how much real time passed since the one before. One object, reused every frame,
 * so reading it allocates nothing; keep the values, not the object.
 */
public final class Frame {

    /** The longest delta a frame reports: a frame after a stall (a breakpoint, a drag of the window) is cut to this. */
    public static final double MAX_DELTA = 0.25;

    private long number = -1;
    private double delta;
    private double time;
    private long lastNanos = Long.MIN_VALUE;

    Frame() {
    }

    /** Starts the next frame at {@code nanos} ({@link System#nanoTime()}). */
    void next(long nanos) {
        number++;
        delta = lastNanos == Long.MIN_VALUE ? 0 : Math.min(MAX_DELTA, (nanos - lastNanos) / 1e9);
        time += delta;
        lastNanos = nanos;
    }

    /** The frame's number: 0 for the first. */
    public long number() {
        return number;
    }

    /** The seconds since the frame before, at most {@link #MAX_DELTA}; 0 for the first. */
    public double delta() {
        return delta;
    }

    /** The seconds of all frames so far: the sum of their deltas. */
    public double time() {
        return time;
    }
}
