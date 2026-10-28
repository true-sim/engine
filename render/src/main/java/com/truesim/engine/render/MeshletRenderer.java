package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.EXTMeshShader.VK_PIPELINE_STAGE_TASK_SHADER_BIT_EXT;
import static org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
import static org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT;
import static org.lwjgl.vulkan.EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
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
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT;
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
 * visible one into chunks of 64 meshlets; the GPU then culls each chunk's meshlets (cluster LOD for the allowed screen
 * error, frustum, normal cone) and draws the visible ones, on one of two paths over the same data and shaders:
 *
 * <ul>
 *   <li>{@link Path#MESH_SHADERS}: a task workgroup per chunk culls and launches a mesh workgroup per visible meshlet,
 *       which emits its vertices and triangles; where the device has mesh shaders.</li>
 *   <li>{@link Path#INDEXED}: a compute workgroup per chunk culls into indexed indirect draws, drawn with one
 *       {@code vkCmdDrawIndexedIndirectCount}; on every device.</li>
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
        /** Task and mesh shaders. */
        MESH_SHADERS
    }

    /**
     * How much a frame holds: instances, visible meshlets on the indexed path, and chunks of 64 meshlets of visible
     * instances (at most 65,535: the guaranteed dispatch size).
     */
    public record Limits(int instances, int draws, int chunks) {

        public Limits {
            if (instances < 1 || draws < 1 || chunks < 1 || chunks > 65_535) {
                throw new IllegalArgumentException("Need room for instances, draws and 1 to 65,535 chunks: "
                    + instances + ", " + draws + ", " + chunks);
            }
        }
    }

    /** VK_PIPELINE_STAGE_2_TASK_SHADER_BIT_EXT: the same bit as the 32-bit stage. */
    private static final long TASK_SHADER_STAGE = VK_PIPELINE_STAGE_TASK_SHADER_BIT_EXT;
    static final int INSTANCE_BYTES = 48;
    private static final int VIEW_BYTES = 192;
    private static final int DRAW_BYTES = 20;
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final GeometryPool pool;
    private final Limits limits;
    private final Pipeline instancesPass;
    private final Pipeline cull;
    private final Pipeline draw;
    private final Pipeline meshDraw;
    private final GpuBuffer view;
    private final GpuBuffer instances;
    private final GpuBuffer chunks;
    private final GpuBuffer chunkCommand;
    private final GpuBuffer draws;
    private final GpuBuffer drawMeshlets;
    private final GpuBuffer drawCount;
    private Path path = Path.AUTO;
    private int instanceCount;
    private boolean closed;

    /** A renderer of {@code pool}'s meshes into {@code colorFormat} and {@code depthFormat} attachments. */
    public MeshletRenderer(VulkanDevice device, GeometryPool pool, Limits limits, int colorFormat, int depthFormat) {
        this.pool = pool;
        this.limits = Objects.requireNonNull(limits, "limits");
        try (ShaderModule instanceShader = ShaderModule.load(device, "shaders/meshlets/instances.spv");
             ShaderModule cullShader = ShaderModule.load(device, "shaders/meshlets/cull.spv");
             ShaderModule drawShader = ShaderModule.load(device, "shaders/meshlets/draw.spv")) {
            instancesPass = Pipeline.compute(device, instanceShader, "instancesMain", 56);
            cull = Pipeline.compute(device, cullShader, "cullMain", 72);
            draw = Pipeline.graphics(device)
                .stage(VK_SHADER_STAGE_VERTEX_BIT, drawShader, "vertexMain")
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT, drawShader, "fragmentMain")
                .color(colorFormat)
                .depth(depthFormat)
                .pushConstants(48)
                .build();
            if (device.meshShaders()) {
                try (ShaderModule meshShader = ShaderModule.load(device, "shaders/meshlets/mesh.spv")) {
                    meshDraw = Pipeline.graphics(device)
                        .stage(VK_SHADER_STAGE_TASK_BIT_EXT, meshShader, "taskMain")
                        .stage(VK_SHADER_STAGE_MESH_BIT_EXT, meshShader, "meshMain")
                        .stage(VK_SHADER_STAGE_FRAGMENT_BIT, drawShader, "fragmentMain") // the same shading
                        .color(colorFormat)
                        .depth(depthFormat)
                        .pushConstants(88)
                        .build();
                }
            } else {
                meshDraw = null;
            }
        }
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        view = GpuBuffer.hostVisible(device, VIEW_BYTES, storage);
        instances = GpuBuffer.hostVisible(device, (long) INSTANCE_BYTES * limits.instances(), storage);
        chunks = GpuBuffer.deviceLocal(device, 8L * limits.chunks(), storage);
        chunkCommand = GpuBuffer.deviceLocal(device, 16,
            storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        draws = GpuBuffer.deviceLocal(device, (long) DRAW_BYTES * limits.draws(),
            storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
        drawMeshlets = GpuBuffer.deviceLocal(device, 4L * limits.draws(), storage);
        drawCount = GpuBuffer.hostVisible(device, 4,
            storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
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
        MemorySegment m = view.memory();
        float[] matrix = camera.viewProjection();
        for (int i = 0; i < 16; i++) {
            m.set(F32, 4L * i, matrix[i]);
        }
        float[] planes = camera.planes();
        for (int i = 0; i < 24; i++) {
            m.set(F32, 64 + 4L * i, planes[i]);
        }
        m.set(F32, 160, camera.projectionScale());
        m.set(F32, 164, errorPixels);
        m.set(U32, 172, limits.draws());
        m.set(U32, 176, mode);
    }

    /**
     * Records the frame: the instance pass, culling and drawing into {@code colorView} and {@code depthView}
     * ({@code width} by {@code height}, in color and depth attachment layouts), cleared to ({@code r}, {@code g},
     * {@code b}) and depth 0. Allocates nothing.
     */
    public void record(VkCommandBuffer commands, long colorView, long depthView, int width, int height, float r,
        float g, float b) {
        boolean meshShading = path() == Path.MESH_SHADERS;
        view.memory().set(U32, 168, instanceCount);
        try (MemoryStack stack = stackPush()) {
            vkCmdFillBuffer(commands, drawCount.handle(), 0, 4, 0);
            long reset = stack.nmalloc(4, 16); // the chunk command: no chunks yet, a dispatch of x by 1 by 1
            memPutInt(reset, 0);
            memPutInt(reset + 4, 1);
            memPutInt(reset + 8, 1);
            memPutInt(reset + 12, 0);
            nvkCmdUpdateBuffer(commands, chunkCommand.handle(), 0, 16, reset);
        }
        barrier(commands, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, 56);
            memPutLong(push, view.deviceAddress());
            memPutLong(push + 8, pool.meshTable().deviceAddress());
            memPutLong(push + 16, instances.deviceAddress());
            memPutLong(push + 24, chunks.deviceAddress());
            memPutLong(push + 32, chunkCommand.deviceAddress());
            memPutLong(push + 40, pool.levels().deviceAddress());
            memPutInt(push + 48, limits.chunks());
            instancesPass.bind(commands);
            instancesPass.push(commands, push, 52);
            vkCmdDispatch(commands, (instanceCount + 63) / 64, 1, 1);
        }
        // The chunks feed an indirect command and the next shaders: the cull pass or the task shaders.
        barrier(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                | (meshShading ? TASK_SHADER_STAGE : 0),
            VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        if (!meshShading) {
            try (MemoryStack stack = stackPush()) {
                long push = stack.nmalloc(8, 72);
                memPutLong(push, view.deviceAddress());
                memPutLong(push + 8, pool.meshTable().deviceAddress());
                memPutLong(push + 16, instances.deviceAddress());
                memPutLong(push + 24, chunks.deviceAddress());
                memPutLong(push + 32, pool.meshlets().deviceAddress());
                memPutLong(push + 40, pool.clusterLod().deviceAddress());
                memPutLong(push + 48, draws.deviceAddress());
                memPutLong(push + 56, drawMeshlets.deviceAddress());
                memPutLong(push + 64, drawCount.deviceAddress());
                cull.bind(commands);
                cull.push(commands, push, 72);
                vkCmdDispatchIndirect(commands, chunkCommand.handle(), 0);
            }
            barrier(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT,
                VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        }
        try (MemoryStack stack = stackPush()) {
            beginRendering(commands, stack, colorView, depthView, width, height, r, g, b);
            if (meshShading) {
                long push = stack.nmalloc(8, 88);
                memPutLong(push, view.deviceAddress());
                memPutLong(push + 8, pool.meshTable().deviceAddress());
                memPutLong(push + 16, instances.deviceAddress());
                memPutLong(push + 24, chunks.deviceAddress());
                memPutLong(push + 32, pool.meshlets().deviceAddress());
                memPutLong(push + 40, pool.clusterLod().deviceAddress());
                memPutLong(push + 48, pool.meshletVertices().deviceAddress());
                memPutLong(push + 56, pool.meshletTriangles().deviceAddress());
                memPutLong(push + 64, pool.positions().deviceAddress());
                memPutLong(push + 72, pool.attributes().deviceAddress());
                memPutLong(push + 80, drawCount.deviceAddress());
                meshDraw.bind(commands);
                setViewport(commands, stack, width, height);
                meshDraw.push(commands, push, 88);
                vkCmdDrawMeshTasksIndirectEXT(commands, chunkCommand.handle(), 0, 1, 12);
            } else {
                long push = stack.nmalloc(8, 48);
                memPutLong(push, view.deviceAddress());
                memPutLong(push + 8, pool.meshTable().deviceAddress());
                memPutLong(push + 16, instances.deviceAddress());
                memPutLong(push + 24, pool.positions().deviceAddress());
                memPutLong(push + 32, pool.attributes().deviceAddress());
                memPutLong(push + 40, drawMeshlets.deviceAddress());
                draw.bind(commands);
                setViewport(commands, stack, width, height);
                draw.push(commands, push, 48);
                vkCmdBindIndexBuffer(commands, pool.indices().handle(), 0, VK_INDEX_TYPE_UINT32);
                vkCmdDrawIndexedIndirectCount(commands, draws.handle(), 0, drawCount.handle(), 0, limits.draws(),
                    DRAW_BYTES);
            }
            vkCmdEndRendering(commands);
        }
    }

    private static void beginRendering(VkCommandBuffer commands, MemoryStack stack, long colorView, long depthView,
        int width, int height, float r, float g, float b) {
        long color = stack.ncalloc(VkRenderingAttachmentInfo.ALIGNOF, 1, VkRenderingAttachmentInfo.SIZEOF);
        attachment(color, colorView, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        long clear = color + VkRenderingAttachmentInfo.CLEARVALUE;
        memPutFloat(clear, r);
        memPutFloat(clear + 4, g);
        memPutFloat(clear + 8, b);
        memPutFloat(clear + 12, 1);
        long depth = stack.ncalloc(VkRenderingAttachmentInfo.ALIGNOF, 1, VkRenderingAttachmentInfo.SIZEOF);
        attachment(depth, depthView, VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL); // cleared to 0: reverse depth
        long rendering = stack.ncalloc(VkRenderingInfo.ALIGNOF, 1, VkRenderingInfo.SIZEOF);
        memPutInt(rendering + VkRenderingInfo.STYPE, VK_STRUCTURE_TYPE_RENDERING_INFO);
        memPutInt(rendering + VkRenderingInfo.RENDERAREA + VkRect2D.EXTENT, width);
        memPutInt(rendering + VkRenderingInfo.RENDERAREA + VkRect2D.EXTENT + 4, height);
        memPutInt(rendering + VkRenderingInfo.LAYERCOUNT, 1);
        memPutInt(rendering + VkRenderingInfo.COLORATTACHMENTCOUNT, 1);
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

    private static void attachment(long info, long view, int layout) {
        memPutInt(info + VkRenderingAttachmentInfo.STYPE, VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO);
        memPutLong(info + VkRenderingAttachmentInfo.IMAGEVIEW, view);
        memPutInt(info + VkRenderingAttachmentInfo.IMAGELAYOUT, layout);
        memPutInt(info + VkRenderingAttachmentInfo.LOADOP, VK_ATTACHMENT_LOAD_OP_CLEAR);
        memPutInt(info + VkRenderingAttachmentInfo.STOREOP, VK_ATTACHMENT_STORE_OP_STORE);
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
        return drawCount.memory().get(U32, 0);
    }

    public Limits limits() {
        return limits;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            instancesPass.close();
            cull.close();
            draw.close();
            if (meshDraw != null) {
                meshDraw.close();
            }
            for (GpuBuffer buffer : new GpuBuffer[] {view, instances, chunks, chunkCommand, draws, drawMeshlets,
                drawCount}) {
                buffer.close();
            }
        }
    }
}
