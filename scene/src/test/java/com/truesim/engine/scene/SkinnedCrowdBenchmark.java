package com.truesim.engine.scene;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Clock;
import com.truesim.engine.app.Engine;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Interpolation;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.animation.AnimationClip;
import com.truesim.scene.animation.Animator;
import com.truesim.scene.animation.Animators;
import com.truesim.scene.animation.Rig;
import com.truesim.scene.animation.Skeleton;
import com.truesim.scene.animation.Track;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.meshbuild.MeshBuilder;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderable;
import com.truesim.scene.render.SkinBinding;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A5: a crowd of 1,000 skinned characters of the Khronos fox's size (24 joints in a chain, 1,728 vertices), each with its
 * own animator bending every joint, drawn at 1920 x 1080 with frames at 144 a second of simulated time and a world clock
 * of 60 steps a second. Compares the animators once per frame ({@code ANIMATE}) with the animators on fixed steps, their
 * joints drawn between steps on the GPU (the skinning blends two steps' matrices), on the CPU (the sync blends them into
 * the copy's matrices each frame) or not at all. Prints per kind of frame (with a step, without) the medians of the
 * engine's phases (steps and ANIMATE: animators, transforms, the joints captured) and of the sync, the joint matrices
 * uploaded, the GPU time of the deformation pass and of the whole frame, and what the main thread allocated. Run with
 * {@code TRUESIM_BENCHMARK=true ./gradlew :engine-scene:test --tests '*SkinnedCrowdBenchmark'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class SkinnedCrowdBenchmark {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int CROWD = Integer.parseInt(System.getenv().getOrDefault("TRUESIM_BENCHMARK_CROWD", "1000"));
    private static final int JOINTS = 24;
    private static final int RINGS = 72;   // 72 rings of 24: the fox's 1,728 vertices
    private static final int SIDES = 24;
    private static final double LENGTH = 0.05; // between joints: a body 1.2 m long
    private static final int FRAMES = 288; // two seconds at 144 frames a second, measured per run
    private static final com.sun.management.ThreadMXBean THREADS =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    record Body(int rings) {
    }

    static final Generator<Body, MeshData> BODIES = (body, seed) -> body(body.rings());
    static final AssetRef<MeshData> BODY = AssetRef.generated(RenderAssets.MESH, BODIES, new Body(RINGS), 1);

    /** A tube along z, 0.2 m across, each ring on the two joints nearest it, weighted by distance. */
    static MeshData body(int rings) {
        int vertices = rings * SIDES;
        float[] positions = new float[3 * vertices];
        float[] normals = new float[3 * vertices];
        float[] weights = new float[4 * vertices];
        int[] joints = new int[4 * vertices];
        double length = (JOINTS - 1) * LENGTH;
        for (int r = 0; r < rings; r++) {
            double z = length * r / (rings - 1);
            double along = z / LENGTH;
            int j = Math.min(JOINTS - 2, (int) along);
            double t = Math.min(1, along - j);
            for (int s = 0; s < SIDES; s++) {
                int v = r * SIDES + s;
                double a = 2 * Math.PI * s / SIDES;
                positions[3 * v] = (float) (0.1 * Math.cos(a));
                positions[3 * v + 1] = (float) (0.1 * Math.sin(a));
                positions[3 * v + 2] = (float) z;
                normals[3 * v] = (float) Math.cos(a);
                normals[3 * v + 1] = (float) Math.sin(a);
                joints[4 * v] = j;
                joints[4 * v + 1] = j + 1;
                weights[4 * v] = (float) (1 - t);
                weights[4 * v + 1] = (float) t;
            }
        }
        int[] indices = new int[6 * (rings - 1) * SIDES];
        int k = 0;
        for (int r = 0; r < rings - 1; r++) {
            for (int s = 0; s < SIDES; s++) {
                int a = r * SIDES + s;
                int b = r * SIDES + (s + 1) % SIDES;
                int c = a + SIDES;
                int d = b + SIDES;
                indices[k++] = a;
                indices[k++] = b;
                indices[k++] = c;
                indices[k++] = b;
                indices[k++] = d;
                indices[k++] = c;
            }
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.JOINTS,
            VertexAttribute.WEIGHTS), vertices).positions(positions).attribute(VertexAttribute.NORMAL, normals)
            .attribute(VertexAttribute.WEIGHTS, weights).joints(joints).submesh(indices).buildFlat();
    }

    /** The chain: joint 0 at the holder, each next one {@link #LENGTH} further along z. */
    static Skeleton skeleton() {
        String[] names = new String[JOINTS];
        int[] parents = new int[JOINTS];
        double[] rest = new double[10 * JOINTS];
        for (int j = 0; j < JOINTS; j++) {
            names[j] = "j" + j;
            parents[j] = j - 1;
            rest[10 * j + 2] = j == 0 ? 0 : LENGTH;
            rest[10 * j + 6] = 1;
            rest[10 * j + 7] = 1;
            rest[10 * j + 8] = 1;
            rest[10 * j + 9] = 1;
        }
        return new Skeleton(names, parents, rest);
    }

    /** Every joint swinging about x and y, 0.25 rad either way, a second a swing, each joint later than its parent. */
    static AnimationClip swing() {
        List<Track> tracks = new ArrayList<>();
        int keys = 9;
        for (int j = 0; j < JOINTS; j++) {
            float[] times = new float[keys];
            float[] values = new float[4 * keys];
            for (int k = 0; k < keys; k++) {
                times[k] = k / (keys - 1f);
                double phase = 2 * Math.PI * (times[k] - j / (double) JOINTS);
                double ax = 0.25 * Math.sin(phase);
                double ay = 0.25 * Math.cos(phase);
                // about x, then about y
                double cx = Math.cos(ax / 2);
                double sx = Math.sin(ax / 2);
                double cy = Math.cos(ay / 2);
                double sy = Math.sin(ay / 2);
                values[4 * k] = (float) (sx * cy);
                values[4 * k + 1] = (float) (cx * sy);
                values[4 * k + 2] = (float) (-sx * sy);
                values[4 * k + 3] = (float) (cx * cy);
            }
            tracks.add(new Track("j" + j, 0, Track.Property.ROTATION, Track.Interpolation.LINEAR, times, values, 0));
        }
        return new AnimationClip("swing", tracks);
    }

    @Test
    void crowd() throws InterruptedException {
        FrameSlots slots = new FrameSlots(2);
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("benchmark").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 22, 100_000, 4), slots);
             Materials materials = new Materials(device, 64, slots);
             Textures textures = new Textures(device, slots);
             TaskScheduler tasks = new TaskScheduler();
             JobSystem jobs = JobSystem.ofAvailableProcessors();
             AssetCache assets = new AssetCache(tasks);
             SceneResources resources = new SceneResources(assets, pool, materials, textures);
             GpuScene gpu = new GpuScene(device, pool, CROWD + 64, slots);
             MeshletRenderer renderer = new MeshletRenderer(device, gpu, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, slots);
             GpuImage shaded = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
             FrameGraph graph = new FrameGraph(device, slots)) {
            SceneGraph scene = new SceneGraph(new ChangeClock(), CROWD * (JOINTS + 2) + 64, 1 << 20);
            ComponentStore<Renderable> renderables = scene.components().store(Renderable.TYPE);
            SceneSync sync = new SceneSync(resources, scene, gpu, jobs);
            Animation animation = new Animation();
            Skeleton skeleton = skeleton();
            Rig rig = Rig.of(skeleton);
            AnimationClip swing = swing();
            float[] inverseBinds = new float[16 * JOINTS];
            for (int j = 0; j < JOINTS; j++) {
                inverseBinds[16 * j] = 1;
                inverseBinds[16 * j + 5] = 1;
                inverseBinds[16 * j + 10] = 1;
                inverseBinds[16 * j + 15] = 1;
                inverseBinds[16 * j + 14] = (float) (-j * LENGTH); // the rest joint's world z, undone
            }
            Animator[] crowd = new Animator[CROWD];
            Application world = new Application() {
                @Override
                public void configure(AppConfig app) {
                    app.use(animation).defaults(Engine.FRAME_RATE, 10_000);
                }
            };
            Camera camera = new Camera().set(0, -0.5, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            ViewPort port = graph.addView("main").size(WIDTH, HEIGHT);
            SceneView main = geometry.createView(port, camera, ViewOptions.of(1 << 18, 1 << 22).withOcclusion(true));
            RenderView cameraView = main.view();
            ExternalImage target = new ExternalImage("shaded", WIDTH, HEIGHT, Format.R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .set(shaded.handle(), 0);
            port.add(new ClusteredForwardShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT))
                .output(Keys.SCENE_COLOR, target);
            try (Engine engine = Engine.create(world)) {
                Clock clock = engine.addClock("crowd"); // 60 steps a second
                sync.follow(clock);
                Animators animators = animation.animators(scene, clock);
                int side = (int) Math.ceil(Math.sqrt(CROWD));
                for (int i = 0; i < CROWD; i++) {
                    long holder = scene.create();
                    scene.setTranslation(holder, 1.5 * (i % side - side / 2.0), 0, -3 - 1.5 * (i / side));
                    long body = scene.create(holder);
                    long[] nodes = new long[JOINTS];
                    for (int j = 0; j < JOINTS; j++) {
                        nodes[j] = scene.create(j == 0 ? holder : nodes[j - 1]);
                        scene.setTranslation(nodes[j], 0, 0, j == 0 ? 0 : LENGTH);
                    }
                    renderables.set(body, Renderable.of(BODY, List.of(SceneSyncTest.RED)));
                    renderables.set(body, renderables.get(body).withSkin(new SkinBinding(nodes, inverseBinds)));
                    crowd[i] = animators.add(rig, nodes);
                    crowd[i].play(swing).loop(true).time(i * 0.013 % 1);
                }
                camera.position(0, 6, 4);
                long[] now = {0};
                while (resources.uploadedMeshCount() < 1 || sync.instanceCount() < CROWD) {
                    Thread.sleep(1);
                    frame(engine, graph, slots, tasks, scene, renderables, sync, cameraView, camera, now);
                }
                System.out.printf(Locale.ROOT, "Skinned crowd benchmark on %s: %,d characters of %d joints and %,d "
                    + "vertices, %d animation threads, %dx%d, frames at 144/s, steps at 60/s%n", device.gpu().name(),
                    CROWD, JOINTS, RINGS * SIDES, animation.threadCount(), WIDTH, HEIGHT);
                graph.timings(true);
                String[] runs = {"every frame", "fixed, GPU", "fixed, CPU", "fixed, OFF"};
                for (int round = 0; round < 3; round++) {
                    for (String run : runs) {
                        boolean fixed = !run.equals("every frame");
                        sync.interpolation(run.endsWith("CPU") ? Interpolation.CPU
                            : run.endsWith("OFF") ? Interpolation.OFF : Interpolation.GPU);
                        for (Animator a : crowd) {
                            a.fixedSteps(fixed);
                        }
                        measure(run, engine, clock, graph, slots, tasks, scene, renderables, sync, pool, cameraView,
                            camera, now);
                    }
                }
                graph.timings(false);
            }
            device.waitIdle();
            sync.close();
        }
    }

    private static void frame(Engine engine, FrameGraph graph, FrameSlots slots, TaskScheduler tasks,
        SceneGraph scene, ComponentStore<Renderable> renderables, SceneSync sync, RenderView cameraView, Camera camera, long[] now) {
        int slot = graph.beginFrame();
        slots.begin(slot);
        tasks.drainCompleted();
        now[0] += 1_000_000_000L / 144;
        engine.stepAt(now[0]);
        scene.update();
        sync.update();
        cameraView.set(camera, 1, MeshletRenderer.MODE_LIT);
        graph.submit(0, 0);
        slots.end();
    }

    private static void measure(String run, Engine engine, Clock clock, FrameGraph graph, FrameSlots slots,
        TaskScheduler tasks, SceneGraph scene, ComponentStore<Renderable> renderables, SceneSync sync, GeometryPool pool,
        RenderView cameraView, Camera camera, long[] now) {
        long[] phases = new long[FRAMES];
        long[] syncs = new long[FRAMES];
        long[] uploaded = new long[FRAMES];
        long[] allocated = new long[FRAMES];
        boolean[] stepped = new boolean[FRAMES];
        boolean[] steppedAt = new boolean[4096]; // by the graph's frame number, for its timings two frames late
        long[] deformGpu = new long[FRAMES];
        long[] frameGpu = new long[FRAMES];
        boolean[] gpuStepped = new boolean[FRAMES];
        int gpuCount = 0;
        for (int frame = -144; frame < FRAMES; frame++) { // a second to warm up
            long bytes = THREADS.getCurrentThreadAllocatedBytes();
            int slot = graph.beginFrame();
            slots.begin(slot);
            long graphFrame = slots.frames();
            tasks.drainCompleted();
            long t0 = System.nanoTime();
            now[0] += 1_000_000_000L / 144;
            engine.stepAt(now[0]); // FIXED (fixed animators, joints captured), ANIMATE (the others)
            long t1 = System.nanoTime();
            scene.update();
            sync.update();
            long t2 = System.nanoTime();
            cameraView.set(camera, 1, MeshletRenderer.MODE_LIT);
            steppedAt[(int) (graphFrame % steppedAt.length)] = clock.stepsLastFrame() > 0;
            graph.submit(0, 0);
            slots.end();
            if (frame >= 0) {
                phases[frame] = t1 - t0;
                syncs[frame] = t2 - t1;
                stepped[frame] = clock.stepsLastFrame() > 0;
                allocated[frame] = THREADS.getCurrentThreadAllocatedBytes() - bytes;
                uploaded[frame] = pool.deformation().bytesUploadedLastFrame();
                GpuTimings timings = graph.timings();
                if (timings != null && gpuCount < FRAMES) {
                    long deform = 0;
                    long total = 0;
                    for (int p = 0; p < timings.count(); p++) {
                        long nanos = (long) (timings.millis(p) * 1e6);
                        total += nanos;
                        if (timings.pass(p).equals("deformation")) {
                            deform += nanos;
                        }
                    }
                    deformGpu[gpuCount] = deform;
                    frameGpu[gpuCount] = total;
                    gpuStepped[gpuCount++] = steppedAt[(int) (timings.frame() % steppedAt.length)];
                }
            }
        }
        System.out.printf(Locale.ROOT, "  %-12s with a step: phases %6.3f sync %6.3f ms, matrices %,9d B, GPU deformation "
            + "%6.3f frame %6.3f ms | without: phases %6.3f sync %6.3f ms, matrices %,9d B, GPU deformation %6.3f "
            + "frame %6.3f ms | matrices %,d B a frame on average | %,d B allocated (median), %,d max; %d drawn "
            + "between steps%n", run,
            median(phases, stepped, true) / 1e6, median(syncs, stepped, true) / 1e6, median(uploaded, stepped, true),
            median(Arrays.copyOf(deformGpu, gpuCount), Arrays.copyOf(gpuStepped, gpuCount), true) / 1e6,
            median(Arrays.copyOf(frameGpu, gpuCount), Arrays.copyOf(gpuStepped, gpuCount), true) / 1e6,
            median(phases, stepped, false) / 1e6, median(syncs, stepped, false) / 1e6, median(uploaded, stepped, false),
            median(Arrays.copyOf(deformGpu, gpuCount), Arrays.copyOf(gpuStepped, gpuCount), false) / 1e6,
            median(Arrays.copyOf(frameGpu, gpuCount), Arrays.copyOf(gpuStepped, gpuCount), false) / 1e6,
            (long) Arrays.stream(uploaded).average().orElse(0), median(allocated, stepped, null),
            Arrays.stream(allocated).max().orElse(0), sync.interpolatedSkinCount());
    }

    /** The median of {@code values} over the frames whose {@code stepped} is {@code which} (null: all). */
    private static long median(long[] values, boolean[] stepped, Boolean which) {
        long[] kept = new long[values.length];
        int n = 0;
        for (int i = 0; i < values.length; i++) {
            if (which == null || stepped[i] == which) {
                kept[n++] = values[i];
            }
        }
        if (n == 0) {
            return 0;
        }
        long[] sorted = Arrays.copyOf(kept, n);
        Arrays.sort(sorted);
        return sorted[n / 2];
    }
}
