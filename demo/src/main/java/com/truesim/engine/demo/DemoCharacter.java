package com.truesim.engine.demo;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.Maths;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.RayTracing;
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

/**
 * The Khronos fox ({@code -Dtruesim.demo.character=<glTF-Sample-Assets>/Models/Fox/glTF/Fox.gltf}) on the demo's
 * terrain, through the whole animation path: its clips in a rig, played by an animator, skinned on the GPU. A loop of a
 * few seconds: surveying, cross-fading into a walk that speeds up into a run (a blend space), and back; all the while its
 * head looks at a circling light (a look-at shared down its neck and spine), its tail follows a wagging point (a FABRIK
 * chain with limits), and its four feet stand on the uneven ground (foot placement on both pairs of legs).
 */
final class DemoCharacter implements AutoCloseable {

    /** How long the loop lasts, seconds. */
    static final double LOOP = 12;

    private final TaskScheduler tasks = new TaskScheduler();
    private final AssetCache cache = new AssetCache(tasks);
    private final Textures textures;
    private final SceneResources resources;
    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Renderables renderables = new Renderables(scene);
    private final Animators animators = new Animators(scene);
    private final SceneSync sync;
    private final long holder;
    private final double x;
    private final double z;
    private final Animator animator;
    private final Rig.Layer base;
    private final Clip survey;
    private final BlendSpace1 move;
    private final LookAt look;
    private final FabrikChain tail;
    private final double[] tailRoot = new double[3];
    private boolean moving;
    private double last;

    /**
     * The fox from {@code file}, at world ({@code x}, {@code z}) on the terrain, drawn into {@code gpu}; traced by
     * {@code rayTracing}, if not null.
     */
    DemoCharacter(VulkanDevice device, FrameSlots slots, GeometryPool pool, Materials materials, GpuScene gpu,
        Path file, double x, double z,
        RayTracing rayTracing) throws InterruptedException {
        this.x = x;
        this.z = z;
        textures = new Textures(device, slots);
        resources = new SceneResources(cache, pool, materials, textures);
        if (rayTracing != null) {
            resources.rayTracing(rayTracing); // its meshes and skinned copies traced
        }
        sync = new SceneSync(resources, renderables, gpu);
        PathAssets paths = new PathAssets(cache);
        paths.mounts().add("character", PathSource.folder(file.getParent()));
        new Gltf(paths).register();
        for (String extension : List.of("png", "jpg", "jpeg")) {
            paths.importers().add(RenderAssets.TEXTURE, extension, ImageImporter.INSTANCE);
        }
        ModelData model = await(paths.ref(RenderAssets.MODEL, "character://" + file.getFileName()));
        // The fox is about 80 units tall: a small dog's size, facing the camera.
        holder = scene.create();
        scene.setScale(holder, 0.012, 0.012, 0.012);
        long[] nodes = model.spawn(scene, renderables, holder);
        animators.morphSink(renderables::setMorphWeights);

        Rig.Builder r = Rig.builder(model.skeleton());
        survey = r.clip(await(model.animation("Survey")));
        Clip walk = r.clip(await(model.animation("Walk")));
        Clip run = r.clip(await(model.animation("Run")));
        move = r.blendSpace1().at(0, walk).at(1, run);
        base = r.layer();
        look = r.lookAt(r.joint("b_Head_05"), 0, 0, 1).chain(r.joint("b_Neck_04"), 0.4)
            .chain(r.joint("b_Spine02_03"), 0.2).limit(1.2);
        tail = r.fabrik(r.joint("b_Tail01_012"), r.joint("b_Tail03_014"));
        for (String joint : new String[] {"b_Tail01_012", "b_Tail02_013"}) {
            tail.limit(r.joint(joint), 0.6);
        }
        TwoBoneIk leftBack = r.twoBoneIk(r.joint("b_LeftLeg01_015"), r.joint("b_LeftLeg02_016"),
            r.joint("b_LeftFoot01_017"));
        TwoBoneIk rightBack = r.twoBoneIk(r.joint("b_RightLeg01_019"), r.joint("b_RightLeg02_020"),
            r.joint("b_RightFoot01_021"));
        TwoBoneIk leftFront = r.twoBoneIk(r.joint("b_LeftUpperArm_09"), r.joint("b_LeftForeArm_010"),
            r.joint("b_LeftHand_011"));
        TwoBoneIk rightFront = r.twoBoneIk(r.joint("b_RightUpperArm_06"), r.joint("b_RightForeArm_07"),
            r.joint("b_RightHand_08"));
        r.modifier(new FootPlacement(leftBack, rightBack, r.joint("b_Hip_01"), DemoMeshes::height));
        r.modifier(new FootPlacement(leftFront, rightFront, r.joint("b_Spine02_03"), DemoMeshes::height));
        Rig rig = r.build();
        animator = animators.add(rig, nodes);
        animator.layer(base).play(survey);
        update(0, 0, 0, 0);
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

    /** The fox at {@code seconds}, looking at world ({@code lx}, {@code ly}, {@code lz}). Before the frame records. */
    void update(double seconds, double lx, double ly, double lz) {
        tasks.drainCompleted(); // meshes and textures arriving
        double t = seconds % LOOP;
        // Surveying, then walking faster into a run, then surveying again: cross-fades between.
        boolean shouldMove = t >= 3 && t < 10;
        if (shouldMove != moving) {
            moving = shouldMove;
            animator.layer(base).play(moving ? move : survey, 0.5);
        }
        animator.input(move, Math.max(0, Math.min(1, (t - 4) / 4)));
        // Turning slowly where it stands, so the camera sees it from the sides as well.
        double yaw = 0.3 * Maths.sin(seconds * 0.4) + Math.PI * 0.25;
        scene.setTranslation(holder, x, DemoMeshes.height(x, z), z);
        scene.setRotation(holder, 0, Maths.sin(yaw / 2), 0, Maths.cos(yaw / 2));
        animator.lookAt(look).target(lx, ly, lz).weight(1);
        // The tail after a point wagging behind it, up and to the sides.
        double behind = 1.2;
        double side = 0.5 * Maths.sin(seconds * 3);
        tailRoot[0] = x - behind * Maths.sin(yaw) + side * Maths.cos(yaw);
        tailRoot[1] = DemoMeshes.height(x, z) + 0.9;
        tailRoot[2] = z - behind * Maths.cos(yaw) - side * Maths.sin(yaw);
        animator.fabrik(tail).target(tailRoot[0], tailRoot[1], tailRoot[2]).weight(0.8);
        animators.update(Math.max(0, seconds - last));
        last = seconds;
        scene.update();
        renderables.update();
        sync.update();
    }

    @Override
    public void close() {
        sync.close();
        resources.close();
        cache.close();
        tasks.close();
        textures.close();
    }
}
