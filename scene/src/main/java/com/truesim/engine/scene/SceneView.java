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
 * One camera's view of a scene: draws its {@link Renderables} with a {@link MeshletRenderer} from shared
 * {@link SceneResources}, following the scene's and the renderables' change logs, and writes the instances relative to
 * the camera every frame. Its {@link #geometry()} is what the frame graph's techniques draw, whichever rendering mode
 * the graph runs.
 *
 * <pre>{@code
 * MeshletRenderer renderer = new MeshletRenderer(device, resources.pool(), limits, colorFormat, depthFormat, slots);
 * SceneView view = new SceneView(resources, renderables, renderer);
 * graph.add(new ClusteredForwardShading(view.geometry(), width, height, colorFormat, depthFormat));
 * // a rendering mode is a technique: graph.replace(Keys.SCENE_COLOR, new VisibilityBufferShading(view.geometry(), ...))
 *
 * // every frame, after the frame boundary (tasks.drainCompleted()), scene.update() and renderables.update():
 * view.setOrigin(cameraX, cameraY, cameraZ);   // the camera's world position: instances are relative to it
 * view.update();                               // renderer.setInstanceCount and the instances it draws
 * view.geometry().setView(0, camera, 1, MeshletRenderer.MODE_LIT);   // the camera, at the origin
 * }</pre>
 *
 * <p>Several scenes draw as several views, each with its own renderer and frame graph, all from one
 * {@link SceneResources}: a mesh or material used by several views is on the GPU once. Two cameras on one scene are two
 * views of the same renderables (their own origins, their own instances), or one view drawing several of the
 * renderer's views when they are close enough to share an origin.
 *
 * <ul>
 *   <li><b>Assets by reference:</b> a renderable draws once its mesh is on the GPU; materials draw untextured until
 *       their textures arrive, and as the default until they load (see {@link SceneResources}).</li>
 *   <li><b>Materials per submesh:</b> a renderable's materials take consecutive ids in the material table, one per
 *       submesh of its mesh, shared by every renderable of every view with the same materials and mesh layout.</li>
 *   <li><b>Changes, not the whole scene:</b> renderables added, changed or removed, and nodes moved, are found in the
 *       change logs. A frame where the origin moves, or most nodes moved, rewrites every instance; otherwise only
 *       moved ones.</li>
 *   <li><b>Deterministic:</b> the same changes give the same instances, slots and ids.</li>
 * </ul>
 *
 * <p>Not yet followed: shadow casting per renderable, alpha modes and double-sidedness (the renderer draws everything
 * opaque and casting shadows), and the nodes' layers. Updating allocates nothing once warm when nothing but transforms
 * changed. Use it on the main thread, the asset cache's.
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

    // Instances: slot to item, slots 0 to instanceCount - 1 drawn.
    private final int[] slotItem;
    private int instanceCount;
    // Items ready to draw while every slot was taken, drawn as slots free (checked again then: they may have changed).
    private int[] waiting = new int[64];
    private int waitingCount;

    private double originX;
    private double originY;
    private double originZ;
    private boolean originMoved = true;

    private final Map<AssetRef<MeshData>, ViewMesh> meshes = new HashMap<>();

    /** A mesh this view's renderables use: the shared upload, and the list of this view's items using it. */
    private final class ViewMesh implements SceneResources.MeshUser {
        SceneResources.Mesh mesh;
        int holders;
        int firstItem = NONE;

        @Override
        public void uploaded(SceneResources.Mesh uploaded) {
            for (int item = firstItem; item != NONE; item = itemNext[item]) {
                show(item);
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

    /** The camera's world position: instances are written relative to it, in floats, precise near the camera. */
    public void setOrigin(double x, double y, double z) {
        if (x != originX || y != originY || z != originZ) {
            originX = x;
            originY = y;
            originZ = z;
            originMoved = true;
        }
    }

    /**
     * Brings the renderer up to date: renderables added, changed or removed since the last update, nodes moved, and
     * every instance when the origin moved. Call after the frame boundary, {@code scene.update()} and
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
        if (!originMoved) {
            // More changed nodes than half the instances (or more than the scene's log kept): rewriting every
            // instance, in slot order, costs less than following the changes.
            int changed = scene.changeCountSince(since);
            if (changed < 0 || changed > instanceCount / 2) {
                originMoved = true;
            } else {
                scene.changesSince(since, onSceneChange);
            }
        }
        resources.flush();
        if (originMoved) {
            for (int slot = 0; slot < instanceCount; slot++) {
                writeInstance(slotItem[slot]);
            }
            originMoved = false;
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
        renderer.setInstanceCount(instanceCount);
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

    /** The instance slot {@code node}'s renderable is drawn in, or -1 while it is not drawn. */
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

    /** Renderables ready to draw that the renderer has no instance slot for: they draw as slots free. */
    public int waitingCount() {
        int count = 0;
        for (int i = 0; i < waitingCount; i++) {
            int item = waiting[i];
            count += itemNode[item] != NodeHandle.NONE && itemSlot[item] == NONE ? 1 : 0;
        }
        return count;
    }

    // ---------------------------------------------------------------- following the renderables

    /** Drops every item and makes them again from the renderables as they are. */
    @AllowAllocation("rebuilding, rarely")
    private void rebuild() {
        destroyAll();
        renderables.forEach(this::reconcile);
        originMoved = true;
    }

    private void destroyAll() {
        for (int item = 0; item < itemNode.length; item++) {
            if (itemNode[item] != NodeHandle.NONE) {
                destroy(item);
            }
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
                return; // nothing a view follows changed (e.g. shadows)
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
        if (NodeChange.has(changes, NodeChange.TRANSFORM) && item < itemNode.length && itemNode[item] == node
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
        movedItems = Arrays.copyOf(movedItems, length);
        Arrays.fill(itemSlot, old, length, NONE);
        Arrays.fill(itemNext, old, length, NONE);
        Arrays.fill(itemPrevious, old, length, NONE);
    }

    // ---------------------------------------------------------------- instances

    /** Draws {@code item}: an instance slot at the end, and its materials. */
    private void show(int item) {
        if (instanceCount == slotItem.length) {
            wait(item); // the renderer holds no more instances: drawn when a slot frees
            return;
        }
        SceneResources.Mesh mesh = itemMesh[item].mesh;
        itemSet[item] = resources.acquireSet(renderables.materials(itemNode[item]), mesh.data.materialSlots());
        int slot = instanceCount++;
        slotItem[slot] = item;
        itemSlot[item] = slot;
        markMoved(item);
    }

    /** Stops drawing {@code item}: the last instance moves into its slot. */
    private void hide(int item) {
        int slot = itemSlot[item];
        if (slot == NONE) {
            return;
        }
        int last = slotItem[--instanceCount];
        slotItem[instanceCount] = NONE;
        if (last != item) {
            slotItem[slot] = last;
            itemSlot[last] = slot;
            markMoved(last);
        }
        itemSlot[item] = NONE;
        resources.releaseSet(itemSet[item]);
        itemSet[item] = null;
        while (waitingCount > 0) {
            int next = waiting[--waitingCount];
            if (next != item && itemNode[next] != NodeHandle.NONE && itemSlot[next] == NONE
                && itemMesh[next].mesh.uploaded()) {
                show(next);
                break;
            }
        }
    }

    @AllowAllocation("growing the list of waiting items")
    private void wait(int item) {
        if (waitingCount == waiting.length) {
            waiting = Arrays.copyOf(waiting, waitingCount * 2);
        }
        waiting[waitingCount++] = item;
    }

    private void markMoved(int item) {
        if (!moved[item]) {
            moved[item] = true;
            movedItems[movedCount++] = item;
        }
    }

    /** Writes {@code item}'s instance: its node's world transform relative to the origin, its mesh and materials. */
    private void writeInstance(int item) {
        TransformArray world = scene.worldTransforms();
        MemorySegment s = world.segment();
        long o = NodeHandle.index(itemNode[item]) * world.stride();
        renderer.setInstance(itemSlot[item], itemMesh[item].mesh.id, itemSet[item].first,
            (float) (s.get(DOUBLE, o) - originX), (float) (s.get(DOUBLE, o + 8) - originY),
            (float) (s.get(DOUBLE, o + 16) - originZ),
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
