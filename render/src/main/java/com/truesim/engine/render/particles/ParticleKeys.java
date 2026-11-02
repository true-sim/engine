package com.truesim.engine.render.particles;

import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.Image;
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

    /** The side arrays, both halves: attributes kept from birth and trails' past positions. */
    public static final Key<Buffer> SIDE = Key.buffer("truesim.particleSide");
    /** Per slot of the pool, each range of ribbons of several strands in ribbon order. */
    public static final Key<Buffer> RIBBON_ORDER = Key.buffer("truesim.particleRibbonOrder");

    /**
     * A view's reactive mask (effects step 21's hook for temporal antialiasing and upscalers): an R8 image of how much
     * particles cover each pixel, their alpha and the light they add, which such a technique trusts history less by.
     * Drawn only where something requires it.
     */
    public static final Key<Image> REACTIVE = Key.image("truesim.particleReactive");
    /**
     * Where a view's nearest blended surface is (reverse depth, 0 where there is none), from a transparency technique
     * that writes it: emitters split at glass ({@code glass} 2) draw behind it before the glass and in front after.
     * Optional: without it they draw after the glass.
     */
    public static final Key<Image> GLASS_DEPTH = Key.image("truesim.glassDepth");
    /**
     * Per slot of the scene's instances that mesh particles write, the particle's transform at the frame before, as
     * the instance's (motion vectors of mesh particles, effects step 21): written by "particle instances".
     */
    public static final Key<Buffer> PREVIOUS_INSTANCES = Key.buffer("truesim.particlePreviousInstances");

    private ParticleKeys() {
    }
}
