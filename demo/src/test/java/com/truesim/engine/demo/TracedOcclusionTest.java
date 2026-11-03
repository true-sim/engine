package com.truesim.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.common.collision.BoxShape;
import com.truesim.common.collision.CollisionWorld;
import com.truesim.common.collision.TriangleMeshShape;
import com.truesim.common.math.Quat;
import com.truesim.common.math.Transform;
import com.truesim.common.math.Vec3;
import com.truesim.engine.audio.SegmentTracer;
import com.truesim.engine.audio.TracedAudioWorld;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.SegmentTracing;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.audio.AudioMaterial;
import com.truesim.scene.audio.CollisionAudioWorld;
import com.truesim.scene.audio.Transmission;
import com.truesim.scene.meshbuild.MeshBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Occlusion rays traced on the GPU against what physics traces for the same walls (§2.11, audio step 19): boxes as
 * convex bodies against meshes crossed going in, and as triangle meshes against every triangle crossed; validated.
 * Prints the agreement and the time per frame of batches of 64 and 4,096 rays. Here, where the app joins the
 * renderer's tracing and audio's world.
 */
class TracedOcclusionTest {

    private static final Key<Image> TARGET = Key.image("test.target");
    private static final AudioMaterial[] TABLE = {null, AudioMaterial.CONCRETE, AudioMaterial.GLASS,
        AudioMaterial.WOOD, AudioMaterial.BRICK, AudioMaterial.CURTAIN};
    private static final int GRID = 5; // boxes on a grid of cells 8 m wide, one each, apart
    private static final int RAYS = 4096;

