package com.truesim.engine.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.Textures;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.TextureData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * no frame in flight draws it) and released in the cache when the last scene lets go. Materials draw as the default until
 * they load, and untextured where their textures have not loaded yet. Give the pool, materials and textures the frame
 * slots of every graph drawing them (shared slots for several graphs). Use it on the main thread, the asset cache's.
 */
public final class SceneResources implements AutoCloseable {

    static final int NONE = -1;

    private final AssetCache assets;
    private final GeometryPool pool;
    private final Materials materials;
    private final Textures textures;
    private final AssetCache.Listener listener = new Listener();
    private final Map<AssetRef<MeshData>, Mesh> meshes = new HashMap<>();
    private final Map<MaterialKey, MaterialSet> sets = new HashMap<>();
    private final Map<AssetRef<MaterialData>, MaterialEntry> materialEntries = new HashMap<>();
    private final Map<AssetRef<TextureData>, TextureEntry> textureEntries = new HashMap<>();
    private final List<MaterialSet> dirtySets = new ArrayList<>();
    /** Loaded meshes the pool had no room for: tried again as views update, as room frees. */
    private final List<Mesh> waitingForRoom = new ArrayList<>();
    private boolean closed;

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
        final List<TextureEntry> textures = new ArrayList<>(4);
        final List<MaterialSet> users = new ArrayList<>();

        MaterialEntry(AssetRef<MaterialData> ref) {
            this.ref = ref;
        }
    }

    /** A texture some materials use: its bindless slot once uploaded. */
    private static final class TextureEntry {
        final AssetRef<TextureData> ref;
        int slot = NONE;
        final List<MaterialEntry> users = new ArrayList<>();

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

    /**
     * Loaded meshes waiting for room in the geometry pool: they draw once it has room, e.g. when removed meshes have
     * left the frames in flight.
     */
    public int waitingForRoomCount() {
        return waitingForRoom.size();
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
            mesh = new Mesh(ref);
            meshes.put(ref, mesh);
            mesh.users.add(user);
            assets.acquire(ref);
            MeshData data = assets.get(ref);
            if (data != null) {
                upload(mesh, data);
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
            } else {
                waitingForRoom.remove(mesh);
            }
            assets.release(mesh.ref);
        }
    }

    @AllowAllocation("growing the list of meshes waiting for room")
    private void upload(Mesh mesh, MeshData data) {
        mesh.data = data;
        if (!tryUpload(mesh)) {
            waitingForRoom.add(mesh);
        }
    }

    /** Puts {@code mesh} in the pool if it has room, and tells its users. Allocates nothing. */
    private boolean tryUpload(Mesh mesh) {
        mesh.id = pool.tryAdd(mesh.data);
        if (mesh.id == NONE) {
            return false;
        }
        for (int i = 0; i < mesh.users.size(); i++) {
            mesh.users.get(i).uploaded(mesh);
        }
        return true;
    }

    // ---------------------------------------------------------------- materials and textures

    /**
     * Holds the block of material ids for {@code materials} on a mesh of {@code slots} submeshes: made and filled the
     * first time; null while the material table has no room for it (try again later). Allocates nothing when the
     * block exists.
     */
    MaterialSet acquireSet(List<AssetRef<MaterialData>> materials, int slots) {
        checkOpen();
        lookup.materials = materials;
        lookup.slots = slots;
        MaterialSet set = sets.get(lookup);
        lookup.materials = List.of(); // the probe holds no list
        if (set == null) {
            if (!this.materials.hasRoom(slots)) {
                return null; // checked first: a block waiting for room is not built every update
            }
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

    /**
     * Brings the GPU up to date, called by views as they update: meshes waiting for room go in where it has freed, and
     * material blocks whose materials or textures arrived are written.
     */
    void flush() {
        for (int i = 0; i < waitingForRoom.size(); ) { // the longest waiting first
            if (tryUpload(waitingForRoom.get(i))) {
                waitingForRoom.remove(i);
            } else {
                i++;
            }
        }
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
            entry = new MaterialEntry(ref);
            materialEntries.put(ref, entry);
            assets.acquire(ref);
            MaterialData data = assets.get(ref);
            if (data != null) {
                materialLoaded(entry, data);
            }
        }
        if (!entry.users.contains(set)) {
            entry.users.add(set);
        }
        return entry;
    }

    private void releaseMaterial(MaterialEntry entry, MaterialSet set) {
        entry.users.remove(set);
        if (!entry.users.isEmpty()) {
            return;
        }
        materialEntries.remove(entry.ref);
        for (TextureEntry texture : entry.textures) {
            texture.users.remove(entry);
            if (texture.users.isEmpty()) {
                textureEntries.remove(texture.ref);
                if (texture.slot != NONE) {
                    textures.remove(texture.slot);
                }
                assets.release(texture.ref);
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
                texture = new TextureEntry(ref);
                textureEntries.put(ref, texture);
                assets.acquire(ref);
                TextureData texels = assets.get(ref);
                if (texels != null) {
                    textureLoaded(texture, texels);
                }
            }
            texture.users.add(entry);
            entry.textures.add(texture);
        }
        markDirty(entry);
    }

    private void textureLoaded(TextureEntry entry, TextureData data) {
        entry.slot = data.colorSpace() == TextureData.ColorSpace.SRGB
            ? textures.color(data.width(), data.height(), data.texels())
            : textures.data(data.width(), data.height(), data.texels());
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

    /** The renderer's material for {@code entry}: the default until it loads, untextured where textures are missing. */
    @AllowAllocation("materials change rarely")
    private Material convert(MaterialEntry entry) {
        MaterialData d = entry == null || entry.data == null ? MaterialData.DEFAULT : entry.data;
        return new Material().baseColor(d.baseColor(0), d.baseColor(1), d.baseColor(2))
            .emissive(d.emissive(0), d.emissive(1), d.emissive(2))
            .roughness(d.roughness()).metallic(d.metallic())
            .baseColorTexture(slot(d.baseColorTexture()))
            .normalTexture(slot(d.normalTexture()), d.normalScale())
            .ormTexture(slot(d.ormTexture()), d.occlusionStrength())
            .emissiveTexture(slot(d.emissiveTexture()));
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
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The scene resources are closed");
        }
    }

    /**
     * Stops hearing the asset cache. Close the views first: what they still hold stays on the GPU and in the cache. The
     * pool, materials and textures stay open.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            assets.removeListener(listener);
        }
    }
}
