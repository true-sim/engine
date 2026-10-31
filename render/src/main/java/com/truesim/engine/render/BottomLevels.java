package com.truesim.engine.render;

import static com.truesim.engine.render.AccelerationStructures.align16;
import static com.truesim.engine.render.AccelerationStructures.alignedOffset;
import static com.truesim.engine.render.AccelerationStructures.buildInfo;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_COPY_ACCELERATION_STRUCTURE_INFO_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdCopyAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdWriteAccelerationStructuresPropertiesKHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_64_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_WAIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCmdResetQueryPool;
import static org.lwjgl.vulkan.VK10.vkCreateQueryPool;
import static org.lwjgl.vulkan.VK10.vkDestroyQueryPool;
import static org.lwjgl.vulkan.VK10.vkGetQueryPoolResults;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.AccelerationStructures.Structure;
import com.truesim.engine.render.layout.BottomLevelLayout;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.FrameUploads;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Vulkan;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.MeshLevel;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCopyAccelerationStructureInfoKHR;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

/**
 * {@link RayTracing}'s bottom levels: one per pool mesh, built from one of its detail levels and compacted, and the
 * table shaders find them in ({@code BottomLevel}, raytracing.slang). With frames in flight each goes through the
 * stages of a {@link Pending} at frames' starts, never waited for; without, it is built and compacted at once.
 */
@AllowAllocation("set up once")
final class BottomLevels {

    private final VulkanDevice device;
    private final GeometryPool pool;
    private final AccelerationStructures structures;
    private final FrameSlots slots;
    private final boolean deferred;
    private final boolean quantized;
    private GpuBuffer table; // per pool mesh, grown with the pool
    private Structure[] bottom;
    private long bytes;
    private long version; // counts changes to the bottom levels: top levels built before see them at their next build
    // With frames in flight (the pool's frame slots), bottom levels build in frames: each on its way through the
    // stages below.
    private Pending[] pendingOf; // per pool mesh: its bottom level on its way, or null
    private final List<Pending> pending = new ArrayList<>();
    private final FrameSlots.FrameStart start = this::recordStart;
    private final java.util.function.IntConsumer forgetMesh = this::remove;
    private boolean closed;

    /** A bottom level on its way: inputs staged; built at a frame's start; compacted at a later one; then in place. */
    private static final class Pending {
        final int mesh;
        final int triangles;
        final int vertexCount;
        final long vertices; // an address, or an offset into the pool's positions (fromPool)
        final boolean fromPool;
        final long stride;
        final int format;
        final GpuBuffer indices;
        final GpuBuffer decoded; // or null
        final GpuBuffer transform; // or null
        final long due; // the frame count from which its inputs are on the GPU
        Structure built;
        GpuBuffer scratch;
        long queryPool;
        long builtAt; // retireAt of the frame that built it
        Structure compacted;
        long copiedAt;
        boolean cancelled; // removed on its way

        Pending(int mesh, int triangles, int vertexCount, long vertices, boolean fromPool, long stride, int format,
            GpuBuffer indices, GpuBuffer decoded, GpuBuffer transform, long due) {
            this.mesh = mesh;
            this.triangles = triangles;
            this.vertexCount = vertexCount;
            this.vertices = vertices;
            this.fromPool = fromPool;
            this.stride = stride;
            this.format = format;
            this.indices = indices;
            this.decoded = decoded;
            this.transform = transform;
            this.due = due;
        }
    }

