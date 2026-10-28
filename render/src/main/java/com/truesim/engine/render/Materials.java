package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * The material table shaders read, by id: 64 bytes per {@link Material}, as engine-render's {@code shading.slang}
 * lays it out. Material 0 is a light gray default, so meshes draw before anything is set up. Instances name a first
 * material ({@link MeshletRenderer#setInstance(int, int, int, float, float, float, float, float, float, float,
 * float, float, float)}); each submesh of the mesh takes the next.
 *
 * <p>Materials are read when the frame's commands run: change one only once the frames using it have finished.
 */
@AllowAllocation("created rarely")
public final class Materials implements AutoCloseable {

    static final int BYTES = 64;
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final GpuBuffer table;
    private final int capacity;
    private int count;

    /** A table of up to {@code capacity} materials, the default one included. */
    public Materials(VulkanDevice device, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("At least the default material: " + capacity);
        }
        this.capacity = capacity;
        table = GpuBuffer.hostVisible(device, (long) BYTES * capacity,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
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
        MemorySegment m = table.memory().asSlice((long) BYTES * id, BYTES);
        m.set(F32, 0, material.r);
        m.set(F32, 4, material.g);
        m.set(F32, 8, material.b);
        m.set(F32, 12, material.a);
        m.set(F32, 16, material.er);
        m.set(F32, 20, material.eg);
        m.set(F32, 24, material.eb);
        m.set(F32, 28, material.roughness);
        m.set(F32, 32, material.metallic);
        m.set(F32, 36, material.normalScale);
        m.set(F32, 40, material.occlusion);
        m.set(F32, 44, 0);
        m.set(U32, 48, material.baseColorTexture);
        m.set(U32, 52, material.normalTexture);
        m.set(U32, 56, material.ormTexture);
        m.set(U32, 60, material.emissiveTexture);
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
