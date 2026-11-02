package com.truesim.engine.render.particles;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.MeshLevel;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The alias tables mesh shapes draw triangles from (effects.md §2.7, Vose's alias method): per mesh of the geometry
 * pool, over its finest level's triangles, one by area (uniform on the surface) and one by the volume of each
 * triangle's tetrahedron to the mesh's box center (uniform in the volume, for meshes star-shaped about it). Built once
 * per mesh, from its data, the first time an emitter emits from it; a skinned copy uses its source's (areas change
 * little as it moves). An entry is three words: the triangle (meshlet of the mesh << 8 | triangle of the meshlet), the
 * chance it keeps its draw (a float) and the entry it gives it to otherwise. Freed when the mesh leaves the pool.
 */
final class MeshTables implements AutoCloseable {

    private static final int USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /** A mesh's tables. */
    record Table(GpuBuffer buffer, int triangles, int vertices) {
    }

    private final VulkanDevice device;
    private final Retiring retired;
    private Table[] tables = new Table[16]; // by pool mesh
    private int size;
    private GeometryPool pool;
    private final java.util.function.IntConsumer removed = this::forget;

    @AllowAllocation("made once")
    MeshTables(VulkanDevice device, FrameSlots slots) {
        this.device = device;
        this.retired = new Retiring(slots);
    }

    /** Builds tables of {@code pool}'s meshes from now on, and drops them as meshes leave it. */
    @AllowAllocation("set up rarely")
    void follow(GeometryPool pool) {
        if (this.pool == pool) {
            return;
        }
        if (this.pool != null) {
            this.pool.stopFollowing(removed);
            for (int mesh = 0; mesh < tables.length; mesh++) {
                forget(mesh);
            }
        }
        this.pool = pool;
        if (pool != null) {
            pool.onRemove(removed);
        }
    }

    /** The tables of pool mesh {@code mesh} (a source, not a copy), whose data is {@code data}: built now if not yet. */
    Table of(int mesh, MeshData data) {
        Table table = mesh < tables.length ? tables[mesh] : null;
        return table != null ? table : build(mesh, data);
    }

    /** How many meshes have tables. */
    int size() {
        return size;
    }

    private void forget(int mesh) {
        Table table = mesh < tables.length ? tables[mesh] : null;
        if (table != null) {
            tables[mesh] = null;
            size--;
            retired.add(table.buffer()); // frames in flight may still draw from it
        }
    }

    /** Frees the tables of meshes gone that no frame reads any more. */
    void release() {
        retired.release();
    }

    @AllowAllocation("a mesh's tables, built once when an emitter first emits from it")
    private Table build(int mesh, MeshData data) {
        MeshLevel finest = data.levels().getLast();
        int count = 0;
        for (int m = finest.firstMeshlet(); m < finest.firstMeshlet() + finest.meshletCount(); m++) {
            count += data.meshletTriangleCount(m);
        }
        int[] packed = new int[count];
        double[] areas = new double[count];
        double[] volumes = new double[count];
        double[] a = new double[3];
        double[] b = new double[3];
        double[] c = new double[3];
        double[] center = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            center[axis] = data.boxMin(axis) + 0.5 * data.boxExtent(axis);
        }
        MemorySegment triangles = data.meshletTriangles();
        MemorySegment vertices = data.meshletVertices();
        int i = 0;
        for (int m = finest.firstMeshlet(); m < finest.firstMeshlet() + finest.meshletCount(); m++) {
            int first = data.meshletVertexOffset(m);
            int corner = data.meshletTriangleOffset(m);
            for (int t = 0; t < data.meshletTriangleCount(m); t++) {
                data.position(vertex(vertices, triangles, first, corner + 3 * t), a);
                data.position(vertex(vertices, triangles, first, corner + 3 * t + 1), b);
                data.position(vertex(vertices, triangles, first, corner + 3 * t + 2), c);
                double ux = b[0] - a[0];
                double uy = b[1] - a[1];
                double uz = b[2] - a[2];
                double vx = c[0] - a[0];
                double vy = c[1] - a[1];
                double vz = c[2] - a[2];
                double nx = uy * vz - uz * vy;
                double ny = uz * vx - ux * vz;
                double nz = ux * vy - uy * vx;
                areas[i] = 0.5 * Math.sqrt(nx * nx + ny * ny + nz * nz);
                // The tetrahedron to the center: |(a - center) . n| / 6
                volumes[i] = Math.abs((a[0] - center[0]) * nx + (a[1] - center[1]) * ny + (a[2] - center[2]) * nz) / 6;
                packed[i++] = m << 8 | t;
            }
        }
        GpuBuffer buffer = GpuBuffer.hostVisible(device, 24L * Math.max(count, 1), USAGE, Particles.queues(device));
        MemorySegment memory = buffer.memory();
        write(memory, 0, packed, areas);
        write(memory, 12L * count, packed, volumes);
        Table table = new Table(buffer, count, data.vertexCount());
        if (mesh >= tables.length) {
            tables = Arrays.copyOf(tables, Math.max(mesh + 1, 2 * tables.length));
        }
        tables[mesh] = table;
        size++;
        return table;
    }

    /** The mesh vertex of byte {@code corner} of the triangles, of a meshlet whose vertices start at {@code first}. */
    private static int vertex(MemorySegment vertices, MemorySegment triangles, int first, int corner) {
        int local = Byte.toUnsignedInt(triangles.get(ValueLayout.JAVA_BYTE, corner));
        return vertices.get(U32, 4L * (first + local));
    }

    /**
     * Writes the alias table of {@code weights} from byte {@code at}: Vose's method, small and large entries taken in
     * order from two stacks, so the same weights give the same table. Without any weight every entry is as likely.
     */
    static void write(MemorySegment memory, long at, int[] packed, double[] weights) {
        int n = weights.length;
        double sum = 0;
        for (double w : weights) {
            sum += w;
        }
        double[] scaled = new double[n];
        int[] small = new int[n];
        int[] large = new int[n];
        int smalls = 0;
        int larges = 0;
        for (int i = 0; i < n; i++) {
            scaled[i] = sum > 0 ? weights[i] * n / sum : 1;
            if (scaled[i] < 1) {
                small[smalls++] = i;
            } else {
                large[larges++] = i;
            }
        }
        int[] alias = new int[n];
        double[] keep = new double[n];
        Arrays.fill(keep, 1);
        for (int i = 0; i < n; i++) {
            alias[i] = i;
        }
        while (smalls > 0 && larges > 0) {
            int s = small[--smalls];
            int l = large[--larges];
            keep[s] = scaled[s];
            alias[s] = l;
            scaled[l] += scaled[s] - 1;
            if (scaled[l] < 1) {
                small[smalls++] = l;
            } else {
                large[larges++] = l;
            }
        }
        for (int i = 0; i < n; i++) {
            long e = at + 12L * i;
            memory.set(U32, e, packed[i]);
            memory.set(U32, e + 4, Float.floatToRawIntBits((float) keep[i]));
            memory.set(U32, e + 8, alias[i]);
        }
    }

    @Override
    public void close() {
        follow(null);
        retired.close();
    }
}
