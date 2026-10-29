package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.EXTMeshShader.VK_PIPELINE_STAGE_MESH_SHADER_BIT_EXT;
import static org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
import static org.lwjgl.vulkan.EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.nvkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.nvkCmdSetViewport;
import static org.lwjgl.vulkan.VK10.nvkCmdUpdateBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdDispatchIndirect;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexedIndirect;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_INDEX_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_INDEX_INPUT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_MEMORY_BARRIER_2;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_INFO;
import static org.lwjgl.vulkan.VK13.nvkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.nvkCmdPipelineBarrier2;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.Bindless;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

/**
 * Draws a {@link GeometryPool}'s meshes GPU-driven. An instance pass tests every instance's bounds and splits each
 * visible one into chunks of 64 meshlets; a compute workgroup per chunk culls its meshlets (cluster LOD for the allowed
 * screen error, frustum, normal cone) and lists the visible ones, which are drawn on one of two paths over the same data
 * and shaders:
 *
 * <ul>
 *   <li>{@link Path#MESH_SHADERS}: a mesh workgroup per listed meshlet emits its vertices and triangles; where the
 *       device has mesh shaders. (Culling in task shaders instead left most lanes idle when instances need few
 *       meshlets: 0.95 ms against 0.59 ms for 30,000 rocks.)</li>
 *   <li>{@link Path#INDEXED}: an expansion pass writes the listed meshlets' triangles into the frame's index buffer,
 *       and one indexed draw draws them all; on every device.</li>
 * </ul>
 *
 * <pre>{@code
 * renderer.setInstance(0, car, 0, x, y, z, qx, qy, qz, qw, 1, 1, 1);         // world position, in doubles
 * renderer.setInstanceCount(1);
 * renderer.setView(camera.position(cx, cy, cz).set(...), 1.0f, MeshletRenderer.MODE_LIT); // 1 pixel of error
 * renderer.record(commands, colorView, depthView, width, height, 0, 0, 0);
 * }</pre>
 *
 * <p>Instances keep world positions in two floats per axis; each view (as many as {@link Limits#views} says, all
 * culled in one pass) draws them relative to its camera's world position, so a moving camera rewrites only its view.
 *
 * <p>The CPU writes only instances and the view: its cost does not depend on how many meshlets there are. Instance and
 * view data are read when the commands run. Given a frame graph's {@link FrameSlots}, they keep a copy per frame in
 * flight, so they may change every frame; otherwise change them only once the frame that used them has finished.
 * Recording allocates nothing.
 */
@AllowAllocation("created rarely")
public final class MeshletRenderer implements AutoCloseable {

    /** Shaded by the normals. */
    public static final int MODE_LIT = 0;
    /** A color per meshlet, to see the clusters and their detail levels. */
    public static final int MODE_MESHLETS = 1;
    /** Plain white, e.g. for coverage tests. */
    public static final int MODE_WHITE = 2;

    /** How meshlets are drawn. */
    public enum Path {
        /** Mesh shaders where the device has them and the pool's meshlets fit them, else indexed draws. */
        AUTO,
        /** Indexed indirect draws: every Vulkan 1.3 device. */
        INDEXED,
        /** Mesh shaders, a workgroup per visible meshlet. */
        MESH_SHADERS
    }

    /**
     * How much a frame holds: instances; per view, visible meshlets (at most 4,194,304: the guaranteed mesh dispatch
     * size) and, on the indexed path, visible triangles (12 bytes each in the view's index buffer); chunks of 64
     * meshlets of visible instances, shared by the views culled together (at most 65,535: the guaranteed dispatch
     * size); and how many views, from 0: cameras, shadow maps, mirrors, as many as the frame has (each has lists of
     * its own, as big as {@code draws} and {@code triangles} say). Meshlets beyond these are not drawn.
     */
    public record Limits(int instances, int draws, int chunks, int triangles, int views) {

        public Limits {
            if (instances < 1 || draws < 1 || draws > 1 << 22 || chunks < 1 || chunks > 65_535 || triangles < 1
                || views < 1) {
                throw new IllegalArgumentException("Need room for instances, 1 to 4,194,304 draws, triangles, 1 to "
                    + "65,535 chunks and views: " + instances + ", " + draws + ", " + chunks + ", "
                    + triangles + ", " + views);
            }
        }

        /** Limits for one view, the camera. */
        public Limits(int instances, int draws, int chunks, int triangles) {
            this(instances, draws, chunks, triangles, 1);
        }
    }

