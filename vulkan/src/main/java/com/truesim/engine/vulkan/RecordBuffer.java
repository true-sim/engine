package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_MEMORY_BARRIER_2;
import static org.lwjgl.vulkan.VK13.nvkCmdPipelineBarrier2;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.layout.ScatterPush;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

/**
 * A device-local buffer of fixed-size records that change a few at a time: instances, particles, per-object game data.
 * Changing a record writes it once into a list of changes on the CPU ({@link #claim}, then the record's bytes in
 * {@link #changes()}); the frame's first commands copy the list into the frame slot's upload buffer in one go and
 * scatter it with a compute pass. The CPU pays per record changed, in sequential writes; frames in flight keep no
 * copies, as the queue orders the scatter after the frames before. Shaders read the records at {@link #deviceAddress}.
 *
 * <pre>{@code
 * RecordBuffer particles = new RecordBuffer(device, 32, 100_000, graph.frameSlots()); // 32-byte records
 * long at = particles.offset(particles.claim(i));       // record i's place in the changes
 * particles.changes().set(ValueLayout.JAVA_FLOAT, at, x); // ... the record's bytes, as the shader lays them out
 * // the next frame's first commands scatter the changes; passes read particles.deviceAddress() after
 * }</pre>
 *
 * <p>A record is in the changes at most once until they upload: claiming it again returns its entry, so the last
 * write wins. Claim on one thread; fill claimed entries from any (distinct entries), before the next upload. Records
 * start as zeros. Frames recorded by a frame graph upload first thing ({@link FrameSlots#recordStart}); frames recorded
 * otherwise call {@link #upload} before reading. Allocates nothing once created, except when more records change in a
 * frame than ever before: the upload buffers grow then.
 */
@AllowAllocation("created rarely")
public final class RecordBuffer implements AutoCloseable {

    private static final int SCATTER_PUSH = ScatterPush.BYTES;
    private static final int MAX_GROUPS = 65_535;
    /** Records an upload buffer holds at first; it grows (to the capacity at most) when more change in a frame. */
    static final int FIRST_UPLOAD_RECORDS = 256;

    private final VulkanDevice device;
    private final int recordBytes;
    private final int capacity;
    private final FrameSlots slots;
    private final Retiring retiring; // upload buffers replaced by bigger ones, until their frames finish
    // Set once while constructing; null until made, so a constructor that fails frees what it made.
    private GpuBuffer records;
    private Pipeline scatter;
    private final FrameSlots.FrameStart start = this::upload;
    // The changes since the last upload: entry e is record indices[e], its bytes at e * recordBytes of changes.
    private final Arena arena = Arena.ofShared();
    private final MemorySegment indices;
    private final MemorySegment changes;
    private final int[] entryOf;
    private int pending;
    // Per frame slot, the upload buffer (indices, then records) and where in it the next upload goes: frames upload
    // once or a few times each, the next frame of the slot starting over (with one slot, where the last one ended).
    private final GpuBuffer[] uploads;
    private final int[] uploadCapacity; // records each holds
    private final long[] uploadFrame;
    private final int[] uploadAt;
    private boolean closed;

