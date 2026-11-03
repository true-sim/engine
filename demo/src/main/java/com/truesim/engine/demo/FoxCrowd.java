package com.truesim.engine.demo;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.Maths;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.Textures;
import com.truesim.engine.scene.Animation;
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
import com.truesim.scene.animation.Joint;
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
 *
 * <p>In an {@link DemoScene.Arena arena} (the showcase's) the foxes roam instead: a third stand surveying, the others
 * walk or run around its middle on rings, faster further out, their level of detail following the camera
 * ({@link #lod}); their walk and run carry the demo fox's foot-down events ({@link DemoCharacter#FEET}).
 */
final class FoxCrowd implements AutoCloseable {

    /** How many rings the roaming foxes walk and run on, the innermost slowest. */
    private static final int RINGS = 6;

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
    private final RayTracing rayTracing; // or null
    private final Animator[] crowd;
    private final ModelData model;
    private final LookAt look;
    private final FabrikChain tail;
    private final Joint[] feet = new Joint[4];
    private final long[] holders;
    private final long[] bodies; // the nodes drawing their skinned meshes
    // Roaming, in an arena: per fox, its ring's radius (0: standing), angular speed, angle at time 0
    private final DemoScene.Arena arena; // or null: on a grid
    private final double[] radius;
    private final double[] turn;
    private final double[] phase;
    private final double[] where; // per fox: x and z where it is now
    private final Animation animation;
    private long lastAnimation; // nanoseconds the last update spent in the animators and the scene graph
    // Timings, after a warm-up: nanoseconds summed and updates counted; copies the GPU skinned.
    private long animating;
    private long syncing;
    private long skinned;
    private long refitted; // bottom levels built or refitted, with ray tracing
    private int timed;
    private int updates;

    /**
     * {@code count} foxes from {@code file} drawn into {@code gpu}; with {@code lod}, updated less often the further
     * they are from ({@code eyeX}, {@code eyeZ}); traced by {@code rayTracing}, if not null (its copies' bottom levels
     * refitted as they are skinned).
     */
    FoxCrowd(VulkanDevice device, FrameSlots slots, GeometryPool pool, Materials materials, GpuScene gpu, Path file,
        int count, boolean lod, double eyeX, double eyeZ,
        RayTracing rayTracing, Animation animation) throws InterruptedException {
        this(device, slots, pool, materials, gpu, file, count, lod, eyeX, eyeZ, rayTracing, null, animation);
    }

    /** The arena's foxes roaming in it, drawn into {@code gpu}, with level of detail by distance from the camera. */
    FoxCrowd(VulkanDevice device, FrameSlots slots, GeometryPool pool, Materials materials, GpuScene gpu,
        DemoScene.Arena arena, Animation animation) throws InterruptedException {
        this(device, slots, pool, materials, gpu, arena.fox(), arena.foxes(), true, arena.x(), arena.z(), null, arena,
            animation);
    }

    private FoxCrowd(VulkanDevice device, FrameSlots slots, GeometryPool pool, Materials materials, GpuScene gpu,
        Path file, int count, boolean lod, double eyeX, double eyeZ, RayTracing rayTracing, DemoScene.Arena arena,
        Animation animation)
        throws InterruptedException {
        this.pool = pool;
        this.rayTracing = rayTracing;
        this.arena = arena;
        textures = new Textures(device, slots);
        resources = new SceneResources(cache, pool, materials, textures);
        if (rayTracing != null) {
            resources.rayTracing(rayTracing); // its meshes and skinned copies traced
        }
        sync = new SceneSync(resources, renderables, gpu, jobs);
        this.animation = animation;
        animators = animation.animators(scene); // sampled in parallel on the plugin's threads
        PathAssets paths = new PathAssets(cache);
        paths.mounts().add("crowd", PathSource.folder(file.getParent()));
        new Gltf(paths).register();
        for (String extension : List.of("png", "jpg", "jpeg")) {
            paths.importers().add(RenderAssets.TEXTURE, extension, ImageImporter.INSTANCE);
        }
        model = await(paths.ref(RenderAssets.MODEL, "crowd://" + file.getFileName()));
        Rig.Builder r = Rig.builder(model.skeleton());
        Clip survey = r.clip(await(model.animation("Survey")));
        Clip walk = r.clip(await(model.animation("Walk")));
        Clip run = r.clip(await(model.animation("Run")));
        if (arena != null) {
            DemoCharacter.footEvents(walk, run); // dust where their feet come down
        }
        BlendSpace1 move = r.blendSpace1().at(0, walk).at(1, run);
        Rig.Layer base = r.layer();
        look = r.lookAt(r.joint("b_Head_05"), 0, 0, 1).chain(r.joint("b_Neck_04"), 0.4).limit(1.2);
        tail = r.fabrik(r.joint("b_Tail01_012"), r.joint("b_Tail03_014"));
        for (int f = 0; f < 4; f++) {
            feet[f] = r.joint(DemoCharacter.FOOT_JOINTS[f]);
        }
        TwoBoneIk[] legs = {
            r.twoBoneIk(r.joint("b_LeftLeg01_015"), r.joint("b_LeftLeg02_016"), r.joint("b_LeftFoot01_017")),
            r.twoBoneIk(r.joint("b_RightLeg01_019"), r.joint("b_RightLeg02_020"), r.joint("b_RightFoot01_021")),
            r.twoBoneIk(r.joint("b_LeftUpperArm_09"), r.joint("b_LeftForeArm_010"), r.joint("b_LeftHand_011")),
            r.twoBoneIk(r.joint("b_RightUpperArm_06"), r.joint("b_RightForeArm_07"), r.joint("b_RightHand_08"))};
        r.modifier(new FootPlacement(legs[0], legs[1], r.joint("b_Hip_01"), DemoMeshes::height));
        r.modifier(new FootPlacement(legs[2], legs[3], r.joint("b_Spine02_03"), DemoMeshes::height));
        Rig rig = r.build();
        crowd = new Animator[count];
        holders = new long[count];
        bodies = new long[count];
        radius = new double[count];
        turn = new double[count];
        phase = new double[count];
        where = new double[2 * count];
        SplittableRandom random = new SplittableRandom(11);
        int side = (int) Math.ceil(Math.sqrt(count));
        int movers = count - (count + 2) / 3;
        for (int i = 0; i < count; i++) {
            double x = -side + 2.0 * (i % side) + random.nextDouble(-0.4, 0.4);
            double z = -side + 2.0 * (i / side) + random.nextDouble(-0.4, 0.4);
            double yaw = random.nextDouble(0, 2 * Math.PI);
            double input = random.nextDouble();
            if (arena != null) {
                // Every third stands between the rings; the others take their places on the rings in turn
                double inner = 5;
                double spacing = (arena.radius() - 2 - inner) / (RINGS - 1);
                if (i % 3 == 0) {
                    double around = random.nextDouble(0, 2 * Math.PI);
                    double at = inner + spacing * (random.nextInt(RINGS - 1) + 0.5);
                    x = arena.x() + at * Maths.cos(around);
                    z = arena.z() + at * Maths.sin(around);
                } else {
                    int mover = i - i / 3 - 1;
                    int ring = mover % RINGS;
                    int onRing = movers / RINGS + (ring < movers % RINGS ? 1 : 0);
                    input = ring / (RINGS - 1.0);
                    radius[i] = inner + spacing * ring;
                    double speed = 0.9 + 2.4 * input; // metres a second, walking to running
                    turn[i] = (ring % 2 == 0 ? 1 : -1) * speed / radius[i];
                    phase[i] = 2 * Math.PI * (mover / RINGS) / onRing + 0.7 * ring;
                }
            }
            long holder = scene.create();
            holders[i] = holder;
            where[2 * i] = x;
            where[2 * i + 1] = z;
            scene.setTranslation(holder, x, DemoMeshes.height(x, z), z);
            scene.setRotation(holder, 0, Maths.sin(yaw / 2), 0, Maths.cos(yaw / 2));
            scene.setScale(holder, 0.012, 0.012, 0.012);
            long[] nodes = model.spawn(scene, renderables, holder);
            for (long node : nodes) {
                if (bodies[i] == 0 && renderables.has(node) && renderables.skin(node) != null) {
                    bodies[i] = node;
                }
            }
            Animator a = animators.add(rig, nodes);
            if (i % 3 == 0) {
                a.layer(base).play(survey).time(random.nextDouble(0, 5));
            } else {
                a.layer(base).play(move).time(random.nextDouble(0, 1));
                a.input(move, input);
            }
            a.fabrik(tail).target(x - 0.6 * Maths.sin(yaw), DemoMeshes.height(x, z) + 0.8, z - 0.6 * Maths.cos(yaw))
                .weight(0.8);
            crowd[i] = a;
        }
        if (lod) {
            lod(eyeX, eyeZ);
        }
        place(0);
        System.out.println("Fox crowd: " + count + " foxes, animation level of detail " + (lod ? "on" : "off")
            + (arena == null ? "" : ", roaming"));
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

    /**
     * Animation level of detail by distance from ({@code eyeX}, {@code eyeZ}): every update within 15 m, every second
     * up to 40 m, every fourth beyond.
     */
    void lod(double eyeX, double eyeZ) {
        for (int i = 0; i < crowd.length; i++) {
            double x = where[2 * i] - eyeX;
            double z = where[2 * i + 1] - eyeZ;
            double d = Maths.sqrt(x * x + z * z);
            crowd[i].updateEvery(d < 15 ? 1 : d < 40 ? 2 : 4);
        }
    }

    /** Moves the roaming foxes along their rings to where they are at {@code seconds}, facing the way they go. */
    private void place(double seconds) {
        if (arena == null) {
            return;
        }
        for (int i = 0; i < crowd.length; i++) {
            if (radius[i] == 0) {
                continue;
            }
            double angle = phase[i] + turn[i] * seconds;
            double c = Maths.cos(angle);
            double s = Maths.sin(angle);
            double x = arena.x() + radius[i] * c;
            double z = arena.z() + radius[i] * s;
            double yaw = turn[i] > 0 ? Maths.atan2(-s, c) : Maths.atan2(s, -c); // along the ring, either way round
            double ground = DemoMeshes.height(x, z);
            where[2 * i] = x;
            where[2 * i + 1] = z;
            scene.setTranslation(holders[i], x, ground, z);
            scene.setRotation(holders[i], 0, Maths.sin(yaw / 2), 0, Maths.cos(yaw / 2));
            crowd[i].fabrik(tail).target(x - 0.6 * Maths.sin(yaw), ground + 0.8, z - 0.6 * Maths.cos(yaw));
        }
    }

    /**
     * The crowd at {@code seconds}, looking at world ({@code lx}, {@code ly}, {@code lz}): where they go and look, in
     * UPDATE; the animation plugin poses them in ANIMATE, {@link #draw} draws them.
     */
    void update(double seconds, double lx, double ly, double lz) {
        tasks.drainCompleted();
        place(seconds);
        for (Animator a : crowd) {
            a.lookAt(look).target(lx, ly, lz).weight(1);
        }
    }

    /** Draws the crowd as animation posed it: after the frame's ANIMATE, before it records; times both. */
    void draw() {
        boolean time = ++updates > 30; // after a warm-up
        long animated = animation.lastNanos(animators);
        long start = System.nanoTime();
        renderables.update();
        sync.update();
        long synced = System.nanoTime();
        lastAnimation = animated;
        if (time) {
            animating += animated;
            syncing += synced - start;
            timed++;
        }
    }

    /** Nanoseconds the last frame spent animating the crowd (the plugin's ANIMATE): animators and transforms. */
    long lastAnimation() {
        return lastAnimation;
    }

    /** Notes how many copies the GPU skinned in the frame just recorded. */
    void recorded() {
        if (updates > 30) {
            skinned += pool.deformation().skinnedLastFrame();
            refitted += rayTracing == null ? 0 : rayTracing.bottomLevelsUpdatedLastFrame();
        }
    }

    /** The scene the foxes are nodes of. */
    SceneGraph scene() {
        return scene;
    }

    /** Their sync, which draws them: what particles' mesh shapes emit from. */
    SceneSync sync() {
        return sync;
    }

    Animators animators() {
        return animators;
    }

    /** The feet's joints, in the order of {@link DemoCharacter#FEET}. */
    Joint[] feet() {
        return feet;
    }

    /** How many foxes. */
    int count() {
        return crowd.length;
    }

    /** Fox {@code i}'s node drawing its skinned mesh. */
    long body(int i) {
        return bodies[i];
    }

    /** Fox {@code i}'s node that places it (its feet on the ground). */
    long holder(int i) {
        return holders[i];
    }

    /** Where fox {@code i} is now: its x ({@code axis} 0) or z (1). */
    double where(int i, int axis) {
        return where[2 * i + axis];
    }

    /** The skinned mesh the foxes draw, as authored (its bind pose). */
    com.truesim.scene.assets.mesh.MeshData bodyMesh() throws InterruptedException {
        for (ModelData.Node node : model.nodes()) {
            if (node.mesh() != null && node.skin() >= 0) {
                return await(node.mesh());
            }
        }
        throw new IllegalStateException("The model has no skinned mesh");
    }

    /** Whether fox {@code i} roams (walks or runs on a ring) rather than standing. */
    boolean roams(int i) {
        return radius[i] > 0;
    }

    /** What animating the crowd cost per update, as a line. */
    String report() {
        return String.format(java.util.Locale.ROOT, "Fox crowd, %d foxes: CPU per update %.3f ms animators (sampling,"
            + " blending, solvers, writing) + %.3f ms scene sync (joint matrices, bounds); GPU skinned %.0f copies per"
            + " frame%s", crowd.length, animating / 1e6 / Math.max(1, timed), syncing / 1e6 / Math.max(1, timed),
            skinned / (double) Math.max(1, timed), rayTracing == null ? "" : String.format(java.util.Locale.ROOT,
                "; ray tracing refitted %.0f bottom levels per frame (%d rebuilt in all)",
                refitted / (double) Math.max(1, timed), rayTracing.bottomLevelRebuilds()));
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