    /** The frame's one output, a cleared image: what the tracing hangs on. */
    private record Target() implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(TARGET);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.create("target", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
            g.pass("clear").write(image, Access.TRANSFER_DST).record(ctx -> ctx.clear(image.whole(), 0, 0, 0, 1));
            g.provide(TARGET, image);
        }
    }

    /** A box from -1 to 1 along each axis, its faces counter-clockwise seen from outside. */
    static final float[] CORNERS = new float[24];
    static final int[] FACES = {0, 2, 3, 0, 3, 1, 4, 5, 7, 4, 7, 6, 0, 1, 5, 0, 5, 4, 2, 6, 7, 2, 7, 3, 0, 4, 6, 0, 6,
        2, 1, 3, 7, 1, 7, 5};

    static {
        for (int v = 0; v < 8; v++) {
            CORNERS[3 * v] = (v & 1) * 2 - 1;
            CORNERS[3 * v + 1] = (v >> 1 & 1) * 2 - 1;
            CORNERS[3 * v + 2] = (v >> 2 & 1) * 2 - 1;
        }
    }

    /** A box of the scene: center, half extents, a turn about y, a material. */
    record Box(double x, double y, double z, double hx, double hy, double hz, double yaw, int material) {
        Quat rotation() {
            return Quat.of(0, Math.sin(yaw / 2), 0, Math.cos(yaw / 2));
        }

        /** Corner {@code v} of {@link #CORNERS} in the world. */
        Vec3 corner(int v) {
            double cx = CORNERS[3 * v] * hx;
            double cy = CORNERS[3 * v + 1] * hy;
            double cz = CORNERS[3 * v + 2] * hz;
            double c = Math.cos(yaw);
            double s = Math.sin(yaw);
            return new Vec3(x + c * cx + s * cz, y + cy, z - s * cx + c * cz);
        }
    }

    /** Boxes on the grid, one per cell, turned and sized at random. */
    static List<Box> boxes(long seed) {
        Random random = new Random(seed);
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                boxes.add(new Box(4 + 8 * i + random.nextDouble() - 0.5, 1.5, 4 + 8 * j + random.nextDouble() - 0.5,
                    0.1 + 2.3 * random.nextDouble(), 0.5 + 2.5 * random.nextDouble(), 0.1 + 2.3 * random.nextDouble(),
                    random.nextDouble() * Math.PI, 1 + random.nextInt(TABLE.length - 1)));
            }
        }
        return boxes;
    }

    /** Segments between points at random over the grid, some starting or ending inside a box. */
    static double[] rays(long seed, int count) {
        Random random = new Random(seed);
        double[] rays = new double[6 * count];
        for (int i = 0; i < 6 * count; i += 3) {
            rays[i] = -2 + 44 * random.nextDouble();
            rays[i + 1] = -1 + 5 * random.nextDouble();
            rays[i + 2] = -2 + 44 * random.nextDouble();
        }
        return rays;
    }

    /** The boxes as physics sees them: convex bodies, or triangle meshes (the drawn triangles). */
    static CollisionAudioWorld physics(List<Box> boxes, boolean meshes) {
        CollisionWorld world = new CollisionWorld();
        for (Box box : boxes) {
            int collider;
            if (meshes) {
                List<Vec3> corners = new ArrayList<>();
                for (int v = 0; v < 8; v++) {
                    corners.add(box.corner(v));
                }
                int[] materials = new int[FACES.length / 3];
                java.util.Arrays.fill(materials, box.material());
                collider = world.addStatic(TriangleMeshShape.of(corners, FACES, materials), Transform.IDENTITY);
            } else {
                collider = world.addStatic(new BoxShape(new Vec3(box.hx(), box.hy(), box.hz())),
                    Transform.of(new Vec3(box.x(), box.y(), box.z()), box.rotation()));
            }
            world.setMaterial(collider, box.material());
        }
        world.update();
        return new CollisionAudioWorld(world, m -> m >= 0 && m < TABLE.length ? TABLE[m] : null, -1);
    }

    /** Frames until batch {@code ticket} is back, into {@code out}: its ray count. */
    private static int await(FrameGraph graph, TracedAudioWorld world, long ticket, Transmission[] out) {
        for (int frame = 0; frame < 16; frame++) {
            int count = world.collect(ticket, out, 0);
            if (count >= 0) {
                return count;
            }
            graph.beginFrame();
            graph.submit(0, 0);
        }
        throw new AssertionError("batch " + ticket + " never came back");
    }

    /** How many of the rays physics and the GPU agree on (each band within 1e-5), and the largest difference. */
    private static double[] compare(CollisionAudioWorld physics, double[] rays, Transmission[] traced, String what) {
        Transmission expected = new Transmission();
        int agree = 0;
        int crossed = 0;
        double largest = 0;
        for (int i = 0; i < traced.length; i++) {
            int o = 6 * i;
            crossed += physics.trace(rays[o], rays[o + 1], rays[o + 2], rays[o + 3], rays[o + 4], rays[o + 5],
                expected.open()) ? 1 : 0;
            double d = Math.max(Math.abs(expected.low() - traced[i].low()), Math.max(
                Math.abs(expected.mid() - traced[i].mid()), Math.abs(expected.high() - traced[i].high())));
            largest = Math.max(largest, d);
            agree += d <= 1e-5 ? 1 : 0;
            if (d > 1e-5) {
                Transmission back = new Transmission();
                physics.trace(rays[o + 3], rays[o + 4], rays[o + 5], rays[o], rays[o + 1], rays[o + 2], back);
                System.out.printf("  ray %d (%.3f %.3f %.3f) to (%.3f %.3f %.3f): physics mid %.4f (traced back %.4f),"
                    + " GPU %.4f%n", i, rays[o], rays[o + 1], rays[o + 2], rays[o + 3], rays[o + 4], rays[o + 5],
                    expected.mid(), back.mid(), traced[i].mid());
            }
        }
        System.out.printf("%s: %d of %d rays agree (%d cross a box), largest difference %.3g%n", what, agree,
            traced.length, crossed, largest);
        return new double[] {agree, crossed, largest};
    }

    @Test
    void theGpuTracesWhatPhysicsTracesForTheSameWalls() {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            return;
        }
        try (instance; VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
            FrameGraph graph = new FrameGraph(device, 2);
            GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(16, 1, 1), graph.frameSlots());
            GpuScene scene = new GpuScene(device, pool, 32, graph.frameSlots());
            MeshletRenderer renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT, graph.frameSlots());
            RayTracing rayTracing = new RayTracing(device, pool, 32);
            SegmentTracing tracing = new SegmentTracing(device, rayTracing, renderer, RAYS, TARGET);
            int usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            GpuImage target = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
            ExternalImage output = new ExternalImage("target", 4, 4, Format.R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
            try {
                MeshData cube = new MeshBuilder(VertexFormat.of(), 8).positions(CORNERS).submesh(FACES).buildFlat();
                int mesh = pool.add(cube);
                rayTracing.add(mesh, cube, 0);
                List<Box> boxes = boxes(19);
                for (int i = 0; i < boxes.size(); i++) {
                    Box b = boxes.get(i);
                    Quat q = b.rotation();
                    scene.set(i, mesh, b.material(), b.x(), b.y(), b.z(), (float) q.x(), (float) q.y(), (float) q.z(),
                        (float) q.w(), (float) b.hx(), (float) b.hy(), (float) b.hz());
                }
                rayTracing.origin(20, 0, 20); // rays move into the top level's frame
                graph.add(new Target()).add(tracing).output(TARGET, output);
                for (int frame = 0; frame < 12 && rayTracing.pendingBottomLevels() > 0; frame++) {
                    graph.beginFrame();
                    graph.submit(0, 0);
                }
                double[] rays = rays(20, RAYS);
                Transmission[] traced = new Transmission[RAYS];
                for (int i = 0; i < RAYS; i++) {
                    traced[i] = new Transmission();
                }
                SegmentTracer tracer = SegmentTracer.of(tracing.capacity(), SegmentTracing.WORDS, tracing::submit,
                    tracing::collect);
                TracedAudioWorld bodies = new TracedAudioWorld(tracer, m -> m < TABLE.length ? TABLE[m] : null);
                assertThat(bodies.capacity()).isEqualTo(RAYS);
                tracing.frontsOnly(true);
                assertThat(await(graph, bodies, bodies.submit(rays, RAYS), traced)).isEqualTo(RAYS);
                double[] convex = compare(physics(boxes, false), rays, traced, "convex bodies, crossings going in");
                TracedAudioWorld meshes = new TracedAudioWorld(tracer, m -> m < TABLE.length ? TABLE[m] : null);
                tracing.frontsOnly(false);
                assertThat(await(graph, meshes, meshes.submit(rays, RAYS), traced)).isEqualTo(RAYS);
                double[] sides = compare(physics(boxes, true), rays, traced, "triangle meshes, every crossing");
                assertThat(convex[1]).as("rays crossing boxes").isGreaterThan(RAYS / 4.0);
                assertThat(convex[0]).as("rays agreeing, bodies").isEqualTo(RAYS);
                assertThat(sides[0]).as("rays agreeing, meshes").isEqualTo(RAYS);
                time(graph, bodies, rays, traced, 64);
                time(graph, bodies, rays, traced, RAYS);
                device.waitIdle();
                assertThat(instance.validationErrors()).as("validation errors").isZero();
            } finally {
                device.waitIdle();
                graph.close();
                target.close();
                tracing.close();
                rayTracing.close();
                renderer.close();
                scene.close();
                pool.close();
            }
        }
    }

    /**
     * Prints what a batch of {@code count} rays costs a frame: the GPU's pass and readback (their GPU timings), and the
     * CPU's submit and collect, over 100 frames, a batch each, collected as they come back (as the occlusion does); and
     * physics tracing the same rays on one thread.
     */
    private static void time(FrameGraph graph, TracedAudioWorld world, double[] rays, Transmission[] out, int count) {
        graph.timings(true);
        double trace = 0;
        double copy = 0;
        long cpu = 0;
        int timed = 0;
        int collected = 0;
        long[] out8 = new long[8]; // the batches out, oldest first
        int head = 0;
        int tail = 0;
        for (int frame = 0; frame < 120; frame++) {
            long start = System.nanoTime();
            long t = world.submit(rays, count);
            if (t != 0) {
                out8[tail++ % 8] = t;
            }
            while (head < tail && world.collect(out8[head % 8], out, 0) >= 0) {
                head++;
                collected += frame >= 20 ? 1 : 0;
            }
            long spent = System.nanoTime() - start;
            graph.beginFrame();
            graph.submit(0, 0);
            GpuTimings timings = graph.timings();
            if (frame >= 20 && timings != null && timings.frame() > 0) {
                for (int i = 0; i < timings.count(); i++) {
                    trace += "segments".equals(timings.pass(i)) ? timings.millis(i) : 0;
                    copy += "segment crossings".equals(timings.pass(i)) ? timings.millis(i) : 0;
                }
                cpu += spent;
                timed++;
            }
        }
        graph.timings(false);
        CollisionAudioWorld physics = physics(boxes(19), false);
        Transmission t = new Transmission();
        double physicsMillis = Double.MAX_VALUE;
        for (int round = 0; round < 20; round++) { // the fastest of 20: warmed
            long start = System.nanoTime();
            for (int i = 0; i < count; i++) {
                int o = 6 * i;
                physics.trace(rays[o], rays[o + 1], rays[o + 2], rays[o + 3], rays[o + 4], rays[o + 5], t.open());
            }
            physicsMillis = Math.min(physicsMillis, (System.nanoTime() - start) / 1e6);
        }
        System.out.printf("%d rays a frame: GPU trace %.3f ms, readback copy %.3f ms; CPU submit and collect %.3f ms;"
            + " physics on one thread %.3f ms; %d batches back in %d frames%n", count, trace / timed, copy / timed,
            cpu / 1e6 / timed, physicsMillis, collected, timed);
    }
}
