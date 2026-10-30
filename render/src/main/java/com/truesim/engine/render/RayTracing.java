package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_FORMAT_FEATURE_ACCELERATION_STRUCTURE_VERTEX_BUFFER_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_COPY_ACCELERATION_STRUCTURE_INFO_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_PROPERTIES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.nvkCmdBuildAccelerationStructuresKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdCopyAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdWriteAccelerationStructuresPropertiesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCreateAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkDestroyAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_64_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_WAIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCmdResetQueryPool;
import static org.lwjgl.vulkan.VK10.vkCreateQueryPool;
import static org.lwjgl.vulkan.VK10.vkDestroyQueryPool;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;
import static org.lwjgl.vulkan.VK10.vkGetQueryPoolResults;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalBuffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.render.layout.InstancesPush;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.Bindless;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.Vulkan;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.MeshLevel;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.LongBuffer;
import java.util.Objects;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryInstancesDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCopyAccelerationStructureInfoKHR;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

/**
 * Hardware ray tracing over a {@link GeometryPool}'s meshes and a {@link MeshletRenderer}'s instances, on devices with
 * {@link VulkanDevice#rayTracing()}:
 *
 * <ul>
 *   <li>a bottom-level acceleration structure per mesh ({@link #add}), built once from one of its detail levels,
 *       straight from the pool's quantized positions (the mesh's box as the build transform) where the GPU
 *       takes that vertex format, from decoded positions where not, then compacted;</li>
 *   <li>a top-level one, rebuilt on the GPU every frame ({@link #buildTopLevel}) from a scene's instance buffer
 *       by a compute pass: no work per instance on the CPU.</li>
 * </ul>
 *
 * <p>Shaders trace into the top level through its slot ({@link #slot()}) in the bindless set's acceleration structure
 * binding, with ray queries. Instances whose mesh has no bottom level are left out of the top level.
 */
@AllowAllocation("set up once")
public final class RayTracing implements AutoCloseable {

    private static final int AS_INSTANCE_BYTES = 64; // VkAccelerationStructureInstanceKHR
    private static final int PUSH_BYTES = InstancesPush.BYTES;

    private final VulkanDevice device;
    private final GeometryPool pool;
    private final Bindless bindless;
    private final long scratchAlignment;
    private final boolean quantized;
    private final GpuBuffer bottomTable;
    private final Structure[] bottom;
    private final int maxInstances;
    private final GpuBuffer instances;
    private final long instancesAddress; // 16-byte aligned, as instance data must be
    private final Structure top;
    private final GpuBuffer topScratch;
    private final int topSlot;
    private final Pipeline instancePass;
    private long bottomBytes;
    private boolean closed;

    /** An acceleration structure and the buffer it lives in. */
    private record Structure(GpuBuffer buffer, long handle, long address) { }

