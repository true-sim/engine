package com.truesim.engine.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.Lane;
import com.truesim.common.jobs.Task;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.jobs.TaskState;
import com.truesim.common.math.Vec2;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.Vec4;
import com.truesim.common.math.color.Color;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.MaterialFeature;
import com.truesim.engine.render.MaterialFeatures;
import com.truesim.engine.render.MaterialType;
import com.truesim.engine.render.MaterialValues;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.TextureSampler;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.materials.TextureTransformParameters;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.TextureData;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The GPU side of scene assets, shared by every {@link SceneSync} on a device: meshes in a {@link GeometryPool},
 * materials in a {@link Materials} table and textures in {@link Textures}, uploaded from the asset cache once however
 * many scenes and renderables use them, and removed when none does. Several scenes, in one GPU scene or several, draw
 * from the same resources.
 *
 * <pre>{@code
 * FrameSlots slots = graph.frameSlots();          // or slots shared by several graphs
 * SceneResources resources = new SceneResources(assets, new GeometryPool(device, capacity, slots),
 *     new Materials(device, 4096, slots), new Textures(device, slots));
 * SceneSync world = new SceneSync(resources, worldRenderables, gpu);
 * SceneSync gizmos = new SceneSync(resources, gizmoRenderables, gpu);  // same table, other layers
 * SceneSync preview = new SceneSync(resources, previewRenderables, previewGpu);
 * }</pre>
 *
 * <p>An asset is acquired in the cache while some scene uses it, uploaded once it has loaded, and freed on the GPU (once
 * no frame in flight draws it) and released in the cache when the last scene lets go. Give the pool, materials and
 * textures the frame slots of every graph drawing them (shared slots for several graphs). Use it on the main thread, the
 * asset cache's.
 *
 * <p>How renderables come in is the application's choice, and each asset's ({@link Loading}): streamed, they draw as
 * soon as their mesh is on the GPU, materials as the default until they load and untextured until their textures do;
 * whole, they wait until those assets are on the GPU too, so nothing pops in. To show a level only once it has loaded,
 * {@link #awaitLoaded} blocks until every asset the scenes use is there. Given a lane ({@link #prepareOn}), meshes and
 * textures are made ready for the GPU on its threads (their data copied into staging memory), so the main thread only
 * places them.
 *
 * <pre>{@code
 * resources.loading(SceneResources.Loading.WHOLE)                 // the application's default: no pop-in
 *     .loading(skyTexture, SceneResources.Loading.STREAM)         // this one may come late: not waited for
 *     .prepareOn(tasks, 2);                                       // two threads of the asset cache's scheduler
 * world.update();                                                 // the level's renderables, their assets acquired
 * resources.awaitLoaded(tasks, Duration.ofSeconds(30));           // startup: block until all of it is there
 * world.update();                                                 // all of it drawn from the next frame
 * // a loading screen instead keeps drawing frames, updating the scenes, until resources.loaded()
 * }</pre>
 */
public final class SceneResources implements AutoCloseable {

    static final int NONE = -1;
    private static final Logger LOG = LoggerFactory.getLogger(SceneResources.class);

    /** How a renderable takes in an asset: whether it waits for it to draw. */
    public enum Loading {
        /**
         * Drawn without it until it arrives: a renderable draws once its mesh is on the GPU, a material as the default
         * until it loads, a texture as none until it does.
         */
        STREAM,
        /**
         * Waited for: a renderable using it first draws once it is on the GPU (a material: loaded, with its textures
         * that are waited for too). An asset that fails is not waited for.
         */
        WHOLE
    }

    private final AssetCache assets;
    private final GeometryPool pool;
    private final Materials materials;
    private final Textures textures;
    private final AssetCache.Listener listener = new Listener();
    private Diagnostics diagnostics = new Diagnostics() { };
    private final Map<AssetRef<MeshData>, Mesh> meshes = new HashMap<>();
    private final Map<MaterialKey, MaterialSet> sets = new HashMap<>();
    private final Map<AssetRef<MaterialData>, MaterialEntry> materialEntries = new HashMap<>();
    private final Map<AssetRef<TextureData>, TextureEntry> textureEntries = new HashMap<>();
    private final List<MaterialSet> dirtySets = new ArrayList<>();
    private Loading loading = Loading.STREAM;
    private final Map<AssetRef<?>, Loading> loadingOf = new HashMap<>();
    private Lane workers; // where meshes and textures are made ready, or null: on the main thread
    private TaskScheduler workerTasks; // the scheduler of the workers, which hands what they made over
    private int preparing; // meshes and textures being made ready on the workers
    private boolean closed;

    /**
     * Hears what keeps a scene from drawing as its data says, which is otherwise only logged: games show it, count it
     * or fail a test on it. Every method does nothing by default.
     */
    public interface Diagnostics {

        /**
         * An asset a scene uses could not load, for {@code cause}: what uses it does not draw (a mesh), or draws as the
         * default (a material) or untextured (a texture).
         */
        default void assetFailed(AssetRef<?> asset, Throwable cause) {
        }

        /**
         * Material {@code material} cannot be drawn as its data says, for {@code problem}: a type not registered or values
         * out of range (it draws as the default), a feature unknown or not allowed by its type (shown without it), or
         * field names its type does not have (ignored).
         */
        default void materialProblem(AssetRef<MaterialData> material, String problem) {
        }
    }

    /** Reports what keeps scenes from drawing as their data says to {@code listener}, from now on. */
    public SceneResources diagnostics(Diagnostics listener) {
        this.diagnostics = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** Hears that a mesh it holds is on the GPU now. */
    interface MeshUser {
        void uploaded(Mesh mesh);
    }

    /** A mesh some views use: its upload, and who waits for it. */
    static final class Mesh {
        final AssetRef<MeshData> ref;
        MeshData data;
        int id = NONE; // in the pool
        final List<MeshUser> users = new ArrayList<>(2);
        boolean preparing; // being made ready on the workers: its data stays held until it is
        boolean dropped; // let go of while preparing
        boolean failed; // it could not load or be placed: never drawn

        Mesh(AssetRef<MeshData> ref) {
            this.ref = ref;
        }

        boolean uploaded() {
            return id != NONE;
        }
    }

    /**
     * The materials of a renderable whose mesh has {@code slots} submeshes: what a block of material ids holds. Looked
     * up through one reused key, so finding a block that exists allocates nothing.
     */
    static final class MaterialKey {
        List<AssetRef<MaterialData>> materials;
        int slots;

        MaterialKey(List<AssetRef<MaterialData>> materials, int slots) {
            this.materials = materials;
            this.slots = slots;
        }

        List<AssetRef<MaterialData>> materials() {
            return materials;
        }

        int slots() {
            return slots;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MaterialKey key && key.slots == slots && sameMaterials(key.materials, materials);
        }

        @Override
        public int hashCode() {
            int hash = slots;
            for (int i = 0; i < materials.size(); i++) {
                hash = 31 * hash + materials.get(i).hashCode();
            }
            return hash;
        }
    }

    /** Whether two material lists are equal, compared by index: no iterators. */
    static boolean sameMaterials(List<AssetRef<MaterialData>> a, List<AssetRef<MaterialData>> b) {
        if (a == b) {
            return true;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private final MaterialKey lookup = new MaterialKey(List.of(), 0);

    /** A block of material ids, one per submesh, shared by every renderable of every scene with the same key. */
    static final class MaterialSet {
        final MaterialKey key;
        final MaterialEntry[] entries; // per submesh; null: the default
        int first;
        int holders;
        boolean dirty;

        MaterialSet(MaterialKey key) {
            this.key = key;
            this.entries = new MaterialEntry[key.slots()];
        }
    }

    /** A material some sets use: its data once loaded, and its textures. */
    private static final class MaterialEntry {
        final AssetRef<MaterialData> ref;
        MaterialData data;
        boolean failed;
        final List<TextureEntry> textures = new ArrayList<>(4);
        final Set<MaterialSet> users = new java.util.LinkedHashSet<>(); // in the order they came: deterministic

        MaterialEntry(AssetRef<MaterialData> ref) {
            this.ref = ref;
        }
    }

    /** A texture some materials use: its bindless slot once uploaded. */
    private static final class TextureEntry {
        final AssetRef<TextureData> ref;
        int slot = NONE;
        final Set<MaterialEntry> users = new java.util.LinkedHashSet<>();
        boolean failed;
        boolean preparing; // being made ready on the workers: its data stays held until it is
        boolean dropped; // let go of while preparing

        TextureEntry(AssetRef<TextureData> ref) {
            this.ref = ref;
        }
    }

    /** Resources uploading from {@code assets} into {@code pool}, {@code materials} and {@code textures}. */
    @AllowAllocation("created rarely")
    public SceneResources(AssetCache assets, GeometryPool pool, Materials materials, Textures textures) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.textures = Objects.requireNonNull(textures, "textures");
        textures.namedBy(materials); // removing a texture a material still names is a bug here, refused
        assets.addListener(listener);
    }

    public GeometryPool pool() {
        return pool;
    }

    public Materials materials() {
        return materials;
    }

    public Textures textures() {
        return textures;
    }

    /**
     * Renderables take in assets as {@code mode} says ({@link Loading#STREAM} by default), unless an asset says
     * otherwise ({@link #loading(AssetRef, Loading)}).
     */
    public SceneResources loading(Loading mode) {
        this.loading = Objects.requireNonNull(mode, "mode");
        return this;
    }

    /**
     * Renderables take in {@code asset} (a material or texture) as {@code mode} says, whatever the default; null goes
     * back to the default. Read as they are drawn: set it before the renderables using it come. For every scene these
     * resources serve. Meshes are always waited for: a renderable draws its mesh or nothing.
     */
    @AllowAllocation("set rarely")
    public SceneResources loading(AssetRef<?> asset, Loading mode) {
        Objects.requireNonNull(asset, "asset");
        if (asset.type() == RenderAssets.MESH) {
            throw new IllegalArgumentException("Meshes are always waited for: " + asset);
        }
        if (mode == null) {
            loadingOf.remove(asset);
        } else {
            loadingOf.put(asset, mode);
        }
        return this;
    }

    /** How renderables take in {@code asset}: its own mode, or the default. */
    public Loading loadingOf(AssetRef<?> asset) {
        Loading mode = loadingOf.get(asset);
        return mode == null ? loading : mode;
    }

    /**
     * Makes meshes and textures ready for the GPU on {@code threads} threads of {@code tasks} (the asset cache's
     * scheduler, whose drain hands them over), their data copied into staging memory there, instead of on the main
     * thread as they arrive; 0 threads goes back to the main thread. Costs a frame of latency, saves the main thread
     * milliseconds per large asset.
     */
    @AllowAllocation("set up once")
    public SceneResources prepareOn(TaskScheduler tasks, int threads) {
        Objects.requireNonNull(tasks, "tasks");
        if (threads < 0) {
            throw new IllegalArgumentException("Threads to prepare on: " + threads);
        }
        this.workers = threads == 0 ? null : tasks.platformLane("gpu prepare", threads);
        this.workerTasks = threads == 0 ? null : tasks;
        return this;
    }

    /** How many meshes and textures are being made ready on the workers ({@link #prepareOn}). */
    public int preparingCount() {
        return preparing;
    }

    /**
     * How many assets the scenes use are not on the GPU yet: loading in the cache, being made ready, the streamed ones
     * too. Failed ones do not count.
     */
    public int loadingCount() {
        return count(false);
    }

    /**
     * How many assets the scenes wait for are not on the GPU yet: their meshes, and materials and textures taken in
     * {@link Loading#WHOLE}; those streamed come when they come. Failed ones do not count.
     */
    public int waitingCount() {
        return count(true);
    }

    /** Whether nothing the scenes wait for is on its way ({@link #waitingCount()} 0): a loading screen's test. */
    public boolean loaded() {
        return waitingCount() == 0;
    }

    private int count(boolean waitedForOnly) {
        int count = 0;
        for (Mesh mesh : meshes.values()) {
            count += mesh.uploaded() || mesh.failed ? 0 : 1;
        }
        for (MaterialEntry entry : materialEntries.values()) {
            boolean counts = !waitedForOnly || loadingOf(entry.ref) == Loading.WHOLE;
            count += !counts || entry.data != null || entry.failed ? 0 : 1;
        }
        for (TextureEntry entry : textureEntries.values()) {
            boolean counts = !waitedForOnly || loadingOf(entry.ref) == Loading.WHOLE;
            count += !counts || entry.slot != NONE || entry.failed ? 0 : 1;
        }
        return count;
    }

    /**
     * Blocks until every asset the scenes wait for is on the GPU (or failed: {@link #waitingCount()}), draining
     * {@code tasks} (the asset cache's scheduler, the one given {@link #prepareOn}) on this thread, the main one, as
     * loads finish. Nothing is drawn or presented meanwhile, nor are events or a headset's frames: for startup and tests;
     * a loading screen draws frames until {@link #loaded()}. Update the scenes before, so they have acquired what they
     * use, and after, to draw what came. Returns whether everything came within {@code timeout}.
     */
    public boolean awaitLoaded(TaskScheduler tasks, Duration timeout) {
        checkOpen();
        if (workerTasks != null && tasks != workerTasks) {
            throw new IllegalArgumentException("Meshes and textures are prepared on another scheduler: drain that one");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            tasks.drainCompleted();
            flush();
            if (loaded()) {
                return true;
            }
            if (System.nanoTime() - deadline > 0) {
                return false;
            }
            LockSupport.parkNanos(200_000);
        }
    }

    /**
     * Whether a renderable of {@code mesh} with materials {@code set} may draw: the assets of it that are waited for
     * ({@link Loading#WHOLE}) are on the GPU or failed. Allocates nothing.
     */
    boolean ready(MaterialSet set) {
        for (int s = 0; s < set.entries.length; s++) {
            MaterialEntry entry = set.entries[s];
            if (entry == null || entry.failed) {
                continue;
            }
            if (entry.data == null) {
                if (loadingOf(entry.ref) == Loading.WHOLE) {
                    return false;
                }
                continue;
            }
            for (int t = 0; t < entry.textures.size(); t++) {
                TextureEntry texture = entry.textures.get(t);
                if (texture.slot == NONE && !texture.failed && loadingOf(texture.ref) == Loading.WHOLE) {
                    return false;
                }
            }
        }
        return true;
    }

    /** How many distinct meshes views use, uploaded or loading. */
    public int meshCount() {
        return meshes.size();
    }

    /** How many of the meshes views use are on the GPU. */
    public int uploadedMeshCount() {
        int count = 0;
        for (Mesh mesh : meshes.values()) {
            count += mesh.uploaded() ? 1 : 0;
        }
        return count;
    }

    /** How many blocks of material ids are in use. */
    public int materialSetCount() {
        return sets.size();
    }

    /** How many distinct materials and textures sets use. */
    public int materialCount() {
        return materialEntries.size();
    }

    public int textureCount() {
        return textureEntries.size();
    }


    // ---------------------------------------------------------------- meshes

    /**
     * Holds {@code ref} for {@code user}: acquired in the cache and uploaded once loaded, at once if it is already. The
     * user hears {@link MeshUser#uploaded} when it is on the GPU, unless it is now.
     */
    @AllowAllocation("a mesh used anew")
    Mesh acquireMesh(AssetRef<MeshData> ref, MeshUser user) {
        checkOpen();
        Mesh mesh = meshes.get(ref);
        if (mesh == null) {
            assets.acquire(ref); // first: a ref no loader can load throws before anything is put in
            mesh = new Mesh(ref);
            meshes.put(ref, mesh);
            mesh.users.add(user);
            MeshData data = assets.get(ref);
            if (data != null) {
                upload(mesh, data);
            } else if (failedAlready(ref)) {
                mesh.failed = true;
            }
            return mesh;
        }
        mesh.users.add(user);
        return mesh;
    }

    /** Lets go of {@code mesh} for {@code user}: at no users it leaves the GPU and is released in the cache. */
    void releaseMesh(Mesh mesh, MeshUser user) {
        mesh.users.remove(user);
        if (mesh.users.isEmpty()) {
            meshes.remove(mesh.ref);
            if (mesh.uploaded()) {
                pool.remove(mesh.id);
            }
            if (mesh.preparing) {
                mesh.dropped = true; // released once the workers are done with its data
            } else {
                assets.release(mesh.ref);
            }
        }
    }

    /** Puts {@code mesh}, loaded from {@code data}, in the pool: made ready on the workers first, if there are. */
    @AllowAllocation("a mesh arrived")
    private void upload(Mesh mesh, MeshData data) {
        mesh.data = data;
        if (workers == null) {
            int id;
            try {
                id = pool.tryAdd(data);
            } catch (RuntimeException e) {
                failed(mesh, e); // not thrown into the cache's delivery: its other listeners hear of the load
                return;
            }
            placed(mesh, id);
            return;
        }
        mesh.preparing = true;
        preparing++;
        workers.task(() -> pool.prepare(data)).deliver(done -> prepared(mesh, done)).submit();
    }

    /** {@code mesh} made ready on the workers: placed in the pool, unless it was let go of meanwhile. */
    private void prepared(Mesh mesh, Task<GeometryPool.Prepared> done) {
        preparing--;
        mesh.preparing = false;
        if (done.state() != TaskState.SUCCEEDED) {
            mesh.failed = true;
            LOG.warn("Could not prepare {}", mesh.ref, done.failure());
            diagnostics.assetFailed(mesh.ref, done.failure());
        } else if (mesh.dropped || closed) {
            done.result().close();
        } else {
            int id = NONE;
            try {
                id = pool.tryAdd(done.result());
            } catch (RuntimeException e) {
                failed(mesh, e);
            }
            if (id == NONE) {
                done.result().close();
            }
            if (!mesh.failed) {
                placed(mesh, id);
            }
        }
        if (mesh.dropped) {
            assets.release(mesh.ref);
        }
    }

    /** {@code mesh} could not go on the GPU, for {@code cause}: never drawn, reported, not waited for. */
    private void failed(Mesh mesh, Throwable cause) {
        mesh.failed = true;
        LOG.warn("Could not put {} on the GPU", mesh.ref, cause);
        diagnostics.assetFailed(mesh.ref, cause);
    }

    /** Whether {@code ref} failed in the cache before (another holder asked first): its failure heard already. */
    private boolean failedAlready(AssetRef<?> ref) {
        if (assets.state(ref) != com.truesim.scene.assets.AssetState.FAILED) {
            return false;
        }
        diagnostics.assetFailed(ref, assets.failure(ref));
        return true;
    }

    /** {@code mesh} is pool mesh {@code id} now: its users hear of it. -1: the pool cannot hold it. */
    @AllowAllocation("reporting a mesh the pool cannot hold")
    private void placed(Mesh mesh, int id) {
        if (id == NONE) {
            String problem = mesh.ref + " has more than " + GeometryPool.MAX_LEVELS + " detail levels: not drawn";
            LOG.warn(problem);
            mesh.failed = true;
            diagnostics.assetFailed(mesh.ref, new IllegalArgumentException(problem));
            return;
        }
        mesh.id = id;
        for (int i = 0; i < mesh.users.size(); i++) {
            mesh.users.get(i).uploaded(mesh);
        }
    }

    // ---------------------------------------------------------------- materials and textures

    /**
     * Holds the block of material ids for {@code materials} on a mesh of {@code slots} submeshes: made and filled the
     * first time (the table grows if it is full). Allocates nothing when the block exists.
     */
    MaterialSet acquireSet(List<AssetRef<MaterialData>> materials, int slots) {
        checkOpen();
        lookup.materials = materials;
        lookup.slots = slots;
        MaterialSet set = sets.get(lookup);
        lookup.materials = List.of(); // the probe holds no list
        if (set == null) {
            set = newSet(new MaterialKey(materials, slots));
        }
        set.holders++;
        return set;
    }

    /** A new block for {@code key}, its materials held: the table has room for it. */
    @AllowAllocation("a new block of materials")
    private MaterialSet newSet(MaterialKey key) {
        MaterialSet set = new MaterialSet(key);
        List<Material> initial = new ArrayList<>(key.slots());
        for (int s = 0; s < key.slots(); s++) {
            AssetRef<MaterialData> ref = key.materials().isEmpty() ? null
                : key.materials().get(Math.min(s, key.materials().size() - 1));
            set.entries[s] = ref == null ? null : acquireMaterial(ref, set);
            initial.add(convert(set.entries[s]));
        }
        set.first = materials.addAll(initial);
        sets.put(key, set);
        return set;
    }

    /** Lets go of {@code set}: at no holders its ids are freed and its materials let go. */
    void releaseSet(MaterialSet set) {
        if (--set.holders > 0) {
            return;
        }
        sets.remove(set.key);
        materials.remove(set.first);
        if (set.dirty) {
            dirtySets.remove(set);
        }
        releaseMaterials(set);
    }

    /**
     * Lets go of {@code set}'s materials, each once: submeshes past the end of the material list share the last one,
     * and a list may name a material twice, but the set holds each once.
     */
    private void releaseMaterials(MaterialSet set) {
        for (int s = 0; s < set.entries.length; s++) {
            MaterialEntry entry = set.entries[s];
            if (entry != null && !heldEarlier(set, s)) {
                releaseMaterial(entry, set);
            }
        }
    }

    /** Whether submesh {@code s}'s material is also an earlier submesh's of {@code set}. */
    private static boolean heldEarlier(MaterialSet set, int s) {
        for (int earlier = 0; earlier < s; earlier++) {
            if (set.entries[earlier] == set.entries[s]) {
                return true;
            }
        }
        return false;
    }

    /** Brings the GPU up to date, called by views as they update: blocks whose materials or textures came are written. */
    void flush() {
        for (int i = 0; i < dirtySets.size(); i++) {
            MaterialSet set = dirtySets.get(i);
            set.dirty = false;
            for (int s = 0; s < set.entries.length; s++) {
                materials.set(set.first + s, convert(set.entries[s]));
            }
        }
        dirtySets.clear();
    }

    @AllowAllocation("a new material")
    private MaterialEntry acquireMaterial(AssetRef<MaterialData> ref, MaterialSet set) {
        MaterialEntry entry = materialEntries.get(ref);
        if (entry == null) {
            try {
                assets.acquire(ref); // first: a ref no loader can load throws before anything is put in
            } catch (RuntimeException e) {
                LOG.warn("Could not ask for {}: it draws as the default", ref, e);
                diagnostics.assetFailed(ref, e);
                return null; // the default, as a material that failed
            }
            entry = new MaterialEntry(ref);
            materialEntries.put(ref, entry);
            MaterialData data = assets.get(ref);
            if (data != null) {
                materialLoaded(entry, data);
            } else if (failedAlready(ref)) {
                entry.failed = true;
            }
        }
        entry.users.add(set); // a set, so a set listed twice is held once
        return entry;
    }

    private void releaseMaterial(MaterialEntry entry, MaterialSet set) {
        entry.users.remove(set);
        if (!entry.users.isEmpty()) {
            return;
        }
        materialEntries.remove(entry.ref);
        for (TextureEntry texture : entry.textures) {
            // A texture the material names twice is in its list twice, its users once: let go of once.
            if (texture.users.remove(entry) && texture.users.isEmpty()) {
                textureEntries.remove(texture.ref);
                if (texture.slot != NONE) {
                    textures.remove(texture.slot);
                }
                if (texture.preparing) {
                    texture.dropped = true; // released once the workers are done with its texels
                } else {
                    assets.release(texture.ref);
                }
            }
        }
        assets.release(entry.ref);
    }

    @AllowAllocation("a material arrived: its textures")
    private void materialLoaded(MaterialEntry entry, MaterialData data) {
        entry.data = data;
        for (AssetRef<TextureData> ref : data.textures()) {
            TextureEntry texture = textureEntries.get(ref);
            if (texture == null) {
                try {
                    assets.acquire(ref); // first: a ref no loader can load throws before anything is put in
                } catch (RuntimeException e) {
                    LOG.warn("Could not ask for {}: drawn untextured", ref, e);
                    diagnostics.assetFailed(ref, e);
                    continue;
                }
                texture = new TextureEntry(ref);
                textureEntries.put(ref, texture);
                texture.users.add(entry);
                entry.textures.add(texture);
                TextureData texels = assets.get(ref);
                if (texels != null) {
                    textureLoaded(texture, texels);
                } else if (failedAlready(ref)) {
                    texture.failed = true;
                }
                continue;
            }
            texture.users.add(entry);
            entry.textures.add(texture);
        }
        markDirty(entry);
    }

    @AllowAllocation("a texture arrived")
    private void textureLoaded(TextureEntry entry, TextureData data) {
        boolean srgb = data.colorSpace() == TextureData.ColorSpace.SRGB;
        if (workers == null) {
            try {
                entry.slot = srgb ? textures.color(data.width(), data.height(), data.texels())
                    : textures.data(data.width(), data.height(), data.texels());
            } catch (RuntimeException e) {
                failed(entry, e); // not thrown into the cache's delivery
                return;
            }
            textureArrived(entry);
            return;
        }
        entry.preparing = true;
        preparing++;
        workers.task(() -> srgb ? textures.prepareColor(data.width(), data.height(), data.texels())
                : textures.prepareData(data.width(), data.height(), data.texels()))
            .deliver(done -> texturePrepared(entry, done)).submit();
    }

    /** {@code entry} made ready on the workers: added, unless it was let go of meanwhile. */
    private void texturePrepared(TextureEntry entry, Task<Textures.Prepared> done) {
        preparing--;
        entry.preparing = false;
        if (done.state() != TaskState.SUCCEEDED) {
            failed(entry, done.failure());
        } else if (entry.dropped || closed) {
            done.result().close();
        } else {
            try {
                entry.slot = textures.add(done.result());
                textureArrived(entry);
            } catch (RuntimeException e) {
                done.result().close();
                failed(entry, e);
            }
        }
        if (entry.dropped) {
            assets.release(entry.ref);
        }
    }

    /** {@code entry} could not go on the GPU, for {@code cause}: drawn untextured, reported, not waited for. */
    private void failed(TextureEntry entry, Throwable cause) {
        entry.failed = true;
        LOG.warn("Could not put {} on the GPU", entry.ref, cause);
        diagnostics.assetFailed(entry.ref, cause);
    }

    private void textureArrived(TextureEntry entry) {
        for (MaterialEntry material : entry.users) {
            markDirty(material);
        }
    }

    @AllowAllocation("a material or texture arrived")
    private void markDirty(MaterialEntry entry) {
        for (MaterialSet set : entry.users) {
            if (!set.dirty) {
                set.dirty = true;
                dirtySets.add(set);
            }
        }
    }

    /**
     * The renderer's material for {@code entry}: the default until it loads, untextured where textures are missing. Its
     * type by name, its parameters and features by field name (what the data lacks keeps the defaults); masked alpha
     * and double-sidedness follow, and blended alpha (drawn by the view's {@code Transparency} technique). Data the renderer cannot use
     * (a type not registered, values out of range) draws as the default; field names its type does not read are
     * ignored. Each problem is logged and reported to the {@link Diagnostics}.
     */
    @AllowAllocation("materials change rarely")
    private Material convert(MaterialEntry entry) {
        MaterialData d = entry == null || entry.data == null ? MaterialData.DEFAULT : entry.data;
        MaterialType type = materials.type(d.type());
        if (type == null) {
            problem(entry, "no material type " + d.type() + " is registered: it draws as the default");
            return Material.DEFAULT;
        }
        try {
            Set<String> read = new HashSet<>();
            Transforms transforms = new Transforms();
            Material material = new Material(type, type.fromValues().apply(new DataValues(d.values(), "", read,
                transforms))).doubleSided(d.doubleSided());
            if (d.alphaMode() == MaterialData.AlphaMode.MASK) {
                material = material.alphaMask(d.alphaCutoff());
            } else if (d.alphaMode() == MaterialData.AlphaMode.BLEND) {
                material = material.alphaBlend();
            }
            unread(entry, d.values(), read, "");
            for (String name : d.features()) {
                Set<String> readOfFeature = new HashSet<>();
                MaterialFeature feature = MaterialFeatures.fromValues(name, new DataValues(d.feature(name), "",
                    readOfFeature, transforms));
                if (feature == null) {
                    problem(entry, "no material feature " + name + ": it is shown without it");
                } else if (((type.allowedFeatures() | Material.EVERY_TYPES_FEATURES) & feature.feature()) == 0) {
                    problem(entry, "material type " + type.name() + " allows no " + name + ": it is shown without it");
                } else {
                    material = material.feature(feature);
                    unread(entry, d.feature(name), readOfFeature, name + ": ");
                }
            }
            TextureTransformParameters moved = transforms.feature();
            if (moved != null) {
                material = material.feature(moved);
            }
            return material;
        } catch (IllegalArgumentException e) {
            problem(entry, e.getMessage() + ": it draws as the default");
            return Material.DEFAULT;
        }
    }

    /** Reports the names of {@code values} that the type never read: names it does not have, ignored. */
    @AllowAllocation("materials change rarely")
    private void unread(MaterialEntry entry, MaterialData.Values values, Set<String> read, String where) {
        List<String> unknown = new ArrayList<>();
        for (String name : values.names()) {
            if (!read.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            java.util.Collections.sort(unknown);
            problem(entry, where + "no field " + String.join(", ", unknown) + " in its type: ignored");
        }
    }

    @AllowAllocation("materials change rarely")
    private void problem(MaterialEntry entry, String problem) {
        LOG.warn("{}: {}", entry.ref, problem);
        diagnostics.materialProblem(entry.ref, problem);
    }

    /** Material data's values as a generated record reads them: textures as the slots they have, nested by path. */
    @AllowAllocation("materials change rarely")
    private final class DataValues implements MaterialValues {
        private final MaterialData.Values values;
        private final String prefix;
        private final Set<String> read; // the names asked for, by path
        private final Transforms transforms; // the material's texture transforms, gathered as textures are read

        DataValues(MaterialData.Values values, String prefix, Set<String> read, Transforms transforms) {
            this.values = values;
            this.prefix = prefix;
            this.read = read;
            this.transforms = transforms;
        }

        private String name(String name) {
            read.add(prefix + name);
            return prefix + name;
        }

        @Override
        public float f32(String name, float fallback) {
            return values.f32(name(name), fallback);
        }

        @Override
        public int i32(String name, int fallback) {
            return values.i32(name(name), fallback);
        }

        @Override
        public Vec2 vec2(String name, Vec2 fallback) {
            float[] v = floats(name, 2, 2);
            return v == null ? fallback : new Vec2(v[0], v[1]);
        }

        @Override
        public Vec3 vec3(String name, Vec3 fallback) {
            float[] v = floats(name, 3, 3);
            return v == null ? fallback : new Vec3(v[0], v[1], v[2]);
        }

        @Override
        public Vec4 vec4(String name, Vec4 fallback) {
            float[] v = floats(name, 4, 4);
            return v == null ? fallback : new Vec4(v[0], v[1], v[2], v[3]);
        }

        /** A color as rgb (alpha as the fallback's) or rgba. */
        @Override
        public Color color(String name, Color fallback) {
            float[] v = floats(name, 3, 4);
            return v == null ? fallback : new Color(v[0], v[1], v[2], v.length == 4 ? v[3] : fallback.a());
        }

        private float[] floats(String name, int min, int max) {
            float[] v = values.floats(name(name));
            if (v != null && (v.length < min || v.length > max)) {
                throw new IllegalArgumentException(prefix + name + " is " + min + (max > min ? " to " + max : "")
                    + " floats, not " + v.length);
            }
            return v;
        }

        /**
         * The texture's slot, at the UV set {@code nameTexCoord} names (1: the second) and moved by the transform
         * {@code nameOffset}, {@code nameRotation}, {@code nameScale} give, as glTF's importer names them.
         */
        @Override
        public int texture(String name, boolean srgb, int fallback) {
            AssetRef<TextureData> ref = values.texture(name(name));
            int texCoord = values.i32(name(name + "TexCoord"), 0);
            float[] offset = values.floats(name(name + "Offset"));
            float rotation = values.f32(name(name + "Rotation"), 0);
            float[] scale = values.floats(name(name + "Scale"));
            if (ref == null) {
                return fallback;
            }
            int slot = slot(ref); // its color space is the texture data's own
            if (slot == SceneResources.NONE) {
                return slot;
            }
            if (texCoord == 1) {
                slot = TextureSampler.secondUv(slot);
            } else if (texCoord != 0) {
                throw new IllegalArgumentException(prefix + name + "TexCoord: UV sets 0 and 1, not " + texCoord);
            }
            if (offset != null || scale != null || rotation != 0) {
                slot = TextureSampler.transform(slot, transforms.add(prefix + name, offset, rotation, scale));
            }
            return slot;
        }

        @Override
        public MaterialValues nested(String name) {
            return new DataValues(values, prefix + name + ".", read, transforms);
        }
    }

    /** A material's texture transforms, as its textures name them: each distinct one once, up to eight. */
    @AllowAllocation("materials change rarely")
    static final class Transforms {
        private final java.util.List<float[]> transforms = new java.util.ArrayList<>(); // offset xy, rotation, scale xy

        /** The number (1 to 8) of the transform {@code offset}, {@code rotation}, {@code scale} (null: defaults). */
        int add(String texture, float[] offset, float rotation, float[] scale) {
            if (offset != null && offset.length != 2 || scale != null && scale.length != 2) {
                throw new IllegalArgumentException(texture + ": a transform's offset and scale are 2 floats each");
            }
            float[] t = {offset == null ? 0 : offset[0], offset == null ? 0 : offset[1], rotation,
                scale == null ? 1 : scale[0], scale == null ? 1 : scale[1]};
            for (int i = 0; i < transforms.size(); i++) {
                if (java.util.Arrays.equals(transforms.get(i), t)) {
                    return i + 1;
                }
            }
            if (transforms.size() == TextureSampler.TRANSFORMS) {
                throw new IllegalArgumentException(texture + ": a material has at most " + TextureSampler.TRANSFORMS
                    + " texture transforms");
            }
            transforms.add(t);
            return transforms.size();
        }

        /** The feature holding them; null for none. */
        TextureTransformParameters feature() {
            if (transforms.isEmpty()) {
                return null;
            }
            Vec4[] moves = new Vec4[TextureSampler.TRANSFORMS];
            float[] rotations = new float[TextureSampler.TRANSFORMS];
            for (int i = 0; i < moves.length; i++) {
                float[] t = i < transforms.size() ? transforms.get(i) : new float[] {0, 0, 0, 1, 1};
                moves[i] = new Vec4(t[0], t[1], t[3], t[4]);
                rotations[i] = t[2];
            }
            return new TextureTransformParameters(moves[0], moves[1], moves[2], moves[3], moves[4], moves[5], moves[6],
                moves[7], new Vec4(rotations[0], rotations[1], rotations[2], rotations[3]),
                new Vec4(rotations[4], rotations[5], rotations[6], rotations[7]));
        }
    }

    private int slot(AssetRef<TextureData> ref) {
        TextureEntry entry = ref == null ? null : textureEntries.get(ref);
        return entry == null ? NONE : entry.slot;
    }

    /** Hears of loads in the asset cache: assets waited for go to the GPU. */
    private final class Listener implements AssetCache.Listener {
        @Override
        public void loaded(AssetRef<?> ref, Object data) {
            if (ref.type() == RenderAssets.MESH) {
                Mesh mesh = meshes.get(ref);
                if (mesh != null && mesh.data == null) {
                    upload(mesh, (MeshData) data);
                }
            } else if (ref.type() == RenderAssets.MATERIAL) {
                MaterialEntry entry = materialEntries.get(ref);
                if (entry != null && entry.data == null) {
                    materialLoaded(entry, (MaterialData) data);
                }
            } else if (ref.type() == RenderAssets.TEXTURE) {
                TextureEntry entry = textureEntries.get(ref);
                if (entry != null && entry.slot == NONE) {
                    textureLoaded(entry, (TextureData) data);
                }
            }
        }

        @Override
        public void evicted(AssetRef<?> ref, Object data) {
            // What views use is held, so never evicted; nothing else is on the GPU.
        }

        @Override
        public void failed(AssetRef<?> ref, Throwable cause) {
            MaterialEntry material = materialEntries.get(ref);
            TextureEntry texture = textureEntries.get(ref);
            Mesh mesh = meshes.get(ref);
            if (mesh != null) {
                mesh.failed = true;
            }
            if (material != null) {
                material.failed = true; // not waited for
            }
            if (texture != null) {
                texture.failed = true;
            }
            if (mesh != null || material != null || texture != null) {
                diagnostics.assetFailed(ref, cause); // one a scene uses
            }
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The scene resources are closed");
        }
    }

    /**
     * Stops hearing the asset cache. Close the views first: what they still hold stays on the GPU and in the cache. The
     * pool, materials and textures stay open: they are the caller's. Meshes and textures being made ready on the workers
     * ({@link #preparingCount()}) are freed as the scheduler hands them over: drain it until none are before closing the
     * device.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            assets.removeListener(listener);
        }
    }
}
