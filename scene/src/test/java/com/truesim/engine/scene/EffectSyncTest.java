package com.truesim.engine.scene;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.StartupFailure;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.render.particles.ParticleKeys;
import com.truesim.engine.render.particles.ParticleSimulation;
import com.truesim.engine.render.particles.Particles;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.Effect;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.Forces;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.ParticleCursor;
import com.truesim.scene.effects.Quality;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.Space;
import com.truesim.scene.effects.Spawn;
import com.truesim.scene.effects.Target;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The effect sync: a scene's effects through the runtime, the CPU emitters and the GPU, attached to nodes, stopped,
 * killed, paused and replaced; beams ending at nodes; allocation-free frames; and the effects plugin. Validated.
 */
class EffectSyncTest {

    private static final double FRAME = 1 / 60.0;
    private static final Key<Image> SEEN = Key.image("test.particlesSeen");

    private VulkanInstance instance;
    private VulkanDevice device;
    private FrameGraph graph;
    private Particles particles;
    private GpuImage target;
    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Effects effects = new Effects(scene);

    @BeforeEach
    void start() {
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        graph = new FrameGraph(device, 2);
        particles = new Particles(device, graph.frameSlots(), 4 << 20, 64);
        int usage = org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT
            | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        target = new GpuImage(device, 4, 4, org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM, usage,
            org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT);
        ExternalImage output = new ExternalImage("seen", 4, 4, Format.R8G8B8A8_UNORM, usage,
            org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED, org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
            .set(target.handle(), target.view());
        graph.add(new ParticleSimulation(particles)).add(new Seen()).output(SEEN, output);
    }

    /** Reads the simulated particles, as a draw would: the simulation runs for it. */
    private static final class Seen implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(SEEN);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer particles = g.require(ParticleKeys.PARTICLES);
            Image image = g.create("seen", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
            g.pass("particles seen").read(particles, BufferAccess.STORAGE_READ).write(image, Access.TRANSFER_DST)
                .record(ctx -> ctx.clear(image.mip(0), 0, 0, 0, 1));
            g.provide(SEEN, image);
        }
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            graph.close();
            particles.close();
            target.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** A torch: flames carried with it, sparks and smoke left behind, a CPU emitter of embers gameplay reads. */
    static EffectData torch() {
        return EffectData.builder()
            .emitter("flames", e -> e.space(Space.LOCAL).spawn(Spawn.rate(40)).init(Shape.disc(0.1f),
                Init.velocity(Bind.constant(0), Bind.random(0.5f, 1), Bind.constant(0)), Init.lifetime(0.5f)))
            .emitter("sparks", e -> e.spawn(Spawn.rate(20)).init(Shape.cone(0.3f, 0.05f),
                Init.speed(Bind.random(2, 3)), Init.lifetime(1)).update(Forces.gravity()))
            .emitter("embers", e -> e.target(Target.CPU).spawn(Spawn.rate(10)).init(Init.lifetime(2)))
            .build();
    }

    /** A frame as an application runs it: the scene, the effects (between frames), then the graph. */
    private void frame(EffectSync sync, double seconds) {
        scene.update();
        sync.update(seconds);
        graph.beginFrame();
        graph.submit(0, 0);
    }

    /** Frames until the GPU's statistics are of a frame after the last change. */
    private void settle(EffectSync sync, double seconds) {
        for (int f = 0; f < 4; f++) {
            frame(sync, seconds);
        }
        device.waitIdle();
    }

    @Test
    void effectsOnNodesSpawnFollowStopAndEnd() {
        EffectSync sync = new EffectSync(effects, particles);
        assertThat(sync.effects()).isSameAs(effects);
        assertThat(sync.particles()).isSameAs(particles);
        assertThat(sync.runtime().effects()).isSameAs(effects);
        long hand = scene.create();
        scene.setTranslation(hand, 3, 1, 0);
        scene.update();
        Effect torch = effects.play(torch(), hand).offset(0, 0.5, 0);
        for (int f = 0; f < 30; f++) {
            scene.setTranslation(hand, 3 + 0.05 * f, 1, 0);
            frame(sync, FRAME);
        }
        settle(sync, FRAME);
        assertThat(particles.alive(torch.id(), 0)).as("flames").isPositive();
        assertThat(particles.alive(torch.id(), 1)).as("sparks").isPositive();
        assertThat(particles.instance(torch.id(), 2)).as("a CPU emitter uploaded").isNotNegative();
        assertThat(effects.position(torch.id(), 1)).as("at its socket").isCloseTo(1.5, within(1e-12));
        ParticleCursor embers = sync.simulation().particles(torch.id(), 2, new ParticleCursor());
        assertThat(embers.count()).isPositive();
        // Stopped: no more spawns; once its particles died, it ends and its ranges stay warm.
        torch.stop();
        for (int f = 0; f < 150 && torch.alive(); f++) {
            frame(sync, FRAME);
        }
        assertThat(torch.alive()).isFalse();
        frame(sync, FRAME);
        assertThat(particles.instance(torch.id(), 0)).isEqualTo(-1);
        assertThat(particles.warmCount()).isOne();
        // Killed: gone at once.
        Effect again = effects.play(torch(), hand);
        frame(sync, FRAME);
        assertThat(particles.warmCount()).as("taken again").isZero();
        again.kill();
        frame(sync, FRAME);
        assertThat(particles.instance(again.id(), 0)).isEqualTo(-1);
    }

    @Test
    void pausedEffectsKeepTheirParticlesAndScaledOnesSlowDown() {
        EffectSync sync = new EffectSync(effects, particles, null, null);
        EffectData puffs = EffectData.builder().emitter("puffs", e -> e.spawn(Spawn.rate(30))
            .init(Init.lifetime(10))).build();
        Effect a = effects.play(puffs, 0, 0, 0);
        Effect b = effects.play(puffs, 1, 0, 0).timeScale(0.5f);
        for (int f = 0; f < 30; f++) {
            frame(sync, FRAME);
        }
        settle(sync, FRAME);
        int alive = particles.alive(a.id(), 0);
        assertThat(particles.alive(b.id(), 0)).as("half the time, half the particles").isCloseTo(alive / 2,
            within(2));
        effects.pause(true);
        settle(sync, FRAME);
        settle(sync, FRAME);
        int paused = particles.alive(a.id(), 0);
        settle(sync, FRAME);
        assertThat(particles.alive(a.id(), 0)).as("nothing spawns or dies while paused").isEqualTo(paused);
        effects.pause(false);
        settle(sync, 0); // a paused world: steps of 0
        int frozen = particles.alive(a.id(), 0);
        settle(sync, 0);
        assertThat(particles.alive(a.id(), 0)).isEqualTo(frozen);
        settle(sync, FRAME);
        assertThat(particles.alive(a.id(), 0)).isGreaterThan(frozen);
    }

    @Test
    void aReplacedAssetRestartsItsInstancesKeepingHandles() {
        EffectSync sync = new EffectSync(effects, particles);
        EffectData few = EffectData.builder().emitter("e", e -> e.capacity(64).spawn(Spawn.burst(0, 3))
            .init(Init.lifetime(100))).build();
        EffectData more = EffectData.builder().emitter("e", e -> e.capacity(64).spawn(Spawn.burst(0, 7))
            .init(Init.lifetime(100))).build();
        Effect effect = effects.play(few, 0, 0, 0);
        settle(sync, FRAME);
        assertThat(particles.alive(effect.id(), 0)).isEqualTo(3);
        effects.replace(few, more);
        settle(sync, FRAME);
        assertThat(effect.alive()).isTrue();
        assertThat(particles.alive(effect.id(), 0)).as("restarted with the new data").isEqualTo(7);
    }

    @Test
    void beamsEndAtNodesWhileBothLast() {
        EffectSync sync = new EffectSync(effects, particles);
        EffectData beam = EffectData.builder().emitter("beam", e -> e.spawn(Spawn.rate(1))
            .render(Renderer.beam())).build();
        long target = scene.create();
        long other = scene.create();
        scene.update();
        Effect zap = effects.play(beam, 0, 0, 0);
        Effect second = effects.play(beam, 0, 1, 0);
        sync.beamEnd(zap.id(), 0, target);
        sync.beamEnd(zap.id(), 0, other); // moved to another node
        sync.beamEnd(second.id(), 0, target);
        sync.beamEnd(second.id(), 1, target); // no such emitter: never drawn, dropped with its effect
        assertThat(sync.beamCount()).isEqualTo(3);
        scene.setTranslation(other, 2, 0, 0);
        frame(sync, FRAME);
        frame(sync, FRAME);
        assertThat(sync.beamCount()).isEqualTo(3);
        scene.remove(other);
        second.kill();
        frame(sync, FRAME);
        assertThat(sync.beamCount()).as("its end removed, the other's effect killed").isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> sync.beamEnd(zap.id(), 0, other));
        assertThatIllegalArgumentException().isThrownBy(() -> sync.beamEnd(second.id(), 0, target));
        for (int i = 0; i < 5; i++) { // growing
            sync.beamEnd(effects.play(beam, i, 0, 0).id(), 0, target);
        }
        assertThat(sync.beamCount()).isEqualTo(5);
    }

