package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.render.layout.DeformPush;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Poses the geometry pool's deformed copies ({@link GeometryPool#addDeformed}) on the GPU, once a frame, in the scene
 * upload's wake ({@link SceneData}): each copy's joints as matrices in its mesh's space, which the mesh's joint palette
 * indexes, and the box that holds the posed mesh. The caller (the scene sync) works them out from the scene each frame:
 *
 * <pre>{@code
 * int copy = pool.addDeformed(mesh);
 * deformation.add(copy, jointCount);
 * // each frame
 * deformation.joint(copy, j, m00, m01, m02, m03, m10, ..., m23);   // per joint: rows of a 3x4 matrix
 * deformation.bounds(copy, minX, minY, minZ, maxX, maxY, maxZ, growth);
 * }</pre>
 *
 * <p>A copy draws as its source until its first bounds; posed, it is skinned every frame (skipping unchanged ones
 * comes later). Its meshlets' spheres are recomputed from the posed vertices, cone culling is off for it, and its
 * detail spheres grow by {@code growth}, the farthest any joint moves a point of its box. Allocates nothing per frame.
 */
public final class Deformation {

    private static final int MATRIX_FLOATS = 12;
    private static final ValueLayout.OfFloat F32 =
        ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final VulkanDevice device;
    private final GeometryPool pool;
    private final FrameSlots slots;
    private final Retiring retired;
    private final Arena arena = Arena.ofShared();
    // The joints' matrices of every copy, as the frames copy them: grown as copies come.
    private MemorySegment matrices;
    private FrameBuffer frameMatrices;
    private final RangeAllocator matrixRanges;
    // By mesh number: the copy's first matrix and count (0: not a copy), box and growth, and whether it is posed.
    private int[] firstMatrix = new int[64];
    private int[] jointCount = new int[64];
    private float[] bounds = new float[7 * 64];
    private boolean[] posed = new boolean[64];
    // The copies, densely.
    private int[] copies = new int[64];
    private int copyCount;

    @AllowAllocation("made with its pool")
    Deformation(VulkanDevice device, GeometryPool pool, FrameSlots slots) {
        this.device = device;
        this.pool = pool;
        this.slots = slots;
        this.retired = new Retiring(slots);
        this.matrixRanges = new RangeAllocator(256);
        this.matrices = arena.allocate(256L * MATRIX_FLOATS * 4, 16);
    }

    /** Takes copy {@code copy} (a pool's deformed mesh) with {@code joints} joints, its palette's. */
    @AllowAllocation("adding a copy")
    public void add(int copy, int joints) {
        if (pool.deformedFrom(copy) < 0) {
            throw new IllegalArgumentException("Not a deformed copy: " + copy);
        }
        if (joints < 1 || joints > 256) {
            throw new IllegalArgumentException("A copy has 1 to 256 joints: " + joints);
        }
        if (copy < jointCount.length && jointCount[copy] != 0) {
            throw new IllegalStateException("Copy " + copy + " was added already");
        }
        if (copy >= jointCount.length) {
            int length = Math.max(copy + 1, 2 * jointCount.length);
            firstMatrix = Arrays.copyOf(firstMatrix, length);
            jointCount = Arrays.copyOf(jointCount, length);
            bounds = Arrays.copyOf(bounds, 7 * length);
            posed = Arrays.copyOf(posed, length);
        }
        long first = matrixRanges.allocate(joints, 1);
        if (first < 0) {
            long capacity = Math.max(2 * matrixRanges.capacity(), matrixRanges.capacity() + joints);
            MemorySegment grown = arena.allocate(capacity * MATRIX_FLOATS * 4, 16);
            MemorySegment.copy(matrices, 0, grown, 0, matrices.byteSize());
            matrices = grown; // the arena frees it with the rest
            matrixRanges.grow(capacity);
            first = matrixRanges.allocate(joints, 1);
        }
        firstMatrix[copy] = (int) first;
        jointCount[copy] = joints;
        posed[copy] = false;
        if (copyCount == copies.length) {
            copies = Arrays.copyOf(copies, 2 * copyCount);
        }
        copies[copyCount++] = copy;
        for (int j = 0; j < joints; j++) { // at rest until posed
            joint(copy, j, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0);
        }
    }

    /** Lets go of copy {@code copy}: the pool removed it. */
    void forget(int copy) {
        if (copy >= jointCount.length || jointCount[copy] == 0) {
            return; // added to the pool, never posed here
        }
        matrixRanges.release(firstMatrix[copy], jointCount[copy]);
        jointCount[copy] = 0;
        posed[copy] = false;
        for (int i = 0; i < copyCount; i++) {
            if (copies[i] == copy) {
                copies[i] = copies[--copyCount];
                break;
            }
        }
    }

    /** How many copies it poses. */
    public int size() {
        return copyCount;
    }

