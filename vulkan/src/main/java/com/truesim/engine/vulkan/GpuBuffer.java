package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO;
import static org.lwjgl.util.vma.Vma.vmaCreateBuffer;
import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO;
import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;

import com.truesim.annotations.AllowAllocation;
import java.lang.foreign.MemorySegment;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;

/**
 * A buffer in GPU memory, allocated by VMA. A host-visible buffer stays mapped: {@link #memory()} is a
 * {@link MemorySegment} over it, so data can be written in place, e.g. by common-math's {@code ElementBuffer.wrap}.
 * Device-local buffers are filled by copies from host-visible ones.
 */
@AllowAllocation("created rarely")
public final class GpuBuffer implements AutoCloseable {

    private final VulkanDevice device;
    private final long handle;
    private final long allocation;
    private final long size;
    private final MemorySegment memory;
    private final long deviceAddress;
    private boolean closed;

    @SuppressWarnings("restricted") // wraps the mapped memory VMA returned, of exactly this size
    private GpuBuffer(VulkanDevice device, long size, int usage, boolean hostVisible) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive: " + size);
        }
        this.device = device;
        this.size = size;
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_AUTO)
                .flags(hostVisible ? VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT : 0);
            LongBuffer buffer = stack.mallocLong(1);
            PointerBuffer allocated = stack.mallocPointer(1);
            VmaAllocationInfo result = VmaAllocationInfo.calloc(stack);
            Vulkan.check(vmaCreateBuffer(device.allocator(), info, allocationInfo, buffer, allocated, result),
                "Could not allocate a buffer of " + size + " bytes");
            this.handle = buffer.get(0);
            this.allocation = allocated.get(0);
            this.memory = hostVisible ? MemorySegment.ofAddress(result.pMappedData()).reinterpret(size) : null;
            this.deviceAddress = (usage & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) == 0 ? 0
                : vkGetBufferDeviceAddress(device.handle(), VkBufferDeviceAddressInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    .buffer(handle));
        }
    }

    /** A buffer the CPU writes and reads directly, e.g. for uploads, readbacks and per-frame data. */
    public static GpuBuffer hostVisible(VulkanDevice device, long size, int usage) {
        return new GpuBuffer(device, size, usage, true);
    }

    /** A buffer in fast GPU memory, filled by transfers. */
    public static GpuBuffer deviceLocal(VulkanDevice device, long size, int usage) {
        return new GpuBuffer(device, size, usage, false);
    }

    /**
     * A device-local buffer holding {@code data}, copied through a staging buffer on the graphics queue, so the
     * graphics and compute work that uses it needs no queue ownership transfer: for data uploaded once, such as meshes. {@code usage} gets {@code TRANSFER_DST} added.
     */
    public static GpuBuffer upload(VulkanDevice device, MemorySegment data, int usage) {
        long bytes = data.byteSize();
        GpuBuffer target = deviceLocal(device, bytes, usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        try (GpuBuffer staging = hostVisible(device, bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
            MemorySegment.copy(data, 0, staging.memory, 0, bytes);
            device.submitAndWait(device.graphics(), commands -> {
                try (MemoryStack stack = stackPush()) {
                    vkCmdCopyBuffer(commands, staging.handle, target.handle, VkBufferCopy.calloc(1, stack).size(bytes));
                }
            });
        } catch (RuntimeException e) {
            target.close();
            throw e;
        }
        return target;
    }

    /**
     * Writes {@code data} at {@code offset}: straight into a host-visible buffer, or through a staging buffer on the
     * graphics queue into a device-local one, waiting until it is there. For setup work such as adding meshes.
     */
    public void write(long offset, MemorySegment data) {
        long bytes = data.byteSize();
        if (offset < 0 || offset + bytes > size) {
            throw new IllegalArgumentException("Writing " + bytes + " bytes at " + offset + " overruns " + size);
        }
        if (bytes == 0) {
            return;
        }
        if (memory != null) {
            MemorySegment.copy(data, 0, memory, offset, bytes);
            return;
        }
        try (GpuBuffer staging = hostVisible(device, bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
            MemorySegment.copy(data, 0, staging.memory, 0, bytes);
            device.submitAndWait(device.graphics(), commands -> {
                try (MemoryStack stack = stackPush()) {
                    vkCmdCopyBuffer(commands, staging.handle, handle, VkBufferCopy.calloc(1, stack)
                        .dstOffset(offset).size(bytes));
                }
            });
        }
    }

    public long handle() {
        return handle;
    }

    /**
     * The buffer's address for shaders, e.g. passed in push constants; the buffer needs
     * {@code VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT}.
     */
    public long deviceAddress() {
        if (deviceAddress == 0) {
            throw new IllegalStateException("The buffer was made without SHADER_DEVICE_ADDRESS usage");
        }
        return deviceAddress;
    }

    public long size() {
        return size;
    }

    /** The mapped memory of a host-visible buffer. */
    public MemorySegment memory() {
        if (memory == null) {
            throw new IllegalStateException("A device-local buffer is not mapped");
        }
        return memory;
    }

    public boolean isHostVisible() {
        return memory != null;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            vmaDestroyBuffer(device.allocator(), handle, allocation);
        }
    }
}
