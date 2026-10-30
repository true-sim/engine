package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.nvkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_MEMORY_BARRIER_2;
import static org.lwjgl.vulkan.VK13.nvkCmdPipelineBarrier2;

import com.truesim.annotations.AllowAllocation;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

/**
 * Uploads into device-local resources without waiting for the GPU: the data is copied into a staging buffer at once,
 * and the copy into its target (or any commands reading the staging buffer, such as a texture's upload and mip chain)
 * is recorded at the start of the next frame of its {@link FrameSlots}, before any pass of that frame
 * ({@link FrameSlots#recordStart}, which a frame graph calls). What is uploaded is resident from that frame on, so
 * draw it from the next frame recorded. Staging buffers are freed once no frame in flight reads them.
 *
 * <pre>{@code
 * FrameUploads uploads = new FrameUploads(device, graph.frameSlots());
 * uploads.write(positions, offset, meshPositions);   // returns at once; copied when the next frame records
 * }</pre>
 *
 * <p>With {@link FrameSlots#single()} there are no frames to defer to: uploads run at once and wait, as for callers
 * that wait for the GPU between frames. Use it from one thread, the one recording frames.
 */
@AllowAllocation("uploads are rare next to frames: a staging buffer each")
public final class FrameUploads implements AutoCloseable {

    private final VulkanDevice device;
    private final FrameSlots slots;
    private final boolean deferred;
    private final FrameSlots.FrameStart start = this::flush;
    // Waiting for the next frame: staging buffers, and for each a target and offset, or commands of its own.
    private final List<GpuBuffer> staging = new ArrayList<>();
    private final List<GpuBuffer> targets = new ArrayList<>();
    private long[] offsets = new long[8]; // by index of staging: no boxing
    private final List<BiConsumer<VkCommandBuffer, GpuBuffer>> commands = new ArrayList<>();
    // Recorded: staging buffers the frames in flight may still read, and when they are free.
    private final Retiring retiring; // staging buffers copied from, until their frames finish
    private long pendingBytes;
    private boolean closed;

    /** Uploads deferred to the frames of {@code slots}, or run at once for {@link FrameSlots#single()}. */
    public FrameUploads(VulkanDevice device, FrameSlots slots) {
        this.device = Objects.requireNonNull(device, "device");
        this.slots = Objects.requireNonNull(slots, "slots");
        this.retiring = new Retiring(slots);
        this.deferred = !slots.isSingle();
        if (deferred) {
            slots.register(start);
        }
    }

    /** Whether uploads wait for the next frame; otherwise they are done when the call returns. */
    public boolean deferred() {
        return deferred;
    }

    /**
     * Copies {@code data} into {@code target} (device-local, with transfer-destination usage) at {@code offset}: in the
     * next frame, or at once without frames to defer to. {@code data} may be reused when this returns.
     */
    public void write(GpuBuffer target, long offset, MemorySegment data) {
        write(target, offset, data, data.byteSize());
    }

    /**
     * Like {@link #write(GpuBuffer, long, MemorySegment)}, writing {@code bytes} bytes: {@code data}, then zeros up to
     * {@code bytes} (copies move whole words, so data of a length not a multiple of 4 is padded here).
     */
    public void write(GpuBuffer target, long offset, MemorySegment data, long bytes) {
        checkOpen();
        if (offset < 0 || bytes < data.byteSize() || offset + bytes > target.size()) {
            throw new IllegalArgumentException("Writing " + bytes + " bytes (" + data.byteSize() + " given) at "
                + offset + " overruns " + target.size());
        }
        if (bytes == 0) {
            return;
        }
        GpuBuffer buffer = stage(data, bytes);
        if (!deferred) {
            try (buffer) {
                device.submitAndWait(device.graphics(), commands -> copy(commands, buffer, target, offset));
            }
            return;
        }
        queue(buffer, target, offset, null);
    }

    /**
     * Records {@code record} with a staging buffer holding {@code data}, in the next frame (or at once, waiting): for
     * uploads that are more than a copy into a buffer, such as an image and its mip chain. It runs in the frame's first
     * commands, so later passes may use what it wrote once a barrier from all commands' writes is in place, which the
     * uploads record after it.
     */
    public void record(MemorySegment data, BiConsumer<VkCommandBuffer, GpuBuffer> record) {
        checkOpen();
        Objects.requireNonNull(record, "record");
        GpuBuffer buffer = stage(data, data.byteSize());
        if (!deferred) {
            try (buffer) {
                device.submitAndWait(device.graphics(), commands -> record.accept(commands, buffer));
            }
            return;
        }
        queue(buffer, null, 0, record);
    }

