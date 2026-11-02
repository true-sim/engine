package com.truesim.engine.render.particles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
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
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ViewOptions;
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
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.Spawn;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * What curves (E18: keys searched per particle, cached per chunk) cost the update: a million particles under gravity
 * and drag, then with 8 curves over life of 2 keys, 8 keys and 8 evenly spaced keys (the reviewer note on E18: bake
 * long curves if they cost over 15 % of the update). GPU time of the "particle update" pass, median of 60 frames. And
 * what the sort of a view costs at 10,000, 100,000 and a million particles of one sorted emitter (every "particle
 * sort" pass). Run with {@code TRUESIM_BENCHMARK=true ./gradlew :engine-render:test --tests '*ParticleBenchmark*'}.
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

    @Test
    void sortTimes() {
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("bench").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            System.out.println("Particle sort of a view, one sorted emitter, " + instance.bestGpu().name());
            for (int n : new int[] {10_000, 100_000, PARTICLES}) {
                System.out.printf("  %,d particles: %.3f ms%n", n, sort(device, n));
            }
        }
    }

    /**
     * A million particles in a view of 1920 x 1080 (effects step 18): GPU time of the simulation's passes (the update
     * apart), the view's sort and its draw, medians of 60 frames; once on the graphics queue and once with the
     * simulation on the async compute queue (the frame's span then, as the passes overlap).
     */
    @Test
    void aMillionParticlesUpdatedSortedAndDrawn() {
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("bench").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            System.out.println("A million particles, one sorted emitter, 1920 x 1080, " + instance.bestGpu().name());
            for (boolean async : new boolean[] {false, true}) {
                double[] ms = view(device, PARTICLES, 1920, 1080, 0.03f, false, async, 0.02f, 4);
                System.out.printf("  %s: update %.3f ms, simulation %.3f ms, sort %.3f ms, draw %.3f ms, frame %.3f ms%n",
                    async ? "async compute" : "graphics queue", ms[0], ms[1], ms[2], ms[3], ms[4]);
            }
        }
    }

    /**
     * Overdraw (effects step 18): 600 large smoke puffs (3 m, per-pixel lit, sorted) filling a view at a Quest 3's eye
     * size (2064 x 2208) and at 1920 x 1080, drawn at full and at half resolution: GPU time of the draw passes (the
     * half one's depth, draw and upsample summed), medians of 60 frames.
     */
    @Test
    void overdrawOfLargeSmokeAtFullAndHalfResolution() {
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("bench").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            System.out.println("Overdraw: 600 smoke puffs of 3 m, lit per pixel, " + instance.bestGpu().name());
            for (int[] size : new int[][] {{2064, 2208}, {1920, 1080}}) {
                for (boolean half : new boolean[] {false, true}) {
                    double[] ms = view(device, 600, size[0], size[1], 3, half, false, 0, 1.2f);
                    System.out.printf("  %d x %d, %s resolution: draw %.3f ms%n", size[0], size[1],
                        half ? "half" : "full", ms[3]);
                }
            }
        }
    }

    /**
     * Medians over 60 frames of a view of {@code width} x {@code height} drawing {@code n} particles of {@code size}
     * metres: [the update, the simulation's passes, the sort's, the draw's (at half resolution: all of them), the
     * frame's span]. Lit per pixel, alpha blended and spread over a box of 8 m (smoke) when {@code size} is over 1,
     * else unlit small sparks in a ball of 5 m.
     */
    private static double[] view(VulkanDevice device, int n, int width, int height, float size, boolean half,
        boolean async, float speed, float alpha) {
        SceneGraph nodes = new SceneGraph(new ChangeClock());
        Effects effects = new Effects(nodes);
        EffectRuntime runtime = new EffectRuntime(effects);
        boolean smoke = size > 1;
        Renderer renderer = Renderer.billboard(Renderer.Facing.POSITION).set("half", half ? 1 : 0);
        if (smoke) {
            renderer = renderer.lighting(Renderer.Lighting.PER_PIXEL).set("soft", 0.5f);
        }
        Renderer drawn = renderer;
        EffectData data = EffectData.builder().emitter("e", e -> e.spawn(Spawn.burst(0, n))
            .init(smoke ? Shape.box(8, 8, 8) : Shape.sphere(5), Init.speed(Bind.random(0, speed)), Init.lifetime(1000),
                Init.size(Bind.constant(size)), Init.color(Bind.color(0.5f, 0.5f, 0.5f, alpha / 4)))
            .render(drawn)).build();
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
            | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        Camera camera = new Camera().position(0, 0, smoke ? 6 : 20).set(0, 0, -1, 0, 1, 0, Math.PI / 2, width, height,
            0.1);
        try (FrameGraph graph = new FrameGraph(device, 2);
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(64, 4, 4), graph.frameSlots());
             GpuScene scene = new GpuScene(device, pool, 4, graph.frameSlots());
             MeshletRenderer meshes = new MeshletRenderer(device, scene, VK_FORMAT_R16G16B16A16_SFLOAT,
                 VK_FORMAT_D32_SFLOAT, graph.frameSlots());
             Particles particles = new Particles(device, graph.frameSlots(),
                 (long) n * ParticlePool.SLOT_BYTES + (1 << 20), 16);
             GpuImage target = new GpuImage(device, width, height, VK_FORMAT_R16G16B16A16_SFLOAT, usage,
                 VK_IMAGE_ASPECT_COLOR_BIT)) {
            graph.timings(true);
            SceneGeometry geometry = SceneGeometry.meshlets(meshes);
            ViewPort port = graph.addView("bench").size(width, height);
            SceneView view = geometry.createView(port, camera, ViewOptions.of(256, 1 << 12));
            port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT)
                .clearColor(0, 0, 0)).add(new ParticleDraw(particles, view))
                .output(com.truesim.engine.render.Keys.SCENE_COLOR, new ExternalImage("bench", width, height,
                    Format.R16G16B16A16_SFLOAT, usage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .set(target.handle(), target.view()));
            graph.add(new ParticleSimulation(particles).async(async));
            effects.play(data, 0, 0, 0);
            List<double[]> frames = new ArrayList<>();
            for (int f = 0; f < 80; f++) {
                runtime.update(16_667L);
                graph.beginFrame();
                particles.update(runtime, null);
                view.set(1, MeshletRenderer.MODE_LIT);
                graph.submit(0, 0);
                GpuTimings timings = graph.timings();
                if (f < 20 || timings == null) {
                    continue;
                }
                double[] ms = new double[5];
                for (int i = 0; i < timings.count(); i++) {
                    String name = timings.pass(i);
                    double t = timings.millis(i);
                    if (Double.isNaN(t)) {
                        continue;
                    }
                    if (name.equals("particle update")) {
                        ms[0] += t;
                    }
                    if (name.startsWith("particle sort")) {
                        ms[2] += t;
                    } else if (name.startsWith("particle draw") || name.startsWith("particle half")
                        || name.startsWith("particle upsample") || name.startsWith("particle lighting")) {
                        ms[3] += t;
                    } else if (name.startsWith("particle") && !name.startsWith("particle cull")) {
                        ms[1] += t;
                    }
                }
                ms[4] = timings.spanMillis();
                frames.add(ms);
            }
            device.waitIdle();
            assertThat(particles.alive()).isEqualTo(n);
            double[] medians = new double[5];
            for (int k = 0; k < 5; k++) {
                int column = k;
                double[] values = frames.stream().mapToDouble(ms -> ms[column]).sorted().toArray();
                medians[k] = values[values.length / 2];
            }
            return medians;
        }
    }

    /** The median time of the passes sorting {@code n} particles of one emitter for a camera. */
    private static double sort(VulkanDevice device, int n) {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Effects effects = new Effects(scene);
        EffectRuntime runtime = new EffectRuntime(effects);
        EffectData data = EffectData.builder().emitter("e", e -> e.spawn(Spawn.burst(0, n))
            .init(Shape.sphere(5), Init.lifetime(1000))
            .render(Renderer.billboard(Renderer.Facing.POSITION))).build();
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        Camera camera = new Camera().position(0, 0, 20).set(0, 0, -1, 0, 1, 0, Math.PI / 2, 64, 64, 0.1);
        try (FrameGraph graph = new FrameGraph(device, 2);
             Particles particles = new Particles(device, graph.frameSlots(),
                 (long) n * ParticlePool.SLOT_BYTES + (1 << 20), 16);
             GpuImage target = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT)) {
            graph.timings(true);
            graph.add(new ParticleSimulation(particles)).add(new SortProbe(particles, camera)).output(PROBE,
                new ExternalImage("probe", 4, 4, Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
            effects.play(data, 0, 0, 0);
            List<Double> times = new ArrayList<>();
            for (int f = 0; f < 80; f++) {
                runtime.update(16_667L);
                graph.beginFrame();
                particles.update(effects, runtime.spawns());
                graph.submit(0, 0);
                GpuTimings timings = graph.timings();
                double sum = 0;
                for (int i = 0; f >= 20 && i < timings.count(); i++) {
                    if (timings.pass(i).startsWith("particle sort") && !Double.isNaN(timings.millis(i))) {
                        sum += timings.millis(i);
                    }
                }
                if (f >= 20) {
                    times.add(sum);
                }
            }
            device.waitIdle();
            assertThat(particles.alive()).isEqualTo(n);
            times.sort(null);
            return times.get(times.size() / 2);
        }
    }

    /** Requests a sort for {@code camera}, so it runs. */
    private record SortProbe(Particles particles, Camera camera) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(PROBE);
        }

        @Override
        public void build(GraphBuilder g) {
            var pool = g.require(ParticleKeys.PARTICLES);
            var state = g.require(ParticleKeys.STATE);
            ParticleSort.Order order = g.request(new ParticleSort(particles, camera, pool, state));
            Image image = g.create("probe", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
            g.pass("probe").read(order.sorted(), BufferAccess.STORAGE_READ).write(image, Access.TRANSFER_DST)
                .record(ctx -> ctx.clear(image.mip(0), 0, 0, 0, 1));
            g.provide(PROBE, image);
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
