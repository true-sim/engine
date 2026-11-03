package com.truesim.engine.app;

/**
 * A timer on a world's {@link Clock} (application-api.md D97): it fires after a number of the clock's steps, once
 * ({@link Clock#after}) or every so many ({@link Clock#every}), inside the step it falls in, after the game's and the
 * plugins' {@code fixedUpdate} and before the clock's after-step hooks. Counted in steps, so it fires at the same tick on
 * every run; a paused or slowed world delays it with its steps. Making one allocates; running it does not.
 */
public final class Timer {

    private final Runnable action;
    private final long period; // steps between firings; 0: once
    private long due;          // the tick it fires at next
    private boolean cancelled;

    Timer(Runnable action, long due, long period) {
        this.action = action;
        this.due = due;
        this.period = period;
    }

    /** Stops it: it fires no more. */
    public void cancel() {
        cancelled = true;
    }

    /** Whether it will fire again: not cancelled, and repeating or not fired yet. */
    public boolean pending() {
        return !cancelled;
    }

    /** The tick it fires at next. */
    public long dueTick() {
        return due;
    }

    /** Fires if due at {@code tick}; returns whether it is done (fired once, or cancelled). */
    boolean run(long tick) {
        if (!cancelled && tick >= due) {
            if (period == 0) {
                cancelled = true;
            } else {
                due += period;
            }
            action.run();
        }
        return cancelled;
    }
}
