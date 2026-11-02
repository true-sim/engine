package com.truesim.engine.demo;

import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.particles.ParticleSimulation;
import com.truesim.engine.render.particles.Particles;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Bind;
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
 * The demo's effects: a fountain of sparks over a column of smoke, played at a point, simulated on the GPU and drawn in
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
        .build();

    private final Effects effects = new Effects(new SceneGraph(new ChangeClock()));
    private final EffectRuntime runtime = new EffectRuntime(effects);
    private final Particles particles;

    /** The demo's effects at ({@code x}, {@code y}, {@code z}), in a pool of 32 MB. */
    DemoEffects(VulkanDevice device, FrameSlots slots, double x, double y, double z) {
        particles = new Particles(device, slots, 32 << 20);
        effects.play(SPARKS, x, y, z);
    }

    Particles particles() {
        return particles;
    }

    ParticleSimulation simulation() {
        return new ParticleSimulation(particles);
    }

    /** Moves the effects on by {@code seconds}; while the frame records. */
    void update(double seconds) {
        runtime.update(seconds);
        particles.update(effects, runtime.spawns());
    }

    @Override
    public void close() {
        particles.close();
    }
}
