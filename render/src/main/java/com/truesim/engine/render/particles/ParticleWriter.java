package com.truesim.engine.render.particles;

import com.truesim.annotations.AllowAllocation;

/**
 * A CPU emitter's particles as the game writes them (effects.md §2.6, frame-graph-v2 §4.19's game-written records):
 * the emitter's updater is the game's own code, and the GPU draws the records exactly like simulated particles. Made
 * once per emitter of a playing effect ({@link Particles#writer}); each frame the game {@link #clear}s it (or keeps
 * last frame's records) and writes records, {@link #next} starting each with defaults, at most the emitter's capacity.
 * Positions are in the world, in doubles. Allocates nothing; used from the render thread.
 *
 * <pre>{@code
 * ParticleWriter shards = particles.writer(grenade.id(), SHARDS);       // once
 * // each frame, before particles.update:
 * shards.clear();
 * for (Shard s : game.shards()) {
 *     shards.next().position(s.x, s.y, s.z).velocity(s.vx, s.vy, s.vz).size(0.05f).color(1, 0.8f, 0.6f, 1);
 * }
 * }</pre>
 */
public final class ParticleWriter {

    final double[] positions;
    final float[] velocities;
    final float[] colors;
    final float[] sizes;
    final float[] rotations;
    final float[] ages;
    final float[] lifetimes;
    private final long effect;
    private final int emitter;
    int count;
    private int current = -1;
    private long dropped;
    boolean ended;

    @AllowAllocation("made once per emitter")
    ParticleWriter(long effect, int emitter, int capacity) {
        this.effect = effect;
        this.emitter = emitter;
        positions = new double[3 * capacity];
        velocities = new float[3 * capacity];
        colors = new float[4 * capacity];
        sizes = new float[capacity];
        rotations = new float[capacity];
        ages = new float[capacity];
        lifetimes = new float[capacity];
    }

    /** The effect whose emitter it writes. */
    public long effect() {
        return effect;
    }

    /** The emitter, by its index in its effect's data. */
    public int emitter() {
        return emitter;
    }

    /** The most records it holds. */
    public int capacity() {
        return sizes.length;
    }

    /** Records written. */
    public int count() {
        return count;
    }

    /** Records refused for want of room since it was made. */
    public long dropped() {
        return dropped;
    }

    /** Whether its effect ended: what it writes is drawn no more. */
    public boolean ended() {
        return ended;
    }

    /** Forgets the records written. */
    public ParticleWriter clear() {
        count = 0;
        current = -1;
        return this;
    }

    /**
     * Starts a record: at the world origin, at rest, white, 0.1 m across, age 0 of a 1 s life. Past the capacity the
     * record is refused (counted) and the setters write nothing until the next {@link #clear}.
     */
    public ParticleWriter next() {
        if (count == sizes.length) {
            dropped++;
            current = -1;
            return this;
        }
        current = count++;
        int i = current;
        positions[3 * i] = 0;
        positions[3 * i + 1] = 0;
        positions[3 * i + 2] = 0;
        velocities[3 * i] = 0;
        velocities[3 * i + 1] = 0;
        velocities[3 * i + 2] = 0;
        colors[4 * i] = 1;
        colors[4 * i + 1] = 1;
        colors[4 * i + 2] = 1;
        colors[4 * i + 3] = 1;
        sizes[i] = 0.1f;
        rotations[i] = 0;
        ages[i] = 0;
        lifetimes[i] = 1;
        return this;
    }

    /** The record's world position. */
    public ParticleWriter position(double x, double y, double z) {
        if (current >= 0) {
            positions[3 * current] = x;
            positions[3 * current + 1] = y;
            positions[3 * current + 2] = z;
        }
        return this;
    }

    /** Its velocity, in metres per second: velocity-facing billboards turn along it. */
    public ParticleWriter velocity(float x, float y, float z) {
        if (current >= 0) {
            velocities[3 * current] = x;
            velocities[3 * current + 1] = y;
            velocities[3 * current + 2] = z;
        }
        return this;
    }

    /** Its linear color and alpha. */
    public ParticleWriter color(float r, float g, float b, float a) {
        if (current >= 0) {
            colors[4 * current] = r;
            colors[4 * current + 1] = g;
            colors[4 * current + 2] = b;
            colors[4 * current + 3] = a;
        }
        return this;
    }

    /** Its size, in metres across. */
    public ParticleWriter size(float size) {
        if (current >= 0) {
            sizes[current] = size;
        }
        return this;
    }

    /** Its rotation in the billboard's plane, in radians. */
    public ParticleWriter rotation(float radians) {
        if (current >= 0) {
            rotations[current] = radians;
        }
        return this;
    }

    /** Its age and lifetime, in seconds: flipbooks played over life read them. */
    public ParticleWriter age(float age, float lifetime) {
        if (current >= 0) {
            ages[current] = age;
            lifetimes[current] = lifetime;
        }
        return this;
    }
}
