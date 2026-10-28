package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
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
    static final int MESH_ENTRY_BYTES = 80;

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
    private final GpuBuffer indices;
    private final GpuBuffer meshTable;
    private int vertexCount;
    private long attributeBytes;
    private int meshletVertexCount;
    private int meshletCount;
    private int indexCount;
    private int meshCount;
    private long triangleCount;
    private boolean closed;

    public GeometryPool(VulkanDevice device, Capacity capacity) {
        this.capacity = capacity;
        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        int transferDst = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        long triangleIndices = 3L * capacity.triangles();
        positions = GpuBuffer.deviceLocal(device, (long) MeshData.POSITION_BYTES * capacity.vertices(),
            usage | transferDst);
        attributes = GpuBuffer.deviceLocal(device, 28L * capacity.vertices(), usage | transferDst);
        meshletVertices = GpuBuffer.deviceLocal(device, 4L * capacity.meshlets() * 255, usage | transferDst);
        meshletTriangles = GpuBuffer.deviceLocal(device, (triangleIndices + 3) & ~3L, usage | transferDst);
        meshlets = GpuBuffer.deviceLocal(device, (long) MeshData.MESHLET_BYTES * capacity.meshlets(),
            usage | transferDst);
        clusterLod = GpuBuffer.deviceLocal(device, (long) MeshData.CLUSTER_LOD_BYTES * capacity.meshlets(),
            usage | transferDst);
        indices = GpuBuffer.deviceLocal(device, 4L * triangleIndices,
            usage | transferDst | VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        meshTable = GpuBuffer.hostVisible(device, (long) MESH_ENTRY_BYTES * capacity.meshes(), usage);
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
            || attributeBytes + mesh.attributes().byteSize() > attributes.size()) {
            throw new IllegalStateException("The geometry pool has no room for a mesh of " + mesh.vertexCount()
                + " vertices and " + mesh.meshletCount() + " meshlets");
        }
        // The meshlet records count their vertices and triangles from the mesh's start; shaders add these bases.
        positions.write((long) MeshData.POSITION_BYTES * vertexCount, mesh.positions());
        attributes.write(attributeBytes, mesh.attributes());
        meshletVertices.write(4L * meshletVertexCount, mesh.meshletVertices());
        writeTriangles(mesh);
        meshlets.write((long) MeshData.MESHLET_BYTES * meshletCount, mesh.meshlets());
        clusterLod.write((long) MeshData.CLUSTER_LOD_BYTES * meshletCount, mesh.clusterLod());
        indices.write(4L * indexCount, mesh.expandIndices());
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
        vertexCount += mesh.vertexCount();
        attributeBytes += mesh.attributes().byteSize();
        meshletVertexCount += meshletVertexTotal;
        meshletCount += mesh.meshletCount();
        indexCount += indexTotal;
        triangleCount += mesh.triangleCount();
        return meshCount++;
    }

    /**
     * The meshlet triangles, at the same position as the mesh's indices: a byte per index, so the index base works for
     * both. Copied word-aligned: the pool's byte offset may not be a multiple of 4.
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

    GpuBuffer indices() {
        return indices;
    }

    GpuBuffer meshTable() {
        return meshTable;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            for (GpuBuffer buffer : new GpuBuffer[] {positions, attributes, meshletVertices, meshletTriangles,
                meshlets, clusterLod, indices, meshTable}) {
                buffer.close();
            }
        }
    }
}
