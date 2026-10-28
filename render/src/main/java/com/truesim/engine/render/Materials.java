package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;

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
    private int count; // ids handed out so far, from 0
    private int live;
    private final boolean[] removed;
    private final int[] freeIds;
    private int freeCount;
    // Removed ids waiting for the frames that may read them.
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
        removed = new boolean[capacity];
        freeIds = new int[capacity];
        retiring = new int[capacity];
        retireAt = new long[capacity];
        table = FrameBuffer.create(device, (long) BYTES * capacity,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots);
        add(new Material());
    }

    /** Adds {@code material} (copied) and returns its id: a removed one no frame in flight reads, lowest first. */
    public int add(Material material) {
        releaseRetired();
        int id;
        if (freeCount > 0) {
            int lowest = 0;
            for (int i = 1; i < freeCount; i++) {
                if (freeIds[i] < freeIds[lowest]) {
                    lowest = i;
                }
            }
            id = freeIds[lowest];
            freeIds[lowest] = freeIds[--freeCount];
            removed[id] = false;
        } else if (count < capacity) {
            id = count++;
        } else {
            throw new IllegalStateException("The material table holds " + capacity + " materials");
        }
        write(id, material);
        live++;
        return id;
    }

    /** Replaces material {@code id} with {@code material} (copied). */
    public void set(int id, Material material) {
        if (!contains(id)) {
            throw new IllegalArgumentException("No material " + id);
        }
        write(id, material);
    }

    /**
     * Removes material {@code id} (not the default, 0): instances must no longer use it from the next frame on. Its id
     * is reused once no frame in flight can read it. Allocates nothing.
     */
    public void remove(int id) {
        if (id == 0 || !contains(id)) {
            throw new IllegalArgumentException("No material " + id + " to remove");
        }
        removed[id] = true;
        live--;
        retiring[retiringCount] = id;
        retireAt[retiringCount++] = table.slots().retireAt();
    }

    /** Whether {@code id} is a material of the table, added and not removed. */
    public boolean contains(int id) {
        return id >= 0 && id < count && !removed[id];
    }

    private void releaseRetired() {
        int kept = 0;
        for (int i = 0; i < retiringCount; i++) {
            if (table.slots().isFinished(retireAt[i])) {
                freeIds[freeCount++] = retiring[i];
            } else {
                retiring[kept] = retiring[i];
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
