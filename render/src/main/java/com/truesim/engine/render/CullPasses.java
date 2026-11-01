package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.EXTMeshShader.VK_PIPELINE_STAGE_MESH_SHADER_BIT_EXT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_INDEX_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_INDEX_INPUT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalBuffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.render.layout.CommonConstants;
import com.truesim.engine.render.layout.CullPush;
import com.truesim.engine.render.layout.ExpandPush;
import com.truesim.engine.render.layout.InstancePush;
import com.truesim.engine.render.layout.ViewLayout;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.Bindless;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * A {@link MeshletRenderer}'s culling: the instance pass splitting each instance visible to a view into chunks of 64
 * meshlets, the cull pass listing each chunk's visible meshlets in its view's lists and, on the indexed path, each
 * view's expansion of its lists into indices. The chunks are shared by the views culled together and grow between
 * frames to what frames wanted. Culling allocates nothing.
 */
@AllowAllocation("created rarely")
final class CullPasses implements AutoCloseable {

    /** The most chunks the buffer grows to: 16 bytes each. */
    static final int CHUNK_LIMIT = 1 << 24;
    /** VK_PIPELINE_STAGE_2_MESH_SHADER_BIT_EXT: the same bit as the 32-bit stage. */
    private static final long MESH_SHADER_STAGE = VK_PIPELINE_STAGE_MESH_SHADER_BIT_EXT;
    private static final int BINS = MeshletRenderer.BINS;
    private static final int PHASE_ALL = CommonConstants.PHASE_ALL;
    private static final int PHASE_EARLY = CommonConstants.PHASE_EARLY;
    private static final int PHASE_LATE = CommonConstants.PHASE_LATE;
    /** Rows of one instance dispatch: the guaranteed workgroup count. */
    private static final int MAX_ROWS = 65_535;
    /** Rows one buffer update writes: 8 bytes each, a few KiB of the stack. */
    private static final int ROWS_PER_UPDATE = 512;
    // The shaders' Occlusion struct, the same in the instance and cull pushes: its fields from its start.
    private static final int OCCLUSION_WIDTH = InstancePush.OCCLUSION_DEPTH_WIDTH - InstancePush.OCCLUSION;
    private static final int OCCLUSION_HEIGHT = InstancePush.OCCLUSION_DEPTH_HEIGHT - InstancePush.OCCLUSION;
    private static final int OCCLUSION_MIPS = InstancePush.OCCLUSION_MIPS - InstancePush.OCCLUSION;

    private final VulkanDevice device;
    private final GpuScene scene;
    private final GeometryPool pool;
    private final Bindless bindless;
    private final FrameSlots slots;
    private final RenderViews views;
    private final Retiring retiring;
    private final Runnable grow = this::grow;
    private final Pipeline instancesPass;
    private final Pipeline cull;
    private final Pipeline expand;
    private int maxChunks;
    private GpuBuffer chunks; // grown as frames want more (grow)
    private final GpuBuffer chunkCommand;
    // The culling's buffers as imported buffers of the frame graph, which the culling passes declare.
    private final ExternalBuffer chunksImport;
    private final ExternalBuffer chunkCommandImport;
    private boolean closed;

