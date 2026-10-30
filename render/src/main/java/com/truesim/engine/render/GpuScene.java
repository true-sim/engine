package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.layout.InstanceLayout;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.RecordBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
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
 * <p>The instances live in a {@link RecordBuffer}, device-local and the GPU's alone: setting or removing one writes its
 * 64-byte record once into the list of changes, scattered on the GPU at the start of the next frame. So the CPU pays
 * per instance changed, and frames in flight keep no copies. Given a frame graph's {@link FrameSlots} instances may
 * change every frame; otherwise change them only once the frames using them have finished.
 *
 * <p>Custom passes may read the instances ({@link #instanceAddress}, {@link #instanceBuffer}), laid out as
 * {@code meshlets/common.slang}'s {@code Instance}: {@link #INSTANCE_BYTES} bytes per id, a hole's mesh word 0.
 * Allocates nothing once created.
 */
@AllowAllocation("created rarely")
public final class GpuScene implements AutoCloseable {

    /** Bytes per instance record. */
    public static final int INSTANCE_BYTES = InstanceLayout.BYTES;
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
    private final RecordBuffer instances; // the GPU's, device-local, uploading at the start of each frame
    private final long[] used;
    private int firstFreeWord; // every word of used before it is full: the search for a free id starts here
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
        instances = new RecordBuffer(device, INSTANCE_BYTES, capacity, slots); // zeros: every id a hole
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

    /**
     * {@link #set(int, int, int, float, float, float, float, float, float, float, float, float, float)}, material 0.
     */
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
        if (id < 0 || id >= capacity) {
            throw new IllegalArgumentException("No instance " + id);
        }
        check(mesh, material);
        write(claim(id), mesh, material, layers, castsShadows, x, y, z, qx, qy, qz, qw, sx, sy, sz);
    }

    /**
     * Takes instance {@code id} (if free) and returns its entry in the changes of this frame, for {@link #write}: the
     * first half of {@link #set}, so that many instances can be claimed on one thread and written on several. Claim on
     * the thread changing the scene; write before the next upload. Allocates nothing.
     */
    public int claim(int id) {
        Objects.checkIndex(id, capacity);
        if (!contains(id)) {
            used[id >>> 6] |= 1L << id;
            count++;
            highWater = Math.max(highWater, id + 1);
        }
        return instances.claim(id);
    }

    /**
     * Writes the instance of entry {@code entry} ({@link #claim}), as {@link #set} does. Safe from any thread for
     * distinct entries, while nothing claims, removes or uploads. Allocates nothing.
     */
    public void write(int entry, int mesh, int material, int layers, boolean castsShadows, double x, double y,
        double z, float qx, float qy, float qz, float qw, float sx, float sy, float sz) {
        long o = instances.offset(entry);
        check(mesh, material);
        MemorySegment m = instances.changes();
        m.set(FLOAT, o + InstanceLayout.ROTATION, qx);
        m.set(FLOAT, o + InstanceLayout.ROTATION + 4, qy);
        m.set(FLOAT, o + InstanceLayout.ROTATION + 8, qz);
        m.set(FLOAT, o + InstanceLayout.ROTATION + 12, qw);
        float hx = (float) x;
        float hy = (float) y;
        float hz = (float) z;
        m.set(FLOAT, o + InstanceLayout.POSITION, hx);
        m.set(FLOAT, o + InstanceLayout.POSITION + 4, hy);
        m.set(FLOAT, o + InstanceLayout.POSITION + 8, hz);
        m.set(INT, o + InstanceLayout.MESH, mesh | LIVE | (castsShadows ? CASTS_SHADOWS : 0));
        m.set(FLOAT, o + InstanceLayout.SCALE, sx);
        m.set(FLOAT, o + InstanceLayout.SCALE + 4, sy);
        m.set(FLOAT, o + InstanceLayout.SCALE + 8, sz);
        m.set(INT, o + InstanceLayout.MATERIAL_BASE, material);
        m.set(FLOAT, o + InstanceLayout.POSITION_LOW, (float) (x - hx));
        m.set(FLOAT, o + InstanceLayout.POSITION_LOW + 4, (float) (y - hy));
        m.set(FLOAT, o + InstanceLayout.POSITION_LOW + 8, (float) (z - hz));
        m.set(INT, o + InstanceLayout.LAYERS, layers);
    }

    private void check(int mesh, int material) {
        if (!pool.contains(mesh) || mesh > MESH_BITS || material < 0) {
            throw new IllegalArgumentException("No mesh " + mesh + " or material " + material);
        }
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
        instances.clear(id); // a hole
        used[id >>> 6] &= ~(1L << id);
        count--;
        firstFreeWord = Math.min(firstFreeWord, id >>> 6);
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
        for (int w = firstFreeWord; w < used.length; w++) {
            long free = ~used[w];
            if (free != 0) {
                firstFreeWord = w;
                int id = (w << 6) + Long.numberOfTrailingZeros(free);
                return id < capacity ? id : -1;
            }
        }
        return -1;
    }

    /** How many instances changed since the last upload: what the next one scatters. */
    public int pendingCount() {
        return instances.pendingCount();
    }

    /**
     * Brings the GPU's instances up to date for passes reading them next in {@code commands}
     * ({@link RecordBuffer#upload}). Frames recorded by a frame graph do it first; passes reading instances call it
     * too, for frames recorded otherwise. Allocates nothing.
     */
    public void upload(VkCommandBuffer commands) {
        instances.upload(commands);
    }

    /** The instances' device address, for shaders: id i's record at {@code instanceAddress() + i * INSTANCE_BYTES}. */
    public long instanceAddress() {
        return instances.deviceAddress();
    }

    /** The device-local buffer holding the instances, e.g. to declare in a frame graph ({@code importBuffer}). */
    public GpuBuffer instanceBuffer() {
        return instances.buffer();
    }

    /** The material table's device address, for passes shading the instances (as {@code shading.slang} reads it). */
    public long materialsAddress() {
        return materials.deviceAddress();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            instances.close();
            defaultMaterials.close();
        }
    }
}
