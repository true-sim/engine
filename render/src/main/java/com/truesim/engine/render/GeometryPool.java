package com.truesim.engine.render;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.FrameUploads;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;

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
     * How much the pool holds: vertices, meshlets (and as many triangles as 124 per meshlet allow) and meshes.
     * Attributes take up to 28 bytes per vertex.
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

    private final Capacity capacity;
    private final GpuBuffer positions;
    private final GpuBuffer attributes;
    private final GpuBuffer meshletVertices;
    private final GpuBuffer meshletTriangles;
    private final GpuBuffer meshlets;
    private final GpuBuffer clusterLod;
    private final GpuBuffer meshTable;
    private final GpuBuffer levels;
    private final FrameSlots slots;
    private final FrameUploads uploads;
    // Where meshes live: ranges of each kind of data, and mesh numbers.
    private final RangeAllocator vertexRanges;
    private final RangeAllocator attributeRanges;
    private final RangeAllocator meshletVertexRanges;
    private final RangeAllocator meshletRanges;
    private final RangeAllocator triangleRanges;
    private final int[] freeIds;
    private int freeIdCount;
    private int idCount; // ids handed out so far, from 0; freed ones are reused first
    // Per mesh number: its ranges (starts and lengths), and whether it is live.
    private final int[] vertexStart;
    private final int[] vertexLength;
    private final long[] attributeStart;
    private final long[] attributeLength;
    private final int[] meshletVertexStart;
    private final int[] meshletVertexLength;
    private final int[] meshletStart;
    private final int[] meshletLength;
    private final long[] triangleStart;
    private final long[] triangleLength;
    private final boolean[] live;
    private final boolean[] fits;
    // Removed meshes waiting for the frames that may draw them: their numbers and when they are free.
    private final int[] retiring;
    private final long[] retireAt;
    private int retiringCount;
    private int vertexCount;
    private int meshletCount;
    private int meshCount;
    private int unfit; // live meshes too large for the mesh shader path
    private boolean closed;

    /**
     * A pool of {@code capacity}, for frames the caller waits for between {@link #remove removing} a mesh and adding
     * another.
     */
    public GeometryPool(VulkanDevice device, Capacity capacity) {
        this(device, capacity, FrameSlots.single());
    }

    /**
     * A pool of {@code capacity} whose removed meshes keep their data until no frame of {@code slots} (a frame graph's)
     * can still draw them.
     */
    public GeometryPool(VulkanDevice device, Capacity capacity, FrameSlots slots) {
        this.capacity = capacity;
        this.slots = Objects.requireNonNull(slots, "slots");
        this.uploads = new FrameUploads(device, slots);
        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        int transferDst = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        long triangleIndices = 3L * capacity.triangles();
        // Positions are ray tracing's build input too, where the device traces rays: bottom-level acceleration
        // structures read them as they are.
        int buildInput = device.rayTracing() ? VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
            : 0;
        positions = GpuBuffer.deviceLocal(device, (long) MeshData.POSITION_BYTES * capacity.vertices(),
            usage | transferDst | buildInput);
        attributes = GpuBuffer.deviceLocal(device, 28L * capacity.vertices(), usage | transferDst);
        meshletVertices = GpuBuffer.deviceLocal(device, 4L * capacity.meshlets() * 255, usage | transferDst);
        meshletTriangles = GpuBuffer.deviceLocal(device, (triangleIndices + 3) & ~3L, usage | transferDst);
        meshlets = GpuBuffer.deviceLocal(device, (long) MeshData.MESHLET_BYTES * capacity.meshlets(),
            usage | transferDst);
        clusterLod = GpuBuffer.deviceLocal(device, (long) MeshData.CLUSTER_LOD_BYTES * capacity.meshlets(),
            usage | transferDst);
        meshTable = GpuBuffer.hostVisible(device, (long) MESH_ENTRY_BYTES * capacity.meshes(), usage);
        levels = GpuBuffer.hostVisible(device, 8L * MAX_LEVELS * capacity.meshes(), usage);
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
     * Drawn from the next frame on (or, without frame slots, waits until the upload has finished).
     *
     * @throws IllegalStateException if the pool has no room left for it
     */
    public int add(MeshData mesh) {
        int id = tryAdd(mesh);
        if (id < 0) {
            throw noRoom(mesh);
        }
        return id;
    }

    /**
     * Adds {@code mesh} as {@link #add} does, or returns -1 if the pool has no room for it now: room may come back as
     * removed meshes leave the frames in flight. Allocates nothing when there is no room.
     */
    public int tryAdd(MeshData mesh) {
        if (closed) {
            throw new IllegalStateException("The pool is closed");
        }
        releaseRetired();
        int meshletVertexTotal = (int) (mesh.meshletVertices().byteSize() / 4);
        long indexTotal = 3L * mesh.triangleCount();
        long indexRange = (indexTotal + 3) & ~3L; // whole words: the last one is padded
        if (mesh.levels().size() > MAX_LEVELS || freeIdCount == 0 && idCount == capacity.meshes()) {
            return -1;
        }
        // Each kind of data in a range of its own; triangle bytes start on a word, so no word holds two meshes.
        long vertexBase = vertexRanges.allocate(mesh.vertexCount(), 1);
        long attributeBase = attributeRanges.allocate(mesh.attributes().byteSize(), 4);
        long meshletVertexBase = meshletVertexRanges.allocate(meshletVertexTotal, 1);
        long meshletBase = meshletRanges.allocate(mesh.meshletCount(), 1);
        long indexBase = triangleRanges.allocate(indexRange, 4);
        if (vertexBase < 0 || attributeBase < 0 || meshletVertexBase < 0 || meshletBase < 0 || indexBase < 0) {
            give(vertexRanges, vertexBase, mesh.vertexCount());
            give(attributeRanges, attributeBase, mesh.attributes().byteSize());
            give(meshletVertexRanges, meshletVertexBase, meshletVertexTotal);
            give(meshletRanges, meshletBase, mesh.meshletCount());
            give(triangleRanges, indexBase, indexRange);
            return -1;
        }
        boolean fitsMesh = true;
        for (int m = 0; m < mesh.meshletCount() && fitsMesh; m++) {
            fitsMesh = mesh.meshletVertexCount(m) <= MESH_SHADER_VERTICES
                && mesh.meshletTriangleCount(m) <= MESH_SHADER_TRIANGLES;
        }
        int id = freeIdCount > 0 ? takeLowestFreeId() : idCount++;
        // The meshlet records count their vertices and triangles from the mesh's start; shaders add these bases.
        uploads.write(positions, (long) MeshData.POSITION_BYTES * vertexBase, mesh.positions());
        uploads.write(attributes, attributeBase, mesh.attributes());
        uploads.write(meshletVertices, 4L * meshletVertexBase, mesh.meshletVertices());
        writeTriangles(mesh, indexBase);
        uploads.write(meshlets, (long) MeshData.MESHLET_BYTES * meshletBase, mesh.meshlets());
        uploads.write(clusterLod, (long) MeshData.CLUSTER_LOD_BYTES * meshletBase, mesh.clusterLod());
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

    private static void give(RangeAllocator ranges, long start, long length) {
        if (start >= 0) {
            ranges.release(start, length);
        }
    }

    private IllegalStateException noRoom(MeshData mesh) {
        return new IllegalStateException("The geometry pool has no room for a mesh of " + mesh.vertexCount()
            + " vertices, " + mesh.meshletCount() + " meshlets and " + mesh.levels().size() + " levels");
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
        }
    }
}
