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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * draw it from the next frame recorded.
 *
 * <pre>{@code
 * FrameUploads uploads = new FrameUploads(device, graph.frameSlots());
 * uploads.write(positions, offset, meshPositions);   // returns at once; copied when the next frame records
 * }</pre>
 *
 * <p>Uploads share staging buffers of {@value #CHUNK_BYTES} bytes, filled one after the other and reused once no frame
 * in flight reads them; a bigger upload gets one of its own. With {@link FrameSlots#single()} there are no frames to
 * defer to: uploads run at once and wait, as for callers that wait for the GPU between frames. Use it from one thread,
 * the one recording frames.
 */
@AllowAllocation("uploads are rare next to frames")
public final class FrameUploads implements AutoCloseable {

    /** Records commands reading an upload's data from {@code staging}, at {@code offset}, for {@link #record}. */
    @FunctionalInterface
    public interface Staged {
        void record(VkCommandBuffer commands, GpuBuffer staging, long offset);
    }

    /** The size of the staging buffers uploads share; a bigger upload gets one of its own, freed after. */
    public static final long CHUNK_BYTES = 4L << 20;
    /** Uploads start at multiples of this in a staging buffer: enough for buffer-to-image copies of any texel. */
    static final long ALIGNMENT = 16;
    /** Free staging buffers kept for reuse; more are freed. */
    static final int KEPT_CHUNKS = 4;

    /** A staging buffer filled from the start; closing it (once its frames finish) returns it for reuse. */
    private final class Chunk implements AutoCloseable {
        final GpuBuffer buffer;
        long used;

        Chunk(long bytes) {
            this.buffer = GpuBuffer.hostWritten(device, bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        }

        @Override
        public void close() {
            recycle(this);
        }
    }

    /** An upload waiting for the next frame: from its chunk into a target at an offset, or commands of its own. */
    private record Pending(Chunk chunk, long from, long bytes, GpuBuffer target, long offset, Staged record) {
    }

    private final VulkanDevice device;
    private final FrameSlots slots;
    private final boolean deferred;
    private final FrameSlots.FrameStart start = this::flush;
    private final List<Pending> pending = new ArrayList<>();
    // The staging buffers the waiting uploads are in, the last one still being filled; reusable ones wait in free.
    private final List<Chunk> filling = new ArrayList<>();
    private final ArrayDeque<Chunk> free = new ArrayDeque<>();
    private final Retiring retiring; // staging buffers copied from, until their frames finish
    private int created;
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
        upload(data, bytes, target, offset, null);
    }

    /**
     * Records {@code record} with a staging buffer holding {@code data} from the offset it is given, in the next frame
     * (or at once, waiting): for uploads that are more than a copy into a buffer, such as an image and its mip chain.
     * It runs in the frame's first commands, so later passes may use what it wrote once a barrier from all commands'
     * writes is in place, which the uploads record after it. The staging buffer holds other uploads too: read only the
     * data's bytes from the offset on.
     */
    public void record(MemorySegment data, Staged record) {
        checkOpen();
        Objects.requireNonNull(record, "record");
        upload(data, data.byteSize(), null, 0, record);
    }

    /** How many uploads wait for the next frame, and their bytes. */
    public int pendingCount() {
        return pending.size();
    }

    public long pendingBytes() {
        return pendingBytes;
    }

    /** How many staging buffers were made so far: few, as they are reused. */
    int stagingBuffersCreated() {
        return created;
    }

    private void upload(MemorySegment data, long bytes, GpuBuffer target, long offset, Staged record) {
        Chunk chunk = chunkFor(Math.max(1, bytes));
        long from = chunk.used;
        MemorySegment memory = chunk.buffer.memory();
        MemorySegment.copy(data, 0, memory, from, data.byteSize());
        memory.asSlice(from + data.byteSize(), bytes - data.byteSize()).fill((byte) 0); // the padding
        chunk.used = from + (bytes + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT;
        if (!deferred) {
            try {
                device.submitAndWait(device.graphics(),
                    commands -> run(commands, chunk, from, bytes, target, offset, record));
            } finally {
                filling.remove(chunk);
                recycle(chunk);
            }
            return;
        }
        pending.add(new Pending(chunk, from, bytes, target, offset, record));
        pendingBytes += bytes;
    }

    /** The staging buffer being filled if {@code bytes} fit, else a fresh one (reused where one is free). */
    private Chunk chunkFor(long bytes) {
        Chunk last = filling.isEmpty() ? null : filling.getLast();
        if (last != null && last.used + bytes <= last.buffer.size()) {
            return last;
        }
        Chunk chunk = bytes <= CHUNK_BYTES ? free.poll() : null;
        if (chunk == null) {
            chunk = new Chunk(Math.max(CHUNK_BYTES, bytes));
            created++;
        }
        filling.add(chunk);
        return chunk;
    }

    /** Takes back a staging buffer no frame reads any more: kept for reuse, or freed. */
    private void recycle(Chunk chunk) {
        if (closed || chunk.buffer.size() != CHUNK_BYTES || free.size() >= KEPT_CHUNKS) {
            chunk.buffer.close();
        } else {
            chunk.used = 0;
            free.push(chunk);
        }
    }

    private static void run(VkCommandBuffer commands, Chunk chunk, long from, long bytes, GpuBuffer target,
        long offset, Staged record) {
        if (target != null) {
            copy(commands, chunk.buffer, from, bytes, target, offset);
        } else {
            record.record(commands, chunk.buffer, from);
        }
    }

    private static void copy(VkCommandBuffer commands, GpuBuffer staging, long from, long bytes, GpuBuffer target,
        long offset) {
        try (MemoryStack stack = stackPush()) {
            long region = stack.ncalloc(VkBufferCopy.ALIGNOF, 1, VkBufferCopy.SIZEOF);
            memPutLong(region + VkBufferCopy.SRCOFFSET, from);
            memPutLong(region + VkBufferCopy.DSTOFFSET, offset);
            memPutLong(region + VkBufferCopy.SIZE, bytes);
            nvkCmdCopyBuffer(commands, staging.handle(), target.handle(), 1, region);
        }
    }

    /**
     * Records the waiting uploads into {@code commands}, ordered after whatever the frames before did and before
     * everything after. Frames do it first (registered with the frame slots); work outside frames that needs what was
     * uploaded (a build reading it, say) calls it on the graphics queue first. Allocates nothing without uploads.
     */
    public void flush(VkCommandBuffer commands) {
        retiring.release();
        if (pending.isEmpty()) {
            return;
        }
        barrier(commands, VK_ACCESS_2_MEMORY_WRITE_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        for (Pending upload : pending) {
            run(commands, upload.chunk(), upload.from(), upload.bytes(), upload.target(), upload.offset(),
                upload.record());
        }
        barrier(commands, VK_ACCESS_2_MEMORY_WRITE_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        for (Chunk chunk : filling) {
            retiring.add(chunk); // reused once the frames now in flight finish
        }
        filling.clear();
        pending.clear();
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
            for (Chunk chunk : filling) {
                chunk.buffer.close();
            }
            filling.clear();
            pending.clear();
            retiring.close();
            for (Chunk chunk : free) {
                chunk.buffer.close();
            }
            free.clear();
        }
    }
}
