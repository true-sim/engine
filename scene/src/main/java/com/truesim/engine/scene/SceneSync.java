package com.truesim.engine.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.JobSystem;
import com.truesim.common.math.Maths;
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
import com.truesim.scene.render.SkinBinding;
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
 *   <li><b>Skins:</b> a renderable with a skin ({@link Renderables#setSkin}) whose mesh has joints draws a copy of
 *       its own ({@code GeometryPool.addDeformed}), posed each update from its joints' world transforms relative to
 *       its node, in doubles, and skinned on the GPU before anything draws.</li>
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
    // Skinned items: the deformed copy each draws (NONE: its mesh as it is), and those that have one, densely.
    private int[] itemCopy = filled(64);
    private int[] skinned = new int[64];
    private int skinnedCount;
    private final double[] relative = new double[12]; // the node's world transform, inverted
    private final double[] jointWorld = new double[12];
    private final double[] matrix = new double[12];
    private final double[] box = new double[6];

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
        if (gpu.drawsMaterials() && gpu.materials() != resources.materials()) {
            throw new IllegalArgumentException("The GPU scene draws another material table than the resources fill");
        }
        this.scene = renderables.scene();
        this.clock = scene.clock();
        gpu.setMaterials(resources.materials()); // the resources' table: instances name its ids
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
            if (changed < 0 || changed > Math.max(instanceCount, itemCount) / 2) {
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
        finishSwaps();
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
        for (int i = 0; i < skinnedCount; i++) {
            pose(skinned[i]);
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
            pendingSet[item] = resources.acquireSet(renderables.materials(itemNode[item]),
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
        cancelPendingMesh(item);
        if (pendingSet[item] != null) {
            resources.releaseSet(pendingSet[item]);
            pendingSet[item] = null;
        }
        ViewMesh entry = itemMesh[item];
        unlink(entry, item);
        itemMesh[item] = null;
        itemNode[item] = NodeHandle.NONE;
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
        itemSlot = Arrays.copyOf(itemSlot, length);
        itemNext = Arrays.copyOf(itemNext, length);
        itemPrevious = Arrays.copyOf(itemPrevious, length);
        moved = Arrays.copyOf(moved, length);
        isWaiting = Arrays.copyOf(isWaiting, length);
        pendingMesh = Arrays.copyOf(pendingMesh, length);
        pendingSet = Arrays.copyOf(pendingSet, length);
        isSwapping = Arrays.copyOf(isSwapping, length);
        movedItems = Arrays.copyOf(movedItems, length);
        itemCopy = Arrays.copyOf(itemCopy, length);
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
        itemSlot[item] = slot;
        refreshCopy(item);
        write(gpu.claim(slot), item); // takes the id
        return true;
    }

    /**
     * Holds {@code item}'s block of materials, from when its mesh is on the GPU, while it waits too: its materials and
     * textures load. Whether it holds one: not once its renderable is gone.
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
        dropCopy(item);
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

    /**
     * Gives drawn {@code item} the deformed copy its skin asks for, or none: a new one when its mesh or skin changed.
     * The instance is rewritten with it.
     */
    @AllowAllocation("a skinned renderable drawn or changed")
    private void refreshCopy(int item) {
        SkinBinding skin = renderables.has(itemNode[item]) ? renderables.skin(itemNode[item]) : null;
        MeshData data = itemMesh[item].mesh.data;
        boolean wanted = skin != null && data.jointCount() > 0;
        int source = itemMesh[item].mesh.id;
        var pool = resources.pool();
        if (itemCopy[item] != NONE && wanted && pool.deformedFrom(itemCopy[item]) == source) {
            return; // the same mesh: its joints are read each update
        }
        dropCopy(item);
        if (!wanted) {
            return;
        }
        int copy = pool.addDeformed(source);
        pool.deformation().add(copy, data.jointCount());
        itemCopy[item] = copy;
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
        resources.pool().remove(itemCopy[item]); // after its instance stops naming it: written this update
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
     * Poses {@code item}'s copy: each palette joint's matrix, the joint's world transform relative to the node's times
     * its inverse bind matrix, and the box of the joints' rest boxes so moved, with how far any of them moves.
     */
    private void pose(int item) {
        long node = itemNode[item];
        SkinBinding skin = renderables.skin(node);
        MeshData data = itemMesh[item].mesh.data;
        int copy = itemCopy[item];
        world(node, matrix);
        invert(matrix, relative);
        box[0] = box[1] = box[2] = Double.POSITIVE_INFINITY;
        box[3] = box[4] = box[5] = Double.NEGATIVE_INFINITY;
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
                double x = (c & 1) == 0 ? corner[0] : corner[3];
                double y = (c & 2) == 0 ? corner[1] : corner[4];
                double z = (c & 4) == 0 ? corner[2] : corner[5];
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
        if (box[0] > box[3]) { // no joint moves a vertex: the rest box
            for (int axis = 0; axis < 3; axis++) {
                box[axis] = data.boxMin(axis);
                box[3 + axis] = data.boxMin(axis) + data.boxExtent(axis);
            }
        }
        resources.pool().deformation().bounds(copy, box[0], box[1], box[2], box[3], box[4], box[5], growth);
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
