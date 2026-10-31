package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR;
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
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.FrameUploads;
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
import java.util.ArrayList;
import java.util.List;
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
 *   <li>a top-level one per scene, rebuilt on the GPU when its instances change ({@link #buildTopLevel}) from the
 *       scene's instance buffer by a compute pass: no work per instance on the CPU. It is relative to a world position, its
 *       {@link #origin(double, double, double) origin}: anything traces into it by subtracting that from its world
 *       positions (a camera shading: its own position less the origin), and the app moves it when the cameras have
 *       gone far from it, as the structure holds floats.</li>
 * </ul>
 *
 * <p>Shaders trace into a scene's top level through its slot ({@link #slot(GpuScene)}) in the bindless set's
 * acceleration structure binding, with ray queries. Instances whose mesh has no bottom level are left out of it.
 */
@AllowAllocation("set up once")
public final class RayTracing implements AutoCloseable {

    private static final int AS_INSTANCE_BYTES = 64; // VkAccelerationStructureInstanceKHR
    private static final int PUSH_BYTES = InstancesPush.BYTES;
    /** The top level's build: fast to trace, and refittable as instances move. */
    private static final int TOP_FLAGS = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
        | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;

    private final VulkanDevice device;
    private final GeometryPool pool;
    private final Bindless bindless;
    private final long scratchAlignment;
    private final boolean quantized;
    private GpuBuffer bottomTable; // per pool mesh, grown with the pool
    private Structure[] bottom;
    // A top level per scene, made as a scene is first followed or built from, for startInstances instances or the
    // scene's capacity: two scenes never overwrite each other's, nor rebuild each other's every frame.
    private final int startInstances;
    private final java.util.Map<GpuScene, SceneTop> tops = new java.util.IdentityHashMap<>();
    private final Pipeline instancePass;
    private long bottomBytes;
    private boolean closed;
    // With frames in flight (the pool's frame slots), bottom levels build in frames, never waited for: each on its way
    // through the stages below, and structures and buffers no frame in flight may read any more, freed once they are not.
    private final FrameSlots slots;
    private final boolean deferred;
    private Pending[] pendingOf; // per pool mesh: its bottom level on its way, or null
    private final List<Pending> pending = new ArrayList<>();
    private final List<Retired> retired = new ArrayList<>();
    private final FrameSlots.FrameStart start = this::recordStart;

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

    /** Something to free once no frame in flight reads it ({@link FrameSlots#isFinished}). */
    private record Retired(Runnable free, long at) {
    }
    private double originX; // the world position the top level is relative to
    private double originY;
    private double originZ;
    private long topLevelRefits;
    /** Refits between full builds of a top level while only instances move. */
    private static final int MAX_REFITS = 60;
    private long bottomVersion; // counts changes to the bottom levels: top levels built before see them at their next build
    private long topLevelBuilds;

    /**
     * A scene's top level, for maxInstances instances: grown with the scene, each time a new one in a new slot, the old
     * one retired once no frame traces it.
     */
    private final class SceneTop {
        final GpuScene scene;
        int maxInstances;
        GpuBuffer instances;
        long instancesAddress; // 16-byte aligned, as instance data must be
        Structure top;
        GpuBuffer scratch;
        volatile int slot;
        final ExternalBuffer topImport;
        final ExternalBuffer instancesImport;
        final ExternalBuffer scratchImport;
        final Runnable follow = this::follow;
        // What it was last built from: it stands while they are the same.
        boolean built;
        long builtVersion;
        long builtBottoms;
        int builtCount; // the instances of the last build: a refit keeps their number
        int refits; // since the last full build: refits loosen the tree, so a build comes every MAX_REFITS
        double builtX;
        double builtY;
        double builtZ;

        @AllowAllocation("a scene's top level, once")
        SceneTop(GpuScene scene) {
            this.scene = scene;
            make(Math.max(startInstances, scene.capacity()));
            topImport = new ExternalBuffer("top level", top.buffer());
            instancesImport = new ExternalBuffer("top level instances", instances);
            scratchImport = new ExternalBuffer("top level scratch", scratch);
            scene.onGrow(follow);
        }

        /** A top level for {@code count} instances: its instance records, structure, scratch and bindless slot. */
        private void make(int count) {
            instances = GpuBuffer.deviceLocal(device, (long) AS_INSTANCE_BYTES * count + 16,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                    | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
            instancesAddress = (instances.deviceAddress() + 15) & ~15L;
            try (MemoryStack stack = stackPush()) {
                VkAccelerationStructureGeometryKHR.Buffer geometry = instancesGeometry(stack, instancesAddress);
                VkAccelerationStructureBuildGeometryInfoKHR info = buildInfo(stack,
                    VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, TOP_FLAGS, geometry);
                VkAccelerationStructureBuildSizesInfoKHR sizes = sizes(stack, info, count);
                top = create(sizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
                scratch = RayTracing.this.scratch(Math.max(sizes.buildScratchSize(), sizes.updateScratchSize()));
            }
            slot = bindless.addAccelerationStructure(top.handle());
            maxInstances = count;
        }

        /** Grows to the scene's capacity, if it is smaller. */
        private void follow() {
            if (scene.capacity() > maxInstances) {
                grow(scene.capacity());
            }
        }

        /**
         * A top level for {@code count} instances in this one's place, in a new slot (shaders read the slot anew each
         * frame), built at the next build; the old one is freed once no frame traces it.
         */
        @AllowAllocation("growing, rarely")
        private void grow(int count) {
            retire(freeing());
            make(count);
            topImport.set(top.buffer());
            instancesImport.set(instances);
            scratchImport.set(scratch);
            built = false; // the new one is empty until built
        }

        /** What frees the top level as it is now. */
        private Runnable freeing() {
            Structure oldTop = top;
            GpuBuffer oldInstances = instances;
            GpuBuffer oldScratch = scratch;
            int oldSlot = slot;
            return () -> {
                bindless.removeAccelerationStructure(oldSlot);
                destroy(oldTop);
                oldScratch.close();
                oldInstances.close();
            };
        }
    }

    /** An acceleration structure and the buffer it lives in. */
    private record Structure(GpuBuffer buffer, long handle, long address) { }

    /**
     * Ray tracing over {@code pool}'s meshes, each scene's top level made with room for {@code maxInstances} instances
     * (or the scene's capacity, if more): it grows with its scene ({@link #follow}), to hold all its instances.
     */
    public RayTracing(VulkanDevice device, GeometryPool pool, int maxInstances) {
        if (!device.rayTracing()) {
            throw new IllegalStateException("The device has no ray tracing");
        }
        this.device = device;
        this.pool = Objects.requireNonNull(pool, "pool");
        this.bindless = device.bindless();
        this.slots = pool.frameSlots();
        pool.onRemove(forgetMesh);
        this.deferred = pool.uploads().deferred(); // frames in flight, whose uploads wait for the next frame
        this.pendingOf = new Pending[pool.capacity().meshes()];
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
        startInstances = Math.max(1, maxInstances);
        try (ShaderModule shader = ShaderModule.load(device, "shaders/raytracing/instances.spv")) {
            instancePass = Pipeline.compute(device, shader, "instancesMain", PUSH_BYTES);
        }
        if (deferred) {
            slots.register(start);
        }
    }

    /** {@code buffer}'s address rounded up to 16 bytes, as a build transform's must be. */
    private static long align16(GpuBuffer buffer) {
        return (buffer.deviceAddress() + 15) & ~15L;
    }

    /** Where in {@code buffer} its 16-byte aligned address falls. */
    private static long alignedOffset(GpuBuffer buffer) {
        return align16(buffer) - buffer.deviceAddress();
    }

    /** Frees {@code free} once no frame in flight reads what it frees: at once without frames in flight. */
    private void retire(Runnable free) {
        if (deferred) {
            retired.add(new Retired(free, slots.retireAt()));
        } else {
            free.run(); // no frames in flight: the caller waits for them
        }
    }

    /**
     * Gives {@code scene} a top level of its own and grows it with the scene from now on, to hold every instance of it,
     * as its table grows: before the frames, so a frame graph rebuilds for the new buffers. Builds from a scene follow
     * it themselves; a graph's ({@code TopLevel}) as it builds.
     */
    public void follow(GpuScene scene) {
        top(scene).follow();
    }

    /**
     * Drops {@code scene}'s top level, freed once no frame traces it: for a scene no longer traced (closing, say). A
     * later build from it makes a new one. Nothing for a scene without one.
     */
    @AllowAllocation("dropping a scene, once")
    public void stopFollowing(GpuScene scene) {
        SceneTop t = tops.remove(scene);
        if (t != null) {
            scene.stopFollowing(t.follow);
            retire(t.freeing());
        }
    }

    /** {@code scene}'s top level, made now if it has none. */
    private SceneTop top(GpuScene scene) {
        SceneTop t = tops.get(scene);
        return t != null ? t : newTop(scene);
    }

    @AllowAllocation("a scene's top level, once")
    private SceneTop newTop(GpuScene scene) {
        SceneTop t = new SceneTop(Objects.requireNonNull(scene, "scene"));
        tops.put(scene, t);
        return t;
    }

    /** The one scene's top level, where there is exactly one: else the scene must be named. */
    private SceneTop onlyTop() {
        if (tops.size() != 1) {
            throw new IllegalStateException("Top levels of " + tops.size() + " scenes: name the scene");
        }
        return tops.values().iterator().next();
    }

    private final java.util.function.IntConsumer forgetMesh = this::remove;

    /** Room for as many meshes as the pool has grown to: the per-mesh table copied into a bigger one. */
    private void growMeshes() {
        int meshes = pool.capacity().meshes();
        bottom = java.util.Arrays.copyOf(bottom, meshes);
        pendingOf = java.util.Arrays.copyOf(pendingOf, meshes);
        GpuBuffer old = bottomTable;
        bottomTable = GpuBuffer.hostVisible(device, 8L * meshes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
        MemorySegment.copy(old.memory(), 0, bottomTable.memory(), 0, old.size());
        bottomTable.memory().asSlice(old.size()).fill((byte) 0);
        retire(old::close); // frames in flight read it
    }

    /** Whether bottom levels read the pool's quantized positions as they are (else decoded copies). */
    public boolean quantizedVertices() {
        return quantized;
    }

    /** How many instances {@code scene}'s top level holds now: it grows with the scene. */
    public int instanceCapacity(GpuScene scene) {
        return top(scene).maxInstances;
    }

    /** {@link #instanceCapacity(GpuScene)} of the one scene with a top level; throws with none or several. */
    public int instanceCapacity() {
        return onlyTop().maxInstances;
    }

    /**
     * {@code scene}'s top level's slot in the bindless set's acceleration structure binding, for shaders to trace into:
     * a new one when the top level grows, so read it each frame. Made now if the scene has none.
     */
    public int slot(GpuScene scene) {
        return top(scene).slot;
    }

    /** {@link #slot(GpuScene)} of the one scene with a top level; throws with none or several. */
    public int slot() {
        return onlyTop().slot;
    }

    /** Bytes the bottom levels take, compacted. */
    public long bottomLevelBytes() {
        return bottomBytes;
    }

    /**
     * Builds the bottom level of pool mesh {@code mesh} ({@code data} is the mesh as added to the pool) from detail
     * level {@code level} (0 the coarsest, as {@link MeshData#levels()} orders them): opaque triangles, compacted. A
     * coarser level traces faster and suits soft effects; the finest matches the drawn surface best.
     *
     * <p>With the frame slots of a frame graph (the pool's), nothing is waited for: the level's inputs are staged with
     * the pool's uploads, it is built at the start of the frame after they arrive, and its instances are traced from the
     * frame after that build finished (until then they are left out); it is compacted a few frames later. Without frame
     * slots, it is built and compacted at once, waiting for the GPU.
     */
    @AllowAllocation("building, once per mesh")
    public void add(int mesh, MeshData data, int level) {
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
        bottomVersion++; // instances of the mesh enter top levels at their next build
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

    /** Stages {@code mesh}'s level for a build in a later frame ({@link #add}). */
    @AllowAllocation("building, once per mesh")
    private void addDeferred(int mesh, MeshData data, int level) {
        MeshLevel chosen = data.levels().get(level);
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

    /**
     * Removes pool mesh {@code mesh}'s bottom level: its instances are left out of top levels built from now on, and it
     * is freed once no frame in flight may trace it (at once without frame slots). Done as the mesh leaves the pool
     * ({@link GeometryPool#remove}), so calling it is only for dropping the level while the mesh stays. Nothing without
     * one.
     */
    @AllowAllocation("removing, once per mesh")
    public void remove(int mesh) {
        if (mesh >= bottom.length) {
            return; // never added
        }
        Pending onItsWay = pendingOf[mesh];
        if (onItsWay != null) {
            onItsWay.cancelled = true; // freed by the stage it is in, when the GPU is done with it
            pendingOf[mesh] = null;
            bottomTable.memory().setAtIndex(ValueLayout.JAVA_LONG, mesh, 0); // built, it may be traced already
            bottomVersion++;
        }
        Structure structure = bottom[mesh];
        if (structure == null) {
            return;
        }
        bottom[mesh] = null;
        bottomTable.memory().setAtIndex(ValueLayout.JAVA_LONG, mesh, 0);
        bottomVersion++; // its instances leave top levels at their next build
        long compacted = structure.buffer().size();
        bottomBytes -= compacted;
        if (deferred) {
            retired.add(new Retired(() -> destroy(structure), slots.retireAt()));
        } else {
            destroy(structure);
        }
    }

    /** Whether pool mesh {@code mesh} has a bottom level its instances are traced through. */
    public boolean hasBottomLevel(int mesh) {
        return mesh < bottom.length && bottom[mesh] != null;
    }

    /** How many bottom levels are on their way: staged, building or compacting. */
    public int pendingBottomLevels() {
        return pending.size();
    }

    /**
     * The frame's first commands: builds whose inputs arrived, compactions of those built, and in place those compacted;
     * then what no frame in flight reads any more freed. Allocates nothing when nothing is on its way.
     */
    private void recordStart(VkCommandBuffer commands) {
        for (int i = retired.size() - 1; i >= 0; i--) {
            if (slots.isFinished(retired.get(i).at())) {
                retired.remove(i).free().run();
            }
        }
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
            VkAccelerationStructureBuildSizesInfoKHR sizes = sizes(stack, info.get(0), p.triangles);
            p.built = create(sizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            p.scratch = scratch(sizes.buildScratchSize());
            info.get(0).dstAccelerationStructure(p.built.handle()).scratchData()
                .deviceAddress(align(p.scratch.deviceAddress()));
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
            retired.add(new Retired(() -> destroy(uncompacted), slots.retireAt()));
            return;
        }
        bottomTable.memory().setAtIndex(ValueLayout.JAVA_LONG, p.mesh, p.built.address());
        bottomVersion++; // its instances enter top levels at their next build
        p.compacted = create(size, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
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
        retired.add(new Retired(() -> destroy(uncompacted), slots.retireAt()));
        if (p.cancelled) {
            Structure compacted = p.compacted;
            retired.add(new Retired(() -> destroy(compacted), slots.retireAt()));
            return;
        }
        pendingOf[p.mesh] = null;
        bottom[p.mesh] = p.compacted;
        bottomBytes += p.compacted.buffer().size();
        bottomTable.memory().setAtIndex(ValueLayout.JAVA_LONG, p.mesh, p.compacted.address());
        bottomVersion++;
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

    /**
     * Moves the top level's origin to world position ({@code x}, {@code y}, {@code z}), from the next build: keep it
     * within a few kilometres of whatever traces rays, where its floats are fine (at 7,000 km they are 0.5 m apart).
     * Read when frames record. (0, 0, 0) at first.
     */
    public RayTracing origin(double x, double y, double z) {
        originX = x;
        originY = y;
        originZ = z;
        return this;
    }

    /** The origin's world position along {@code axis} (0 x, 1 y, 2 z). */
    public double origin(int axis) {
        return switch (axis) {
            case 0 -> originX;
            case 1 -> originY;
            case 2 -> originZ;
            default -> throw new IndexOutOfBoundsException("axis " + axis);
        };
    }

    /**
     * Records the build of {@code renderer}'s scene's top level from its instances, relative to the {@link #origin}: a
     * compute pass writes the instance records (transform, mesh's bottom level), then the top level is built, ready for
     * ray queries after this. Records no build while the scene's instances ({@link GpuScene#version()}), the origin and
     * the bottom levels are as they were at its last one: the structure built then stands. Allocates nothing, but for a
     * scene's first top level.
     */
    public void buildTopLevel(VkCommandBuffer commands, MeshletRenderer renderer) {
        buildTopLevel(commands, renderer, false);
    }

    /** Of {@link #topLevelBuilds}, how many refitted a top level rather than building it anew: instances moved only. */
    public long topLevelRefits() {
        return topLevelRefits;
    }

    /** How many times top levels were built, of all scenes: one whose instances, origin and bottom levels stayed, none. */
    public long topLevelBuilds() {
        return topLevelBuilds;
    }

    /**
     * Declares that {@code pass} builds {@code scene}'s top level
     * ({@link #buildTopLevel(VkCommandBuffer, MeshletRenderer, boolean)}): it writes the instance records, the scratch
     * and the structure. Returns the structure as a buffer of the build, for passes tracing rays through it to read
     * ({@link BufferAccess#ACCELERATION_STRUCTURE_READ}).
     */
    @AllowAllocation("building the graph, when it changes")
    public Buffer declareTopLevelBuild(GraphBuilder g, GraphBuilder.PassBuilder pass, GpuScene scene) {
        SceneTop t = top(scene);
        Buffer structure = g.importBuffer(t.topImport); // set anew as the top level grows: the graph rebuilds
        pass.write(g.importBuffer(t.instancesImport), BufferAccess.STORAGE_WRITE)
            .write(g.importBuffer(t.scratchImport), BufferAccess.ACCELERATION_STRUCTURE_BUILD)
            .write(structure, BufferAccess.ACCELERATION_STRUCTURE_BUILD);
        return structure;
    }

    /**
     * Like {@link #buildTopLevel(VkCommandBuffer, MeshletRenderer)}, in a frame graph pass that, with {@code declared},
     * declares the build ({@link #declareTopLevelBuild}) and whose readers declare tracing: the graph orders the
     * structure with them, not barriers of its own.
     */
    public void buildTopLevel(VkCommandBuffer commands, MeshletRenderer renderer, boolean declared) {
        GpuScene scene = renderer.scene();
        SceneTop t = top(scene); // a build outside a graph: made and grown now, its buffers fresh
        int count = Math.min(scene.highWater(), t.maxInstances);
        scene.upload(commands);
        boolean same = t.built && t.builtBottoms == bottomVersion && originX == t.builtX && originY == t.builtY
            && originZ == t.builtZ;
        if (same && scene.version() == t.builtVersion) {
            return; // nothing it was built from changed
        }
        // Instances moved (or changed), the same number of them, the same bottom levels and origin: refitted, the tree
        // kept, unless enough refits have loosened it. Else built anew.
        boolean refit = same && count == t.builtCount && t.refits < MAX_REFITS;
        t.refits = refit ? t.refits + 1 : 0;
        topLevelRefits += refit ? 1 : 0;
        t.built = true;
        t.builtCount = count;
        t.builtVersion = scene.version();
        t.builtBottoms = bottomVersion;
        t.builtX = originX;
        t.builtY = originY;
        t.builtZ = originZ;
        topLevelBuilds++;
        if (bottom.length < pool.capacity().meshes()) {
            growMeshes(); // the instance pass looks every instance's mesh up
        }
        if (!declared) { // whatever traced the top level before (the last frame) is done with it before it is rebuilt
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        }
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, PUSH_BYTES);
            memPutLong(push + InstancesPush.INSTANCES, scene.instanceAddress());
            memPutLong(push + InstancesPush.BOTTOM, bottomTable.deviceAddress());
            memPutLong(push + InstancesPush.RECORDS, t.instancesAddress);
            memPutInt(push + InstancesPush.COUNT, count);
            putOrigin(push + InstancesPush.ORIGIN_X, push + InstancesPush.ORIGIN_LOW_X, originX);
            putOrigin(push + InstancesPush.ORIGIN_Y, push + InstancesPush.ORIGIN_LOW_Y, originY);
            putOrigin(push + InstancesPush.ORIGIN_Z, push + InstancesPush.ORIGIN_LOW_Z, originZ);
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
            memPutLong(data + VkAccelerationStructureGeometryInstancesDataKHR.DATA, t.instancesAddress);
            long info = stack.ncalloc(VkAccelerationStructureBuildGeometryInfoKHR.ALIGNOF, 1,
                VkAccelerationStructureBuildGeometryInfoKHR.SIZEOF);
            memPutInt(info + VkAccelerationStructureBuildGeometryInfoKHR.STYPE,
                VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR);
            memPutInt(info + VkAccelerationStructureBuildGeometryInfoKHR.TYPE,
                VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            memPutInt(info + VkAccelerationStructureBuildGeometryInfoKHR.FLAGS, TOP_FLAGS);
            memPutInt(info + VkAccelerationStructureBuildGeometryInfoKHR.MODE,
                refit ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR);
            if (refit) { // in place
                memPutLong(info + VkAccelerationStructureBuildGeometryInfoKHR.SRCACCELERATIONSTRUCTURE, t.top.handle());
            }
            memPutLong(info + VkAccelerationStructureBuildGeometryInfoKHR.DSTACCELERATIONSTRUCTURE, t.top.handle());
            memPutInt(info + VkAccelerationStructureBuildGeometryInfoKHR.GEOMETRYCOUNT, 1);
            memPutAddress(info + VkAccelerationStructureBuildGeometryInfoKHR.PGEOMETRIES, geometry);
            memPutLong(info + VkAccelerationStructureBuildGeometryInfoKHR.SCRATCHDATA,
                align(t.scratch.deviceAddress()));
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

    /** {@code value} as a float at {@code high} and what it leaves at {@code low}: two floats, as instances hold. */
    private static void putOrigin(long high, long low, double value) {
        float h = (float) value;
        memPutFloat(high, h);
        memPutFloat(low, (float) (value - h));
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
            if (deferred) {
                slots.unregister(start);
            }
            for (Pending p : pending) {
                if (p.compacted != null) { // compacting: its inputs, query and scratch went when it was built
                    destroy(p.compacted);
                    destroy(p.built);
                    continue;
                }
                if (p.built != null) { // building
                    vkDestroyQueryPool(device.handle(), p.queryPool, null);
                    p.scratch.close();
                    destroy(p.built);
                }
                freeInputs(p);
            }
            pending.clear();
            for (Retired r : retired) {
                r.free().run();
            }
            retired.clear();
            pool.stopFollowing(forgetMesh);
            for (SceneTop t : tops.values()) {
                t.scene.stopFollowing(t.follow);
                t.freeing().run();
            }
            tops.clear();
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
