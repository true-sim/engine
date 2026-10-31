package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.nvkCmdBuildAccelerationStructuresKHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
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
import com.truesim.engine.render.AccelerationStructures.Structure;
import com.truesim.engine.render.layout.InstancesPush;
import com.truesim.engine.render.layout.RaySceneLayout;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.Bindless;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryInstancesDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkCommandBuffer;

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
    private final FrameSlots slots;
    private final AccelerationStructures structures; // made, freed once no frame traces them
    private final BottomLevels bottoms; // per pool mesh
    // A top level per scene, made as a scene is first followed or built from, for startInstances instances or the
    // scene's capacity: two scenes never overwrite each other's, nor rebuild each other's every frame.
    private final int startInstances;
    private final java.util.Map<GpuScene, SceneTop> tops = new java.util.IdentityHashMap<>();
    private final Pipeline instancePass;
    private boolean closed;
    private double originX; // the world position the top level is relative to
    private double originY;
    private double originZ;
    private long topLevelRefits;
    /** Refits between full builds of a top level while only instances move. */
    private static final int MAX_REFITS = 60;
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
        // Its RayScene for shaders tracing it, per frame slot: the slot and the addresses of what alpha-tests masked
        // triangles, written as each frame records (a material table's copy is the frame's).
        final com.truesim.engine.vulkan.FrameBuffer record;
        // What it was last built from: it stands while they are the same.
        boolean built;
        long builtMaterials; // the materials' version: which instances are masked
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
            record = com.truesim.engine.vulkan.FrameBuffer.create(device, RaySceneLayout.BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots,
                device.graphics().family(), device.compute().family()); // traced by async compute too
            scene.onGrow(follow);
        }

        /** Its RayScene as this frame's tracing reads it. Allocates nothing. */
        void writeRecord() {
            record.putInt(RaySceneLayout.SLOT, slot);
            record.putLong(RaySceneLayout.INSTANCES, scene.instanceAddress());
            record.putLong(RaySceneLayout.MESHES, pool.meshTable().deviceAddress());
            record.putLong(RaySceneLayout.MESHLETS, pool.meshlets().deviceAddress());
            record.putLong(RaySceneLayout.MESHLET_VERTICES, pool.meshletVertices().deviceAddress());
            record.putLong(RaySceneLayout.MESHLET_TRIANGLES, pool.meshletTriangles().deviceAddress());
            record.putLong(RaySceneLayout.ATTRIBUTES, pool.attributes().deviceAddress());
            record.putLong(RaySceneLayout.MATERIALS, scene.materialsAddress());
            record.putLong(RaySceneLayout.BOTTOM, bottoms.tableAddress());
        }

        /** A top level for {@code count} instances: its instance records, structure, scratch and bindless slot. */
        private void make(int count) {
            instances = GpuBuffer.deviceLocal(device, (long) AS_INSTANCE_BYTES * count + 16,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                    | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
            instancesAddress = (instances.deviceAddress() + 15) & ~15L;
            try (MemoryStack stack = stackPush()) {
                VkAccelerationStructureGeometryKHR.Buffer geometry = AccelerationStructures.instancesGeometry(stack,
                    instancesAddress);
                VkAccelerationStructureBuildGeometryInfoKHR info = AccelerationStructures.buildInfo(stack,
                    VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, TOP_FLAGS, geometry);
                VkAccelerationStructureBuildSizesInfoKHR sizes = structures.sizes(stack, info, count);
                top = structures.create(sizes.accelerationStructureSize(),
                    VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
                scratch = structures.scratch(Math.max(sizes.buildScratchSize(), sizes.updateScratchSize()));
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
                structures.destroy(oldTop);
                oldScratch.close();
                oldInstances.close();
            };
        }
    }

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
        // With frames in flight, whose uploads wait for the next frame, nothing is waited for: freed once no frame
        // traces it.
        structures = new AccelerationStructures(device, slots, pool.uploads().deferred());
        bottoms = new BottomLevels(device, pool, structures);
        startInstances = Math.max(1, maxInstances);
        try (ShaderModule shader = ShaderModule.load(device, "shaders/raytracing/instances.spv")) {
            instancePass = Pipeline.compute(device, shader, "instancesMain", PUSH_BYTES);
        }
    }

    /** Frees {@code free} once no frame in flight reads what it frees: at once without frames in flight. */
    private void retire(Runnable free) {
        structures.retire(free);
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
            retire(t.record::close);
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

    /** Whether bottom levels read the pool's quantized positions as they are (else decoded copies). */
    public boolean quantizedVertices() {
        return structures.quantized();
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

    /**
     * The address of {@code scene}'s {@code RayScene} (raytracing.slang) for the frame recording: its top level's slot
     * and what alpha-tests its masked triangles, for shaders tracing rays into it ({@code occluded}). Written as its
     * top level's build records ({@link #buildTopLevel}): read it in passes after that one.
     */
    public long sceneAddress(GpuScene scene) {
        return top(scene).record.deviceAddress();
    }

    /** {@link #slot(GpuScene)} of the one scene with a top level; throws with none or several. */
    public int slot() {
        return onlyTop().slot;
    }

    /** Bytes the bottom levels take, compacted. */
    public long bottomLevelBytes() {
        return bottoms.bytes();
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
        bottoms.add(mesh, data, level);
    }

    /**
     * Removes pool mesh {@code mesh}'s bottom level: its instances are left out of top levels built from now on, and it
     * is freed once no frame in flight may trace it (at once without frame slots). Done as the mesh leaves the pool
     * ({@link GeometryPool#remove}), so calling it is only for dropping the level while the mesh stays. Nothing without
     * one.
     */
    public void remove(int mesh) {
        bottoms.remove(mesh);
    }

    /** Whether pool mesh {@code mesh} has a bottom level its instances are traced through. */
    public boolean hasBottomLevel(int mesh) {
        return bottoms.hasBottomLevel(mesh);
    }

    /** How many bottom levels are on their way: staged, building or compacting. */
    public int pendingBottomLevels() {
        return bottoms.pendingBottomLevels();
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
        t.writeRecord();
        long materials = scene.materials() == null ? 0 : scene.materials().version();
        boolean same = t.built && t.builtBottoms == bottoms.version() && originX == t.builtX && originY == t.builtY
            && originZ == t.builtZ && t.builtMaterials == materials;
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
        t.builtBottoms = bottoms.version();
        t.builtMaterials = materials;
        t.builtX = originX;
        t.builtY = originY;
        t.builtZ = originZ;
        topLevelBuilds++;
        bottoms.followPool(); // the instance pass looks every instance's mesh up
        if (!declared) { // whatever traced the top level before (the last frame) is done with it before it is rebuilt
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        }
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, PUSH_BYTES);
            memPutLong(push + InstancesPush.INSTANCES, scene.instanceAddress());
            memPutLong(push + InstancesPush.BOTTOM, bottoms.tableAddress());
            memPutLong(push + InstancesPush.MATERIALS, scene.materialsAddress()); // masked instances are not opaque
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
                structures.align(t.scratch.deviceAddress()));
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

    /** Destroys the acceleration structures; the device must be done with them. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            bottoms.close();
            structures.close();
            for (SceneTop t : tops.values()) {
                t.scene.stopFollowing(t.follow);
                t.freeing().run();
                t.record.close();
            }
            tops.clear();
            instancePass.close();
        }
    }
}
