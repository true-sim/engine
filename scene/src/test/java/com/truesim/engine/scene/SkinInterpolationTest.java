package com.truesim.engine.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Clock;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Step;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Interpolation;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneData;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.animation.AnimationClip;
import com.truesim.scene.animation.Animator;
import com.truesim.scene.animation.Animators;
import com.truesim.scene.animation.Skeleton;
import com.truesim.scene.animation.Track;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.Renderable;
import com.truesim.scene.render.SkinBinding;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCopy;

/**
 * Animators on fixed steps (A5), their skinned joints drawn between the steps: the posed vertices of the deformed copy,
 * read back from the pool after the deformation pass, against the two steps' poses, on the GPU path and the CPU path;
 * a teleport snapping to its step's pose; stopping and moving outside the steps; frames without a step posing nothing.
 */
class SkinInterpolationTest {

    private static final long MS = 1_000_000L;
    private static final int SIZE = 32;
    private static final int POSITION_BYTES = 8 << 10; // the pool's 1,024 vertices

    /** The right joint rising 1 m a second, for 100 s. */
    private static final AnimationClip RISE = new AnimationClip("rise", List.of(new Track("right", 0,
        Track.Property.TRANSLATION, Track.Interpolation.LINEAR, new float[] {0, 100}, new float[] {0, 0, 0, 0, 100, 0},
        0)));

