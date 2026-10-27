package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO;
import static org.lwjgl.util.vma.Vma.vmaCreateBuffer;
import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;

import com.truesim.annotations.AllowAllocation;
import java.lang.foreign.MemorySegment;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

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

    public long handle() {
        return handle;
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
