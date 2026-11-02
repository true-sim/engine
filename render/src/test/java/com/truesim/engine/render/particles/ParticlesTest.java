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
import com.truesim.scene.effects.Bind;
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
    private final CpuSimulation cpu = new CpuSimulation(runtime, null);
    private final ParticleCursor cursor = new ParticleCursor();

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
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        target = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        ExternalImage output = new ExternalImage("probe", 4, 4, Format.R8G8B8A8_UNORM, usage,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
        graph.add(new ParticleSimulation(particles)).add(new Probe(poolCopy, stateCopy)).output(PROBE, output);
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
            target.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** Copies the pool and the emitter state into host buffers each frame, after the simulation. */
    private record Probe(GpuBuffer pool, GpuBuffer state) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(PROBE);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer particles = g.require(ParticleKeys.PARTICLES);
            Buffer states = g.require(ParticleKeys.STATE);
            Image image = g.create("probe", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
            g.pass("particle probe").read(particles, BufferAccess.TRANSFER_SRC).read(states, BufferAccess.TRANSFER_SRC)
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
            Init.velocity(Bind.constant(1), Bind.constant(0), Bind.constant(0))));
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
        // World space: offsets from where it started. Gravity is left out under a turned node: CpuSimulation pulls
        // world-space particles along the node's down there (reported), the GPU along the world's.
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
    void emittersWithoutRoomAreReportedAndCpuEmittersTakeNone() {
        EffectData huge = EffectData.builder().emitter("huge", e -> e.capacity(1 << 20).spawn(Spawn.rate(10)))
            .emitter("cpu", e -> e.target(Target.CPU).spawn(Spawn.rate(10))).build();
        long effect = effects.play(huge, 0, 0, 0).id();
        frame(FRAME);
        frame(FRAME);
        frame(FRAME);
        assertThat(particles.unplaced()).isOne();
        assertThat(particles.instance(effect, 0)).isEqualTo(-1);
        assertThat(particles.instance(effect, 1)).isEqualTo(-1);
        assertThat(particles.alive(effect, 0)).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> new Particles(device, graph.frameSlots(), 1 << 20, 0));
    }
}