    @Test
    void framesAllocateNothing() throws Exception {
        try (JobSystem jobs = new JobSystem(2)) {
            EffectSync sync = new EffectSync(effects, particles, null, jobs);
            sync.quality(Quality.MEDIUM);
            assertThat(sync.runtime().quality()).isEqualTo(Quality.MEDIUM);
            long node = scene.create();
            for (int i = 0; i < 20; i++) {
                effects.play(torch(), node).offset(i, 0, 0).inheritVelocity(0.5f);
            }
            for (int f = 0; f < 60; f++) {
                scene.setTranslation(node, 0.01 * f, 0, 0);
                frame(sync, FRAME);
            }
            double[] x = {0};
            assertAllocationFree(() -> {
                x[0] += 0.01;
                scene.setTranslation(node, x[0], 0, 0);
                scene.update();
                sync.clearViewers();
                sync.addViewer(0, 2, -5);
                sync.update(FRAME);
            }, 500, 200, 5);
        }
    }

    @Test
    void thePluginSyncsAWorldsEffectsEachFrame() {
        EffectsPlugin plugin = new EffectsPlugin();
        assertThatIllegalStateException().isThrownBy(plugin::particles).withMessageContaining("not yet");
        Engine engine;
        try {
            engine = Engine.create(new Application() {
                @Override
                public void configure(AppConfig app) {
                    app.use(new Rendering().headless(true), plugin);
                }
            });
        } catch (StartupFailure e) {
            assumeThat(e).as("no Vulkan available: " + e.reason()).isNull();
            throw e;
        }
        try (engine) {
            EffectsPlugin fx = engine.get(EffectsPlugin.KEY);
            assertThat(fx.particles().pool().slots()).isPositive();
            assertThatIllegalStateException().isThrownBy(fx::sync);
            SceneGraph world = new SceneGraph(new ChangeClock());
            Effects playing = fx.effects(world);
            assertThatIllegalStateException().isThrownBy(() -> fx.effects(world));
            assertThat(fx.sync().effects()).isSameAs(playing);
            assertThat(fx.sync().runtime().quality()).isEqualTo(Quality.HIGH);
            engine.settings().set(EffectsPlugin.QUALITY, Quality.LOW);
            Effect torch = playing.play(torch(), 0, 0, 0);
            for (int f = 0; f < 10; f++) {
                engine.step();
            }
            assertThat(fx.sync().runtime().quality()).isEqualTo(Quality.LOW);
            assertThat(torch.time()).as("moved on by the frames").isPositive();
            assertThat(fx.particles().instance(torch.id(), 0)).isNotNegative();
        }
    }
}