    /** Ray tracing over {@code pool}'s meshes, for up to {@code maxInstances} instances. */
    public RayTracing(VulkanDevice device, GeometryPool pool, int maxInstances) {
        if (!device.rayTracing()) {
            throw new IllegalStateException("The device has no ray tracing");
        }
        this.device = device;
        this.pool = Objects.requireNonNull(pool, "pool");
        this.bindless = device.bindless();
        this.maxInstances = maxInstances;
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceAccelerationStructurePropertiesKHR properties =
                VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_PROPERTIES_KHR);
            vkGetPhysicalDeviceProperties2(device.gpu().handle(), VkPhysicalDeviceProperties2.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2).pNext(properties.address()));
            scratchAlignment = properties.minAccelerationStructureScratchOffsetAlignment();
            VkFormatProperties format = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(device.gpu().handle(), VK_FORMAT_R16G16B16A16_UNORM, format);
            quantized = (format.bufferFeatures() & VK_FORMAT_FEATURE_ACCELERATION_STRUCTURE_VERTEX_BUFFER_BIT_KHR) != 0;
        }
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        int meshes = pool.capacity().meshes();
        bottom = new Structure[meshes];
        bottomTable = GpuBuffer.hostVisible(device, 8L * meshes, storage);
        bottomTable.memory().fill((byte) 0);
        instances = GpuBuffer.deviceLocal(device, (long) AS_INSTANCE_BYTES * maxInstances + 16,
            storage | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
        instancesAddress = (instances.deviceAddress() + 15) & ~15L;
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = instancesGeometry(stack, instancesAddress);
            VkAccelerationStructureBuildGeometryInfoKHR info = buildInfo(stack, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,
                VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR, geometry);
            VkAccelerationStructureBuildSizesInfoKHR sizes = sizes(stack, info, maxInstances);
            top = create(sizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            topScratch = scratch(sizes.buildScratchSize());
        }
        topSlot = bindless.addAccelerationStructure(top.handle());
        try (ShaderModule shader = ShaderModule.load(device, "shaders/raytracing/instances.spv")) {
            instancePass = Pipeline.compute(device, shader, "instancesMain", PUSH_BYTES);
        }
    }

    /** Whether bottom levels read the pool's quantized positions as they are (else decoded copies). */
    public boolean quantizedVertices() {
        return quantized;
    }

    /** The top level's slot in the bindless set's acceleration structure binding, for shaders to trace into. */
    public int slot() {
        return topSlot;
    }

    /** Bytes the bottom levels take, compacted. */
    public long bottomLevelBytes() {
        return bottomBytes;
    }

    /**
     * Builds the bottom level of pool mesh {@code mesh} ({@code data} is the mesh as added to the pool) from detail
     * level {@code level} (0 the coarsest, as {@link MeshData#levels()} orders them): opaque triangles, compacted. A
     * coarser level traces faster and suits soft effects; the finest matches the drawn surface best. Waits for the GPU.
     */
    @AllowAllocation("building, once per mesh")
    public void add(int mesh, MeshData data, int level) {
        if (!pool.contains(mesh)) {
            throw new IllegalArgumentException("No mesh " + mesh + " in the pool");
        }
        if (bottom[mesh] != null) {
            throw new IllegalStateException("Mesh " + mesh + " has a bottom level already");
        }
        MeshLevel chosen = data.levels().get(level);
        int firstMeshlet = chosen.firstMeshlet();
        int lastMeshlet = firstMeshlet + chosen.meshletCount() - 1;
        int firstIndex = data.meshletTriangleOffset(firstMeshlet);
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
                transform = GpuBuffer.hostVisible(device, 48, usage);
                MemorySegment rows = transform.memory();
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
                geometry.geometry().triangles().transformData().deviceAddress(transform.deviceAddress());
            }
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR
                .calloc(1, stack);
            info.put(0, buildInfo(stack, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                    | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR, geometry));
            VkAccelerationStructureBuildSizesInfoKHR sizes = sizes(stack, info.get(0), triangles);
            Structure built = create(sizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            try (GpuBuffer scratch = scratch(sizes.buildScratchSize())) {
                info.get(0).dstAccelerationStructure(built.handle()).scratchData()
                    .deviceAddress(align(scratch.deviceAddress()));
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
                    pool.uploads().flush(commands); // the mesh's positions, if added since the last frame
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
                Structure compacted = create(compactedSize.get(0), VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
                device.submitAndWait(device.graphics(), commands -> vkCmdCopyAccelerationStructureKHR(commands,
                    VkCopyAccelerationStructureInfoKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COPY_ACCELERATION_STRUCTURE_INFO_KHR)
                        .src(built.handle())
                        .dst(compacted.handle())
                        .mode(VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR)));
                destroy(built);
                bottom[mesh] = compacted;
                bottomBytes += compactedSize.get(0);
                bottomTable.memory().setAtIndex(ValueLayout.JAVA_LONG, mesh, compacted.address());
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

    /**
     * Records the top level's build from {@code view}'s scene's instances, relative to the view's camera (where the
     * traced points are): a compute pass writes the instance records
     * (transform, mesh's bottom level), then the top level is built, ready for ray queries after this. Allocates
     * nothing.
     */
    public void buildTopLevel(VkCommandBuffer commands, RenderView view) {
        buildTopLevel(commands, view, false);
    }

    /**
     * Declares that {@code pass} builds the top level ({@link #buildTopLevel(VkCommandBuffer, RenderView, boolean)}):
     * it writes the instance records, the scratch and the structure. Returns the structure as a buffer of the build,
     * for passes tracing rays through it to read ({@link BufferAccess#ACCELERATION_STRUCTURE_READ}).
     */
    @AllowAllocation("building the graph, when it changes")
    public Buffer declareTopLevelBuild(GraphBuilder g, GraphBuilder.PassBuilder pass) {
        Buffer structure = g.importBuffer(new ExternalBuffer("top level", top.buffer()));
        pass.write(g.importBuffer(new ExternalBuffer("top level instances", instances)), BufferAccess.STORAGE_WRITE)
            .write(g.importBuffer(new ExternalBuffer("top level scratch", topScratch)),
                BufferAccess.ACCELERATION_STRUCTURE_BUILD)
            .write(structure, BufferAccess.ACCELERATION_STRUCTURE_BUILD);
        return structure;
    }

    /**
     * Like {@link #buildTopLevel(VkCommandBuffer, RenderView)}, in a frame graph pass that, with {@code declared},
     * declares the build ({@link #declareTopLevelBuild}) and whose readers declare tracing: the graph orders the
     * structure with them, not barriers of its own.
     */
    public void buildTopLevel(VkCommandBuffer commands, RenderView view, boolean declared) {
        GpuScene scene = view.renderer().scene();
        int count = Math.min(scene.highWater(), maxInstances);
        scene.upload(commands);
        if (!declared) { // whatever traced the top level before (the last frame) is done with it before it is rebuilt
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        }
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, PUSH_BYTES);
            memPutLong(push + InstancesPush.INSTANCES, scene.instanceAddress());
            memPutLong(push + InstancesPush.BOTTOM, bottomTable.deviceAddress());
            memPutLong(push + InstancesPush.RECORDS, instancesAddress);
            memPutInt(push + InstancesPush.COUNT, count);
            memPutLong(push + InstancesPush.VIEW, view.address()); // relative to the camera shading the traced points
            instancePass.bind(commands);
            instancePass.push(commands, push, PUSH_BYTES);
            Commands.dispatch(commands, (count + 63) / 64, 1, 1);
        }
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_2_SHADER_READ_BIT
                | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
        try (MemoryStack stack = stackPush()) {
            // Written field by field: no struct wrappers, so recording allocates nothing.
            long geometry = stack.ncalloc(VkAccelerationStructureGeometryKHR.ALIGNOF, 1,
                VkAccelerationStructureGeometryKHR.SIZEOF);
            memPutInt(geometry + VkAccelerationStructureGeometryKHR.STYPE,
                VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR);
            memPutInt(geometry + VkAccelerationStructureGeometryKHR.GEOMETRYTYPE, VK_GEOMETRY_TYPE_INSTANCES_KHR);
            memPutInt(geometry + VkAccelerationStructureGeometryKHR.FLAGS, VK_GEOMETRY_OPAQUE_BIT_KHR);
            long data = geometry + VkAccelerationStructureGeometryKHR.GEOMETRY;
            memPutInt(data + VkAccelerationStructureGeometryInstancesDataKHR.STYPE,
                VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR);
            memPutLong(data + VkAccelerationStructureGeometryInstancesDataKHR.DATA, instancesAddress);
            long info = stack.ncalloc(VkAccelerationStructureBuildGeometryInfoKHR.ALIGNOF, 1,
                VkAccelerationStructureBuildGeometryInfoKHR.SIZEOF);
            memPutInt(info + VkAccelerationStructureBuildGeometryInfoKHR.STYPE,
                VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR);
            memPutInt(info + VkAccelerationStructureBuildGeometryInfoKHR.TYPE,
                VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            memPutInt(info + VkAccelerationStructureBuildGeometryInfoKHR.FLAGS,
                VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR);
            memPutInt(info + VkAccelerationStructureBuildGeometryInfoKHR.MODE,
                VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR);
            memPutLong(info + VkAccelerationStructureBuildGeometryInfoKHR.DSTACCELERATIONSTRUCTURE, top.handle());
            memPutInt(info + VkAccelerationStructureBuildGeometryInfoKHR.GEOMETRYCOUNT, 1);
            memPutAddress(info + VkAccelerationStructureBuildGeometryInfoKHR.PGEOMETRIES, geometry);
            memPutLong(info + VkAccelerationStructureBuildGeometryInfoKHR.SCRATCHDATA,
                align(topScratch.deviceAddress()));
            long range = stack.ncalloc(VkAccelerationStructureBuildRangeInfoKHR.ALIGNOF, 1,
                VkAccelerationStructureBuildRangeInfoKHR.SIZEOF);
            memPutInt(range + VkAccelerationStructureBuildRangeInfoKHR.PRIMITIVECOUNT, count);
            long ranges = stack.nmalloc(8, 8);
            memPutAddress(ranges, range);
            nvkCmdBuildAccelerationStructuresKHR(commands, 1, info, ranges);
        }
        if (!declared) {
            Barriers.memory(commands, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        }
    }

    private static VkAccelerationStructureGeometryKHR.Buffer instancesGeometry(MemoryStack stack, long address) {
        VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
            .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
            .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
        geometry.geometry().instances()
            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR)
            .arrayOfPointers(false);
        geometry.geometry().instances().data().deviceAddress(address);
        return geometry;
    }

    private static VkAccelerationStructureBuildGeometryInfoKHR buildInfo(MemoryStack stack, int type, int flags,
        VkAccelerationStructureGeometryKHR.Buffer geometry) {
        return VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
            .type(type)
            .flags(flags)
            .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
            .geometryCount(1)
            .pGeometries(geometry);
    }

    private VkAccelerationStructureBuildSizesInfoKHR sizes(MemoryStack stack,
        VkAccelerationStructureBuildGeometryInfoKHR info, int primitives) {
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);
        vkGetAccelerationStructureBuildSizesKHR(device.handle(),
            org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info,
            stack.ints(primitives), sizes);
        return sizes;
    }

    private Structure create(long size, int type) {
        // Traced by the graphics queue and by async compute (ray-traced shadows): shared by both, no ownership to move.
        GpuBuffer buffer = GpuBuffer.deviceLocal(device, size, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, device.graphics().family(), device.compute().family());
        try (MemoryStack stack = stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            Vulkan.check(vkCreateAccelerationStructureKHR(device.handle(), VkAccelerationStructureCreateInfoKHR
                .calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                .buffer(buffer.handle())
                .size(size)
                .type(type), null, handle), "Could not create an acceleration structure");
            long address = vkGetAccelerationStructureDeviceAddressKHR(device.handle(),
                VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR)
                    .accelerationStructure(handle.get(0)));
            return new Structure(buffer, handle.get(0), address);
        }
    }

    /** Scratch memory with room to align its start. */
    private GpuBuffer scratch(long size) {
        return GpuBuffer.deviceLocal(device, size + scratchAlignment, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
    }

    private long align(long address) {
        return (address + scratchAlignment - 1) / scratchAlignment * scratchAlignment;
    }

    private void destroy(Structure structure) {
        vkDestroyAccelerationStructureKHR(device.handle(), structure.handle(), null);
        structure.buffer().close();
    }

    /** Destroys the acceleration structures; the device must be done with them. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            bindless.removeAccelerationStructure(topSlot);
            destroy(top);
            topScratch.close();
            instances.close();
            for (Structure structure : bottom) {
                if (structure != null) {
                    destroy(structure);
                }
            }
            bottomTable.close();
            instancePass.close();
        }
    }
}