    /** Culling of {@code scene} into {@code views}, with room for {@code chunks} chunks to start with. */
    CullPasses(VulkanDevice device, GpuScene scene, FrameSlots slots, RenderViews views, Retiring retiring,
        int chunks) {
        this.device = device;
        this.scene = scene;
        this.pool = scene.pool();
        this.bindless = device.bindless();
        this.slots = slots;
        this.views = views;
        this.retiring = retiring;
        this.maxChunks = chunks;
        try (ShaderModule instanceShader = ShaderModule.load(device, "shaders/meshlets/instances.spv");
             ShaderModule cullShader = ShaderModule.load(device, "shaders/meshlets/cull.spv");
             ShaderModule expandShader = ShaderModule.load(device, "shaders/meshlets/expand.spv")) {
            instancesPass = Pipeline.compute(device, instanceShader, "instancesMain", InstancePush.BYTES);
            cull = Pipeline.compute(device, cullShader, "cullMain", CullPush.BYTES);
            expand = Pipeline.compute(device, expandShader, "expandMain", ExpandPush.BYTES);
        }
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        this.chunks = GpuBuffer.deviceLocal(device, 16L * chunks, storage);
        // The cull dispatch (x, y, z), the chunks wanted this frame, the end of those written, and the most a frame
        // ever wanted: never reset, read by the CPU as frames end to grow the chunks.
        chunkCommand = GpuBuffer.hostVisible(device, 24,
            storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        chunkCommand.memory().fill((byte) 0);
        chunksImport = new ExternalBuffer("meshlet chunks", this.chunks);
        chunkCommandImport = new ExternalBuffer("chunk command", chunkCommand);
        slots.onEnd(grow);
    }

    /** The most chunks of 64 meshlets a frame wanted so far: what the views culled together needed. */
    int wantedChunks() {
        return chunkCommand.memory().get(java.lang.foreign.ValueLayout.JAVA_INT, 20);
    }

    /** How many chunks there is room for now. */
    int capacity() {
        return maxChunks;
    }

    /**
     * As a frame ends: room for the chunks frames wanted, half again for growth, when a frame wanted more than there
     * was. The old buffer goes once no frame reads it; a graph rebuilds at its next frame for the new size.
     */
    @AllowAllocation("growing, rarely")
    private void grow() {
        int wanted = wantedChunks();
        if (wanted <= maxChunks || maxChunks == CHUNK_LIMIT || closed) {
            return;
        }
        int grown = (int) Math.min(CHUNK_LIMIT, Math.max(2L * maxChunks, wanted + wanted / 2L));
        retiring.add(chunks);
        chunks = GpuBuffer.deviceLocal(device, 16L * grown,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
        chunksImport.set(chunks);
        maxChunks = grown;
    }

    /**
     * Declares that {@code pass} culls: it writes the chunks and rows (transfers, then compute) and reads the chunk
     * command it writes for its indirect dispatch.
     */
    void declare(GraphBuilder g, GraphBuilder.PassBuilder pass) {
        Buffer command = g.importBuffer(chunkCommandImport);
        Buffer culledRows = g.importBuffer(views.rowsImport());
        pass.write(g.importBuffer(chunksImport), BufferAccess.STORAGE_WRITE)
            .write(command, BufferAccess.TRANSFER_DST)
            .write(command, BufferAccess.STORAGE_WRITE)
            .read(command, BufferAccess.INDIRECT)
            .write(culledRows, BufferAccess.TRANSFER_DST)
            .read(culledRows, BufferAccess.STORAGE_READ);
    }

    /** View {@code i}'s phase in a cull: late without {@code early}, else early or all. */
    private static int phase(boolean[] early, int i) {
        return early == null ? PHASE_LATE : early[i] ? PHASE_EARLY : PHASE_ALL;
    }

    /**
     * Culls {@code views[0]} to {@code views[count - 1]}, each in its phase ({@link #phase}; without {@code early} the
     * late one, against {@code pyramid}): resets their lists, then the instance pass (a row per view), the cull pass
     * over all their chunks and, unless {@code meshShading}, each view's expansion. With {@code declared} a graph
     * orders what the build declared: no barriers of its own for those.
     */
    void cull(VkCommandBuffer commands, RenderView[] views, boolean[] early, int count, int pyramid, int width,
        int height, int mips, boolean declared, boolean meshShading) {
        retiring.release(); // culls of one frame may run on several threads: it locks
        scene.upload(commands);
        for (int v = 0; v < count; v++) {
            views[v].refresh(); // from where their cameras are now
        }
        // Declared to a graph that orders everything it touches: the indexed path's index buffers too, where the build
        // declared them and the views have them (else, e.g. the path changed since, its own barriers).
        boolean ordered = declared && (meshShading || indicesOrdered(views, count));
        int instanceCount = scene.highWater();
        bindless.bind(commands); // the late phase samples the depth pyramid through it
        // What came before (the early phase, the frame before) used the buffers this resets, and the visibility.
        if (!ordered) {
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        }
        try (MemoryStack stack = stackPush()) {
            long reset = stack.nmalloc(4, 20); // the chunk command: no chunks yet, a dispatch of x by 1 by 1
            memPutInt(reset, 0);
            memPutInt(reset + 4, 1);
            memPutInt(reset + 8, 1);
            memPutInt(reset + 12, 0);
            memPutInt(reset + 16, 0);
            Commands.updateBuffer(commands, chunkCommand.handle(), 0, 20, reset); // the most ever wanted stays
            long dispatches = stack.nmalloc(4, 16 * BINS); // per raster bin: no meshlets, a dispatch of x by 1 by 1
            long draws = stack.ncalloc(4, 6 * BINS, 4); // per bin: no indices, one instance, from the bin's first
            for (int bin = 0; bin < BINS; bin++) {
                memPutInt(dispatches + 16L * bin, 0);
                memPutInt(dispatches + 16L * bin + 4, 1);
                memPutInt(dispatches + 16L * bin + 8, 1);
                memPutInt(dispatches + 16L * bin + 12, 0);
                memPutInt(draws + 24L * bin + 4, 1);
            }
            for (int i = 0; i < count; i++) {
                RenderView v = views[i];
                v.putInt(ViewLayout.INSTANCE_COUNT, instanceCount);
                // mesh shaders read no index buffer: every listed meshlet fits
                long indices = 3L * v.options().triangles();
                long binIndices = 3L * v.options().binTriangles();
                v.putInt(ViewLayout.MAX_INDICES, meshShading ? -1 : (int) indices);
                v.putInt(ViewLayout.BIN_INDICES, meshShading ? -1 : (int) binIndices);
                for (int bin = 1; bin < BINS; bin++) {
                    memPutInt(draws + 24L * bin + 8, (int) (indices + (bin - 1) * binIndices)); // the bin's first index
                }
                if (early != null) {
                    Commands.fillBuffer(commands, v.drawCount().handle(), 0, VK_WHOLE_SIZE, 0); // counts, indices wanted
                }
                v.clearGrownVisibility(commands);
                // The late phase keeps the blended bin's commands: its lists are appended to (visibleCountWord).
                int resetBins = early != null ? BINS : MeshletRenderer.BIN_BLENDED;
                Commands.updateBuffer(commands, v.expandCommand().handle(), 0, 16 * resetBins, dispatches);
                Commands.updateBuffer(commands, v.drawCommand().handle(), 0, 24 * resetBins, draws);
            }
        }
        writeRows(commands, views, early, count);
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, InstancePush.BYTES);
            memPutLong(push + InstancePush.VIEWS, this.views.constants().deviceAddress());
            memPutLong(push + InstancePush.MESHES, pool.meshTable().deviceAddress());
            memPutLong(push + InstancePush.INSTANCES, scene.instanceAddress());
            memPutLong(push + InstancePush.CHUNKS, chunks.deviceAddress());
            memPutLong(push + InstancePush.CHUNK_COMMAND, chunkCommand.deviceAddress());
            memPutLong(push + InstancePush.LEVELS, pool.levels().deviceAddress());
            memPutInt(push + InstancePush.MAX_CHUNKS, maxChunks);
            occlusion(push + InstancePush.OCCLUSION, pyramid, width, height, mips);
            memPutLong(push + InstancePush.ROWS, this.views.rows().deviceAddress());
            instancesPass.bind(commands);
            // A row of workgroups per view, at most 65,535 rows a dispatch (the guaranteed size).
            for (int first = 0; first < count; first += MAX_ROWS) {
                memPutInt(push + InstancePush.FIRST_ROW, first);
                instancesPass.push(commands, push, InstancePush.BYTES);
                Commands.dispatch(commands, (instanceCount + 63) / 64, Math.min(MAX_ROWS, count - first), 1);
            }
        }
        // The chunks feed the cull pass's indirect dispatch and the pass itself.
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, CullPush.BYTES);
            memPutLong(push + CullPush.VIEWS, this.views.constants().deviceAddress());
            memPutLong(push + CullPush.MESHES, pool.meshTable().deviceAddress());
            memPutLong(push + CullPush.INSTANCES, scene.instanceAddress());
            memPutLong(push + CullPush.CHUNKS, chunks.deviceAddress());
            memPutLong(push + CullPush.MESHLETS, pool.meshlets().deviceAddress());
            memPutLong(push + CullPush.CLUSTER_LOD, pool.clusterLod().deviceAddress());
            occlusion(push + CullPush.OCCLUSION, pyramid, width, height, mips);
            memPutLong(push + CullPush.MATERIALS, scene.materialsAddress()); // each meshlet's raster bin
            memPutLong(push + CullPush.CHUNK_COMMAND, chunkCommand.deviceAddress()); // how many chunks were written
            cull.bind(commands);
            cull.push(commands, push, CullPush.BYTES);
            Commands.dispatchIndirect(commands, chunkCommand.handle(), 0);
        }
        if (meshShading) { // the lists and their dispatches feed the mesh shaders
            if (!ordered) {
                Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | MESH_SHADER_STAGE,
                    VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
            }
            return;
        }
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        expand(commands, views, early, count);
        if (!ordered) { // the expansion feeds the indexed draws
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_INDEX_INPUT_BIT
                    | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT,
                VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_INDEX_READ_BIT
                    | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        }
    }

    /** The indexed path's expansion of each view's lists, per raster bin, into its index buffer. */
    private void expand(VkCommandBuffer commands, RenderView[] views, boolean[] early, int count) {
        expand.bind(commands);
        for (int i = 0; i < count; i++) {
            RenderView v = views[i];
            for (int bin = 0; bin < BINS; bin++) {
                try (MemoryStack stack = stackPush()) {
                    long push = stack.nmalloc(8, ExpandPush.BYTES);
                    memPutLong(push + ExpandPush.VIEW, v.address());
                    memPutLong(push + ExpandPush.VISIBLE, v.visible().deviceAddress());
                    memPutLong(push + ExpandPush.VISIBLE_COUNT, v.drawCount().deviceAddress());
                    memPutLong(push + ExpandPush.MESHLETS, pool.meshlets().deviceAddress());
                    memPutLong(push + ExpandPush.MESHES, pool.meshTable().deviceAddress());
                    memPutLong(push + ExpandPush.INSTANCES, scene.instanceAddress());
                    memPutLong(push + ExpandPush.MESHLET_TRIANGLES, pool.meshletTriangles().deviceAddress());
                    memPutLong(push + ExpandPush.INDICES, v.frameIndices().deviceAddress());
                    memPutInt(push + ExpandPush.BIN, bin);
                    memPutInt(push + ExpandPush.LATE, early == null ? 1 : 0);
                    expand.push(commands, push, ExpandPush.BYTES);
                    Commands.dispatchIndirect(commands, v.expandCommand().handle(), 16L * bin);
                }
            }
        }
    }

    /** Whether views[0] to views[count - 1] all have the index buffers the graph declared. */
    private static boolean indicesOrdered(RenderView[] views, int count) {
        for (int i = 0; i < count; i++) {
            if (!views[i].indicesOrdered()) {
                return false;
            }
        }
        return true;
    }

    /** The shaders' Occlusion struct at {@code address}: pyramid slot, depth size, pyramid levels. */
    private static void occlusion(long address, int pyramid, int width, int height, int mips) {
        memPutInt(address, pyramid);
        memPutInt(address + OCCLUSION_WIDTH, width);
        memPutInt(address + OCCLUSION_HEIGHT, height);
        memPutInt(address + OCCLUSION_MIPS, mips);
    }

    /**
     * Writes the instance pass's rows: each view culled (its index) and its phase ({@code CulledView}), as commands, so
     * each cull of a frame reads its own. At most 64 KiB per update, as Vulkan allows.
     */
    private void writeRows(VkCommandBuffer commands, RenderView[] views, boolean[] early, int count) {
        for (int first = 0; first < count; first += ROWS_PER_UPDATE) {
            int n = Math.min(ROWS_PER_UPDATE, count - first);
            try (MemoryStack stack = stackPush()) {
                long data = stack.nmalloc(4, 8 * n);
                for (int i = 0; i < n; i++) {
                    RenderView v = views[first + i];
                    memPutInt(data + 8L * i, v.index());
                    memPutInt(data + 8L * i + 4, phase(early, first + i));
                }
                Commands.updateBuffer(commands, this.views.rows().handle(), 8L * first, 8L * n, data);
            }
        }
    }

    /** Closes its pipelines and buffers: the GPU is done with them. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            slots.removeOnEnd(grow);
            instancesPass.close();
            cull.close();
            expand.close();
            chunks.close();
            chunkCommand.close();
        }
    }
}
