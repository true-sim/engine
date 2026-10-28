package com.truesim.engine.render;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;
import java.lang.foreign.Arena;
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
 * <p>Meshes are added for good: the pool only grows within its capacity. Use it from one thread.
 */
@AllowAllocation("created rarely; adding meshes uploads them")
public final class GeometryPool implements AutoCloseable {

    /** Bytes per mesh table entry. */
    static final int MESH_ENTRY_BYTES = 96;

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
    private int vertexCount;
    private long attributeBytes;
    private int meshletVertexCount;
    private int meshletCount;
    private int indexCount;
    private int meshCount;
    private long triangleCount;
    private boolean fitsMeshShaders = true;
    private boolean closed;

    public GeometryPool(VulkanDevice device, Capacity capacity) {
        this.capacity = capacity;
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
    }

    /**
     * Uploads {@code mesh} and returns its number, for instances to draw it by. Waits until the upload has finished.
     *
     * @throws IllegalStateException if the pool has no room left for it
     */
    public int add(MeshData mesh) {
        if (closed) {
            throw new IllegalStateException("The pool is closed");
        }
        int meshletVertexTotal = (int) (mesh.meshletVertices().byteSize() / 4);
        int indexTotal = 3 * mesh.triangleCount();
        if (meshCount == capacity.meshes() || vertexCount + mesh.vertexCount() > capacity.vertices()
            || meshletCount + mesh.meshletCount() > capacity.meshlets()
            || triangleCount + mesh.triangleCount() > capacity.triangles()
            || meshletVertexCount + meshletVertexTotal > meshletVertices.size() / 4
            || attributeBytes + mesh.attributes().byteSize() > attributes.size()
            || mesh.levels().size() > MAX_LEVELS) {
            throw new IllegalStateException("The geometry pool has no room for a mesh of " + mesh.vertexCount()
                + " vertices and " + mesh.meshletCount() + " meshlets");
        }
        for (int m = 0; m < mesh.meshletCount() && fitsMeshShaders; m++) {
            fitsMeshShaders = mesh.meshletVertexCount(m) <= MESH_SHADER_VERTICES
                && mesh.meshletTriangleCount(m) <= MESH_SHADER_TRIANGLES;
        }
        // The meshlet records count their vertices and triangles from the mesh's start; shaders add these bases.
        positions.write((long) MeshData.POSITION_BYTES * vertexCount, mesh.positions());
        attributes.write(attributeBytes, mesh.attributes());
        meshletVertices.write(4L * meshletVertexCount, mesh.meshletVertices());
        writeTriangles(mesh);
        meshlets.write((long) MeshData.MESHLET_BYTES * meshletCount, mesh.meshlets());
        clusterLod.write((long) MeshData.CLUSTER_LOD_BYTES * meshletCount, mesh.clusterLod());
        MemorySegment entry = meshTable.memory().asSlice((long) MESH_ENTRY_BYTES * meshCount, MESH_ENTRY_BYTES);
        for (int axis = 0; axis < 3; axis++) {
            entry.set(F32, 4L * axis, (float) mesh.boxMin(axis));
            entry.set(F32, 16 + 4L * axis, (float) mesh.boxExtent(axis));
        }
        entry.set(U32, 12, vertexCount);
        entry.set(U32, 28, meshletCount);
        entry.set(F32, 32, (float) mesh.bounds().x());
        entry.set(F32, 36, (float) mesh.bounds().y());
        entry.set(F32, 40, (float) mesh.bounds().z());
        entry.set(F32, 44, (float) mesh.bounds().radius());
        entry.set(U32, 48, mesh.meshletCount());
        entry.set(U32, 52, meshletVertexCount);
        entry.set(U32, 56, indexCount);
        entry.set(U32, 60, (int) attributeBytes);
        entry.set(U32, 64, mesh.format().mask());
        entry.set(U32, 68, mesh.format().stride());
        entry.set(U32, 72, MAX_LEVELS * meshCount);
        entry.set(U32, 76, mesh.levels().size());
        writeLevels(mesh, entry);
        vertexCount += mesh.vertexCount();
        attributeBytes += mesh.attributes().byteSize();
        meshletVertexCount += meshletVertexTotal;
        meshletCount += mesh.meshletCount();
        indexCount += indexTotal;
        triangleCount += mesh.triangleCount();
        return meshCount++;
    }

    /**
     * The meshlet triangles, a byte per local index, from the mesh's index base (its triangles' first index in the
     * pool, counted in indices). Copied word-aligned: the pool's byte offset may not be a multiple of 4.
     */
    private void writeTriangles(MeshData mesh) {
        MemorySegment source = mesh.meshletTriangles();
        long start = indexCount & ~3L;
        long end = indexCount + source.byteSize();
        MemorySegment words = Arena.ofAuto().allocate(((end + 3) & ~3L) - start, 4);
        if (start < indexCount) { // the bytes of the previous mesh sharing the first word
            MemorySegment.copy(lastWord, 0, words, 0, 4);
        }
        MemorySegment.copy(source, 0, words, indexCount - start, source.byteSize());
        meshletTriangles.write(start, words);
        MemorySegment.copy(words, words.byteSize() - 4, lastWord, 0, 4);
    }

    private final MemorySegment lastWord = Arena.ofAuto().allocate(4, 4);

    /**
     * The mesh's level table and the sphere around all its detail groups, for the instance pass: an instance whose
     * nearest point sees level L's error within the allowed pixels needs no meshlet past level L's end, as no finer
     * meshlet's parent error can exceed it there. Roots and other meshlets past a level whose parent error does exceed
     * it extend that level's end, so the range stays right for any mesh.
     */
    private void writeLevels(MeshData mesh, MemorySegment entry) {
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
        long base = 8L * MAX_LEVELS * meshCount;
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

    /** How many meshes the pool holds. */
    public int meshCount() {
        return meshCount;
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
        return fitsMeshShaders;
    }

    GpuBuffer meshletVertices() {
        return meshletVertices;
    }

    GpuBuffer meshletTriangles() {
        return meshletTriangles;
    }

    /** Mesh {@code mesh}'s first vertex in {@link #positions()}. */
    int vertexBase(int mesh) {
        return entryWord(mesh, 12);
    }

    /** Mesh {@code mesh}'s first meshlet in {@link #meshlets()}. */
    int meshletBase(int mesh) {
        return entryWord(mesh, 28);
    }

    /** How many meshlets mesh {@code mesh} has. */
    int meshletCount(int mesh) {
        return entryWord(mesh, 48);
    }

    /** Mesh {@code mesh}'s first index (byte) in {@link #meshletTriangles()}: its triangle t's indices are 3t on. */
    int indexBase(int mesh) {
        return entryWord(mesh, 56);
    }

    private int entryWord(int mesh, long offset) {
        return meshTable.memory().get(U32, (long) MESH_ENTRY_BYTES * Objects.checkIndex(mesh, meshCount) + offset);
    }

    GpuBuffer positions() {
        return positions;
    }

    GpuBuffer attributes() {
        return attributes;
    }

    GpuBuffer meshlets() {
        return meshlets;
    }

    GpuBuffer clusterLod() {
        return clusterLod;
    }

    GpuBuffer meshTable() {
        return meshTable;
    }

    GpuBuffer levels() {
        return levels;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            for (GpuBuffer buffer : new GpuBuffer[] {positions, attributes, meshletVertices, meshletTriangles,
                meshlets, clusterLod, meshTable, levels}) {
                buffer.close();
            }
        }
    }
}
