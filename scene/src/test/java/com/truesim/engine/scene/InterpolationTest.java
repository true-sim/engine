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
import com.truesim.engine.graph.Buffer;
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
import com.truesim.engine.render.RenderSettings;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneData;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.layout.InstanceLayout;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.Renderables;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;

/**
 * Instances moved in fixed steps drawn between steps (D91): the poses the GPU blends, read back from the instance table
 * after the blend, against the steps' poses; on the CPU alike; teleports, opting out, stopping, moves outside the steps,
 * and frames without a step writing nothing.
 */
class InterpolationTest {

    private static final long MS = 1_000_000L;
    private static final int SIZE = 32;
    private static final double FAR = 1e6; // far from the origin: the position's two parts both matter

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

    private GpuBuffer readback() {
        return closing(GpuBuffer.hostVisible(device, 4L * GpuScene.INSTANCE_BYTES, VK_BUFFER_USAGE_TRANSFER_DST_BIT));
    }

    private <T extends AutoCloseable> T closing(T closeable) {
        closing.add(closeable);
        return closeable;
    }

    /** Copies the instance table into the frame slot's readback after everything writing it, each frame. */
    private record InstanceReadback(MeshletRenderer renderer, FrameSlots slots, GpuBuffer[] readbacks) implements Technique {
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
            Buffer instances = g.request(new SceneData(renderer)).instances();
            g.pass("instance readback").read(instances, BufferAccess.TRANSFER_SRC).sideEffects().record(ctx -> {
                try (MemoryStack stack = stackPush()) {
                    GpuBuffer readback = readbacks[slots.current()];
                    VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).size(readback.size());
                    vkCmdCopyBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(instances), readback.handle(),
                        region);
                }
            });
            g.provide(Keys.SCENE_COLOR, color);
        }
    }

    /** A world of one square moving 1 m along x per step and turning 0.2 rad about y, at 10 steps a second. */
    private final class World implements Application {
        final SceneGraph scene = new SceneGraph(new ChangeClock());
        final Renderables renderables = new Renderables(scene);
        final long node = scene.create();
        final GpuScene gpu = closing(new GpuScene(device, pool, 4, slots));
        final SceneSync sync = closing(new SceneSync(resources, renderables, gpu));
        final GpuBuffer[] readbacks = {readback(), readback()};
        int lastSlot;
        final FrameGraph graph;
        Clock clock;
        boolean moving = true;
        long teleportAt = -1;

        World() {
            scene.setTranslation(node, FAR, 0, FAR);
            renderables.set(node, SceneSyncTest.SQUARE, List.of(SceneSyncTest.RED));
            graph = closing(new FrameGraph(device, slots));
            MeshletRenderer renderer = closing(new MeshletRenderer(device, gpu, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT, slots));
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            ViewPort port = graph.addView("main").size(SIZE, SIZE);
            SceneView view = geometry.createView(port, new Camera().set(0, 0, -1, 0, 1, 0, 1, SIZE, SIZE, 0.1)
                .position(FAR, 0, FAR + 5), ViewOptions.of(64, 1 << 12));
            int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            GpuImage target = closing(new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage,
                VK_IMAGE_ASPECT_COLOR_BIT));
            port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT))
                .add(new InstanceReadback(renderer, slots, readbacks))
                .output(Keys.SCENE_COLOR, new ExternalImage("target", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
        }

        @Override
        public void configure(AppConfig app) {
            app.defaults(Engine.FRAME_RATE, 10_000);
        }

        @Override
        public void start(Engine engine) {
            clock = engine.addClock("world").rate(10);
            sync.follow(clock);
        }

        @Override
        public void fixedUpdate(Step step) {
            if (!moving) {
                return;
            }
            double angle = 0.2 * step.tick();
            double jump = step.tick() >= teleportAt && teleportAt >= 0 ? 100 : 0;
            scene.setTranslation(node, FAR + step.tick() + jump, 0, FAR);
            scene.setRotation(node, 0, Math.sin(angle / 2), 0, Math.cos(angle / 2));
            if (step.tick() == teleportAt) {
                sync.teleport(node);
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
            renderables.update();
            sync.update();
            graph.submit(0, 0);
            slots.end();
        }

        /** The instance's x as drawn in the last frame (after the blend), relative to {@link #FAR}. */
        double drawnX() {
            device.waitIdle();
            MemorySegment m = readbacks[lastSlot].memory();
            float high = m.get(ValueLayout.JAVA_FLOAT_UNALIGNED, InstanceLayout.POSITION);
            float low = m.get(ValueLayout.JAVA_FLOAT_UNALIGNED, InstanceLayout.POSITION_LOW);
            return ((double) high - FAR) + low;
        }

        /** The instance's rotation angle about y as drawn in the last frame. */
        double drawnAngle() {
            MemorySegment m = readbacks[lastSlot].memory();
            float y = m.get(ValueLayout.JAVA_FLOAT_UNALIGNED, InstanceLayout.ROTATION + 4);
            float w = m.get(ValueLayout.JAVA_FLOAT_UNALIGNED, InstanceLayout.ROTATION + 12);
            return 2 * Math.atan2(y, w);
        }

        void settle(Engine engine) throws InterruptedException {
            moving = false;
            for (int round = 0; round < 4; round++) {
                frame(engine, 0);
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (assets.loadingCount() > 0 || tasks.pending() > 0) {
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.sleep(1);
                    tasks.drainCompleted();
                }
            }
            assertThat(sync.instanceCount()).isEqualTo(1);
            moving = true;
        }
    }

    @Test
    void theGpuBlendsTheLastTwoStepsByTheFramesFactor() throws InterruptedException {
        blendsTheLastTwoSteps(Interpolation.GPU);
    }

    @Test
    void theCpuBlendsTheSame() throws InterruptedException {
        blendsTheLastTwoSteps(Interpolation.CPU);
    }

    private void blendsTheLastTwoSteps(Interpolation mode) throws InterruptedException {
        World world = new World();
        try (Engine engine = Engine.create(world)) {
            world.sync.interpolation(mode);
            world.settle(engine); // frames at time 0: no steps
            // Frames every 30 ms over steps every 100 ms: drawn between the last two steps' poses by the blend
            for (long t = 30; t <= 600; t += 30) {
                world.frame(engine, t);
                long tick = world.clock.tick();
                double blend = world.clock.blend();
                assertThat(blend).isCloseTo((t % 100) / 100.0, within(1e-9));
                if (tick >= 2) { // both poses from steps: x was tick - 1, is tick
                    assertThat(world.drawnX()).as("x at %d ms", t).isCloseTo(tick - 1 + blend, within(1e-3));
                    assertThat(world.drawnAngle()).as("angle at %d ms", t)
                        .isCloseTo(0.2 * (tick - 1 + blend), within(1e-3)); // normalized linear: near the arc
                }
            }
            assertThat(world.sync.interpolatedCount()).isEqualTo(1);
            assertThat(world.gpu.motion().count()).isEqualTo(mode == Interpolation.GPU ? 1 : 0);
        }
    }

    @Test
    void theRenderSettingSwitchesThePathWithoutAppCode() throws InterruptedException {
        EngineSettings settings = new EngineSettings();
        resources.settings(settings); // as the assets plugin gives the engine's
        World world = new World();
        try (Engine engine = Engine.create(world)) {
            world.settle(engine);
            assertThat(world.sync.interpolation()).isEqualTo(Interpolation.GPU); // the default
            world.frame(engine, 100);
            world.frame(engine, 200);
            world.frame(engine, 250);
            assertThat(world.gpu.motion().count()).isEqualTo(1);
            assertThat(world.drawnX()).isCloseTo(1.5, within(1e-3));
            // The player picks the CPU: the sync switches at its next update, drawn where the step left it, the record
            // gone; blended again on the CPU from the next step
            settings.set(RenderSettings.INTERPOLATION, Interpolation.CPU);
            world.frame(engine, 300);
            world.frame(engine, 350);
            assertThat(world.sync.interpolation()).isEqualTo(Interpolation.CPU);
            assertThat(world.gpu.motion().count()).isZero();
            assertThat(world.drawnX()).isCloseTo(3, within(1e-3));
            world.frame(engine, 400);
            world.frame(engine, 450);
            assertThat(world.drawnX()).isCloseTo(3.5, within(1e-3));
            // Off: drawn as each step leaves it
            settings.set(RenderSettings.INTERPOLATION, Interpolation.OFF);
            world.frame(engine, 500);
            world.frame(engine, 550);
            assertThat(world.drawnX()).isCloseTo(5, within(1e-3));
            // Back to the GPU: from the step after the switch
            settings.set(RenderSettings.INTERPOLATION, Interpolation.GPU);
            world.frame(engine, 600);
            world.frame(engine, 700);
            world.frame(engine, 750);
            assertThat(world.gpu.motion().count()).isEqualTo(1);
            assertThat(world.drawnX()).isCloseTo(6.5, within(1e-3));
            // Set by hand, the setting no longer applies to that sync
            world.sync.interpolation(Interpolation.OFF);
            settings.set(RenderSettings.INTERPOLATION, Interpolation.CPU);
            world.frame(engine, 800);
            assertThat(world.sync.interpolation()).isEqualTo(Interpolation.OFF);
        } finally {
            resources.settings(null);
        }
    }

    @Test
    void framesWithoutAStepWriteNothingOnTheGpuPath() throws InterruptedException {
        World world = new World();
        try (Engine engine = Engine.create(world)) {
            world.settle(engine);
            world.frame(engine, 100); // a step: its two poses uploaded, nothing else
            world.frame(engine, 200);
            assertThat(world.clock.stepsLastFrame()).isEqualTo(1);
            engine.stepAt(230 * MS); // a frame without a step
            assertThat(world.clock.stepsLastFrame()).isZero();
            int slot = world.graph.beginFrame();
            world.lastSlot = slot;
            slots.begin(slot);
            world.scene.update();
            world.renderables.update();
            world.sync.update();
            assertThat(world.gpu.pendingCount()).as("instances written").isZero();
            assertThat(world.gpu.motion().pendingCount()).as("motion records written").isZero();
            world.graph.submit(0, 0);
            slots.end();
            assertThat(world.drawnX()).isCloseTo(1.3, within(1e-3)); // yet it moves
        }
    }

    @Test
    void aTeleportIsDrawnWhereItLandsWithoutBlendingAcross() throws InterruptedException {
        World world = new World();
        try (Engine engine = Engine.create(world)) {
            world.teleportAt = 3;
            world.settle(engine);
            world.frame(engine, 100);
            world.frame(engine, 200);
            world.frame(engine, 250);
            assertThat(world.drawnX()).isCloseTo(1.5, within(1e-3));
            world.frame(engine, 300); // step 3 jumps 100 m: drawn there at once, not on the way
            world.frame(engine, 350);
            assertThat(world.drawnX()).isCloseTo(103, within(1e-3));
            world.frame(engine, 450); // step 4 moves on from there, blended again
            assertThat(world.drawnX()).isCloseTo(103.5, within(1e-3));
        }
    }

    @Test
    void stoppingOptingOutAndMovingOutsideTheStepsDrawWhereItIs() throws InterruptedException {
        World world = new World();
        try (Engine engine = Engine.create(world)) {
            world.settle(engine);
            world.frame(engine, 100);
            world.frame(engine, 200);
            world.frame(engine, 250);
            assertThat(world.drawnX()).isCloseTo(1.5, within(1e-3));
            // It stops: the next step finds it still, its record goes and it is drawn where it stopped
            world.moving = false;
            world.frame(engine, 300);
            world.frame(engine, 350);
            assertThat(world.drawnX()).isCloseTo(2, within(1e-3));
            assertThat(world.sync.interpolatedCount()).isZero();
            assertThat(world.gpu.motion().count()).isZero();
            // Opted out: drawn as each step leaves it
            world.moving = true;
            world.sync.interpolated(world.node, false);
            world.frame(engine, 400);
            world.frame(engine, 450);
            assertThat(world.drawnX()).isCloseTo(4, within(1e-3));
            world.sync.interpolated(world.node, true);
            world.frame(engine, 500);
            world.frame(engine, 550);
            assertThat(world.drawnX()).isCloseTo(4.5, within(1e-3));
            // Moved in the frame's update, after the steps: drawn there, interpolation stopped until its next step
            world.scene.setTranslation(world.node, FAR + 50, 0, FAR);
            world.frame(engine, 560);
            assertThat(world.drawnX()).isCloseTo(50, within(1e-3));
            assertThat(world.sync.interpolatedCount()).isZero();
        }
    }
}
