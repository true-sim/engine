package com.truesim.engine.demo;

import com.truesim.common.math.Maths;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.particles.ParticleSimulation;
import com.truesim.engine.render.particles.Particles;
import com.truesim.engine.scene.EffectSync;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Bind;
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
 * beam reaching it from the fountain (the beam's end is the orb's node). Simulated on the GPU and drawn in every view
 * given a {@code ParticleDraw}.
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
        sync = new EffectSync(effects, particles);
        this.x = x;
        this.y = y;
        this.z = z;
        torch = scene.create();
        orb = scene.create();
        long fountain = scene.create();
        scene.setTranslation(fountain, x, y, z);
        place(0);
        scene.update();
        effects.play(TORCH, torch).offset(0, 1.1, 0).inheritVelocity(1);
        effects.play(SPARKS, fountain);
        effects.play(RIBBON, orb);
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
        place(time);
        scene.update(); // the torch and the orb where they are now
        sync.update(seconds);
    }

    @Override
    public void close() {
        particles.close();
    }
}