    /** VK_PIPELINE_STAGE_2_MESH_SHADER_BIT_EXT: the same bit as the 32-bit stage. */
    private static final long MESH_SHADER_STAGE = VK_PIPELINE_STAGE_MESH_SHADER_BIT_EXT;
    static final int INSTANCE_BYTES = 64;
    /** Instance flags above the mesh id, as meshlets/common.slang has them. */
    private static final int LIVE = 1 << 24;
    private static final int CASTS_SHADOWS = 1 << 25;
    private static final int MESH_BITS = (1 << 24) - 1;
    /** Layer 0: where instances are unless set otherwise. */
    private static final int DEFAULT_LAYERS = 1;
    private static final int INSTANCE_PUSH = 92;
    private static final int CULL_PUSH = 76;
    private static final int PHASE_ALL = 0;
    /** Rows of one instance dispatch: the guaranteed workgroup count. */
    private static final int MAX_ROWS = 65_535;
    /** Rows one buffer update writes: 8 bytes each, a few KiB of the stack. */
    private static final int ROWS_PER_UPDATE = 512;
    private static final int LISTS_BYTES = 32;
    /**
     * Both draw paths' push blocks: the materials at byte 80 and the light list at 88, where the shared fragment
     * shader reads them.
     */
    private static final int DRAW_PUSH = 96;
    /** The visibility buffer's IDs: instance + 1 (0: nothing), and meshlet << 7 | triangle. */
    public static final int ID_FORMAT = org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_UINT;
    private static final int PHASE_EARLY = 1;
    private static final int PHASE_LATE = 2;
    private static final int VIEW_BYTES = 304;
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final GeometryPool pool;
    private final Bindless bindless;
    private final Limits limits;
    private final Pipeline instancesPass;
    private final Pipeline cull;
    private final Pipeline expand;
    private final Pipeline draw;
    private final Pipeline meshDraw;
    private final Pipeline drawDepth;
    private final Pipeline meshDepth;
    private final Pipeline idsDraw; // the visibility buffer's: IDs, no shading (null without primitive IDs)
    private final Pipeline idsMesh;
    private final FrameBuffer view;
    private final FrameBuffer instances;
    private final GpuBuffer chunks;
    private final GpuBuffer chunkCommand;
    // Per view with lists: what culling finds, and the commands drawing it.
    private final GpuBuffer[] visible;
    private final GpuBuffer[] frameIndices;
    private final GpuBuffer[] expandCommand;
    private final GpuBuffer[] drawCommand;
    private final GpuBuffer lists; // the shaders' ViewLists, per view
    private final GpuBuffer drawCount;
    private final GpuBuffer visibility;
    private final int[] phaseOf; // each view's last culling phase
    private final double[] viewPositions; // each view's camera's world position
    private final GpuBuffer rows; // the views the instance pass culls, a row each, and their phases
    private final boolean[] listed; // views of the cull being recorded
    private final int[] one = new int[1]; // a single view to cull
    private final Materials defaultMaterials;
    private Materials materials;
    private Path path = Path.AUTO;
    private int instanceCount;
    private boolean closed;

    /**
     * A renderer of {@code pool}'s meshes into {@code colorFormat} and {@code depthFormat} attachments, with one copy of
     * its instances and views: for frames the caller waits for before writing the next.
     */
    public MeshletRenderer(VulkanDevice device, GeometryPool pool, Limits limits, int colorFormat, int depthFormat) {
        this(device, pool, limits, colorFormat, depthFormat, FrameSlots.single());
    }

