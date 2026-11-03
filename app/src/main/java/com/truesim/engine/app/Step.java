package com.truesim.engine.app;

/**
 * The fixed step running ({@link Application#fixedUpdate}, {@link Plugin#fixedUpdate}): which world's clock, its tick
 * and the step's length. One object, reused for every step, so reading it allocates nothing; keep the values, not the
 * object.
 */
public final class Step {

    private Clock clock;
    private Frame frame;

    Step() {
    }

    void set(Clock stepping, Frame running) {
        clock = stepping;
        frame = running;
    }

    /** The clock of the world stepping. */
    public Clock clock() {
        return clock;
    }

    /** The step's tick: 1 for the clock's first step, counting up by one. */
    public long tick() {
        return clock.tick();
    }

    /** The step's length in seconds: the clock's 1 / rate, the same for every step at that rate. */
    public double delta() {
        return clock.stepSeconds();
    }

    /** The world's time at the end of this step, seconds: its ticks so far over its rate. */
    public double time() {
        return clock.time();
    }

    /** The frame running the step. */
    public Frame frame() {
        return frame;
    }
}
