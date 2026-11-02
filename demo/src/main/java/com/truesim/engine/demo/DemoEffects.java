package com.truesim.engine.demo;

import com.truesim.common.math.Maths;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.particles.ParticleSimulation;
import com.truesim.engine.render.particles.Particles;
import com.truesim.engine.scene.EffectBindings;
import com.truesim.engine.scene.EffectSync;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.Collide;
import com.truesim.scene.effects.ParticleEvent;
import com.truesim.scene.effects.ParticleWorld;
import com.truesim.scene.effects.Effect;
import com.truesim.scene.effects.ColorGradient;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectParameter;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.FloatCurve;
import com.truesim.scene.effects.Forces;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Over;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.Space;
import com.truesim.scene.effects.Spawn;

/**
 * The demo's effects, played on nodes of a scene and drawn through an {@link EffectSync}: a torch swaying on its node
 * (flames carried with it in its frame, sparks thrown off with its velocity, smoke left behind in the world), a
 * fountain of sparks with streaks of trails over a column of smoke, and an orb circling it with a ribbon behind and a
 * beam reaching it from the fountain (the beam's end is the orb's node), and two clouds of red and blue puffs through
 * each other, each drawn back to front, a swirl of motes in a vortex stirred by curl noise, hail bouncing on the
 * terrain, and fireworks bursting on the death of a burst. Simulated on the GPU and drawn in every view given a {@code
 * ParticleDraw}. With the fox ({@link #dust}), its feet kick up dust on their foot-down events and its survey's
 * flickering curve drives the torch's intensity, frame by frame.
 */
final class DemoEffects implements AutoCloseable {

    /** How strongly the torch burns: its flames' rate and size. */
    static final EffectParameter.Float INTENSITY = EffectParameter.ofFloat("intensity");

