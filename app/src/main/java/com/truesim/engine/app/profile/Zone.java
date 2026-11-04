package com.truesim.engine.app.profile;

/**
 * A named CPU zone of the profiler, declared once ({@link Profiler#zone}) and timed by
 * {@link Profiler#begin}/{@link Profiler#end} wherever it runs:
 *
 * <pre>{@code
 * static final Zone PHYSICS = Profiler.zone("game.physics");
 * Profiler.begin(PHYSICS);
 * try { step(); } finally { Profiler.end(PHYSICS); }
 * }</pre>
 */
public final class Zone {

    private final int id;
    private final String name;

    Zone(int id, String name) {
        this.id = id;
        this.name = name;
    }

    /** Its id: its index among the zones declared. */
    public int id() {
        return id;
    }

    /** Its name. */
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
