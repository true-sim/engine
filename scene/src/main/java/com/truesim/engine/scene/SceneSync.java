package com.truesim.engine.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.JobSystem;
import com.truesim.common.math.TransformArray;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.GpuScene;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.NodeChange;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.Renderables;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A scene kept on the GPU: its {@link Renderables} as instances of a {@link GpuScene}, at their world positions, from
 * shared {@link SceneResources}, following the scene's and the renderables' change logs. What draws the instances is
 * the renderers' business: any number of {@link com.truesim.engine.render.RenderView}s (cameras, shadow maps,
 * mirrors), each at its own world position, all culled in one pass; moving a camera rewrites no instance.
 *
 * <pre>{@code
 * GpuScene gpu = new GpuScene(device, resources.pool(), 100_000, slots);
 * SceneSync sync = new SceneSync(resources, renderables, gpu);
 * MeshletRenderer renderer = new MeshletRenderer(device, gpu, colorFormat, depthFormat, slots);
 * SceneGeometry geometry = SceneGeometry.meshlets(renderer);
 * ViewPort port = graph.addView("main").size(width, height);
 * SceneView view = geometry.createView(port, camera, ViewOptions.of(1 << 18, 1 << 22).withOcclusion(true));
 * port.add(new ClusteredForwardShading(geometry, view, colorFormat, depthFormat));
 *
 * // every frame, after the frame boundary (tasks.drainCompleted()), scene.update() and renderables.update():
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
 *       change logs. Only moved instances are written, or every one when most nodes moved.</li>
 *   <li><b>Stable ids:</b> a renderable keeps its instance id while drawn; a removed one leaves a hole the next takes
 *       (the lowest free id), so what the GPU keeps per instance stays with it. Deterministic: the same changes give the
 *       same ids. A new renderable may take an id freed the same frame, and then starts with what the GPU kept for the
 *       id (seen or hidden by occlusion culling) for its first frame.</li>
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

    private final SceneResources resources;
    private final Renderables renderables;
    private final SceneGraph scene;
    private final ChangeClock clock;
    private final GpuScene gpu;
    private final JobSystem jobs; // or null: the instances are written on the calling thread
    private final JobSystem.RangeTask fill = this::fill;
    private final Renderables.Visitor onRenderableChange = (node, changes) -> reconcile(node);
    private final NodeChange.Visitor onSceneChange = this::sceneChanged;
    private long seen;
    private boolean started;
    private boolean closed;

    // Items, one per renderable, by node index.
    private long[] itemNode = new long[64];
    private ViewMesh[] itemMesh = new ViewMesh[64];
    private SceneResources.MaterialSet[] itemSet = new SceneResources.MaterialSet[64];
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

    // Instance ids (itemSlot) are the GpuScene's, stable while drawn, the lowest free one taken: what the GPU keeps per
    // instance (occlusion history, later previous transforms) stays with its instance.
    private int instanceCount;          // drawn
    // Items to draw at the end of the update, oldest first: their meshes arrived (maybe before the changes of the
    // frame were followed), or the instance table or the material table had no room. Checked again then: they may be
    // gone.
    private int[] waiting = new int[64];
    private int waitingCount;
    private boolean[] isWaiting = new boolean[64];

    private boolean rewriteAll = true;

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
     * Keeps {@code renderables} as instances of {@code gpu}, which holds meshes of {@code resources}' pool and is given
     * its material table (scenes sharing a {@link GpuScene} share their resources too).
     */
    public SceneSync(SceneResources resources, Renderables renderables, GpuScene gpu) {
        this(resources, renderables, gpu, null);
    }

    /**
     * Like {@link #SceneSync(SceneResources, Renderables, GpuScene)}, writing many instances at once (most nodes moved,
     * say) on {@code jobs}' threads: each instance is claimed in order on the calling thread and its record written
     * on any, so the ids and the result do not depend on the threads.
     */
    @AllowAllocation("created rarely")
    public SceneSync(SceneResources resources, Renderables renderables, GpuScene gpu, JobSystem jobs) {
        this.jobs = jobs;
        this.resources = Objects.requireNonNull(resources, "resources");
        this.renderables = Objects.requireNonNull(renderables, "renderables");
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        if (gpu.pool() != resources.pool()) {
            throw new IllegalArgumentException("The GPU scene holds another geometry pool's meshes than the resources "
                + "fill");
        }
        this.scene = renderables.scene();
        this.clock = scene.clock();
        gpu.setMaterials(resources.materials());
    }

    private static int[] filled(int length) {
        int[] array = new int[length];
        Arrays.fill(array, NONE);
        return array;
    }

    /** The instances this scene is kept as. */
    public GpuScene gpu() {
        return gpu;
    }

    public Renderables renderables() {
        return renderables;
    }

    public SceneResources resources() {
        return resources;
    }

    /**
     * Brings the instances up to date: renderables added, changed or removed since the last update, and nodes moved.
     * Call after the frame boundary, {@code scene.update()} and
     * {@code renderables.update()}, before the frame graph records. Allocates nothing once warm when only transforms
     * changed.
     */
    public void update() {
        checkOpen();
        long since = seen;
        seen = clock.now();
        if (!started || !renderables.changesSince(since, onRenderableChange)) {
            started = true;
            rebuild(); // the first update, or fell behind the renderables' log
        }
        if (!rewriteAll) {
            // More changed nodes than half the instances (or more than the scene's log kept): rewriting every
            // instance costs less than following the changes.
            int changed = scene.changeCountSince(since);
            if (changed < 0 || changed > instanceCount / 2) {
                rewriteAll = true;
            } else {
                scene.changesSince(since, onSceneChange);
            }
        }
        if (rewriteAll) {
            // Not following the scene's log: nodes removed after the renderables' update (which drops their renderables
            // next frame) go now, before anything reads them.
            for (int item = 0; item < itemNode.length; item++) {
                if (itemNode[item] != NodeHandle.NONE && !scene.contains(itemNode[item])) {
                    destroy(item);
                }
            }
        }
        resources.flush();
        drawWaiting();
        int count = 0;
        if (rewriteAll) {
            for (int item = 0; item < itemNode.length; item++) {
                if (itemSlot[item] != NONE) {
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
        if (jobs != null && count >= PARALLEL) {
            jobs.parallelFor(count, GRAIN, fill);
        } else {
            fill(0, count, 0);
        }
        for (int i = 0; i < movedCount; i++) {
            moved[movedItems[i]] = false;
        }
        movedCount = 0;
    }

    /** How many renderables are drawn now: those whose meshes are on the GPU. */
    public int instanceCount() {
        return instanceCount;
    }

    /** How many renderables this scene has, drawn or waiting for their meshes. */
    public int renderableCount() {
        return itemCount;
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
     * Renderables whose meshes are on the GPU that do not draw yet: the material table has no room for their materials,
     * or assets they wait for ({@link SceneResources.Loading#WHOLE}) have not arrived. They draw as those come.
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
        renderables.forEach(this::reconcile);
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
        if (!renderables.has(node) || !scene.contains(node)) { // gone, or its node is: the renderables see it later
            if (itemNode[item] == node) {
                destroy(item);
            }
            return;
        }
        AssetRef<MeshData> ref = renderables.mesh(node);
        List<AssetRef<MaterialData>> list = renderables.materials(node);
        if (itemNode[item] == node) {
            if (itemMesh[item].mesh.ref.equals(ref)) {
                if (itemSet[item] != null && !SceneResources.sameMaterials(itemSet[item].key.materials(), list)) {
                    swapMaterials(item, list); // the mesh and the instance id stay
                } else if (itemSlot[item] != NONE) {
                    markMoved(item); // whether it casts shadows, written with the instance
                }
                return;
            }
            destroy(item);
        }
        ViewMesh entry = meshes.get(ref);
        if (entry == null) {
            entry = new ViewMesh();
            meshes.put(ref, entry);
            entry.mesh = resources.acquireMesh(ref, entry); // may upload now: no items of it to show yet
        }
        entry.holders++;
        itemNode[item] = node;
        itemMesh[item] = entry;
        itemCount++;
        link(entry, item);
        if (entry.mesh.uploaded()) {
            show(item);
        }
    }

    /**
     * Draws {@code item}, drawn now, with {@code materials} instead: the new set is taken before the old one goes, so
     * materials both use stay on the GPU. With no room in the material table it stops drawing until there is.
     */
    private void swapMaterials(int item, List<AssetRef<MaterialData>> materials) {
        SceneResources.MaterialSet set = resources.acquireSet(materials, itemMesh[item].mesh.data.materialSlots());
        if (set == null) {
            hide(item);
            queue(item);
            return;
        }
        resources.releaseSet(itemSet[item]);
        itemSet[item] = set;
        if (itemSlot[item] != NONE) {
            markMoved(item);
        }
    }

    private void sceneChanged(long node, int changes) {
        int item = NodeHandle.index(node);
        if (NodeChange.has(changes, NodeChange.REMOVED)) {
            // Removed after the renderables' update, which drops its renderable next frame: its instance goes now, and
            // nothing reads the node again.
            if (item < itemNode.length && itemNode[item] == node) {
                destroy(item);
            }
            return;
        }
        if ((NodeChange.has(changes, NodeChange.TRANSFORM) || NodeChange.has(changes, NodeChange.LAYERS))
            && item < itemNode.length && itemNode[item] == node
            && itemSlot[item] != NONE) {
            markMoved(item);
        }
    }

    private void destroy(int item) {
        hide(item);
        ViewMesh entry = itemMesh[item];
        unlink(entry, item);
        itemMesh[item] = null;
        itemNode[item] = NodeHandle.NONE;
        itemCount--;
        if (--entry.holders == 0) {
            meshes.remove(entry.mesh.ref);
            resources.releaseMesh(entry.mesh, entry);
        }
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
        itemSlot = Arrays.copyOf(itemSlot, length);
        itemNext = Arrays.copyOf(itemNext, length);
        itemPrevious = Arrays.copyOf(itemPrevious, length);
        moved = Arrays.copyOf(moved, length);
        isWaiting = Arrays.copyOf(isWaiting, length);
        movedItems = Arrays.copyOf(movedItems, length);
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
            return false; // the material table is full: drawn when ids free
        }
        if (!resources.ready(itemSet[item])) {
            return false; // assets it waits for are not on the GPU yet
        }
        int slot = gpu.freeId(); // the table grows if it is full
        instanceCount++;
        itemSlot[item] = slot;
        write(gpu.claim(slot), item); // takes the id
        return true;
    }

    /**
     * Holds {@code item}'s block of materials, from when its mesh is on the GPU, while it waits too: its materials and
     * textures load. Whether it holds one: not while the material table has no room, nor once its renderable is gone.
     */
    private boolean holdMaterials(int item) {
        if (itemSet[item] == null && renderables.has(itemNode[item])) {
            itemSet[item] = resources.acquireSet(renderables.materials(itemNode[item]),
                itemMesh[item].mesh.data.materialSlots());
        }
        return itemSet[item] != null;
    }

    /** Stops drawing {@code item}: its id becomes a hole, free for the next renderable drawn. */
    private void hide(int item) {
        int slot = itemSlot[item];
        if (slot != NONE) {
            gpu.remove(slot);
            instanceCount--;
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
        gpu.write(entry, itemMesh[item].mesh.id, itemSet[item].first, scene.layers(node),
            renderables.castsShadows(node), s.get(DOUBLE, o), s.get(DOUBLE, o + 8), s.get(DOUBLE, o + 16),
            (float) s.get(DOUBLE, o + 24), (float) s.get(DOUBLE, o + 32), (float) s.get(DOUBLE, o + 40),
            (float) s.get(DOUBLE, o + 48),
            (float) s.get(DOUBLE, o + 56), (float) s.get(DOUBLE, o + 64), (float) s.get(DOUBLE, o + 72));
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
            closed = true;
        }
    }
}
