package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.List;

/**
 * The material table shaders read, by id: 64 bytes per {@link Material}, as engine-render's {@code shading.slang}
 * lays it out. Material 0 is a light gray default, so meshes draw before anything is set up. Instances name a first
 * material ({@link MeshletRenderer#setInstance(int, int, int, float, float, float, float, float, float, float,
 * float, float, float)}); each submesh of the mesh takes the next.
 *
 * <p>Materials are read when the frame's commands run. Given a frame graph's {@link FrameSlots}, the table keeps a copy
 * per frame in flight; otherwise change a material only once the frames using it have finished.
 */
@AllowAllocation("created rarely")
public final class Materials implements AutoCloseable {

    static final int BYTES = 64;

    private final FrameBuffer table;
    private final int capacity;
    /** Ids in contiguous blocks: an add takes one, {@link #addAll} as many as materials. */
    private final RangeAllocator ids;
    /** Per id: the length of the block starting there (0: not a block's start), and whether it is in use. */
    private final int[] blockLength;
    private final boolean[] used;
    private int live;
    // Removed blocks waiting for the frames that may read them: their first ids and when they are free.
    private final int[] retiring;
    private final long[] retireAt;
    private int retiringCount;

    /**
     * A table of up to {@code capacity} materials, the default one included, in one copy: for frames the caller waits
     * for before changing materials.
     */
    public Materials(VulkanDevice device, int capacity) {
        this(device, capacity, FrameSlots.single());
    }

    /**
     * A table of up to {@code capacity} materials, the default one included, a copy per frame of {@code slots} (a frame
     * graph's), so materials may change while earlier frames are drawn.
     */
    public Materials(VulkanDevice device, int capacity, FrameSlots slots) {
        if (capacity < 1) {
            throw new IllegalArgumentException("At least the default material: " + capacity);
        }
        this.capacity = capacity;
        ids = new RangeAllocator(capacity);
        blockLength = new int[capacity];
        used = new boolean[capacity];
        retiring = new int[capacity];
        retireAt = new long[capacity];
        table = FrameBuffer.create(device, (long) BYTES * capacity,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots);
        add(new Material());
    }

    /**
     * Adds {@code material} (copied) and returns its id: the lowest free one, ids of removed materials once no frame in
     * flight reads them.
     */
    public int add(Material material) {
        int id = take(1);
        if (id < 0) {
            throw new IllegalStateException("The material table holds " + capacity + " materials: no room");
        }
        write(id, material);
        return id;
    }

    /**
     * Adds {@code materials} (copied) under consecutive ids and returns the first, e.g. one per submesh of a mesh, which
     * an instance names by the first: submesh s takes the first id plus s. {@link #remove} of the first removes them all.
     */
    public int addAll(List<Material> materials) {
        int first = tryAddAll(materials);
        if (first < 0) {
            throw new IllegalStateException("The material table holds " + capacity + " materials: no room for "
                + materials.size() + " more in a row");
        }
        return first;
    }

    /**
     * Like {@link #addAll}, or returns -1 if the table has no room for them in a row now: room may come back as removed
     * blocks leave the frames in flight. Allocates nothing when there is no room.
     */
    public int tryAddAll(List<Material> materials) {
        if (materials.isEmpty()) {
            throw new IllegalArgumentException("No materials to add");
        }
        int first = take(materials.size());
        for (int i = 0; first >= 0 && i < materials.size(); i++) {
            write(first + i, materials.get(i));
        }
        return first;
    }

    /**
     * Whether {@link #tryAddAll} would find {@code length} ids in a row now, counting removed blocks no frame in flight
     * reads any more. Allocates nothing.
     */
    public boolean hasRoom(int length) {
        releaseRetired();
        return ids.fits(length);
    }

    /** The first of {@code length} free ids in a row, taken, or -1 with no room. */
    private int take(int length) {
        releaseRetired();
        long first = ids.allocate(length, 1);
        if (first < 0) {
            return -1;
        }
        int id = (int) first;
        blockLength[id] = length;
        for (int i = id; i < id + length; i++) {
            used[i] = true;
        }
        live += length;
        return id;
    }

    /** Replaces material {@code id} with {@code material} (copied); any id of a block. */
    public void set(int id, Material material) {
        if (!contains(id)) {
            throw new IllegalArgumentException("No material " + id);
        }
        write(id, material);
    }

    /**
     * Removes material {@code id} (not the default, 0), or the block {@link #addAll} returned {@code id} for: instances
     * must no longer use them from the next frame on. The ids are reused once no frame in flight can read them.
     * Allocates nothing.
     */
    public void remove(int id) {
        if (id <= 0 || id >= capacity || blockLength[id] == 0 || !used[id]) {
            throw new IllegalArgumentException("No material " + id + " to remove, or not the first of its block");
        }
        int length = blockLength[id];
        for (int i = id; i < id + length; i++) {
            used[i] = false;
        }
        live -= length;
        retiring[retiringCount] = id;
        retireAt[retiringCount++] = table.slots().retireAt();
    }

    /** Whether {@code id} is a material of the table, added and not removed. */
    public boolean contains(int id) {
        return id >= 0 && id < capacity && used[id];
    }

    /** Gives removed blocks no frame in flight reads back, oldest first. */
    private void releaseRetired() {
        int kept = 0;
        for (int i = 0; i < retiringCount; i++) {
            int id = retiring[i];
            if (table.slots().isFinished(retireAt[i])) {
                ids.release(id, blockLength[id]);
                blockLength[id] = 0;
            } else {
                retiring[kept] = id;
                retireAt[kept++] = retireAt[i];
            }
        }
        retiringCount = kept;
    }

    private void write(int id, Material material) {
        long o = (long) BYTES * id;
        table.putFloat(o, material.r);
        table.putFloat(o + 4, material.g);
        table.putFloat(o + 8, material.b);
        table.putFloat(o + 12, material.a);
        table.putFloat(o + 16, material.er);
        table.putFloat(o + 20, material.eg);
        table.putFloat(o + 24, material.eb);
        table.putFloat(o + 28, material.roughness);
        table.putFloat(o + 32, material.metallic);
        table.putFloat(o + 36, material.normalScale);
        table.putFloat(o + 40, material.occlusion);
        table.putFloat(o + 44, 0);
        table.putInt(o + 48, material.baseColorTexture);
        table.putInt(o + 52, material.normalTexture);
        table.putInt(o + 56, material.ormTexture);
        table.putInt(o + 60, material.emissiveTexture);
    }

    /** How many materials the table holds, the default one included. */
    public int count() {
        return live;
    }

    long deviceAddress() {
        return table.deviceAddress();
    }

    @Override
    public void close() {
        table.close();
    }
}