    /**
     * A renderer whose instances and views keep a copy per frame of {@code slots} (a frame graph's
     * {@link com.truesim.engine.graph.FrameGraph#frameSlots()}), so they may change every frame while earlier frames
     * are still drawn.
     */
    public MeshletRenderer(VulkanDevice device, GeometryPool pool, Limits limits, int colorFormat, int depthFormat,
        FrameSlots slots) {
        this.pool = pool;
        this.bindless = device.bindless();
        this.limits = Objects.requireNonNull(limits, "limits");
        phaseOf = new int[limits.views()];
        viewPositions = new double[3 * limits.views()];
        listed = new boolean[limits.views()];
        try (ShaderModule instanceShader = ShaderModule.load(device, "shaders/meshlets/instances.spv");
             ShaderModule cullShader = ShaderModule.load(device, "shaders/meshlets/cull.spv");
             ShaderModule expandShader = ShaderModule.load(device, "shaders/meshlets/expand.spv");
             ShaderModule drawShader = ShaderModule.load(device, "shaders/meshlets/draw.spv")) {
            instancesPass = Pipeline.compute(device, instanceShader, "instancesMain", INSTANCE_PUSH);
            cull = Pipeline.compute(device, cullShader, "cullMain", CULL_PUSH);
            expand = Pipeline.compute(device, expandShader, "expandMain", 64);
            draw = Pipeline.graphics(device)
                .stage(VK_SHADER_STAGE_VERTEX_BIT, drawShader, "vertexMain")
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT, drawShader, "fragmentMain")
                .color(colorFormat)
                .depth(depthFormat)
                .pushConstants(DRAW_PUSH)
                .build();
            drawDepth = Pipeline.graphics(device) // depth only: shadow maps
                .stage(VK_SHADER_STAGE_VERTEX_BIT, drawShader, "vertexMain")
                .depth(depthFormat)
                .pushConstants(DRAW_PUSH)
                .build();
            ShaderModule idsShader = device.primitiveIds() ? ShaderModule.load(device, "shaders/visibility/ids.spv")
                : null;
            idsDraw = idsShader == null ? null : Pipeline.graphics(device)
                .stage(VK_SHADER_STAGE_VERTEX_BIT, drawShader, "vertexMain")
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT, idsShader, "idsMain")
                .color(ID_FORMAT)
                .depth(depthFormat)
                .pushConstants(DRAW_PUSH)
                .build();
            if (device.meshShaders()) {
                try (ShaderModule meshShader = ShaderModule.load(device, "shaders/meshlets/mesh.spv")) {
                    meshDraw = Pipeline.graphics(device)
                        .stage(VK_SHADER_STAGE_MESH_BIT_EXT, meshShader, "meshMain")
                        .stage(VK_SHADER_STAGE_FRAGMENT_BIT, drawShader, "fragmentMain") // the same shading
                        .color(colorFormat)
                        .depth(depthFormat)
                        .pushConstants(DRAW_PUSH)
                        .build();
                    meshDepth = Pipeline.graphics(device)
                        .stage(VK_SHADER_STAGE_MESH_BIT_EXT, meshShader, "meshMain")
                        .depth(depthFormat)
                        .pushConstants(DRAW_PUSH)
                        .build();
                    idsMesh = idsShader == null ? null : Pipeline.graphics(device)
                        .stage(VK_SHADER_STAGE_MESH_BIT_EXT, meshShader, "meshMain")
                        .stage(VK_SHADER_STAGE_FRAGMENT_BIT, idsShader, "idsMain")
                        .color(ID_FORMAT)
                        .depth(depthFormat)
                        .pushConstants(DRAW_PUSH)
                        .build();
                }
            } else {
                meshDraw = null;
                meshDepth = null;
                idsMesh = null;
            }
            if (idsShader != null) {
                idsShader.close();
            }
        }
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        view = FrameBuffer.create(device, (long) VIEW_BYTES * limits.views(), storage, slots);
        for (int v = 0; v < limits.views(); v++) {
            view.putInt((long) VIEW_BYTES * v + 284, -1); // every layer
        }
        instances = FrameBuffer.create(device, (long) INSTANCE_BYTES * limits.instances(), storage, slots);
        chunks = GpuBuffer.deviceLocal(device, 16L * limits.chunks(), storage);
        rows = GpuBuffer.deviceLocal(device, 8L * limits.views(), storage | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        chunkCommand = GpuBuffer.deviceLocal(device, 16,
            storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        int commandUsage = storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        // Per view: the early (or only) and late phases' counts.
        drawCount = GpuBuffer.hostVisible(device, 8L * limits.views(),
            storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        int views = limits.views();
        visible = new GpuBuffer[views];
        frameIndices = new GpuBuffer[views];
        expandCommand = new GpuBuffer[views];
        drawCommand = new GpuBuffer[views];
        lists = GpuBuffer.hostVisible(device, (long) LISTS_BYTES * views, storage);
        for (int v = 0; v < views; v++) {
            visible[v] = GpuBuffer.deviceLocal(device, 16L * limits.draws(), storage);
            frameIndices[v] = GpuBuffer.deviceLocal(device, 12L * limits.triangles(),
                storage | VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            expandCommand[v] = GpuBuffer.deviceLocal(device, 16, commandUsage);
            drawCommand[v] = GpuBuffer.deviceLocal(device, 24, commandUsage);
            MemorySegment entry = lists.memory().asSlice((long) LISTS_BYTES * v, LISTS_BYTES);
            entry.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, visible[v].deviceAddress());
            entry.set(ValueLayout.JAVA_LONG_UNALIGNED, 8, expandCommand[v].deviceAddress());
            entry.set(ValueLayout.JAVA_LONG_UNALIGNED, 16, drawCommand[v].deviceAddress());
            entry.set(ValueLayout.JAVA_LONG_UNALIGNED, 24, drawCount.deviceAddress() + 8L * v);
        }
        visibility = GpuBuffer.deviceLocal(device, 4L * limits.instances(), storage | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        defaultMaterials = new Materials(device, 1);
        materials = defaultMaterials;
        device.submitAndWait(device.graphics(), commands -> vkCmdFillBuffer(commands, visibility.handle(), 0,
            visibility.size(), 0)); // nothing visible yet: the first late phase finds out
    }

    /** Chooses how meshlets are drawn; {@link Path#AUTO} by default. */
    public void setPath(Path requested) {
        if (requested == Path.MESH_SHADERS && meshDraw == null) {
            throw new IllegalStateException("The device has no mesh shaders");
        }
        this.path = Objects.requireNonNull(requested, "path");
    }

    /** The path the next frame takes. */
    public Path path() {
        if (path == Path.AUTO) {
            return meshDraw != null && pool.fitsMeshShaders() ? Path.MESH_SHADERS : Path.INDEXED;
        }
        return path;
    }

    /**
     * Draws with {@code table}'s materials (null: the default one only). Read when the frame's commands run, as
     * instances are.
     */
    public void setMaterials(Materials table) {
        this.materials = table == null ? defaultMaterials : table;
    }

    /**
     * Sets instance {@code index}: mesh {@code mesh} of the pool at world position ({@code x}, {@code y}, {@code z}),
     * rotated by quaternion ({@code qx}, {@code qy}, {@code qz}, {@code qw}) and scaled. Each view draws it relative to
     * its camera's {@link Camera#position position}, so it is written once, not as cameras move. Allocates nothing.
     */
    public void setInstance(int index, int mesh, float x, float y, float z, float qx, float qy, float qz, float qw,
        float sx, float sy, float sz) {
        setInstance(index, mesh, 0, x, y, z, qx, qy, qz, qw, sx, sy, sz);
    }

    /**
     * Like {@link #setInstance(int, int, float, float, float, float, float, float, float, float, float, float)}, with
     * materials from {@code material} of the {@link #setMaterials table} on: the mesh's submesh s takes material
     * {@code material + s}. Allocates nothing.
     */
    public void setInstance(int index, int mesh, int material, float x, float y, float z, float qx, float qy, float qz,
        float qw, float sx, float sy, float sz) {
        setInstance(index, mesh, material, (double) x, y, z, qx, qy, qz, qw, sx, sy, sz);
    }

    /**
     * Like {@link #setInstance(int, int, int, float, float, float, float, float, float, float, float, float, float)}
     * at a world position in doubles: kept as two floats per axis (about 48 bits), precise anywhere in a large world.
     * Allocates nothing.
     */
    public void setInstance(int index, int mesh, int material, double x, double y, double z, float qx, float qy,
        float qz, float qw, float sx, float sy, float sz) {
        setInstance(index, mesh, material, DEFAULT_LAYERS, true, x, y, z, qx, qy, qz, qw, sx, sy, sz);
    }

    /**
     * Like {@link #setInstance(int, int, int, double, double, double, float, float, float, float, float, float,
     * float)}, on the layers of {@code layers} (bit n: layer n; drawn in views of any of them, see
     * {@link #setViewFilter}) and casting shadows or not (shadow views and shadow rays leave out those that do not).
     * Instances are on layer 0 and cast shadows otherwise. Allocates nothing.
     */
    public void setInstance(int index, int mesh, int material, int layers, boolean castsShadows, double x, double y,
        double z, float qx, float qy, float qz, float qw, float sx, float sy, float sz) {
        if (index < 0 || index >= limits.instances() || !pool.contains(mesh) || mesh > MESH_BITS || material < 0) {
            throw new IllegalArgumentException("No instance " + index + ", mesh " + mesh + " or material " + material);
        }
        FrameBuffer m = instances;
        long o = (long) INSTANCE_BYTES * index;
        m.putFloat(o, qx);
        m.putFloat(o + 4, qy);
        m.putFloat(o + 8, qz);
        m.putFloat(o + 12, qw);
        float hx = (float) x;
        float hy = (float) y;
        float hz = (float) z;
        m.putFloat(o + 16, hx);
        m.putFloat(o + 20, hy);
        m.putFloat(o + 24, hz);
        m.putInt(o + 28, mesh | LIVE | (castsShadows ? CASTS_SHADOWS : 0));
        m.putFloat(o + 32, sx);
        m.putFloat(o + 36, sy);
        m.putFloat(o + 40, sz);
        m.putInt(o + 44, material);
        m.putFloat(o + 48, (float) (x - hx));
        m.putFloat(o + 52, (float) (y - hy));
        m.putFloat(o + 56, (float) (z - hz));
        m.putInt(o + 60, layers);
    }

    /**
     * Removes instance {@code index}: it is a hole from now on, culled at no cost but a thread, until set again. Ids
     * stay where they are, so whatever is kept per instance (occlusion history) stays with its instance.
     * Allocates nothing.
     */
    public void removeInstance(int index) {
        Objects.checkIndex(index, limits.instances());
        instances.putInt((long) INSTANCE_BYTES * index + 28, 0);
    }

    /**
     * Which instances view {@code index} draws: those on any layer of {@code layers} (all by default), and with
     * {@code castersOnly} only those casting shadows (a shadow map's view). Kept until set again. Allocates nothing.
     */
    public void setViewFilter(int index, int layers, boolean castersOnly) {
        long o = (long) VIEW_BYTES * lists(index);
        view.putInt(o + 284, layers);
        view.putInt(o + 300, castersOnly ? 1 : 0);
    }

    /** How many instances, from 0, are drawn: ids up to the highest in use, holes ({@link #removeInstance}) skipped. */
    public void setInstanceCount(int count) {
        if (count < 0 || count > limits.instances()) {
            throw new IllegalArgumentException("0 to " + limits.instances() + " instances: " + count);
        }
        this.instanceCount = count;
    }

    /**
     * Sets the view: {@code camera}'s matrices, the error allowed on screen in pixels (detail is chosen per meshlet so
     * that simplification moves no surface by more) and the shading {@code mode}. Allocates nothing.
     */
    public void setView(Camera camera, float errorPixels, int mode) {
        setView(0, camera, errorPixels, mode);
    }

    /**
     * Sets view {@code index} (0 to {@link Limits#views} - 1; 0 is the one {@link #record} draws), e.g. a light's
     * orthographic view for {@link #recordDepth}, or another camera's. Allocates nothing.
     */
    public void setView(int index, Camera camera, float errorPixels, int mode) {
        lists(index);
        long o = (long) VIEW_BYTES * index;
        FrameBuffer m = view;
        float[] matrix = camera.viewProjection();
        for (int i = 0; i < 16; i++) {
            m.putFloat(o + 4L * i, matrix[i]);
        }
        float[] planes = camera.planes();
        for (int i = 0; i < 24; i++) {
            m.putFloat(o + 64 + 4L * i, planes[i]);
        }
        m.putFloat(o + 160, camera.projectionScale());
        m.putFloat(o + 164, errorPixels);
        m.putInt(o + 172, limits.draws());
        m.putInt(o + 176, mode);
        m.putInt(o + 180, camera.orthographic() ? 1 : 0);
        float[] inverse = camera.inverseViewProjection();
        for (int i = 0; i < 16; i++) {
            m.putFloat(o + 192 + 4L * i, inverse[i]);
        }
        // The camera's world position, two floats per axis as instances keep theirs, and the eye off it.
        for (int axis = 0; axis < 3; axis++) {
            double p = camera.position(axis);
            viewPositions[3 * index + axis] = p;
            float high = (float) p;
            m.putFloat(o + 256 + 4L * axis, high);
            m.putFloat(o + 272 + 4L * axis, (float) (p - high));
            m.putFloat(o + 288 + 4L * axis, (float) camera.eye(axis));
        }
    }

    /**
     * Records the frame: the instance pass, culling and drawing into {@code colorView} and {@code depthView}
     * ({@code width} by {@code height}, in color and depth attachment layouts), cleared to ({@code r}, {@code g},
     * {@code b}) and depth 0. Allocates nothing.
     */
    public void record(VkCommandBuffer commands, long colorView, long depthView, int width, int height, float r,
        float g, float b) {
        cullView(commands, 0, false);
        drawView(commands, 0, colorView, depthView, width, height, r, g, b);
    }

    /** Culls view {@code index} alone, in the early phase of occlusion culling or not. */
    private void cullView(VkCommandBuffer commands, int index, boolean early) {
        one[0] = lists(index);
        cullViews(commands, one, 1, early ? index : -1);
    }

    /**
     * Draws view {@code index}'s depth only (no color, no shading) into {@code depthView}, cleared to 0: e.g. a
     * shadow map from a light's orthographic view ({@link #setView(int, Camera, float, int)}). Allocates nothing.
     */
    public void recordDepth(VkCommandBuffer commands, long depthView, int width, int height, int index) {
        cullView(commands, index, false);
        drawView(commands, index, 0, depthView, width, height, 0, 0, 0);
    }

    /**
     * The early phase of two-phase occlusion culling: like {@link #record}, but only the instances visible the last
     * time {@link #recordLate} looked. Then build a depth pyramid of the depth (e.g. engine-render's
     * {@code DepthPyramid}) and record the late phase. Instances are tracked by index: keep an object's index from frame
     * to frame. Allocates nothing.
     */
    public void recordEarly(VkCommandBuffer commands, long colorView, long depthView, int width, int height, float r,
        float g, float b) {
        cullView(commands, 0, true);
        drawView(commands, 0, colorView, depthView, width, height, r, g, b);
    }

    /**
     * The late phase: tests every instance in view, and the meshlets of those newly visible, against the depth pyramid
     * of what the early phase drew (sampled slot {@code pyramid}, {@code mips} levels, made from the {@code width} by
     * {@code height} depth), and draws over the early phase's image (in the attachment layouts again) what became
     * visible. What is hidden now is not drawn early next frame; what appears is drawn late the same frame, so nothing
     * pops in. Allocates nothing.
     */
    public void recordLate(VkCommandBuffer commands, long colorView, long depthView, int width, int height,
        int pyramid, int mips) {
        recordLate(commands, colorView, depthView, width, height, pyramid, mips, 0);
    }

    /** Like {@link #recordLate(VkCommandBuffer, long, long, int, int, int, int)}, lit by the light list {@code lights}. */
    public void recordLate(VkCommandBuffer commands, long colorView, long depthView, int width, int height,
        int pyramid, int mips, long clusters) {
        one[0] = 0;
        cull(commands, one, 1, -1, 0, pyramid, width, height, mips);
        drawView(commands, 0, colorView, depthView, width, height, 0, 0, 0, clusters);
    }

    /**
     * Culls views {@code views[0]} to {@code views[count - 1]} together, however many: one instance pass and one cull
     * pass for them all, each view's meshlets into its own lists, for {@link #drawView}. View {@code early} (one of
     * them, or -1 for none) runs the early phase of occlusion culling ({@link #recordEarly}; one view at most, as
     * visibility is tracked per instance); the others draw everything in view. Chunks ({@link Limits#chunks}) are
     * shared by the views. Allocates nothing.
     */
    public void cullViews(VkCommandBuffer commands, int[] views, int count, int early) {
        boolean valid = count >= 1 && count <= views.length;
        boolean earlyListed = early < 0;
        for (int i = 0; valid && i < count; i++) {
            int v = views[i];
            valid = v >= 0 && v < limits.views() && !listed[v];
            if (valid) {
                listed[v] = true;
                earlyListed |= v == early;
            }
        }
        for (int i = 0; i < Math.min(count, views.length); i++) {
            if (views[i] >= 0 && views[i] < limits.views()) {
                listed[views[i]] = false;
            }
        }
        if (!valid || !earlyListed) {
            throw new IllegalArgumentException("Views to cull: 1 or more of the " + limits.views() + ", each once, and "
                + "the early one (or -1) among them; " + count + " given, early " + early);
        }
        cull(commands, views, count, early, -1, 0, 0, 0, 0);
    }

    /**
     * Draws view {@code index}, as culled last, into {@code colorView} and {@code depthView} ({@code width} by
     * {@code height}, in the attachment layouts): cleared to ({@code r}, {@code g}, {@code b}) and depth 0, or after
     * the late phase drawn over them. With {@code colorView} 0, depth only (no shading): a shadow map, say. Allocates
     * nothing.
     */
    public void drawView(VkCommandBuffer commands, int index, long colorView, long depthView, int width, int height,
        float r, float g, float b) {
        drawView(commands, index, colorView, depthView, width, height, r, g, b, 0);
    }

    /**
     * Like {@link #drawView(VkCommandBuffer, int, long, long, int, int, float, float, float)}, shaded with the lights
     * of {@code clusters}: the device address of the frame's light list ({@link Keys#LIGHT_LIST}; 0: the sun alone).
     */
    public void drawView(VkCommandBuffer commands, int index, long colorView, long depthView, int width, int height,
        float r, float g, float b, long clusters) {
        boolean depthOnly = colorView == 0;
        draw(commands, index, colorView, depthView, width, height, r, g, b, clusters, depthOnly ? drawDepth : draw,
            depthOnly ? meshDepth : meshDraw);
    }

    /**
     * Draws view {@code index}, as culled last, into a visibility buffer: {@code idView} ({@link #ID_FORMAT}) gets
     * what each pixel shows, cleared to 0 (nothing), and {@code depthView} its depth; after the late phase, drawn over
     * them. A shading pass then shades each pixel once (engine-render's {@code VisibilityScene}). Needs
     * {@link VulkanDevice#primitiveIds()}. Allocates nothing.
     */
    public void drawIds(VkCommandBuffer commands, int index, long idView, long depthView, int width, int height) {
        if (idsDraw == null) {
            throw new IllegalStateException("The device has no primitive IDs in fragment shaders (geometryShader)");
        }
        draw(commands, index, idView, depthView, width, height, 0, 0, 0, 0, idsDraw, idsMesh);
    }

    /** The late phase ({@link #recordLate}) into a visibility buffer ({@link #drawIds}). */
    public void recordLateIds(VkCommandBuffer commands, long idView, long depthView, int width, int height,
        int pyramid, int mips) {
        if (idsDraw == null) {
            throw new IllegalStateException("The device has no primitive IDs in fragment shaders (geometryShader)");
        }
        one[0] = 0;
        cull(commands, one, 1, -1, 0, pyramid, width, height, mips);
        drawIds(commands, 0, idView, depthView, width, height);
    }

    private void draw(VkCommandBuffer commands, int index, long colorView, long depthView, int width, int height,
        float r, float g, float b, long clusters, Pipeline indexed, Pipeline mesh) {
        int v = lists(index);
        boolean late = phaseOf[v] == PHASE_LATE;
        long viewAddress = view.deviceAddress() + (long) VIEW_BYTES * v;
        long count = drawCount.deviceAddress() + 8L * v + (late ? 4 : 0);
        try (MemoryStack stack = stackPush()) {
            beginRendering(commands, stack, colorView, depthView, width, height, r, g, b, late);
            if (path() == Path.MESH_SHADERS) {
                long push = stack.nmalloc(8, DRAW_PUSH);
                memPutLong(push, viewAddress);
                memPutLong(push + 8, pool.meshTable().deviceAddress());
                memPutLong(push + 16, instances.deviceAddress());
                memPutLong(push + 24, visible[v].deviceAddress());
                memPutLong(push + 32, count);
                memPutLong(push + 40, pool.meshlets().deviceAddress());
                memPutLong(push + 48, pool.meshletVertices().deviceAddress());
                memPutLong(push + 56, pool.meshletTriangles().deviceAddress());
                memPutLong(push + 64, pool.positions().deviceAddress());
                memPutLong(push + 72, pool.attributes().deviceAddress());
                memPutLong(push + 80, materials.deviceAddress());
                memPutLong(push + 88, clusters);
                mesh.bind(commands);
                setViewport(commands, stack, width, height);
                mesh.push(commands, push, DRAW_PUSH);
                vkCmdDrawMeshTasksIndirectEXT(commands, expandCommand[v].handle(), 0, 1, 12);
            } else {
                long push = stack.ncalloc(8, 1, DRAW_PUSH);
                memPutLong(push, viewAddress);
                memPutLong(push + 8, pool.meshTable().deviceAddress());
                memPutLong(push + 16, instances.deviceAddress());
                memPutLong(push + 24, pool.positions().deviceAddress());
                memPutLong(push + 32, pool.attributes().deviceAddress());
                memPutLong(push + 40, visible[v].deviceAddress());
                memPutLong(push + 48, pool.meshlets().deviceAddress());
                memPutLong(push + 56, pool.meshletVertices().deviceAddress());
                memPutLong(push + 80, materials.deviceAddress());
                memPutLong(push + 88, clusters);
                indexed.bind(commands);
                setViewport(commands, stack, width, height);
                indexed.push(commands, push, DRAW_PUSH);
                vkCmdBindIndexBuffer(commands, frameIndices[v].handle(), 0, VK_INDEX_TYPE_UINT32);
                vkCmdDrawIndexedIndirect(commands, drawCommand[v].handle(), 0, 1, 20);
            }
            vkCmdEndRendering(commands);
        }
    }

    /** {@code index}, a view with lists. */
    private int lists(int index) {
        if (index < 0 || index >= limits.views()) {
            throw new IllegalArgumentException("View " + index + " has no lists: the renderer's limits give "
                + limits.views() + " views");
        }
        return index;
    }

    /**
     * Culls {@code views[0]} to {@code views[count - 1]}, view {@code early} in the early phase and view {@code late}
     * in the late phase against {@code pyramid} (-1: none): resets their lists, then the instance pass (a row per
     * view), the cull pass over all their chunks and, on the indexed path, each view's expansion.
     */
    private void cull(VkCommandBuffer commands, int[] views, int count, int early, int late, int pyramid, int width,
        int height, int mips) {
        boolean meshShading = path() == Path.MESH_SHADERS;
        bindless.bind(commands); // the late phase samples the depth pyramid through it
        // What came before (the early phase, the frame before) used the buffers this resets, and the visibility.
        barrier(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            long reset = stack.nmalloc(4, 16); // the chunk command: no chunks yet, a dispatch of x by 1 by 1
            memPutInt(reset, 0);
            memPutInt(reset + 4, 1);
            memPutInt(reset + 8, 1);
            memPutInt(reset + 12, 0);
            nvkCmdUpdateBuffer(commands, chunkCommand.handle(), 0, 16, reset);
            long draw = stack.ncalloc(4, 6, 4); // no indices, one instance; word 5 counts reserved indices
            memPutInt(draw + 4, 1);
            for (int i = 0; i < count; i++) {
                int v = views[i];
                int phase = v == early ? PHASE_EARLY : v == late ? PHASE_LATE : PHASE_ALL;
                phaseOf[v] = phase;
                view.putInt((long) VIEW_BYTES * v + 168, instanceCount);
                if (phase != PHASE_LATE) {
                    vkCmdFillBuffer(commands, drawCount.handle(), 8L * v, 8, 0);
                }
                nvkCmdUpdateBuffer(commands, expandCommand[v].handle(), 0, 16, reset); // no meshlets: x by 1 by 1
                nvkCmdUpdateBuffer(commands, drawCommand[v].handle(), 0, 24, draw);
            }
        }
        writeRows(commands, views, count);
        barrier(commands, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, INSTANCE_PUSH);
            memPutLong(push, view.deviceAddress());
            memPutLong(push + 8, pool.meshTable().deviceAddress());
            memPutLong(push + 16, instances.deviceAddress());
            memPutLong(push + 24, chunks.deviceAddress());
            memPutLong(push + 32, chunkCommand.deviceAddress());
            memPutLong(push + 40, pool.levels().deviceAddress());
            memPutInt(push + 48, limits.chunks());
            memPutLong(push + 56, visibility.deviceAddress());
            occlusion(push + 64, pyramid, width, height, mips);
            memPutLong(push + 80, rows.deviceAddress());
            instancesPass.bind(commands);
            // A row of workgroups per view, at most 65,535 rows a dispatch (the guaranteed size).
            for (int first = 0; first < count; first += MAX_ROWS) {
                memPutInt(push + 88, first);
                instancesPass.push(commands, push, INSTANCE_PUSH);
                vkCmdDispatch(commands, (instanceCount + 63) / 64, Math.min(MAX_ROWS, count - first), 1);
            }
        }
        // The chunks feed the cull pass's indirect dispatch and the pass itself.
        barrier(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, CULL_PUSH);
            memPutLong(push, view.deviceAddress());
            memPutLong(push + 8, pool.meshTable().deviceAddress());
            memPutLong(push + 16, instances.deviceAddress());
            memPutLong(push + 24, chunks.deviceAddress());
            memPutLong(push + 32, pool.meshlets().deviceAddress());
            memPutLong(push + 40, pool.clusterLod().deviceAddress());
            memPutLong(push + 48, lists.deviceAddress());
            // mesh shaders read no frame index buffer: every listed meshlet fits
            memPutInt(push + 56, meshShading ? -1 : (int) Math.min(Integer.MAX_VALUE, 3L * limits.triangles()));
            occlusion(push + 60, pyramid, width, height, mips);
            cull.bind(commands);
            cull.push(commands, push, CULL_PUSH);
            vkCmdDispatchIndirect(commands, chunkCommand.handle(), 0);
        }
        if (meshShading) { // the lists and their dispatches feed the mesh shaders
            barrier(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | MESH_SHADER_STAGE,
                VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
            return;
        }
        barrier(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        expand.bind(commands);
        for (int i = 0; i < count; i++) {
            int v = views[i];
            try (MemoryStack stack = stackPush()) {
                long push = stack.nmalloc(8, 64);
                memPutLong(push, view.deviceAddress() + (long) VIEW_BYTES * v);
                memPutLong(push + 8, visible[v].deviceAddress());
                memPutLong(push + 16, drawCount.deviceAddress() + 8L * v + (phaseOf[v] == PHASE_LATE ? 4 : 0));
                memPutLong(push + 24, pool.meshlets().deviceAddress());
                memPutLong(push + 32, pool.meshTable().deviceAddress());
                memPutLong(push + 40, instances.deviceAddress());
                memPutLong(push + 48, pool.meshletTriangles().deviceAddress());
                memPutLong(push + 56, frameIndices[v].deviceAddress());
                expand.push(commands, push, 64);
                vkCmdDispatchIndirect(commands, expandCommand[v].handle(), 0);
            }
        }
        barrier(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_INDEX_INPUT_BIT
                | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT,
            VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_INDEX_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
    }

    /** Begins rendering into the attachments, cleared or, with {@code load}, as they are. */
    static void beginRendering(VkCommandBuffer commands, MemoryStack stack, long colorView, long depthView,
        int width, int height, float r, float g, float b, boolean load) {
        long color = stack.ncalloc(VkRenderingAttachmentInfo.ALIGNOF, 1, VkRenderingAttachmentInfo.SIZEOF);
        attachment(color, colorView, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, load);
        long clear = color + VkRenderingAttachmentInfo.CLEARVALUE;
        memPutFloat(clear, r);
        memPutFloat(clear + 4, g);
        memPutFloat(clear + 8, b);
        memPutFloat(clear + 12, 1);
        long depth = stack.ncalloc(VkRenderingAttachmentInfo.ALIGNOF, 1, VkRenderingAttachmentInfo.SIZEOF);
        attachment(depth, depthView, VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL, load); // cleared to 0: reverse depth
        long rendering = stack.ncalloc(VkRenderingInfo.ALIGNOF, 1, VkRenderingInfo.SIZEOF);
        memPutInt(rendering + VkRenderingInfo.STYPE, VK_STRUCTURE_TYPE_RENDERING_INFO);
        memPutInt(rendering + VkRenderingInfo.RENDERAREA + VkRect2D.EXTENT, width);
        memPutInt(rendering + VkRenderingInfo.RENDERAREA + VkRect2D.EXTENT + 4, height);
        memPutInt(rendering + VkRenderingInfo.LAYERCOUNT, 1);
        memPutInt(rendering + VkRenderingInfo.COLORATTACHMENTCOUNT, colorView == 0 ? 0 : 1);
        memPutAddress(rendering + VkRenderingInfo.PCOLORATTACHMENTS, color);
        memPutAddress(rendering + VkRenderingInfo.PDEPTHATTACHMENT, depth);
        nvkCmdBeginRendering(commands, rendering);
    }

    static void setViewport(VkCommandBuffer commands, MemoryStack stack, int width, int height) {
        long viewport = stack.ncalloc(VkViewport.ALIGNOF, 1, VkViewport.SIZEOF);
        memPutFloat(viewport + VkViewport.WIDTH, width);
        memPutFloat(viewport + VkViewport.HEIGHT, height);
        memPutFloat(viewport + VkViewport.MAXDEPTH, 1);
        nvkCmdSetViewport(commands, 0, 1, viewport);
        long scissor = stack.ncalloc(VkRect2D.ALIGNOF, 1, VkRect2D.SIZEOF);
        memPutInt(scissor + VkRect2D.EXTENT, width);
        memPutInt(scissor + VkRect2D.EXTENT + 4, height);
        nvkCmdSetScissor(commands, 0, 1, scissor);
    }

    private static void attachment(long info, long view, int layout, boolean load) {
        memPutInt(info + VkRenderingAttachmentInfo.STYPE, VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO);
        memPutLong(info + VkRenderingAttachmentInfo.IMAGEVIEW, view);
        memPutInt(info + VkRenderingAttachmentInfo.IMAGELAYOUT, layout);
        memPutInt(info + VkRenderingAttachmentInfo.LOADOP,
            load ? VK_ATTACHMENT_LOAD_OP_LOAD : VK_ATTACHMENT_LOAD_OP_CLEAR);
        memPutInt(info + VkRenderingAttachmentInfo.STOREOP, VK_ATTACHMENT_STORE_OP_STORE);
    }

    /** The shaders' Occlusion struct at {@code address}: pyramid slot, depth size, pyramid levels. */
    private static void occlusion(long address, int pyramid, int width, int height, int mips) {
        memPutInt(address, pyramid);
        memPutInt(address + 4, width);
        memPutInt(address + 8, height);
        memPutInt(address + 12, mips);
    }

    /**
     * Writes the instance pass's rows: each view culled and its phase ({@code CulledView}), as commands, so each cull
     * of a frame reads its own. At most 64 KiB per update, as Vulkan allows.
     */
    private void writeRows(VkCommandBuffer commands, int[] views, int count) {
        for (int first = 0; first < count; first += ROWS_PER_UPDATE) {
            int n = Math.min(ROWS_PER_UPDATE, count - first);
            try (MemoryStack stack = stackPush()) {
                long data = stack.nmalloc(4, 8 * n);
                for (int i = 0; i < n; i++) {
                    int v = views[first + i];
                    memPutInt(data + 8L * i, v);
                    memPutInt(data + 8L * i + 4, phaseOf[v]);
                }
                nvkCmdUpdateBuffer(commands, rows.handle(), 8L * first, 8L * n, data);
            }
        }
    }

    /** A global memory barrier: writes in {@code srcStage} are visible to {@code dstAccess} in {@code dstStage}. */
    private static void barrier(VkCommandBuffer commands, long srcStage, long srcAccess, long dstStage,
        long dstAccess) {
        try (MemoryStack stack = stackPush()) {
            long barrier = stack.ncalloc(VkMemoryBarrier2.ALIGNOF, 1, VkMemoryBarrier2.SIZEOF);
            memPutInt(barrier + VkMemoryBarrier2.STYPE, VK_STRUCTURE_TYPE_MEMORY_BARRIER_2);
            memPutLong(barrier + VkMemoryBarrier2.SRCSTAGEMASK, srcStage);
            memPutLong(barrier + VkMemoryBarrier2.SRCACCESSMASK, srcAccess);
            memPutLong(barrier + VkMemoryBarrier2.DSTSTAGEMASK, dstStage);
            memPutLong(barrier + VkMemoryBarrier2.DSTACCESSMASK, dstAccess);
            long dependency = stack.ncalloc(VkDependencyInfo.ALIGNOF, 1, VkDependencyInfo.SIZEOF);
            memPutInt(dependency + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
            memPutInt(dependency + VkDependencyInfo.MEMORYBARRIERCOUNT, 1);
            memPutAddress(dependency + VkDependencyInfo.PMEMORYBARRIERS, barrier);
            nvkCmdPipelineBarrier2(commands, dependency);
        }
    }

    /**
     * How many meshlets the last finished frame found visible (on the indexed path, more than the draw limit means
     * some were dropped). Read it once the frame's commands have completed.
     */
    public int visibleMeshlets() {
        return visibleMeshlets(0);
    }

    /** Like {@link #visibleMeshlets()}, for view {@code index}. */
    public int visibleMeshlets(int index) {
        long o = 8L * lists(index);
        return drawCount.memory().get(U32, o) + drawCount.memory().get(U32, o + 4);
    }

    public Limits limits() {
        return limits;
    }

    /** The view buffer, for other passes drawing the same frame. */
    FrameBuffer viewBuffer() {
        return view;
    }

    /** View {@code index}'s address in the view buffer. */
    /** The world position along {@code axis} of view {@code index}'s camera, as last {@link #setView set}. */
    public double viewPosition(int index, int axis) {
        return viewPositions[3 * lists(index) + Objects.checkIndex(axis, 3)];
    }

    long viewAddress(int index) {
        return view.deviceAddress() + (long) VIEW_BYTES * lists(index);
    }

    /** The geometry pool whose meshes it draws, also for other passes shading the same instances. */
    public GeometryPool pool() {
        return pool;
    }

    /** The material table's address, for other passes shading the same instances. */
    long materialsAddress() {
        return materials.deviceAddress();
    }

    /** The instance buffer, for other passes drawing the same instances. */
    FrameBuffer instanceBuffer() {
        return instances;
    }

    /** How many instance ids, from 0, the renderer draws: holes included ({@link #setInstanceCount}). */
    public int instanceCount() {
        return instanceCount;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            instancesPass.close();
            cull.close();
            expand.close();
            draw.close();
            drawDepth.close();
            if (meshDraw != null) {
                meshDraw.close();
                meshDepth.close();
            }
            if (idsDraw != null) {
                idsDraw.close();
            }
            if (idsMesh != null) {
                idsMesh.close();
            }
            view.close();
            instances.close();
            for (GpuBuffer buffer : new GpuBuffer[] {chunks, rows, chunkCommand, lists, drawCount, visibility}) {
                buffer.close();
            }
            defaultMaterials.close();
            for (int v = 0; v < limits.views(); v++) {
                visible[v].close();
                frameIndices[v].close();
                expandCommand[v].close();
                drawCommand[v].close();
            }
        }
    }
}
