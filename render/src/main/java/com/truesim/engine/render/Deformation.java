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
import com.truesim.engine.render.layout.KeepPush;
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
 * <p>A copy moved in fixed steps may be drawn between two steps' poses ({@link #blended(int, boolean)}, decision A5):
 * its previous step's matrices and weights are kept beside the current ones and the skinning blends them by the
 * frame's factor ({@link #blend}), so frames without a step write nothing per joint on the CPU.
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
    // Copies blended between two steps' poses (A5): the previous step's matrices and weights (-1: none kept), whether
    // it blends, and how far from the previous pose to the current one it is drawn.
    private int[] previousMatrix = filled(64);
    private boolean[] blended = new boolean[64];
    private float[] factor = new float[64];
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
    private long uploaded; // bytes of matrices and weights the frame recorded last copied for the GPU
    // The matrices' version (counting frames that found them written) and the one each frame slot's copy holds: a slot
    // holding the current one is not copied again (copies blended between steps skin anew each frame, from the same
    // matrices until the next step).
    private boolean written;
    private long version;
    private final long[] slotVersion;
    // Motion vectors' memory (keepPrevious): each copy's vertices as the frame before drew them, copied aside before it
    // is skinned anew, at its range of previousPositions; per pool mesh number a 32-byte record (motion.slang's
    // DeformedBefore: the box they were quantized in, their range, whether this frame skinned it anew) written into
    // plain memory and handed to the frame's copy at once; the box each copy was last skinned in.
    private volatile boolean keepPrevious;
    private GpuBuffer previousPositions;
    private final RangeAllocator previousRanges = new RangeAllocator(1 << 12);
    private long[] previousOffset = new long[64];
    private int[] previousVertices = new int[64];
    private float[] skinnedBounds = new float[6 * 64];
    private MemorySegment previousTable;
    private FrameBuffer framePrevious;
    private int previousMeshes; // records the table holds
    // Displacement into copies (Displacement.COMPUTE): which copies, their sources' vertex submeshes and the frame's
    // passes; per mesh number the box it is drawn in (its bounds, grown by the table's largest displacement where
    // displaced), whether posed in place by skinning, and where it keeps the frame before's vertices this frame
    private final CopyDisplacement displacement;
    private float[] drawn = new float[7 * 64];
    private float[] margins = filledFloats(64); // how far its instance's materials displace it, as bound (-1: not yet)
    private boolean[] skinnedNow = new boolean[64];
    private long[] keptAt = new long[64];

    private static float[] filledFloats(int length) {
        float[] array = new float[length];
        Arrays.fill(array, -1);
        return array;
    }

    private static int[] filled(int length) {
        int[] array = new int[length];
        Arrays.fill(array, -1);
        return array;
    }

    @AllowAllocation("made with its pool")
    Deformation(VulkanDevice device, GeometryPool pool, FrameSlots slots) {
        this.device = device;
        this.pool = pool;
        this.slots = slots;
        this.retired = new Retiring(slots);
        this.slotVersion = new long[slots.count()];
        Arrays.fill(slotVersion, -1);
        this.matrixRanges = new RangeAllocator(256);
        this.matrices = arena.allocate(256L * MATRIX_FLOATS * 4, 16);
        this.morphRanges = new RangeAllocator(1 << 16);
        this.morphData = GpuBuffer.hostVisible(device, 1 << 16,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
        this.displacement = new CopyDisplacement(device, pool, slots);
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
        int targets = mesh == null ? 0 : mesh.morphTargetCount();
        if (joints < (targets > 0 ? 0 : 1) || joints > 256) {
            throw new IllegalArgumentException("A copy has 1 to 256 joints, or 0 with morph targets: " + joints);
        }
        addCopy(copy, joints, mesh);
    }

    /**
     * Copy {@code copy} (a pool's deformed mesh, {@link GeometryPool#addDeformed}) is displaced once a frame by its
     * materials' types ({@link Displacement#COMPUTE}): after its morph targets and skinning, if it has them (added with
     * them before), else from its source's vertices as they are; its box grows by the material table's largest
     * displacement. The instance drawing it ({@link GpuScene#set} with the copy as its mesh) is the one whose pose,
     * materials and override entry displace it; it draws the copy as it is (never displaced again in the vertex
     * stages), and ray tracing's bottom level of it ({@link RayTracing#addDeformed}) is refitted each frame. Allocates
     * when first displacing a copy.
     */
    @AllowAllocation("adding a copy")
    public void displace(int copy) {
        if (pool.deformedFrom(copy) < 0) {
            throw new IllegalArgumentException("Not a deformed copy: " + copy);
        }
        if (copy >= added.length || !added[copy]) {
            addCopy(copy, 0, null); // no joints, no morphs: displaced from its source alone
        }
        displacement.add(copy);
        posed[copy] = true;
        changed[copy] = true;
    }

    /** Whether pool mesh {@code mesh} is a copy displaced once a frame ({@link #displace}). */
    public boolean displaces(int mesh) {
        return displacement.displaces(mesh);
    }

    /**
     * Displaced copy {@code copy} is drawn by instance {@code instance} ({@link GpuScene} writing it), whose materials
     * displace it at most {@code margin}: its box grows by that, around its source's box where it has no joints or
     * morphs. On any thread for different copies.
     */
    void bind(int copy, int instance, float margin) {
        displacement.bind(copy, instance);
        if (margins[copy] != margin) {
            margins[copy] = margin;
            changed[copy] = true;
            if (jointCount[copy] == 0 && targetCount[copy] == 0) {
                displacement.restBounds(copy, margin, bounds);
            }
        }
    }

    /** How much copy {@code copy}'s posed box grows when drawn: its margin where displaced and posed by joints. */
    private float grown(int copy) {
        return displacement.displaces(copy) && (jointCount[copy] > 0 || targetCount[copy] > 0)
            ? Math.max(margins[copy], 0) : 0;
    }

    /** How many copies are displaced once a frame. */
    public int displacedCount() {
        return displacement.size();
    }

    @AllowAllocation("adding a copy")
    private void addCopy(int copy, int joints, MeshData mesh) {
        int source = pool.deformedFrom(copy);
        if (source < 0) {
            throw new IllegalArgumentException("Not a deformed copy: " + copy);
        }
        int targets = mesh == null ? 0 : mesh.morphTargetCount();
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
            int old = previousMatrix.length;
            previousMatrix = Arrays.copyOf(previousMatrix, length);
            Arrays.fill(previousMatrix, old, length, -1);
            blended = Arrays.copyOf(blended, length);
            factor = Arrays.copyOf(factor, length);
            previousOffset = Arrays.copyOf(previousOffset, length);
            previousVertices = Arrays.copyOf(previousVertices, length);
            skinnedBounds = Arrays.copyOf(skinnedBounds, 6 * length);
            drawn = Arrays.copyOf(drawn, 7 * length);
            int oldMargins = margins.length;
            margins = Arrays.copyOf(margins, length);
            Arrays.fill(margins, oldMargins, length, -1);
            skinnedNow = Arrays.copyOf(skinnedNow, length);
            keptAt = Arrays.copyOf(keptAt, length);
        }
        int slotCount = Math.max(1, joints + (targets + MATRIX_FLOATS - 1) / MATRIX_FLOATS);
        long first = allocateSlots(slotCount);
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
        written = true;
        if (copyCount == copies.length) {
            copies = Arrays.copyOf(copies, 2 * copyCount);
        }
        copies[copyCount++] = copy;
        for (int j = 0; j < joints; j++) { // at rest until posed
            joint(copy, j, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0);
        }
        if (keepPrevious) {
            keep(copy);
        }
    }

    /**
     * Keeps each copy's vertices as the frame before drew them, from the next frame on, for motion vectors
     * ({@link MotionVectors}): a range of a buffer of their own per copy, copied aside before each skinning, and per
     * pool mesh where they lie and whether the frame moved them ({@link #previousTableAddress}). Set while building a
     * graph, not while frames record. Allocates the memory when first on.
     */
    @AllowAllocation("set up rarely")
    public synchronized void keepPrevious(boolean on) {
        if (on && !keepPrevious) {
            if (framePrevious == null) {
                previousPositions = GpuBuffer.deviceLocal(device, 8L * previousRanges.capacity(),
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
                growTable(Math.max(64, added.length));
            }
            for (int i = 0; i < copyCount; i++) {
                keep(copies[i]);
            }
        }
        keepPrevious = on;
    }

    /** Whether copies' previous vertices are kept ({@link #keepPrevious}). */
    public boolean keepsPrevious() {
        return keepPrevious;
    }

    /** A range of the kept vertices for {@code copy}, and room for its record. */
    @AllowAllocation("adding a copy")
    private void keep(int copy) {
        if (copy >= previousMeshes) {
            growTable(Math.max(copy + 1, 2 * previousMeshes));
        }
        if (previousVertices[copy] > 0) {
            return;
        }
        int vertices = pool.vertexCountOf(copy);
        long first = previousRanges.allocate(vertices, 1);
        if (first < 0) {
            long capacity = Math.max(2 * previousRanges.capacity(), previousRanges.end() + vertices);
            retired.add(previousPositions); // frames in flight read it; copies are kept anew from their next skinning
            previousPositions = GpuBuffer.deviceLocal(device, 8L * capacity,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
            previousRanges.grow(capacity);
            first = previousRanges.allocate(vertices, 1);
        }
        previousOffset[copy] = first;
        previousVertices[copy] = vertices;
    }

    @AllowAllocation("adding a copy")
    private void growTable(int meshes) {
        MemorySegment grown = arena.allocate(32L * meshes, 16);
        if (previousTable != null) {
            MemorySegment.copy(previousTable, 0, grown, 0, previousTable.byteSize());
        }
        previousTable = grown;
        if (framePrevious != null) {
            retired.add(framePrevious);
        }
        framePrevious = FrameBuffer.create(device, 32L * meshes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots);
        previousMeshes = meshes;
    }

    /**
     * The frame's previous-vertex records (motion.slang's DeformedBefore), per pool mesh: the copy of the frame slot
     * recording ({@link #keepPrevious} on).
     */
    public long previousTableAddress() {
        return framePrevious.deviceAddress();
    }

    /** How many pool meshes the previous-vertex records cover. */
    public int previousTableMeshes() {
        return previousMeshes;
    }

    /** The kept vertices, quantized as the pool's ({@link #keepPrevious} on). */
    public long previousPositionsAddress() {
        return previousPositions.deviceAddress();
    }

    /** {@code slotCount} matrix slots, the matrices grown if they have not the room; the first's index. */
    @AllowAllocation("adding a copy, or blending one")
    private long allocateSlots(int slotCount) {
        long first = matrixRanges.allocate(slotCount, 1);
        if (first < 0) {
            long capacity = Math.max(2 * matrixRanges.capacity(), matrixRanges.capacity() + slotCount);
            MemorySegment grown = arena.allocate(capacity * MATRIX_FLOATS * 4, 16);
            MemorySegment.copy(matrices, 0, grown, 0, matrices.byteSize());
            matrices = grown; // the arena frees it with the rest
            matrixRanges.grow(capacity);
            first = matrixRanges.allocate(slotCount, 1);
        }
        return first;
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
        displacement.forget(copy);
        matrixRanges.release(firstMatrix[copy], matrixSlots[copy]);
        if (previousVertices[copy] > 0) {
            previousRanges.release(previousOffset[copy], previousVertices[copy]);
            previousVertices[copy] = 0;
            previousTable.set(ValueLayout.JAVA_INT_UNALIGNED, 32L * copy + 28, 0);
        }
        if (previousMatrix[copy] >= 0) {
            matrixRanges.release(previousMatrix[copy], matrixSlots[copy]);
            previousMatrix[copy] = -1;
        }
        blended[copy] = false;
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

    /** Bytes of joint matrices and morph weights the frame recorded last copied for the GPU (0: none skinned). */
    public long bytesUploadedLastFrame() {
        return uploaded;
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

    /**
     * Along {@code axis}, the corner of the box copy {@code copy} was last posed in ({@link #bounds}; a displaced
     * copy's grown by its displacement).
     */
    float boxMin(int copy, int axis) {
        return bounds[7 * copy + axis] - grown(copy);
    }

    /** Along {@code axis}, the size of the box copy {@code copy} was last posed in. */
    float boxExtent(int copy, int axis) {
        return bounds[7 * copy + 3 + axis] + 2 * grown(copy);
    }

    /** How many copies it poses. */
    public int size() {
        return copyCount;
    }

    /** The pool mesh id of the {@code i}th copy it poses ({@link #size}). */
    int copyAt(int i) {
        return copies[i];
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
            // Read before written: copies are posed on many threads, and a flag every thread writes ping-pongs
            if (!changed[copy]) {
                changed[copy] = true;
            }
            if (!written) {
                written = true;
            }
        }
    }

    /**
     * Whether copy {@code copy} is drawn between two steps' poses (decision A5: joints moved in fixed steps), blended on
     * the GPU: from now on {@link #joint} and {@link #weight} write the current step's pose, {@link #step} begins the
     * next, and {@link #blend} says how far from the previous one to the current one each frame draws. Turned on, the
     * previous pose is the current one (nothing moves until the next step); turned off, the current one is drawn and
     * the previous one's room let go. The caller gives bounds holding both poses.
     */
    public void blended(int copy, boolean on) {
        added(copy);
        if (on == blended[copy]) {
            return;
        }
        blended[copy] = on;
        changed[copy] = true;
        if (on) {
            previousMatrix[copy] = (int) allocateSlots(matrixSlots[copy]);
            snap(copy);
            factor[copy] = 1;
        } else { // the higher of its two rooms let go (steps swap them), so the matrices copied for the GPU end lower
            if (firstMatrix[copy] > previousMatrix[copy]) {
                MemorySegment.copy(matrices, 4L * MATRIX_FLOATS * firstMatrix[copy], matrices,
                    4L * MATRIX_FLOATS * previousMatrix[copy], 4L * MATRIX_FLOATS * matrixSlots[copy]);
                int lower = previousMatrix[copy];
                previousMatrix[copy] = firstMatrix[copy];
                firstMatrix[copy] = lower;
                written = true;
            }
            matrixRanges.release(previousMatrix[copy], matrixSlots[copy]); // frames in flight read their own copies
            previousMatrix[copy] = -1;
        }
    }

    /** Whether copy {@code copy} is drawn between two steps' poses ({@link #blended(int, boolean)}). */
    public boolean blended(int copy) {
        return copy >= 0 && copy < added.length && added[copy] && blended[copy];
    }

    /**
     * Copy {@code copy}, blended, begins a step's pose: its current pose becomes the previous one, and every joint and
     * weight is to be written anew. Allocates nothing.
     */
    public void step(int copy) {
        added(copy);
        if (!blended[copy]) {
            throw new IllegalStateException("Copy " + copy + " is not blended between steps");
        }
        int current = firstMatrix[copy];
        firstMatrix[copy] = previousMatrix[copy];
        previousMatrix[copy] = current;
        changed[copy] = true;
    }

    /** Copy {@code copy}, blended, starts from its current pose (a teleport): the previous pose is set to it. */
    public void snap(int copy) {
        added(copy);
        if (!blended[copy]) {
            throw new IllegalStateException("Copy " + copy + " is not blended between steps");
        }
        long bytes = 4L * MATRIX_FLOATS * matrixSlots[copy];
        MemorySegment.copy(matrices, 4L * MATRIX_FLOATS * firstMatrix[copy], matrices,
            4L * MATRIX_FLOATS * previousMatrix[copy], bytes);
        changed[copy] = true;
        written = true;
    }

    /**
     * How far from its previous step's pose to its current one copy {@code copy}, blended, is drawn this frame: 0 to 1
     * (a clock's {@code blend()}). Skinned again when it changes. Allocates nothing.
     */
    public void blend(int copy, double amount) {
        added(copy);
        float f = (float) Math.max(0, Math.min(1, amount));
        if (factor[copy] != f) {
            factor[copy] = f;
            changed[copy] |= blended[copy];
        }
    }

    /** How many values copy {@code copy}'s pose has: 12 per joint (a 3x4 matrix's rows), then a weight per target. */
    public int valueCount(int copy) {
        added(copy);
        return MATRIX_FLOATS * jointCount[copy] + targetCount[copy];
    }

    /** Copy {@code copy}'s current pose, {@link #valueCount} values, into {@code out}: as {@link #joint} wrote it. */
    public void read(int copy, float[] out) {
        long at = 4L * MATRIX_FLOATS * firstMatrix[copy];
        int n = valueCount(copy);
        for (int i = 0; i < n; i++) {
            out[i] = matrices.get(F32, at + 4L * i);
        }
    }

    /**
     * Writes copy {@code copy}'s current pose as {@code a} of the way from {@code from} to {@code to} (poses as
     * {@link #read} gives): two steps' poses blended on the CPU. Matrices blend linearly, so each vertex is drawn that
     * part of the way between where the two poses put it. On any thread for different copies; allocates nothing.
     */
    public void blendInto(int copy, float[] from, float[] to, double a) {
        long at = 4L * MATRIX_FLOATS * firstMatrix[copy];
        int n = MATRIX_FLOATS * jointCount[copy] + targetCount[copy];
        float k = (float) a;
        for (int i = 0; i < n; i++) {
            put(copy, at + 4L * i, from[i] + k * (to[i] - from[i]));
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
    void record(PassContext ctx, SceneData.Buffers buffers, Pipeline skin, Pipeline spheres, DeformPush push,
        Pipeline keep, KeepPush keepPush, GpuScene scene) {
        retired.release();
        releaseMorphRanges();
        // The box each copy is drawn in: as posed; displaced ones (moving every frame) grown by the table's largest
        // displacement, those without joints or morphs around their source's box
        for (int i = 0; i < copyCount; i++) {
            int copy = copies[i];
            boolean displaced = displacement.displaces(copy);
            skinnedNow[copy] = jointCount[copy] > 0 || targetCount[copy] > 0;
            int b = 7 * copy;
            float grow = grown(copy);
            for (int axis = 0; axis < 3; axis++) {
                drawn[b + axis] = boxMin(copy, axis);
                drawn[b + 3 + axis] = boxExtent(copy, axis);
            }
            drawn[b + 6] = bounds[b + 6] + grow;
            if (displaced) {
                changed[copy] = true; // displaced anew every frame
            }
        }
        int skinning = 0;
        for (int i = 0; i < copyCount; i++) {
            skinning += posed[copies[i]] && changed[copies[i]] ? 1 : 0;
        }
        skinned = skinning;
        uploaded = 0;
        boolean keeping = keepPrevious;
        if (keeping) {
            // Which copies this frame moves from what the frame before drew (skinned before, and now anew), and the
            // box those vertices are quantized in
            for (int i = 0; i < copyCount; i++) {
                int copy = copies[i];
                long at = 32L * copy;
                boolean moved = moves(copy);
                for (int axis = 0; axis < 3; axis++) {
                    previousTable.set(F32, at + 4 * axis, skinnedBounds[6 * copy + axis]);
                    previousTable.set(F32, at + 16 + 4 * axis, skinnedBounds[6 * copy + 3 + axis]);
                }
                previousTable.set(ValueLayout.JAVA_INT_UNALIGNED, at + 12, (int) previousOffset[copy]);
                previousTable.set(ValueLayout.JAVA_INT_UNALIGNED, at + 28, moved ? 1 : 0);
            }
            framePrevious.put(0, previousTable, 0, previousTable.byteSize());
        }
        if (skinning == 0) {
            return; // every copy as last skinned: their vertices stay
        }
        long room = 4L * MATRIX_FLOATS * matrixRanges.capacity();
        long bytes = 4L * MATRIX_FLOATS * matrixRanges.end(); // up to the last copy's: the rest is not read
        if (frameMatrices == null || frameMatrices.size() < room) {
            if (frameMatrices != null) {
                retired.add(frameMatrices);
            }
            frameMatrices = FrameBuffer.create(device, room,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots);
            Arrays.fill(slotVersion, -1);
        }
        if (written) {
            version++;
            written = false;
        }
        int slot = slots.current();
        if (slotVersion[slot] != version) { // else this slot's copy is the current one: skinned anew from it
            frameMatrices.put(0, matrices, 0, bytes);
            slotVersion[slot] = version;
            uploaded = bytes;
        }
        long matrixBase = frameMatrices.deviceAddress();
        var commands = ctx.unsafe().commands();
        // Frames before read the mesh table, positions and records this overwrites
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        if (keeping) {
            // What the frame before drew, aside before the skinning overwrites it
            keepPush.positions(ctx.address(buffers.positions())).previous(previousPositions.deviceAddress());
            for (int i = 0; i < copyCount; i++) {
                int copy = copies[i];
                keptAt[copy] = moves(copy) ? previousOffset[copy] : -1;
                if (moves(copy) && !(displacement.displaces(copy) && !skinnedNow[copy])) { // those kept as displaced
                    int vertices = previousVertices[copy];
                    ctx.dispatch(keep, keepPush.from(pool.vertexBase(copy)).to((int) previousOffset[copy])
                        .count(vertices), (vertices + 63) / 64);
                }
            }
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        }
        push.meshes(pool.meshTable().deviceAddress()).positions(ctx.address(buffers.positions()))
            .attributes(ctx.address(buffers.attributes())).meshlets(ctx.address(buffers.meshlets()))
            .meshletVertices(ctx.address(buffers.meshletVertices())).clusterLod(ctx.address(buffers.clusterLod()));
        for (int i = 0; i < copyCount; i++) {
            int copy = copies[i];
            if (!posed[copy] || !changed[copy] || displacement.displaces(copy) && !skinnedNow[copy]) {
                continue; // displaced from its source: copied in by the displacement below
            }
            int vertices = pool.vertexCountOf(copy);
            set(push, copy, matrixBase).vertices(vertices);
            ctx.dispatch(skin, push, (vertices + 63) / 64);
        }
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        if (displacement.size() > 0) {
            // Displaced copies: posed (or copied from their sources), then moved by their types, their spheres after
            displacement.record(ctx, buffers, scene, skinnedNow, keptAt, keeping, drawn,
                previousPositions == null ? 0 : previousPositions.deviceAddress());
        }
        for (int i = 0; i < copyCount; i++) {
            int copy = copies[i];
            if (!posed[copy] || !changed[copy]) {
                continue;
            }
            if (!displacement.displaces(copy)) {
                int meshlets = pool.meshletCount(copy);
                set(push, copy, matrixBase).vertices(meshlets);
                ctx.dispatch(spheres, push, (meshlets + 63) / 64);
            }
            changed[copy] = false;
            skins[copy]++;
            System.arraycopy(drawn, 7 * copy, skinnedBounds, 6 * copy, 6); // what its vertices are quantized in now
        }
        // Everything after reads them: culling, drawing, ray tracing
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
    }

    /**
     * Whether this frame moves copy {@code copy}'s vertices from those the frame before drew: skinned anew, posed
     * before, with room kept for them.
     */
    private boolean moves(int copy) {
        return posed[copy] && changed[copy] && skins[copy] > 0 && previousVertices[copy] > 0;
    }

    private DeformPush set(DeformPush push, int copy, long matrixBase) {
        int b = 7 * copy;
        int source = pool.deformedFrom(copy);
        push.morph(targetCount[copy] > 0 ? morphData.deviceAddress() + morphStart[source] : 0)
            .joints(jointCount[copy]).targets(targetCount[copy]);
        push.previous(matrixBase + 4L * MATRIX_FLOATS * (blended[copy] ? previousMatrix[copy] : firstMatrix[copy]))
            .blend(blended[copy] ? factor[copy] : -1);
        return push.matrices(matrixBase + 4L * MATRIX_FLOATS * firstMatrix[copy]).source(source)
            .target(copy).growth(drawn[b + 6]).boxMin(drawn[b], drawn[b + 1], drawn[b + 2])
            .boxExtent(drawn[b + 3], drawn[b + 4], drawn[b + 5]);
    }

    /** Frees its buffers: the pool's closing. */
    void close() {
        if (frameMatrices != null) {
            frameMatrices.close();
        }
        if (framePrevious != null) {
            framePrevious.close();
            previousPositions.close();
        }
        morphData.close();
        displacement.close();
        retired.close();
        arena.close();
    }
}
