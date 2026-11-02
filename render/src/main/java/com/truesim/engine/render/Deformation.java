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
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;
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
 * <p>A copy of a mesh with morph targets ({@link #add(int, int, MeshData)}) has their weights too ({@link #weight}):
 * the targets' deltas, uploaded once per mesh, are added to the rest vertices before skinning; a copy may have no
 * joints then. The box and growth count the morphs' moves as well.
 *
 * <p>A copy draws as its source until its first bounds; posed, it is skinned in the frames whose pose (joints, weights
 * or bounds) differs from the one it was last skinned with, and keeps its vertices in the others. Its meshlets' spheres are recomputed from the posed vertices, cone culling is off for it, and its
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
    // By mesh number: the copy's first matrix, joints, morph targets and matrix slots (its weights after its joints'
    // matrices), box and growth, and whether it is added and posed.
    private int[] firstMatrix = new int[64];
    private int[] jointCount = new int[64];
    private int[] targetCount = new int[64];
    private int[] matrixSlots = new int[64];
    private boolean[] added = new boolean[64];
    private float[] bounds = new float[7 * 64];
    private boolean[] posed = new boolean[64];
    private boolean[] changed = new boolean[64]; // posed anew since its last skinning: skinned again
    private int[] skins = new int[64]; // how many times it was skinned: ray tracing refits it when this changes
    // Morph targets by source mesh number, uploaded once while copies of it are added: their bytes' start in morphData
    // (offsets per vertex and one more, then the records) and how many copies use them. Ranges let go wait for the
    // frames that may read them.
    private GpuBuffer morphData;
    private final RangeAllocator morphRanges;
    private long[] morphStart = new long[64];
    private long[] morphLength = new long[64];
    private int[] morphUsers = new int[64];
    private long[] releasingStart = new long[16];
    private long[] releasingLength = new long[16];
    private long[] releasingAt = new long[16];
    private int releasingCount;
    // The copies, densely.
    private int[] copies = new int[64];
    private int copyCount;
    private int skinned; // in the frame recorded last

    @AllowAllocation("made with its pool")
    Deformation(VulkanDevice device, GeometryPool pool, FrameSlots slots) {
        this.device = device;
        this.pool = pool;
        this.slots = slots;
        this.retired = new Retiring(slots);
        this.matrixRanges = new RangeAllocator(256);
        this.matrices = arena.allocate(256L * MATRIX_FLOATS * 4, 16);
        this.morphRanges = new RangeAllocator(1 << 16);
        this.morphData = GpuBuffer.hostVisible(device, 1 << 16,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
    }

    /** Takes copy {@code copy} (a pool's deformed mesh) with {@code joints} joints, its palette's. */
    public void add(int copy, int joints) {
        add(copy, joints, null);
    }

    /**
     * Takes copy {@code copy} with {@code joints} joints (0 to 256; at least 1 unless {@code mesh} has morph targets)
     * and {@code mesh}'s morph targets (the copy's source's data; null or none: no morphs), their weights 0.
     */
    @AllowAllocation("adding a copy")
    public void add(int copy, int joints, MeshData mesh) {
        int source = pool.deformedFrom(copy);
        if (source < 0) {
            throw new IllegalArgumentException("Not a deformed copy: " + copy);
        }
        int targets = mesh == null ? 0 : mesh.morphTargetCount();
        if (joints < (targets > 0 ? 0 : 1) || joints > 256) {
            throw new IllegalArgumentException("A copy has 1 to 256 joints, or 0 with morph targets: " + joints);
        }
        if (targets > 0 && mesh.vertexCount() != pool.vertexCountOf(copy)) {
            throw new IllegalArgumentException("Not the copy's mesh: " + mesh.vertexCount() + " vertices for "
                + pool.vertexCountOf(copy));
        }
        if (copy < added.length && added[copy]) {
            throw new IllegalStateException("Copy " + copy + " was added already");
        }
        if (copy >= added.length) {
            int length = Math.max(copy + 1, 2 * added.length);
            firstMatrix = Arrays.copyOf(firstMatrix, length);
            jointCount = Arrays.copyOf(jointCount, length);
            targetCount = Arrays.copyOf(targetCount, length);
            matrixSlots = Arrays.copyOf(matrixSlots, length);
            added = Arrays.copyOf(added, length);
            bounds = Arrays.copyOf(bounds, 7 * length);
            posed = Arrays.copyOf(posed, length);
            changed = Arrays.copyOf(changed, length);
            skins = Arrays.copyOf(skins, length);
        }
        int slotCount = Math.max(1, joints + (targets + MATRIX_FLOATS - 1) / MATRIX_FLOATS);
        long first = matrixRanges.allocate(slotCount, 1);
        if (first < 0) {
            long capacity = Math.max(2 * matrixRanges.capacity(), matrixRanges.capacity() + slotCount);
            MemorySegment grown = arena.allocate(capacity * MATRIX_FLOATS * 4, 16);
            MemorySegment.copy(matrices, 0, grown, 0, matrices.byteSize());
            matrices = grown; // the arena frees it with the rest
            matrixRanges.grow(capacity);
            first = matrixRanges.allocate(slotCount, 1);
        }
        if (targets > 0) {
            holdMorphs(source, mesh);
        }
        firstMatrix[copy] = (int) first;
        jointCount[copy] = joints;
        targetCount[copy] = targets;
        matrixSlots[copy] = slotCount;
        added[copy] = true;
        posed[copy] = false;
        skins[copy] = 0;
        matrices.asSlice(4L * MATRIX_FLOATS * first, 4L * MATRIX_FLOATS * slotCount).fill((byte) 0); // weights 0
        if (copyCount == copies.length) {
            copies = Arrays.copyOf(copies, 2 * copyCount);
        }
        copies[copyCount++] = copy;
        for (int j = 0; j < joints; j++) { // at rest until posed
            joint(copy, j, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0);
        }
    }

    /** Uploads {@code mesh}'s morph targets for copies of {@code source}, unless they are already. */
    @AllowAllocation("adding a copy of a mesh with morph targets")
    private void holdMorphs(int source, MeshData mesh) {
        if (source >= morphUsers.length) {
            int length = Math.max(source + 1, 2 * morphUsers.length);
            morphStart = Arrays.copyOf(morphStart, length);
            morphLength = Arrays.copyOf(morphLength, length);
            morphUsers = Arrays.copyOf(morphUsers, length);
        }
        if (morphUsers[source]++ > 0) {
            return;
        }
        releaseMorphRanges();
        long offsets = mesh.morphOffsets().byteSize();
        long length = offsets + mesh.morphRecords().byteSize();
        long start = morphRanges.allocate(length, 4);
        if (start < 0) {
            long capacity = Math.max(2 * morphRanges.capacity(), morphRanges.capacity() + length + 4);
            GpuBuffer grown = GpuBuffer.hostVisible(device, capacity,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
            MemorySegment.copy(morphData.memory(), 0, grown.memory(), 0, morphData.size());
            retired.add(morphData); // frames in flight read it
            morphData = grown;
            morphRanges.grow(capacity);
            start = morphRanges.allocate(length, 4);
        }
        MemorySegment.copy(mesh.morphOffsets(), 0, morphData.memory(), start, offsets);
        MemorySegment.copy(mesh.morphRecords(), 0, morphData.memory(), start + offsets, mesh.morphRecords().byteSize());
        morphStart[source] = start;
        morphLength[source] = length;
    }

    /** Lets go of {@code source}'s morph targets once no copy uses them and no frame in flight reads them. */
    @AllowAllocation("growing the list of ranges let go")
    private void releaseMorphs(int source) {
        if (--morphUsers[source] > 0) {
            return;
        }
        if (releasingCount == releasingStart.length) {
            releasingStart = Arrays.copyOf(releasingStart, 2 * releasingCount);
            releasingLength = Arrays.copyOf(releasingLength, 2 * releasingCount);
            releasingAt = Arrays.copyOf(releasingAt, 2 * releasingCount);
        }
        releasingStart[releasingCount] = morphStart[source];
        releasingLength[releasingCount] = morphLength[source];
        releasingAt[releasingCount++] = slots.retireAt();
    }

    private void releaseMorphRanges() {
        int kept = 0;
        for (int i = 0; i < releasingCount; i++) {
            if (slots.isFinished(releasingAt[i])) {
                morphRanges.release(releasingStart[i], releasingLength[i]);
            } else {
                releasingStart[kept] = releasingStart[i];
                releasingLength[kept] = releasingLength[i];
                releasingAt[kept++] = releasingAt[i];
            }
        }
        releasingCount = kept;
    }

    /** Lets go of copy {@code copy}: the pool removed it. */
    void forget(int copy) {
        if (copy >= added.length || !added[copy]) {
            return; // added to the pool, never posed here
        }
        matrixRanges.release(firstMatrix[copy], matrixSlots[copy]);
        if (targetCount[copy] > 0) {
            releaseMorphs(pool.deformedFrom(copy));
        }
        added[copy] = false;
        jointCount[copy] = 0;
        targetCount[copy] = 0;
        posed[copy] = false;
        for (int i = 0; i < copyCount; i++) {
            if (copies[i] == copy) {
                copies[i] = copies[--copyCount];
                break;
            }
        }
    }

    /** How many copies the frame recorded last skinned: those whose pose changed. */
    public int skinnedLastFrame() {
        return skinned;
    }

    /**
     * How many times copy {@code copy} was skinned, in frames recorded so far: 0 while it draws as its source.
     */
    int skins(int copy) {
        return copy < added.length && added[copy] ? skins[copy] : 0;
    }

    /**
     * Whether copy {@code copy} has bounds: skinned in every frame from the one recorded after its first, its positions
     * quantized in its box ({@link #boxMin}); before, they are its source's.
     */
    boolean posed(int copy) {
        return copy < added.length && added[copy] && posed[copy];
    }

    /** Along {@code axis}, the corner of the box copy {@code copy} was last posed in ({@link #bounds}). */
    float boxMin(int copy, int axis) {
        return bounds[7 * copy + axis];
    }

    /** Along {@code axis}, the size of the box copy {@code copy} was last posed in. */
    float boxExtent(int copy, int axis) {
        return bounds[7 * copy + 3 + axis];
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
        added(copy);
        long at = 4L * MATRIX_FLOATS * (firstMatrix[copy] + java.util.Objects.checkIndex(joint, jointCount[copy]));
        put(copy, at, m00);
        put(copy, at + 4, m01);
        put(copy, at + 8, m02);
        put(copy, at + 12, m03);
        put(copy, at + 16, m10);
        put(copy, at + 20, m11);
        put(copy, at + 24, m12);
        put(copy, at + 28, m13);
        put(copy, at + 32, m20);
        put(copy, at + 36, m21);
        put(copy, at + 40, m22);
        put(copy, at + 44, m23);
    }

    /** Morph target {@code target}'s weight for copy {@code copy}. */
    public void weight(int copy, int target, double weight) {
        added(copy);
        long at = 4L * (MATRIX_FLOATS * ((long) firstMatrix[copy] + jointCount[copy])
            + java.util.Objects.checkIndex(target, targetCount[copy]));
        put(copy, at, weight);
    }

    /** Writes {@code value} at {@code at} of the matrices, noting {@code copy} changed if it does. */
    private void put(int copy, long at, double value) {
        float f = (float) value;
        if (Float.floatToRawIntBits(matrices.get(F32, at)) != Float.floatToRawIntBits(f)) {
            matrices.set(F32, at, f);
            changed[copy] = true;
        }
    }

    /**
     * The box holding copy {@code copy} as posed, in its mesh's space (positions are quantized in it), and the farthest
     * any joint moves a point of its box; from now on it is skinned every frame.
     */
    public void bounds(int copy, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
        double growth) {
        added(copy);
        if (!(maxX >= minX && maxY >= minY && maxZ >= minZ) || !Double.isFinite(minX + minY + minZ + maxX + maxY + maxZ)
            || !(growth >= 0) || growth == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a box and growth: " + minX + ", " + minY + ", " + minZ + " to "
                + maxX + ", " + maxY + ", " + maxZ + ", " + growth);
        }
        int b = 7 * copy;
        bound(copy, b, minX);
        bound(copy, b + 1, minY);
        bound(copy, b + 2, minZ);
        bound(copy, b + 3, maxX - minX);
        bound(copy, b + 4, maxY - minY);
        bound(copy, b + 5, maxZ - minZ);
        bound(copy, b + 6, growth);
        changed[copy] |= !posed[copy];
        posed[copy] = true;
    }

    private void bound(int copy, int i, double value) {
        float f = (float) value;
        if (bounds[i] != f) {
            bounds[i] = f;
            changed[copy] = true;
        }
    }

    private void added(int copy) {
        if (copy < 0 || copy >= added.length || !added[copy]) {
            throw new IllegalArgumentException("Not a copy of these: " + copy);
        }
    }

    /**
     * Records the frame's skinning: the matrices into the frame's copy, a dispatch per posed copy, then each one's
     * meshlet spheres; barriers around it, as the mesh table it writes is not one of the graph's buffers.
     */
    void record(PassContext ctx, SceneData.Buffers buffers, Pipeline skin, Pipeline spheres, DeformPush push) {
        retired.release();
        releaseMorphRanges();
        int skinning = 0;
        for (int i = 0; i < copyCount; i++) {
            skinning += posed[copies[i]] && changed[copies[i]] ? 1 : 0;
        }
        skinned = skinning;
        if (skinning == 0) {
            return; // every copy as last skinned: their vertices stay
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
            if (!posed[copy] || !changed[copy]) {
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
            if (!posed[copy] || !changed[copy]) {
                continue;
            }
            int meshlets = pool.meshletCount(copy);
            set(push, copy, matrixBase).vertices(meshlets);
            ctx.dispatch(spheres, push, (meshlets + 63) / 64);
            changed[copy] = false;
            skins[copy]++;
        }
        // Everything after reads them: culling, drawing, ray tracing
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
    }

    private DeformPush set(DeformPush push, int copy, long matrixBase) {
        int b = 7 * copy;
        int source = pool.deformedFrom(copy);
        push.morph(targetCount[copy] > 0 ? morphData.deviceAddress() + morphStart[source] : 0)
            .joints(jointCount[copy]).targets(targetCount[copy]);
        return push.matrices(matrixBase + 4L * MATRIX_FLOATS * firstMatrix[copy]).source(source)
            .target(copy).growth(bounds[b + 6]).boxMin(bounds[b], bounds[b + 1], bounds[b + 2])
            .boxExtent(bounds[b + 3], bounds[b + 4], bounds[b + 5]);
    }

    /** Frees its buffers: the pool's closing. */
    void close() {
        if (frameMatrices != null) {
            frameMatrices.close();
        }
        morphData.close();
        retired.close();
        arena.close();
    }
}