    /** How many uploads wait for the next frame, and their bytes. */
    public int pendingCount() {
        return staging.size();
    }

    public long pendingBytes() {
        return pendingBytes;
    }

    /** A staging buffer of {@code bytes}: {@code data}, then zeros. */
    private GpuBuffer stage(MemorySegment data, long bytes) {
        GpuBuffer buffer = GpuBuffer.hostWritten(device, Math.max(1, bytes), VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        MemorySegment.copy(data, 0, buffer.memory(), 0, data.byteSize());
        buffer.memory().asSlice(data.byteSize(), bytes - data.byteSize()).fill((byte) 0); // the padding
        return buffer;
    }

    private static void copy(VkCommandBuffer commands, GpuBuffer staging, GpuBuffer target, long offset) {
        try (MemoryStack stack = stackPush()) {
            long region = stack.ncalloc(VkBufferCopy.ALIGNOF, 1, VkBufferCopy.SIZEOF);
            memPutLong(region + VkBufferCopy.DSTOFFSET, offset);
            memPutLong(region + VkBufferCopy.SIZE, staging.size());
            nvkCmdCopyBuffer(commands, staging.handle(), target.handle(), 1, region);
        }
    }

    private void queue(GpuBuffer buffer, GpuBuffer target, long offset,
        BiConsumer<VkCommandBuffer, GpuBuffer> record) {
        staging.add(buffer);
        targets.add(target);
        if (staging.size() > offsets.length) {
            offsets = java.util.Arrays.copyOf(offsets, 2 * offsets.length);
        }
        offsets[staging.size() - 1] = offset;
        commands.add(record);
        pendingBytes += buffer.size();
    }

    /**
     * Records the waiting uploads into {@code commands}, ordered after whatever the frames before did and before
     * everything after. Frames do it first (registered with the frame slots); work outside frames that needs what was
     * uploaded (a build reading it, say) calls it on the graphics queue first. Allocates nothing without uploads.
     */
    public void flush(VkCommandBuffer commands) {
        retiring.release();
        if (staging.isEmpty()) {
            return;
        }
        barrier(commands, VK_ACCESS_2_MEMORY_WRITE_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        for (int i = 0; i < staging.size(); i++) {
            GpuBuffer buffer = staging.get(i);
            if (targets.get(i) != null) {
                copy(commands, buffer, targets.get(i), offsets[i]);
            } else {
                this.commands.get(i).accept(commands, buffer);
            }
            retiring.add(buffer);
        }
        barrier(commands, VK_ACCESS_2_MEMORY_WRITE_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        staging.clear();
        targets.clear();
        this.commands.clear();
        pendingBytes = 0;
    }

    private static void barrier(VkCommandBuffer commands, long srcAccess, long dstAccess) {
        try (MemoryStack stack = stackPush()) {
            long barrier = stack.ncalloc(VkMemoryBarrier2.ALIGNOF, 1, VkMemoryBarrier2.SIZEOF);
            memPutInt(barrier + VkMemoryBarrier2.STYPE, VK_STRUCTURE_TYPE_MEMORY_BARRIER_2);
            memPutLong(barrier + VkMemoryBarrier2.SRCSTAGEMASK, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            memPutLong(barrier + VkMemoryBarrier2.SRCACCESSMASK, srcAccess);
            memPutLong(barrier + VkMemoryBarrier2.DSTSTAGEMASK, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            memPutLong(barrier + VkMemoryBarrier2.DSTACCESSMASK, dstAccess);
            long dependency = stack.ncalloc(VkDependencyInfo.ALIGNOF, 1, VkDependencyInfo.SIZEOF);
            memPutInt(dependency + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
            memPutInt(dependency + VkDependencyInfo.MEMORYBARRIERCOUNT, 1);
            memPutAddress(dependency + VkDependencyInfo.PMEMORYBARRIERS, barrier);
            nvkCmdPipelineBarrier2(commands, dependency);
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The uploads are closed");
        }
    }

    /**
     * Stops deferring and frees the staging buffers: call once the GPU is idle. Uploads still waiting are dropped.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (deferred) {
                slots.unregister(start);
            }
            for (GpuBuffer buffer : staging) {
                buffer.close();
            }
            staging.clear();
            retiring.close();
        }
    }
}
