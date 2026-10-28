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
    private int count;

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
        table = FrameBuffer.create(device, (long) BYTES * capacity,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots);
        add(new Material());
    }

    /** Adds {@code material} (copied) and returns its id. */
    public int add(Material material) {
        if (count == capacity) {
            throw new IllegalStateException("The material table holds " + capacity + " materials");
        }
        write(count, material);
        return count++;
    }

    /** Replaces material {@code id} with {@code material} (copied). */
    public void set(int id, Material material) {
        if (id < 0 || id >= count) {
            throw new IllegalArgumentException("No material " + id);
        }
        write(id, material);
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
        return count;
    }

    long deviceAddress() {
        return table.deviceAddress();
    }

    @Override
    public void close() {
        table.close();
    }
}