    /** A torch: flames in its frame, sparks and smoke in the world. */
    static final EffectData TORCH = EffectData.builder().parameter(INTENSITY, 1)
        .emitter("flames", e -> e.space(Space.LOCAL).capacity(160).spawn(Spawn.rate(Bind.param(INTENSITY).times(110)))
            .init(Shape.disc(0.09f), Init.velocity(Bind.random(-0.08f, 0.08f), Bind.random(1, 1.6f),
                Bind.random(-0.08f, 0.08f)), Init.lifetime(Bind.random(0.35f, 0.7f)),
                Init.size(Bind.param(INTENSITY).times(0.22f)), Init.color(Bind.color(3.2f, 1.3f, 0.35f, 1)))
            .update(Forces.gravity(Bind.constant(-0.25f)), Forces.drag(1.2f),
                Over.size(FloatCurve.of(0, 0.7f, 0.3f, 1, 1, 0.15f)),
                Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.1f, 1, 1, 1, 1)
                    .linear(0.5f, 0.9f, 0.45f, 0.2f, 1).linear(1, 0.4f, 0.1f, 0.02f, 0).build()))
            .render(Renderer.billboard(Renderer.Facing.AXIS).additive().set("soft", 0.2f)))
        .emitter("sparks", e -> e.spawn(Spawn.rate(24)).init(Shape.cone(0.45f, 0.06f), Init.speed(Bind.random(1.5f, 3)),
                Init.lifetime(Bind.random(0.6f, 1.4f)), Init.size(Bind.random(0.015f, 0.03f)),
                Init.color(Bind.color(5, 2.2f, 0.6f, 1)))
            .update(Forces.gravity(Bind.constant(0.6f)), Forces.drag(0.8f),
                Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 1).linear(1, 0.6f, 0.2f, 0.05f, 0).build()))
            .render(Renderer.billboard(Renderer.Facing.VELOCITY).additive().set("stretch", 0.12f)))
        .emitter("smoke", e -> e.spawn(Spawn.rate(10)).init(Shape.sphere(0.12f),
                Init.velocity(Bind.random(-0.1f, 0.1f), Bind.random(0.5f, 0.9f), Bind.random(-0.1f, 0.1f)),
                Init.lifetime(Bind.random(3, 4.5f)), Init.size(Bind.random(0.35f, 0.55f)),
                Init.color(Bind.color(0.5f, 0.48f, 0.45f, 0.5f)))
            .update(Forces.drag(0.4f), Over.size(FloatCurve.of(0, 1, 1, 4)),
                Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.12f, 1, 1, 1, 1)
                    .linear(1, 1, 1, 1, 0).build()))
            .render(Renderer.billboard(Renderer.Facing.POSITION).set("soft", 0.5f).set("volume", 1)))
        .build();

    /** Dust kicked up by a foot coming down: as much as the event weighs. */
    static final EffectData DUST = EffectData.builder().parameter(INTENSITY, 1)
        .emitter("dust", e -> e.duration(0.05).capacity(16).spawn(Spawn.burst(0, Bind.param(INTENSITY).times(14)))
            .init(Shape.disc(0.06f), Init.velocity(Bind.random(-0.35f, 0.35f), Bind.random(0.15f, 0.5f),
                Bind.random(-0.35f, 0.35f)), Init.lifetime(Bind.random(0.9f, 1.6f)), Init.size(Bind.random(0.06f, 0.12f)),
                Init.color(Bind.color(0.55f, 0.45f, 0.32f, 0.7f)))
            .update(Forces.gravity(Bind.constant(0.05f)), Forces.drag(2.5f), Over.size(FloatCurve.of(0, 1, 1, 3.5f)),
                Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0.9f).linear(1, 1, 1, 1, 0).build()))
            .render(Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.PER_VERTEX)
                .set("soft", 0.1f)))
        .build();

    /**
     * Fire on a skin: flames born all over the mesh of the node the effect plays on, a skinned one as posed (its
     * surface, uniform by area), licking up and left behind as it moves.
     */
    static final EffectData SKIN_FIRE = EffectData.builder()
        .emitter("flames", e -> e.capacity(1400).spawn(Spawn.rate(2400))
            .init(Shape.mesh(Shape.SURFACE), Init.speed(Bind.random(0.05f, 0.2f)),
                Init.velocity(Bind.random(-0.05f, 0.05f), Bind.random(0.3f, 0.6f), Bind.random(-0.05f, 0.05f)),
                Init.lifetime(Bind.random(0.2f, 0.45f)), Init.size(Bind.random(0.04f, 0.08f)),
                Init.color(Bind.color(1.4f, 0.55f, 0.14f, 1)))
            .update(Forces.gravity(Bind.constant(-0.3f)), Forces.drag(1.5f),
                Over.size(FloatCurve.of(0, 0.6f, 0.3f, 1, 1, 0.2f)),
                Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.15f, 1, 1, 1, 1)
                    .linear(0.6f, 0.9f, 0.4f, 0.15f, 1).linear(1, 0.4f, 0.08f, 0.02f, 0).build()))
            .render(Renderer.billboard(Renderer.Facing.AXIS).additive().set("soft", 0.05f)))
        .build();

    /** The rock mesh and material debris draws: the world's own, registered as {@link #debris} says. */
    static final com.truesim.scene.assets.AssetRef<?> ROCK = com.truesim.scene.assets.AssetRef.of(
        com.truesim.scene.assets.AssetType.of("demo.mesh", String.class, s -> 0),
        new com.truesim.scene.assets.AssetId(0, 1));

    /**
     * Debris: every four seconds a burst of rocks thrown up, tumbling as they fly and roll, bouncing on the terrain and
     * settling, mesh particles casting shadows (instances the simulation writes), with a puff of dust.
     */
    static final EffectData DEBRIS = EffectData.builder()
        .emitter("rocks", e -> e.duration(4).loops(0).capacity(60).spawn(Spawn.burst(0, 40))
            .init(Shape.cone(0.5f, 0.3f), Init.speed(Bind.random(4, 8)), Init.lifetime(Bind.random(3.4f, 3.9f)),
                Init.size(Bind.random(0.1f, 0.22f)))
            .update(Forces.gravity(), Forces.drag(0.1f))
            .collide(Collide.height().bounce(0.3f).friction(0.5f))
            .render(Renderer.mesh(ROCK, ROCK).castsShadows(true)))
        .emitter("dust", e -> e.duration(4).loops(0).capacity(40).spawn(Spawn.burst(0, 24))
            .init(Shape.sphere(0.5f), Init.speed(Bind.random(0.5f, 1.5f)), Init.lifetime(Bind.random(1.5f, 2.5f)),
                Init.size(Bind.random(0.6f, 1.1f)), Init.color(Bind.color(0.45f, 0.38f, 0.3f, 0.5f)))
            .update(Forces.drag(1.5f), Over.size(FloatCurve.of(0, 0.6f, 1, 2)),
                Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.1f, 1, 1, 1, 1)
                    .linear(1, 1, 1, 1, 0).build()))
            .render(Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.PER_VERTEX)
                .set("soft", 0.3f)))
        .build();

    /**
     * Lit smoke: grey puffs lit per pixel (a sphere's normal across each), so the sun and the lights near it light one
     * side, the sun's cascades shadow it, and the other side stays in the sky's light.
     */
    static final EffectData LIT_SMOKE = EffectData.builder()
        .emitter("smoke", e -> e.spawn(Spawn.rate(14)).init(Shape.sphere(0.4f),
                Init.velocity(Bind.random(-0.15f, 0.15f), Bind.random(0.5f, 0.9f), Bind.random(-0.15f, 0.15f)),
                Init.lifetime(Bind.random(4, 6)), Init.size(Bind.random(0.9f, 1.4f)),
                Init.color(Bind.color(0.8f, 0.8f, 0.8f, 0.85f)))
            .update(Forces.drag(0.3f), Over.size(FloatCurve.of(0, 0.6f, 1, 2.2f)),
                Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.12f, 1, 1, 1, 1)
                    .linear(1, 1, 1, 1, 0).build()))
            .render(Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.PER_PIXEL)
                .set("soft", 0.5f)))
        .build();

    /**
     * Sparks that light what is near: velocity-facing additive streaks bouncing on the terrain, half of them point lights
     * (the brightest 48 kept), dimming as they cool, lighting the ground and the rocks beside them.
     */
    static final EffectData LIT_SPARKS = EffectData.builder().emitter("sparks", e -> e.spawn(Spawn.rate(160))
        .init(Shape.cone(0.4f, 0.05f), Init.speed(Bind.random(3, 6)), Init.lifetime(Bind.random(1, 1.8f)),
            Init.size(Bind.random(0.03f, 0.05f)), Init.color(Bind.color(4, 1.8f, 0.5f, 1)))
        .update(Forces.gravity(), Forces.drag(0.3f), Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 1)
            .linear(0.6f, 0.8f, 0.45f, 0.2f, 1).linear(1, 0.3f, 0.05f, 0, 0).build()))
        .collide(Collide.height().bounce(0.4f).friction(0.3f))
        .render(Renderer.billboard(Renderer.Facing.VELOCITY).additive().set("stretch", 0.08f),
            Renderer.light(2.5f, 0.5f).set("max", 48).set("fraction", 0.5f)))
        .build();

    /** Sparks: velocity-facing and additive, cooling as they fall. */
    static final EffectData SPARKS = EffectData.builder().emitter("sparks", e -> e.spawn(Spawn.rate(400))
        .init(Shape.cone(0.35f, 0.05f), Init.speed(Bind.random(4, 8)), Init.lifetime(Bind.random(0.8f, 1.8f)),
            Init.size(Bind.random(0.03f, 0.06f)), Init.color(Bind.color(4, 1.6f, 0.4f, 1)))
        .update(Forces.gravity(), Forces.drag(0.4f), Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 1)
            .linear(0.5f, 0.8f, 0.4f, 0.2f, 1).linear(1, 0.3f, 0.05f, 0, 0).build()))
        .render(Renderer.billboard(Renderer.Facing.VELOCITY).additive().set("stretch", 0.08f)))
        .emitter("smoke", e -> e.spawn(Spawn.rate(24))
            .init(Shape.sphere(0.4f), Init.velocity(Bind.random(-0.2f, 0.2f), Bind.random(0.6f, 1.2f),
                Bind.random(-0.2f, 0.2f)), Init.lifetime(Bind.random(3, 5)), Init.size(Bind.random(0.8f, 1.4f)),
                Init.color(Bind.color(0.25f, 0.24f, 0.23f, 0.5f)))
            .update(Forces.drag(0.3f), Over.size(FloatCurve.of(0, 0.5f, 1, 2.5f)),
                Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.15f, 1, 1, 1, 1)
                    .linear(1, 1, 1, 1, 0).build()))
            .render(Renderer.billboard(Renderer.Facing.POSITION).set("soft", 0.6f).set("volume", 1)))
        .emitter("streaks", e -> e.spawn(Spawn.rate(40))
            .init(Shape.cone(0.5f, 0.05f), Init.speed(Bind.random(5, 8)), Init.lifetime(Bind.random(1, 1.6f)),
                Init.size(Bind.constant(0.05f)), Init.color(Bind.color(3, 2.4f, 0.8f, 1)))
            .update(Forces.gravity(), Forces.drag(0.3f))
            .render(Renderer.trail(12).additive().set("width.tail", 0.1f)))
        .build();

    /**
     * Two clouds of puffs, red and blue, through each other: each emitter's puffs drawn back to front (sorted, the
     * default for alpha-blended billboards), the emitters as wholes.
     */
    static final EffectData PUFFS = EffectData.builder()
        .emitter("red", e -> e.spawn(Spawn.rate(14)).init(Shape.sphere(0.9f),
                Init.velocity(Bind.random(-0.1f, 0.1f), Bind.random(0.05f, 0.2f), Bind.random(-0.1f, 0.1f)),
                Init.lifetime(Bind.random(4, 6)), Init.size(Bind.random(0.5f, 0.8f)),
                Init.color(Bind.color(0.9f, 0.15f, 0.1f, 0.85f)))
            .update(Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.15f, 1, 1, 1, 1)
                .linear(0.8f, 1, 1, 1, 1).linear(1, 1, 1, 1, 0).build()))
            .render(Renderer.billboard(Renderer.Facing.POSITION).set("soft", 0.2f)))
        .emitter("blue", e -> e.spawn(Spawn.rate(14)).init(Shape.sphere(0.9f),
                Init.velocity(Bind.random(-0.1f, 0.1f), Bind.random(0.05f, 0.2f), Bind.random(-0.1f, 0.1f)),
                Init.lifetime(Bind.random(4, 6)), Init.size(Bind.random(0.5f, 0.8f)),
                Init.color(Bind.color(0.1f, 0.3f, 0.95f, 0.85f)))
            .update(Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.15f, 1, 1, 1, 1)
                .linear(0.8f, 1, 1, 1, 1).linear(1, 1, 1, 1, 0).build()))
            .render(Renderer.billboard(Renderer.Facing.POSITION).set("soft", 0.2f)))
        .build();

    /** A swirl: motes rising in a vortex that pulls them in, stirred by curl noise. */
    static final EffectData SWIRL = EffectData.builder().emitter("motes", e -> e.spawn(Spawn.rate(260))
        .init(Shape.disc(1.4f), Init.velocity(Bind.random(-0.2f, 0.2f), Bind.random(0.4f, 0.9f),
                Bind.random(-0.2f, 0.2f)), Init.lifetime(Bind.random(3, 4.5f)), Init.size(Bind.random(0.04f, 0.08f)),
            Init.color(Bind.color(0.4f, 1.6f, 2.4f, 1)))
        .update(Forces.vortex(Bind.constant(5), 3, 0, 1, 0), Forces.curl(Bind.constant(1.5f), 0.7f, 0.3f, 2),
            Forces.gravity(Bind.constant(-0.1f)), Forces.drag(0.8f), Forces.speedLimit(4),
            Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0)
                .linear(0.1f, 1, 1, 1, 1).linear(1, 0.6f, 0.3f, 1.2f, 0).build()))
        .render(Renderer.billboard(Renderer.Facing.POSITION).additive())).build();

    /** Hail thrown up from a spout, bouncing on the terrain (its height field) until it settles. */
    static final EffectData HAIL = EffectData.builder().emitter("hail", e -> e.spawn(Spawn.rate(70))
        .init(Shape.cone(0.5f, 0.1f), Init.speed(Bind.random(3.5f, 6)), Init.lifetime(Bind.random(5, 7)),
            Init.size(Bind.random(0.07f, 0.12f)), Init.color(Bind.color(0.85f, 0.95f, 1.6f, 1)))
        .update(Forces.gravity(), Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 1).linear(0.85f, 1, 1, 1, 1)
            .linear(1, 1, 1, 1, 0).build()))
        .collide(Collide.height().bounce(0.45f).friction(0.25f))
        .render(Renderer.billboard(Renderer.Facing.POSITION))).build();

    /** Fireworks: rockets bursting into stars on their death, the stars into sparkles on theirs (events). */
    static final EffectData FIREWORKS = EffectData.builder()
        .emitter("rockets", e -> e.spawn(Spawn.rate(1.2f)).init(Shape.disc(0.5f),
                Init.lifetime(Bind.random(1.1f, 1.5f)),
                Init.velocity(Bind.random(-1.5f, 1.5f), Bind.random(11, 14), Bind.random(-1.5f, 1.5f)),
                Init.size(Bind.constant(0.12f)), Init.color(Bind.random(ColorGradient.builder()
                    .linear(0, 4, 0.8f, 0.3f, 1).linear(0.33f, 0.4f, 3.5f, 0.8f, 1).linear(0.66f, 0.6f, 1, 4, 1)
                    .linear(1, 4, 0.6f, 3.5f, 1).build())))
            .update(Forces.gravity(), Forces.drag(0.2f))
            .event(ParticleEvent.onDeath("stars", Bind.random(60, 90)))
            .render(Renderer.billboard(Renderer.Facing.VELOCITY).additive().set("stretch", 0.1f)))
        .emitter("stars", e -> e.capacity(500).spawn(Spawn.fromEvents())
            .init(Shape.sphere(0.2f), Init.speed(Bind.random(4, 7)), Init.lifetime(Bind.random(1.2f, 1.8f)),
                Init.size(Bind.random(0.15f, 0.22f)), Init.inherit(0.3f, true, false))
            .update(Forces.gravity(Bind.constant(0.4f)), Forces.drag(1.2f),
                Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 1).linear(0.7f, 1, 0.8f, 0.6f, 1)
                    .linear(1, 0.5f, 0.2f, 0.1f, 0).build()))
            .event(ParticleEvent.onDeath("sparkles", Bind.constant(3)).chance(0.5f))
            .render(Renderer.billboard(Renderer.Facing.VELOCITY).additive().set("stretch", 0.06f)))
        .emitter("sparkles", e -> e.capacity(800).spawn(Spawn.fromEvents())
            .init(Shape.sphere(0.1f), Init.speed(Bind.random(0.5f, 1.5f)), Init.lifetime(Bind.random(0.3f, 0.6f)),
                Init.size(Bind.constant(0.05f)), Init.color(Bind.color(3, 3, 2.5f, 1)))
            .update(Forces.gravity(Bind.constant(0.2f)))
            .render(Renderer.billboard(Renderer.Facing.POSITION).additive())).build();

    /** A ribbon behind an orb circling the fountain, narrowing to its tail. */
    static final EffectData RIBBON = EffectData.builder().emitter("ribbon", e -> e.spawn(Spawn.rate(60))
        .init(Init.lifetime(1.5f), Init.size(Bind.constant(0.3f)), Init.color(Bind.color(0.3f, 1.2f, 2, 1)))
        .update(Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 1).linear(1, 1, 1, 1, 0).build()))
        .render(Renderer.ribbon().additive().set("width.tail", 0f))).build();

    /** A crackling beam from the fountain to the orb. */
    static final EffectData BEAM = EffectData.builder().emitter("beam", e -> e.spawn(Spawn.rate(1))
        .render(Renderer.beam().additive().set("width", 0.08f).set("noise", 0.25f).set("segments", 24)
            .set("color.r", 1.5f).set("color.g", 0.6f).set("color.b", 3f))).build();

    private final SceneGraph scene;
    private final Effects effects;
    private final Particles particles;
    private final EffectSync sync;
    private final double x;
    private final double y;
    private final double z;
    private final long torch;
    private final long orb;
    private final Effect torchFire;
    private EffectBindings bindings;
    private double time;

    /** The demo's effects at ({@code x}, {@code y}, {@code z}) in a scene of their own, in a pool of 32 MB. */
    DemoEffects(VulkanDevice device, FrameSlots slots, double x, double y, double z) {
        this(device, slots, new SceneGraph(new ChangeClock()), x, y, z);
    }

    /**
     * The demo's effects at ({@code x}, {@code y}, {@code z}) on nodes of {@code scene}, which its owner updates
     * before {@link #update}: a character's, whose joints effects attach to.
     */
    DemoEffects(VulkanDevice device, FrameSlots slots, SceneGraph scene, double x, double y, double z) {
        this.scene = scene;
        particles = new Particles(device, slots, 32 << 20);
        effects = new Effects(scene);
        sync = new EffectSync(effects, particles, new ParticleWorld() { // the terrain, for collision
            @Override
            public double height(double x, double z) {
                return DemoMeshes.height(x, z);
            }

            @Override
            public boolean raycast(double x, double y, double z, double dx, double dy, double dz, double max,
                Hit hit) {
                return false;
            }
        }, null);
        this.x = x;
        this.y = y;
        this.z = z;
        torch = scene.create();
        orb = scene.create();
        long fountain = scene.create();
        scene.setTranslation(fountain, x, y, z);
        place(0);
        scene.update();
        torchFire = effects.play(TORCH, torch).offset(0, 1.1, 0).inheritVelocity(1);
        effects.play(SPARKS, fountain);
        effects.play(RIBBON, orb);
        effects.play(PUFFS, x + 3, DemoMeshes.height(x + 3, z - 1) + 1.2, z - 1);
        effects.play(SWIRL, x - 1, DemoMeshes.height(x - 1, z - 4) + 0.1, z - 4);
        effects.play(HAIL, x + 2, DemoMeshes.height(x + 2, z + 3), z + 3);
        effects.play(FIREWORKS, x - 6, DemoMeshes.height(x - 6, z - 8), z - 8);
        effects.play(LIT_SMOKE, x - 5, DemoMeshes.height(x - 5, z + 5), z + 5);
        sync.beamEnd(effects.play(BEAM, fountain).offset(0, 0.3, 0).id(), 0, orb);
    }

    /** The torch sways to the side of the fountain, the orb circles it. */
    private void place(double t) {
        double tx = x - 3 + 0.35 * Maths.sin(1.3 * t);
        double tz = z + 1 + 0.2 * Maths.sin(0.9 * t);
        scene.setTranslation(torch, tx, DemoMeshes.height(tx, tz), tz);
        scene.setTranslation(orb, x + 2 * Maths.cos(1.5 * t), y + 1.5 + 0.4 * Maths.sin(3 * t),
            z + 2 * Maths.sin(1.5 * t));
    }

    /** Dust at the fox's feet on its foot-down events, and the torch flickering with its survey's curve. */
    void dust(DemoCharacter fox) {
        bindings = new EffectBindings(effects, fox.animators());
        for (int f = 0; f < 4; f++) {
            bindings.onEvent(DemoCharacter.FEET[f], DUST, fox.feet()[f]).scale(INTENSITY).minimumWeight(0.2f);
        }
        bindings.curve(fox.animator(), DemoCharacter.FLICKER, torchFire, INTENSITY).map(0.9, 0.4);
    }

    /**
     * Sets the fox on fire: flames over its skin as it moves, from the posed copy {@code renderer} draws (the world's,
     * whose pool holds the fox's meshes).
     */
    void burn(DemoCharacter fox, com.truesim.engine.render.MeshletRenderer renderer) {
        particles.meshes(renderer);
        sync.meshes(fox.sync());
        effects.play(SKIN_FIRE, fox.body());
    }

    /**
     * Debris at ({@code dx}, {@code dz}) from the effects' place: rocks of the world's mesh {@code rock} with material
     * {@code material}, mesh particles in {@code renderer}'s scene (a range of its ids the GPU writes).
     */
    void debris(com.truesim.engine.render.MeshletRenderer renderer, int rock, int material, double dx, double dz) {
        particles.meshes(renderer, 1024);
        particles.mesh(ROCK, rock, material);
        effects.play(DEBRIS, x + dx, DemoMeshes.height(x + dx, z + dz), z + dz);
    }

    /**
     * Sparks lighting what is near at ({@code dx}, {@code dz}) from the effects' place: particle lights in
     * {@code lights}, at most 256 a frame.
     */
    void sparkLights(com.truesim.engine.render.Lights lights, double dx, double dz) {
        particles.lights(lights, 256);
        effects.play(LIT_SPARKS, x + dx, DemoMeshes.height(x + dx, z + dz), z + dz);
    }

    /** The bindings with the fox, or null. */
    EffectBindings bindings() {
        return bindings;
    }

    Effects effects() {
        return effects;
    }

    EffectSync sync() {
        return sync;
    }

    Particles particles() {
        return particles;
    }

    ParticleSimulation simulation() {
        return new ParticleSimulation(particles);
    }

    /** Moves the effects on by {@code seconds}, after the scene's owner updated it; before the frame records. */
    void update(double seconds) {
        time += seconds;
        sync.clearViewers();
        sync.addViewer(x, y, z); // the height field around the effects
        place(time);
        if (bindings != null) {
            bindings.update(); // after the fox's animators
        }
        scene.update(); // the torch and the orb where they are now
        sync.update(seconds);
    }

    @Override
    public void close() {
        particles.close();
    }
}
