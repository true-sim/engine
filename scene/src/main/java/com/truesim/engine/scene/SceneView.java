package com.truesim.engine.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.TransformArray;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
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
 * A scene drawn by a {@link MeshletRenderer}: its {@link Renderables} as the renderer's instances, at their world
 * positions, from shared {@link SceneResources}, following the scene's and the renderables' change logs. Cameras are
 * the renderer's views, each at its own world position ({@link com.truesim.engine.render.Camera#position}): the GPU
 * draws the instances relative to each, so moving a camera rewrites no instance, and every view (the camera, shadow
 * maps, mirrors, more cameras) is culled in one pass. Its {@link #geometry()} is what the frame graph's techniques
 * draw, whichever rendering mode the graph runs.
 *
 * <pre>{@code
 * MeshletRenderer renderer = new MeshletRenderer(device, resources.pool(), limits, colorFormat, depthFormat, slots);
 * SceneView view = new SceneView(resources, renderables, renderer);
 * graph.add(new ClusteredForwardShading(view.geometry(), width, height, colorFormat, depthFormat));
 * // a rendering mode is a technique: graph.replace(Keys.SCENE_COLOR, new VisibilityBufferShading(view.geometry(), ...))
 *
 * // every frame, after the frame boundary (tasks.drainCompleted()), scene.update() and renderables.update():
 * view.update();                               // renderer.setInstanceCount and the instances that changed
 * camera.position(cameraX, cameraY, cameraZ);  // the camera's world position, in doubles
 * view.geometry().setView(0, camera, 1, MeshletRenderer.MODE_LIT);
 * }</pre>
 *
 * <p>Several scenes draw as several views, each with its own renderer and frame graph, all from one
 * {@link SceneResources}: a mesh or material used by several views is on the GPU once. Several cameras on one scene
 * are several of the renderer's views (as many as its limits give), each drawn by a graph of its own.
 *
 * <ul>
 *   <li><b>Assets by reference:</b> a renderable draws once its mesh is on the GPU; materials draw untextured until
 *       their textures arrive, and as the default until they load (see {@link SceneResources}).</li>
 *   <li><b>Materials per submesh:</b> a renderable's materials take consecutive ids in the material table, one per
 *       submesh of its mesh, shared by every renderable of every view with the same materials and mesh layout.</li>
 *   <li><b>Changes, not the whole scene:</b> renderables added, changed or removed, and nodes moved, are found in the
 *       change logs. Only moved instances are written, or every one when most nodes moved.</li>
 *   <li><b>Stable ids:</b> a renderable keeps its instance id while drawn; a removed one leaves a hole the next takes
 *       (the lowest free id), so what the GPU keeps per instance stays with it. Deterministic: the same changes give the
 *       same ids.</li>
 *   <li><b>Layers and shadows:</b> instances are on their nodes' layers ({@code SceneGraph.setLayers}), drawn in views
 *       of any of them ({@code renderer.setViewFilter}), and cast shadows as their renderables say: shadow maps and
 *       shadow rays leave out those that do not.</li>
 * </ul>
 *
 * <p>Not yet followed: alpha modes and double-sidedness (the renderer draws everything opaque). Updating allocates
 * nothing once warm when nothing but transforms changed. Use it on the main thread, the asset cache's.
 */
public final class SceneView implements AutoCloseable {

    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED;
    private static final int NONE = SceneResources.NONE;

    private final SceneResources resources;
    private final Renderables renderables;
    private final SceneGraph scene;
    private final ChangeClock clock;
    private final MeshletRenderer renderer;
    private final SceneGeometry geometry;
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
    private int itemCount;

    // Instances: id (the renderer's index) to item, NONE for a hole. Ids are stable while drawn, the lowest free one
    // taken: what the GPU keeps per instance (occlusion history, later previous transforms) stays with its instance.
    private final int[] slotItem;
    private final long[] usedIds;
    private int instanceCount;          // drawn
    private int highWater;              // ids from 0 up to here, holes included, are the renderer's
    // Items to draw at the end of the update, oldest first: their meshes arrived (maybe before the changes of the
    // frame were followed), or the renderer or the material table had no room. Checked again then: they may be gone.
    private int[] waiting = new int[64];
    private int waitingCount;
    private boolean[] isWaiting = new boolean[64];

    private boolean rewriteAll = true;

    private final Map<AssetRef<MeshData>, ViewMesh> meshes = new HashMap<>();

    /** A mesh this view's renderables use: the shared upload, and the list of this view's items using it. */
    private final class ViewMesh implements SceneResources.MeshUser {
        SceneResources.Mesh mesh;
        int holders;
        int firstItem = NONE;

        @Override
        public void uploaded(SceneResources.Mesh uploaded) {
            // Drawn at the end of the update, once the frame's changes are followed: a renderable may be gone already.
            for (int item = firstItem; item != NONE; item = itemNext[item]) {
                queue(item);
            }
        }
    }

    /**
     * A view drawing {@code renderables} with {@code renderer}, which draws the meshes of {@code resources}' pool and is
     * given its material table.
     */
    @AllowAllocation("created rarely")
    public SceneView(SceneResources resources, Renderables renderables, MeshletRenderer renderer) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.renderables = Objects.requireNonNull(renderables, "renderables");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        if (renderer.pool() != resources.pool()) {
            throw new IllegalArgumentException("The renderer draws another geometry pool than the resources fill");
        }
        this.scene = renderables.scene();
        this.clock = scene.clock();
        this.geometry = SceneGeometry.meshlets(renderer);
        slotItem = filled(renderer.limits().instances());
        usedIds = new long[(renderer.limits().instances() + 63) / 64];
        renderer.setMaterials(resources.materials());
    }

    private static int[] filled(int length) {
        int[] array = new int[length];
        Arrays.fill(array, NONE);
        return array;
    }

    /** What the frame graph's techniques draw for this view: its renderer's meshlets. */
    public SceneGeometry geometry() {
        return geometry;
    }

    public MeshletRenderer renderer() {
        return renderer;
    }

    public Renderables renderables() {
        return renderables;
    }

    public SceneResources resources() {
        return resources;
    }

    /**
     * Brings the renderer up to date: renderables added, changed or removed since the last update, and nodes moved.
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
            // instance, in slot order, costs less than following the changes.
            int changed = scene.changeCountSince(since);
            if (changed < 0 || changed > instanceCount / 2) {
                rewriteAll = true;
            } else {
                scene.changesSince(since, onSceneChange);
            }
        }
        resources.flush();
        drawWaiting();
        if (rewriteAll) {
            for (int slot = 0; slot < highWater; slot++) {
                if (slotItem[slot] != NONE) {
                    writeInstance(slotItem[slot]);
                }
            }
            rewriteAll = false;
        } else {
            for (int i = 0; i < movedCount; i++) {
                int item = movedItems[i];
                if (itemSlot[item] != NONE) {
                    writeInstance(item);
                }
            }
        }
        for (int i = 0; i < movedCount; i++) {
            moved[movedItems[i]] = false;
        }
        movedCount = 0;
        renderer.setInstanceCount(highWater);
    }

    /** How many renderables are drawn now: those whose meshes are on the GPU. */
    public int instanceCount() {
        return instanceCount;
    }

    /** How many renderables this view follows, drawn or waiting for their meshes. */
    public int renderableCount() {
        return itemCount;
    }

    /** How many distinct meshes this view's renderables use. */
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
     * Renderables ready to draw that the renderer has no instance slot for, or the material table no room for their
     * materials: they draw as room frees.
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
        if (!renderables.has(node)) {
            if (itemNode[item] == node) {
                destroy(item);
            }
            return;
        }
        AssetRef<MeshData> ref = renderables.mesh(node);
        List<AssetRef<MaterialData>> list = renderables.materials(node);
        if (itemNode[item] == node) {
            if (itemMesh[item].mesh.ref.equals(ref)
                && (itemSet[item] == null || SceneResources.sameMaterials(itemSet[item].key.materials(), list))) {
                if (itemSlot[item] != NONE) {
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

    private void sceneChanged(long node, int changes) {
        int item = NodeHandle.index(node);
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
        Arrays.fill(itemSlot, old, length, NONE);
        Arrays.fill(itemNext, old, length, NONE);
        Arrays.fill(itemPrevious, old, length, NONE);
    }

    // ---------------------------------------------------------------- instances

    /** Draws {@code item}, or has it wait for room: in the renderer, or in the material table. */
    private void show(int item) {
        if (!tryShow(item)) {
            queue(item);
        }
    }

    /** Draws {@code item}, a live renderable's whose mesh is on the GPU: the lowest free instance id, its materials. */
    private boolean tryShow(int item) {
        if (instanceCount == slotItem.length) {
            return false; // the renderer holds no more instances: drawn when a slot frees
        }
        SceneResources.Mesh mesh = itemMesh[item].mesh;
        SceneResources.MaterialSet set = resources.acquireSet(renderables.materials(itemNode[item]),
            mesh.data.materialSlots());
        if (set == null) {
            return false; // the material table is full: drawn when ids free
        }
        itemSet[item] = set;
        int slot = takeId();
        instanceCount++;
        highWater = Math.max(highWater, slot + 1);
        slotItem[slot] = item;
        itemSlot[item] = slot;
        markMoved(item);
        return true;
    }

    /** Stops drawing {@code item}: its id becomes a hole, free for the next renderable drawn. */
    private void hide(int item) {
        int slot = itemSlot[item];
        if (slot == NONE) {
            return;
        }
        renderer.removeInstance(slot);
        slotItem[slot] = NONE;
        usedIds[slot >>> 6] &= ~(1L << slot);
        instanceCount--;
        while (highWater > 0 && slotItem[highWater - 1] == NONE) {
            highWater--;
        }
        itemSlot[item] = NONE;
        resources.releaseSet(itemSet[item]);
        itemSet[item] = null;
    }

    /** The lowest free instance id, taken: there is one. */
    private int takeId() {
        for (int w = 0; ; w++) {
            long free = ~usedIds[w];
            if (free != 0) {
                int id = (w << 6) + Long.numberOfTrailingZeros(free);
                usedIds[w] |= 1L << id;
                return id;
            }
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

    /** Writes {@code item}'s instance: its node's world transform and layers, its mesh, materials and shadows. */
    private void writeInstance(int item) {
        TransformArray world = scene.worldTransforms();
        MemorySegment s = world.segment();
        long o = NodeHandle.index(itemNode[item]) * world.stride();
        long node = itemNode[item];
        renderer.setInstance(itemSlot[item], itemMesh[item].mesh.id, itemSet[item].first, scene.layers(node),
            renderables.castsShadows(node), s.get(DOUBLE, o), s.get(DOUBLE, o + 8), s.get(DOUBLE, o + 16),
            (float) s.get(DOUBLE, o + 24), (float) s.get(DOUBLE, o + 32), (float) s.get(DOUBLE, o + 40),
            (float) s.get(DOUBLE, o + 48),
            (float) s.get(DOUBLE, o + 56), (float) s.get(DOUBLE, o + 64), (float) s.get(DOUBLE, o + 72));
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The scene view is closed");
        }
    }

    /**
     * Stops following the scene: every renderable stops drawing, and what only this view used leaves the GPU and is
     * released in the asset cache. The resources and the renderer stay open.
     */
    @Override
    public void close() {
        if (!closed) {
            destroyAll();
            renderer.setInstanceCount(0);
            closed = true;
        }
    }
}
