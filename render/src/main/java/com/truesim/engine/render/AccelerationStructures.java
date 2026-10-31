package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_FORMAT_FEATURE_ACCELERATION_STRUCTURE_VERTEX_BUFFER_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_PROPERTIES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCreateAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkDestroyAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UNORM;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Vulkan;
import com.truesim.engine.vulkan.VulkanDevice;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;

/**
 * What {@link RayTracing}'s bottom and top levels share: making and destroying acceleration structures and their
 * scratch memory, the device's limits for them, and freeing what frames in flight may still trace once none does.
 */
@AllowAllocation("set up once")
final class AccelerationStructures {

    /** An acceleration structure and the buffer it lives in. */
    record Structure(GpuBuffer buffer, long handle, long address) { }

    /** Something to free once no frame in flight reads it ({@link FrameSlots#isFinished}). */
    private record Retired(Runnable free, long at) {
    }

    private final VulkanDevice device;
    private final FrameSlots slots;
    private final boolean deferred;
    private final long scratchAlignment;
    private final boolean quantized;
    private final List<Retired> retired = new ArrayList<>();

    /** For {@code device}'s structures, freed after the frames of {@code slots} with {@code deferred}, else at once. */
    AccelerationStructures(VulkanDevice device, FrameSlots slots, boolean deferred) {
        this.device = device;
        this.slots = slots;
        this.deferred = deferred;
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceAccelerationStructurePropertiesKHR properties =
                VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_PROPERTIES_KHR);
            vkGetPhysicalDeviceProperties2(device.gpu().handle(), VkPhysicalDeviceProperties2.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2).pNext(properties.address()));
            scratchAlignment = properties.minAccelerationStructureScratchOffsetAlignment();
            VkFormatProperties format = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(device.gpu().handle(), VK_FORMAT_R16G16B16A16_UNORM, format);
            quantized = (format.bufferFeatures() & VK_FORMAT_FEATURE_ACCELERATION_STRUCTURE_VERTEX_BUFFER_BIT_KHR) != 0;
        }
    }

    /** Whether bottom levels can read the pool's quantized positions as they are (unorm16 vertices). */
    boolean quantized() {
        return quantized;
    }

    /** Whether frames are in flight: builds go through them, and what they trace is freed once they are done. */
    boolean deferred() {
        return deferred;
    }

    /** Frees {@code free} once no frame in flight reads what it frees: at once without frames in flight. */
    void retire(Runnable free) {
        if (deferred) {
            retired.add(new Retired(free, slots.retireAt()));
        } else {
            free.run(); // no frames in flight: the caller waits for them
        }
    }

    /** Frees what no frame in flight reads any more. Allocates nothing. */
    void release() {
        for (int i = retired.size() - 1; i >= 0; i--) {
            if (slots.isFinished(retired.get(i).at())) {
                retired.remove(i).free().run();
            }
        }
    }

    /** {@code buffer}'s address rounded up to 16 bytes, as a build transform's and instance data's must be. */
    static long align16(GpuBuffer buffer) {
        return (buffer.deviceAddress() + 15) & ~15L;
    }

    /** Where in {@code buffer} its 16-byte aligned address falls. */
    static long alignedOffset(GpuBuffer buffer) {
        return align16(buffer) - buffer.deviceAddress();
    }

    static VkAccelerationStructureGeometryKHR.Buffer instancesGeometry(MemoryStack stack, long address) {
        VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
            .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
            .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
        geometry.geometry().instances()
            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR)
            .arrayOfPointers(false);
        geometry.geometry().instances().data().deviceAddress(address);
        return geometry;
    }

    static VkAccelerationStructureBuildGeometryInfoKHR buildInfo(MemoryStack stack, int type, int flags,
        VkAccelerationStructureGeometryKHR.Buffer geometry) {
        return VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
            .type(type)
            .flags(flags)
            .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
            .geometryCount(1)
            .pGeometries(geometry);
    }

    VkAccelerationStructureBuildSizesInfoKHR sizes(MemoryStack stack,
        VkAccelerationStructureBuildGeometryInfoKHR info, int primitives) {
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);
        vkGetAccelerationStructureBuildSizesKHR(device.handle(),
            org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info,
            stack.ints(primitives), sizes);
        return sizes;
    }

    Structure create(long size, int type) {
        // Traced by the graphics queue and by async compute (ray-traced shadows): shared by both, no ownership to move.
        GpuBuffer buffer = GpuBuffer.deviceLocal(device, size, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, device.graphics().family(), device.compute().family());
        try (MemoryStack stack = stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            Vulkan.check(vkCreateAccelerationStructureKHR(device.handle(), VkAccelerationStructureCreateInfoKHR
                .calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                .buffer(buffer.handle())
                .size(size)
                .type(type), null, handle), "Could not create an acceleration structure");
            long address = vkGetAccelerationStructureDeviceAddressKHR(device.handle(),
                VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR)
                    .accelerationStructure(handle.get(0)));
            return new Structure(buffer, handle.get(0), address);
        }
    }

    /** Scratch memory with room to align its start. */
    GpuBuffer scratch(long size) {
        return GpuBuffer.deviceLocal(device, size + scratchAlignment, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
    }

    /** {@code address} rounded up to the scratch alignment. */
    long align(long address) {
        return (address + scratchAlignment - 1) / scratchAlignment * scratchAlignment;
    }

    void destroy(Structure structure) {
        vkDestroyAccelerationStructureKHR(device.handle(), structure.handle(), null);
        structure.buffer().close();
    }

    /** Frees everything retired: the device is done with it. */
    void close() {
        for (Retired r : retired) {
            r.free().run();
        }
        retired.clear();
    }
}
