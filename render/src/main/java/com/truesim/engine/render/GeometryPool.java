package com.truesim.engine.render;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.ExternalBuffer;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.FrameUploads;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Every mesh's GPU data in shared device-local buffers, one per kind, as scene's {@link MeshData} lays it out:
 * positions, attributes, meshlets, cluster LOD records, meshlet vertices and triangles, and the fallback index buffer.
 * A table entry per mesh tells shaders where its data starts. Shaders reach all of it by buffer device address, so any
 * number of meshes draws in one pass.
 *
 * <pre>{@code
 * try (GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 20, 16_384, 256))) {
 *     int car = pool.add(carMesh);     // uploaded once; instances then refer to it by this number
 * }
 * }</pre>
 *
 * <p>Meshes are removed with {@link #remove}: their ranges are reused once no frame in flight can draw them (the
 * pool's {@link FrameSlots}). Ranges are handed out first fit, so the same adds and removes give the same layout.
 *
 * <p>The pool grows as meshes come: the capacity it is made with is where it starts. A buffer without room for a mesh
 * is replaced by one twice its size (or more, for a large mesh), its contents copied over on the GPU in the next
 * frame, the old one freed once no frame reads it; the mesh table grows alike. Shaders reach the buffers by address,
 * read anew each frame, and a frame graph rebuilds once for the new sizes.
 *
 * <p>Given a frame graph's {@link FrameSlots}, adding a mesh does not wait for the GPU: its data is staged at once and
 * copied in at the start of the next frame ({@link FrameUploads}), so it draws from the next frame recorded on.
 * Without, adding waits until the upload has finished. Use it from one thread, the one recording frames.
 */
@AllowAllocation("created rarely; adding meshes uploads them")
public final class GeometryPool implements AutoCloseable {

    /** Bytes per mesh table entry. */
    public static final int MESH_ENTRY_BYTES = 96;

    /** The most detail levels a mesh may have here. */
    public static final int MAX_LEVELS = 32;

    /** The most vertices and triangles a meshlet may hold for the mesh shader path: the builder's defaults. */
    public static final int MESH_SHADER_VERTICES = 64;
    public static final int MESH_SHADER_TRIANGLES = 124;

    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /**
     * How much the pool holds before it grows: vertices, meshlets (and as many triangles as 124 per meshlet allow) and
     * meshes. Attributes take up to 28 bytes per vertex.
     */
    public record Capacity(int vertices, int meshlets, int triangles, int meshes) {

        public Capacity {
            if (vertices < 1 || meshlets < 1 || triangles < 1 || meshes < 1) {
                throw new IllegalArgumentException("Capacities must be positive: " + vertices + ", " + meshlets
                    + ", " + triangles + ", " + meshes);
            }
        }

        /** Room for {@code vertices} vertices, {@code meshlets} full meshlets and {@code meshes} meshes. */
        public static Capacity of(int vertices, int meshlets, int meshes) {
            return new Capacity(vertices, meshlets, meshlets * 124, meshes);
        }
    }

    private Capacity capacity;
    private final VulkanDevice device;
    private final int positionUsage;
    private final int dataUsage;
    private final int tableUsage;
    private GpuBuffer positions;
    private GpuBuffer attributes;
    private GpuBuffer meshletVertices;
    private GpuBuffer meshletTriangles;
    private GpuBuffer meshlets;
    private GpuBuffer clusterLod;
    private GpuBuffer meshTable;
    private GpuBuffer levels;
    // The positions as the GPU has them at a frame's start: the replaced buffer until the frame copying it records.
    private volatile GpuBuffer positionsOnGpu;
    // The buffers as a frame graph imports them, set anew when one grows.
    private final ExternalBuffer[] graphBuffers;
    private final FrameSlots slots;
    private final FrameUploads uploads;
    // Buffers replaced by bigger ones: until the frame copying from them records, then until no frame reads them.
    private final List<GpuBuffer> replaced = new ArrayList<>();
    private final Retiring oldBuffers;
    // Where meshes live: ranges of each kind of data, and mesh numbers.
    private final RangeAllocator vertexRanges;
    private final RangeAllocator attributeRanges;
    private final RangeAllocator meshletVertexRanges;
    private final RangeAllocator meshletRanges;
    private final RangeAllocator triangleRanges;
    private int[] freeIds;
    private int freeIdCount;
    private int idCount; // ids handed out so far, from 0; freed ones are reused first
    // Per mesh number: its ranges (starts and lengths), and whether it is live.
    private int[] vertexStart;
    private int[] vertexLength;
    private long[] attributeStart;
    private long[] attributeLength;
    private int[] meshletVertexStart;
    private int[] meshletVertexLength;
    private int[] meshletStart;
    private int[] meshletLength;
    private long[] triangleStart;
    private long[] triangleLength;
    private boolean[] live;
    private boolean[] fits;
    // Removed meshes waiting for the frames that may draw them: their numbers and when they are free.
    private int[] retiring;
    private long[] retireAt;
    private int retiringCount;
    private int vertexCount;
    private int meshletCount;
    private int meshCount;
    private int unfit; // live meshes too large for the mesh shader path
    private boolean closed;

    /** Where a pool made without a capacity starts: a few meshes' worth, grown as meshes come. */
    public static final Capacity START = Capacity.of(1 << 16, 1024, 64);

    /** A pool starting small ({@link #START}), for frames the caller waits for. */
    public GeometryPool(VulkanDevice device) {
        this(device, START);
    }

    /** A pool starting small ({@link #START}), for the frames of {@code slots} (a frame graph's). */
    public GeometryPool(VulkanDevice device, FrameSlots slots) {
        this(device, START, slots);
    }

    /**
     * A pool starting at {@code capacity}, for frames the caller waits for between {@link #remove removing} a mesh and
     * adding another.
     */
    public GeometryPool(VulkanDevice device, Capacity capacity) {
        this(device, capacity, FrameSlots.single());
    }

    /**
     * A pool starting at {@code capacity} whose removed meshes keep their data until no frame of {@code slots} (a frame
     * graph's) can still draw them. Sized for what it will hold, it never grows.
     */
    public GeometryPool(VulkanDevice device, Capacity capacity, FrameSlots slots) {
        this.device = Objects.requireNonNull(device, "device");
        this.capacity = capacity;
        this.slots = Objects.requireNonNull(slots, "slots");
        this.uploads = new FrameUploads(device, slots);
        this.oldBuffers = new Retiring(slots);
        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        long triangleIndices = 3L * capacity.triangles();
        // Positions are ray tracing's build input too, where the device traces rays: bottom-level acceleration
        // structures read them as they are. Each is copied from when it grows.
        int buildInput = device.rayTracing() ? VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
            : 0;
        tableUsage = usage;
        dataUsage = usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        positionUsage = dataUsage | buildInput;
        positions = GpuBuffer.deviceLocal(device, (long) MeshData.POSITION_BYTES * capacity.vertices(), positionUsage);
        positionsOnGpu = positions;
        attributes = GpuBuffer.deviceLocal(device, 28L * capacity.vertices(), dataUsage);
        meshletVertices = GpuBuffer.deviceLocal(device, 4L * capacity.meshlets() * 255, dataUsage);
        meshletTriangles = GpuBuffer.deviceLocal(device, (triangleIndices + 3) & ~3L, dataUsage);
        meshlets = GpuBuffer.deviceLocal(device, (long) MeshData.MESHLET_BYTES * capacity.meshlets(), dataUsage);
        clusterLod = GpuBuffer.deviceLocal(device, (long) MeshData.CLUSTER_LOD_BYTES * capacity.meshlets(), dataUsage);
        meshTable = GpuBuffer.hostVisible(device, (long) MESH_ENTRY_BYTES * capacity.meshes(), usage);
        levels = GpuBuffer.hostVisible(device, 8L * MAX_LEVELS * capacity.meshes(), usage);
        graphBuffers = new ExternalBuffer[] {new ExternalBuffer("positions", positions),
            new ExternalBuffer("attributes", attributes), new ExternalBuffer("meshlets", meshlets),
            new ExternalBuffer("meshlet vertices", meshletVertices),
            new ExternalBuffer("meshlet triangles", meshletTriangles), new ExternalBuffer("cluster LOD", clusterLod)};
        int meshes = capacity.meshes();
        vertexRanges = new RangeAllocator(capacity.vertices());
        attributeRanges = new RangeAllocator(attributes.size());
        meshletVertexRanges = new RangeAllocator(meshletVertices.size() / 4);
        meshletRanges = new RangeAllocator(capacity.meshlets());
        // Whole words within 3 indices per triangle of capacity, which other tables laid out alike can rely on.
        triangleRanges = new RangeAllocator(3L * capacity.triangles() & ~3L);
        freeIds = new int[meshes];
        vertexStart = new int[meshes];
        vertexLength = new int[meshes];
        attributeStart = new long[meshes];
        attributeLength = new long[meshes];
        meshletVertexStart = new int[meshes];
        meshletVertexLength = new int[meshes];
        meshletStart = new int[meshes];
        meshletLength = new int[meshes];
        triangleStart = new long[meshes];
        triangleLength = new long[meshes];
        live = new boolean[meshes];
        fits = new boolean[meshes];
        retiring = new int[meshes];
        retireAt = new long[meshes];
    }

    /**
     * Uploads {@code mesh} and returns its number, for instances to draw it by: a free one, the lowest removed one first.
     * Drawn from the next frame on (or, without frame slots, waits until the upload has finished). The pool grows if
     * it lacks room.
     *
     * @throws IllegalStateException if the mesh has more than {@link #MAX_LEVELS} detail levels
     */
    public int add(MeshData mesh) {
        int id = tryAdd(mesh);
        if (id < 0) {
            throw noRoom(mesh);
        }
        return id;
    }

    /**
     * A mesh made ready on any thread ({@link #prepare}): its data staged in one buffer, the costly part of adding one
     * (megabytes copied for a large mesh, and memory allocated). Add it on the frame's thread with
     * {@link #add(Prepared)}; close it if it is not added.
     */
    public static final class Prepared implements AutoCloseable {
        private final MeshData mesh;
        private final GpuBuffer staging;
        // Where each kind of data starts in the staging buffer: positions at 0.
        private final long attributes;
        private final long meshletVertices;
        private final long triangles;
        private final long meshlets;
        private final long clusterLod;
        private boolean taken;

        private Prepared(MeshData mesh, GpuBuffer staging, long attributes, long meshletVertices, long triangles,
            long meshlets, long clusterLod) {
            this.mesh = mesh;
            this.staging = staging;
            this.attributes = attributes;
            this.meshletVertices = meshletVertices;
            this.triangles = triangles;
            this.meshlets = meshlets;
            this.clusterLod = clusterLod;
        }

        /** The mesh it holds. */
        public MeshData mesh() {
            return mesh;
        }

        /** Frees it, unless it was added. */
        @Override
        public void close() {
            if (!taken) {
                taken = true;
                staging.close();
            }
        }
    }

    /**
     * {@code mesh}'s data staged for {@link #add(Prepared)}, on any thread (a loader's): nothing of the pool is touched,
     * so the frame's thread only places it and records its copies.
     */
    public Prepared prepare(MeshData mesh) {
        long positionBytes = mesh.positions().byteSize();
        long attributes = align(positionBytes);
        long meshletVertices = align(attributes + mesh.attributes().byteSize());
        long triangles = align(meshletVertices + mesh.meshletVertices().byteSize());
        long triangleBytes = (mesh.meshletTriangles().byteSize() + 3) & ~3L; // whole words
        long meshlets = align(triangles + triangleBytes);
        long clusterLod = align(meshlets + mesh.meshlets().byteSize());
        long total = clusterLod + mesh.clusterLod().byteSize();
        GpuBuffer staging = FrameUploads.staging(device, Math.max(16, total));
        MemorySegment memory = staging.memory();
        MemorySegment.copy(mesh.positions(), 0, memory, 0, positionBytes);
        MemorySegment.copy(mesh.attributes(), 0, memory, attributes, mesh.attributes().byteSize());
        MemorySegment.copy(mesh.meshletVertices(), 0, memory, meshletVertices, mesh.meshletVertices().byteSize());
        MemorySegment.copy(mesh.meshletTriangles(), 0, memory, triangles, mesh.meshletTriangles().byteSize());
        memory.asSlice(triangles + mesh.meshletTriangles().byteSize(),
            triangleBytes - mesh.meshletTriangles().byteSize()).fill((byte) 0);
        MemorySegment.copy(mesh.meshlets(), 0, memory, meshlets, mesh.meshlets().byteSize());
        MemorySegment.copy(mesh.clusterLod(), 0, memory, clusterLod, mesh.clusterLod().byteSize());
        return new Prepared(mesh, staging, attributes, meshletVertices, triangles, meshlets, clusterLod);
    }

    private static long align(long offset) {
        return (offset + 15) & ~15L;
    }

    /**
     * Adds {@code prepared} ({@link #prepare}): on the frame's thread, as {@link #add(MeshData)}, but its data is
     * staged already, so only its ranges are placed and its copies recorded. The prepared mesh is the pool's from now
     * on.
     */
    public int add(Prepared prepared) {
        int id = tryAdd(prepared);
        if (id < 0) {
            throw noRoom(prepared.mesh);
        }
        return id;
    }

    /** Like {@link #add(Prepared)}, or -1 for too many levels: the prepared mesh stays the caller's then. */
    public int tryAdd(Prepared prepared) {
        if (prepared.taken) {
            throw new IllegalStateException("The prepared mesh was added or closed already");
        }
        return tryAdd(prepared.mesh, prepared);
    }

    /** Records copies of a prepared mesh's data from its staging buffer into the pool's buffers, at their bases. */
    private void recordPrepared(Prepared prepared, long vertexBase, long attributeBase, long meshletVertexBase,
        long indexBase, long meshletBase) {
        MeshData mesh = prepared.mesh;
        prepared.taken = true;
        // The buffers of now: one may be replaced by a bigger one before the frame records, copying this in.
        GpuBuffer positions = this.positions;
        GpuBuffer attributes = this.attributes;
        GpuBuffer meshletVertices = this.meshletVertices;
        GpuBuffer meshletTriangles = this.meshletTriangles;
        GpuBuffer meshlets = this.meshlets;
        GpuBuffer clusterLod = this.clusterLod;
        uploads.record(prepared.staging, (commands, staging, offset) -> {
            copy(commands, staging, 0, positions, (long) MeshData.POSITION_BYTES * vertexBase,
                mesh.positions().byteSize());
            copy(commands, staging, prepared.attributes, attributes, attributeBase, mesh.attributes().byteSize());
            copy(commands, staging, prepared.meshletVertices, meshletVertices, 4L * meshletVertexBase,
                mesh.meshletVertices().byteSize());
            copy(commands, staging, prepared.triangles, meshletTriangles, indexBase,
                (mesh.meshletTriangles().byteSize() + 3) & ~3L);
            copy(commands, staging, prepared.meshlets, meshlets, (long) MeshData.MESHLET_BYTES * meshletBase,
                mesh.meshlets().byteSize());
            copy(commands, staging, prepared.clusterLod, clusterLod, (long) MeshData.CLUSTER_LOD_BYTES * meshletBase,
                mesh.clusterLod().byteSize());
        });
    }

    private static void copy(VkCommandBuffer commands, GpuBuffer from, long fromOffset, GpuBuffer to, long toOffset,
        long bytes) {
        if (bytes == 0) {
            return;
        }
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            long region = stack.ncalloc(org.lwjgl.vulkan.VkBufferCopy.ALIGNOF, 1, org.lwjgl.vulkan.VkBufferCopy.SIZEOF);
            org.lwjgl.system.MemoryUtil.memPutLong(region + org.lwjgl.vulkan.VkBufferCopy.SRCOFFSET, fromOffset);
            org.lwjgl.system.MemoryUtil.memPutLong(region + org.lwjgl.vulkan.VkBufferCopy.DSTOFFSET, toOffset);
            org.lwjgl.system.MemoryUtil.memPutLong(region + org.lwjgl.vulkan.VkBufferCopy.SIZE, bytes);
            com.truesim.engine.vulkan.Commands.copyBuffer(commands, from.handle(), to.handle(), 1, region);
        }
    }

    /**
     * Adds {@code mesh} as {@link #add} does, or returns -1 if it has more than {@link #MAX_LEVELS} detail levels.
     */
    public int tryAdd(MeshData mesh) {
        return tryAdd(mesh, null);
    }

    /** Places {@code mesh}: its data written now, or copied from {@code prepared}'s staging (if not null). */
    private int tryAdd(MeshData mesh, Prepared prepared) {
        if (closed) {
            throw new IllegalStateException("The pool is closed");
        }
        releaseRetired();
        int meshletVertexTotal = (int) (mesh.meshletVertices().byteSize() / 4);
        long indexTotal = 3L * mesh.triangleCount();
        long indexRange = (indexTotal + 3) & ~3L; // whole words: the last one is padded
        if (mesh.levels().size() > MAX_LEVELS) {
            return -1;
        }
        if (freeIdCount == 0 && idCount == capacity.meshes()) {
            growMeshes();
        }
        // Each kind of data in a range of its own; triangle bytes start on a word, so no word holds two meshes.
        long vertexBase = place(vertexRanges, mesh.vertexCount(), 1);
        long attributeBase = place(attributeRanges, mesh.attributes().byteSize(), 4);
        long meshletVertexBase = place(meshletVertexRanges, meshletVertexTotal, 1);
        long meshletBase = place(meshletRanges, mesh.meshletCount(), 1);
        long indexBase = place(triangleRanges, indexRange, 4);
        boolean fitsMesh = true;
        for (int m = 0; m < mesh.meshletCount() && fitsMesh; m++) {
            fitsMesh = mesh.meshletVertexCount(m) <= MESH_SHADER_VERTICES
                && mesh.meshletTriangleCount(m) <= MESH_SHADER_TRIANGLES;
        }
        int id = freeIdCount > 0 ? takeLowestFreeId() : idCount++;
        // The meshlet records count their vertices and triangles from the mesh's start; shaders add these bases.
        if (prepared != null) {
            recordPrepared(prepared, vertexBase, attributeBase, meshletVertexBase, indexBase, meshletBase);
        } else {
            uploads.write(positions, (long) MeshData.POSITION_BYTES * vertexBase, mesh.positions());
            uploads.write(attributes, attributeBase, mesh.attributes());
            uploads.write(meshletVertices, 4L * meshletVertexBase, mesh.meshletVertices());
            writeTriangles(mesh, indexBase);
            uploads.write(meshlets, (long) MeshData.MESHLET_BYTES * meshletBase, mesh.meshlets());
            uploads.write(clusterLod, (long) MeshData.CLUSTER_LOD_BYTES * meshletBase, mesh.clusterLod());
        }
        MemorySegment entry = meshTable.memory().asSlice((long) MESH_ENTRY_BYTES * id, MESH_ENTRY_BYTES);
        for (int axis = 0; axis < 3; axis++) {
            entry.set(F32, 4L * axis, (float) mesh.boxMin(axis));
            entry.set(F32, 16 + 4L * axis, (float) mesh.boxExtent(axis));
        }
        entry.set(U32, 12, (int) vertexBase);
        entry.set(U32, 28, (int) meshletBase);
        entry.set(F32, 32, (float) mesh.bounds().x());
        entry.set(F32, 36, (float) mesh.bounds().y());
        entry.set(F32, 40, (float) mesh.bounds().z());
        entry.set(F32, 44, (float) mesh.bounds().radius());
        entry.set(U32, 48, mesh.meshletCount());
        entry.set(U32, 52, (int) meshletVertexBase);
        entry.set(U32, 56, (int) indexBase);
        entry.set(U32, 60, (int) attributeBase);
        entry.set(U32, 64, mesh.format().mask());
        entry.set(U32, 68, mesh.format().stride());
        entry.set(U32, 72, MAX_LEVELS * id);
        entry.set(U32, 76, mesh.levels().size());
        writeLevels(mesh, entry, id);
        vertexStart[id] = (int) vertexBase;
        vertexLength[id] = mesh.vertexCount();
        attributeStart[id] = attributeBase;
        attributeLength[id] = mesh.attributes().byteSize();
        meshletVertexStart[id] = (int) meshletVertexBase;
        meshletVertexLength[id] = meshletVertexTotal;
        meshletStart[id] = (int) meshletBase;
        meshletLength[id] = mesh.meshletCount();
        triangleStart[id] = indexBase;
        triangleLength[id] = indexRange;
        live[id] = true;
        fits[id] = fitsMesh;
        unfit += fitsMesh ? 0 : 1;
        vertexCount += mesh.vertexCount();
        meshletCount += mesh.meshletCount();
        meshCount++;
        return id;
    }

    /**
     * Removes mesh {@code mesh}: instances must no longer draw it from the next frame on. Its data stays until no frame
     * in flight can still draw it, then its ranges and number are reused. Allocates nothing. Geometry that keeps data of
     * its own per mesh ({@link ClassicLodGeometry}, {@link RayTracing}'s bottom levels) does not follow removals yet:
     * remove only meshes those were not given.
     */
    public void remove(int mesh) {
        if (!contains(mesh)) {
            throw new IllegalArgumentException("No mesh " + mesh);
        }
        live[mesh] = false;
        unfit -= fits[mesh] ? 0 : 1;
        vertexCount -= vertexLength[mesh];
        meshletCount -= meshletLength[mesh];
        meshCount--;
        retiring[retiringCount] = mesh;
        retireAt[retiringCount++] = slots.retireAt();
    }

    /** Whether {@code mesh} is a mesh of the pool, added and not removed. */
    public boolean contains(int mesh) {
        return mesh >= 0 && mesh < idCount && live[mesh];
    }

    /** Gives the ranges and numbers of removed meshes no frame in flight can draw back, oldest first. */
    private void releaseRetired() {
        int kept = 0;
        for (int i = 0; i < retiringCount; i++) {
            int id = retiring[i];
            if (slots.isFinished(retireAt[i])) {
                vertexRanges.release(vertexStart[id], vertexLength[id]);
                attributeRanges.release(attributeStart[id], attributeLength[id]);
                meshletVertexRanges.release(meshletVertexStart[id], meshletVertexLength[id]);
                meshletRanges.release(meshletStart[id], meshletLength[id]);
                triangleRanges.release(triangleStart[id], triangleLength[id]);
                freeIds[freeIdCount++] = id;
            } else {
                retiring[kept] = id;
                retireAt[kept++] = retireAt[i];
            }
        }
        retiringCount = kept;
    }

    /** The lowest free mesh number, taken: deterministic, whatever order meshes were removed in. */
    private int takeLowestFreeId() {
        int lowest = 0;
        for (int i = 1; i < freeIdCount; i++) {
            if (freeIds[i] < freeIds[lowest]) {
                lowest = i;
            }
        }
        int id = freeIds[lowest];
        freeIds[lowest] = freeIds[--freeIdCount];
        return id;
    }

    /**
     * The most meshlets the pool holds: the visibility buffer's ids give a meshlet 24 bits beside its triangle's 7 (the
     * top bit tells classic triangles).
     */
    public static final long MAX_MESHLETS = 1L << 24;
    /** The most attribute bytes: the mesh table keeps their offsets in 32 bits. */
    public static final long MAX_ATTRIBUTE_BYTES = 1L << 32;

    /** A range of {@code length} from {@code ranges}, grown (and its buffers with it) if it has none free. */
    private long place(RangeAllocator ranges, long length, long alignment) {
        long start = ranges.allocate(length, alignment);
        if (start < 0) {
            long limit = limit(ranges);
            if (ranges.capacity() + length + alignment > limit) {
                throw new IllegalStateException("The geometry pool holds at most " + limit + " of these units ("
                    + "meshlets, vertices, bytes of attributes or triangles): no room for " + length + " more");
            }
            // The free end merges with the new room, so this much more always holds it.
            long grown = Math.max(2 * ranges.capacity(), ranges.capacity() + length + alignment);
            grow(ranges, Math.min(limit, (grown + 3) & ~3L)); // whole words; the limits are
            start = ranges.allocate(length, alignment);
        }
        return start;
    }

    /** How far {@code ranges} may grow: what the shaders' ids and offsets can name. */
    private long limit(RangeAllocator ranges) {
        if (ranges == meshletRanges) {
            return MAX_MESHLETS;
        }
        if (ranges == attributeRanges || ranges == triangleRanges) {
            return MAX_ATTRIBUTE_BYTES; // byte offsets in 32 bits
        }
        return Integer.MAX_VALUE & ~3L; // vertices and meshlet vertices: 32-bit indices
    }



    /** Grows {@code ranges} to {@code units} and the buffers it places data in to match. */
    private void grow(RangeAllocator ranges, long units) {
        if (ranges == vertexRanges) {
            if (units > Integer.MAX_VALUE) {
                throw new IllegalStateException("The geometry pool holds at most " + Integer.MAX_VALUE + " vertices");
            }
            positions = grown(positions, (long) MeshData.POSITION_BYTES * units, positionUsage);
        } else if (ranges == attributeRanges) {
            attributes = grown(attributes, units, dataUsage);
        } else if (ranges == meshletVertexRanges) {
            meshletVertices = grown(meshletVertices, 4L * units, dataUsage);
        } else if (ranges == meshletRanges) {
            meshlets = grown(meshlets, (long) MeshData.MESHLET_BYTES * units, dataUsage);
            clusterLod = grown(clusterLod, (long) MeshData.CLUSTER_LOD_BYTES * units, dataUsage);
        } else {
            meshletTriangles = grown(meshletTriangles, units, dataUsage);
        }
        ranges.grow(units);
        capacity = new Capacity((int) vertexRanges.capacity(), (int) Math.min(Integer.MAX_VALUE,
            meshletRanges.capacity()), (int) Math.min(Integer.MAX_VALUE, triangleRanges.capacity() / 3),
            capacity.meshes());
        GpuBuffer[] buffers = {positions, attributes, meshlets, meshletVertices, meshletTriangles, clusterLod};
        for (int i = 0; i < buffers.length; i++) {
            graphBuffers[i].set(buffers[i]);
        }
    }

    /**
     * A buffer of {@code bytes} taking {@code old}'s place: what {@code old} holds is copied in, in order with the
     * uploads (those queued before land in {@code old} first, those after in the new one), and {@code old} is freed once
     * no frame reads it.
     */
    private GpuBuffer grown(GpuBuffer old, long bytes, int usage) {
        GpuBuffer grown = GpuBuffer.deviceLocal(device, bytes, usage);
        synchronized (replaced) {
            replaced.add(old);
        }
        uploads.recordCommands(commands -> {
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
            copy(commands, old, 0, grown, 0, old.size());
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT);
            if (positionsOnGpu == old) {
                positionsOnGpu = grown;
            }
            synchronized (replaced) {
                if (replaced.remove(old)) { // else the pool was closed
                    oldBuffers.add(old); // this frame reads it too
                }
            }
        });
        return grown;
    }

    /** Twice the mesh numbers: the tables copied into bigger ones, the old ones freed once no frame reads them. */
    private void growMeshes() {
        int meshes = capacity.meshes();
        int more = (int) Math.min(Integer.MAX_VALUE - 8, 2L * meshes);
        meshTable = grownTable(meshTable, (long) MESH_ENTRY_BYTES * more);
        levels = grownTable(levels, 8L * MAX_LEVELS * more);
        freeIds = Arrays.copyOf(freeIds, more);
        vertexStart = Arrays.copyOf(vertexStart, more);
        vertexLength = Arrays.copyOf(vertexLength, more);
        attributeStart = Arrays.copyOf(attributeStart, more);
        attributeLength = Arrays.copyOf(attributeLength, more);
        meshletVertexStart = Arrays.copyOf(meshletVertexStart, more);
        meshletVertexLength = Arrays.copyOf(meshletVertexLength, more);
        meshletStart = Arrays.copyOf(meshletStart, more);
        meshletLength = Arrays.copyOf(meshletLength, more);
        triangleStart = Arrays.copyOf(triangleStart, more);
        triangleLength = Arrays.copyOf(triangleLength, more);
        live = Arrays.copyOf(live, more);
        fits = Arrays.copyOf(fits, more);
        retiring = Arrays.copyOf(retiring, more);
        retireAt = Arrays.copyOf(retireAt, more);
        capacity = new Capacity(capacity.vertices(), capacity.meshlets(), capacity.triangles(), more);
    }

    private GpuBuffer grownTable(GpuBuffer old, long bytes) {
        GpuBuffer grown = GpuBuffer.hostVisible(device, bytes, tableUsage);
        MemorySegment.copy(old.memory(), 0, grown.memory(), 0, old.size());
        grown.memory().asSlice(old.size()).fill((byte) 0);
        oldBuffers.add(old); // frames in flight read it; those recorded from now on read the new one
        return grown;
    }

    private IllegalStateException noRoom(MeshData mesh) {
        return new IllegalStateException("A mesh may have at most " + MAX_LEVELS + " detail levels here: "
            + mesh.levels().size());
    }

    /**
     * The meshlet triangles, a byte per local index, at {@code indexBase} (its triangles' first index in the pool,
     * counted in indices, a multiple of 4): copied as whole words, the last one padded with zeros.
     */
    private void writeTriangles(MeshData mesh, long indexBase) {
        MemorySegment source = mesh.meshletTriangles();
        uploads.write(meshletTriangles, indexBase, source, (source.byteSize() + 3) & ~3L); // whole words
    }

    /**
     * The mesh's level table and the sphere around all its detail groups, for the instance pass: an instance whose
     * nearest point sees level L's error within the allowed pixels needs no meshlet past level L's end, as no finer
     * meshlet's parent error can exceed it there. Roots and other meshlets past a level whose parent error does exceed
     * it extend that level's end, so the range stays right for any mesh.
     */
    private void writeLevels(MeshData mesh, MemorySegment entry, int id) {
        double[] lod = new double[10];
        double cx = mesh.bounds().x();
        double cy = mesh.bounds().y();
        double cz = mesh.bounds().z();
        double radius = mesh.bounds().radius();
        double[] parentErrors = new double[mesh.meshletCount()];
        for (int m = 0; m < mesh.meshletCount(); m++) {
            mesh.meshletLod(m, lod);
            radius = Math.max(radius, Math.sqrt(square(lod[0] - cx) + square(lod[1] - cy) + square(lod[2] - cz))
                + lod[3]);
            if (lod[9] != Double.POSITIVE_INFINITY) {
                radius = Math.max(radius, Math.sqrt(square(lod[5] - cx) + square(lod[6] - cy) + square(lod[7] - cz))
                    + lod[8]);
            }
            parentErrors[m] = lod[9];
        }
        entry.set(F32, 80, (float) cx);
        entry.set(F32, 84, (float) cy);
        entry.set(F32, 88, (float) cz);
        entry.set(F32, 92, (float) radius);
        MemorySegment table = levels.memory();
        long base = 8L * MAX_LEVELS * id;
        for (int l = 0; l < mesh.levels().size(); l++) {
            var level = mesh.levels().get(l);
            int end = level.firstMeshlet() + level.meshletCount();
            for (int m = end; m < mesh.meshletCount(); m++) {
                if (parentErrors[m] > level.error()) {
                    end = m + 1;
                }
            }
            table.set(U32, base + 8L * l, end);
            table.set(F32, base + 8L * l + 4, (float) level.error());
        }
    }

    private static double square(double value) {
        return value * value;
    }

    /** How many meshes the pool holds now. */
    public int meshCount() {
        return meshCount;
    }

    /** One past the highest mesh number handed out: numbers of meshes held are below it. */
    public int meshSlots() {
        return idCount;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int meshletCount() {
        return meshletCount;
    }

    public Capacity capacity() {
        return capacity;
    }

    /**
     * Whether every meshlet fits the mesh shader path ({@link #MESH_SHADER_VERTICES}, {@link #MESH_SHADER_TRIANGLES});
     * otherwise the renderer draws with indexed draws.
     */
    public boolean fitsMeshShaders() {
        return unfit == 0;
    }

    /**
     * Each meshlet's vertex indices, 32 bits each (mesh-relative, the mesh's {@link #vertexBase} added). For custom
     * passes, by device address.
     */
    public GpuBuffer meshletVertices() {
        return meshletVertices;
    }

    /**
     * Each meshlet's triangles, three 8-bit meshlet-local indices each, from {@link #indexBase}. For custom passes, by
     * device address.
     */
    public GpuBuffer meshletTriangles() {
        return meshletTriangles;
    }

    /** Mesh {@code mesh}'s first vertex in {@link #positions()}. */
    public int vertexBase(int mesh) {
        return entryWord(mesh, 12);
    }

    /** Mesh {@code mesh}'s first meshlet in {@link #meshlets()}. */
    public int meshletBase(int mesh) {
        return entryWord(mesh, 28);
    }

    /** How many meshlets mesh {@code mesh} has. */
    public int meshletCount(int mesh) {
        return entryWord(mesh, 48);
    }

    /** Mesh {@code mesh}'s first index (byte) in {@link #meshletTriangles()}: its triangle t's indices are 3t on. */
    public int indexBase(int mesh) {
        return entryWord(mesh, 56);
    }

    private int entryWord(int mesh, long offset) {
        if (!contains(mesh)) {
            throw new IllegalArgumentException("No mesh " + mesh);
        }
        return meshTable.memory().get(U32, (long) MESH_ENTRY_BYTES * mesh + offset);
    }

    /** From now on a frame graph's scene upload pass ({@link SceneData}) records the pool's uploads, not frame starts. */
    void uploadInPasses() {
        uploads.uploadInPasses();
    }

    /** Records the waiting uploads for that pass, which declares the pool's buffers as transfer destinations. */
    void uploadInPass(VkCommandBuffer commands) {
        uploads.uploadInPass(commands);
    }

    /** The frames the pool's meshes are drawn in: what changes to them wait for. */
    FrameSlots frameSlots() {
        return slots;
    }

    /**
     * The positions buffer as the GPU has it at a frame's start, before the frame's uploads: the one replaced by a
     * bigger one until the frame copying it over. For builds recorded at frame starts (ray tracing's).
     */
    GpuBuffer positionsAtFrameStart() {
        return positionsOnGpu;
    }

    /**
     * The buffers as a frame graph imports them: positions, attributes, meshlets, meshlet vertices, meshlet triangles
     * and cluster LOD records. Set anew when a buffer grows, so the graph rebuilds for its size.
     */
    ExternalBuffer[] graphBuffers() {
        return graphBuffers;
    }

    /** The pool's uploads, for work outside frames that reads meshes added since the last frame. */
    FrameUploads uploads() {
        return uploads;
    }

    /**
     * Every mesh's quantized positions, {@code MeshData.POSITION_BYTES} each, from {@link #vertexBase}. For custom
     * passes, by device address.
     */
    public GpuBuffer positions() {
        return positions;
    }

    /**
     * Every mesh's vertex attributes, in its format's layout (the mesh table's format and stride). For custom passes,
     * by device address.
     */
    public GpuBuffer attributes() {
        return attributes;
    }

    /**
     * Every mesh's meshlet records, {@code MeshData.MESHLET_BYTES} each, from {@link #meshletBase}. For custom passes,
     * by device address.
     */
    public GpuBuffer meshlets() {
        return meshlets;
    }

    /**
     * Every meshlet's cluster LOD record, {@code MeshData.CLUSTER_LOD_BYTES} each, as the meshlets. For custom passes,
     * by device address.
     */
    public GpuBuffer clusterLod() {
        return clusterLod;
    }

    /**
     * The mesh table: an entry of {@link #MESH_ENTRY_BYTES} per mesh number, as {@code meshlets/common.slang}'s {@code
     * MeshEntry}. For custom passes, by device address.
     */
    public GpuBuffer meshTable() {
        return meshTable;
    }

    /**
     * Every mesh's level table, {@link #MAX_LEVELS} entries of 8 bytes per mesh number. For custom passes, by device
     * address.
     */
    public GpuBuffer levels() {
        return levels;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            uploads.close();
            for (GpuBuffer buffer : new GpuBuffer[] {positions, attributes, meshletVertices, meshletTriangles,
                meshlets, clusterLod, meshTable, levels}) {
                buffer.close();
            }
            synchronized (replaced) {
                replaced.forEach(GpuBuffer::close);
                replaced.clear();
            }
            oldBuffers.close();
        }
    }
}