    private VulkanInstance instance;
    private VulkanDevice device;
    private final FrameSlots slots = new FrameSlots(2);
    private GeometryPool pool;
    private Materials materials;
    private Textures textures;
    private TaskScheduler tasks;
    private AssetCache assets;
    private SceneResources resources;
    private final List<AutoCloseable> closing = new ArrayList<>();

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
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 10, 16, 4), slots);
        materials = new Materials(device, 16, slots);
        textures = new Textures(device, slots);
        tasks = new TaskScheduler();
        assets = new AssetCache(tasks);
        resources = new SceneResources(assets, pool, materials, textures);
    }

    @AfterEach
    void stop() throws Exception {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            for (int i = closing.size() - 1; i >= 0; i--) {
                closing.get(i).close();
            }
            resources.close();
            assets.close();
            tasks.close();
            textures.close();
            materials.close();
            pool.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    private <T extends AutoCloseable> T closing(T closeable) {
        closing.add(closeable);
        return closeable;
    }

    /** Copies the pool's positions into the frame slot's readback after the deformation, each frame. */
    private record PositionReadback(MeshletRenderer renderer, FrameSlots slots, GpuBuffer[] readbacks)
        implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(Keys.SCENE_COLOR); // so it is built
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            SceneData.Buffers scene = g.request(new SceneData(renderer));
            g.pass("position readback").read(scene.positions(), BufferAccess.TRANSFER_SRC).sideEffects().record(ctx -> {
                Barriers.memory(ctx.unsafe().commands(), VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
                try (MemoryStack stack = stackPush()) {
                    GpuBuffer readback = readbacks[slots.current()];
                    VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                        .size(Math.min(readback.size(), renderer.pool().positions().size()));
                    vkCmdCopyBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(scene.positions()),
                        readback.handle(), region);
                }
            });
            g.provide(Keys.SCENE_COLOR, color);
        }
    }

    /**
     * A world at 10 steps a second with a skinned square (SceneSyncTest's: its left half on one joint, its right half on
     * the other), the right joint raised by an animator on fixed steps: 0.1 m a step.
     */
    private final class World implements Application {
        final SceneGraph scene = new SceneGraph(new ChangeClock());
        final ComponentStore<Renderable> renderables = scene.components().store(Renderable.TYPE);
        final Animation animation = new Animation();
        final long body = scene.create();
        final long left = scene.create(body);
        final long right = scene.create(body);
        final GpuScene gpu = closing(new GpuScene(device, pool, 4, slots));
        final SceneSync sync = closing(new SceneSync(resources, scene, gpu));
        final GpuBuffer[] readbacks = {readback(), readback()};
        final FrameGraph graph;
        int lastSlot;
        Clock clock;
        Animators animators;
        Animator animator;
        long resetAt = -1; // the step that sets the clip's time to 0.5 s (and teleports, if told), or none
        boolean teleport;

        World() {
            this(true);
        }

        /** With the pool's positions read back each frame, or not (its copy calls Vulkan directly: it allocates). */
        World(boolean readback) {
            float[] identities = new float[32];
            for (int i = 0; i < 4; i++) {
                identities[5 * i] = 1; // two identity matrices
                identities[16 + 5 * i] = 1;
            }
            renderables.set(body, Renderable.of(SceneSyncTest.SKINNED, List.of(SceneSyncTest.RED)));
            renderables.set(body, renderables.get(body).withSkin(new SkinBinding(new long[] {left, right}, identities)));
            graph = closing(new FrameGraph(device, slots));
            MeshletRenderer renderer = closing(new MeshletRenderer(device, gpu, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT, slots));
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            ViewPort port = graph.addView("main").size(SIZE, SIZE);
            SceneView view = geometry.createView(port, new Camera().set(0, 0, -1, 0, 1, 0, 1, SIZE, SIZE, 0.1)
                .position(0, 0, 5), ViewOptions.of(64, 1 << 12));
            int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            GpuImage target = closing(new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage,
                VK_IMAGE_ASPECT_COLOR_BIT));
            port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT));
            if (readback) {
                port.add(new PositionReadback(renderer, slots, readbacks));
            }
            port.output(Keys.SCENE_COLOR, new ExternalImage("target", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
        }

        private GpuBuffer readback() {
            return closing(GpuBuffer.hostVisible(device, POSITION_BYTES, VK_BUFFER_USAGE_TRANSFER_DST_BIT));
        }

        @Override
        public void configure(AppConfig app) {
            app.use(animation).defaults(Engine.FRAME_RATE, 10_000);
        }

        @Override
        public void start(Engine engine) {
            clock = engine.addClock("world").rate(10);
            sync.follow(clock);
            animators = animation.animators(scene, clock);
            Skeleton skeleton = new Skeleton(new String[] {"left", "right"}, new int[] {-1, -1},
                new double[] {0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1});
            animator = animators.add(skeleton, new long[] {left, right}).fixedSteps(true);
        }

        @Override
        public void fixedUpdate(Step step) {
            if (step.tick() == resetAt) {
                animator.play(RISE).time(0.5 - step.delta()); // the step moves it on to 0.5 s
                if (teleport) {
                    sync.teleport(body);
                }
            }
        }

        /** A frame at {@code millis}: the engine's phases (the steps), then the scene synced and drawn. */
        void frame(Engine engine, long millis) {
            engine.stepAt(millis * MS);
            int slot = graph.beginFrame();
            lastSlot = slot;
            slots.begin(slot);
            tasks.drainCompleted();
            scene.update();
            sync.update();
            graph.submit(0, 0);
            slots.end();
        }

        /** The height of the square's right half as drawn in the last frame: its posed vertices' y, the two alike. */
        double drawnRight() {
            device.waitIdle();
            int copy = sync.drawnMeshOf(body);
            MemorySegment entry = pool.meshTable().memory();
            long e = 96L * copy;
            double minX = entry.get(ValueLayout.JAVA_FLOAT_UNALIGNED, e);
            double minY = entry.get(ValueLayout.JAVA_FLOAT_UNALIGNED, e + 4);
            double extentX = entry.get(ValueLayout.JAVA_FLOAT_UNALIGNED, e + 16);
            double extentY = entry.get(ValueLayout.JAVA_FLOAT_UNALIGNED, e + 16 + 4);
            MemorySegment m = readbacks[lastSlot].memory();
            int base = pool.vertexBase(copy);
            double right = Double.NaN;
            int rights = 0;
            for (int v = 0; v < 4; v++) { // the vertices in the order the mesh's meshlets keep them
                long at = 8L * (base + v);
                double x = minX + extentX * Short.toUnsignedInt(m.get(ValueLayout.JAVA_SHORT_UNALIGNED, at)) / 65535.0;
                double y = minY + extentY * Short.toUnsignedInt(m.get(ValueLayout.JAVA_SHORT_UNALIGNED, at + 2)) / 65535.0;
                if (x < 0) {
                    assertThat(y).as("the left half stays").isCloseTo(0, within(1e-4));
                } else {
                    assertThat(rights == 0 ? y : right).as("the right half's two vertices alike")
                        .isCloseTo(y, within(1e-4));
                    right = y;
                    rights++;
                }
            }
            assertThat(rights).isEqualTo(2);
            return right;
        }

        void settle(Engine engine) throws InterruptedException {
            for (int round = 0; round < 4; round++) {
                frame(engine, 0);
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (assets.loadingCount() > 0 || tasks.pending() > 0) {
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.sleep(1);
                    tasks.drainCompleted();
                }
            }
            assertThat(sync.instanceCount()).isOne();
            animator.play(RISE);
        }
    }

    @Test
    void theGpuBlendsTheJointsOfTheLastTwoStepsByTheFramesFactor() throws InterruptedException {
        blendsTheLastTwoSteps(Interpolation.GPU);
    }

    @Test
    void theCpuBlendsTheJointsTheSame() throws InterruptedException {
        blendsTheLastTwoSteps(Interpolation.CPU);
    }

    private void blendsTheLastTwoSteps(Interpolation mode) throws InterruptedException {
        World world = new World();
        try (Engine engine = Engine.create(world)) {
            world.sync.interpolation(mode);
            world.settle(engine); // frames at time 0: no steps
            // Frames every 30 ms over steps every 100 ms: the right half drawn between the last two steps' heights
            for (long t = 30; t <= 600; t += 30) {
                world.frame(engine, t);
                long tick = world.clock.tick();
                double blend = world.clock.blend();
                if (tick >= 1) { // step k raised it to 0.1 k; from the rest pose before the first
                    assertThat(world.drawnRight()).as("%s, the right half at %d ms", mode, t)
                        .isCloseTo(0.1 * (tick - 1 + blend), within(1e-4));
                }
            }
            assertThat(world.sync.interpolatedSkinCount()).isOne();
            assertThat(pool.deformation().blended(world.sync.drawnMeshOf(world.body))).isEqualTo(mode.onGpu());
            // The scene keeps the simulated pose: the last step's
            assertThat(world.scene.world(world.right).translation().y()).isCloseTo(0.6, within(1e-6));
        }
    }

    @Test
    void offDrawsTheJointsAsEachStepLeavesThem() throws InterruptedException {
        World world = new World();
        try (Engine engine = Engine.create(world)) {
            world.sync.interpolation(Interpolation.OFF);
            world.settle(engine);
            world.frame(engine, 100);
            world.frame(engine, 250);
            assertThat(world.drawnRight()).isCloseTo(0.2, within(1e-4));
            assertThat(world.sync.interpolatedSkinCount()).isZero();
        }
    }

    @Test
    void framesWithoutAStepPoseNothingOnTheGpuPathYetTheJointsMove() throws InterruptedException {
        World world = new World();
        try (Engine engine = Engine.create(world)) {
            world.settle(engine);
            world.frame(engine, 100);
            world.frame(engine, 200);
            world.frame(engine, 230); // no step
            assertThat(world.clock.stepsLastFrame()).isZero();
            assertThat(world.sync.posedCount()).as("copies posed").isZero();
            assertThat(world.drawnRight()).isCloseTo(0.13, within(1e-4));
            world.frame(engine, 260);
            assertThat(world.sync.posedCount()).isZero();
            assertThat(pool.deformation().skinnedLastFrame()).as("yet skinned, blended anew").isOne();
            assertThat(world.drawnRight()).isCloseTo(0.16, within(1e-4));
        }
    }

    @Test
    void aTeleportSnapsTheJointsToTheirStepsPose() throws InterruptedException {
        for (Interpolation mode : new Interpolation[] {Interpolation.GPU, Interpolation.CPU}) {
            for (boolean teleport : new boolean[] {false, true}) {
                World world = new World();
                try (Engine engine = Engine.create(world)) {
                    world.sync.interpolation(mode);
                    world.resetAt = 3;
                    world.teleport = teleport;
                    world.settle(engine);
                    world.frame(engine, 100);
                    world.frame(engine, 200);
                    world.frame(engine, 300); // step 3 sets the clip to 0.5 s: 0.5 m up from 0.2
                    world.frame(engine, 350);
                    // Teleported: drawn at the step's pose at once; else blended across the jump
                    assertThat(world.drawnRight()).as("%s, teleported %s", mode, teleport)
                        .isCloseTo(teleport ? 0.5 : 0.35, within(1e-4));
                    world.frame(engine, 450); // step 4 moves on from there, blended again
                    assertThat(world.drawnRight()).as("%s, teleported %s", mode, teleport)
                        .isCloseTo(0.55, within(1e-4));
                }
            }
        }
    }

    @Test
    void stoppingAndMovingOutsideTheStepsDrawTheJointsAsPosed() throws InterruptedException {
        World world = new World();
        try (Engine engine = Engine.create(world)) {
            world.settle(engine);
            world.frame(engine, 100);
            world.frame(engine, 200);
            world.frame(engine, 250);
            assertThat(world.drawnRight()).isCloseTo(0.15, within(1e-4));
            // The animator stops: the next step leaves the joints where they are, drawn there
            world.animator.stop();
            world.frame(engine, 300);
            world.frame(engine, 350);
            assertThat(world.sync.interpolatedSkinCount()).isZero();
            assertThat(world.drawnRight()).isCloseTo(0.2, within(1e-4));
            assertThat(pool.deformation().blended(world.sync.drawnMeshOf(world.body))).isFalse();
            // Playing on fixed steps again, blended again from its next step
            world.animator.play(RISE).time(0.2);
            world.frame(engine, 400);
            world.frame(engine, 450);
            assertThat(world.drawnRight()).isCloseTo(0.25, within(1e-4));
            // Moved in the frame, after the steps (the game's late update): drawn so, blending stopped till its next step
            world.scene.setTranslation(world.right, 0, 3, 0);
            world.frame(engine, 460);
            assertThat(world.sync.interpolatedSkinCount()).isZero();
            assertThat(world.drawnRight()).isCloseTo(3, within(1e-4));
        }
    }

    @Test
    void framesWithJointsBetweenStepsAllocateNothingOnceWarm() throws InterruptedException {
        World world = new World(false);
        try (Engine engine = Engine.create(world)) {
            world.settle(engine);
            world.animator.play(RISE).loop(true);
            long[] now = {0};
            for (Interpolation mode : new Interpolation[] {Interpolation.GPU, Interpolation.CPU}) {
                world.sync.interpolation(mode);
                com.truesim.testing.AllocationAssertions.assertAllocationFree(() -> {
                    now[0] += 7 + now[0] % 23; // frames of 7 to 29 ms over steps every 100 ms
                    world.frame(engine, now[0]);
                }, 8000, 300, 8);
                assertThat(world.sync.interpolatedSkinCount()).as(mode.name()).isOne();
            }
        }
    }
}
