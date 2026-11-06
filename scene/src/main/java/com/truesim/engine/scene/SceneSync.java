package com.truesim.engine.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.JobSystem;
import com.truesim.common.math.Maths;
import com.truesim.common.math.TransformArray;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.app.Clock;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.InstanceMotion;
import com.truesim.engine.render.Interpolation;
import com.truesim.engine.render.RenderSettings;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.NodeChange;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.MorphWeights;
import com.truesim.scene.render.Renderable;
import com.truesim.scene.render.SkinBinding;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A scene kept on the GPU: its {@link Renderable} components as instances of a {@link GpuScene}, at their world
 * positions, from shared {@link SceneResources}, following the scene's and the renderables' change logs. What draws the instances is
 * the renderers' business: any number of {@link com.truesim.engine.render.RenderView}s (cameras, shadow maps,
 * mirrors), each at its own world position, all culled in one pass; moving a camera rewrites no instance.
 *
 * <pre>{@code
 * GpuScene gpu = new GpuScene(device, resources.pool(), 100_000, slots);
 * SceneSync sync = new SceneSync(resources, scene, gpu);
 * MeshletRenderer renderer = new MeshletRenderer(device, gpu, colorFormat, depthFormat, slots);
 * SceneGeometry geometry = SceneGeometry.meshlets(renderer);
 * ViewPort port = graph.addView("main").size(width, height);
 * SceneView view = geometry.createView(port, camera, ViewOptions.of(1 << 18, 1 << 22).withOcclusion(true));
 * port.add(new ClusteredForwardShading(geometry, view, colorFormat, depthFormat));
 *
 * // every frame, after the frame boundary (tasks.drainCompleted()) and scene.update():
 * sync.update();                               // the instances that changed
 * camera.position(cameraX, cameraY, cameraZ);
 * view.set(1, MeshletRenderer.MODE_LIT);
 * }</pre>
 *
 * <p>Several scenes may fill one {@link GpuScene} (a world and an editor's gizmos), each with a sync of its own and the
 * same resources: their instances take ids from the one table, and views tell them apart by their layers
 * ({@code SceneGraph.setLayers}, {@link com.truesim.engine.render.ViewOptions#withLayers}). Or each scene has a
 * {@link GpuScene} and a renderer of its own; either way a mesh or material used by several is on the GPU once.
 *
 * <ul>
 *   <li><b>Assets by reference:</b> a renderable draws once its mesh is on the GPU; materials draw untextured until
 *       their textures arrive, and as the default until they load (see {@link SceneResources}).</li>
 *   <li><b>Materials per submesh:</b> a renderable's materials take consecutive ids in the material table, one per
 *       submesh of its mesh, shared by every renderable of every scene with the same materials and mesh layout.</li>
 *   <li><b>Changes, not the whole scene:</b> renderables added, changed or removed, and nodes moved, are found in the
 *       change logs. Only moved instances are written, or every one when most nodes moved; a renderable's change
 *       redoes only the fields that changed (a material swap takes materials, the mesh stays).</li>
 *   <li><b>Stable ids:</b> a renderable keeps its instance id while drawn; a removed one leaves a hole the next takes
 *       (the lowest free id), so what the GPU keeps per instance stays with it. Deterministic: the same changes give the
 *       same ids. A new renderable may take an id freed the same frame, and then starts with what the GPU kept for the
 *       id (seen or hidden by occlusion culling) for its first frame.</li>
 *   <li><b>Skins and morph targets:</b> a renderable with a skin ({@link Renderable#skin}) whose mesh has joints,
 *       or whose mesh has morph targets, draws a copy of its own ({@code GeometryPool.addDeformed}), posed each update
 *       from its joints' world transforms relative to its node, in doubles, and its morph weights
 *       ({@link MorphWeights}), and deformed on the GPU before anything draws.</li>
 *   <li><b>Layers and shadows:</b> instances are on their nodes' layers ({@code SceneGraph.setLayers}), drawn in views
 *       of any of them ({@link com.truesim.engine.render.RenderView#filter}), and cast shadows as their renderables
 *       say: shadow maps and shadow rays leave out those that do not.</li>
 * </ul>
 *
 * <p>Materials' masked alpha and double-sidedness follow their data (blended alpha draws opaque, for now). Updating
 * allocates nothing once warm when nothing but transforms changed. Use it on the main thread, the asset cache's.
 */
public final class SceneSync implements AutoCloseable {

    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED;
    private static final int NONE = SceneResources.NONE;
    /** Instances written per task, and from how many on the writes run on the job system's threads. */
    private static final int GRAIN = 1024;
    private static final int PARALLEL = 4 * GRAIN;
    /** Copies posed per task, and from how many on they are posed on the job system's threads. */
    private static final int POSE_GRAIN = 8;
    private static final int POSE_PARALLEL = 4 * POSE_GRAIN;

    private final SceneResources resources;
    private final ComponentStore<Renderable> renderables;
    private final ComponentStore<MorphWeights> morphs;
    private final SceneGraph scene;
    private final ChangeClock clock;
    private final GpuScene gpu;
    private final JobSystem jobs; // or null: the instances are written on the calling thread
    private final JobSystem.RangeTask fill = this::fill;
    private final JobSystem.RangeTask writeMotions = this::writeMotions;
    private final JobSystem.RangeTask writeBlended = this::writeBlended;
    private final JobSystem.RangeTask writeJoints = this::writeJoints;
    private final ComponentStore.Visitor onRenderableChange = (node, changes) -> reconcile(node);
    private final NodeChange.Visitor onSceneChange = this::sceneChanged;
    private long seen;
    private boolean started;
    private boolean closed;

    // Items, one per renderable, by node index.
    private long[] itemNode = new long[64];
    private ViewMesh[] itemMesh = new ViewMesh[64];
    private SceneResources.MaterialSet[] itemSet = new SceneResources.MaterialSet[64];
    private boolean[] itemCasts = new boolean[64]; // whether its renderable casts shadows, as last reconciled
    private int[] itemSlot = filled(64);
    private int[] itemNext = filled(64); // the next item of the same mesh
    private int[] itemPrevious = filled(64);
    private boolean[] moved = new boolean[64];
    private int[] movedItems = new int[64];
    private int movedCount;
    // The instances written this update: items and their entries in the GPU scene's changes.
    private int[] fillItems = new int[64];
    private int[] fillEntries = new int[64];
    private int itemCount;
    private long instanceVersion;
    // Pickable nodes bounded by their meshes' boxes (bounds(true), the default): the Pickable store, when it was read,
    // and per item whether the bounds the node has are the ones set here
    private boolean bounds = true;
    private com.truesim.scene.core.ComponentStore<com.truesim.scene.core.Pickable> pickables;
    private long pickablesSeen;
    private boolean[] itemBounded = new boolean[64];
    private final com.truesim.scene.core.ComponentStore.Visitor onPickable = (node, changes) -> pickableChanged(node);
    // Skinned items: the deformed copy each draws (NONE: its mesh as it is), and those that have one, densely.
    private int[] itemCopy = filled(64);
    private boolean[] itemCopySkinned = new boolean[64]; // whether its copy was made with the skin's joints
    private int[] skinned = new int[64];
    private int skinnedCount;
    // Copies to pose this update: those whose joints (or their ancestors in the model, or the mesh's node) moved, found
    // in the scene's log through the nodes each copy watches (lists by node index; entries of copies gone are dropped
    // as met). Copies with morph targets are posed every update: their weights are not logged.
    private boolean[] poseDue = new boolean[64];
    private int[] watchHead = filled(64);
    private int[] watchItem = new int[64];
    private int[] watchCopy = new int[64];
    private int[] watchNext = new int[64];
    private int watchCount;
    private int watchFree = NONE;
    private int[] stamp = new int[64]; // by node index: the registration that watched it last, against duplicates
    private int stamps;
    private int posed; // copies posed in the last update
    // Scratch for posing copies, one per thread posing (the job system's workers, or the calling thread alone), and the
    // copies to pose this update: posed in parallel when many.
    private final Scratch[] scratches;
    private int[] poseItems = new int[64];
    private int poseCount;
    private final JobSystem.RangeTask poseMany = this::poseMany;

    /** What posing a copy works in: the node's world transform inverted, a joint's, its matrix, the pose's box. */
    private static final class Scratch {
        final double[] relative = new double[12];
        final double[] jointWorld = new double[12];
        final double[] matrix = new double[12];
        final double[] box = new double[6];
    }

    // Instance ids (itemSlot) are the GpuScene's, stable while drawn, the lowest free one taken: what the GPU keeps per
    // instance (occlusion history, later previous transforms) stays with its instance.
    private int instanceCount;          // drawn
    // Items to draw at the end of the update, oldest first: their meshes arrived (maybe before the changes of the
    // frame were followed), or assets they wait for had not. Checked again then: they may be gone.
    private int[] waiting = new int[64];
    private int waitingCount;
    private boolean[] isWaiting = new boolean[64];

    // Drawn items changing mesh or materials: the new ones held until what they wait for is on the GPU, then swapped
    // in, the instance id kept; the old ones drawn meanwhile.
    private ViewMesh[] pendingMesh = new ViewMesh[64];
    private SceneResources.MaterialSet[] pendingSet = new SceneResources.MaterialSet[64];
    private int[] swapping = new int[64];
    private int swappingCount;
    private boolean[] isSwapping = new boolean[64];

    private boolean rewriteAll = true;

    // Interpolation between fixed steps (D91), once a clock is followed: by node index, each drawn item's pose as last
    // captured or written (position, rotation, scale: 10 doubles) and, while it moves in steps, its pose at the step
    // before; the items moving (tracked), each with its motion record on the GPU; opted out and teleported nodes.
    private static final int POSE = 10;
    private Clock stepClock; // the world's, whose steps capture moves; null: none followed
    private Interpolation interpolation = Interpolation.GPU;
    private final NodeChange.Visitor onStepChange = this::stepChanged;
    private final Runnable afterStep = this::stepped;
    private double[] drawnPose = new double[64 * POSE];
    private double[] fromPose = new double[64 * POSE];
    private long[] movedTick = new long[64];
    private int[] motionSlot = filled(64);
    private boolean[] isTracked = new boolean[64];
    private int[] trackedAt = new int[64];
    private int[] tracked = new int[64];
    private int trackedCount;
    private boolean[] still = new boolean[64];      // opted out: drawn as stepped
    private boolean[] teleported = new boolean[64]; // its next move starts from where it lands
    private boolean[] restart = new boolean[64];    // teleported: the GPU's pose before is not where it starts
    private long fixedThrough = Long.MIN_VALUE;     // the stamp of the last step's changes
    private int captured;                           // changes captured in steps since the last update
    private boolean steppedSince;                   // a step ran since the last update
    private double blendFactor;                     // the frame's, for the CPU's writes
    private com.truesim.engine.settings.EngineSettings.Subscription settingWatch; // render.interpolation, or null
    private boolean settingChanged;                 // since the last update
    private com.truesim.engine.settings.EngineSettings.Subscription listWatch;  // render.changeList, or null
    private com.truesim.engine.settings.EngineSettings.Subscription chunkWatch; // render.changeListChunk, or null
    private boolean listChanged;                    // either, since the last update
    private boolean byHand;                         // interpolation(mode) was called: the setting no longer applies

    // Joints moved in steps (A5), by node index of the skinned renderables: those drawn between their last two steps'
    // poses (densely, with their places), those the step running moved, the tick of their last step and of a teleport,
    // and the boxes of their last pose and of the pose their step's move started from; on the CPU path each one's two
    // poses (the deformation's values), blended into its copy each frame.
    private static final int BOX = 7;
    private static final int JOINT_GRAIN = 16;   // skinned renderables blended per task on the CPU path
    private boolean[] jointsTracked = new boolean[64];
    private int[] jointsAt = new int[64];
    private int[] jointItems = new int[64];
    private int jointCount;
    private boolean[] jointsDue = new boolean[64];
    private int[] dueItems = new int[64];
    private int dueCount;
    private long[] jointsTick = new long[64];
    private long[] snapTick = new long[64];
    private double[] lastBox = new double[64 * BOX];
    private double[] fromBox = new double[64 * BOX];
    private float[][] fromValues = new float[64][];
    private float[][] toValues = new float[64][];
    private double jointFactor;                     // the frame's, for the CPU's blends

    private final Map<AssetRef<MeshData>, ViewMesh> meshes = new HashMap<>();

    /** A mesh this scene's renderables use: the shared upload, and the list of this scene's items using it. */
    private final class ViewMesh implements SceneResources.MeshUser {
        SceneResources.Mesh mesh;
        int holders;
        int firstItem = NONE;

        @Override
        public void uploaded(SceneResources.Mesh uploaded) {
            // Drawn at the end of the update, once the frame's changes are followed: a renderable may be gone already.
            // Its materials are asked for now, so they load meanwhile (an awaitLoaded waits for them too).
            for (int item = firstItem; item != NONE; item = itemNext[item]) {
                holdMaterials(item);
                queue(item);
            }
        }
    }

    /**
     * Keeps the {@link Renderable}s of {@code scene} as instances of {@code gpu}, which holds meshes of
     * {@code resources}' pool and is given its material table (scenes sharing a {@link GpuScene} share their resources
     * too).
     */
    public SceneSync(SceneResources resources, SceneGraph scene, GpuScene gpu) {
        this(resources, scene, gpu, null);
    }

    /**
     * Like {@link #SceneSync(SceneResources, SceneGraph, GpuScene)}, writing many instances at once (most nodes moved,
     * say) on {@code jobs}' threads: each instance is claimed in order on the calling thread and its record written
     * on any, so the ids and the result do not depend on the threads.
     */
    @AllowAllocation("created rarely")
    public SceneSync(SceneResources resources, SceneGraph scene, GpuScene gpu, JobSystem jobs) {
        this.jobs = jobs;
        scratches = new Scratch[jobs == null ? 1 : jobs.threads()];
        for (int i = 0; i < scratches.length; i++) {
            scratches[i] = new Scratch();
        }
        this.resources = Objects.requireNonNull(resources, "resources");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        if (gpu.pool() != resources.pool()) {
            throw new IllegalArgumentException("The GPU scene holds another geometry pool's meshes than the resources "
                + "fill");
        }
        if (gpu.drawsMaterials() && gpu.materials() != resources.materials()) {
            throw new IllegalArgumentException("The GPU scene draws another material table than the resources fill");
        }
        this.clock = scene.clock();
        renderables = scene.components().store(Renderable.TYPE);
        morphs = scene.components().store(MorphWeights.TYPE);
        pickables = scene.components().store(com.truesim.scene.core.Pickable.TYPE);
        gpu.setMaterials(resources.materials()); // the resources' table: instances name its ids
        com.truesim.engine.settings.EngineSettings followed = resources.settings();
        if (followed != null) { // render.interpolation as the player sets it, switched at the next update
            interpolation = RenderSettings.interpolation(followed);
            layout();
            settingWatch = followed.onChange(RenderSettings.INTERPOLATION, () -> settingChanged = true);
            // render.changeList and its chunks as set, from the next update on
            gpu.changeList(followed.get(RenderSettings.CHANGE_LIST), followed.get(RenderSettings.CHANGE_LIST_CHUNK));
            listWatch = followed.onChange(RenderSettings.CHANGE_LIST, () -> listChanged = true);
            chunkWatch = followed.onChange(RenderSettings.CHANGE_LIST_CHUNK, () -> listChanged = true);
        }
    }

    private static int[] filled(int length) {
        int[] array = new int[length];
        Arrays.fill(array, NONE);
        return array;
    }

    /**
     * Whether pickable renderables (nodes with a {@code Pickable} component) are bounded by their meshes' boxes
     * (default on): the spatial index finds them, so the scene interaction picks what is drawn without anyone setting
     * bounds. Only pickable nodes: bounds cost every move a world box, and nothing else here queries the index. A
     * node's bounds are set once its mesh draws (and again if its mesh changes), unless the game set its own first.
     * Off for a game bounding its pickables itself; nodes keep the bounds they have. Returns this.
     */
    public SceneSync bounds(boolean on) {
        checkOpen();
        if (on && !bounds) {
            pickablesSeen = -1; // every pickable drawn now is bounded at the next update
        }
        bounds = on;
        return this;
    }

    /** Whether pickable renderables are bounded by their meshes ({@link #bounds(boolean)}). */
    public boolean bounds() {
        return bounds;
    }

    /** Bounds {@code item}'s node by its mesh's box if it is pickable, drawn, and not bounded by the game. */
    private void bound(int item) {
        long node = itemNode[item];
        if (!bounds || itemSlot[item] == NONE || !pickables.has(node) || !scene.contains(node)
            || scene.hasBounds(node) && !itemBounded[item]) {
            return;
        }
        MeshData mesh = itemMesh[item].mesh.data; // its asset's box: the same for every node drawing it
        scene.setLocalBounds(node, mesh.boxMin(0), mesh.boxMin(1), mesh.boxMin(2), mesh.boxMin(0) + mesh.boxExtent(0),
            mesh.boxMin(1) + mesh.boxExtent(1), mesh.boxMin(2) + mesh.boxExtent(2));
        itemBounded[item] = true;
    }

    /** A node's Pickable came, changed or went: a drawn renderable there is bounded. */
    private void pickableChanged(long node) {
        int item = NodeHandle.index(node);
        if (item < itemNode.length && itemNode[item] == node) {
            bound(item);
        }
    }

    /** Follows the Pickable components added since the last update (all of them after falling behind). */
    private void followPickables() {
        if (!bounds || pickables.version() <= pickablesSeen) {
            return; // nothing new: no work
        }
        long since = pickablesSeen;
        pickablesSeen = clock.now();
        if (since < 0 || !pickables.changesSince(since, onPickable)) {
            for (int i = 0; i < pickables.size(); i++) {
                pickableChanged(pickables.node(i));
            }
        }
    }

    /** The instances this scene is kept as. */
    public GpuScene gpu() {
        return gpu;
    }

    /** The scene whose renderables it keeps. */
    public SceneGraph scene() {
        return scene;
    }

    public SceneResources resources() {
        return resources;
    }

    /**
     * Brings the instances up to date: renderables added, changed or removed since the last update, and nodes moved.
     * Call after the frame boundary and {@code scene.update()}, before the frame graph records. Allocates nothing once warm when only transforms
     * changed.
     */
    public void update() {
        checkOpen();
        followPickables(); // pickables bounded by their meshes: from the next scene.update() on, picking finds them
        if (listChanged) {
            listChanged = false;
            gpu.changeList(resources.settings().get(RenderSettings.CHANGE_LIST),
                resources.settings().get(RenderSettings.CHANGE_LIST_CHUNK));
        }
        if (settingChanged) { // render.interpolation changed: the path switches at the frame boundary
            settingChanged = false;
            if (!byHand) {
                switchTo(RenderSettings.interpolation(resources.settings()));
            }
        }
        long since = seen;
        seen = clock.now();
        if (!started || !renderables.changesSince(since, onRenderableChange)) {
            started = true;
            rebuild(); // the first update, or fell behind the renderables' log
        }
        boolean poseAll = rewriteAll; // the first update, or behind the renderables' log
        if (!rewriteAll) {
            // Following the scene's log finds the moved instances and the copies whose joints moved; when it lost
            // changes, everything is written and posed. More moved instances than half: rewriting every one costs
            // less than picking them.
            int logged = scene.changeCountSince(since);
            int changed = logged < 0 ? -1 : logged - captured; // moves captured in steps are drawn by interpolation
            if (changed < 0 || changed > (watchCount + Math.max(instanceCount, itemCount)) / 2) {
                // Lost changes, or most of what it watches moved: posing and writing all costs less than picking
                rewriteAll = true;
                poseAll = true;
            } else {
                scene.changesSince(since, onSceneChange);
                rewriteAll = movedCount > Math.max(instanceCount, itemCount) / 2;
            }
        }
        if (rewriteAll) {
            // Not following the scene's log: nodes removed since (their renderables went with them) go now, before
            // anything reads them.
            for (int item = 0; item < itemNode.length; item++) {
                if (itemNode[item] != NodeHandle.NONE && !scene.contains(itemNode[item])) {
                    destroy(item);
                }
            }
        }
        resources.flush();
        finishSwaps();
        drawWaiting();
        if (stepClock != null) {
            followSteps();
        }
        int count = 0;
        if (rewriteAll) {
            for (int item = 0; item < itemNode.length; item++) {
                if (itemSlot[item] != NONE && !steppedOnly(item)) {
                    fillItems[count++] = item;
                }
            }
            rewriteAll = false;
        } else {
            for (int i = 0; i < movedCount; i++) {
                int item = movedItems[i];
                if (itemSlot[item] != NONE) {
                    fillItems[count++] = item;
                }
            }
        }
        for (int i = 0; i < count; i++) {
            fillEntries[i] = gpu.claim(itemSlot[fillItems[i]]);
        }
        poseCount = 0;
        for (int i = 0; i < skinnedCount; i++) {
            int item = skinned[i];
            if (poseAll || poseDue[item] || itemMesh[item].mesh.data.morphTargetCount() > 0) {
                poseItems[poseCount++] = item;
            }
            poseDue[item] = false;
        }
        posed = poseCount;
        poseListed();
        if (jobs != null && count >= PARALLEL) {
            jobs.parallelFor(count, GRAIN, fill);
        } else {
            fill(0, count, 0);
        }
        for (int i = 0; i < movedCount; i++) {
            moved[movedItems[i]] = false;
        }
        movedCount = 0;
        if (stepClock != null && trackedCount > 0) {
            blend();
        }
        if (stepClock != null && jointCount > 0) {
            blendJoints();
        }
    }

    /** How many renderables are drawn now: those whose meshes are on the GPU. */
    public int instanceCount() {
        return instanceCount;
    }

    /** How many deformed copies the last update posed: those whose joints moved, or with morph targets. */
    public int posedCount() {
        return posed;
    }

    /** How many renderables this scene has, drawn or waiting for their meshes. */
    public int renderableCount() {
        return itemCount;
    }

    /**
     * A count that grows whenever a renderable starts or stops drawing (its instance id taken or freed): what keeps
     * ids of nodes, such as the selection's highlights, follows it.
     */
    public long instanceVersion() {
        return instanceVersion;
    }

    /** How many distinct meshes this scene's renderables use. */
    public int meshCount() {
        return meshes.size();
    }

    /** The instance id {@code node}'s renderable is drawn as (stable while drawn), or -1 while it is not drawn. */
    public int slotOf(long node) {
        int item = NodeHandle.index(node);
        return item < itemNode.length && itemNode[item] == node ? itemSlot[item] : NONE;
    }

    /** The first material id of {@code node}'s renderable, or -1 while it is not drawn. */
    public int firstMaterialOf(long node) {
        int item = NodeHandle.index(node);
        return item < itemNode.length && itemNode[item] == node && itemSet[item] != null ? itemSet[item].first : NONE;
    }

    /** The pool's id of {@code node}'s mesh, or -1 while it is not on the GPU. */
    public int meshOf(long node) {
        int item = NodeHandle.index(node);
        return item < itemNode.length && itemNode[item] == node ? itemMesh[item].mesh.id : NONE;
    }

    /**
     * The pool mesh {@code node}'s renderable draws as: its deformed copy where it has one (posed as drawn), else its
     * mesh; -1 while it is not on the GPU. What particles' mesh shapes emit from.
     */
    public int drawnMeshOf(long node) {
        int item = NodeHandle.index(node);
        if (item >= itemNode.length || itemNode[item] != node || itemMesh[item].mesh.id == NONE) {
            return NONE;
        }
        return itemCopy[item] != NONE ? itemCopy[item] : itemMesh[item].mesh.id;
    }

    /** The data of {@code node}'s renderable's mesh (a copy's at rest), or null while it is not on the GPU. */
    public MeshData meshDataOf(long node) {
        int item = NodeHandle.index(node);
        return item < itemNode.length && itemNode[item] == node && itemMesh[item].mesh.id != NONE
            ? itemMesh[item].mesh.data : null;
    }

    /** {@code node}'s world transform as of the last {@code scene.update()}: rows of a 3 x 4 matrix, into {@code out}. */
    public void worldOf(long node, double[] out) {
        if (!scene.contains(node)) {
            throw new IllegalArgumentException("Not a node of the scene: " + NodeHandle.toString(node));
        }
        world(node, out);
    }

    /**
     * Renderables whose meshes are on the GPU that do not draw yet: assets they wait for
     * ({@link SceneResources.Loading#WHOLE}) have not arrived. They draw as those come.
     */
    public int waitingCount() {
        int count = 0;
        for (int i = 0; i < waitingCount; i++) {
            count += drawable(waiting[i]) ? 1 : 0;
        }
        return count;
    }

    // ---------------------------------------------------------------- following the renderables

    /**
     * Makes every item match the renderables as they are, the first update or once their log no longer holds the
     * changes since the last: items of renderables gone are dropped, the rest reconciled, so what did not change keeps
     * its meshes and materials on the GPU.
     */
    @AllowAllocation("rebuilding, rarely")
    private void rebuild() {
        for (int item = 0; item < itemNode.length; item++) {
            if (itemNode[item] != NodeHandle.NONE && !renderables.has(itemNode[item])) {
                destroy(item);
            }
        }
        for (int i = 0; i < renderables.size(); i++) {
            reconcile(renderables.node(i));
        }
        rewriteAll = true;
    }

    private void destroyAll() {
        for (int item = 0; item < itemNode.length; item++) {
            if (itemNode[item] != NodeHandle.NONE) {
                destroy(item);
            }
        }
        for (int i = 0; i < waitingCount; i++) {
            isWaiting[waiting[i]] = false;
        }
        waitingCount = 0;
    }

    /** Makes {@code node}'s item match its renderable now, whatever the logged change was. */
    @AllowAllocation("renderables changing: entries for new meshes and materials")
    private void reconcile(long node) {
        int item = NodeHandle.index(node);
        if (item >= itemNode.length) {
            grow(Math.max(item + 1, itemNode.length * 2));
        }
        if (itemNode[item] != NodeHandle.NONE && itemNode[item] != node) {
            destroy(item); // a removed node's, whose slot the scene reused
        }
        Renderable renderable = renderables.get(node);
        if (renderable == null) { // gone, or its node is
            if (itemNode[item] == node) {
                destroy(item);
            }
            return;
        }
        AssetRef<MeshData> ref = renderable.mesh();
        List<AssetRef<MaterialData>> list = renderable.materials();
        itemCasts[item] = renderable.castsShadows();
        if (itemNode[item] == node) {
            if (itemMesh[item].mesh.ref.equals(ref)) {
                cancelPendingMesh(item); // changed back before the new one came
                SceneResources.MaterialSet wanted = pendingSet[item] != null ? pendingSet[item] : itemSet[item];
                if (wanted != null && !SceneResources.sameMaterials(wanted.key.materials(), list)) {
                    swapMaterials(item, list); // the mesh and the instance id stay
                } else if (itemSlot[item] != NONE) {
                    refreshCopy(item); // its skin may have changed
                    markMoved(item); // whether it casts shadows, written with the instance
                }
                return;
            }
            if (itemSlot[item] != NONE) {
                changeMesh(item, ref, list); // drawn on as it is until the new mesh and materials are on the GPU
                return;
            }
            destroy(item);
        }
        ViewMesh entry = holdMesh(ref);
        itemNode[item] = node;
        itemMesh[item] = entry;
        itemCount++;
        link(entry, item);
        if (entry.mesh.uploaded()) {
            show(item);
        }
    }

    /** {@code ref}'s entry, held once more: made, and the mesh acquired, the first time (before anything is put in). */
    @AllowAllocation("a mesh used anew")
    private ViewMesh holdMesh(AssetRef<MeshData> ref) {
        ViewMesh entry = meshes.get(ref);
        if (entry == null) {
            entry = new ViewMesh();
            entry.mesh = resources.acquireMesh(ref, entry); // may upload now: no items of it to show yet
            meshes.put(ref, entry);
        }
        entry.holders++;
        return entry;
    }

    /** Lets go of {@code entry} once: at no holders, of its mesh. */
    private void releaseMesh(ViewMesh entry) {
        if (--entry.holders == 0) {
            meshes.remove(entry.mesh.ref);
            resources.releaseMesh(entry.mesh, entry);
        }
    }

    /**
     * Draws {@code item} with {@code materials} instead: the new set is taken before the old one goes, so materials both
     * use stay on the GPU. Drawn now, it is swapped in once what it waits for is on the GPU ({@code WHOLE}): drawn as
     * it was meanwhile.
     */
    private void swapMaterials(int item, List<AssetRef<MaterialData>> materials) {
        if (itemSlot[item] == NONE) { // not drawn yet: the set it waits with
            SceneResources.MaterialSet set = resources.acquireSet(materials, itemMesh[item].mesh.data.materialSlots());
            resources.releaseSet(itemSet[item]);
            itemSet[item] = set;
            return;
        }
        if (pendingSet[item] != null) {
            resources.releaseSet(pendingSet[item]);
            pendingSet[item] = null;
        }
        if (pendingMesh[item] == null) {
            pendingSet[item] = resources.acquireSet(materials, itemMesh[item].mesh.data.materialSlots());
        } // else taken for the new mesh's submeshes once it is on the GPU
        swapWhenReady(item);
    }

    /** Draws {@code item}, drawn now, with mesh {@code ref} once it and its materials are on the GPU; as it was till then. */
    private void changeMesh(int item, AssetRef<MeshData> ref, List<AssetRef<MaterialData>> materials) {
        if (pendingMesh[item] != null && pendingMesh[item].mesh.ref.equals(ref)) {
            if (pendingSet[item] != null && !SceneResources.sameMaterials(pendingSet[item].key.materials(), materials)) {
                resources.releaseSet(pendingSet[item]); // taken again with the new materials
                pendingSet[item] = null;
            }
        } else {
            cancelPendingMesh(item);
            pendingMesh[item] = holdMesh(ref);
        }
        swapWhenReady(item);
    }

    /** Lets go of the mesh {@code item} was to change to, and the set taken for it. */
    private void cancelPendingMesh(int item) {
        if (pendingMesh[item] != null) {
            releaseMesh(pendingMesh[item]);
            pendingMesh[item] = null;
            if (pendingSet[item] != null) {
                resources.releaseSet(pendingSet[item]);
                pendingSet[item] = null;
            }
        }
    }

    /** Swaps {@code item}'s pending mesh or materials in now if it can, else when an update finds them ready. */
    @AllowAllocation("growing the list of swapping items")
    private void swapWhenReady(int item) {
        if (trySwap(item) || isSwapping[item]) {
            return;
        }
        if (swappingCount == swapping.length) {
            swapping = Arrays.copyOf(swapping, swappingCount * 2);
        }
        swapping[swappingCount++] = item;
        isSwapping[item] = true;
    }

    /**
     * Swaps in {@code item}'s pending mesh and materials, written into its instance id, if they are on the GPU; whether
     * nothing is pending any more (a pending mesh that failed is dropped: the old one stays).
     */
    private boolean trySwap(int item) {
        if (pendingMesh[item] == null && pendingSet[item] == null) {
            return true;
        }
        ViewMesh mesh = pendingMesh[item] != null ? pendingMesh[item] : itemMesh[item];
        if (!mesh.mesh.uploaded()) {
            if (mesh.mesh.failed) {
                cancelPendingMesh(item);
                return true;
            }
            return false;
        }
        if (pendingSet[item] == null) {
            pendingSet[item] = resources.acquireSet(renderables.get(itemNode[item]).materials(),
                mesh.mesh.data.materialSlots());
        }
        if (!resources.ready(pendingSet[item])) {
            return false;
        }
        if (pendingMesh[item] != null) {
            ViewMesh old = itemMesh[item];
            unlink(old, item);
            link(mesh, item);
            itemMesh[item] = mesh;
            pendingMesh[item] = null;
            refreshCopy(item); // of the new mesh, before the old one may leave the pool
            releaseMesh(old);
        }
        resources.releaseSet(itemSet[item]);
        itemSet[item] = pendingSet[item];
        pendingSet[item] = null;
        markMoved(item);
        return true;
    }

    /** Swaps in what drawn items waited for, where it came; those still waiting keep waiting. */
    private void finishSwaps() {
        int kept = 0;
        for (int i = 0; i < swappingCount; i++) {
            int item = swapping[i];
            if (itemNode[item] != NodeHandle.NONE && !trySwap(item)) {
                swapping[kept++] = item;
            } else {
                isSwapping[item] = false;
            }
        }
        swappingCount = kept;
    }

    private void sceneChanged(long node, int changes) {
        int item = NodeHandle.index(node);
        if (NodeChange.has(changes, NodeChange.REMOVED)) {
            // Removed with its renderable: its instance goes now, and nothing reads the node again.
            if (item < itemNode.length && itemNode[item] == node) {
                destroy(item);
            }
            return;
        }
        if ((NodeChange.has(changes, NodeChange.TRANSFORM) || NodeChange.has(changes, NodeChange.LAYERS))
            && item < itemNode.length && itemNode[item] == node
            && itemSlot[item] != NONE && !steppedOnly(item)) {
            markMoved(item);
        }
        if (NodeChange.has(changes, NodeChange.TRANSFORM) && item < watchHead.length) {
            watched(node, item);
        }
    }

    /**
     * Marks the copies watching {@code node} (node index {@code index}) to pose; drops entries of copies gone. A copy
     * whose joints move between steps is posed in the steps: moved after the last one (in the frame), it stops.
     */
    private void watched(long node, int index) {
        int previous = NONE;
        int entry = watchHead[index];
        while (entry != NONE) {
            int next = watchNext[entry];
            int item = watchItem[entry];
            if (itemCopy[item] == watchCopy[entry]) {
                if (!jointsTracked[item]) {
                    poseDue[item] = true;
                } else if (scene.version(node) > fixedThrough) {
                    untrackJoints(item); // moved outside the steps: drawn as posed now, from its next step again
                    poseDue[item] = true;
                }
                previous = entry;
            } else { // its copy is gone: unlinked, free for the next
                if (previous == NONE) {
                    watchHead[index] = next;
                } else {
                    watchNext[previous] = next;
                }
                watchNext[entry] = watchFree;
                watchFree = entry;
            }
            entry = next;
        }
    }

    /**
     * Has {@code item}'s copy watch the nodes whose moves change its pose: its own node, and each joint with its
     * ancestors up to the first that is also an ancestor of its node (a common ancestor moves both alike).
     */
    @AllowAllocation("a skinned renderable drawn or changed")
    private void watch(int item, SkinBinding skin, MeshData data) {
        int registration = ++stamps;
        int copy = itemCopy[item];
        long node = itemNode[item];
        watchNode(NodeHandle.index(node), item, copy, registration);
        if (skin == null) {
            return;
        }
        for (int j = 0; j < data.jointCount(); j++) {
            for (long n = skin.joint(data.paletteJoint(j)); n != NodeHandle.NONE && scene.contains(n)
                && !isAncestor(n, node); n = scene.parent(n)) {
                watchNode(NodeHandle.index(n), item, copy, registration);
            }
        }
    }

    /** Whether {@code ancestor} is {@code node} or above it. */
    private boolean isAncestor(long ancestor, long node) {
        for (long n = node; n != NodeHandle.NONE; n = scene.parent(n)) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    @AllowAllocation("growing the watch lists")
    private void watchNode(int index, int item, int copy, int registration) {
        if (index >= watchHead.length) {
            int old = watchHead.length;
            int length = Math.max(index + 1, 2 * old);
            watchHead = Arrays.copyOf(watchHead, length);
            Arrays.fill(watchHead, old, length, NONE);
            stamp = Arrays.copyOf(stamp, length);
        }
        if (stamp[index] == registration) {
            return; // shared by two of its joints: once
        }
        stamp[index] = registration;
        int entry = watchFree;
        if (entry != NONE) {
            watchFree = watchNext[entry];
        } else {
            if (watchCount == watchItem.length) {
                watchItem = Arrays.copyOf(watchItem, 2 * watchCount);
                watchCopy = Arrays.copyOf(watchCopy, 2 * watchCount);
                watchNext = Arrays.copyOf(watchNext, 2 * watchCount);
            }
            entry = watchCount++;
        }
        watchItem[entry] = item;
        watchCopy[entry] = copy;
        watchNext[entry] = watchHead[index];
        watchHead[index] = entry;
    }

    private void destroy(int item) {
        hide(item);
        cancelPendingMesh(item);
        if (pendingSet[item] != null) {
            resources.releaseSet(pendingSet[item]);
            pendingSet[item] = null;
        }
        ViewMesh entry = itemMesh[item];
        unlink(entry, item);
        itemMesh[item] = null;
        itemNode[item] = NodeHandle.NONE;
        itemBounded[item] = false; // the node keeps its bounds; a node made in its place is not ours
        itemCount--;
        releaseMesh(entry);
    }

    private void link(ViewMesh entry, int item) {
        itemPrevious[item] = NONE;
        itemNext[item] = entry.firstItem;
        if (entry.firstItem != NONE) {
            itemPrevious[entry.firstItem] = item;
        }
        entry.firstItem = item;
    }

    private void unlink(ViewMesh entry, int item) {
        if (itemPrevious[item] != NONE) {
            itemNext[itemPrevious[item]] = itemNext[item];
        } else {
            entry.firstItem = itemNext[item];
        }
        if (itemNext[item] != NONE) {
            itemPrevious[itemNext[item]] = itemPrevious[item];
        }
        itemNext[item] = NONE;
        itemPrevious[item] = NONE;
    }

    @AllowAllocation("growing with the scene")
    private void grow(int length) {
        int old = itemNode.length;
        itemNode = Arrays.copyOf(itemNode, length);
        itemMesh = Arrays.copyOf(itemMesh, length);
        itemSet = Arrays.copyOf(itemSet, length);
        itemCasts = Arrays.copyOf(itemCasts, length);
        itemSlot = Arrays.copyOf(itemSlot, length);
        itemNext = Arrays.copyOf(itemNext, length);
        itemBounded = Arrays.copyOf(itemBounded, length);
        itemPrevious = Arrays.copyOf(itemPrevious, length);
        moved = Arrays.copyOf(moved, length);
        isWaiting = Arrays.copyOf(isWaiting, length);
        pendingMesh = Arrays.copyOf(pendingMesh, length);
        pendingSet = Arrays.copyOf(pendingSet, length);
        isSwapping = Arrays.copyOf(isSwapping, length);
        movedItems = Arrays.copyOf(movedItems, length);
        itemCopy = Arrays.copyOf(itemCopy, length);
        itemCopySkinned = Arrays.copyOf(itemCopySkinned, length);
        poseDue = Arrays.copyOf(poseDue, length);
        poseItems = Arrays.copyOf(poseItems, length);
        jointsTracked = Arrays.copyOf(jointsTracked, length);
        jointsAt = Arrays.copyOf(jointsAt, length);
        jointItems = Arrays.copyOf(jointItems, length);
        jointsDue = Arrays.copyOf(jointsDue, length);
        dueItems = Arrays.copyOf(dueItems, length);
        jointsTick = Arrays.copyOf(jointsTick, length);
        snapTick = Arrays.copyOf(snapTick, length);
        lastBox = Arrays.copyOf(lastBox, BOX * length);
        fromBox = Arrays.copyOf(fromBox, BOX * length);
        fromValues = Arrays.copyOf(fromValues, length);
        toValues = Arrays.copyOf(toValues, length);
        drawnPose = Arrays.copyOf(drawnPose, POSE * length);
        fromPose = Arrays.copyOf(fromPose, POSE * length);
        movedTick = Arrays.copyOf(movedTick, length);
        motionSlot = Arrays.copyOf(motionSlot, length);
        Arrays.fill(motionSlot, old, length, NONE);
        isTracked = Arrays.copyOf(isTracked, length);
        trackedAt = Arrays.copyOf(trackedAt, length);
        tracked = Arrays.copyOf(tracked, length);
        still = Arrays.copyOf(still, length);
        teleported = Arrays.copyOf(teleported, length);
        restart = Arrays.copyOf(restart, length);
        Arrays.fill(itemCopy, old, length, NONE);
        fillItems = new int[length];
        fillEntries = new int[length];
        Arrays.fill(itemSlot, old, length, NONE);
        Arrays.fill(itemNext, old, length, NONE);
        Arrays.fill(itemPrevious, old, length, NONE);
    }

    // ---------------------------------------------------------------- instances

    /** Draws {@code item}, or has it wait for room: in the instance table, or in the material table. */
    private void show(int item) {
        if (!tryShow(item)) {
            queue(item);
        }
    }

    /** Draws {@code item}, a live renderable's whose mesh is on the GPU: the lowest free instance id, its materials. */
    private boolean tryShow(int item) {
        if (!holdMaterials(item)) {
            return false; // its renderable is gone
        }
        if (!resources.ready(itemSet[item])) {
            return false; // assets it waits for are not on the GPU yet
        }
        int slot = gpu.freeId(); // the table grows if it is full
        instanceCount++;
        instanceVersion++;
        itemSlot[item] = slot;
        refreshCopy(item);
        write(gpu.claim(slot), item); // takes the id
        bound(item);
        return true;
    }

    /**
     * Holds {@code item}'s block of materials, from when its mesh is on the GPU, while it waits too: its materials and
     * textures load. Whether it holds one: not once its renderable is gone.
     */
    private boolean holdMaterials(int item) {
        Renderable renderable = itemSet[item] == null ? renderables.get(itemNode[item]) : null;
        if (renderable != null) {
            itemSet[item] = resources.acquireSet(renderable.materials(),
                itemMesh[item].mesh.data.materialSlots());
        }
        return itemSet[item] != null;
    }

    /** Stops drawing {@code item}: its id becomes a hole, free for the next renderable drawn. */
    private void hide(int item) {
        dropCopy(item);
        if (isTracked[item]) {
            untrack(item);
        }
        int slot = itemSlot[item];
        if (slot != NONE) {
            gpu.remove(slot);
            instanceCount--;
            instanceVersion++;
            itemSlot[item] = NONE;
        }
        if (itemSet[item] != null) { // drawn, or waiting for its assets
            resources.releaseSet(itemSet[item]);
            itemSet[item] = null;
        }
    }

    /** Has {@code item} drawn at the end of the update, once. */
    @AllowAllocation("growing the list of waiting items")
    private void queue(int item) {
        if (isWaiting[item]) {
            return;
        }
        if (waitingCount == waiting.length) {
            waiting = Arrays.copyOf(waiting, waitingCount * 2);
        }
        waiting[waitingCount++] = item;
        isWaiting[item] = true;
    }

    /** Whether {@code item} is a live renderable's, not drawn, with its mesh on the GPU. */
    private boolean drawable(int item) {
        return itemNode[item] != NodeHandle.NONE && itemSlot[item] == NONE && itemMesh[item].mesh.uploaded();
    }

    /**
     * Draws the waiting items, oldest first, as far as there is room; those gone or drawn already leave the list, the
     * rest keep waiting in order.
     */
    private void drawWaiting() {
        int kept = 0;
        for (int i = 0; i < waitingCount; i++) {
            int item = waiting[i];
            if (drawable(item) && !tryShow(item)) {
                waiting[kept++] = item;
            } else {
                isWaiting[item] = false;
            }
        }
        waitingCount = kept;
    }

    /**
     * Gives drawn {@code item} the deformed copy its skin asks for, or none: a new one when its mesh or skin changed.
     * The instance is rewritten with it.
     */
    @AllowAllocation("a skinned renderable drawn or changed")
    private void refreshCopy(int item) {
        Renderable renderable = renderables.get(itemNode[item]);
        SkinBinding skin = renderable != null ? renderable.skin() : null;
        MeshData data = itemMesh[item].mesh.data;
        boolean skinning = skin != null && data.jointCount() > 0;
        boolean wanted = skinning || data.morphTargetCount() > 0;
        int source = itemMesh[item].mesh.id;
        var pool = resources.pool();
        if (itemCopy[item] != NONE && wanted && pool.deformedFrom(itemCopy[item]) == source
            && itemCopySkinned[item] == skinning) {
            return; // the same mesh: its joints and weights are read each update
        }
        dropCopy(item);
        if (!wanted) {
            return;
        }
        int copy = resources.addCopy(source, skinning ? data.jointCount() : 0, data);
        itemCopy[item] = copy;
        itemCopySkinned[item] = skinning;
        watch(item, skinning ? skin : null, data);
        if (skinnedCount == skinned.length) {
            skinned = Arrays.copyOf(skinned, 2 * skinnedCount);
        }
        skinned[skinnedCount++] = item;
        pose(item);
        markMoved(item);
    }

    private void dropCopy(int item) {
        if (itemCopy[item] == NONE) {
            return;
        }
        if (jointsTracked[item]) {
            untrackJoints(item);
        }
        resources.removeCopy(itemCopy[item]); // after its instance stops naming it: written this update
        itemCopy[item] = NONE;
        for (int i = 0; i < skinnedCount; i++) {
            if (skinned[i] == item) {
                skinned[i] = skinned[--skinnedCount];
                break;
            }
        }
        if (itemSlot[item] != NONE) {
            markMoved(item);
        }
    }

    /**
     * Poses {@code item}'s copy: its morph weights, and each palette joint's matrix, the joint's world transform
     * relative to the node's times its inverse bind matrix; the box of the joints' rest boxes, grown by the morphs'
     * largest moves, so moved (or the rest box so grown), and how far any point moves.
     */
    private void pose(int item) {
        pose(item, scratches[0]);
    }

    /** Poses the copies listed ({@link #poseItems}): on the job system's threads when many, each copy alone. */
    private void poseListed() {
        if (jobs != null && poseCount >= POSE_PARALLEL) {
            jobs.parallelFor(poseCount, POSE_GRAIN, poseMany);
        } else {
            poseMany(0, poseCount, 0);
        }
    }

    /** Poses {@code poseItems[from]} to {@code poseItems[to - 1]}, on any thread: copies write their own records. */
    private void poseMany(int from, int to, int worker) {
        Scratch scratch = scratches[worker];
        for (int i = from; i < to; i++) {
            pose(poseItems[i], scratch);
        }
    }

    /** {@link #pose(int)} in {@code scratch}, on any thread for different items. */
    private void pose(int item, Scratch scratch) {
        double[] box = scratch.box;
        long node = itemNode[item];
        MeshData data = itemMesh[item].mesh.data;
        int copy = itemCopy[item];
        var deformation = resources.pool().deformation();
        double morph = 0; // the farthest the morphs move a vertex
        MorphWeights weights = data.morphTargetCount() > 0 ? morphs.get(node) : null;
        for (int t = 0; t < data.morphTargetCount(); t++) {
            float weight = weights != null ? weights.weight(t) : 0;
            deformation.weight(copy, t, weight);
            morph += Math.abs(weight) * data.morphMaxDisplacement(t);
        }
        box[0] = box[1] = box[2] = Double.POSITIVE_INFINITY;
        box[3] = box[4] = box[5] = Double.NEGATIVE_INFINITY;
        double growth = 0;
        if (itemCopySkinned[item]) {
            growth = skin(node, data, copy, morph, scratch);
        }
        if (box[0] > box[3]) { // no joint moves a vertex: the rest box
            for (int axis = 0; axis < 3; axis++) {
                box[axis] = data.boxMin(axis);
                box[3 + axis] = data.boxMin(axis) + data.boxExtent(axis);
            }
        }
        int b = BOX * item; // the pose's box; between steps, grown to hold the pose the step's move started from too
        lastBox[b] = box[0] - morph;
        lastBox[b + 1] = box[1] - morph;
        lastBox[b + 2] = box[2] - morph;
        lastBox[b + 3] = box[3] + morph;
        lastBox[b + 4] = box[4] + morph;
        lastBox[b + 5] = box[5] + morph;
        lastBox[b + 6] = growth + morph;
        double[] from = jointsTracked[item] ? fromBox : lastBox;
        deformation.bounds(copy, Math.min(lastBox[b], from[b]), Math.min(lastBox[b + 1], from[b + 1]),
            Math.min(lastBox[b + 2], from[b + 2]), Math.max(lastBox[b + 3], from[b + 3]),
            Math.max(lastBox[b + 4], from[b + 4]), Math.max(lastBox[b + 5], from[b + 5]),
            Math.max(lastBox[b + 6], from[b + 6]));
    }

    /**
     * Sets each palette joint's matrix and fills the scratch's box with the joints' rest boxes, grown by
     * {@code morph}, so moved (to be grown by it again: a morph's move is moved too); returns how far a point of them
     * moves.
     */
    private double skin(long node, MeshData data, int copy, double morph, Scratch scratch) {
        double[] relative = scratch.relative;
        double[] jointWorld = scratch.jointWorld;
        double[] matrix = scratch.matrix;
        double[] box = scratch.box;
        SkinBinding skin = renderables.get(node).skin();
        world(node, matrix);
        invert(matrix, relative);
        double growth = 0;
        double[] m = matrix;
        double[] corner = jointWorld; // reused below, after the joint's matrix is made
        for (int j = 0; j < data.jointCount(); j++) {
            int joint = data.paletteJoint(j);
            world(skin.joint(joint), jointWorld);
            multiply(relative, jointWorld, m);
            // times the inverse bind matrix (column-major 4x4, affine)
            double a00 = m[0];
            double a01 = m[1];
            double a02 = m[2];
            double a10 = m[4];
            double a11 = m[5];
            double a12 = m[6];
            double a20 = m[8];
            double a21 = m[9];
            double a22 = m[10];
            double[] r = m; // written in place: its translation first, then the rest from the saved values
            double b00 = skin.inverseBind(joint, 0);
            double b10 = skin.inverseBind(joint, 1);
            double b20 = skin.inverseBind(joint, 2);
            double b01 = skin.inverseBind(joint, 4);
            double b11 = skin.inverseBind(joint, 5);
            double b21 = skin.inverseBind(joint, 6);
            double b02 = skin.inverseBind(joint, 8);
            double b12 = skin.inverseBind(joint, 9);
            double b22 = skin.inverseBind(joint, 10);
            double b03 = skin.inverseBind(joint, 12);
            double b13 = skin.inverseBind(joint, 13);
            double b23 = skin.inverseBind(joint, 14);
            r[3] = a00 * b03 + a01 * b13 + a02 * b23 + m[3];
            r[7] = a10 * b03 + a11 * b13 + a12 * b23 + m[7];
            r[11] = a20 * b03 + a21 * b13 + a22 * b23 + m[11];
            r[0] = a00 * b00 + a01 * b10 + a02 * b20;
            r[1] = a00 * b01 + a01 * b11 + a02 * b21;
            r[2] = a00 * b02 + a01 * b12 + a02 * b22;
            r[4] = a10 * b00 + a11 * b10 + a12 * b20;
            r[5] = a10 * b01 + a11 * b11 + a12 * b21;
            r[6] = a10 * b02 + a11 * b12 + a12 * b22;
            r[8] = a20 * b00 + a21 * b10 + a22 * b20;
            r[9] = a20 * b01 + a21 * b11 + a22 * b21;
            r[10] = a20 * b02 + a21 * b12 + a22 * b22;
            resources.pool().deformation().joint(copy, j, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9],
                r[10], r[11]);
            data.jointBox(j, corner);
            if (corner[0] > corner[3]) {
                continue; // a joint no vertex follows
            }
            for (int c = 0; c < 8; c++) {
                double x = (c & 1) == 0 ? corner[0] - morph : corner[3] + morph;
                double y = (c & 2) == 0 ? corner[1] - morph : corner[4] + morph;
                double z = (c & 4) == 0 ? corner[2] - morph : corner[5] + morph;
                double px = r[0] * x + r[1] * y + r[2] * z + r[3];
                double py = r[4] * x + r[5] * y + r[6] * z + r[7];
                double pz = r[8] * x + r[9] * y + r[10] * z + r[11];
                box[0] = Math.min(box[0], px);
                box[1] = Math.min(box[1], py);
                box[2] = Math.min(box[2], pz);
                box[3] = Math.max(box[3], px);
                box[4] = Math.max(box[4], py);
                box[5] = Math.max(box[5], pz);
                growth = Math.max(growth, Maths.sqrt((px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z)));
            }
        }
        return growth;
    }

    /** {@code node}'s world transform as rows of a 3x4 matrix. */
    private void world(long node, double[] out) {
        TransformArray world = scene.worldTransforms();
        MemorySegment s = world.segment();
        long o = NodeHandle.index(node) * world.stride();
        double x = s.get(DOUBLE, o + 24);
        double y = s.get(DOUBLE, o + 32);
        double z = s.get(DOUBLE, o + 40);
        double w = s.get(DOUBLE, o + 48);
        double sx = s.get(DOUBLE, o + 56);
        double sy = s.get(DOUBLE, o + 64);
        double sz = s.get(DOUBLE, o + 72);
        out[0] = (1 - 2 * (y * y + z * z)) * sx;
        out[1] = 2 * (x * y - z * w) * sy;
        out[2] = 2 * (x * z + y * w) * sz;
        out[3] = s.get(DOUBLE, o);
        out[4] = 2 * (x * y + z * w) * sx;
        out[5] = (1 - 2 * (x * x + z * z)) * sy;
        out[6] = 2 * (y * z - x * w) * sz;
        out[7] = s.get(DOUBLE, o + 8);
        out[8] = 2 * (x * z - y * w) * sx;
        out[9] = 2 * (y * z + x * w) * sy;
        out[10] = (1 - 2 * (x * x + y * y)) * sz;
        out[11] = s.get(DOUBLE, o + 16);
    }

    /** The inverse of affine {@code m} (rows of 3x4) into {@code out}. */
    static void invert(double[] m, double[] out) {
        double c00 = m[5] * m[10] - m[6] * m[9];
        double c01 = m[2] * m[9] - m[1] * m[10];
        double c02 = m[1] * m[6] - m[2] * m[5];
        double c10 = m[6] * m[8] - m[4] * m[10];
        double c11 = m[0] * m[10] - m[2] * m[8];
        double c12 = m[2] * m[4] - m[0] * m[6];
        double c20 = m[4] * m[9] - m[5] * m[8];
        double c21 = m[1] * m[8] - m[0] * m[9];
        double c22 = m[0] * m[5] - m[1] * m[4];
        double inverse = 1 / (m[0] * c00 + m[1] * c10 + m[2] * c20);
        out[0] = c00 * inverse;
        out[1] = c01 * inverse;
        out[2] = c02 * inverse;
        out[4] = c10 * inverse;
        out[5] = c11 * inverse;
        out[6] = c12 * inverse;
        out[8] = c20 * inverse;
        out[9] = c21 * inverse;
        out[10] = c22 * inverse;
        out[3] = -(out[0] * m[3] + out[1] * m[7] + out[2] * m[11]);
        out[7] = -(out[4] * m[3] + out[5] * m[7] + out[6] * m[11]);
        out[11] = -(out[8] * m[3] + out[9] * m[7] + out[10] * m[11]);
    }

    /** Affine {@code a} times {@code b} (rows of 3x4) into {@code out}, which may be neither. */
    static void multiply(double[] a, double[] b, double[] out) {
        for (int row = 0; row < 3; row++) {
            int r = 4 * row;
            double x = a[r];
            double y = a[r + 1];
            double z = a[r + 2];
            out[r] = x * b[0] + y * b[4] + z * b[8];
            out[r + 1] = x * b[1] + y * b[5] + z * b[9];
            out[r + 2] = x * b[2] + y * b[6] + z * b[10];
            out[r + 3] = x * b[3] + y * b[7] + z * b[11] + a[r + 3];
        }
    }

    // ---------------------------------------------------------------- interpolation between fixed steps (D91)

    /**
     * Follows the steps of {@code world}, the scene's world clock: after each step the scene's world transforms are
     * updated (D123) and the renderables it moved are captured; each frame draws them {@link Clock#blend()} of the way
     * from their pose at the step before to the last step's, as {@link #interpolation} says (GPU by default). Nodes
     * moved outside the steps are drawn where they are, and stop interpolating. Skinned renderables whose joints a step
     * moves (an animator on fixed steps, animation.md A5; physics) are posed after the step and drawn between their
     * last two steps' poses alike: their joint matrices blended linearly (each vertex drawn that part of the way
     * between where the two poses put it), on the GPU by the skinning or on the CPU here, as {@link #interpolation}
     * says; their bounds hold both poses. Once, before frames run.
     */
    @AllowAllocation("set up once")
    public SceneSync follow(Clock world) {
        if (stepClock != null) {
            throw new IllegalStateException("The scene sync follows a clock already");
        }
        stepClock = Objects.requireNonNull(world, "world");
        world.afterStep(afterStep);
        return this;
    }

    /** The clock followed ({@link #follow}), or null. */
    public Clock clock() {
        return stepClock;
    }

    /**
     * Draws instances moved in steps between steps as {@code mode} says: on the GPU, on the CPU, or not at all. Those
     * moving now are drawn where they are and start again from their next step. Set by hand, the resources' setting
     * ({@code RenderSettings.INTERPOLATION}, which the sync otherwise follows) no longer applies to this sync.
     */
    public SceneSync interpolation(Interpolation mode) {
        byHand = true;
        switchTo(Objects.requireNonNull(mode, "mode"));
        return this;
    }

    private void switchTo(Interpolation mode) {
        if (mode != interpolation) {
            stopAll();
            interpolation = mode;
            layout();
        }
    }

    /** The GPU's motion records kept as the mode says: packed, or precise. With none in use. */
    private void layout() {
        if (interpolation.onGpu()) {
            gpu.motion().layout(interpolation == Interpolation.GPU_PRECISE ? InstanceMotion.Layout.PRECISE
                : InstanceMotion.Layout.PACKED);
        }
    }

    public Interpolation interpolation() {
        return interpolation;
    }

    /**
     * Whether {@code node}'s renderable is drawn between steps when steps move it (true unless set): false draws it as
     * each step leaves it. Allocates nothing once the arrays have the node's room.
     */
    public void interpolated(long node, boolean on) {
        int item = room(node);
        still[item] = !on;
        if (!on && isTracked[item]) {
            untrack(item);
            markMoved(item); // drawn where it is from the next update
        }
        if (!on && jointsTracked[item]) {
            untrackJoints(item); // its joints too: posed as each step leaves them
            poseDue[item] = true;
        }
    }

    /**
     * {@code node} jumps (a teleport, a respawn): its next step's move is drawn from where it lands, not blended from
     * where it was; a skinned renderable's joints moved in the step (its animator reset, a new pose) snap to the step's
     * pose alike. Call in the step that moves it. Allocates nothing once the arrays have the node's room.
     */
    public void teleport(long node) {
        int item = room(node);
        teleported[item] = true;
        if (stepClock != null) {
            snapTick[item] = stepClock.tick();
        }
    }

    /** How many renderables move between steps now: those the last step moved. */
    public int interpolatedCount() {
        return trackedCount;
    }

    /** How many skinned renderables' joints move between steps now (A5): those whose joints the last step moved. */
    public int interpolatedSkinCount() {
        return jointCount;
    }

    private int room(long node) {
        int item = NodeHandle.index(node);
        if (item >= itemNode.length) {
            grow(Math.max(item + 1, 2 * itemNode.length));
        }
        return item;
    }

    /** After each step of the clock followed: the transforms, then the renderables the step moved. */
    private void stepped() {
        long mark = clock.now();
        scene.update();
        scene.changesSince(mark, onStepChange);
        fixedThrough = clock.now();
        steppedSince = true;
        if (dueCount > 0) {
            captureJoints();
        }
    }

    /**
     * A change of the step just run: a drawn renderable moved is captured, its pose before kept; skinned renderables
     * whose joints it moved are posed after the step ({@link #captureJoints}).
     */
    private void stepChanged(long node, int changes) {
        int index = NodeHandle.index(node);
        boolean joint = NodeChange.has(changes, NodeChange.TRANSFORM) && interpolation != Interpolation.OFF
            && index < watchHead.length && stepWatched(index);
        if (!captureMove(node, changes) && joint) {
            captured++; // drawn by interpolation, not by the frame's sync
        }
    }

    /** A change of the step just run to a drawn renderable's node: captured if it moved; whether it was. */
    private boolean captureMove(long node, int changes) {
        int item = NodeHandle.index(node);
        if (item >= itemNode.length || itemNode[item] != node || itemSlot[item] == NONE) {
            return false;
        }
        if (NodeChange.has(changes, NodeChange.LAYERS)) {
            markMoved(item); // its layers written; its pose, if interpolated, blended over it
        }
        if (!NodeChange.has(changes, NodeChange.TRANSFORM) || interpolation == Interpolation.OFF || still[item]) {
            return false;
        }
        captured++;
        System.arraycopy(drawnPose, POSE * item, fromPose, POSE * item, POSE); // where the step before left it
        if (!isTracked[item]) {
            isTracked[item] = true;
            trackedAt[item] = trackedCount;
            tracked[trackedCount++] = item;
        }
        readPose(item);
        if (teleported[item]) {
            System.arraycopy(drawnPose, POSE * item, fromPose, POSE * item, POSE);
            teleported[item] = false;
            restart[item] = true;
        }
        movedTick[item] = stepClock.tick();
        return true;
    }

    /**
     * Marks the drawn skinned copies watching node index {@code index}, moved by the step just run, to pose after it;
     * whether any watch it.
     */
    private boolean stepWatched(int index) {
        boolean any = false;
        for (int entry = watchHead[index]; entry != NONE; entry = watchNext[entry]) {
            int item = watchItem[entry];
            if (itemCopy[item] != watchCopy[entry] || !itemCopySkinned[item] || itemSlot[item] == NONE || still[item]) {
                continue;
            }
            any = true;
            if (!jointsDue[item]) {
                jointsDue[item] = true;
                dueItems[dueCount++] = item;
            }
        }
        return any;
    }

    /**
     * After a step: poses the skinned copies whose joints it moved, each step's pose kept beside the one before (on the
     * GPU, by the deformation; on the CPU, here), their bounds holding both; a teleport in the step snaps to its pose.
     */
    private void captureJoints() {
        long tick = stepClock.tick();
        var deformation = resources.pool().deformation();
        boolean onGpu = interpolation.onGpu();
        poseCount = 0;
        for (int i = 0; i < dueCount; i++) {
            int item = dueItems[i];
            jointsDue[item] = false;
            int copy = itemCopy[item];
            if (copy == NONE || itemSlot[item] == NONE || !itemCopySkinned[item]) {
                continue;
            }
            int b = BOX * item;
            System.arraycopy(lastBox, b, fromBox, b, BOX); // the pose drawn till now: where the step's move starts
            if (!jointsTracked[item]) {
                trackJoints(item, copy, onGpu);
            } else if (onGpu) {
                deformation.step(copy);
            } else {
                float[] swap = fromValues[item];
                fromValues[item] = toValues[item];
                toValues[item] = swap;
            }
            poseItems[poseCount++] = item;
        }
        poseListed(); // the step's poses, their bounds holding the ones before too
        for (int i = 0; i < poseCount; i++) {
            int item = poseItems[i];
            int copy = itemCopy[item];
            int b = BOX * item;
            if (!onGpu) {
                deformation.read(copy, toValues[item]);
            }
            if (snapTick[item] == tick) { // teleported in the step: drawn at its pose, not blended towards it
                if (onGpu) {
                    deformation.snap(copy);
                } else {
                    System.arraycopy(toValues[item], 0, fromValues[item], 0, toValues[item].length);
                }
                System.arraycopy(lastBox, b, fromBox, b, BOX);
                deformation.bounds(copy, lastBox[b], lastBox[b + 1], lastBox[b + 2], lastBox[b + 3], lastBox[b + 4],
                    lastBox[b + 5], lastBox[b + 6]);
            }
            jointsTick[item] = tick;
        }
        dueCount = 0;
    }

    /** Draws {@code item}'s copy between steps from now on, starting from its pose drawn till now. */
    @AllowAllocation("a skinned renderable's joints first moved in steps: room for its two poses")
    private void trackJoints(int item, int copy, boolean onGpu) {
        var deformation = resources.pool().deformation();
        if (onGpu) {
            deformation.blended(copy, true);
        } else {
            int n = deformation.valueCount(copy);
            if (fromValues[item] == null || fromValues[item].length != n) {
                fromValues[item] = new float[n];
                toValues[item] = new float[n];
            }
            deformation.read(copy, fromValues[item]);
        }
        jointsTracked[item] = true;
        jointsAt[item] = jointCount;
        jointItems[jointCount++] = item;
    }

    /** Stops drawing {@code item}'s copy between steps: drawn as posed (its current pose, until posed again). */
    private void untrackJoints(int item) {
        int copy = itemCopy[item];
        var deformation = resources.pool().deformation();
        if (copy != NONE && deformation.blended(copy)) {
            deformation.blended(copy, false);
        }
        int at = jointsAt[item];
        int last = jointItems[--jointCount];
        jointItems[at] = last;
        jointsAt[last] = at;
        jointsTracked[item] = false;
    }

    /** Each frame: each copy between steps blended by the clock's factor, on the GPU or here. */
    private void blendJoints() {
        double a = stepClock.blend();
        if (interpolation.onGpu()) {
            var deformation = resources.pool().deformation();
            for (int i = 0; i < jointCount; i++) {
                deformation.blend(itemCopy[jointItems[i]], a);
            }
            return;
        }
        jointFactor = a;
        if (jobs != null && jointCount >= 4 * JOINT_GRAIN) {
            jobs.parallelFor(jointCount, JOINT_GRAIN, writeJoints);
        } else {
            writeJoints(0, jointCount, 0);
        }
    }

    /** Writes the blended poses of {@code jointItems[from]} to {@code jointItems[to - 1]}, on any thread. */
    private void writeJoints(int from, int to, int worker) {
        var deformation = resources.pool().deformation();
        double a = jointFactor;
        for (int i = from; i < to; i++) {
            int item = jointItems[i];
            deformation.blendInto(itemCopy[item], fromValues[item], toValues[item], a);
        }
    }

    /**
     * Whether {@code item}'s last change came from a step and it is interpolated: written by the blend, not as moved.
     * One interpolated but changed since (moved in the frame's update) stops interpolating.
     */
    private boolean steppedOnly(int item) {
        if (!isTracked[item]) {
            return false;
        }
        if (scene.version(itemNode[item]) <= fixedThrough) {
            return true;
        }
        untrack(item);
        return false;
    }

    /**
     * In a frame after steps: the renderables the last step moved get their two poses (on the GPU, one record each);
     * those it did not move stop, drawn where they are.
     */
    private void followSteps() {
        if (!steppedSince) {
            return;
        }
        steppedSince = false;
        captured = 0;
        long tick = stepClock.tick();
        int j = 0;
        while (j < jointCount) { // skinned copies whose joints the last step did not move stop, drawn as posed
            int item = jointItems[j];
            if (jointsTick[item] != tick) {
                untrackJoints(item); // the last one moves into place j
                poseDue[item] = true;
            } else {
                j++;
            }
        }
        InstanceMotion motion = gpu.motion();
        if (interpolation.onGpu()) {
            motion.beginStep(tick);
        }
        int count = 0; // records to write, in the fill lists (free until the fill)
        int i = 0;
        while (i < trackedCount) {
            int item = tracked[i];
            if (itemSlot[item] == NONE) {
                untrack(item); // the last one moves into place i
            } else if (movedTick[item] != tick) {
                untrack(item);
                markMoved(item);
            } else {
                if (interpolation.onGpu()) {
                    if (motionSlot[item] == NONE) {
                        if (motion.count() == 0) { // packed positions are kept from here: the first mover's
                            int f = POSE * item;
                            motion.origin(fromPose[f], fromPose[f + 1], fromPose[f + 2]);
                        }
                        motionSlot[item] = motion.add();
                    }
                    fillItems[count++] = item;
                }
                i++;
            }
        }
        // Claimed in order on this thread, written on the job system's threads when many, as instances are
        for (int r = 0; r < count; r++) {
            int item = fillItems[r];
            fillEntries[r] = motion.claim(motionSlot[item], restart[item]);
            restart[item] = false;
        }
        if (jobs != null && count >= PARALLEL) {
            jobs.parallelFor(count, GRAIN, writeMotions);
        } else {
            writeMotions(0, count, 0);
        }
    }

    /** Writes the motion records of {@code fillItems[from]} to {@code fillItems[to - 1]}, on any thread. */
    private void writeMotions(int from, int to, int worker) {
        InstanceMotion motion = gpu.motion();
        double[] a = fromPose;
        double[] b = drawnPose;
        for (int r = from; r < to; r++) {
            int item = fillItems[r];
            int f = POSE * item;
            motion.write(fillEntries[r], itemSlot[item], a[f], a[f + 1], a[f + 2], (float) a[f + 3], (float) a[f + 4],
                (float) a[f + 5], (float) a[f + 6], (float) a[f + 7], (float) a[f + 8], (float) a[f + 9], b[f],
                b[f + 1], b[f + 2], (float) b[f + 3], (float) b[f + 4], (float) b[f + 5], (float) b[f + 6],
                (float) b[f + 7], (float) b[f + 8], (float) b[f + 9]);
        }
    }

    /** Each frame: the GPU's blend factor, or every moving renderable's blended pose written. */
    private void blend() {
        double a = stepClock.blend();
        if (interpolation.onGpu()) {
            gpu.motion().blend(a);
            return;
        }
        blendFactor = a;
        // Claimed in order on this thread, written on the job system's threads when many, as instances are
        for (int i = 0; i < trackedCount; i++) {
            fillEntries[i] = gpu.claim(itemSlot[tracked[i]]);
        }
        if (jobs != null && trackedCount >= PARALLEL) {
            jobs.parallelFor(trackedCount, GRAIN, writeBlended);
        } else {
            writeBlended(0, trackedCount, 0);
        }
    }

    /** Writes the blended poses of {@code tracked[from]} to {@code tracked[to - 1]}, on any thread. */
    private void writeBlended(int from, int to, int worker) {
        double a = blendFactor;
        double b = 1 - a;
        double[] p = fromPose;
        double[] q = drawnPose;
        for (int i = from; i < to; i++) {
            int item = tracked[i];
            int f = POSE * item;
            double qx = q[f + 3];
            double qy = q[f + 4];
            double qz = q[f + 5];
            double qw = q[f + 6];
            double sign = p[f + 3] * qx + p[f + 4] * qy + p[f + 5] * qz + p[f + 6] * qw < 0 ? -a : a;
            double rx = b * p[f + 3] + sign * qx;
            double ry = b * p[f + 4] + sign * qy;
            double rz = b * p[f + 5] + sign * qz;
            double rw = b * p[f + 6] + sign * qw;
            double length = Math.sqrt(rx * rx + ry * ry + rz * rz + rw * rw);
            long node = itemNode[item];
            gpu.write(fillEntries[i], itemCopy[item] != NONE ? itemCopy[item] : itemMesh[item].mesh.id,
                itemSet[item].first, scene.layers(node), itemCasts[item],
                b * p[f] + a * q[f], b * p[f + 1] + a * q[f + 1], b * p[f + 2] + a * q[f + 2],
                (float) (rx / length), (float) (ry / length), (float) (rz / length), (float) (rw / length),
                (float) (b * p[f + 7] + a * q[f + 7]), (float) (b * p[f + 8] + a * q[f + 8]),
                (float) (b * p[f + 9] + a * q[f + 9]));
        }
    }

    private void untrack(int item) {
        if (motionSlot[item] != NONE) {
            gpu.motion().remove(motionSlot[item]);
            motionSlot[item] = NONE;
        }
        int at = trackedAt[item];
        int last = tracked[--trackedCount];
        tracked[at] = last;
        trackedAt[last] = at;
        isTracked[item] = false;
    }

    /** Every renderable moving between steps drawn where it is. */
    private void stopAll() {
        while (jointCount > 0) {
            int item = jointItems[jointCount - 1];
            untrackJoints(item);
            poseDue[item] = true;
        }
        while (trackedCount > 0) {
            int item = tracked[trackedCount - 1];
            untrack(item);
            if (itemSlot[item] != NONE) {
                markMoved(item);
            }
        }
    }

    /** {@code item}'s node's world pose into {@code drawnPose}: position, rotation, scale. */
    private void readPose(int item) {
        TransformArray world = scene.worldTransforms();
        MemorySegment s = world.segment();
        long o = NodeHandle.index(itemNode[item]) * world.stride();
        int f = POSE * item;
        for (int c = 0; c < POSE; c++) {
            drawnPose[f + c] = s.get(DOUBLE, o + 8L * c);
        }
    }

    private void markMoved(int item) {
        if (!moved[item]) {
            moved[item] = true;
            movedItems[movedCount++] = item;
        }
    }

    /** Writes the instances of {@code fillItems[from]} to {@code fillItems[to - 1]}, on any thread. */
    private void fill(int from, int to, int worker) {
        for (int i = from; i < to; i++) {
            write(fillEntries[i], fillItems[i]);
        }
    }

    /**
     * Writes {@code item}'s instance into entry {@code entry} of the GPU scene's changes: its node's world transform
     * and layers, its mesh, materials and shadows. Reads only, so items write on several threads at once.
     */
    private void write(int entry, int item) {
        TransformArray world = scene.worldTransforms();
        MemorySegment s = world.segment();
        long o = NodeHandle.index(itemNode[item]) * world.stride();
        long node = itemNode[item];
        gpu.write(entry, itemCopy[item] != NONE ? itemCopy[item] : itemMesh[item].mesh.id, itemSet[item].first, scene.layers(node),
            itemCasts[item], s.get(DOUBLE, o), s.get(DOUBLE, o + 8), s.get(DOUBLE, o + 16),
            (float) s.get(DOUBLE, o + 24), (float) s.get(DOUBLE, o + 32), (float) s.get(DOUBLE, o + 40),
            (float) s.get(DOUBLE, o + 48),
            (float) s.get(DOUBLE, o + 56), (float) s.get(DOUBLE, o + 64), (float) s.get(DOUBLE, o + 72));
        int f = POSE * item; // the pose drawn, where its next step's move starts
        for (int c = 0; c < POSE; c++) {
            drawnPose[f + c] = s.get(DOUBLE, o + 8L * c);
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The scene sync is closed");
        }
    }

    /**
     * Stops following the scene: its instances leave the GPU scene, and what only this scene used leaves the GPU and is
     * released in the asset cache. The resources and the GPU scene stay open.
     */
    @Override
    public void close() {
        if (!closed) {
            destroyAll();
            if (settingWatch != null) {
                settingWatch.close();
                listWatch.close();
                chunkWatch.close();
            }
            closed = true;
        }
    }
}
