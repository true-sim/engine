package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO;
import static org.lwjgl.util.vma.Vma.vmaCreateBuffer;
import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_CONCURRENT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO;
import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;

import com.truesim.annotations.AllowAllocation;
import java.lang.foreign.Arena;
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
 * Device-local buffers are filled by copies from host-visible ones. Once closed, its memory, handle and device address
 * are no longer handed out, and segments over the memory throw.
 */
@AllowAllocation("created rarely")
public final class GpuBuffer implements AutoCloseable, DeviceBuffer {

    private final VulkanDevice device;
    private final long handle;
    private final long allocation;
    private final long size;
    private final boolean concurrent; // shared by several queue families, without ownership transfers
    private final Arena mapping; // closed with the buffer, so the mapped memory cannot be used after it
    private final MemorySegment memory;
    private final long deviceAddress;
    private boolean closed;

    private GpuBuffer(VulkanDevice device, long size, int usage, boolean hostVisible, int... families) {
        this(device, size, usage, hostVisible ? VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT : 0, families);
    }

    @SuppressWarnings("restricted") // wraps the mapped memory VMA returned, of exactly this size
    private GpuBuffer(VulkanDevice device, long size, int usage, int hostAccess, int... families) {
        boolean hostVisible = hostAccess != 0;
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive: " + size);
        }
        this.device = device;
        this.size = size;
        this.concurrent = families.length > 1;
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            if (families.length > 1) {
                info.sharingMode(VK_SHARING_MODE_CONCURRENT).pQueueFamilyIndices(stack.ints(families));
            }
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_AUTO)
                .flags(hostVisible ? hostAccess | VMA_ALLOCATION_CREATE_MAPPED_BIT : 0)
                // Coherent, as nothing flushes or invalidates: writes reach the GPU and its writes the CPU without.
                .requiredFlags(hostVisible ? VK_MEMORY_PROPERTY_HOST_COHERENT_BIT : 0);
            LongBuffer buffer = stack.mallocLong(1);
            PointerBuffer allocated = stack.mallocPointer(1);
            VmaAllocationInfo result = VmaAllocationInfo.calloc(stack);
            Vulkan.check(vmaCreateBuffer(device.allocator(), info, allocationInfo, buffer, allocated, result),
                "Could not allocate a buffer of " + size + " bytes");
            this.handle = buffer.get(0);
            this.allocation = allocated.get(0);
            this.mapping = hostVisible ? Arena.ofShared() : null;
            this.memory = hostVisible ? MemorySegment.ofAddress(result.pMappedData()).reinterpret(size, mapping, null)
                : null;
            this.deviceAddress = (usage & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) == 0 ? 0
                : vkGetBufferDeviceAddress(device.handle(), VkBufferDeviceAddressInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    .buffer(handle));
        }
        device.track(this);
    }

    /** A buffer the CPU writes and reads directly, e.g. for uploads, readbacks and per-frame data. */
    public static GpuBuffer hostVisible(VulkanDevice device, long size, int usage) {
        return new GpuBuffer(device, size, usage, true);
    }

    /**
     * Like {@link #hostVisible(VulkanDevice, long, int)}, used by the queues of {@code families} at once (concurrent
     * sharing), e.g. data the graphics queue and async compute both read.
     */
    public static GpuBuffer hostVisible(VulkanDevice device, long size, int usage, int... families) {
        return new GpuBuffer(device, size, usage, true, java.util.Arrays.stream(families).distinct().toArray());
    }

    /**
     * A buffer the CPU only writes, in order (e.g. with {@code MemorySegment.copy}), and the GPU reads: write-combined
     * memory, or device-local memory the CPU can reach where the GPU has it (resizable BAR). Not for reading back.
     */
    public static GpuBuffer hostWritten(VulkanDevice device, long size, int usage) {
        return new GpuBuffer(device, size, usage, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
    }

    /** Like {@link #hostWritten(VulkanDevice, long, int)}, used by the queues of {@code families} at once. */
    public static GpuBuffer hostWritten(VulkanDevice device, long size, int usage, int... families) {
        return new GpuBuffer(device, size, usage, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT,
            java.util.Arrays.stream(families).distinct().toArray());
    }

    /** A buffer in fast GPU memory, filled by transfers. */
    public static GpuBuffer deviceLocal(VulkanDevice device, long size, int usage) {
        return new GpuBuffer(device, size, usage, false);
    }

    /**
     * A buffer in fast GPU memory used by the queues of {@code families} at once (concurrent sharing, so no ownership
     * transfers), e.g. by graphics and async compute; one family is the same as {@link #deviceLocal(VulkanDevice, long,
     * int)}.
     */
    public static GpuBuffer deviceLocal(VulkanDevice device, long size, int usage, int... families) {
        int[] distinct = java.util.Arrays.stream(families).distinct().toArray();
        return new GpuBuffer(device, size, usage, false, distinct);
    }

    /**
     * A device-local buffer holding {@code data}, copied through a staging buffer on the graphics queue, so the
     * graphics and compute work that uses it needs no queue ownership transfer: for data uploaded once, such as meshes. {@code usage} gets {@code TRANSFER_DST} added.
     */
    public static GpuBuffer upload(VulkanDevice device, MemorySegment data, int usage) {
        long bytes = data.byteSize();
        GpuBuffer target = deviceLocal(device, bytes, usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        try (GpuBuffer staging = hostWritten(device, bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) { // written once, in order
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
        try (GpuBuffer staging = hostWritten(device, bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
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
        checkOpen();
        return handle;
    }

    /**
     * The buffer's address for shaders, e.g. passed in push constants; the buffer needs
     * {@code VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT}.
     */
    @Override
    public long deviceAddress() {
        checkOpen();
        if (deviceAddress == 0) {
            throw new IllegalStateException("The buffer was made without SHADER_DEVICE_ADDRESS usage");
        }
        return deviceAddress;
    }

    /** Whether the buffer has a device address: made with {@code VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT}. */
    public boolean hasDeviceAddress() {
        return deviceAddress != 0;
    }

    /**
     * Whether queues of several families use the buffer at once (concurrent sharing), so none takes it over: e.g. the
     * graphics queue and an async compute queue.
     */
    public boolean concurrent() {
        return concurrent;
    }

    public long size() {
        return size;
    }

    /** The mapped memory of a host-visible buffer, usable until the buffer closes. */
    public MemorySegment memory() {
        checkOpen();
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
            if (mapping != null) {
                mapping.close(); // segments over the mapping throw from now on
            }
            vmaDestroyBuffer(device.allocator(), handle, allocation);
            device.untrack(this);
        }
    }

    @Override
    public String toString() {
        return "GpuBuffer of " + size + " bytes";
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The buffer is closed");
        }
    }
}
