package com.truesim.engine.app.profile;

/**
 * Where {@link Profiler#drain} hands the zones the threads recorded since the last drain, in each thread's order: a
 * begin at a depth, an end of the zone open at a depth. Times are {@link System#nanoTime()}'s.
 */
public interface ZoneSink {

    /** Zone {@code zone} (an id of {@link Profiler#zoneName}) began on thread {@code thread} at {@code nanos}. */
    void begin(int thread, long nanos, int zone, int depth);

    /** The zone open at {@code depth} on thread {@code thread} ended at {@code nanos}. */
    void end(int thread, long nanos, int depth);

    /**
     * Thread {@code thread} recorded more than its ring holds since the last drain: what was open is unknown now
     * (close it where it is); the begins and ends that follow start at depth 0.
     */
    default void lost(int thread, long nanos) {
        // nothing by default
    }
}
