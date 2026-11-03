package com.truesim.engine.app;

/**
 * The frame running: its number, how much time passed since the one before, and the fixed steps it ran. One object,
 * reused every frame, so reading it allocates nothing; keep the values, not the object.
 *
 * <p>Two of the clocks (application-api.md D88) are here: real time ({@link #realDelta()}, {@link #realTime()}), as the
 * system clock measured it, and the app clock ({@link #delta()}, {@link #time()}), the same with each frame's delta cut
 * to {@link Engine#MAX_FRAME_DELTA} (0.25 s unless set; D92), which the game and the world clocks run on. Each world
 * has a {@link Clock} of its own.
 */
public final class Frame {


    private long number = -1;
    private long deltaNanos;
    private double delta;
    private double time;
    private double realDelta;
    private double realTime;
    private boolean capped;
    private int steps;
    private long droppedTicks;
    private long lastNanos = Long.MIN_VALUE;
    private double maxDelta;

    Frame() {
    }

    /** Starts the next frame at {@code nanos} ({@link System#nanoTime()}), its delta cut to {@code maxNanos}. */
    void next(long nanos, long maxNanos) {
        number++;
        long real = lastNanos == Long.MIN_VALUE ? 0 : Math.max(0, nanos - lastNanos);
        capped = real > maxNanos;
        deltaNanos = Math.min(real, maxNanos);
        maxDelta = maxNanos / 1e9;
        delta = deltaNanos / 1e9;
        time += delta;
        realDelta = real / 1e9;
        realTime += realDelta;
        lastNanos = nanos;
        steps = 0;
        droppedTicks = 0;
    }

    /** The frame's fixed stepping: {@code taken} steps over every world, {@code dropped} ticks past the budget. */
    void stepped(int taken, long dropped) {
        steps = taken;
        droppedTicks = dropped;
    }

    /** The frame's number: 0 for the first. */
    public long number() {
        return number;
    }

    /** The seconds since the frame before on the app clock, at most {@link #maxDelta()}; 0 for the first. */
    public double delta() {
        return delta;
    }

    /** {@link #delta()} in nanoseconds: what the world clocks are owed. */
    public long deltaNanos() {
        return deltaNanos;
    }

    /** The app clock: the seconds of all frames so far, the sum of their deltas. */
    public double time() {
        return time;
    }

    /** The real seconds since the frame before, not capped; 0 for the first. */
    public double realDelta() {
        return realDelta;
    }

    /** Real time: the real seconds of all frames so far. */
    public double realTime() {
        return realTime;
    }

    /** The longest delta this frame could report, seconds: {@link Engine#MAX_FRAME_DELTA} as it was. */
    public double maxDelta() {
        return maxDelta;
    }

    /** Whether the frame's delta was cut to {@link #maxDelta()}: a stall, reported (D92). */
    public boolean capped() {
        return capped;
    }

    /** The fixed steps this frame ran, every world's together: at most {@link Engine#FIXED_STEP_BUDGET}. */
    public int steps() {
        return steps;
    }

    /** The ticks the step budget dropped this frame, every world's together: 0 unless frames fall behind (D92). */
    public long droppedTicks() {
        return droppedTicks;
    }
}
