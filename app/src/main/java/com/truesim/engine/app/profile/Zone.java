package com.truesim.engine.app.profile;

/**
 * A named CPU zone of the profiler, declared once ({@link Profiler#zone}) and timed by
 * {@link Profiler#begin}/{@link Profiler#end} wherever it runs:
 *
 * <pre>{@code
 * static final Zone PHYSICS = Profiler.zone("game.physics");
 * try (Zone z = PHYSICS.begin()) {      // ends however the block is left, an exception too; allocates nothing
 *     step();
 * }
 * }</pre>
 *
 * <p>A zone left without its end (an exception past a bare {@link Profiler#begin}) is closed when an enclosing zone
 * of the same thread ends: the drain matches each end to its zone, so the zones after it keep their depths.
 */
public final class Zone implements AutoCloseable {

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

    /** Begins it on the calling thread ({@link Profiler#begin}); returns it, for try-with-resources. */
    public Zone begin() {
        Profiler.begin(this);
        return this;
    }

    /** Ends it on the calling thread ({@link Profiler#end}). */
    @Override
    public void close() {
        Profiler.end(this);
    }

    @Override
    public String toString() {
        return name;
    }
}
