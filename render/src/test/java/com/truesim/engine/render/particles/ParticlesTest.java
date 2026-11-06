package com.truesim.engine.render.particles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.engine.render.RadixSort;
import com.truesim.common.state.ChangeClock;
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
import com.truesim.engine.render.layout.ParticleConstants;
import com.truesim.engine.render.layout.ParticleLayout;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.Push;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetType;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.Collide;
import com.truesim.scene.effects.ParticleEvent;
import com.truesim.scene.effects.ParticleWorld;
import com.truesim.scene.effects.ColorGradient;
import com.truesim.scene.effects.CpuSimulation;
import com.truesim.scene.effects.Effect;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectParameter;
import com.truesim.scene.effects.EffectRuntime;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.EmitterData;
import com.truesim.scene.effects.FloatCurve;
import com.truesim.scene.effects.Forces;
import com.truesim.scene.effects.Hash;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Module;
import com.truesim.scene.effects.Over;
import com.truesim.scene.effects.ParticleCursor;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.Space;
import com.truesim.scene.effects.Spawn;
import com.truesim.scene.effects.Target;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkBufferCopy;

/**
 * The GPU particle simulation against scene-effects' Java reference ({@link CpuSimulation}): the same asset as a GPU
 * and as a CPU emitter, with the same seed, simulated side by side over frames and compared particle by particle, per
 * module; spawn counts, order and overflow; seeds whatever the order; the hash against its fixed vectors. Validated,
 * with synchronization validation, over three frames or more.
 */
class ParticlesTest {

    static final EffectParameter.Float INTENSITY = EffectParameter.ofFloat("intensity");
    static final EffectParameter.Color TINT = EffectParameter.ofColor("tint");
    private static final Key<Image> PROBE = Key.image("test.particleProbe");
    private static final long FRAME = 16_667;

