package com.truesim.engine.render.particles;

import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.Key;

/** The results the particle simulation provides: buffers draws read and plugins modify (effects.md §2.2). */
public final class ParticleKeys {

    /** The particle pool, both halves ({@link ParticlePool}): particle.slang's {@code Particle} records. */
    public static final Key<Buffer> PARTICLES = Key.buffer("truesim.particles");
    /**
     * The emitter instances' state, {@code STATE_WORDS} words each: their particle counts, spawns, overflow and GPU
     * bounds (particle.slang's {@code STATE_*}).
     */
    public static final Key<Buffer> STATE = Key.buffer("truesim.particleState");

    private ParticleKeys() {
    }
}
