package com.truesim.engine.render.particles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

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
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.layout.ParticleConstants;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.CpuSimulation;
import com.truesim.scene.effects.Effect;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectRuntime;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.Spawn;
import com.truesim.scene.effects.Target;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The particle sort (effects.md §2.4): each sorted emitter's particles in a run of their own, farthest from the center
 * first, emitters in the frame's order, unsorted renderers left out; read back from the GPU. Validated, with
 * synchronization validation.
 */
class ParticleSortTest {

    private static final Key<Image> PROBE = Key.image("test.sortProbe");
    private static final long FRAME = 16_667;

    private VulkanInstance instance;
    private VulkanDevice device;
    private FrameGraph graph;
    private Particles particles;
    private GpuImage target;
    private final Camera center = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, 64, 64, 0.1);
    private final SceneGraph nodes = new SceneGraph(new ChangeClock());
    private final Effects effects = new Effects(nodes);
    private final EffectRuntime runtime = new EffectRuntime(effects);
    private final CpuSimulation cpu = new CpuSimulation(runtime, null);
    private int[] args;
    private long[] sorted;

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
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        target = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        build(4 << 20);
    }

    /** The graph: the simulation and the probe, over a pool of {@code budget} bytes. */
    private void build(long budget) {
        graph = new FrameGraph(device, 2);
        particles = new Particles(device, graph.frameSlots(), budget, 16);
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        graph.add(new ParticleSimulation(particles)).add(new Probe()).output(PROBE, new ExternalImage("probe", 4, 4,
            Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
            .set(target.handle(), target.view()));
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

    /** Requests the sort from {@link #center} and reads it back. */
    private final class Probe implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(PROBE);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer pool = g.require(ParticleKeys.PARTICLES);
            Buffer state = g.require(ParticleKeys.STATE);
            ParticleSort.Order order = g.request(new ParticleSort(particles, center, pool, state));
            int slots = particles.pool().slots();
            g.readback("sort args", order.args(), 0, 4L * (4 + ParticleConstants.SORT_EMITTERS),
                (data, frame) -> args = data.toArray(ValueLayout.JAVA_INT));
            g.readback("sorted", order.sorted(), 0, 8L * slots,
                (data, frame) -> sorted = data.toArray(ValueLayout.JAVA_LONG));
            Image image = g.create("probe", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
            g.pass("probe").read(order.sorted(), BufferAccess.STORAGE_READ).write(image, Access.TRANSFER_DST)
                .record(ctx -> ctx.clear(image.mip(0), 0, 0, 0, 1));
            g.provide(PROBE, image);
        }
    }

    private void frames(int n) {
        for (int f = 0; f < n; f++) {
            nodes.update();
            runtime.update(FRAME);
            cpu.update();
            graph.beginFrame();
            particles.update(effects, runtime.spawns(), cpu);
            graph.submit(0, 0);
        }
        device.waitIdle();
    }

    private static int key(long entry) {
        return (int) entry;
    }

    private static int index(long entry) {
        return (int) (entry >>> 32);
    }

    @Test
    void eachSortedEmittersParticlesRunFarthestFirst() {
        // A CPU emitter the game writes (known positions), a GPU emitter of still particles, an additive one
        EffectData written = EffectData.builder().emitter("e", e -> e.target(Target.CPU).capacity(300)
            .spawn(Spawn.rate(1)).render(Renderer.billboard(Renderer.Facing.POSITION))).build();
        EffectData ball = EffectData.builder().emitter("e", e -> e.spawn(Spawn.burst(0, 1000))
            .init(Shape.sphere(5), Init.lifetime(100)).render(Renderer.billboard(Renderer.Facing.POSITION))).build();
        EffectData sparks = EffectData.builder().emitter("e", e -> e.spawn(Spawn.burst(0, 100))
            .init(Shape.sphere(1), Init.lifetime(100)).render(Renderer.billboard(Renderer.Facing.POSITION)
                .additive())).build();
        Effect cloud = effects.play(written, 0, 0, -10);
        effects.play(ball, 3, 1, -20);
        effects.play(sparks, 0, 0, -5);
        frames(1);
        ParticleWriter writer = particles.writer(cloud.id(), 0);
        SplittableRandom random = new SplittableRandom(7);
        double[] distance = new double[300];
        for (int i = 0; i < 300; i++) {
            double x = random.nextDouble(-10, 10);
            double y = random.nextDouble(-10, 10);
            double z = random.nextDouble(-30, 0);
            writer.next().position(x, y, z);
            distance[i] = Math.sqrt(x * x + y * y + z * z);
        }
        frames(4);
        frames(2); // the readbacks of a frame after the writes
        assertThat(particles.sortedCount()).as("the additive emitter is not sorted").isEqualTo(2);
        assertThat(args[3]).as("keys").isEqualTo(1300);
        assertThat(args[4]).isZero();
        assertThat(args[5]).isEqualTo(300);
        // The CPU emitter's run: every particle once, farthest first
        boolean[] seen = new boolean[300];
        for (int k = 0; k < 300; k++) {
            long entry = sorted[k];
            assertThat(key(entry) >>> ParticleConstants.SORT_DEPTH_BITS).isZero();
            assertThat(seen[index(entry)]).isFalse();
            seen[index(entry)] = true;
            if (k > 0) {
                assertThat(distance[index(entry)]).as("entry " + k)
                    .isLessThanOrEqualTo(distance[index(sorted[k - 1])] * 1.001);
            }
        }
        // The GPU emitter's: every particle once, keys in order
        boolean[] seenBall = new boolean[1000];
        for (int k = 300; k < 1300; k++) {
            long entry = sorted[k];
            assertThat(key(entry) >>> ParticleConstants.SORT_DEPTH_BITS).isEqualTo(1);
            assertThat(Integer.compareUnsigned(key(entry), key(sorted[k - 1]))).as("entry " + k)
                .isGreaterThanOrEqualTo(k == 300 ? -1 : 0);
            assertThat(seenBall[index(entry)]).isFalse();
            seenBall[index(entry)] = true;
        }
    }

    @Test
    void keysSortAsAStableSortAcrossGroupsAndPartGroups() {
        device.waitIdle();
        graph.close();
        particles.close();
        build(ParticlePool.SLOT_BYTES * 80_000L);
        for (int n : new int[] {1, ParticleConstants.SORT_GROUP - 1, ParticleConstants.SORT_GROUP + 1, 70_000}) {
            // Two sorted emitters, the second's keys above the first's; many equal distances among 70,000 in a ball
            Effect small = effects.play(EffectData.builder().emitter("e", e -> e.capacity(5).spawn(Spawn.burst(0, 5))
                .init(Shape.sphere(1), Init.lifetime(100)).render(Renderer.billboard(Renderer.Facing.POSITION)))
                .build(), 0, 0, -3);
            Effect ball = effects.play(EffectData.builder().emitter("e", e -> e.capacity(n).spawn(Spawn.burst(0, n))
                .init(Shape.sphere(5), Init.lifetime(100)).render(Renderer.billboard(Renderer.Facing.POSITION)))
                .build(), 0, 0, -20);
            frames(5);
            assertThat(args[3]).as("keys of " + n).isEqualTo(n + 5);
            // A stable sort of the keys as written (each emitter's in index order): keys in order, equal keys by index
            // The emitters in the frame's order, whichever slots the effects took
            int run = args[5];
            assertThat(run).isIn(5, n);
            int equal = 0;
            boolean[][] seen = {new boolean[run], new boolean[n + 5 - run]};
            for (int k = 0; k < n + 5; k++) {
                long entry = sorted[k];
                int emitter = k < run ? 0 : 1;
                assertThat(key(entry) >>> ParticleConstants.SORT_DEPTH_BITS).as("emitter of " + k).isEqualTo(emitter);
                assertThat(seen[emitter][index(entry)]).isFalse();
                seen[emitter][index(entry)] = true;
                if (k > 0 && k != run) {
                    int order = Integer.compareUnsigned(key(entry), key(sorted[k - 1]));
                    assertThat(order).as("key " + k + " of " + n).isNotNegative();
                    if (order == 0) {
                        equal++;
                        assertThat(index(entry)).as("equal key " + k + " of " + n)
                            .isGreaterThan(index(sorted[k - 1]));
                    }
                }
            }
            if (n == 70_000) {
                assertThat(equal).as("equal keys, kept in order").isGreaterThan(1000);
            }
            small.kill();
            ball.kill();
            frames(1);
        }
    }

    @Test
    void passesCoverTheKeysBitsEvenly() {
        assertThat(ParticleSort.passes(0)).isEqualTo(6); // 20 bits: 5, made even
        assertThat(ParticleSort.passes(1)).isEqualTo(6);
        assertThat(ParticleSort.passes(16)).isEqualTo(6); // 24 bits
        assertThat(ParticleSort.passes(17)).isEqualTo(8); // 25
        assertThat(ParticleSort.passes(ParticleConstants.SORT_EMITTERS)).isEqualTo(8); // 32
        assertThatNullPointerException().isThrownBy(() -> new ParticleSort(particles, null, null, null));
    }
}
