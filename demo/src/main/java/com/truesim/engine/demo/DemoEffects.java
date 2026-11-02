package com.truesim.engine.demo;

import com.truesim.common.math.Maths;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.particles.ParticleSimulation;
import com.truesim.engine.render.particles.Particles;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.Effect;
import com.truesim.scene.effects.ColorGradient;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectRuntime;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.FloatCurve;
import com.truesim.scene.effects.Forces;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Over;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.Spawn;

/**
 * The demo's effects: a fountain of sparks with streaks of trails over a column of smoke, an orb circling it with a
 * ribbon behind and a beam reaching it, played at a point, simulated on the GPU and drawn in
 * every view given a {@code ParticleDraw}. Until the effect sync (effects step 8) follows a scene's effects, the demo
 * keeps its own {@link Effects} and runtime.
 */
final class DemoEffects implements AutoCloseable {

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

    private final Effects effects = new Effects(new SceneGraph(new ChangeClock()));
    private final EffectRuntime runtime = new EffectRuntime(effects);
    private final Particles particles;
    private final double x;
    private final double y;
    private final double z;
    private final Effect orb;
    private final Effect beam;
    private double time;

    /** The demo's effects at ({@code x}, {@code y}, {@code z}), in a pool of 32 MB. */
    DemoEffects(VulkanDevice device, FrameSlots slots, double x, double y, double z) {
        particles = new Particles(device, slots, 32 << 20);
        this.x = x;
        this.y = y;
        this.z = z;
        effects.play(SPARKS, x, y, z);
        orb = effects.play(RIBBON, x + 2, y + 1.5, z);
        beam = effects.play(BEAM, x, y + 0.3, z);
    }

    Particles particles() {
        return particles;
    }

    ParticleSimulation simulation() {
        return new ParticleSimulation(particles);
    }

    /** Moves the effects on by {@code seconds}; while the frame records. */
    void update(double seconds) {
        time += seconds;
        double ox = x + 2 * Maths.cos(1.5 * time);
        double oy = y + 1.5 + 0.4 * Maths.sin(3 * time);
        double oz = z + 2 * Maths.sin(1.5 * time);
        orb.moveTo(ox, oy, oz);
        runtime.update(seconds);
        particles.update(effects, runtime.spawns());
        particles.beamEnd(beam.id(), 0, ox, oy, oz);
    }

    @Override
    public void close() {
        particles.close();
    }
}