    /**
     * Joint {@code joint}'s matrix for copy {@code copy}: rows of a 3x4 matrix taking the mesh's rest positions to its
     * posed ones (the joint's world transform relative to the instance's, times its inverse bind matrix).
     */
    public void joint(int copy, int joint, double m00, double m01, double m02, double m03, double m10, double m11,
        double m12, double m13, double m20, double m21, double m22, double m23) {
        long at = 4L * MATRIX_FLOATS * (firstMatrix[copy] + java.util.Objects.checkIndex(joint, joints(copy)));
        matrices.set(F32, at, (float) m00);
        matrices.set(F32, at + 4, (float) m01);
        matrices.set(F32, at + 8, (float) m02);
        matrices.set(F32, at + 12, (float) m03);
        matrices.set(F32, at + 16, (float) m10);
        matrices.set(F32, at + 20, (float) m11);
        matrices.set(F32, at + 24, (float) m12);
        matrices.set(F32, at + 28, (float) m13);
        matrices.set(F32, at + 32, (float) m20);
        matrices.set(F32, at + 36, (float) m21);
        matrices.set(F32, at + 40, (float) m22);
        matrices.set(F32, at + 44, (float) m23);
    }

    /**
     * The box holding copy {@code copy} as posed, in its mesh's space (positions are quantized in it), and the farthest
     * any joint moves a point of its box; from now on it is skinned every frame.
     */
    public void bounds(int copy, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
        double growth) {
        joints(copy);
        if (!(maxX >= minX && maxY >= minY && maxZ >= minZ) || !Double.isFinite(minX + minY + minZ + maxX + maxY + maxZ)
            || !(growth >= 0) || growth == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a box and growth: " + minX + ", " + minY + ", " + minZ + " to "
                + maxX + ", " + maxY + ", " + maxZ + ", " + growth);
        }
        int b = 7 * copy;
        bounds[b] = (float) minX;
        bounds[b + 1] = (float) minY;
        bounds[b + 2] = (float) minZ;
        bounds[b + 3] = (float) (maxX - minX);
        bounds[b + 4] = (float) (maxY - minY);
        bounds[b + 5] = (float) (maxZ - minZ);
        bounds[b + 6] = (float) growth;
        posed[copy] = true;
    }

    private int joints(int copy) {
        if (copy < 0 || copy >= jointCount.length || jointCount[copy] == 0) {
            throw new IllegalArgumentException("Not a copy of these: " + copy);
        }
        return jointCount[copy];
    }

    /**
     * Records the frame's skinning: the matrices into the frame's copy, a dispatch per posed copy, then each one's
     * meshlet spheres; barriers around it, as the mesh table it writes is not one of the graph's buffers.
     */
    void record(PassContext ctx, SceneData.Buffers buffers, Pipeline skin, Pipeline spheres, DeformPush push) {
        retired.release();
        if (copyCount == 0) {
            return;
        }
        long bytes = 4L * MATRIX_FLOATS * matrixRanges.capacity();
        if (frameMatrices == null || frameMatrices.size() < bytes) {
            if (frameMatrices != null) {
                retired.add(frameMatrices);
            }
            frameMatrices = FrameBuffer.create(device, bytes,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots);
        }
        frameMatrices.put(0, matrices, 0, bytes);
        long matrixBase = frameMatrices.deviceAddress();
        var commands = ctx.unsafe().commands();
        // Frames before read the mesh table, positions and records this overwrites
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        push.meshes(pool.meshTable().deviceAddress()).positions(ctx.address(buffers.positions()))
            .attributes(ctx.address(buffers.attributes())).meshlets(ctx.address(buffers.meshlets()))
            .meshletVertices(ctx.address(buffers.meshletVertices())).clusterLod(ctx.address(buffers.clusterLod()));
        for (int i = 0; i < copyCount; i++) {
            int copy = copies[i];
            if (!posed[copy]) {
                continue;
            }
            int vertices = pool.vertexCountOf(copy);
            set(push, copy, matrixBase).vertices(vertices);
            ctx.dispatch(skin, push, (vertices + 63) / 64);
        }
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        for (int i = 0; i < copyCount; i++) {
            int copy = copies[i];
            if (!posed[copy]) {
                continue;
            }
            int meshlets = pool.meshletCount(copy);
            set(push, copy, matrixBase).vertices(meshlets);
            ctx.dispatch(spheres, push, (meshlets + 63) / 64);
        }
        // Everything after reads them: culling, drawing, ray tracing
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
    }

    private DeformPush set(DeformPush push, int copy, long matrixBase) {
        int b = 7 * copy;
        return push.matrices(matrixBase + 4L * MATRIX_FLOATS * firstMatrix[copy]).source(pool.deformedFrom(copy))
            .target(copy).growth(bounds[b + 6]).boxMin(bounds[b], bounds[b + 1], bounds[b + 2])
            .boxExtent(bounds[b + 3], bounds[b + 4], bounds[b + 5]);
    }

    /** Frees its buffers: the pool's closing. */
    void close() {
        if (frameMatrices != null) {
            frameMatrices.close();
        }
        retired.close();
        arena.close();
    }
}
