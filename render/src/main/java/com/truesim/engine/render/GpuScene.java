package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * The instances of a world, as the GPU draws them: a table of instances by id, each a {@link GeometryPool} mesh at a
 * world position (two floats per axis, about 48 bits: precise anywhere in a large world), rotated and scaled, with its
 * materials, layers and whether it casts shadows. Renderers draw it from any number of views
 * ({@link MeshletRenderer#createView}), each relative to its own camera; several scenes (a world, an editor's gizmos)
 * may fill one table, told apart by their layers.
 *
 * <pre>{@code
 * GpuScene scene = new GpuScene(device, pool, 100_000, graph.frameSlots());
 * scene.setMaterials(materials);
 * int car = scene.add(carMesh, carMaterials, 1, true, x, y, z, qx, qy, qz, qw, 1, 1, 1);   // the lowest free id
 * scene.set(car, carMesh, carMaterials, 1, true, x2, y2, z2, qx, qy, qz, qw, 1, 1, 1);     // moved
 * scene.remove(car);                                                                      // a hole until reused
 * }</pre>
 *
 * <p>Ids are stable: removing an instance leaves a hole the culling skips, and ids are taken lowest first, so whatever
 * the GPU keeps per instance (occlusion history; previous transforms and custom data, as buffers by id) stays with its
 * instance.
 *
 * <p>The instances live in one device-local buffer, the GPU's alone. Setting or removing one writes its 64-byte record
 * once into a list of what changed (an id at most once: setting it again overwrites its entry), and the first pass
 * reading the instances in a frame ({@link #upload}) copies the list into the frame slot's upload buffer in one go and
 * scatters it with a compute pass. So the CPU pays per instance changed, in one sequential write, and frames in flight
 * keep no copies: the queue orders the scatter after the frames before. Given a frame graph's {@link FrameSlots}
 * instances may change every frame; otherwise change them only once the frames using them have finished. Allocates
 * nothing once created.
 */
@AllowAllocation("created rarely")
public final class GpuScene implements AutoCloseable {

    static final int INSTANCE_BYTES = 64;
    private static final int SCATTER_PUSH = 28;
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat FLOAT =
        ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    /** Instance flags above the mesh id, as meshlets/common.slang has them. */
    private static final int LIVE = 1 << 24;
    private static final int CASTS_SHADOWS = 1 << 25;
    private static final int MESH_BITS = (1 << 24) - 1;
    /** Layer 0: where instances are unless set otherwise. */
    private static final int DEFAULT_LAYERS = 1;

    private final GeometryPool pool;
    private final int capacity;
    private final FrameSlots slots;
    private final GpuBuffer instances;  // the GPU's, device-local
    private final Pipeline scatter;
    // What changed since the last upload: entry e is id ids[e] with record e of records; entryOf[id] is its entry or -1.
    private final Arena arena = Arena.ofShared();
    private final MemorySegment ids;
    private final MemorySegment records;
    private final int[] entryOf;
    private int pending;
    // Per frame slot, the upload buffer (ids, then records) and where in it the next upload goes: frames upload once
    // or a few times each, the next frame of the slot starting over (or, with one slot, where the last one ended).
    private final GpuBuffer[] uploads;
    private final long[] uploadFrame;
    private final int[] uploadAt;
    private final long[] used;
    private final Materials defaultMaterials;
    private Materials materials;
    private int count;
    private int highWater;
    private boolean closed;

    /** Room for {@code capacity} instances of {@code pool}'s meshes, one copy: for frames the caller waits for. */
    public GpuScene(VulkanDevice device, GeometryPool pool, int capacity) {
        this(device, pool, capacity, FrameSlots.single());
    }

    /** Room for {@code capacity} instances, a copy per frame of {@code slots} (a frame graph's). */
    public GpuScene(VulkanDevice device, GeometryPool pool, int capacity, FrameSlots slots) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Room for instances: " + capacity);
        }
        this.pool = Objects.requireNonNull(pool, "pool");
        this.capacity = capacity;
        this.slots = Objects.requireNonNull(slots, "slots");
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        instances = GpuBuffer.deviceLocal(device, (long) INSTANCE_BYTES * capacity,
            storage | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        device.submitAndWait(device.graphics(), commands -> vkCmdFillBuffer(commands, instances.handle(), 0,
            instances.size(), 0)); // every id a hole
        try (ShaderModule shader = ShaderModule.load(device, "shaders/meshlets/scatter.spv")) {
            scatter = Pipeline.compute(device, shader, "scatterMain", SCATTER_PUSH);
        }
        ids = arena.allocate(4L * capacity, 64);
        records = arena.allocate((long) INSTANCE_BYTES * capacity, 64);
        entryOf = new int[capacity];
        Arrays.fill(entryOf, -1);
        uploads = new GpuBuffer[slots.count()];
        for (int s = 0; s < uploads.length; s++) {
            uploads[s] = GpuBuffer.hostWritten(device, uploadOffset(capacity) + (long) INSTANCE_BYTES * capacity,
                storage);
        }
        uploadFrame = new long[uploads.length];
        Arrays.fill(uploadFrame, -1);
        uploadAt = new int[uploads.length];
        used = new long[(capacity + 63) / 64];
        defaultMaterials = new Materials(device, 1);
        materials = defaultMaterials;
    }

    public GeometryPool pool() {
        return pool;
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Instances take their materials from {@code table} (null: the default one only): an instance's submesh s takes
     * material {@code material + s}. Read when the frame's commands run, as instances are.
     */
    public void setMaterials(Materials table) {
        this.materials = table == null ? defaultMaterials : table;
    }

    public Materials materials() {
        return materials;
    }

    /**
     * Adds an instance (see {@link #set(int, int, int, int, boolean, double, double, double, float, float, float,
     * float, float, float, float)}) under the lowest free id and returns it, or -1 when the table is full.
     * Allocates nothing.
     */
    public int add(int mesh, int material, int layers, boolean castsShadows, double x, double y, double z, float qx,
        float qy, float qz, float qw, float sx, float sy, float sz) {
        int id = lowestFree();
        if (id >= 0) {
            set(id, mesh, material, layers, castsShadows, x, y, z, qx, qy, qz, qw, sx, sy, sz);
        }
        return id;
    }

    /** {@link #set(int, int, int, float, float, float, float, float, float, float, float, float, float)}, material 0. */
    public void set(int id, int mesh, float x, float y, float z, float qx, float qy, float qz, float qw, float sx,
        float sy, float sz) {
        set(id, mesh, 0, x, y, z, qx, qy, qz, qw, sx, sy, sz);
    }

    /** {@link #set(int, int, int, double, double, double, float, float, float, float, float, float, float)}. */
    public void set(int id, int mesh, int material, float x, float y, float z, float qx, float qy, float qz, float qw,
        float sx, float sy, float sz) {
        set(id, mesh, material, (double) x, y, z, qx, qy, qz, qw, sx, sy, sz);
    }

    /** {@link #set(int, int, int, int, boolean, double, double, double, float, float, float, float, float, float,
     * float)} on layer 0, casting shadows. */
    public void set(int id, int mesh, int material, double x, double y, double z, float qx, float qy, float qz,
        float qw, float sx, float sy, float sz) {
        set(id, mesh, material, DEFAULT_LAYERS, true, x, y, z, qx, qy, qz, qw, sx, sy, sz);
    }

    /**
     * Sets instance {@code id} (taking the id if free): mesh {@code mesh} of the pool at world position ({@code x},
     * {@code y}, {@code z}), rotated by quaternion ({@code qx}, {@code qy}, {@code qz}, {@code qw}) and scaled, its
     * submeshes with materials from {@code material} on, on the layers of {@code layers} (bit n: layer n; drawn in views
     * of any of them) and casting shadows or not (shadow views and shadow rays leave out those that do not). Each view
     * draws it relative to its camera, so it is written once, not as cameras move. Allocates nothing.
     */
    public void set(int id, int mesh, int material, int layers, boolean castsShadows, double x, double y, double z,
        float qx, float qy, float qz, float qw, float sx, float sy, float sz) {
        if (id < 0 || id >= capacity || !pool.contains(mesh) || mesh > MESH_BITS || material < 0) {
            throw new IllegalArgumentException("No instance " + id + ", mesh " + mesh + " or material " + material);
        }
        if (!contains(id)) {
            used[id >>> 6] |= 1L << id;
            count++;
            highWater = Math.max(highWater, id + 1);
        }
        MemorySegment m = records;
        long o = (long) INSTANCE_BYTES * entry(id);
        m.set(FLOAT, o, qx);
        m.set(FLOAT, o + 4, qy);
        m.set(FLOAT, o + 8, qz);
        m.set(FLOAT, o + 12, qw);
        float hx = (float) x;
        float hy = (float) y;
        float hz = (float) z;
        m.set(FLOAT, o + 16, hx);
        m.set(FLOAT, o + 20, hy);
        m.set(FLOAT, o + 24, hz);
        m.set(INT, o + 28, mesh | LIVE | (castsShadows ? CASTS_SHADOWS : 0));
        m.set(FLOAT, o + 32, sx);
        m.set(FLOAT, o + 36, sy);
        m.set(FLOAT, o + 40, sz);
        m.set(INT, o + 44, material);
        m.set(FLOAT, o + 48, (float) (x - hx));
        m.set(FLOAT, o + 52, (float) (y - hy));
        m.set(FLOAT, o + 56, (float) (z - hz));
        m.set(INT, o + 60, layers);
    }

    /** {@code id}'s entry in the list of changes, made if it has none yet. */
    private int entry(int id) {
        int entry = entryOf[id];
        if (entry < 0) {
            entry = pending++;
            entryOf[id] = entry;
            ids.setAtIndex(ValueLayout.JAVA_INT, entry, id);
        }
        return entry;
    }

    /**
     * Removes instance {@code id}, if there is one: its id is a hole from now on, culled at no cost but a thread, and
     * free for the next instance. Allocates nothing.
     */
    public void remove(int id) {
        Objects.checkIndex(id, capacity);
        if (!contains(id)) {
            return;
        }
        long o = (long) INSTANCE_BYTES * entry(id);
        for (int word = 0; word < INSTANCE_BYTES; word += 8) {
            records.set(ValueLayout.JAVA_LONG_UNALIGNED, o + word, 0); // a hole
        }
        used[id >>> 6] &= ~(1L << id);
        count--;
        while (highWater > 0 && !contains(highWater - 1)) {
            highWater--;
        }
    }

    /** Removes every instance from id {@code id} on. Allocates nothing. */
    public void removeFrom(int id) {
        for (int i = highWater - 1; i >= Math.max(id, 0); i--) {
            remove(i);
        }
    }

    /** Whether id {@code id} is an instance's. */
    public boolean contains(int id) {
        return id >= 0 && id < capacity && (used[id >>> 6] & 1L << id) != 0;
    }

    /** How many instances there are. */
    public int count() {
        return count;
    }

    /** The ids culled: from 0 up to the highest in use, holes included. */
    public int highWater() {
        return highWater;
    }

    /** The lowest free id, which {@link #add} takes next, or -1 when the table is full. */
    public int freeId() {
        return lowestFree();
    }

    private int lowestFree() {
        for (int w = 0; w < used.length; w++) {
            long free = ~used[w];
            if (free != 0) {
                int id = (w << 6) + Long.numberOfTrailingZeros(free);
                return id < capacity ? id : -1;
            }
        }
        return -1;
    }

    /** How many instances changed since the last upload: what the next one scatters. */
    public int pendingCount() {
        return pending;
    }

    /**
     * Brings the GPU's instances up to date, for passes reading them next in {@code commands}: the changes since the
     * last upload are copied into the open frame slot's upload buffer and scattered by a compute pass, ordered after
     * whatever read the instances before (earlier frames too: the queue's order) and before what reads them after.
     * Nothing without changes, so every pass reading instances calls it first: the first in a frame uploads. Allocates
     * nothing.
     */
    public synchronized void upload(VkCommandBuffer commands) {
        if (pending == 0) {
            return;
        }
        int slot = Math.max(slots.current(), 0);
        GpuBuffer target = uploads[slot];
        long frame = slots.frames();
        int at = uploadAt[slot];
        if (uploadFrame[slot] != frame) {
            at = 0; // the slot's last frame has finished
        } else if (at + pending > capacity) {
            if (!slots.isFinished(slots.retireAt())) {
                throw new IllegalStateException("More instances changed in one frame than the scene holds: change "
                    + "each at most once between the passes of a frame");
            }
            at = 0; // one copy: the caller waited for the frames before
        }
        uploadFrame[slot] = frame;
        uploadAt[slot] = at + pending;
        MemorySegment memory = target.memory();
        long recordsAt = uploadOffset(capacity);
        MemorySegment.copy(ids, 0, memory, 4L * at, 4L * pending);
        MemorySegment.copy(records, 0, memory, recordsAt + (long) INSTANCE_BYTES * at, (long) INSTANCE_BYTES * pending);
        // Whatever read the instances before, in this frame or those before, has read them.
        MeshletRenderer.barrier(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, 0, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        try (org.lwjgl.system.MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, SCATTER_PUSH);
            memPutLong(push, target.deviceAddress() + 4L * at);
            memPutLong(push + 8, target.deviceAddress() + recordsAt + (long) INSTANCE_BYTES * at);
            memPutLong(push + 16, instances.deviceAddress());
            memPutInt(push + 24, pending);
            scatter.bind(commands);
            scatter.push(commands, push, SCATTER_PUSH);
            vkCmdDispatch(commands, (pending + 63) / 64, 1, 1);
        }
        MeshletRenderer.barrier(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT);
        for (int e = 0; e < pending; e++) {
            entryOf[ids.getAtIndex(ValueLayout.JAVA_INT, e)] = -1;
        }
        pending = 0;
    }

    /** Where an upload buffer's records start: after an id per instance, aligned for the records. */
    private static long uploadOffset(int capacity) {
        return (4L * capacity + 63) & ~63L;
    }

    /** The instance buffer, for passes drawing the instances (after {@link #upload}). */
    GpuBuffer instanceBuffer() {
        return instances;
    }

    /** The material table's address, for passes shading the instances. */
    long materialsAddress() {
        return materials.deviceAddress();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            instances.close();
            scatter.close();
            for (GpuBuffer upload : uploads) {
                upload.close();
            }
            arena.close();
            defaultMaterials.close();
        }
    }
}