    /**
     * {@code capacity} records of {@code recordBytes} bytes each (a multiple of 16) in device-local memory, for the
     * frames of {@code slots} (a frame graph's, or {@link FrameSlots#single()} for frames the caller waits for).
     */
    public RecordBuffer(VulkanDevice device, int recordBytes, int capacity, FrameSlots slots) {
        if (recordBytes < 16 || recordBytes % 16 != 0 || capacity < 1
            || (long) capacity * (recordBytes / 16) > (long) Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Records of a multiple of 16 bytes, at least one: " + recordBytes
                + " bytes, " + capacity);
        }
        this.device = Objects.requireNonNull(device, "device");
        this.recordBytes = recordBytes;
        this.capacity = capacity;
        this.slots = Objects.requireNonNull(slots, "slots");
        this.retiring = new Retiring(slots);
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        uploads = new GpuBuffer[slots.count()];
        uploadCapacity = new int[uploads.length];
        try {
            records = GpuBuffer.deviceLocal(device, (long) recordBytes * capacity, storage
                | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT); // copied from: readbacks
            device.submitAndWait(device.graphics(), commands -> vkCmdFillBuffer(commands, records.handle(), 0,
                records.size(), 0));
            try (ShaderModule shader = ShaderModule.load(device, "shaders/records/scatter.spv")) {
                scatter = Pipeline.compute(device, shader, "scatterMain", SCATTER_PUSH);
            }
            for (int s = 0; s < uploads.length; s++) {
                uploads[s] = uploadBuffer(Math.min(capacity, FIRST_UPLOAD_RECORDS));
                uploadCapacity[s] = Math.min(capacity, FIRST_UPLOAD_RECORDS);
            }
        } catch (RuntimeException e) {
            free(); // what was made leaks nothing
            throw e;
        }
        indices = arena.allocate(4L * capacity, 64);
        changes = arena.allocate((long) recordBytes * capacity, 64);
        entryOf = new int[capacity];
        Arrays.fill(entryOf, -1);
        uploadFrame = new long[uploads.length];
        Arrays.fill(uploadFrame, -1);
        uploadAt = new int[uploads.length];
        slots.register(start);
    }

    public int recordBytes() {
        return recordBytes;
    }

    public int capacity() {
        return capacity;
    }

    /** The records, for shaders: record i at {@code deviceAddress() + i * recordBytes()}, after the frame's upload. */
    public long deviceAddress() {
        return records.deviceAddress();
    }

    /** The device-local buffer holding the records, e.g. for a frame graph's external buffer. */
    public GpuBuffer buffer() {
        return records;
    }

    /**
     * Record {@code index}'s entry in the changes, made if it has none yet: fill its bytes at {@link #offset} of
     * {@link #changes()}. A new entry holds what an earlier one of this upload held, or garbage: write the whole
     * record. Allocates nothing.
     */
    public int claim(int index) {
        Objects.checkIndex(index, capacity);
        int entry = entryOf[index];
        if (entry < 0) {
            entry = pending++;
            entryOf[index] = entry;
            indices.setAtIndex(ValueLayout.JAVA_INT, entry, index);
        }
        return entry;
    }

    /** The changes' memory: entry e's record at {@link #offset}(e). Write it before the next upload. */
    public MemorySegment changes() {
        return changes;
    }

    /** Where entry {@code entry}'s record starts in {@link #changes()}. */
    public long offset(int entry) {
        return (long) recordBytes * Objects.checkIndex(entry, pending);
    }

    /** Sets record {@code index} to zeros (at the next upload). Allocates nothing. */
    public void clear(int index) {
        long at = (long) recordBytes * claim(index);
        for (int word = 0; word < recordBytes; word += 8) {
            changes.set(ValueLayout.JAVA_LONG_UNALIGNED, at + word, 0);
        }
    }

    /** How many records changed since the last upload: what the next one scatters. */
    public int pendingCount() {
        return pending;
    }

    /**
     * Brings the device-local records up to date for what reads them next in {@code commands}: the changes are copied
     * into the open frame slot's upload buffer and scattered by a compute pass, ordered after whatever read the
     * records before (earlier frames too: the queue's order) and before what reads them after. Nothing without
     * changes. Frames recorded by a frame graph do it first; call it before reading in frames recorded otherwise.
     * Allocates nothing, except to grow the upload buffer when more records changed than it held so far.
     */
    public synchronized void upload(VkCommandBuffer commands) {
        if (pending == 0) {
            return;
        }
        if (slots.current() < 0 && !slots.isSingle()) {
            // Between frames any slot's upload buffer may still be read by a frame in flight.
            throw new IllegalStateException("Upload records within a frame (between beginFrame and submit)");
        }
        retiring.release();
        int slot = Math.max(slots.current(), 0);
        long frame = slots.frames();
        int at = uploadFrame[slot] == frame ? uploadAt[slot] : 0; // else the slot's last frame has finished
        if (at + pending > uploadCapacity[slot]) {
            if (slots.isSingle() && pending <= uploadCapacity[slot]) {
                at = 0; // one copy: the caller waited for the frames before
            } else {
                // A bigger buffer; this frame may still read the old one, so it goes once the frame is done.
                int grown = Math.min(capacity, Math.max(pending, 2 * uploadCapacity[slot]));
                GpuBuffer bigger = uploadBuffer(grown);
                retiring.add(uploads[slot]);
                uploads[slot] = bigger;
                uploadCapacity[slot] = grown;
                at = 0;
            }
        }
        GpuBuffer target = uploads[slot];
        uploadFrame[slot] = frame;
        uploadAt[slot] = at + pending;
        MemorySegment memory = target.memory();
        long changesAt = changesOffset(uploadCapacity[slot]) + (long) recordBytes * at;
        MemorySegment.copy(indices, 0, memory, 4L * at, 4L * pending);
        MemorySegment.copy(changes, 0, memory, changesAt, (long) recordBytes * pending);
        // Whatever read the records before, in this frame or those before, has read them.
        barrier(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, 0, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        int words = recordBytes / 16;
        long threads = (long) pending * words;
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, SCATTER_PUSH);
            memPutLong(push + ScatterPush.INDICES, target.deviceAddress() + 4L * at);
            memPutLong(push + ScatterPush.CHANGES, target.deviceAddress() + changesAt);
            memPutLong(push + ScatterPush.RECORDS, records.deviceAddress());
            memPutInt(push + ScatterPush.COUNT, pending);
            memPutInt(push + ScatterPush.WORDS, words);
            scatter.bind(commands);
            for (long first = 0; first < threads; first += 64L * MAX_GROUPS) {
                memPutInt(push + ScatterPush.FIRST, (int) first);
                scatter.push(commands, push, SCATTER_PUSH);
                vkCmdDispatch(commands, (int) Math.min(MAX_GROUPS, (threads - first + 63) / 64), 1, 1);
            }
        }
        barrier(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT);
        for (int e = 0; e < pending; e++) {
            entryOf[indices.getAtIndex(ValueLayout.JAVA_INT, e)] = -1;
        }
        pending = 0;
    }

    /** An upload buffer for {@code entries} changed records: their indices, then their bytes. */
    private GpuBuffer uploadBuffer(int entries) {
        return GpuBuffer.hostWritten(device, changesOffset(entries) + (long) recordBytes * entries,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
    }

    /** Where an upload buffer's records start: after an index per entry, aligned for the records. */
    private static long changesOffset(int entries) {
        return (4L * entries + 63) & ~63L;
    }

    private static void barrier(VkCommandBuffer commands, long srcStage, long srcAccess, long dstStage,
        long dstAccess) {
        try (MemoryStack stack = stackPush()) {
            long barrier = stack.ncalloc(VkMemoryBarrier2.ALIGNOF, 1, VkMemoryBarrier2.SIZEOF);
            memPutInt(barrier + VkMemoryBarrier2.STYPE, VK_STRUCTURE_TYPE_MEMORY_BARRIER_2);
            memPutLong(barrier + VkMemoryBarrier2.SRCSTAGEMASK, srcStage);
            memPutLong(barrier + VkMemoryBarrier2.SRCACCESSMASK, srcAccess);
            memPutLong(barrier + VkMemoryBarrier2.DSTSTAGEMASK, dstStage);
            memPutLong(barrier + VkMemoryBarrier2.DSTACCESSMASK, dstAccess);
            long dependency = stack.ncalloc(VkDependencyInfo.ALIGNOF, 1, VkDependencyInfo.SIZEOF);
            memPutInt(dependency + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
            memPutInt(dependency + VkDependencyInfo.MEMORYBARRIERCOUNT, 1);
            memPutAddress(dependency + VkDependencyInfo.PMEMORYBARRIERS, barrier);
            nvkCmdPipelineBarrier2(commands, dependency);
        }
    }

    /** Frees the buffers: once the GPU no longer reads them. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            slots.unregister(start);
            free();
        }
    }

    /** Frees the buffers, pipeline and memory made. */
    private void free() {
        retiring.close();
        if (records != null) {
            records.close();
        }
        if (scatter != null) {
            scatter.close();
        }
        for (GpuBuffer upload : uploads) {
            if (upload != null) {
                upload.close();
            }
        }
        arena.close();
    }
}
