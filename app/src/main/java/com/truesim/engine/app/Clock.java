package com.truesim.engine.app;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.Objects;

/**
 * A world's clock (application-api.md D88, D89): it runs the world's fixed steps at its own rate, the engine's
 * {@link Engine#FIXED_RATE} (60 a second unless set) until given one. Each frame it is owed the frame's time
 * ({@link Frame#delta()}, capped), counted in whole ticks: what it is owed is kept as an integer of nanoseconds times
 * its rate, so a tick is exactly 10^9 of them and no rounding drifts.
 * The engine steps every clock in the order made ({@link Engine#addClock}), within one budget of steps per frame shared
 * by all ({@link Engine#FIXED_STEP_BUDGET}, D92); ticks past the budget are dropped and counted
 * ({@link #droppedTicks()}), the part of a tick left over is kept.
 *
 * <p>After each step the clock fires its timers due ({@link #after}, {@link #every}: D97), then runs what was given to
 * {@link #afterStep}, in that order: the world's transforms
 * propagated (D123), so the next step reads its parents as they are. For rendering between steps, {@link #blend()} is
 * how far the world is into its next tick: the scene is drawn that far between its last two steps.
 *
 * <p>Used from the main thread only. Stepping allocates nothing.
 */
public final class Clock {

    /** The most steps a second a clock takes. */
    public static final int MAX_RATE = 10_000;
    static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final String name;
    private int rate;
    private boolean ownRate;    // set by rate(n): the engine's fixed rate no longer applies
    private long owed;          // nanoseconds times the rate not yet stepped: under NANOS_PER_SECOND after stepping
    private long tick;          // steps so far
    private long rateTick;      // the tick when the rate was last set
    private double rateTime;    // the time then
    private long dropped;       // ticks dropped by the budget, so far
    private int lastSteps;      // steps in the last frame
    private int lastDropped;    // ticks dropped in the last frame
    private double renderTime;  // the time drawn in the last frame: the last step's plus the blend
    private double frameDelta;  // how far the drawn time moved in the last frame
    private Runnable[] after = new Runnable[0];
    private Timer[] timers = new Timer[0];
    private int timerCount;

    Clock(String name, int rate) {
        this.name = Objects.requireNonNull(name, "name");
        this.rate = rate;
    }

    /** Its name: the world's, in reports. */
    public String name() {
        return name;
    }

    /** Steps a second. */
    public int rate() {
        return rate;
    }

    /**
     * Steps {@code stepsPerSecond} times a second from now on (1 to {@link #MAX_RATE}); the part of a tick it is owed
     * is kept as the same fraction of the new tick. Allocates nothing.
     */
    public Clock rate(int stepsPerSecond) {
        change(stepsPerSecond);
        ownRate = true;
        return this;
    }

    /** The engine's fixed rate ({@link Engine#FIXED_RATE}) at a frame's start: taken unless the rate was set. */
    void follow(int fixedRate) {
        if (!ownRate && fixedRate != rate) {
            change(fixedRate);
        }
    }

    private void change(int stepsPerSecond) {
        if (stepsPerSecond < 1 || stepsPerSecond > MAX_RATE) {
            throw new IllegalArgumentException("A clock steps 1 to " + MAX_RATE + " times a second: " + stepsPerSecond);
        }
        rateTime = time();
        rateTick = tick;
        rate = stepsPerSecond;
    }

    /** Steps so far: the tick of the last step, 0 before the first. */
    public long tick() {
        return tick;
    }

    /** A step's length, seconds: 1 / {@link #rate()}. */
    public double stepSeconds() {
        return 1.0 / rate;
    }

    /** The world's time at its last step, seconds: the sum of its steps' lengths. */
    public double time() {
        return rateTime + (double) (tick - rateTick) / rate;
    }

    /**
     * How far the world is into its next tick, from 0 (just stepped) to below 1: what rendering blends its last two
     * steps by (D91).
     */
    public double blend() {
        return (double) owed / NANOS_PER_SECOND;
    }

    /** The world's time as drawn this frame, seconds: its last step's plus the blend of a step. */
    public double renderTime() {
        return renderTime;
    }

    /**
     * How far the drawn time moved this frame, seconds ({@link #renderTime()}): what runs once a frame on the world's
     * clock (animators, A5) moves on by. The frame's delta, less any ticks the budget dropped.
     */
    public double frameDelta() {
        return frameDelta;
    }