    private VulkanInstance instance;
    private VulkanDevice device;
    private FrameGraph graph;
    private Particles particles;
    private GpuImage target;
    private GpuBuffer poolCopy;
    private GpuBuffer stateCopy;
    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Effects effects = new Effects(scene);
    private final EffectRuntime runtime = new EffectRuntime(effects);
    private CpuSimulation cpu = new CpuSimulation(runtime, null);
    private final ParticleCursor cursor = new ParticleCursor();
    private ParticleSimulation simulation;
    private GpuBuffer orderCopy;

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
        start(2 << 20);
    }

    /** Particles in a pool of {@code budget} bytes, simulated and copied off the GPU every frame. */
    private void start(long budget) {
        particles = new Particles(device, graph.frameSlots(), budget, 64);
        poolCopy = GpuBuffer.hostVisible(device, particles.pool().buffer().size(), VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        stateCopy = GpuBuffer.hostVisible(device, 4L * ParticleConstants.STATE_WORDS * particles.maxEmitters(),
            VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        orderCopy = GpuBuffer.hostVisible(device, 4L * particles.pool().slots(), VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        target = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        ExternalImage output = new ExternalImage("probe", 4, 4, Format.R8G8B8A8_UNORM, usage,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
        simulation = new ParticleSimulation(particles);
        graph.add(simulation).add(new Probe(poolCopy, stateCopy, orderCopy)).output(PROBE, output);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            graph.close();
            particles.close();
            poolCopy.close();
            stateCopy.close();
            orderCopy.close();
            target.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** Copies the pool and the emitter state into host buffers each frame, after the simulation. */
    private record Probe(GpuBuffer pool, GpuBuffer state, GpuBuffer orders) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(PROBE);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer particles = g.require(ParticleKeys.PARTICLES);
            Buffer states = g.require(ParticleKeys.STATE);
            Buffer order = g.require(ParticleKeys.RIBBON_ORDER);
            Image image = g.create("probe", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
            g.pass("particle probe").read(particles, BufferAccess.TRANSFER_SRC).read(states, BufferAccess.TRANSFER_SRC)
                .read(order, BufferAccess.TRANSFER_SRC)
                .write(image, Access.TRANSFER_DST).sideEffects()
                .record(ctx -> {
                    ctx.clear(image.mip(0), 0, 0, 0, 1);
                    // Every frame copies into the same buffers: after the frames before
                    com.truesim.engine.vulkan.Barriers.memory(ctx.unsafe().commands(),
                        org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
                    try (var stack = stackPush()) {
                        VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                        region.size(pool.size());
                        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(ctx.unsafe().commands(),
                            ctx.unsafe().buffer(particles), pool.handle(), region);
                        region.size(state.size());
                        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(states),
                            state.handle(), region);
                        region.size(this.orders.size());
                        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(order),
                            this.orders.handle(), region);
                    }
                });
            g.provide(PROBE, image);
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    /** The same effect twice: emitter "e" on the GPU, then on the CPU. */
    static EffectData[] pair(Consumer<EmitterData.Builder> setup) {
        EffectData[] both = new EffectData[2];
        for (Target target : new Target[] {Target.GPU, Target.CPU}) {
            both[target == Target.GPU ? 0 : 1] = EffectData.builder().parameter(INTENSITY, 0.5f)
                .parameter(TINT, 1, 0.5f, 0.25f, 1).emitter("e", e -> setup.accept(e.target(target))).build();
        }
        return both;
    }

    /** Plays both of {@code pair} at ({@code x}, {@code y}, {@code z}) with seed {@code seed}: GPU then CPU. */
    private long[] play(EffectData[] pair, double x, double y, double z, int seed) {
        return new long[] {effects.play(pair[0], x, y, z).seed(seed).id(), effects.play(pair[1], x, y, z).seed(seed).id()};
    }

    /** A frame of {@code ticks}: the runtime, the Java reference, then the GPU's. */
    private void frame(long ticks) {
        scene.update();
        runtime.update(ticks);
        cpu.update();
        graph.beginFrame();
        particles.update(effects, runtime.spawns());
        graph.submit(0, 0);
    }

    /** A frame of {@code ticks} through the runtime's decisions: levels of detail, drawn fractions, placements. */
    private void frameThroughRuntime(long ticks) {
        scene.update();
        runtime.update(ticks);
        cpu.update();
        graph.beginFrame();
        particles.update(runtime, cpu);
        graph.submit(0, 0);
    }

    /** A GPU particle as read back: position, velocity, age, lifetime, color, size, seed, flags, spawn index. */
    private record Gpu(float x, float y, float z, float vx, float vy, float vz, float age, float lifetime, float r,
        float g, float b, float a, float size, int seed, int flags, int index) {
    }

    /** The live particles of GPU emitter {@code emitter} of {@code effect}, in order, after the last frame. */
    private List<Gpu> gpu(long effect, int emitter) {
        device.waitIdle();
        int instance = particles.instance(effect, emitter);
        assertThat(instance).as("an instance").isNotNegative();
        int alive = stateCopy.memory().getAtIndex(ValueLayout.JAVA_INT,
            (long) instance * ParticleConstants.STATE_WORDS + ParticleConstants.STATE_ALIVE);
        MemorySegment pool = poolCopy.memory();
        long base = ((long) particles.currentHalf() * particles.pool().slots() + particles.first(effect, emitter))
            * ParticleLayout.BYTES;
        List<Gpu> list = new ArrayList<>();
        for (int i = 0; i < alive; i++) {
            long p = base + (long) i * ParticleLayout.BYTES;
            int flags = pool.get(ValueLayout.JAVA_INT, p + ParticleLayout.FLAGS);
            if ((flags & ParticleConstants.PARTICLE_DEAD) != 0) {
                continue;
            }
            list.add(new Gpu(f(pool, p + ParticleLayout.POSITION), f(pool, p + ParticleLayout.POSITION + 4),
                f(pool, p + ParticleLayout.POSITION + 8), f(pool, p + ParticleLayout.VELOCITY),
                f(pool, p + ParticleLayout.VELOCITY + 4), f(pool, p + ParticleLayout.VELOCITY + 8),
                f(pool, p + ParticleLayout.AGE), f(pool, p + ParticleLayout.LIFETIME), f(pool, p + ParticleLayout.COLOR),
                f(pool, p + ParticleLayout.COLOR + 4), f(pool, p + ParticleLayout.COLOR + 8),
                f(pool, p + ParticleLayout.COLOR + 12), f(pool, p + ParticleLayout.SIZE),
                pool.get(ValueLayout.JAVA_INT, p + ParticleLayout.SEED), flags,
                pool.get(ValueLayout.JAVA_INT, p + ParticleLayout.SPAWN_INDEX)));
        }
        return list;
    }

    private static float f(MemorySegment m, long at) {
        return m.get(ValueLayout.JAVA_FLOAT, at);
    }

    /**
     * Asserts that GPU emitter 0 of {@code effects[0]} holds what CPU emitter 0 of {@code effects[1]} does, in order,
     * positions within {@code tolerance} metres (and the rest relatively), with the GPU's world-space positions offset
     * from ({@code ox}, {@code oy}, {@code oz}).
     */
    private void assertSame(long[] pair, double ox, double oy, double oz, double tolerance) {
        List<Gpu> gpu = gpu(pair[0], 0);
        cpu.particles(pair[1], 0, cursor);
        assertThat(gpu).as("particles").hasSize(cursor.count());
        assertThat(gpu).as("some particles").isNotEmpty();
        for (Gpu p : gpu) {
            cursor.next();
            String which = "particle " + p.index;
            assertThat(Integer.toUnsignedLong(p.index)).as(which).isEqualTo(cursor.spawnIndex());
            assertThat(p.seed).as("seed of " + which).isEqualTo(cursor.seed());
            assertThat((double) p.age).as("age of " + which).isCloseTo(cursor.age(), within(1e-4));
            assertThat(p.lifetime).as("lifetime of " + which).isCloseTo(cursor.lifetime(), within(1e-5f));
            assertThat(ox + p.x).as("x of " + which).isCloseTo(cursor.x(), within(tolerance));
            assertThat(oy + p.y).as("y of " + which).isCloseTo(cursor.y(), within(tolerance));
            assertThat(oz + p.z).as("z of " + which).isCloseTo(cursor.z(), within(tolerance));
            assertThat((double) p.vx).as("vx of " + which).isCloseTo(cursor.vx(), within(tolerance));
            assertThat((double) p.vy).as("vy of " + which).isCloseTo(cursor.vy(), within(tolerance));
            assertThat((double) p.vz).as("vz of " + which).isCloseTo(cursor.vz(), within(tolerance));
            assertThat(p.size).as("size of " + which).isCloseTo(cursor.size(), within(1e-4f));
            float[] color = {p.r, p.g, p.b, p.a};
            for (int c = 0; c < 4; c++) {
                assertThat(color[c]).as("color " + c + " of " + which).isCloseTo(cursor.color(c), within(1e-4f));
            }
        }
    }

    /** Runs {@code setup}'s pair for {@code frames} frames of {@code ticks} and compares them. */
    private void compare(Consumer<EmitterData.Builder> setup, int frames, long ticks, double tolerance) {
        long[] pair = play(pair(setup), 10, 2, -3, 1234);
        for (int f = 0; f < frames; f++) {
            frame(ticks);
        }
        assertSame(pair, 10, 2, -3, tolerance);
    }

    // ---------------------------------------------------------------------------------------------- the hash

    /** Runs the hash on the GPU on {@code inputs} (function, a, b per vector). */
    private int[] gpuHashes(int[] inputs) {
        int count = inputs.length / 3;
        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        try (GpuBuffer in = GpuBuffer.hostVisible(device, 4L * inputs.length, usage);
             GpuBuffer out = GpuBuffer.hostVisible(device, 4L * count, usage)) {
            in.memory().copyFrom(MemorySegment.ofArray(inputs));
            Key<Image> key = Key.image("test.hash");
            Technique hash = new Technique() {
                @Override
                public List<Key<?>> provides() {
                    return List.of(key);
                }

                @Override
                public void build(GraphBuilder g) {
                    Pipeline pipeline = g.computeProgram("shaders/test/particlehash.spv", "hashMain", 24);
                    Push push = new Push();
                    Image image = g.create("hash", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
                    g.pass("hash").write(image, Access.TRANSFER_DST).sideEffects().record(ctx -> {
                        ctx.clear(image.mip(0), 0, 0, 0, 1);
                        ctx.dispatch(pipeline, push.clear().address(in.deviceAddress()).address(out.deviceAddress())
                            .u32(count), (count + 63) / 64);
                    });
                    g.provide(key, image);
                }
            };
            int usageImage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            try (FrameGraph hashing = new FrameGraph(device, 1);
                 GpuImage image = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usageImage,
                     VK_IMAGE_ASPECT_COLOR_BIT)) {
                hashing.add(hash).output(key, new ExternalImage("hash", 4, 4, Format.R8G8B8A8_UNORM, usageImage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(image.handle(), image.view()));
                for (int f = 0; f < 3; f++) {
                    hashing.beginFrame();
                    hashing.submit(0, 0);
                }
                device.waitIdle();
            }
            return out.memory().toArray(ValueLayout.JAVA_INT);
        }
    }

    @Test
    void theHashMatchesScenesFixedVectors() throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                Hash.class.getResourceAsStream("hash-vectors.csv"), StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (!line.startsWith("#")) {
                    lines.add(line);
                }
            }
        }
        assertThat(lines).hasSize(26);
        int[] inputs = new int[3 * lines.size()];
        int[] expected = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",", -1);
            inputs[3 * i] = switch (f[0]) {
                case "pcg" -> 0;
                case "combine" -> 1;
                default -> 2;
            };
            inputs[3 * i + 1] = Integer.parseUnsignedInt(f[1], 16);
            inputs[3 * i + 2] = f[2].isEmpty() ? 0 : Integer.parseUnsignedInt(f[2], 16);
            expected[i] = Integer.parseUnsignedInt(f[3], 16);
        }
        int[] actual = gpuHashes(inputs);
        for (int i = 0; i < lines.size(); i++) {
            assertThat(Integer.toHexString(actual[i])).as(lines.get(i)).isEqualTo(Integer.toHexString(expected[i]));
        }
    }

    // ---------------------------------------------------------------------------------------------- per module

    @Test
    void ballisticParticlesMatchTheReference() {
        for (Module[] forces : new Module[][] {{Forces.gravity()}, {Forces.gravity(), Forces.drag(0.8f)},
            {Forces.gravity(Bind.random(0.5f, 1.5f)), Forces.drag(Bind.param(INTENSITY))}}) {
            compare(e -> e.spawn(Spawn.rate(30)).init(Init.lifetime(10),
                Init.velocity(Bind.constant(3), Bind.random(5, 10), Bind.constant(-1))).update(forces),
                60, FRAME, 2e-3);
        }
    }

    @Test
    void forcesMatchTheReference() {
        effects.wind(3, 0, -1);
        Module[][] cases = {
            {Forces.orbit(Bind.constant(2), 0.3f, 1, 0)},
            {Forces.vortex(Bind.constant(3), 0.5f, 0, 1, 0.2f)},
            {Forces.point(Bind.constant(4), 1, 0.5f, 0).with("radius", 3f).with("killRadius", 0.3f)},
            {Forces.line(Bind.constant(2), 1, 0, 0).with("y", 0.5f).with("radius", 4f)},
            {Forces.curl(Bind.constant(3), 0.8f, 0.4f, 2)},
            {Forces.wind(1.5f, 1, 0.5f), Forces.gravity(Bind.constant(0.2f))},
            {Forces.gravity(), Forces.speedLimit(2)},
            {Forces.drag(0.5f), Forces.orbit(1.5f), Forces.vortex(1), Forces.speedLimit(3)}};
        for (Module[] forces : cases) {
            compare(e -> e.spawn(Spawn.rate(40)).init(Shape.sphere(1.5f), Init.lifetime(10),
                Init.velocity(Bind.random(-1, 1), Bind.random(0, 2), Bind.random(-1, 1))).update(forces),
                20, FRAME, 1e-2);
        }
    }

    @Test
    void collisionsWithCollidersMatchTheReference() {
        long floor = scene.create();
        scene.setTranslation(floor, 10, 0, -3);
        long ball = scene.create();
        scene.setTranslation(ball, 10.5, 0.8, -3);
        long crate = scene.create();
        scene.setTranslation(crate, 9, 0.5, -2.5);
        scene.setRotation(crate, 0, Math.sin(0.4), 0, Math.cos(0.4));
        long pole = scene.create();
        scene.setTranslation(pole, 11, 0.5, -3.5);
        scene.update();
        effects.colliders().plane(floor, 1);
        effects.colliders().sphere(ball, 0.4f, 1);
        effects.colliders().box(crate, 0.4f, 0.3f, 0.4f, 1);
        effects.colliders().capsule(pole, 0.2f, 0.5f, 1);
        effects.colliders().sphere(ball, 3, 2); // another layer
        Collide.Collision[] responses = {Collide.colliders(1).bounce(0.6f).friction(0.1f),
            Collide.colliders(1).slide().radius(0.05f), Collide.colliders(1).stick(), Collide.colliders(1).kill()};
        for (Collide.Collision collision : responses) {
            compare(e -> e.spawn(Spawn.rate(60)).init(Shape.disc(1.5f), Init.lifetime(3), Init.size(Bind.constant(0.1f)),
                Init.velocity(Bind.random(-1, 1), Bind.random(1, 3), Bind.random(-1, 1)))
                .update(Forces.gravity()).collide(collision), 60, FRAME, 1e-2);
        }
    }

    @Test
    void collisionsWithTheHeightFieldMatchTheReference() {
        ParticleWorld slope = new ParticleWorld() {
            @Override
            public double height(double x, double z) {
                return 0.2 * x - 0.1 * z - 1;
            }

            @Override
            public boolean raycast(double x, double y, double z, double dx, double dy, double dz, double max, Hit hit) {
                return false;
            }
        };
        cpu = new CpuSimulation(runtime, slope);
        particles.heights(slope, 64, 65).heightFocus(10, -3);
        compare(e -> e.spawn(Spawn.rate(60)).init(Shape.disc(2), Init.lifetime(3), Init.size(Bind.constant(0.1f)),
            Init.velocity(Bind.random(-1, 1), Bind.random(0, 2), Bind.random(-1, 1)))
            .update(Forces.gravity()).collide(Collide.height().bounce(0.3f)), 60, FRAME, 1e-2);
        assertThat(particles.heightRefreshes()).isOne();
        particles.heightFocus(40, -3); // far enough: taken again
        frame(FRAME);
        assertThat(particles.heightRefreshes()).isEqualTo(2);
        particles.heights(null, 0, 0);
        frame(FRAME);
        assertThatIllegalArgumentException().isThrownBy(() -> particles.heights(slope, 0, 64));
        assertThatIllegalArgumentException().isThrownBy(() -> particles.heights(slope, 10, 1));
    }

    @Test
    void localSpaceParticlesCollideInTheWorldOnTheGpu() {
        long floor = scene.create();
        scene.update();
        effects.colliders().plane(floor, 1);
        long holder = scene.create();
        scene.setTranslation(holder, 0, 2, 0);
        scene.setRotation(holder, Math.sin(0.5), 0, 0, Math.cos(0.5));
        scene.update();
        EffectData[] local = pair(e -> e.space(Space.LOCAL).spawn(Spawn.burst(0, 5)).init(Shape.disc(0.5f),
            Init.lifetime(100)).update(Forces.gravity()).collide(Collide.colliders(1).stick()));
        long[] l = {effects.play(local[0], holder).seed(6).id(), effects.play(local[1], holder).seed(6).id()};
        for (int f = 0; f < 90; f++) {
            frame(FRAME);
        }
        List<Gpu> gpu = gpu(l[0], 0);
        cpu.particles(l[1], 0, cursor);
        assertThat(gpu).hasSize(cursor.count()).hasSize(5);
        for (Gpu p : gpu) {
            cursor.next();
            double[] r = rotate(Math.sin(0.5), 0, 0, Math.cos(0.5), p.x, p.y, p.z);
            assertThat(2 + r[1]).as("on the floor, in the world").isCloseTo(0.05, within(1e-3));
            assertThat(cursor.y()).isCloseTo(0.05, within(1e-3));
            assertThat(p.flags & ParticleConstants.PARTICLE_STUCK).isNotZero();
        }
    }

    /** Fireworks: a rocket bursting into stars on its death, each into sparkles on theirs (scene's EventsTest). */
    private static EffectData fireworks(Target target) {
        return EffectData.builder()
            .emitter("rocket", e -> e.target(target).spawn(Spawn.burst(0, 2)).init(Shape.disc(0.5f),
                    Init.lifetime(0.3f), Init.velocity(Bind.random(-1, 1), Bind.constant(8), Bind.constant(0)),
                    Init.color(Bind.color(1, 0.5f, 0.2f, 1)), Init.size(Bind.constant(0.4f)))
                .update(Forces.gravity()).event(ParticleEvent.onDeath("stars", Bind.random(10, 20))))
            .emitter("stars", e -> e.target(target).capacity(40).spawn(Spawn.fromEvents())
                .init(Shape.point(), Init.speed(Bind.constant(4)), Init.lifetime(Bind.random(0.2f, 0.3f)),
                    Init.inherit(0.5f))
                .update(Forces.gravity(), Forces.drag(0.5f))
                .event(ParticleEvent.onDeath("sparkles", Bind.constant(3)).chance(0.7f)))
            .emitter("sparkles", e -> e.target(target).capacity(120).spawn(Spawn.fromEvents())
                .init(Shape.sphere(0.1f), Init.lifetime(2), Init.inherit(0, true, false)))
            .build();
    }

    /** Emitter {@code emitter} of GPU effect {@code gpu} against the CPU's of {@code cpuEffect}, in order. */
    private void assertSameEmitter(long gpu, long cpuEffect, int emitter, double ox, double oy, double oz) {
        List<Gpu> list = gpu(gpu, emitter);
        cpu.particles(cpuEffect, emitter, cursor);
        assertThat(list).as("emitter " + emitter).hasSize(cursor.count());
        for (Gpu p : list) {
            cursor.next();
            String which = "emitter " + emitter + " particle " + p.index;
            assertThat(Integer.toUnsignedLong(p.index)).as(which).isEqualTo(cursor.spawnIndex());
            assertThat(p.seed).as("seed of " + which).isEqualTo(cursor.seed());
            assertThat(ox + p.x).as("x of " + which).isCloseTo(cursor.x(), within(1e-2));
            assertThat(oy + p.y).as("y of " + which).isCloseTo(cursor.y(), within(1e-2));
            assertThat(oz + p.z).as("z of " + which).isCloseTo(cursor.z(), within(1e-2));
            assertThat(p.r).as("red of " + which).isCloseTo(cursor.color(0), within(1e-4f));
            assertThat(p.size).as("size of " + which).isCloseTo(cursor.size(), within(1e-4f));
        }
    }

    @Test
    void fireworksBurstOnTheDeathOfABurstAsTheReferenceDoes() {
        long gpuEffect = effects.play(fireworks(Target.GPU), 2, 1, -3).seed(77).id();
        long cpuEffect = effects.play(fireworks(Target.CPU), 2, 1, -3).seed(77).id();
        int[] seen = new int[3];
        for (int f = 0; f < 50; f++) {
            frame(FRAME);
            if (f % 5 == 4) {
                for (int e = 0; e < 3; e++) {
                    assertSameEmitter(gpuEffect, cpuEffect, e, 2, 1, -3);
                    seen[e] = Math.max(seen[e], gpu(gpuEffect, e).size());
                }
            }
        }
        assertThat(seen[1]).as("stars burst").isGreaterThan(15);
        assertThat(seen[2]).as("and sparkles from them").isGreaterThan(15);
        device.waitIdle();
        frame(FRAME);
        frame(FRAME);
        device.waitIdle();
        assertThat(particles.events()).as("no events this late").isZero();
    }

    /** Sparks each emitting a trail of 30 a second (particles as emitters, step 19), on {@code target}. */
    private static EffectData emitting(Target target) {
        return EffectData.builder()
            .emitter("sparks", e -> e.target(target).spawn(Spawn.burst(0, 6)).init(Shape.disc(0.5f),
                    Init.lifetime(Bind.random(0.6f, 0.9f)), Init.velocity(Bind.random(-2, 2), Bind.constant(5),
                    Bind.random(-2, 2)))
                .update(Forces.gravity()).event(ParticleEvent.emit("trail", Bind.constant(30))))
            .emitter("trail", e -> e.target(target).capacity(200).spawn(Spawn.fromEvents())
                .init(Init.lifetime(5), Init.inherit(0.2f)))
            .build();
    }

    @Test
    void particlesAsEmittersMatchTheReferenceAndTheirRates() {
        long gpuEffect = effects.play(emitting(Target.GPU), 1, 2, -3).seed(9).id();
        long cpuEffect = effects.play(emitting(Target.CPU), 1, 2, -3).seed(9).id();
        for (int f = 0; f < 30; f++) {
            frame(FRAME);
            if (f % 6 == 5) {
                assertSameEmitter(gpuEffect, cpuEffect, 0, 1, 2, -3);
                assertSameEmitter(gpuEffect, cpuEffect, 1, 1, 2, -3);
            }
        }
        // Each spark has emitted floor(30 x its age) children, give or take the one being carried: 30 frames, 0.5 s
        List<Gpu> trail = gpu(gpuEffect, 1);
        assertThat(trail.size()).as("6 sparks, 15 each").isBetween(6 * 14, 6 * 15);
        // Spread along each spark's path: no two of one spark at one place
        assertThat(trail.stream().map(p -> Math.round(p.y * 1000) + "," + Math.round(p.x * 1000)).distinct().count())
            .isGreaterThan(80);
        assertThat(trail.stream().map(p -> p.seed).distinct().count()).as("each child's own seed").isGreaterThan(80);
    }

    @Test
    void ribbonsPerParentKeepEachSparksTrailTogetherInSpawnOrder() {
        EffectData data = EffectData.builder()
            .emitter("sparks", e -> e.spawn(Spawn.rate(20)).init(Shape.sphere(1), Init.lifetime(0.7f),
                    Init.speed(Bind.constant(3)))
                .event(ParticleEvent.emit("trail", Bind.constant(40))))
            .emitter("trail", e -> e.capacity(600).spawn(Spawn.fromEvents()).init(Init.lifetime(0.3f))
                .render(Renderer.ribbon().set("ribbons", 0)))
            .build();
        long effect = effects.play(data, 0, 0, 0).id();
        for (int f = 0; f < 40; f++) {
            frame(FRAME);
        }
        device.waitIdle();
        int instance = particles.instance(effect, 1);
        int alive = stateCopy.memory().getAtIndex(ValueLayout.JAVA_INT,
            (long) instance * ParticleConstants.STATE_WORDS + ParticleConstants.STATE_ALIVE);
        assertThat(alive).as("trails").isGreaterThan(100);
        MemorySegment pool = poolCopy.memory();
        long base = (long) particles.currentHalf() * particles.pool().slots() + particles.first(effect, 1);
        int first = particles.first(effect, 1);
        java.util.Set<Integer> done = new java.util.HashSet<>();
        int ribbon = -1;
        int index = -1;
        int ribbons = 0;
        for (int i = 0; i < alive; i++) {
            int k = orderCopy.memory().getAtIndex(ValueLayout.JAVA_INT, first + i);
            long p = (base + k) * ParticleLayout.BYTES;
            int id = pool.get(ValueLayout.JAVA_INT, p + ParticleLayout.CUSTOM + 4);
            int spawn = pool.get(ValueLayout.JAVA_INT, p + ParticleLayout.SPAWN_INDEX);
            if (id != ribbon) {
                assertThat(done.add(id)).as("ribbon " + id + " in one run").isTrue();
                ribbon = id;
                ribbons++;
            } else {
                assertThat(spawn).as("in spawn order").isGreaterThan(index);
            }
            index = spawn;
        }
        assertThat(ribbons).as("a ribbon per spark alive").isBetween(10, 20);
    }

    @Test
    void emittersUpdatedEveryOtherFrameMoveByTheTimeBetween() {
        // Past 50 m from the viewer its level of detail updates every second frame
        EffectData data = EffectData.builder(com.truesim.scene.effects.EffectType.builder("far").lod(50, 1, 2, 1)
                .build())
            .emitter("e", e -> e.spawn(Spawn.burst(0, 3)).init(Init.lifetime(100),
                Init.velocity(Bind.constant(1), Bind.constant(0), Bind.constant(0))))
            .build();
        runtime.addViewer(0, 0, 0);
        long effect = effects.play(data, 100, 0, 0).id();
        float[] xs = new float[12];
        for (int f = 0; f < xs.length; f++) {
            frameThroughRuntime(FRAME);
            xs[f] = gpu(effect, 0).getFirst().x;
        }
        int still = 0;
        for (int f = 2; f < xs.length; f++) {
            still += xs[f] == xs[f - 1] ? 1 : 0;
            // never behind by more than the frame held
            assertThat((double) xs[f]).isCloseTo((f + 1) * FRAME * 1e-6, within(FRAME * 1.1e-6));
        }
        assertThat(still).as("held every other frame").isBetween(4, 6);
        assertThat(runtime.updateEvery(effect)).isEqualTo(2);
    }

    @Test
    void sharedEffectsSimulateTheirVariantsOnly() {
        EffectData torch = EffectData.builder().emitter("e", e -> e.space(Space.LOCAL).spawn(Spawn.rate(30))
                .init(Init.lifetime(1), Init.velocity(Bind.constant(0), Bind.constant(1), Bind.constant(0)))
                .render(Renderer.billboard(Renderer.Facing.POSITION)))
            .shared(3).build();
        for (int i = 0; i < 40; i++) {
            effects.play(torch, i, 0, 0);
        }
        for (int f = 0; f < 5; f++) {
            frameThroughRuntime(FRAME);
        }
        assertThat(particles.emitterCount()).as("three variants simulated").isEqualTo(3);
        assertThat(particles.drawCount()).as("and drawn at forty places").isEqualTo(43);
        for (int i = 0; i < 3; i++) {
            assertThat(particles.listHidden(i)).as("variants are not drawn as themselves").isTrue();
        }
        assertThat(particles.listPlacement(3)).isTrue();
    }

    @Test
    void theSimulationOnTheAsyncQueueMatchesTheReference() {
        simulation.async(true);
        assertThat(simulation.async()).isTrue();
        long gpuEffect = effects.play(fireworks(Target.GPU), 2, 1, -3).seed(77).id();
        long cpuEffect = effects.play(fireworks(Target.CPU), 2, 1, -3).seed(77).id();
        for (int f = 0; f < 30; f++) {
            frame(FRAME);
        }
        for (int e = 0; e < 3; e++) {
            assertSameEmitter(gpuEffect, cpuEffect, e, 2, 1, -3);
        }
    }

    @Test
    void theSameChildrenInTwoRuns() {
        long[][] runs = new long[2][];
        for (int run = 0; run < 2; run++) {
            Effect played = effects.play(fireworks(Target.GPU), 0, 0, 0).seed(9);
            long id = played.id();
            for (int f = 0; f < 45; f++) {
                frame(FRAME);
            }
            List<Gpu> sparkles = gpu(id, 2);
            runs[run] = new long[sparkles.size()];
            for (int i = 0; i < sparkles.size(); i++) {
                Gpu p = sparkles.get(i);
                runs[run][i] = (long) p.seed << 32 ^ Float.floatToIntBits(p.x) ^ (long) Float.floatToIntBits(p.y) << 7;
            }
            played.kill();
            frame(FRAME);
        }
        assertThat(runs[0]).isNotEmpty();
        assertThat(runs[1]).as("slots, seeds and places alike").containsExactly(runs[0]);
    }

    @Test
    void theSmallEventSortOrdersAsTheRadixSortDoes() {
        // 1,900 events in a frame (two groups of the radix sort, within the small sort): two parents, two targets
        EffectData data = EffectData.builder()
            .emitter("a", e -> e.capacity(700).spawn(Spawn.burst(0, 700)).init(Shape.sphere(1), Init.lifetime(0.05f))
                .event(ParticleEvent.onDeath("x", Bind.random(1, 3)), ParticleEvent.onDeath("y", Bind.constant(1))))
            .emitter("b", e -> e.capacity(500).spawn(Spawn.burst(0, 500)).init(Shape.sphere(2), Init.lifetime(0.05f))
                .event(ParticleEvent.onDeath("x", Bind.constant(1))))
            .emitter("x", e -> e.capacity(3000).spawn(Spawn.fromEvents()).init(Init.lifetime(10), Init.inherit(1)))
            .emitter("y", e -> e.capacity(700).spawn(Spawn.fromEvents()).init(Init.lifetime(10),
                Init.speed(Bind.constant(1))))
            .build();
        long[][][] runs = new long[2][2][];
        long sent = 0;
        for (int run = 0; run < 2; run++) {
            particles.eventSort(run == 0 ? Particles.EVENTS_SMALL : Particles.EVENTS_RADIX);
            Effect played = effects.play(data, 0, 0, 0).seed(5);
            for (int f = 0; f < 8; f++) {
                frame(FRAME);
                device.waitIdle();
                sent = Math.max(sent, particles.events());
                assertThat(particles.eventsDropped()).as("none dropped").isZero();
            }
            for (int e = 0; e < 2; e++) {
                List<Gpu> children = gpu(played.id(), 2 + e);
                runs[run][e] = new long[children.size()];
                for (int i = 0; i < children.size(); i++) {
                    Gpu p = children.get(i);
                    runs[run][e][i] = (long) p.seed << 32 ^ Float.floatToIntBits(p.x)
                        ^ (long) Float.floatToIntBits(p.z) << 7;
                }
            }
            played.kill();
            frame(FRAME);
        }
        particles.eventSort(Particles.EVENTS_EITHER);
        assertThat(sent).as("over a radix group, within the small sort").isGreaterThan(RadixSort.GROUP)
            .isLessThanOrEqualTo(ParticleConstants.EVENT_SMALL);
        assertThat(runs[0][0]).hasSizeGreaterThan(1000);
        assertThat(runs[0][1]).hasSize(700);
        assertThat(runs[1][0]).as("children in the same slots, alike").containsExactly(runs[0][0]);
        assertThat(runs[1][1]).containsExactly(runs[0][1]);
    }

    @Test
    void eventsBeyondTheBufferAreDroppedAndCounted() {
        device.waitIdle();
        graph.close();
        particles.close();
        poolCopy.close();
        stateCopy.close();
        target.close();
        graph = new FrameGraph(device, 2);
        start(ParticlePool.SLOT_BYTES * 12_000L);
        ParticleEvent[] deaths = new ParticleEvent[8];
        for (int k = 0; k < deaths.length; k++) {
            deaths[k] = ParticleEvent.onDeath("child", Bind.constant(1));
        }
        EffectData many = EffectData.builder()
            .emitter("many", e -> e.spawn(Spawn.burst(0, 10_000)).init(Init.lifetime(0.05f)).event(deaths))
            .emitter("child", e -> e.capacity(1000).spawn(Spawn.fromEvents()).init(Init.lifetime(1)))
            .build();
        // After quiet frames only the small sort is recorded: the burst keeps what it takes
        Effect first = effects.play(many, 0, 0, 0);
        boolean counted = false;
        for (int f = 0; f < 12 && !counted; f++) {
            frame(FRAME);
            device.waitIdle();
            counted = particles.eventsDropped() > 0;
        }
        assertThat(counted).as("80,000 events, room for the small sort's").isTrue();
        assertThat(particles.events()).isEqualTo(80_000);
        assertThat(particles.eventsDropped()).isEqualTo(80_000 - ParticleConstants.EVENT_SMALL);
        // Seen, the radix sort is recorded: the next burst keeps what the event buffer holds
        first.kill();
        effects.play(many, 0, 0, 0);
        counted = false;
        for (int f = 0; f < 12 && !counted; f++) {
            frame(FRAME);
            device.waitIdle();
            counted = particles.eventsDropped() > 0 && particles.events() == 80_000;
        }
        assertThat(counted).as("80,000 events, room for 65,536").isTrue();
        assertThat(particles.eventsDropped()).isEqualTo(80_000 - ParticleConstants.EVENT_CAPACITY);
    }

    @Test
    void aThousandImpactsInAFrameAreOneInstance() {
        EffectData impact = EffectData.builder().emitter("chips", e -> e.capacity(4000).duration(0.1)
            .spawn(Spawn.burst(0, 3)).init(Init.lifetime(1), Init.speed(Bind.random(1, 2)), Shape.cone(0.5f, 0)))
            .build();
        java.util.SplittableRandom random = new java.util.SplittableRandom(4);
        for (int i = 0; i < 1000; i++) {
            effects.burst(impact, random.nextDouble(1, 30), 0, random.nextDouble(1, 30), 0, 1, 0, i);
        }
        frame(FRAME);
        frame(FRAME);
        frame(FRAME);
        device.waitIdle();
        assertThat(particles.emitterCount()).as("one shared instance in the cell, one draw").isOne();
        assertThat(particles.alive()).isEqualTo(3000);
    }

    @Test
    void localEmittersSendAndReceiveEvents() {
        long node = scene.create();
        scene.setTranslation(node, 1, 2, 3);
        scene.setRotation(node, Math.sin(0.4), 0, 0, Math.cos(0.4));
        scene.update();
        EffectData[] both = new EffectData[2];
        for (Target target : new Target[] {Target.GPU, Target.CPU}) {
            both[target.ordinal()] = EffectData.builder()
                .emitter("parent", e -> e.target(target).space(Space.LOCAL).spawn(Spawn.rate(20))
                    .init(Init.lifetime(0.2f), Init.velocity(Bind.constant(0), Bind.constant(1), Bind.constant(0)))
                    .event(ParticleEvent.onBirth("child", Bind.constant(1)), ParticleEvent.every(0.05, "world",
                        Bind.constant(1))))
                .emitter("child", e -> e.target(target).space(Space.LOCAL).capacity(40).spawn(Spawn.fromEvents())
                    .init(Init.lifetime(0.5f), Init.inherit(1)))
                .emitter("world", e -> e.target(target).capacity(80).spawn(Spawn.fromEvents())
                    .init(Init.lifetime(0.5f)))
                .build();
        }
        long g = effects.play(both[Target.GPU.ordinal()], node).seed(3).id();
        long c = effects.play(both[Target.CPU.ordinal()], node).seed(3).id();
        for (int f = 0; f < 20; f++) {
            frame(FRAME);
        }
        assertThat(gpu(g, 1)).hasSize(cpu.count(c, 1)).isNotEmpty();
        assertSameEmitter(g, c, 2, 1, 2, 3);
    }

    @Test
    void orbitsKeepTheirRadiusOnTheGpu() {
        long effect = effects.play(EffectData.builder().emitter("e", e -> e.spawn(Spawn.burst(0, 50))
            .init(Shape.sphere(2), Init.lifetime(100)).update(Forces.orbit(3))).build(), 4, 1, -2).id();
        frame(1L);
        frame(FRAME);
        List<Gpu> start = gpu(effect, 0);
        for (int f = 0; f < 60; f++) {
            frame(FRAME);
        }
        List<Gpu> end = gpu(effect, 0);
        assertThat(end).hasSameSizeAs(start);
        for (int i = 0; i < start.size(); i++) {
            Gpu a = start.get(i);
            Gpu b = end.get(i);
            assertThat(Math.sqrt(b.x * b.x + b.z * b.z)).as("radius about the axis")
                .isCloseTo(Math.sqrt(a.x * a.x + a.z * a.z), within(1e-3));
            assertThat((double) b.y).isCloseTo(a.y, within(1e-4));
        }
    }

    @Test
    void localSpaceForcesMatchTheReference() {
        long node = scene.create();
        scene.setTranslation(node, 1, 2, 3);
        scene.setRotation(node, Math.sin(0.4) * 0.6, Math.sin(0.4) * 0.8, 0, Math.cos(0.4));
        effects.wind(2, 0, 1);
        EffectData[] local = pair(e -> e.space(Space.LOCAL).spawn(Spawn.rate(30)).init(Shape.sphere(1),
            Init.lifetime(3)).update(Forces.vortex(Bind.constant(2), 0.3f, 1, 1, 0), Forces.wind(1),
            Forces.point(Bind.constant(1), 0, 1, 0), Forces.orbit(Bind.constant(1), 0, 0, 1)));
        long[] l = {effects.play(local[0], node).seed(6).id(), effects.play(local[1], node).seed(6).id()};
        for (int f = 0; f < 20; f++) {
            frame(FRAME);
        }
        List<Gpu> gpu = gpu(l[0], 0);
        cpu.particles(l[1], 0, cursor);
        assertThat(gpu).hasSize(cursor.count()).isNotEmpty();
        double qx = effects.rotation(l[0], 0);
        double qy = effects.rotation(l[0], 1);
        double qz = effects.rotation(l[0], 2);
        double qw = effects.rotation(l[0], 3);
        for (Gpu p : gpu) {
            cursor.next();
            double[] r = rotate(qx, qy, qz, qw, p.x, p.y, p.z);
            assertThat(effects.position(l[0], 0) + r[0]).isCloseTo(cursor.x(), within(1e-2));
            assertThat(effects.position(l[0], 1) + r[1]).isCloseTo(cursor.y(), within(1e-2));
            assertThat(effects.position(l[0], 2) + r[2]).isCloseTo(cursor.z(), within(1e-2));
        }
    }

    @Test
    void vectorFieldsPushParticlesByTheirTexels() {
        AssetRef<?> up = AssetRef.of(AssetType.of("test.field", String.class, s -> 0), new AssetId(0, 1));
        AssetRef<?> missing = AssetRef.of(AssetType.of("test.field", String.class, s -> 0), new AssetId(0, 2));
        float[] vectors = new float[3 * 4 * 4 * 4];
        for (int i = 0; i < 64; i++) {
            vectors[3 * i + 1] = 2; // 2 m/s² up everywhere
            vectors[3 * i] = (i & 3) < 2 ? 1 : -1; // and +-1 along x by the cell's column
        }
        try (VectorField field = VectorField.of(device, 4, 4, 4, vectors)) {
            particles.texture(up, field.slot());
            long pushed = effects.play(EffectData.builder().emitter("e", e -> e.spawn(Spawn.burst(0, 10))
                .init(Init.lifetime(100)).update(Forces.field(up, 1.5f, 8).with("tile", 1f))).build(), 0, 0, 0).id();
            long still = effects.play(EffectData.builder().emitter("e", e -> e.spawn(Spawn.burst(0, 10))
                .init(Init.lifetime(100)).update(Forces.field(missing, 1, 8))).build(), 0, 0, 0).id();
            for (int f = 0; f < 30; f++) {
                frame(FRAME);
            }
            for (Gpu p : gpu(pushed, 0)) {
                assertThat((double) p.vy).as("up at 3 m/s²").isCloseTo(3 * p.age, within(1e-2));
            }
            for (Gpu p : gpu(still, 0)) {
                assertThat(p.vy).as("no field registered: no force").isZero();
            }
            device.waitIdle();
        }
        assertThatIllegalArgumentException().isThrownBy(() -> VectorField.of(device, 2, 2, 2, new float[3]));
    }

    @Test
    void aHitchMovesSurvivorsOneMaximumStepAndSpreadsBirthsOverIt() {
        long[] pair = play(pair(e -> e.spawn(Spawn.rate(40)).init(Init.lifetime(5),
            Init.velocity(Bind.constant(1), Bind.constant(4), Bind.constant(0))).update(Forces.gravity())),
            0, 0, 0, 7);
        for (long ticks : new long[] {FRAME, FRAME, 400_000, FRAME, 3_000, FRAME}) {
            frame(ticks);
        }
        assertSame(pair, 0, 0, 0, 2e-3);
    }

    @Test
    void shapesMatchTheReference() {
        for (Module shape : new Module[] {Shape.point(), Shape.sphere(2), Shape.sphere(1.5f, 0.2f),
            Shape.box(1, 2, 3), Shape.cone(0.6f, 0.3f), Shape.disc(1.2f)}) {
            compare(e -> e.spawn(Spawn.rate(60), Spawn.burst(0, 20)).init(shape, Init.speed(Bind.random(1, 3)),
                Init.lifetime(Bind.random(0.5f, 2)), Init.size(Bind.random(0.05f, 0.2f))), 20, FRAME, 1e-3);
        }
    }

    @Test
    void colorsSizesAndCurvesMatchTheReference() {
        FloatCurve cubic = FloatCurve.builder().cubic(0, 0.2f).cubic(0.1f, 1).cubic(0.25f, 0.6f).cubic(0.4f, 1.4f)
            .cubic(0.55f, 0.9f).linear(0.7f, 1.1f).step(0.85f, 0.4f).linear(1, 0).build(); // 8 keys: binary search
        FloatCurve even = FloatCurve.of(0, 1, 0.2f, 2, 0.4f, 0.5f, 0.6f, 1.5f, 0.8f, 0.7f, 1, 0.1f); // evenly spaced
        ColorGradient fire = ColorGradient.builder().linear(0, 1, 0.9f, 0.6f, 1).cubic(0.3f, 1, 0.5f, 0.1f, 1)
            .cubic(0.6f, 0.6f, 0.2f, 0.05f, 0.8f).linear(1, 0.1f, 0.1f, 0.1f, 0).build();
        compare(e -> e.spawn(Spawn.rate(50)).init(Shape.point(), Init.speed(Bind.constant(2)),
            Init.lifetime(Bind.random(0.6f, 1.2f)), Init.size(Bind.param(INTENSITY, FloatCurve.of(0, 0.1f, 1, 0.3f))),
            Init.color(Bind.param(TINT))).update(Forces.drag(0.5f), Over.size(cubic), Over.size(even),
            Over.color(fire)), 40, FRAME, 1e-3);
        compare(e -> e.spawn(Spawn.rate(50)).init(Init.lifetime(Bind.random(0.5f, 1)),
            Init.color(Bind.random(fire)), Init.size(Bind.random(0.1f, 0.3f))), 10, FRAME, 1e-3);
        compare(e -> e.spawn(Spawn.burst(0, 30)).init(Shape.sphere(1), Init.speed(Bind.random(0, 4)),
            Init.lifetime(Bind.random(1, 2)), Init.color(Bind.color(0.2f, 0.4f, 0.6f, 0.8f)),
            Init.size(Bind.speed(FloatCurve.of(0, 0.1f, 4, 0.5f)))), 10, FRAME, 1e-3);
    }

    @Test
    void worldAndLocalSpaceMatchTheReference() {
        long node = scene.create();
        scene.setTranslation(node, 1, 2, 3);
        EffectData[] world = pair(e -> e.spawn(Spawn.rate(30)).init(Init.lifetime(3),
            Init.velocity(Bind.constant(1), Bind.constant(0), Bind.constant(0))).update(Forces.gravity()));
        EffectData[] local = pair(e -> e.space(Space.LOCAL).spawn(Spawn.rate(30)).init(Init.lifetime(3),
            Init.velocity(Bind.constant(1), Bind.constant(0.5f), Bind.constant(0))).update(Forces.gravity()));
        long[] w = {effects.play(world[0], node).seed(5).id(), effects.play(world[1], node).seed(5).id()};
        long[] l = {effects.play(local[0], node).seed(6).id(), effects.play(local[1], node).seed(6).id()};
        for (int f = 0; f < 30; f++) {
            scene.setTranslation(node, 1 + 0.1 * f, 2, 3 - 0.05 * f); // moving: world-space births along the way
            if (f >= 10) {
                double angle = 0.03 * (f - 10);
                scene.setRotation(node, Math.sin(angle) * 0.6, Math.sin(angle) * 0.8, 0, Math.cos(angle));
            }
            frame(FRAME);
        }
        // World space: offsets from where it started, falling along world -Y under the turned node too.
        assertSame(w, 1, 2, 3, 1e-3);
        // Local space: in the emitter's frame on the GPU, in the world through the cursor.
        List<Gpu> gpu = gpu(l[0], 0);
        cpu.particles(l[1], 0, cursor);
        assertThat(gpu).hasSize(cursor.count()).isNotEmpty();
        double qx = effects.rotation(l[0], 0);
        double qy = effects.rotation(l[0], 1);
        double qz = effects.rotation(l[0], 2);
        double qw = effects.rotation(l[0], 3);
        for (Gpu p : gpu) {
            cursor.next();
            double[] r = rotate(qx, qy, qz, qw, p.x, p.y, p.z);
            assertThat(effects.position(l[0], 0) + r[0]).isCloseTo(cursor.x(), within(1e-3));
            assertThat(effects.position(l[0], 1) + r[1]).isCloseTo(cursor.y(), within(1e-3));
            assertThat(effects.position(l[0], 2) + r[2]).isCloseTo(cursor.z(), within(1e-3));
        }
    }

    @Test
    void farOutRebasedAndInheritingVelocityWorldSpaceMatchesTheReference() {
        long node = scene.create();
        double x0 = 7_000_000.25;
        scene.setTranslation(node, x0, 2, 3);
        scene.update(); // played there, its origin there
        particles.rebaseDistance(1.5);
        EffectData[] world = pair(e -> e.spawn(Spawn.rate(30)).init(Shape.sphere(0.2f), Init.lifetime(1.5f),
            Init.speed(Bind.random(1, 2)), Init.size(Bind.random(0.1f, 0.2f))).update(Forces.gravity(), Forces.drag(0.3f))
            .render(com.truesim.scene.effects.Renderer.trail(4)));
        long[] w = {effects.play(world[0], node).seed(7).inheritVelocity(0.5f).id(),
            effects.play(world[1], node).seed(7).inheritVelocity(0.5f).id()};
        for (int f = 0; f < 40; f++) {
            scene.setTranslation(node, x0 + 0.2 * f, 2, 3 - 0.1 * f); // 12 m/s: rebased every few frames
            frame(FRAME);
        }
        assertThat(particles.rebases()).isGreaterThan(5);
        assertThat(particles.origin(w[0], 0)).isNotEqualTo(x0);
        assertSame(w, particles.origin(w[0], 0), particles.origin(w[0], 1), particles.origin(w[0], 2), 1e-3);
        assertThat(particles.origin(0x7fff_0000_0000L, 0)).as("not followed").isNaN();
        assertThatIllegalArgumentException().isThrownBy(() -> particles.rebaseDistance(0));
    }

    @Test
    void updatesBetweenFramesAreForTheNextFrame() {
        long[] pair = play(pair(e -> e.spawn(Spawn.burst(0, 12)).init(Init.lifetime(10))), 0, 0, 0, 3);
        for (int f = 0; f < 3; f++) {
            scene.update();
            runtime.update(FRAME);
            cpu.update();
            particles.update(effects, runtime.spawns()); // before the frame begins
            graph.beginFrame();
            graph.submit(0, 0);
        }
        assertSame(pair, 0, 0, 0, 1e-4);
    }

    @Test
    void endedEffectsStayWarmForTheNextOfTheirKind() {
        EffectData[] brief = pair(e -> e.duration(0.05).spawn(Spawn.burst(0, 10)).init(Init.lifetime(0.05f)));
        long free = particles.pool().freeSlots();
        particles.warmFrames(10);
        Effect first = effects.play(brief[0], 0, 0, 0);
        frame(FRAME);
        int range = particles.first(first.id(), 0);
        for (int f = 0; f < 10 && first.alive(); f++) {
            frame(FRAME);
        }
        frame(FRAME);
        assertThat(particles.warmCount()).isOne();
        assertThat(particles.pool().freeSlots()).as("kept").isLessThan(free);
        assertThat(particles.programCount()).isOne();
        Effect again = effects.play(brief[0], 0, 0, 0);
        frame(FRAME);
        assertThat(particles.warmCount()).isZero();
        assertThat(particles.first(again.id(), 0)).as("the warm range").isEqualTo(range);
        assertThat(gpu(again.id(), 0)).as("starting empty, then its burst").hasSize(10);
        // At most a few per effect; given back when their time is up.
        List<Effect> many = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            many.add(effects.play(brief[0], 0, 0, 0));
        }
        for (int f = 0; f < 6; f++) {
            frame(FRAME);
        }
        assertThat(particles.warmCount()).isEqualTo(4);
        for (int f = 0; f < 12; f++) {
            frame(FRAME);
        }
        assertThat(particles.warmCount()).isZero();
        assertThat(particles.pool().freeSlots()).isEqualTo(free);
        assertThatIllegalArgumentException().isThrownBy(() -> particles.warmFrames(-1));
    }

    @Test
    void warmRangesMakeRoomWhenThePoolIsFull() {
        device.waitIdle();
        graph.close();
        particles.close();
        poolCopy.close();
        stateCopy.close();
        target.close();
        graph = new FrameGraph(device, 2);
        start(ParticlePool.SLOT_BYTES * 1024L); // 1,024 slots
        EffectData small = EffectData.builder().emitter("e", e -> e.capacity(600).duration(0.02)
            .spawn(Spawn.burst(0, 1)).init(Init.lifetime(0.02f))).build();
        EffectData big = EffectData.builder().emitter("e", e -> e.capacity(700).spawn(Spawn.rate(1))).build();
        Effect brief = effects.play(small, 0, 0, 0);
        for (int f = 0; f < 6 && brief.alive(); f++) {
            frame(FRAME);
        }
        frame(FRAME);
        assertThat(particles.warmCount()).isOne();
        long effect = effects.play(big, 0, 0, 0).id();
        frame(FRAME);
        assertThat(particles.instance(effect, 0)).as("room made by the warm one").isNotNegative();
        assertThat(particles.warmCount()).isZero();
        assertThat(particles.unplaced()).isZero();
    }

    @Test
    void replacedEffectsRestartKeepingRangesThatFit() {
        EffectData three = EffectData.builder().emitter("a", e -> e.capacity(64).spawn(Spawn.burst(0, 3))
            .init(Init.lifetime(10))).emitter("b", e -> e.capacity(64).spawn(Spawn.burst(0, 1)).init(
                Init.lifetime(10))).build();
        EffectData five = EffectData.builder().emitter("a", e -> e.capacity(64).spawn(Spawn.burst(0, 5))
            .init(Init.lifetime(10))).emitter("b", e -> e.capacity(128).spawn(Spawn.burst(0, 1))
            .init(Init.lifetime(10))).emitter("c", e -> e.capacity(64).space(Space.LOCAL).spawn(Spawn.burst(0, 2))
            .init(Init.lifetime(10))).build();
        Effect effect = effects.play(three, 0, 0, 0);
        frame(FRAME);
        frame(FRAME);
        int a = particles.first(effect.id(), 0);
        assertThat(gpu(effect.id(), 0)).hasSize(3);
        effects.replace(three, five);
        frame(FRAME);
        frame(FRAME);
        assertThat(effect.alive()).isTrue();
        assertThat(particles.first(effect.id(), 0)).as("the same capacity: kept").isEqualTo(a);
        assertThat(gpu(effect.id(), 0)).as("started over").hasSize(5);
        assertThat(gpu(effect.id(), 1)).hasSize(1);
        assertThat(gpu(effect.id(), 2)).hasSize(2);
        // And back, to fewer emitters.
        effects.replace(five, three);
        frame(FRAME);
        frame(FRAME);
        assertThat(gpu(effect.id(), 0)).hasSize(3);
        assertThat(particles.instance(effect.id(), 2)).isEqualTo(-1);
    }

    private static double[] rotate(double qx, double qy, double qz, double qw, double x, double y, double z) {
        double cx = 2 * (qy * z - qz * y);
        double cy = 2 * (qz * x - qx * z);
        double cz = 2 * (qx * y - qy * x);
        return new double[] {x + qw * cx + qy * cz - qz * cy, y + qw * cy + qz * cx - qx * cz,
            z + qw * cz + qx * cy - qy * cx};
    }

    // ---------------------------------------------------------------------------------------------- counts and order

    @Test
    void spawnCountsOrderAndOverflowMatchTheReference() {
        // The capacity is estimated at the intensity's default (0.5: 50 a second); at 2, 200 a second overflow it.
        EffectData[] data = pair(e -> e.spawn(Spawn.rate(Bind.param(INTENSITY).times(100)), Spawn.burst(0, 25))
            .init(Init.lifetime(Bind.random(0.4f, 1.6f))));
        Effect gpuEffect = effects.play(data[0], 0, 0, 0).seed(99);
        Effect cpuEffect = effects.play(data[1], 0, 0, 0).seed(99);
        long[] pair = {gpuEffect.id(), cpuEffect.id()};
        for (int f = 0; f < 90; f++) {
            if (f == 20) {
                gpuEffect.set(INTENSITY, 2);
                cpuEffect.set(INTENSITY, 2);
            }
            frame(FRAME);
            if (f % 30 == 29) {
                List<Gpu> gpu = gpu(pair[0], 0);
                cpu.particles(pair[1], 0, cursor);
                assertThat(gpu).hasSize(cursor.count());
                for (Gpu p : gpu) {
                    cursor.next();
                    assertThat(Integer.toUnsignedLong(p.index)).as("frame " + f).isEqualTo(cursor.spawnIndex());
                }
            }
        }
        long dropped = cpu.dropped(pair[1], 0);
        assertThat(dropped).as("the reference dropped some").isPositive();
        for (int f = 0; f < 3; f++) { // the statistics come back late: frames that do not simulate
            graph.beginFrame();
            graph.submit(0, 0);
        }
        device.waitIdle();
        graph.beginFrame();
        graph.submit(0, 0);
        assertThat((long) particles.overflow(pair[0], 0)).as("overflow").isEqualTo(dropped);
        assertThat(particles.alive(pair[0], 0)).isEqualTo(cpu.count(pair[1], 0));
        assertThat(particles.alive()).isEqualTo(cpu.count(pair[1], 0));
        assertThat(particles.statisticsFrame()).isPositive();
        // Frames without an update moved nothing.
        assertSame(pair, 0, 0, 0, 1e-3);
    }

    @Test
    void seedsAndValuesDoNotDependOnSlotsListsOrRanges() {
        EffectData[] data = pair(e -> e.spawn(Spawn.rate(40)).init(Shape.sphere(1), Init.speed(Bind.random(1, 2)),
            Init.lifetime(Bind.random(0.5f, 1))).update(Forces.gravity()));
        EffectData other = EffectData.builder().emitter("o", e -> e.spawn(Spawn.rate(10))).build();
        long a = effects.play(data[0], 0, 0, 0).seed(77).id();
        effects.play(other, 5, 5, 5);
        effects.play(other, 6, 6, 6);
        long b = effects.play(data[0], 0, 0, 0).seed(77).id(); // a later slot, list place and range
        for (int f = 0; f < 20; f++) {
            frame(FRAME);
        }
        List<Gpu> first = gpu(a, 0);
        List<Gpu> second = gpu(b, 0);
        assertThat(particles.first(a, 0)).isNotEqualTo(particles.first(b, 0));
        assertThat(second).isEqualTo(first).isNotEmpty();
        int emitterSeed = EffectRuntime.emitterSeed(77, 0);
        for (Gpu p : first) {
            assertThat(p.seed).isEqualTo(EffectRuntime.particleSeed(emitterSeed, Integer.toUnsignedLong(p.index)));
        }
    }

    @Test
    void endedEffectsGiveTheirRangesBack() {
        particles.warmFrames(0);
        long free = particles.pool().freeSlots();
        EffectData brief = pair(e -> e.duration(0.05).spawn(Spawn.burst(0, 10)).init(Init.lifetime(0.1f)))[0];
        Effect effect = effects.play(brief, 0, 0, 0);
        frame(FRAME);
        assertThat(particles.pool().freeSlots()).isLessThan(free);
        assertThat(particles.emitterCount()).isOne();
        for (int f = 0; f < 20 && effect.alive(); f++) {
            frame(FRAME);
        }
        assertThat(effect.alive()).isFalse();
        frame(FRAME);
        assertThat(particles.pool().freeSlots()).isEqualTo(free);
        assertThat(particles.emitterCount()).isZero();
        assertThat(particles.instance(effect.id(), 0)).isEqualTo(-1);
        // Encoded programs go once no frame in flight reads them.
        for (int f = 0; f < 3; f++) {
            frame(FRAME);
        }
        assertThat(particles.programCount()).isZero();
    }

    @Test
    void emittersWithoutRoomAreReportedWhileCpuEmittersTakeRanges() {
        EffectData huge = EffectData.builder().emitter("huge", e -> e.capacity(1 << 20).spawn(Spawn.rate(10)))
            .emitter("cpu", e -> e.target(Target.CPU).spawn(Spawn.rate(10))).build();
        long effect = effects.play(huge, 0, 0, 0).id();
        frame(FRAME);
        frame(FRAME);
        frame(FRAME);
        assertThat(particles.unplaced()).isOne();
        assertThat(particles.instance(effect, 0)).isEqualTo(-1);
        assertThat(particles.instance(effect, 1)).as("a CPU emitter: uploaded into a range").isNotNegative();
        assertThat(particles.alive(effect, 0)).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> new Particles(device, graph.frameSlots(), 1 << 20, 0));
    }
}
