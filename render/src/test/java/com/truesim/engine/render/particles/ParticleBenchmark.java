package com.truesim.engine.render.particles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.common.state.ChangeClock;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectRuntime;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.FloatCurve;
import com.truesim.scene.effects.Forces;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Module;
import com.truesim.scene.effects.Over;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.Spawn;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * What curves (E18: keys searched per particle, cached per chunk) cost the update: a million particles under gravity
 * and drag, then with 8 curves over life of 2 keys, 8 keys and 8 evenly spaced keys (the reviewer note on E18: bake
 * long curves if they cost over 15 % of the update). GPU time of the "particle update" pass, median of 60 frames. Run
 * with {@code TRUESIM_BENCHMARK=true ./gradlew :engine-render:test --tests '*ParticleBenchmark*'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class ParticleBenchmark {

    private static final int PARTICLES = 1 << 20;
    private static final Key<Image> PROBE = Key.image("bench.probe");

    @Test
    void curvesInTheUpdate() {
        FloatCurve two = FloatCurve.of(0, 1, 1, 0.5f);
        FloatCurve eight = FloatCurve.builder().cubic(0, 1).cubic(0.07f, 1.1f).linear(0.21f, 0.9f).cubic(0.3f, 1)
            .cubic(0.52f, 1.05f).linear(0.6f, 0.95f).cubic(0.83f, 1).linear(1, 0.9f).build();
        FloatCurve even = FloatCurve.builder().cubic(0, 1).cubic(1 / 7f, 1.1f).linear(2 / 7f, 0.9f).cubic(3 / 7f, 1)
            .cubic(4 / 7f, 1.05f).linear(5 / 7f, 0.95f).cubic(6 / 7f, 1).linear(1, 0.9f).build();
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("bench").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            System.out.println("Particle update, " + PARTICLES + " particles, " + instance.bestGpu().name());
            double base = run(device, null);
            System.out.printf("  gravity and drag: %.3f ms%n", base);
            for (Object[] c : new Object[][] {{"8 curves of 2 keys", two}, {"8 curves of 8 keys", eight},
                {"8 curves of 8 evenly spaced keys", even}}) {
                double ms = run(device, (FloatCurve) c[1]);
                System.out.printf("  + %s: %.3f ms (curves %.0f %% of the update)%n", c[0], ms,
                    100 * (ms - base) / ms);
            }
        }
    }

    /** The median update time of a million particles with 8 over-life curves {@code curve} (null: none). */
    private static double run(VulkanDevice device, FloatCurve curve) {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Effects effects = new Effects(scene);
        EffectRuntime runtime = new EffectRuntime(effects);
        List<Module> update = new ArrayList<>(List.of(Forces.gravity(Bind.constant(0.01f)), Forces.drag(0.1f)));
        for (int i = 0; curve != null && i < 8; i++) {
            update.add(Over.size(curve));
        }
        EffectData data = EffectData.builder().emitter("e", e -> e.spawn(Spawn.burst(0, PARTICLES))
            .init(Shape.sphere(5), Init.speed(Bind.random(0, 1)), Init.lifetime(1000))
            .update(update.toArray(Module[]::new))).build();
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (FrameGraph graph = new FrameGraph(device, 2);
             Particles particles = new Particles(device, graph.frameSlots(),
                 (long) PARTICLES * ParticlePool.SLOT_BYTES + (1 << 20), 16);
             GpuImage target = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT)) {
            graph.timings(true);
            graph.add(new ParticleSimulation(particles)).add(new Probe()).output(PROBE, new ExternalImage("probe", 4,
                4, Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .set(target.handle(), target.view()));
            effects.play(data, 0, 0, 0);
            List<Double> times = new ArrayList<>();
            for (int f = 0; f < 80; f++) {
                runtime.update(16_667L);
                graph.beginFrame();
                particles.update(effects, runtime.spawns());
                graph.submit(0, 0);
                GpuTimings timings = graph.timings();
                for (int i = 0; f >= 20 && i < timings.count(); i++) {
                    if (timings.pass(i).equals("particle update")) {
                        times.add(timings.millis(i));
                    }
                }
            }
            device.waitIdle();
            assertThat(particles.alive()).isEqualTo(PARTICLES);
            times.sort(null);
            return times.get(times.size() / 2);
        }
    }

    /** Requires the particles, so the simulation runs. */
    private static final class Probe implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(PROBE);
        }

        @Override
        public void build(GraphBuilder g) {
            var particles = g.require(ParticleKeys.PARTICLES);
            Image image = g.create("probe", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
            g.pass("probe").read(particles, BufferAccess.STORAGE_READ).write(image, Access.TRANSFER_DST)
                .record(ctx -> ctx.clear(image.mip(0), 0, 0, 0, 1));
            g.provide(PROBE, image);
        }
    }
}
