package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.AccelerationStructures.Structure;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.MeshLevel;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryTrianglesDataKHR;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * {@link BottomLevels}' levels of the pool's deformed copies ({@link GeometryPool#addDeformed}): one each, refittable,
 * read straight from the copy's quantized positions, which the {@link Deformation} rewrites in the frames that pose it.
 * Built and refitted in one pass a frame ({@link #update}), after the deformation pass and before top levels are built:
 * a copy's first build once its triangles are on the GPU, then a refit (the tree kept, its boxes moved) in each frame
 * that skinned it; asked to ({@link #rebuildEvery}), a build in place every so many refits.
 *
 * <p>The build transform follows the box the copy's positions are quantized in: its source's until first skinned, then
 * the one it was last posed in ({@link Deformation#boxMin}), written per frame slot. Copies of one source share one
 * buffer of its triangles, the first copy's chosen level. Not compacted: compaction would need a copy of each refitted
 * structure, and a refittable one compacts little. Allocates nothing per frame, but for a copy's first build.
 */
@AllowAllocation("set up once")
final class DeformedBottomLevels {

    /**
     * Builds (first, or in place after refits) a frame at most: each takes a part of the scratch as large as a build's,
     * where a refit's is tiny (a fox's: 66 KB against 128 bytes), so every frame's builds and refits go in one command.
     */
    private static final int MAX_BUILDS = 32;
    private static final int FLAGS = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
        | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;
    private static final int TRANSFORM_BYTES = 48; // a 3 x 4 matrix of floats, 16-byte aligned by its place
    private static final int USAGE = VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
        | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    private final VulkanDevice device;
    private final GeometryPool pool;
    private final Deformation deformation;
    private final AccelerationStructures structures;
    private final BottomLevels levels; // the table, its version and bytes
    private final FrameSlots slots;
    private final boolean deferred;
    private Copy[] copyOf = new Copy[64]; // per pool mesh
    private Triangles[] trianglesOf = new Triangles[64]; // per source mesh
    private Copy[] copies = new Copy[16]; // densely
    private int copyCount;
    private GpuBuffer scratch; // MAX_BUILDS parts of buildStride bytes, then a part of updateStride per refit
    private long buildStride;
    private long updateStride;
    private final Arena arena = Arena.ofShared();
    private MemorySegment records; // per build of the frame: its geometry, build info, range and range pointer
    private int recordCapacity;
    private FrameBuffer transforms; // per frame slot, one per build of the frame
    private int rebuildEvery = Integer.MAX_VALUE; // never, unless asked
    private long changes; // counts frames whose builds moved traced levels: top levels refit after
    private long refits;
    private long rebuilds;
    private int updatedLastFrame;

    /** A copy's level: its structure once made, built once its triangles are on the GPU, then refitted. */
    private static final class Copy {
        final int mesh;
        final Triangles triangles;
        final int vertexCount;
        final long due; // the frame count from which its triangles are on the GPU
        final float[] restBox; // its source's box (corner, size): its positions until first skinned
        Structure structure; // made at its first build
        boolean built;
        long builtAt;
        boolean placed; // in the table: traced
        int skins; // the deformation's count of its skinnings at its last build or refit
        int refits; // since its last build

        Copy(int mesh, Triangles triangles, int vertexCount, long due, float[] restBox) {
            this.mesh = mesh;
            this.triangles = triangles;
            this.vertexCount = vertexCount;
            this.due = due;
            this.restBox = restBox;
        }
    }

    /** A source's triangles as 32-bit indices, which its copies' levels read at every build and refit. */
    private static final class Triangles {
        final GpuBuffer indices;
        final int count;
        final long due;
        int users;

        Triangles(GpuBuffer indices, int count, long due) {
            this.indices = indices;
            this.count = count;
            this.due = due;
        }
    }

    DeformedBottomLevels(VulkanDevice device, GeometryPool pool, AccelerationStructures structures,
        BottomLevels levels) {
        this.device = device;
        this.pool = pool;
        this.deformation = pool.deformation();
        this.structures = structures;
        this.levels = levels;
        this.slots = pool.frameSlots();
        this.deferred = structures.deferred();
    }

    /** As {@link RayTracing#addDeformed} says. */
    @AllowAllocation("adding a copy")
    boolean add(int copy, MeshData data, int level) {
        int source = pool.deformedFrom(copy);
        if (source < 0) {
            throw new IllegalArgumentException("Not a deformed copy: " + copy);
        }
        if (data.vertexCount() != pool.vertexCountOf(copy)) {
            throw new IllegalArgumentException("Not the copy's mesh: " + data.vertexCount() + " vertices for "
                + pool.vertexCountOf(copy));
        }
        if (has(copy)) {
            throw new IllegalStateException("Copy " + copy + " has a bottom level already");
        }
        if (!structures.quantized()) {
            return false; // refits read the pool's positions as they are: no decoded copy of them each frame
        }
        if (Math.max(copy, source) >= copyOf.length) {
            int length = Math.max(Math.max(copy, source) + 1, 2 * copyOf.length);
            copyOf = Arrays.copyOf(copyOf, length);
            trianglesOf = Arrays.copyOf(trianglesOf, length);
        }
        MeshLevel chosen = data.levels().get(level);
        Triangles triangles = trianglesOf[source];
        if (triangles == null) {
            triangles = upload(data, chosen);
            trianglesOf[source] = triangles;
        }
        triangles.users++;
        levels.describe(copy, data, chosen);
        float[] box = new float[6];
        for (int axis = 0; axis < 3; axis++) {
            box[axis] = (float) data.boxMin(axis);
            box[3 + axis] = (float) data.boxExtent(axis);
        }
        // Its positions are copied from the source's with the uploads, which arrive in the next frame.
        Copy added = new Copy(copy, triangles, data.vertexCount(),
            deferred ? Math.max(triangles.due, slots.frames() + 2) : 0, box);
        copyOf[copy] = added;
        if (copyCount == copies.length) {
            copies = Arrays.copyOf(copies, 2 * copyCount);
        }
        copies[copyCount++] = added;
        return true;
    }

    /** {@code level} of {@code data}'s triangles on the GPU: staged with the pool's uploads with frames in flight. */
    @AllowAllocation("a source's first copy")
    private Triangles upload(MeshData data, MeshLevel level) {
        int firstMeshlet = level.firstMeshlet();
        int lastMeshlet = firstMeshlet + level.meshletCount() - 1;
        int firstIndex = data.meshletTriangleOffset(firstMeshlet);
        int endIndex = data.meshletTriangleOffset(lastMeshlet) + 3 * data.meshletTriangleCount(lastMeshlet);
        GpuBuffer indices;
        try (Arena arena = Arena.ofConfined()) {
            var slice = data.expandIndices(arena).asSlice(4L * firstIndex, 4L * (endIndex - firstIndex));
            if (deferred) {
                indices = GpuBuffer.deviceLocal(device, slice.byteSize(), USAGE);
                pool.uploads().write(indices, 0, slice);
            } else {
                indices = GpuBuffer.upload(device, slice, USAGE); // without frames in flight: set up, waited for
            }
        }
        return new Triangles(indices, (endIndex - firstIndex) / 3, deferred ? slots.frames() + 2 : 0);
    }

    /** Whether pool mesh {@code mesh} is a copy with a level here, traced or on its way. */
    boolean has(int mesh) {
        return mesh >= 0 && mesh < copyOf.length && copyOf[mesh] != null;
    }

    /** Whether copy {@code mesh}'s level is traced. */
    boolean traced(int mesh) {
        return has(mesh) && copyOf[mesh].placed;
    }

    /** How many levels are on their way: not traced yet. */
    int pending() {
        int pending = 0;
        for (int i = 0; i < copyCount; i++) {
            pending += copies[i].placed ? 0 : 1;
        }
        return pending;
    }

    /** Counts frames whose builds changed traced levels: top levels built at another count are refitted. */
    long changes() {
        return changes;
    }

    /** How many refits there were, of all copies. */
    long refits() {
        return refits;
    }

    /** How many builds in place there were, refits having loosened a level. */
    long rebuilds() {
        return rebuilds;
    }

    /** How many levels the frame recorded last built or refitted. */
    int updatedLastFrame() {
        return updatedLastFrame;
    }

    /** Refits of a copy's level between builds in place, at least 1. */
    void rebuildEvery(int refits) {
        if (refits < 1) {
            throw new IllegalArgumentException("At least one refit between builds: " + refits);
        }
        rebuildEvery = refits;
    }

    /** Drops copy {@code mesh}'s level, if it has one: out of top levels from their next build, freed after the frames. */
    @AllowAllocation("removing a copy")
    void remove(int mesh) {
        if (!has(mesh)) {
            return;
        }
        Copy copy = copyOf[mesh];
        copyOf[mesh] = null;
        for (int i = 0; i < copyCount; i++) {
            if (copies[i] == copy) {
                copies[i] = copies[--copyCount];
                copies[copyCount] = null;
                break;
            }
        }
        levels.setBottom(mesh, 0);
        if (copy.placed) {
            levels.changed();
        }
        if (copy.structure != null) {
            Structure structure = copy.structure;
            levels.addBytes(-structure.buffer().size());
            structures.retire(() -> structures.destroy(structure));
        }
        Triangles triangles = copy.triangles;
        if (--triangles.users == 0) {
            for (int s = 0; s < trianglesOf.length; s++) {
                if (trianglesOf[s] == triangles) {
                    trianglesOf[s] = null;
                    break;
                }
            }
            structures.retire(triangles.indices::close); // a frame in flight may refit from it
        }
    }

    /**
     * Records the frame's builds: copies built last time are traced from now on if that frame finished; copies whose
     * triangles arrived are built; copies skinned since their last build or refit are refitted (built in place every
     * {@link #rebuildEvery} refits). In a pass after the deformation pass, before top levels are built. Allocates
     * nothing, but for first builds.
     */
    void update(VkCommandBuffer commands) {
        updatedLastFrame = 0;
        int builds = 0;
        int work = 0;
        for (int i = 0; i < copyCount; i++) {
            Copy copy = copies[i];
            if (copy.built && !copy.placed && slots.isFinished(copy.builtAt)) {
                copy.placed = true; // no frame in flight can see it before it is built
                levels.setBottom(copy.mesh, copy.structure.address());
                levels.changed();
            }
            if (!copy.built) {
                if (slots.frames() >= copy.due && builds < MAX_BUILDS) {
                    make(copy);
                    builds++;
                    work++;
                }
            } else if (deformation.skins(copy.mesh) != copy.skins) {
                work++;
            }
        }
        if (work == 0) {
            return;
        }
        prepare(work);
        long positions = pool.positions().deviceAddress(); // as the deformation pass left them
        long transformBase = transforms.deviceAddress();
        // Frames before traced these levels, and the deformation pass wrote their positions.
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT
            | VK_ACCESS_2_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
            VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
        boolean moved = false;
        // Of the frame's records: builds from the first on, refits from the last back, each kind a command.
        int buildCount = 0;
        int refitCount = 0;
        long scratchBase = structures.align(scratch.deviceAddress());
        long refitBase = scratchBase + buildStride * MAX_BUILDS; // after the builds' parts
        long geometries = records.address();
        long infos = geometries + (long) VkAccelerationStructureGeometryKHR.SIZEOF * recordCapacity;
        long ranges = infos + (long) VkAccelerationStructureBuildGeometryInfoKHR.SIZEOF * recordCapacity;
        long rangePointers = ranges + (long) VkAccelerationStructureBuildRangeInfoKHR.SIZEOF * recordCapacity;
        int parts = builds; // of the builds' scratch, taken: first builds first, then builds in place
        for (int i = 0; i < copyCount; i++) {
            Copy copy = copies[i];
            int skins = deformation.skins(copy.mesh);
            boolean first = copy.structure != null && !copy.built;
            if (!first && !(copy.built && skins != copy.skins) || buildCount + refitCount == work) {
                continue; // (skinned since counted, on another thread recording: refitted next frame)
            }
            // Built in place once refits loosened it, while the frame has room for builds: else refitted once more.
            boolean refit = !first && (copy.refits < rebuildEvery || parts >= MAX_BUILDS);
            int entry = refit ? work - 1 - refitCount++ : buildCount++;
            long scratchAddress;
            if (first) {
                copy.built = true;
                copy.builtAt = slots.retireAt();
                copy.refits = copy.mesh % rebuildEvery; // copies made together rebuild in different frames
                scratchAddress = scratchBase + buildStride * --builds;
            } else if (refit) {
                copy.refits += copy.refits < rebuildEvery ? 1 : 0;
                refits++;
                scratchAddress = refitBase + updateStride * entry;
            } else {
                copy.refits = 0;
                rebuilds++;
                scratchAddress = scratchBase + buildStride * parts++;
            }
            moved |= copy.placed;
            copy.skins = skins;
            writeTransform(copy, (long) TRANSFORM_BYTES * entry);
            record(copy, refit, positions, transformBase + (long) TRANSFORM_BYTES * entry, scratchAddress,
                geometries + (long) VkAccelerationStructureGeometryKHR.SIZEOF * entry,
                infos + (long) VkAccelerationStructureBuildGeometryInfoKHR.SIZEOF * entry,
                ranges + (long) VkAccelerationStructureBuildRangeInfoKHR.SIZEOF * entry);
            memPutAddress(rangePointers + 8L * entry, ranges + (long) VkAccelerationStructureBuildRangeInfoKHR.SIZEOF
                * entry);
        }
        // Builds, then refits: each with scratch of its own, independent, so the GPU overlaps them.
        if (buildCount > 0) {
            Commands.buildAccelerationStructures(commands, buildCount, infos, rangePointers);
        }
        if (refitCount > 0) {
            int from = work - refitCount;
            Commands.buildAccelerationStructures(commands, refitCount,
                infos + (long) VkAccelerationStructureBuildGeometryInfoKHR.SIZEOF * from, rangePointers + 8L * from);
        }
        // Top levels built after, and the rays traced through them, read the levels.
        Barriers.memory(commands, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
            VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        updatedLastFrame = buildCount + refitCount;
        changes += moved ? 1 : 0;
    }

    /** The structure of {@code copy}, for its first build, and the scratch its builds and refits take. */
    @AllowAllocation("a copy's first build")
    private void make(Copy copy) {
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            geometry.geometry().triangles()
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR)
                .vertexFormat(VK_FORMAT_R16G16B16A16_UNORM)
                .vertexStride(MeshData.POSITION_BYTES)
                .maxVertex(copy.vertexCount - 1)
                .indexType(VK_INDEX_TYPE_UINT32);
            geometry.geometry().triangles().transformData().deviceAddress(16); // non-null: transformed
            VkAccelerationStructureBuildGeometryInfoKHR info = AccelerationStructures.buildInfo(stack,
                VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, FLAGS, geometry);
            VkAccelerationStructureBuildSizesInfoKHR sizes = structures.sizes(stack, info, copy.triangles.count);
            copy.structure = structures.create(sizes.accelerationStructureSize(),
                VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            levels.addBytes(sizes.accelerationStructureSize());
            buildStride = Math.max(buildStride, structures.align(sizes.buildScratchSize()));
            updateStride = Math.max(updateStride, structures.align(Math.max(1, sizes.updateScratchSize())));
        }
    }

    /**
     * Scratch for {@link #MAX_BUILDS} builds and {@code work} refits, records for {@code work} builds and transforms
     * for them in the frame: grown when short, to the copies' number at least.
     */
    @AllowAllocation("growing, rarely")
    private void prepare(int work) {
        int room = Math.max(work, copyCount);
        long scratchBytes = buildStride * MAX_BUILDS + updateStride * work;
        if (scratch == null || scratch.size() < scratchBytes + structures.align(1)) {
            if (scratch != null) {
                GpuBuffer old = scratch;
                structures.retire(old::close);
            }
            scratch = structures.scratch(buildStride * MAX_BUILDS + updateStride * room);
        }
        if (transforms == null || transforms.size() < (long) TRANSFORM_BYTES * work) {
            if (transforms != null) {
                FrameBuffer old = transforms;
                structures.retire(old::close);
            }
            transforms = FrameBuffer.create(device, (long) TRANSFORM_BYTES * room,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | USAGE, slots);
        }
        if (work > recordCapacity) { // the arena frees the old ones with the rest
            recordCapacity = room;
            records = arena.allocate((VkAccelerationStructureGeometryKHR.SIZEOF
                + VkAccelerationStructureBuildGeometryInfoKHR.SIZEOF + VkAccelerationStructureBuildRangeInfoKHR.SIZEOF
                + 8L) * room, 16);
        }
    }

    /** At {@code offset} of the frame's transforms, the box {@code copy}'s positions are quantized in. */
    private void writeTransform(Copy copy, long offset) {
        // Posed, it is skinned by the deformation pass before this one runs: in the box it was posed in, read after
        // that pass's recording or not (passes may record on several threads), as both read the same bounds.
        boolean posed = deformation.posed(copy.mesh);
        for (int axis = 0; axis < 3; axis++) {
            float min = posed ? deformation.boxMin(copy.mesh, axis) : copy.restBox[axis];
            float extent = posed ? deformation.boxExtent(copy.mesh, axis) : copy.restBox[3 + axis];
            long row = offset + 16L * axis;
            for (int column = 0; column < 3; column++) {
                transforms.putFloat(row + 4L * column, column == axis ? BottomLevels.scale(extent) : 0);
            }
            transforms.putFloat(row + 12, min);
        }
    }

    /** Writes {@code copy}'s build (or refit, in place) at {@code geometry}, {@code info} and {@code range}. */
    private void record(Copy copy, boolean refit, long positions, long transform, long scratchAddress, long geometry,
        long info, long range) {
        memPutInt(geometry + VkAccelerationStructureGeometryKHR.STYPE,
            VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR);
        memPutInt(geometry + VkAccelerationStructureGeometryKHR.GEOMETRYTYPE, VK_GEOMETRY_TYPE_TRIANGLES_KHR);
        memPutInt(geometry + VkAccelerationStructureGeometryKHR.FLAGS, VK_GEOMETRY_OPAQUE_BIT_KHR);
        long triangles = geometry + VkAccelerationStructureGeometryKHR.GEOMETRY;
        memPutInt(triangles + VkAccelerationStructureGeometryTrianglesDataKHR.STYPE,
            VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR);
        memPutInt(triangles + VkAccelerationStructureGeometryTrianglesDataKHR.VERTEXFORMAT,
            VK_FORMAT_R16G16B16A16_UNORM);
        memPutLong(triangles + VkAccelerationStructureGeometryTrianglesDataKHR.VERTEXDATA,
            positions + (long) MeshData.POSITION_BYTES * pool.vertexBase(copy.mesh));
        memPutLong(triangles + VkAccelerationStructureGeometryTrianglesDataKHR.VERTEXSTRIDE, MeshData.POSITION_BYTES);
        memPutInt(triangles + VkAccelerationStructureGeometryTrianglesDataKHR.MAXVERTEX, copy.vertexCount - 1);
        memPutInt(triangles + VkAccelerationStructureGeometryTrianglesDataKHR.INDEXTYPE, VK_INDEX_TYPE_UINT32);
        memPutLong(triangles + VkAccelerationStructureGeometryTrianglesDataKHR.INDEXDATA,
            copy.triangles.indices.deviceAddress());
        memPutLong(triangles + VkAccelerationStructureGeometryTrianglesDataKHR.TRANSFORMDATA, transform);
        memPutInt(info + VkAccelerationStructureBuildGeometryInfoKHR.STYPE,
            VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR);
        memPutInt(info + VkAccelerationStructureBuildGeometryInfoKHR.TYPE,
            VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
        memPutInt(info + VkAccelerationStructureBuildGeometryInfoKHR.FLAGS, FLAGS);
        memPutInt(info + VkAccelerationStructureBuildGeometryInfoKHR.MODE,
            refit ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR);
        memPutLong(info + VkAccelerationStructureBuildGeometryInfoKHR.SRCACCELERATIONSTRUCTURE,
            refit ? copy.structure.handle() : 0); // in place
        memPutLong(info + VkAccelerationStructureBuildGeometryInfoKHR.DSTACCELERATIONSTRUCTURE,
            copy.structure.handle());
        memPutInt(info + VkAccelerationStructureBuildGeometryInfoKHR.GEOMETRYCOUNT, 1);
        memPutAddress(info + VkAccelerationStructureBuildGeometryInfoKHR.PGEOMETRIES, geometry);
        memPutLong(info + VkAccelerationStructureBuildGeometryInfoKHR.SCRATCHDATA, scratchAddress);
        memPutInt(range + VkAccelerationStructureBuildRangeInfoKHR.PRIMITIVECOUNT, copy.triangles.count);
    }

    /** Destroys the levels and buffers; the device must be done with them. */
    void close() {
        for (int i = 0; i < copyCount; i++) {
            if (copies[i].structure != null) {
                structures.destroy(copies[i].structure);
            }
        }
        for (Triangles triangles : trianglesOf) {
            if (triangles != null) {
                triangles.indices.close();
            }
        }
        copyCount = 0;
        if (scratch != null) {
            scratch.close();
        }
        if (transforms != null) {
            transforms.close();
        }
        arena.close();
    }
}
