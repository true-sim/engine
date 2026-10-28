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
 * renderer.setInstance(0, car, x, y, z, qx, qy, qz, qw, 1, 1, 1);   // camera-relative position
 * renderer.setInstanceCount(1);
 * renderer.setView(camera.set(...), 1.0f, MeshletRenderer.MODE_LIT); // 1 pixel of error allowed
 * renderer.record(commands, colorView, depthView, width, height, 0, 0, 0);
 * }</pre>
 *
 * <p>The CPU writes only instances and the view: its cost does not depend on how many meshlets there are. Instance and
 * view data are read when the commands run, so change them only once the frame that used them has finished (one
 * renderer per frame in flight). Recording allocates nothing.
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
     * How much a frame holds: instances, visible meshlets (at most 4,194,304: the guaranteed mesh dispatch size),
     * chunks of 64 meshlets of visible instances (at most 65,535: the guaranteed dispatch size) and, on the indexed
     * path, visible triangles (12 bytes each in the frame's index buffer). Meshlets beyond these are not drawn.
     */
    public record Limits(int instances, int draws, int chunks, int triangles) {

        public Limits {
            if (instances < 1 || draws < 1 || draws > 1 << 22 || chunks < 1 || chunks > 65_535 || triangles < 1) {
                throw new IllegalArgumentException("Need room for instances, 1 to 4,194,304 draws, triangles and 1 to "
                    + "65,535 chunks: " + instances + ", " + draws + ", " + chunks + ", " + triangles);
            }
        }
    }

    /** VK_PIPELINE_STAGE_2_MESH_SHADER_BIT_EXT: the same bit as the 32-bit stage. */
    private static final long MESH_SHADER_STAGE = VK_PIPELINE_STAGE_MESH_SHADER_BIT_EXT;
    static final int INSTANCE_BYTES = 48;
    private static final int INSTANCE_PUSH = 84;
    private static final int CULL_PUSH = 104;
    private static final int PHASE_ALL = 0;
    private static final int PHASE_EARLY = 1;
    private static final int PHASE_LATE = 2;
    private static final int VIEW_BYTES = 192;
    /** Views a renderer holds: e.g. the camera and a shadow map's light. */
    public static final int VIEWS = 4;
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
    private final GpuBuffer view;
    private final GpuBuffer instances;
    private final GpuBuffer chunks;
    private final GpuBuffer chunkCommand;
    private final GpuBuffer visible;
    private final GpuBuffer frameIndices;
    private final GpuBuffer expandCommand;
    private final GpuBuffer drawCommand;
    private final GpuBuffer drawCount;
    private final GpuBuffer visibility;
    private Path path = Path.AUTO;
    private int instanceCount;
    private boolean closed;

    /** A renderer of {@code pool}'s meshes into {@code colorFormat} and {@code depthFormat} attachments. */
    public MeshletRenderer(VulkanDevice device, GeometryPool pool, Limits limits, int colorFormat, int depthFormat) {
        this.pool = pool;
        this.bindless = device.bindless();
        this.limits = Objects.requireNonNull(limits, "limits");
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
                .pushConstants(64)
                .build();
            drawDepth = Pipeline.graphics(device) // depth only: shadow maps
                .stage(VK_SHADER_STAGE_VERTEX_BIT, drawShader, "vertexMain")
                .depth(depthFormat)
                .pushConstants(64)
                .build();
            if (device.meshShaders()) {
                try (ShaderModule meshShader = ShaderModule.load(device, "shaders/meshlets/mesh.spv")) {
                    meshDraw = Pipeline.graphics(device)
                        .stage(VK_SHADER_STAGE_MESH_BIT_EXT, meshShader, "meshMain")
                        .stage(VK_SHADER_STAGE_FRAGMENT_BIT, drawShader, "fragmentMain") // the same shading
                        .color(colorFormat)
                        .depth(depthFormat)
                        .pushConstants(80)
                        .build();
                    meshDepth = Pipeline.graphics(device)
                        .stage(VK_SHADER_STAGE_MESH_BIT_EXT, meshShader, "meshMain")
                        .depth(depthFormat)
                        .pushConstants(80)
                        .build();
                }
            } else {
                meshDraw = null;
                meshDepth = null;
            }
        }
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        view = GpuBuffer.hostVisible(device, (long) VIEW_BYTES * VIEWS, storage);
        instances = GpuBuffer.hostVisible(device, (long) INSTANCE_BYTES * limits.instances(), storage);
        chunks = GpuBuffer.deviceLocal(device, 8L * limits.chunks(), storage);
        chunkCommand = GpuBuffer.deviceLocal(device, 16,
            storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        int commandUsage = storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        visible = GpuBuffer.deviceLocal(device, 16L * limits.draws(), storage);
        frameIndices = GpuBuffer.deviceLocal(device, 12L * limits.triangles(),
            storage | VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        expandCommand = GpuBuffer.deviceLocal(device, 16, commandUsage);
        drawCommand = GpuBuffer.deviceLocal(device, 24, commandUsage);
        drawCount = GpuBuffer.hostVisible(device, 8L * VIEWS, // per view: the early (or only) and late phases
            storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        visibility = GpuBuffer.deviceLocal(device, 4L * limits.instances(), storage | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
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
     * Sets instance {@code index}: mesh {@code mesh} of the pool at camera-relative position ({@code x}, {@code y},
     * {@code z}), rotated by quaternion ({@code qx}, {@code qy}, {@code qz}, {@code qw}) and scaled. Allocates nothing.
     */
    public void setInstance(int index, int mesh, float x, float y, float z, float qx, float qy, float qz, float qw,
        float sx, float sy, float sz) {
        if (index < 0 || index >= limits.instances() || mesh < 0 || mesh >= pool.meshCount()) {
            throw new IllegalArgumentException("No instance " + index + " or mesh " + mesh);
        }
        MemorySegment m = instances.memory();
        long o = (long) INSTANCE_BYTES * index;
        m.set(F32, o, qx);
        m.set(F32, o + 4, qy);
        m.set(F32, o + 8, qz);
        m.set(F32, o + 12, qw);
        m.set(F32, o + 16, x);
        m.set(F32, o + 20, y);
        m.set(F32, o + 24, z);
        m.set(U32, o + 28, mesh);
        m.set(F32, o + 32, sx);
        m.set(F32, o + 36, sy);
        m.set(F32, o + 40, sz);
    }

    /** How many instances, from 0, are drawn. */
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
     * Sets view {@code index} (0 to {@link #VIEWS} - 1; 0 is the one {@link #record} draws), e.g. a light's
     * orthographic view for {@link #recordDepth}. Allocates nothing.
     */
    public void setView(int index, Camera camera, float errorPixels, int mode) {
        Objects.checkIndex(index, VIEWS);
        long o = (long) VIEW_BYTES * index;
        MemorySegment m = view.memory();
        float[] matrix = camera.viewProjection();
        for (int i = 0; i < 16; i++) {
            m.set(F32, o + 4L * i, matrix[i]);
        }
        float[] planes = camera.planes();
        for (int i = 0; i < 24; i++) {
            m.set(F32, o + 64 + 4L * i, planes[i]);
        }
        m.set(F32, o + 160, camera.projectionScale());
        m.set(F32, o + 164, errorPixels);
        m.set(U32, o + 172, limits.draws());
        m.set(U32, o + 176, mode);
        m.set(U32, o + 180, camera.orthographic() ? 1 : 0);
    }

    /**
     * Records the frame: the instance pass, culling and drawing into {@code colorView} and {@code depthView}
     * ({@code width} by {@code height}, in color and depth attachment layouts), cleared to ({@code r}, {@code g},
     * {@code b}) and depth 0. Allocates nothing.
     */
    public void record(VkCommandBuffer commands, long colorView, long depthView, int width, int height, float r,
        float g, float b) {
        record(commands, colorView, depthView, width, height, r, g, b, PHASE_ALL, 0, 0, 0);
    }

    /**
     * Draws view {@code index}'s depth only (no color, no shading) into {@code depthView}, cleared to 0: e.g. a
     * shadow map from a light's orthographic view ({@link #setView(int, Camera, float, int)}). Allocates nothing.
     */
    public void recordDepth(VkCommandBuffer commands, long depthView, int width, int height, int index) {
        Objects.checkIndex(index, VIEWS);
        record(commands, 0, depthView, width, height, 0, 0, 0, PHASE_ALL, 0, 0, index);
    }

    /**
     * The early phase of two-phase occlusion culling: like {@link #record}, but only the instances visible the last
     * time {@link #recordLate} looked. Then build a depth pyramid of the depth (e.g. engine-render's
     * {@code DepthPyramid}) and record the late phase. Instances are tracked by index: keep an object's index from frame
     * to frame. Allocates nothing.
     */
    public void recordEarly(VkCommandBuffer commands, long colorView, long depthView, int width, int height, float r,
        float g, float b) {
        record(commands, colorView, depthView, width, height, r, g, b, PHASE_EARLY, 0, 0, 0);
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
        record(commands, colorView, depthView, width, height, 0, 0, 0, PHASE_LATE, pyramid, mips, 0);
    }

    private void record(VkCommandBuffer commands, long colorView, long depthView, int width, int height, float r,
        float g, float b, int phase, int pyramid, int mips, int viewIndex) {
        boolean meshShading = path() == Path.MESH_SHADERS;
        boolean late = phase == PHASE_LATE;
        bindless.bind(commands); // the late phase samples the depth pyramid through it
        boolean depthOnly = colorView == 0;
        long viewAddress = view.deviceAddress() + (long) VIEW_BYTES * viewIndex;
        view.memory().set(U32, (long) VIEW_BYTES * viewIndex + 168, instanceCount);
        // What came before (the early phase, the frame before) used the buffers this phase resets, and the visibility.
        barrier(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        long count = drawCount.deviceAddress() + 8L * viewIndex + (late ? 4 : 0);
        try (MemoryStack stack = stackPush()) {
            if (!late) {
                vkCmdFillBuffer(commands, drawCount.handle(), 8L * viewIndex, 8, 0);
            }
            long reset = stack.nmalloc(4, 16); // the chunk command: no chunks yet, a dispatch of x by 1 by 1
            memPutInt(reset, 0);
            memPutInt(reset + 4, 1);
            memPutInt(reset + 8, 1);
            memPutInt(reset + 12, 0);
            nvkCmdUpdateBuffer(commands, chunkCommand.handle(), 0, 16, reset);
            nvkCmdUpdateBuffer(commands, expandCommand.handle(), 0, 16, reset); // no meshlets: x by 1 by 1
            long draw = stack.ncalloc(4, 6, 4); // no indices, one instance; word 5 counts reserved indices
            memPutInt(draw + 4, 1);
            nvkCmdUpdateBuffer(commands, drawCommand.handle(), 0, 24, draw);
        }
        barrier(commands, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, INSTANCE_PUSH);
            memPutLong(push, viewAddress);
            memPutLong(push + 8, pool.meshTable().deviceAddress());
            memPutLong(push + 16, instances.deviceAddress());
            memPutLong(push + 24, chunks.deviceAddress());
            memPutLong(push + 32, chunkCommand.deviceAddress());
            memPutLong(push + 40, pool.levels().deviceAddress());
            memPutInt(push + 48, limits.chunks());
            memPutLong(push + 56, visibility.deviceAddress());
            occlusion(push + 64, phase, pyramid, width, height, mips);
            instancesPass.bind(commands);
            instancesPass.push(commands, push, INSTANCE_PUSH);
            vkCmdDispatch(commands, (instanceCount + 63) / 64, 1, 1);
        }
        // The chunks feed the cull pass's indirect dispatch and the pass itself.
        barrier(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, CULL_PUSH);
            memPutLong(push, viewAddress);
            memPutLong(push + 8, pool.meshTable().deviceAddress());
            memPutLong(push + 16, instances.deviceAddress());
            memPutLong(push + 24, chunks.deviceAddress());
            memPutLong(push + 32, pool.meshlets().deviceAddress());
            memPutLong(push + 40, pool.clusterLod().deviceAddress());
            memPutLong(push + 48, visible.deviceAddress());
            memPutLong(push + 56, expandCommand.deviceAddress());
            memPutLong(push + 64, drawCommand.deviceAddress());
            memPutLong(push + 72, count);
            // mesh shaders read no frame index buffer: every listed meshlet fits
            memPutInt(push + 80, meshShading ? -1 : (int) Math.min(Integer.MAX_VALUE, 3L * limits.triangles()));
            occlusion(push + 84, phase, pyramid, width, height, mips);
            cull.bind(commands);
            cull.push(commands, push, CULL_PUSH);
            vkCmdDispatchIndirect(commands, chunkCommand.handle(), 0);
        }
        if (meshShading) { // the list and its dispatch feed the mesh shaders
            barrier(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | MESH_SHADER_STAGE,
                VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        } else {
            barrier(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
            try (MemoryStack stack = stackPush()) {
                long push = stack.nmalloc(8, 64);
                memPutLong(push, viewAddress);
                memPutLong(push + 8, visible.deviceAddress());
                memPutLong(push + 16, count);
                memPutLong(push + 24, pool.meshlets().deviceAddress());
                memPutLong(push + 32, pool.meshTable().deviceAddress());
                memPutLong(push + 40, instances.deviceAddress());
                memPutLong(push + 48, pool.meshletTriangles().deviceAddress());
                memPutLong(push + 56, frameIndices.deviceAddress());
                expand.bind(commands);
                expand.push(commands, push, 64);
                vkCmdDispatchIndirect(commands, expandCommand.handle(), 0);
            }
            barrier(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_INDEX_INPUT_BIT
                    | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT,
                VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_INDEX_READ_BIT
                    | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        }
        try (MemoryStack stack = stackPush()) {
            beginRendering(commands, stack, colorView, depthView, width, height, r, g, b, late);
            if (meshShading) {
                long push = stack.nmalloc(8, 80);
                memPutLong(push, viewAddress);
                memPutLong(push + 8, pool.meshTable().deviceAddress());
                memPutLong(push + 16, instances.deviceAddress());
                memPutLong(push + 24, visible.deviceAddress());
                memPutLong(push + 32, count);
                memPutLong(push + 40, pool.meshlets().deviceAddress());
                memPutLong(push + 48, pool.meshletVertices().deviceAddress());
                memPutLong(push + 56, pool.meshletTriangles().deviceAddress());
                memPutLong(push + 64, pool.positions().deviceAddress());
                memPutLong(push + 72, pool.attributes().deviceAddress());
                Pipeline mesh = depthOnly ? meshDepth : meshDraw;
                mesh.bind(commands);
                setViewport(commands, stack, width, height);
                mesh.push(commands, push, 80);
                vkCmdDrawMeshTasksIndirectEXT(commands, expandCommand.handle(), 0, 1, 12);
            } else {
                long push = stack.nmalloc(8, 64);
                memPutLong(push, viewAddress);
                memPutLong(push + 8, pool.meshTable().deviceAddress());
                memPutLong(push + 16, instances.deviceAddress());
                memPutLong(push + 24, pool.positions().deviceAddress());
                memPutLong(push + 32, pool.attributes().deviceAddress());
                memPutLong(push + 40, visible.deviceAddress());
                memPutLong(push + 48, pool.meshlets().deviceAddress());
                memPutLong(push + 56, pool.meshletVertices().deviceAddress());
                Pipeline indexed = depthOnly ? drawDepth : draw;
                indexed.bind(commands);
                setViewport(commands, stack, width, height);
                indexed.push(commands, push, 64);
                vkCmdBindIndexBuffer(commands, frameIndices.handle(), 0, VK_INDEX_TYPE_UINT32);
                vkCmdDrawIndexedIndirect(commands, drawCommand.handle(), 0, 1, 20);
            }
            vkCmdEndRendering(commands);
        }
    }

    /** Begins rendering into the attachments, cleared or, with {@code load}, as they are. */
    private static void beginRendering(VkCommandBuffer commands, MemoryStack stack, long colorView, long depthView,
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

    private static void setViewport(VkCommandBuffer commands, MemoryStack stack, int width, int height) {
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

    /** The shaders' Occlusion struct at {@code address}: phase, pyramid slot, depth size, pyramid levels. */
    private static void occlusion(long address, int phase, int pyramid, int width, int height, int mips) {
        memPutInt(address, phase);
        memPutInt(address + 4, pyramid);
        memPutInt(address + 8, width);
        memPutInt(address + 12, height);
        memPutInt(address + 16, mips);
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
        long o = 8L * Objects.checkIndex(index, VIEWS);
        return drawCount.memory().get(U32, o) + drawCount.memory().get(U32, o + 4);
    }

    public Limits limits() {
        return limits;
    }

    /** The view buffer, for other passes drawing the same frame. */
    GpuBuffer viewBuffer() {
        return view;
    }

    /** The instance buffer, for other passes drawing the same instances. */
    GpuBuffer instanceBuffer() {
        return instances;
    }

    int instanceCount() {
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
            for (GpuBuffer buffer : new GpuBuffer[] {view, instances, chunks, chunkCommand, visible, frameIndices,
                expandCommand, drawCommand,
                drawCount, visibility}) {
                buffer.close();
            }
        }
    }
}
