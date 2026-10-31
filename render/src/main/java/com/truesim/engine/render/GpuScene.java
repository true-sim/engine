package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.ExternalBuffer;
import com.truesim.engine.render.layout.CommonConstants;
import com.truesim.engine.render.layout.InstanceLayout;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.RecordBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * GpuScene.Instance car = new GpuScene.Instance().mesh(carMesh).material(carMaterials).position(x, y, z);
 * int id = scene.add(car);                                 // the lowest free id
 * scene.set(id, car.position(x2, y2, z2).rotation(qx, qy, qz, qw)); // moved: the same description, reused
 * scene.remove(id);                                        // a hole until reused
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
 * <p>The table grows as instances come: the capacity it is made with is where it starts. Taking an id past it doubles
 * the table (or more); the instances are copied over on the GPU in the next frame and the old buffer is freed once no
 * frame reads it. Views' per-instance buffers grow with it, and a frame graph rebuilds once for the new size.
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
    private static final int LIVE = CommonConstants.INSTANCE_LIVE;
    private static final int CASTS_SHADOWS = CommonConstants.INSTANCE_CASTS_SHADOWS;
    private static final int MESH_BITS = (1 << 24) - 1;
    /** Layer 0: where instances are unless set otherwise. */
    private static final int DEFAULT_LAYERS = 1;

    private final GeometryPool pool;
    private int capacity;
    private final RecordBuffer instances; // the GPU's, device-local, uploading at the start of each frame
    private final ExternalBuffer graphBuffer; // the instances as a frame graph imports them, set anew as they grow
    private final List<Runnable> growth = new ArrayList<>(); // what follows the table's size: views' buffers
    private long[] used;
    private int firstFreeWord; // every word of used before it is full: the search for a free id starts here
    private final FrameSlots slots;
    private final Materials defaultMaterials;
    private Materials materials;
    private int count;
    private long version; // changes with every claim and removal
    private int highWater;
    private boolean closed;

    /** Room for {@code capacity} instances of {@code pool}'s meshes to start with, for the frames of the pool's slots. */
    public GpuScene(VulkanDevice device, GeometryPool pool, int capacity) {
        this(device, pool, capacity, pool.frameSlots());
    }

    /** Room for {@code capacity} instances to start with, for the frames of {@code slots} (a frame graph's). */
    public GpuScene(VulkanDevice device, GeometryPool pool, int capacity, FrameSlots slots) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Room for instances: " + capacity);
        }
        this.pool = Objects.requireNonNull(pool, "pool");
        this.capacity = capacity;
        this.slots = Objects.requireNonNull(slots, "slots");
        instances = new RecordBuffer(device, INSTANCE_BYTES, capacity, slots); // zeros: every id a hole
        graphBuffer = new ExternalBuffer("instances", instances.buffer());
        used = new long[(capacity + 63) / 64];
        defaultMaterials = new Materials(device, 1);
        materials = defaultMaterials;
    }

    /**
     * What an instance is ({@link #add(Instance)}, {@link #set(int, Instance)}): its mesh, materials from
     * {@code material} on, layers, whether it casts shadows, world position (doubles), rotation and scale; by name, not
     * fifteen numbers in a row. Mutable and reused: setting it allocates nothing. By default material 0, layer 0,
     * casting shadows, at the origin, not turned, scale 1.
     */
    public static final class Instance {
        private int mesh = -1;
        private int material;
        private int layers = DEFAULT_LAYERS;
        private boolean castsShadows = true;
        private double x;
        private double y;
        private double z;
        private float qx;
        private float qy;
        private float qz;
        private float qw = 1;
        private float sx = 1;
        private float sy = 1;
        private float sz = 1;

        /** The pool's mesh it draws. */
        public Instance mesh(int mesh) {
            this.mesh = mesh;
            return this;
        }

        /** Its first material: submesh s takes material {@code material + s}. */
        public Instance material(int material) {
            this.material = material;
            return this;
        }

        /** The layers it is on, a bit each (bit n: layer n): drawn in views of any of them. */
        public Instance layers(int layers) {
            this.layers = layers;
            return this;
        }

        /** Whether it casts shadows: shadow views and shadow rays leave out those that do not. */
        public Instance castsShadows(boolean castsShadows) {
            this.castsShadows = castsShadows;
            return this;
        }

        /** Its world position. */
        public Instance position(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        /** Its rotation, a unit quaternion. */
        public Instance rotation(float qx, float qy, float qz, float qw) {
            this.qx = qx;
            this.qy = qy;
            this.qz = qz;
            this.qw = qw;
            return this;
        }

        /** Its scale along its axes. */
        public Instance scale(float sx, float sy, float sz) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            return this;
        }

        /** Its scale, the same along every axis. */
        public Instance scale(float s) {
            return scale(s, s, s);
        }
    }

    /** Adds an instance as {@code instance} describes, under the lowest free id, and returns it. Allocates nothing. */
    public int add(Instance instance) {
        int id = freeId();
        set(id, instance);
        return id;
    }

    /** Sets instance {@code id} as {@code instance} describes (taking the id if free). Allocates nothing. */
    public void set(int id, Instance instance) {
        set(id, instance.mesh, instance.material, instance.layers, instance.castsShadows, instance.x, instance.y,
            instance.z, instance.qx, instance.qy, instance.qz, instance.qw, instance.sx, instance.sy, instance.sz);
    }

    /** Writes the instance of entry {@code entry} ({@link #claim}) as {@code instance} describes. */
    public void write(int entry, Instance instance) {
        write(entry, instance.mesh, instance.material, instance.layers, instance.castsShadows, instance.x, instance.y,
            instance.z, instance.qx, instance.qy, instance.qz, instance.qw, instance.sx, instance.sy, instance.sz);
    }

    public GeometryPool pool() {
        return pool;
    }

    /** The frames its instances keep a copy per frame for: what renderers of it are made for too. */
    public FrameSlots frameSlots() {
        return slots;
    }

    /** How many ids the table has room for now; it grows when one past it is taken. */
    public int capacity() {
        return capacity;
    }

    /** Grows the table to {@code atLeast} ids, doubling at least, and tells what follows its size. */
    @AllowAllocation("growing, rarely")
    private void grow(int atLeast) {
        int grown = (int) Math.min(Integer.MAX_VALUE - 64, Math.max(2L * capacity, atLeast));
        instances.grow(grown);
        used = Arrays.copyOf(used, (grown + 63) / 64);
        capacity = grown;
        graphBuffer.set(instances.buffer());
        for (Runnable follower : growth) {
            follower.run();
        }
    }

    /** Runs {@code follower} whenever the table grows (a renderer growing its views' per-instance buffers). */
    @AllowAllocation("once per renderer")
    void onGrow(Runnable follower) {
        growth.add(follower);
    }

    void stopFollowing(Runnable follower) {
        growth.remove(follower);
    }

    /** The instances as a frame graph imports them: set anew as the table grows, so the graph rebuilds for its size. */
    ExternalBuffer graphBuffer() {
        return graphBuffer;
    }

    /**
     * Instances take their materials from {@code table} (null: the default one only): an instance's submesh s takes
     * material {@code material + s}. Read when the frame's commands run, as instances are.
     */
    public void setMaterials(Materials table) {
        this.materials = table == null ? defaultMaterials : table;
    }

    /** Whether instances take their materials from a table set ({@link #setMaterials}), not the default one only. */
    public boolean drawsMaterials() {
        return materials != defaultMaterials;
    }

    public Materials materials() {
        return materials;
    }

    /**
     * Adds an instance (see {@link #set(int, int, int, int, boolean, double, double, double, float, float, float,
     * float, float, float, float)}) under the lowest free id and returns it; the table grows if it is full.
     * Allocates nothing, unless it grows.
     */
    public int add(int mesh, int material, int layers, boolean castsShadows, double x, double y, double z, float qx,
        float qy, float qz, float qw, float sx, float sy, float sz) {
        int id = freeId();
        set(id, mesh, material, layers, castsShadows, x, y, z, qx, qy, qz, qw, sx, sy, sz);
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
     * draws it relative to its camera, so it is written once, not as cameras move. An id past the capacity grows the
     * table. Allocates nothing, unless it grows.
     */
    public void set(int id, int mesh, int material, int layers, boolean castsShadows, double x, double y, double z,
        float qx, float qy, float qz, float qw, float sx, float sy, float sz) {
        if (id < 0) {
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
        if (id < 0) {
            throw new IndexOutOfBoundsException("No instance " + id);
        }
        if (id >= capacity) {
            grow(id + 1);
        }
        version++;
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
        version++;
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

    /** The lowest free id, which {@link #add} takes next: the capacity when the table is full (it grows then). */
    public int freeId() {
        int id = lowestFree();
        return id < 0 ? capacity : id;
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

    /**
     * A number that changes whenever an instance is claimed ({@link #set}, {@link #add}, {@link #claim}) or removed:
     * what was built from the instances (a top level) stands while it is the same.
     */
    public long version() {
        return version;
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

    /** From now on a frame graph's scene upload pass ({@link SceneData}) uploads the instances, not frame starts. */
    void uploadInPasses() {
        instances.uploadInPasses();
    }

    /** Uploads the instances for that pass, which declares it writes them with compute. */
    void uploadInPass(VkCommandBuffer commands) {
        instances.uploadInPass(commands);
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