    BottomLevels(VulkanDevice device, GeometryPool pool, AccelerationStructures structures) {
        this.device = device;
        this.pool = pool;
        this.structures = structures;
        this.slots = pool.frameSlots();
        this.deferred = structures.deferred();
        this.quantized = structures.quantized();
        int meshes = pool.capacity().meshes();
        bottom = new Structure[meshes];
        pendingOf = new Pending[meshes];
        table = GpuBuffer.hostVisible(device, (long) BottomLevelLayout.BYTES * meshes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
        table.memory().fill((byte) 0);
        pool.onRemove(forgetMesh);
        if (deferred) {
            slots.register(start);
        }
    }

    /** The table's address: a {@code BottomLevel} per pool mesh. It moves as the pool grows. */
    long tableAddress() {
        return table.deviceAddress();
    }

    /** Counts changes to the bottom levels: a top level built at another count stands no more. */
    long version() {
        return version;
    }

    /** Bytes the bottom levels take, compacted. */
    long bytes() {
        return bytes;
    }

    /** Room for as many meshes as the pool has grown to, if it grew: a top level looks every instance's mesh up. */
    void followPool() {
        if (bottom.length < pool.capacity().meshes()) {
            growMeshes();
        }
    }

    /** Room for as many meshes as the pool has grown to: the per-mesh table copied into a bigger one. */
    private void growMeshes() {
        int meshes = pool.capacity().meshes();
        bottom = java.util.Arrays.copyOf(bottom, meshes);
        pendingOf = java.util.Arrays.copyOf(pendingOf, meshes);
        GpuBuffer old = table;
        table = GpuBuffer.hostVisible(device, (long) BottomLevelLayout.BYTES * meshes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
        MemorySegment.copy(old.memory(), 0, table.memory(), 0, old.size());
        table.memory().asSlice(old.size()).fill((byte) 0);
        structures.retire(old::close); // frames in flight read it
    }

    /** As {@link RayTracing#add} says. */
    @AllowAllocation("building, once per mesh")
    void add(int mesh, MeshData data, int level) {
        if (!pool.contains(mesh)) {
            throw new IllegalArgumentException("No mesh " + mesh + " in the pool");
        }
        if (mesh >= bottom.length) {
            growMeshes();
        }
        if (bottom[mesh] != null || pendingOf[mesh] != null) {
            throw new IllegalStateException("Mesh " + mesh + " has a bottom level already");
        }
        if (deferred) {
            addDeferred(mesh, data, level);
            return;
        }
        version++; // instances of the mesh enter top levels at their next build
        MeshLevel chosen = data.levels().get(level);
        int firstMeshlet = chosen.firstMeshlet();
        int lastMeshlet = firstMeshlet + chosen.meshletCount() - 1;
        int firstIndex = data.meshletTriangleOffset(firstMeshlet);
        describeBottom(mesh, data, chosen);
        int endIndex = data.meshletTriangleOffset(lastMeshlet) + 3 * data.meshletTriangleCount(lastMeshlet);
        int triangles = (endIndex - firstIndex) / 3;
        int usage = VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        GpuBuffer decoded = null;
        GpuBuffer transform = null;
        // The level's triangles as 32-bit indices, only while building: the bottom level keeps its own copy.
        GpuBuffer indices;
        try (Arena arena = Arena.ofConfined()) {
            indices = GpuBuffer.upload(device, data.expandIndices(arena).asSlice(4L * firstIndex,
                4L * (endIndex - firstIndex)), usage);
        }
        try (MemoryStack stack = stackPush()) {
            long vertices;
            long stride;
            int format;
            if (quantized) {
                // unorm in the mesh's box: the build transform scales and moves it there, as the shaders decode it.
                vertices = pool.positions().deviceAddress() + (long) MeshData.POSITION_BYTES * pool.vertexBase(mesh);
                stride = MeshData.POSITION_BYTES;
                format = VK_FORMAT_R16G16B16A16_UNORM;
                transform = GpuBuffer.hostVisible(device, 64, usage);
                MemorySegment rows = transform.memory().asSlice(alignedOffset(transform), 48);
                for (int axis = 0; axis < 3; axis++) {
                    for (int column = 0; column < 4; column++) {
                        double value = column == axis ? data.boxExtent(axis) : column == 3 ? data.boxMin(axis) : 0;
                        rows.setAtIndex(ValueLayout.JAVA_FLOAT, 4L * axis + column, (float) value);
                    }
                }
            } else {
                MemorySegment floats = Arena.ofAuto().allocate(12L * data.vertexCount(), 4);
                double[] p = new double[3];
                for (int v = 0; v < data.vertexCount(); v++) {
                    data.position(v, p);
                    for (int axis = 0; axis < 3; axis++) {
                        floats.setAtIndex(ValueLayout.JAVA_FLOAT, 3L * v + axis, (float) p[axis]);
                    }
                }
                decoded = GpuBuffer.upload(device, floats, usage);
                vertices = decoded.deviceAddress();
                stride = 12;
                format = VK_FORMAT_R32G32B32_SFLOAT;
            }
            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            geometry.geometry().triangles()
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR)
                .vertexFormat(format)
                .vertexStride(stride)
                .maxVertex(data.vertexCount() - 1)
                .indexType(VK_INDEX_TYPE_UINT32);
            geometry.geometry().triangles().vertexData().deviceAddress(vertices);
            geometry.geometry().triangles().indexData().deviceAddress(indices.deviceAddress());
            if (transform != null) {
                geometry.geometry().triangles().transformData().deviceAddress(align16(transform));
            }
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR
                .calloc(1, stack);
            info.put(0, buildInfo(stack, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                    | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR, geometry));
            VkAccelerationStructureBuildSizesInfoKHR sizes = structures.sizes(stack, info.get(0), triangles);
            Structure built = structures.create(sizes.accelerationStructureSize(),
                                  VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            try (GpuBuffer scratch = structures.scratch(sizes.buildScratchSize())) {
                info.get(0).dstAccelerationStructure(built.handle()).scratchData()
                    .deviceAddress(structures.align(scratch.deviceAddress()));
                VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR
                    .calloc(1, stack).primitiveCount(triangles);
                PointerBuffer ranges = stack.pointers(range.address());
                LongBuffer queryPool = stack.mallocLong(1);
                Vulkan.check(vkCreateQueryPool(device.handle(), VkQueryPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                    .queryType(VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR)
                    .queryCount(1), null, queryPool), "Could not create a query pool");
                LongBuffer handles = stack.longs(built.handle());
                device.submitAndWait(device.graphics(), commands -> {
                    pool.uploads().upload(commands); // the mesh's positions, if added since the last frame
                    vkCmdResetQueryPool(commands, queryPool.get(0), 0, 1);
                    vkCmdBuildAccelerationStructuresKHR(commands, info, ranges);
                    Barriers.memory(commands, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                        VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                    vkCmdWriteAccelerationStructuresPropertiesKHR(commands, handles,
                        VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR, queryPool.get(0), 0);
                });
                LongBuffer compactedSize = stack.mallocLong(1);
                Vulkan.check(vkGetQueryPoolResults(device.handle(), queryPool.get(0), 0, 1, compactedSize, 8,
                    VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT), "Could not read the compacted size");
                vkDestroyQueryPool(device.handle(), queryPool.get(0), null);
                Structure compacted = structures.create(compactedSize.get(0),
                                          VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
                device.submitAndWait(device.graphics(), commands -> vkCmdCopyAccelerationStructureKHR(commands,
                    VkCopyAccelerationStructureInfoKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COPY_ACCELERATION_STRUCTURE_INFO_KHR)
                        .src(built.handle())
                        .dst(compacted.handle())
                        .mode(VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR)));
                structures.destroy(built);
                bottom[mesh] = compacted;
                bytes += compactedSize.get(0);
                setBottom(mesh, compacted.address());
            }
        } finally {
            indices.close();
            if (decoded != null) {
                decoded.close();
            }
            if (transform != null) {
                transform.close();
            }
        }
    }

    /** Mesh {@code mesh}'s bottom level's address in the table shaders read ({@code BottomLevel}), 0 for none. */
    private void setBottom(int mesh, long address) {
        table.memory().set(ValueLayout.JAVA_LONG_UNALIGNED, (long) BottomLevelLayout.BYTES * mesh
            + BottomLevelLayout.ADDRESS, address);
    }

    /**
     * What rays alpha-testing mesh {@code mesh}'s triangles look up ({@code BottomLevel}): the level its bottom level
     * is built from, the triangle its first is, and how many submeshes (instance materials) its meshlets name.
     */
    private void describeBottom(int mesh, MeshData data, MeshLevel level) {
        int submeshes = 0;
        for (int m = level.firstMeshlet(); m < level.firstMeshlet() + level.meshletCount(); m++) {
            submeshes = Math.max(submeshes, data.meshletSubmesh(m) + 1);
        }
        MemorySegment entry = table.memory().asSlice((long) BottomLevelLayout.BYTES * mesh,
            BottomLevelLayout.BYTES);
        entry.set(ValueLayout.JAVA_INT_UNALIGNED, BottomLevelLayout.FIRST_MESHLET, level.firstMeshlet());
        entry.set(ValueLayout.JAVA_INT_UNALIGNED, BottomLevelLayout.MESHLET_COUNT, level.meshletCount());
        entry.set(ValueLayout.JAVA_INT_UNALIGNED, BottomLevelLayout.SUBMESHES, submeshes);
        entry.set(ValueLayout.JAVA_INT_UNALIGNED, BottomLevelLayout.FIRST_INDEX,
            data.meshletTriangleOffset(level.firstMeshlet()));
    }

    /** Stages {@code mesh}'s level for a build in a later frame ({@link #add}). */
    @AllowAllocation("building, once per mesh")
    private void addDeferred(int mesh, MeshData data, int level) {
        MeshLevel chosen = data.levels().get(level);
        describeBottom(mesh, data, chosen);
        int firstMeshlet = chosen.firstMeshlet();
        int lastMeshlet = firstMeshlet + chosen.meshletCount() - 1;
        int firstIndex = data.meshletTriangleOffset(firstMeshlet);
        int endIndex = data.meshletTriangleOffset(lastMeshlet) + 3 * data.meshletTriangleCount(lastMeshlet);
        int usage = VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        FrameUploads uploads = pool.uploads(); // staged with the mesh's own data: on the GPU in the same frame
        GpuBuffer indices = GpuBuffer.deviceLocal(device, 4L * (endIndex - firstIndex), usage);
        try (Arena arena = Arena.ofConfined()) {
            uploads.write(indices, 0, data.expandIndices(arena).asSlice(4L * firstIndex, 4L * (endIndex - firstIndex)));
        }
        GpuBuffer decoded = null;
        GpuBuffer transform = null;
        long vertices;
        long stride;
        int format;
        if (quantized) {
            // An offset: the pool's buffer may be replaced by a bigger one before the build, which reads the one of then.
            vertices = (long) MeshData.POSITION_BYTES * pool.vertexBase(mesh);
            stride = MeshData.POSITION_BYTES;
            format = VK_FORMAT_R16G16B16A16_UNORM;
            transform = GpuBuffer.hostVisible(device, 64, usage);
            MemorySegment rows = transform.memory().asSlice(alignedOffset(transform), 48);
            for (int axis = 0; axis < 3; axis++) {
                for (int column = 0; column < 4; column++) {
                    double value = column == axis ? data.boxExtent(axis) : column == 3 ? data.boxMin(axis) : 0;
                    rows.setAtIndex(ValueLayout.JAVA_FLOAT, 4L * axis + column, (float) value);
                }
            }
        } else {
            decoded = GpuBuffer.deviceLocal(device, 12L * data.vertexCount(), usage);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment floats = arena.allocate(12L * data.vertexCount(), 4);
                double[] p = new double[3];
                for (int v = 0; v < data.vertexCount(); v++) {
                    data.position(v, p);
                    for (int axis = 0; axis < 3; axis++) {
                        floats.setAtIndex(ValueLayout.JAVA_FLOAT, 3L * v + axis, (float) p[axis]);
                    }
                }
                uploads.write(decoded, 0, floats);
            }
            vertices = decoded.deviceAddress();
            stride = 12;
            format = VK_FORMAT_R32G32B32_SFLOAT;
        }
        // Staged now, the uploads arrive in the next frame; it is built at the start of the one after.
        Pending staged = new Pending(mesh, (endIndex - firstIndex) / 3, data.vertexCount(), vertices, quantized,
            stride, format, indices, decoded, transform, slots.frames() + 2);
        pendingOf[mesh] = staged;
        pending.add(staged);
    }

    /** As {@link RayTracing#remove} says. */
    @AllowAllocation("removing, once per mesh")
    void remove(int mesh) {
        if (mesh >= bottom.length) {
            return; // never added
        }
        Pending onItsWay = pendingOf[mesh];
        if (onItsWay != null) {
            onItsWay.cancelled = true; // freed by the stage it is in, when the GPU is done with it
            pendingOf[mesh] = null;
            setBottom(mesh, 0); // built, it may be traced already
            version++;
        }
        Structure structure = bottom[mesh];
        if (structure == null) {
            return;
        }
        bottom[mesh] = null;
        setBottom(mesh, 0);
        version++; // its instances leave top levels at their next build
        long compacted = structure.buffer().size();
        bytes -= compacted;
        structures.retire(() -> structures.destroy(structure));
    }

    /** Whether pool mesh {@code mesh} has a bottom level its instances are traced through. */
    boolean hasBottomLevel(int mesh) {
        return mesh < bottom.length && bottom[mesh] != null;
    }

    /** How many bottom levels are on their way: staged, building or compacting. */
    int pendingBottomLevels() {
        return pending.size();
    }

    /**
     * The frame's first commands: builds whose inputs arrived, compactions of those built, and in place those compacted;
     * then what no frame in flight reads any more freed. Allocates nothing when nothing is on its way.
     */
    private void recordStart(VkCommandBuffer commands) {
        structures.release();
        if (pending.isEmpty()) {
            return;
        }
        // What was written before (the inputs' uploads, builds of earlier frames) is there for the builds and copies.
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_2_MEMORY_READ_BIT
                | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
        boolean built = false;
        for (int i = pending.size() - 1; i >= 0; i--) {
            Pending p = pending.get(i);
            if (p.compacted != null) {
                if (slots.isFinished(p.copiedAt)) {
                    pending.remove(i);
                    place(p);
                }
            } else if (p.built != null) {
                if (slots.isFinished(p.builtAt)) {
                    compact(commands, p);
                }
            } else if (p.cancelled) {
                if (slots.isFinished(p.due + slots.count())) { // its uploads, done with its buffers
                    pending.remove(i);
                    freeInputs(p);
                }
            } else if (slots.frames() >= p.due) {
                build(commands, p);
                built = true;
            }
        }
        if (built) { // the compacted sizes, written after the builds
            Barriers.memory(commands, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            for (Pending p : pending) {
                if (p.built != null && p.compacted == null && p.builtAt == slots.retireAt()) {
                    try (MemoryStack stack = stackPush()) {
                        vkCmdWriteAccelerationStructuresPropertiesKHR(commands, stack.longs(p.built.handle()),
                            VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR, p.queryPool, 0);
                    }
                }
            }
        }
    }

    /** Records {@code p}'s build into the frame's first commands. */
    @AllowAllocation("building, once per mesh")
    private void build(VkCommandBuffer commands, Pending p) {
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            geometry.geometry().triangles()
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR)
                .vertexFormat(p.format)
                .vertexStride(p.stride)
                .maxVertex(p.vertexCount - 1)
                .indexType(VK_INDEX_TYPE_UINT32);
            geometry.geometry().triangles().vertexData().deviceAddress(p.fromPool
                ? pool.positionsAtFrameStart().deviceAddress() + p.vertices : p.vertices);
            geometry.geometry().triangles().indexData().deviceAddress(p.indices.deviceAddress());
            if (p.transform != null) {
                geometry.geometry().triangles().transformData().deviceAddress(align16(p.transform));
            }
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR
                .calloc(1, stack);
            info.put(0, buildInfo(stack, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                    | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR, geometry));
            VkAccelerationStructureBuildSizesInfoKHR sizes = structures.sizes(stack, info.get(0), p.triangles);
            p.built = structures.create(sizes.accelerationStructureSize(),
                          VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            p.scratch = structures.scratch(sizes.buildScratchSize());
            info.get(0).dstAccelerationStructure(p.built.handle()).scratchData()
                .deviceAddress(structures.align(p.scratch.deviceAddress()));
            LongBuffer queryPool = stack.mallocLong(1);
            Vulkan.check(vkCreateQueryPool(device.handle(), VkQueryPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                .queryType(VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR)
                .queryCount(1), null, queryPool), "Could not create a query pool");
            p.queryPool = queryPool.get(0);
            vkCmdResetQueryPool(commands, p.queryPool, 0, 1);
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR
                .calloc(1, stack).primitiveCount(p.triangles);
            vkCmdBuildAccelerationStructuresKHR(commands, info, stack.pointers(range.address()));
            p.builtAt = slots.retireAt();
        }
    }

    /**
     * Its build finished: its instances are traced through it from now on, its inputs freed, and its compacted copy
     * recorded (or, removed on its way, all of it freed).
     */
    @AllowAllocation("building, once per mesh")
    private void compact(VkCommandBuffer commands, Pending p) {
        long size;
        try (MemoryStack stack = stackPush()) {
            LongBuffer compactedSize = stack.mallocLong(1);
            Vulkan.check(vkGetQueryPoolResults(device.handle(), p.queryPool, 0, 1, compactedSize, 8,
                VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT), "Could not read the compacted size"); // done
            size = compactedSize.get(0);
        }
        vkDestroyQueryPool(device.handle(), p.queryPool, null);
        p.scratch.close();
        freeInputs(p);
        if (p.cancelled) {
            pending.remove(p);
            Structure uncompacted = p.built;
            structures.retire(() -> structures.destroy(uncompacted));
            return;
        }
        setBottom(p.mesh, p.built.address());
        version++; // its instances enter top levels at their next build
        p.compacted = structures.create(size, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
        try (MemoryStack stack = stackPush()) {
            vkCmdCopyAccelerationStructureKHR(commands, VkCopyAccelerationStructureInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COPY_ACCELERATION_STRUCTURE_INFO_KHR)
                .src(p.built.handle())
                .dst(p.compacted.handle())
                .mode(VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR));
        }
        p.copiedAt = slots.retireAt();
    }

    /** Its compacted copy is done: traced through from now on, the uncompacted one freed once no frame traces it. */
    private void place(Pending p) {
        Structure uncompacted = p.built;
        structures.retire(() -> structures.destroy(uncompacted));
        if (p.cancelled) {
            Structure compacted = p.compacted;
            structures.retire(() -> structures.destroy(compacted));
            return;
        }
        pendingOf[p.mesh] = null;
        bottom[p.mesh] = p.compacted;
        bytes += p.compacted.buffer().size();
        setBottom(p.mesh, p.compacted.address());
        version++;
    }

    private static void freeInputs(Pending p) {
        p.indices.close();
        if (p.decoded != null) {
            p.decoded.close();
        }
        if (p.transform != null) {
            p.transform.close();
        }
    }

    /** Destroys the bottom levels; the device must be done with them. */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (deferred) {
            slots.unregister(start);
        }
        for (Pending p : pending) {
            if (p.compacted != null) { // compacting: its inputs, query and scratch went when it was built
                structures.destroy(p.compacted);
                structures.destroy(p.built);
                continue;
            }
            if (p.built != null) { // building
                vkDestroyQueryPool(device.handle(), p.queryPool, null);
                p.scratch.close();
                structures.destroy(p.built);
            }
            freeInputs(p);
        }
        pending.clear();
        pool.stopFollowing(forgetMesh);
        for (Structure structure : bottom) {
            if (structure != null) {
                structures.destroy(structure);
            }
        }
        table.close();
    }
}
