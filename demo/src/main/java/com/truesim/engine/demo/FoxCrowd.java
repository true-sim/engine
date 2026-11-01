package com.truesim.engine.demo;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.Maths;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.Textures;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.scene.SceneSync;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.animation.Animator;
import com.truesim.scene.animation.Animators;
import com.truesim.scene.animation.BlendSpace1;
import com.truesim.scene.animation.Clip;
import com.truesim.scene.animation.FabrikChain;
import com.truesim.scene.animation.FootPlacement;
import com.truesim.scene.animation.LookAt;
import com.truesim.scene.animation.Rig;
import com.truesim.scene.animation.TwoBoneIk;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.gltf.Gltf;
import com.truesim.scene.render.ImageImporter;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderables;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A crowd of Khronos foxes for benchmarks ({@code -Dtruesim.demo.foxes=1000}): each with the whole rig the demo's fox
 * has (a walk/run blend space or surveying, a look-at, a FABRIK tail, foot placement on four legs), its own animator
 * and its own GPU-skinned copy, on a grid around the middle of the terrain. With {@code -Dtruesim.demo.foxLod=true},
 * animation level of detail by distance from a point (the benchmark's camera): every update within 15 m, every second
 * up to 40 m, every fourth beyond. Times what animating them costs the CPU per update: the animators, then the scene
 * sync (joint matrices and bounds for the GPU).
 */
final class FoxCrowd implements AutoCloseable {

    private final TaskScheduler tasks = new TaskScheduler();
    private final AssetCache cache = new AssetCache(tasks);
    private final JobSystem jobs = JobSystem.ofAvailableProcessors();
    private final Textures textures;
    private final SceneResources resources;
    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Renderables renderables = new Renderables(scene);
    private final Animators animators;
    private final SceneSync sync;
    private final GeometryPool pool;
    private final Animator[] crowd;
    private final LookAt look;
    private final FabrikChain tail;
    private double last;
    // Timings, after a warm-up: nanoseconds summed and updates counted; copies the GPU skinned.
    private long animating;
    private long syncing;
    private long skinned;
    private int timed;
    private int updates;

    /**
     * {@code count} foxes from {@code file} drawn into {@code gpu}; with {@code lod}, updated less often the further
     * they are from ({@code eyeX}, {@code eyeZ}).
     */
    FoxCrowd(VulkanDevice device, FrameSlots slots, GeometryPool pool, Materials materials, GpuScene gpu, Path file,
        int count, boolean lod, double eyeX, double eyeZ) throws InterruptedException {
        this.pool = pool;
        textures = new Textures(device, slots);
        resources = new SceneResources(cache, pool, materials, textures);
        sync = new SceneSync(resources, renderables, gpu, jobs);
        animators = new Animators(scene, jobs);
        PathAssets paths = new PathAssets(cache);
        paths.mounts().add("crowd", PathSource.folder(file.getParent()));
        new Gltf(paths).register();
        for (String extension : List.of("png", "jpg", "jpeg")) {
            paths.importers().add(RenderAssets.TEXTURE, extension, ImageImporter.INSTANCE);
        }
        ModelData model = await(paths.ref(RenderAssets.MODEL, "crowd://" + file.getFileName()));
        Rig.Builder r = Rig.builder(model.skeleton());
        Clip survey = r.clip(await(model.animation("Survey")));
        BlendSpace1 move = r.blendSpace1().at(0, r.clip(await(model.animation("Walk"))))
            .at(1, r.clip(await(model.animation("Run"))));
        Rig.Layer base = r.layer();
        look = r.lookAt(r.joint("b_Head_05"), 0, 0, 1).chain(r.joint("b_Neck_04"), 0.4).limit(1.2);
        tail = r.fabrik(r.joint("b_Tail01_012"), r.joint("b_Tail03_014"));
        TwoBoneIk[] legs = {
            r.twoBoneIk(r.joint("b_LeftLeg01_015"), r.joint("b_LeftLeg02_016"), r.joint("b_LeftFoot01_017")),
            r.twoBoneIk(r.joint("b_RightLeg01_019"), r.joint("b_RightLeg02_020"), r.joint("b_RightFoot01_021")),
            r.twoBoneIk(r.joint("b_LeftUpperArm_09"), r.joint("b_LeftForeArm_010"), r.joint("b_LeftHand_011")),
            r.twoBoneIk(r.joint("b_RightUpperArm_06"), r.joint("b_RightForeArm_07"), r.joint("b_RightHand_08"))};
        r.modifier(new FootPlacement(legs[0], legs[1], r.joint("b_Hip_01"), DemoMeshes::height));
        r.modifier(new FootPlacement(legs[2], legs[3], r.joint("b_Spine02_03"), DemoMeshes::height));
        Rig rig = r.build();
        crowd = new Animator[count];
        SplittableRandom random = new SplittableRandom(11);
        int side = (int) Math.ceil(Math.sqrt(count));
        for (int i = 0; i < count; i++) {
            double x = -side + 2.0 * (i % side) + random.nextDouble(-0.4, 0.4);
            double z = -side + 2.0 * (i / side) + random.nextDouble(-0.4, 0.4);
            double yaw = random.nextDouble(0, 2 * Math.PI);
            long holder = scene.create();
            scene.setTranslation(holder, x, DemoMeshes.height(x, z), z);
            scene.setRotation(holder, 0, Maths.sin(yaw / 2), 0, Maths.cos(yaw / 2));
            scene.setScale(holder, 0.012, 0.012, 0.012);
            Animator a = animators.add(rig, model.spawn(scene, renderables, holder));
            if (i % 3 == 0) {
                a.layer(base).play(survey).time(random.nextDouble(0, 5));
            } else {
                a.layer(base).play(move).time(random.nextDouble(0, 1));
                a.input(move, random.nextDouble());
            }
            a.fabrik(tail).target(x - 0.6 * Maths.sin(yaw), DemoMeshes.height(x, z) + 0.8, z - 0.6 * Maths.cos(yaw))
                .weight(0.8);
            if (lod) {
                double d = Maths.sqrt((x - eyeX) * (x - eyeX) + (z - eyeZ) * (z - eyeZ));
                a.updateEvery(d < 15 ? 1 : d < 40 ? 2 : 4);
            }
            crowd[i] = a;
        }
        System.out.println("Fox crowd: " + count + " foxes, animation level of detail " + (lod ? "on" : "off"));
    }

    private <T> T await(AssetRef<T> ref) throws InterruptedException {
        cache.acquire(ref);
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (cache.get(ref) == null && cache.failure(ref) == null) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Loading " + ref + " took too long");
            }
            Thread.sleep(1);
            tasks.drainCompleted();
        }
        if (cache.failure(ref) != null) {
            throw new IllegalStateException("Could not load " + ref, cache.failure(ref));
        }
        return cache.get(ref);
    }

    /** The crowd at {@code seconds}, looking at world ({@code lx}, {@code ly}, {@code lz}). Before the frame records. */
    void update(double seconds, double lx, double ly, double lz) {
        boolean time = ++updates > 30; // after a warm-up
        tasks.drainCompleted();
        for (Animator a : crowd) {
            a.lookAt(look).target(lx, ly, lz).weight(1);
        }
        long start = System.nanoTime();
        animators.update(Math.max(0, seconds - last));
        last = seconds;
        scene.update();
        long animated = System.nanoTime();
        renderables.update();
        sync.update();
        long synced = System.nanoTime();
        if (time) {
            animating += animated - start;
            syncing += synced - animated;
            timed++;
        }
    }

    /** Notes how many copies the GPU skinned in the frame just recorded. */
    void recorded() {
        if (updates > 30) {
            skinned += pool.deformation().skinnedLastFrame();
        }
    }

    /** What animating the crowd cost per update, as a line. */
    String report() {
        return String.format(java.util.Locale.ROOT, "Fox crowd, %d foxes: CPU per update %.3f ms animators (sampling,"
            + " blending, solvers, writing) + %.3f ms scene sync (joint matrices, bounds); GPU skinned %.0f copies per"
            + " frame", crowd.length, animating / 1e6 / Math.max(1, timed), syncing / 1e6 / Math.max(1, timed),
            skinned / (double) Math.max(1, timed));
    }

    @Override
    public void close() {
        sync.close();
        resources.close();
        cache.close();
        tasks.close();
        jobs.close();
        textures.close();
    }
}