    /** Steps run in the last frame. */
    public int stepsLastFrame() {
        return lastSteps;
    }

    /** Ticks the budget dropped in the last frame. */
    public int droppedLastFrame() {
        return lastDropped;
    }

    /** Ticks the step budget dropped, so far: time this world lost to slow frames. */
    public long droppedTicks() {
        return dropped;
    }

    /** Runs {@code hook} after each of this clock's steps, after those given before: the world's transforms, say. */
    @AllowAllocation("set up once")
    public Clock afterStep(Runnable hook) {
        Objects.requireNonNull(hook, "hook");
        after = Arrays.copyOf(after, after.length + 1);
        after[after.length - 1] = hook;
        return this;
    }

    /**
     * Fires {@code action} once, in the step {@code steps} from now (1: the next step). Allocates the timer.
     */
    @AllowAllocation("making a timer")
    public Timer after(long steps, Runnable action) {
        return add(new Timer(Objects.requireNonNull(action, "action"), tick + positive(steps), 0));
    }

    /**
     * Fires {@code action} once, in the first step at or past {@code seconds} from now on this clock: the seconds are
     * turned into whole steps at its rate now. Allocates the timer.
     */
    @AllowAllocation("making a timer")
    public Timer afterSeconds(double seconds, Runnable action) {
        return after(steps(seconds), action);
    }

    /** Fires {@code action} every {@code steps} steps, the first {@code steps} from now, until cancelled. */
    @AllowAllocation("making a timer")
    public Timer every(long steps, Runnable action) {
        return add(new Timer(Objects.requireNonNull(action, "action"), tick + positive(steps), positive(steps)));
    }

    /** Fires {@code action} every {@code seconds} on this clock, as whole steps at its rate now, until cancelled. */
    @AllowAllocation("making a timer")
    public Timer everySeconds(double seconds, Runnable action) {
        return every(steps(seconds), action);
    }

    /** The timers pending. */
    public int timerCount() {
        return timerCount;
    }

    private long steps(double seconds) {
        if (!(seconds > 0) || Double.isInfinite(seconds)) {
            throw new IllegalArgumentException("Not a time from now: " + seconds);
        }
        return Math.max(1, (long) Math.ceil(seconds * rate - 1e-9));
    }

    private static long positive(long steps) {
        if (steps < 1) {
            throw new IllegalArgumentException("Steps from now, at least 1: " + steps);
        }
        return steps;
    }

    @AllowAllocation("making a timer")
    private Timer add(Timer timer) {
        if (timerCount == timers.length) {
            timers = Arrays.copyOf(timers, Math.max(4, 2 * timerCount));
        }
        timers[timerCount++] = timer;
        return timer;
    }

    /** Fires the timers due at this tick, in the order made; drops those done. */
    void runTimers() {
        int kept = 0;
        int count = timerCount; // timers made by an action fire from the next step
        for (int i = 0; i < count; i++) {
            Timer timer = timers[i];
            if (!timer.run(tick)) {
                timers[kept++] = timer;
            }
        }
        for (int i = count; i < timerCount; i++) {
            timers[kept++] = timers[i];
        }
        for (int i = kept; i < timerCount; i++) {
            timers[i] = null;
        }
        timerCount = kept;
    }

    /** Adds {@code nanos} of frame time to what it is owed; returns the whole ticks owed now. */
    long owe(long nanos) {
        owed += nanos * rate;
        return owed / NANOS_PER_SECOND;
    }

    /** One step taken: its tick counted. */
    void stepped() {
        tick++;
        owed -= NANOS_PER_SECOND;
    }

    /** After each step: the timers due, then the hooks. */
    void afterStepped() {
        if (timerCount > 0) {
            runTimers();
        }
        for (Runnable hook : after) {
            hook.run();
        }
    }

    /** The frame's stepping is over: {@code steps} taken, {@code ticks} more dropped. */
    void endFrame(int steps, long ticks) {
        owed -= ticks * NANOS_PER_SECOND;
        dropped += ticks;
        lastSteps = steps;
        lastDropped = (int) ticks;
        double drawn = time() + blend() / rate;
        frameDelta = drawn - renderTime;
        renderTime = drawn;
    }

    @Override
    public String toString() {
        return "clock " + name + " (" + rate + "/s, tick " + tick + ")";
    }
}
