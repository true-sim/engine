package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.layout.ScatterPush;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * A device-local buffer of fixed-size records that change a few at a time: instances, particles, per-object game data.
 * Changing a record writes it once into a list of changes ({@link #claim}, then the record's bytes in
 * {@link #changes(int)} at {@link #offset}), which a compute pass at the frame's start scatters into the records. The
 * CPU pays per record changed; frames in flight keep no copies, as the queue orders the scatter after the frames
 * before. Shaders read the records at {@link #deviceAddress}.
 *
 * <pre>{@code
 * RecordBuffer particles = new RecordBuffer(device, 32, 100_000, graph.frameSlots()); // 32-byte records
 * int entry = particles.claim(i);                                   // record i's entry in the changes
 * particles.changes(entry).set(ValueLayout.JAVA_FLOAT, particles.offset(entry), x); // ... as the shader lays it out
 * // the next frame's first commands scatter the changes; passes read particles.deviceAddress() after
 * }</pre>
 *
 * <p>Where the list lives is a {@link ChangeList} choice ({@link #changeList}): in plain memory copied into the frame
 * slot's upload buffer when the frame records ({@link ChangeList#COPY}), or written in place in chunks of GPU-visible
 * host memory that the scatter reads where they are ({@link ChangeList#DIRECT}: no copy on the thread recording, no
 * list of the capacity's size, but the writers touch fresh memory each frame and the GPU reads the changes over the
 * bus). frame-graph-v2.md (#101) has the measurements behind the choice.
 *
 * <p>A record is in the changes at most once until they upload: claiming it again returns its entry, so the last
 * write wins. Claim on one thread; fill claimed entries from any (distinct entries), before the next upload or grow.
 * Records start as zeros. Frames recorded by a frame graph upload first thing ({@link FrameSlots#recordStart}); frames
 * recorded otherwise call {@link #upload} before reading. Allocates nothing once created, except when more records
 * change in a frame than ever before: the upload buffers (or chunks) grow then.
 */
@AllowAllocation("created rarely")
public final class RecordBuffer implements AutoCloseable, DeviceBuffer {

    /** Where the changes are written, and how they reach the GPU. */
    public enum ChangeList {
        /** Into plain memory, copied into the frame slot's upload buffer when the frame records. */
        COPY,
        /** In place, into chunks of GPU-visible host memory the scatter reads where they are. */
        DIRECT
    }

    /** Bytes of a chunk of the {@link ChangeList#DIRECT} list unless set otherwise. */
    public static final long CHUNK_BYTES = 1L << 20;
    private static final int SCATTER_PUSH = ScatterPush.BYTES;
    private static final int MAX_GROUPS = 65_535;
    // Copied from by readbacks and as they grow, copied into as they grow.
    private static final int RECORDS_USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
        | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    private static final int CHANGES_USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    /** Records an upload buffer holds at first; it grows (to the capacity at most) when more change in a frame. */
    static final int FIRST_UPLOAD_RECORDS = 256;

    private final VulkanDevice device;
    private final int recordBytes;
    private int capacity;
    private final FrameSlots slots;
    // The buffer the records were in before they grew, and its size: copied into the new one at the next upload.
    private GpuBuffer grownFrom;
    private final Retiring retiring; // upload buffers and chunks the frames in flight read, until they finish
    // Set once while constructing; null until made, so a constructor that fails frees what it made.
    private GpuBuffer records;
    private Pipeline scatter;
    private final FrameSlots.FrameStart start = this::upload;
    private int[] entryOf;
    private int pending;
    private boolean closed;
    /** Whether passes declaring the records (a frame graph's) upload them, not the frames' starts. */
    private boolean inPasses;
    private long uploadNanos; // the thread recording's time in uploads, so far

    // The list as it is, and as asked for: switched once no change waits (now, or after the next upload).
    private ChangeList list = ChangeList.COPY;
    private long chunkBytes = CHUNK_BYTES;
    private ChangeList wantedList = ChangeList.COPY;
    private long wantedChunkBytes = CHUNK_BYTES;

    // COPY: the changes since the last upload, entry e being record indices[e], its bytes at e * recordBytes of
    // changes; per frame slot, the upload buffer (indices, then records) and where in it the next upload goes: frames
    // upload once or a few times each, the next frame of the slot starting over (with one slot, where the last ended).
    private Arena arena; // the list's memory (null while DIRECT): replaced, the old freed, as it grows
    private MemorySegment indices;
    private MemorySegment changes;
    private final GpuBuffer[] uploads;
    private final int[] uploadCapacity; // records each holds
    private final long[] uploadFrame;
    private final int[] uploadAt;

    // DIRECT: this upload's chunks, entry e in chunk e / chunkEntries; spare chunks, back from the frames that read
    // them (reused last in, first out), and the bytes of all of them.
    private int chunkEntries;
    private Chunk[] open = new Chunk[4];
    private int openCount;
    private Chunk[] spare = new Chunk[4];
    private int spareCount;
    private long chunkMemory;

    /** A chunk of GPU-visible host memory that claims write into: indices first, then records. */
    private final class Chunk implements AutoCloseable {
        final GpuBuffer buffer;
        final MemorySegment memory;
        final long address;
        final int entries;

        Chunk(int entries) {
            this.entries = entries;
            buffer = GpuBuffer.hostVisible(device, changesOffset(entries) + (long) recordBytes * entries,
                CHANGES_USAGE);
            memory = buffer.memory();
            address = buffer.deviceAddress();
            chunkMemory += buffer.size();
        }

        /** Back from the frames that read it: a spare, or freed if the list changed since. */
        @Override
        public void close() {
            if (closed || list != ChangeList.DIRECT || entries != chunkEntries) {
                free();
                return;
            }
            if (spareCount == spare.length) {
                spare = Arrays.copyOf(spare, 2 * spareCount);
            }
            spare[spareCount++] = this;
        }

        void free() {
            chunkMemory -= buffer.size();
            buffer.close();
        }
    }

    /**
     * {@code capacity} records of {@code recordBytes} bytes each (a multiple of 16) in device-local memory, for the
     * frames of {@code slots} (a frame graph's, or {@link FrameSlots#single()} for frames the caller waits for); the
     * changes in a {@link ChangeList#COPY} list until {@link #changeList} says otherwise.
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
        uploads = new GpuBuffer[slots.count()];
        uploadCapacity = new int[uploads.length];
        try {
            records = GpuBuffer.deviceLocal(device, (long) recordBytes * capacity, RECORDS_USAGE);
            device.submitAndWait(device.graphics(), commands -> Commands.fillBuffer(commands, records.handle(), 0,
                records.size(), 0));
            try (ShaderModule shader = ShaderModule.load(device, "shaders/records/scatter.spv")) {
                scatter = Pipeline.compute(device, shader, "scatterMain", SCATTER_PUSH);
            }
        } catch (RuntimeException e) {
            free(); // what was made leaks nothing
            throw e;
        }
        copyList();
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

    /**
     * Writes the changes into a list of kind {@code list} from now on, chunks of {@code chunkBytes} for a
     * {@link ChangeList#DIRECT} one (as many records as fit, the capacity at most): at once if no change waits, else
     * after the next upload. Changes claimed meanwhile go where they went.
     */
    @AllowAllocation("switching, rarely")
    public synchronized void changeList(ChangeList list, long chunkBytes) {
        Objects.requireNonNull(list, "list");
        if (chunkBytes < recordBytes + 4L) {
            throw new IllegalArgumentException("Chunks hold a record at least: " + chunkBytes + " bytes");
        }
        wantedList = list;
        wantedChunkBytes = chunkBytes;
        if (pending == 0) {
            switchList();
        }
    }

    /** The kind of list changes are written into now. */
    public synchronized ChangeList changeList() {
        return list;
    }

    /** Switches to the list asked for; no change waits. */
    private void switchList() {
        if (wantedList == list && wantedChunkBytes == chunkBytes) {
            return;
        }
        ChangeList was = list;
        list = wantedList;
        chunkBytes = wantedChunkBytes;
        if (list == ChangeList.DIRECT) {
            if (was == ChangeList.COPY) {
                arena.close();
                arena = null;
                indices = null;
                changes = null;
                for (int s = 0; s < uploads.length; s++) {
                    if (uploads[s] != null) {
                        retiring.add(uploads[s]); // frames in flight may read it
                        uploads[s] = null;
                        uploadCapacity[s] = 0;
                    }
                }
            }
            chunkEntries = chunkEntriesFor(capacity);
            freeSpares(); // of another size, or none
        } else {
            freeSpares();
            copyList();
        }
    }

    private void freeSpares() {
        for (int c = 0; c < spareCount; c++) {
            spare[c].free();
            spare[c] = null;
        }
        spareCount = 0;
    }

    /** A {@link ChangeList#COPY} list of the capacity. */
    private void copyList() {
        arena = Arena.ofShared();
        indices = arena.allocate(4L * capacity, 64);
        changes = arena.allocate((long) recordBytes * capacity, 64);
    }

    /** Entries per chunk: what fits a chunk, the capacity at most. */
    private int chunkEntriesFor(int capacity) {
        return (int) Math.max(1, Math.min(capacity, chunkBytes / (recordBytes + 4)));
    }

    /**
     * Room for {@code capacity} records from now on: a bigger device-local buffer, the records copied into it (the rest
     * zeros) at the next upload, before its changes scatter; the old buffer is freed once no frame reads it. Frames
     * recorded from now on read the new one ({@link #deviceAddress}, {@link #buffer}). Changes claimed so far stay.
     */
    @AllowAllocation("growing, rarely")
    public synchronized void grow(int capacity) {
        if (capacity <= this.capacity || (long) capacity * (recordBytes / 16) > (long) Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Grows to more than " + this.capacity + " records: " + capacity);
        }
        GpuBuffer bigger = GpuBuffer.deviceLocal(device, (long) recordBytes * capacity, RECORDS_USAGE);
        if (grownFrom == null) {
            grownFrom = records;
        } else {
            // Grown twice before an upload: the first one holds the records; this one goes once no frame recorded
            // since (a grow within a frame) can read it.
            retiring.add(records);
        }
        records = bigger;
        if (list == ChangeList.COPY) {
            Arena more = Arena.ofShared();
            MemorySegment moreIndices = more.allocate(4L * capacity, 64);
            MemorySegment moreChanges = more.allocate((long) recordBytes * capacity, 64);
            MemorySegment.copy(indices, 0, moreIndices, 0, 4L * pending);
            MemorySegment.copy(changes, 0, moreChanges, 0, (long) recordBytes * pending);
            arena.close(); // the old change list: copied, and filled no more (grow excludes filling)
            arena = more;
            indices = moreIndices;
            changes = moreChanges;
        }
        entryOf = Arrays.copyOf(entryOf, capacity);
        Arrays.fill(entryOf, this.capacity, capacity, -1);
        this.capacity = capacity;
    }

    /** The records, for shaders: record i at {@code deviceAddress() + i * recordBytes()}, after the frame's upload. */
    @Override
    public long deviceAddress() {
        return records.deviceAddress();
    }

    /** The device-local buffer holding the records, e.g. for a frame graph's external buffer. */
    public GpuBuffer buffer() {
        return records;
    }

    /**
     * Record {@code index}'s entry in the changes, made if it has none yet: fill its bytes at {@link #offset} of
     * {@link #changes(int)}. A new entry holds what an earlier one of this upload held, or garbage: write the whole
     * record. Allocates nothing, unless a {@link ChangeList#DIRECT} list needs a chunk more than ever before.
     */
    public int claim(int index) {
        Objects.checkIndex(index, capacity);
        int entry = entryOf[index];
        if (entry < 0) {
            entry = pending++;
            entryOf[index] = entry;
            if (list == ChangeList.DIRECT) {
                int c = entry / chunkEntries;
                if (c == openCount) {
                    openChunk();
                }
                open[c].memory.setAtIndex(ValueLayout.JAVA_INT, entry - c * chunkEntries, index);
            } else {
                indices.setAtIndex(ValueLayout.JAVA_INT, entry, index);
            }
        }
        return entry;
    }

    /** The memory entry {@code entry}'s record is written in, at {@link #offset}. Write it before the next upload. */
    public MemorySegment changes(int entry) {
        return list == ChangeList.DIRECT ? open[entry / chunkEntries].memory : changes;
    }

    /** Where entry {@code entry}'s record starts in {@link #changes(int)}. */
    public long offset(int entry) {
        Objects.checkIndex(entry, pending);
        if (list == ChangeList.DIRECT) {
            return changesOffset(chunkEntries) + (long) recordBytes * (entry % chunkEntries);
        }
        return (long) recordBytes * entry;
    }

    /** Opens the next chunk of this upload's changes: a spare one, or a new one. */
    private void openChunk() {
        retiring.release(); // chunks whose frames finished come back as spares
        if (openCount == 0 && chunkEntriesFor(capacity) != chunkEntries) {
            chunkEntries = chunkEntriesFor(capacity); // grown since: chunks of the new size
            freeSpares();
        }
        Chunk chunk = spareCount > 0 ? spare[--spareCount] : newChunk();
        if (openCount == open.length) {
            open = Arrays.copyOf(open, 2 * openCount);
        }
        open[openCount++] = chunk;
    }

    @AllowAllocation("more changes than ever before")
    private Chunk newChunk() {
        return new Chunk(chunkEntries);
    }

    /** Sets record {@code index} to zeros (at the next upload). Allocates nothing. */
    public void clear(int index) {
        int entry = claim(index);
        long at = offset(entry);
        MemorySegment memory = changes(entry);
        for (int word = 0; word < recordBytes; word += 8) {
            memory.set(ValueLayout.JAVA_LONG_UNALIGNED, at + word, 0);
        }
    }

    /** How many records changed since the last upload: what the next one scatters. */
    public int pendingCount() {
        return pending;
    }

    /** Bytes of plain memory the change list holds: a {@link ChangeList#COPY} list of the capacity, or none. */
    public synchronized long hostBytes() {
        return list == ChangeList.COPY ? (4L + recordBytes) * capacity : 0;
    }

    /** Bytes of GPU-visible memory the changes go through: the upload buffers, and the chunks with their spares. */
    public synchronized long pinnedBytes() {
        long bytes = chunkMemory;
        for (GpuBuffer upload : uploads) {
            bytes += upload == null ? 0 : upload.size();
        }
        return bytes;
    }

    /** Nanoseconds the uploads took on the threads recording them, so far: for benchmarks. */
    public synchronized long uploadNanos() {
        return uploadNanos;
    }

    /**
     * Brings the device-local records up to date for what reads them next in {@code commands}: the changes (copied
     * into the open frame slot's upload buffer first, for a {@link ChangeList#COPY} list) are scattered by a compute
     * pass, ordered after whatever read the records before (earlier frames too: the queue's order) and before what
     * reads them after. Nothing without changes. Frames recorded by a frame graph do it first; call it before reading
     * in frames recorded otherwise. Allocates nothing, except to grow the upload buffer when more records changed than
     * it held so far.
     *
     * <p>Nothing once a pass uploads them ({@link #uploadInPasses}): passes reading the records may call it whatever
     * records them, and under parallel recording one recorded before the uploading pass would otherwise take the
     * changes into a command buffer later in the frame than readers the graph ordered after the upload.
     */
    public synchronized void upload(VkCommandBuffer commands) {
        if (!inPasses) {
            upload(commands, true);
        }
    }

    /**
     * From now on, a pass declaring that it writes the records uploads them ({@link #uploadInPass}), as a frame graph's
     * scene upload pass does: the frames' starts no longer do. Frames recorded otherwise keep calling {@link #upload}.
     */
    public synchronized void uploadInPasses() {
        if (!inPasses) {
            inPasses = true;
            slots.unregister(start);
        }
    }

    /**
     * Like {@link #upload}, without its barriers: for a pass that declares it writes the records (with compute) to a
     * graph, which orders it with what reads them.
     */
    public synchronized void uploadInPass(VkCommandBuffer commands) {
        upload(commands, false);
    }

    private void upload(VkCommandBuffer commands, boolean barriers) {
        if (grownFrom != null) {
            copyGrown(commands);
        }
        if (pending == 0) {
            return;
        }
        if (slots.current() < 0 && !slots.isSingle()) {
            // Between frames any slot's upload buffer may still be read by a frame in flight.
            throw new IllegalStateException("Upload records within a frame (between beginFrame and submit)");
        }
        long began = System.nanoTime();
        // Whatever read the records before, in this frame or those before, has read them.
        if (barriers) {
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, 0, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        }
        if (list == ChangeList.DIRECT) {
            scatterChunks(commands);
        } else {
            copyAndScatter(commands);
        }
        if (barriers) {
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT);
        }
        pending = 0;
        switchList(); // if another list was asked for
        uploadNanos += System.nanoTime() - began;
    }

    /** The changes copied into the slot's upload buffer, and scattered from there. */
    private void copyAndScatter(VkCommandBuffer commands) {
        retiring.release();
        int slot = Math.max(slots.current(), 0);
        long frame = slots.frames();
        int at = uploadFrame[slot] == frame ? uploadAt[slot] : 0; // else the slot's last frame has finished
        if (at + pending > uploadCapacity[slot]) {
            if (slots.isSingle() && pending <= uploadCapacity[slot]) {
                at = 0; // one copy: the caller waited for the frames before
            } else {
                // A bigger buffer; this frame may still read the old one, so it goes once the frame is done.
                int grown = Math.min(capacity, Math.max(Math.max(pending, FIRST_UPLOAD_RECORDS),
                    2 * uploadCapacity[slot]));
                GpuBuffer bigger = uploadBuffer(grown);
                if (uploads[slot] != null) {
                    retiring.add(uploads[slot]);
                }
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
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, SCATTER_PUSH);
            scatter.bind(commands);
            dispatch(commands, push, target.deviceAddress() + 4L * at, target.deviceAddress() + changesAt, pending);
        }
        for (int e = 0; e < pending; e++) {
            entryOf[indices.getAtIndex(ValueLayout.JAVA_INT, e)] = -1;
        }
    }

    /** Each chunk's changes scattered where they are; the chunks come back as spares once the frame finishes. */
    private void scatterChunks(VkCommandBuffer commands) {
        long changesAt = changesOffset(chunkEntries);
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, SCATTER_PUSH);
            scatter.bind(commands);
            for (int c = 0; c < openCount; c++) {
                Chunk chunk = open[c];
                int count = Math.min(chunkEntries, pending - c * chunkEntries);
                dispatch(commands, push, chunk.address, chunk.address + changesAt, count);
                for (int e = 0; e < count; e++) {
                    entryOf[chunk.memory.getAtIndex(ValueLayout.JAVA_INT, e)] = -1;
                }
                retiring.add(chunk);
                open[c] = null;
            }
        }
        openCount = 0;
    }

    /** Scatters {@code count} changes, their indices at {@code indices} and records at {@code from}. */
    private void dispatch(VkCommandBuffer commands, long push, long indices, long from, int count) {
        int words = recordBytes / 16;
        long threads = (long) count * words;
        memPutLong(push + ScatterPush.INDICES, indices);
        memPutLong(push + ScatterPush.CHANGES, from);
        memPutLong(push + ScatterPush.RECORDS, records.deviceAddress());
        memPutInt(push + ScatterPush.COUNT, count);
        memPutInt(push + ScatterPush.WORDS, words);
        for (long first = 0; first < threads; first += 64L * MAX_GROUPS) {
            memPutInt(push + ScatterPush.FIRST, (int) first);
            scatter.push(commands, push, SCATTER_PUSH);
            Commands.dispatch(commands, (int) Math.min(MAX_GROUPS, (threads - first + 63) / 64), 1, 1);
        }
    }

    /**
     * Copies the records from the buffer they grew out of into the new one, zeros past them, with barriers of its own:
     * after whatever wrote the old one, before the scatter and whatever reads the new one.
     */
    private void copyGrown(VkCommandBuffer commands) {
        GpuBuffer old = grownFrom;
        grownFrom = null;
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
        try (MemoryStack stack = stackPush()) {
            long region = stack.ncalloc(VkBufferCopy.ALIGNOF, 1, VkBufferCopy.SIZEOF);
            memPutLong(region + VkBufferCopy.SIZE, old.size());
            Commands.copyBuffer(commands, old.handle(), records.handle(), 1, region);
        }
        Commands.fillBuffer(commands, records.handle(), old.size(), records.size() - old.size(), 0);
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        retiring.add(old); // this frame reads it too
    }

    /** An upload buffer for {@code entries} changed records: their indices, then their bytes. */
    private GpuBuffer uploadBuffer(int entries) {
        return GpuBuffer.hostWritten(device, changesOffset(entries) + (long) recordBytes * entries, CHANGES_USAGE);
    }

    /** Where an upload buffer's or a chunk's records start: after an index per entry, aligned for the records. */
    private static long changesOffset(int entries) {
        return (4L * entries + 63) & ~63L;
    }

    /** Frees the buffers: once the GPU no longer reads them. */
    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            slots.unregister(start);
            free();
        }
    }

    /** Frees the buffers, pipeline and memory made. */
    private void free() {
        retiring.close(); // chunks retired come back here and are freed, as closed
        if (grownFrom != null) {
            grownFrom.close();
        }
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
        for (int c = 0; c < openCount; c++) {
            open[c].free();
            open[c] = null;
        }
        openCount = 0;
        freeSpares();
        if (arena != null) {
            arena.close();
        }
    }
}
