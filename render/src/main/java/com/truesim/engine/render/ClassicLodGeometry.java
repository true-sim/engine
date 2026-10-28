package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.MeshLevel;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * A {@link MeshletRenderer}'s instances as classic LOD geometry: each instance draws one whole detail level of its mesh,
 * chosen by its distance, as indexed draws, without meshlet culling. A compute pass per view tests every instance's
 * bounds against the frustum, picks the coarsest level whose error is within the view's allowed pixels (the meshlet
 * path's rule, applied per instance) and writes an indirect draw per part of it (a submesh's contiguous indices); one
 * {@code vkCmdDrawIndexedIndirectCount} draws them all. Draws depth, shaded color (the meshlet paths' fragment
 * shading) and visibility buffer IDs, so it swaps in for {@link MeshletGeometry} under any technique.
 *
 * <p>Meshes are drawn this way once {@link #add added}: discrete LODs (scene's {@code MeshBuilder.buildDiscrete}) or
 * flat meshes, whose levels are whole meshes. Instances of other meshes are not drawn. No occlusion culling: with it
 * asked for, everything in view is drawn in the first phase.
 *
 * <pre>{@code
 * ClassicLodGeometry classic = new ClassicLodGeometry(device, renderer, colorFormat, depthFormat, 65_536);
 * classic.add(rock, rockData);                 // the pool's mesh, and the data it was added from
 * SceneGeometry geometry = new SceneGeometry(classic);
 * }</pre>
 */
@AllowAllocation("created rarely; adding meshes uploads them")
public final class ClassicLodGeometry implements GeometryProvider, AutoCloseable {

    /** A visibility buffer triangle of a classic draw: this bit, the submesh << 24 and the triangle in the mesh. */
    static final int CLASSIC_TRIANGLE = 0x80000000;
    private static final int MAX_TRIANGLES = 1 << 24;
    private static final int MAX_SUBMESHES = 128;
    private static final int SELECT_PUSH = 72;
    private static final int DRAW_PUSH = 96;
    private static final int COMMAND_BYTES = 20;
    private static final int RECORD_BYTES = 16;
    private static final float[] NO_COLOR = new float[3]; // depth and IDs: cleared to 0
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final MeshletRenderer renderer;
    private final GeometryPool pool;
    private final int maxDraws;
    private final long recordsOffset;
    private final Pipeline select;
    private final Pipeline draw;
    private final Pipeline drawDepth;
    private final Pipeline drawIds; // null without primitive IDs
    private final GpuBuffer indices;
    private final GpuBuffer meshes;
    private final GpuBuffer levels;
    private final GpuBuffer parts;
    private int levelCount;
    private int partCount;
    private boolean closed;

    /**
     * Classic LOD drawing of {@code renderer}'s instances into {@code colorFormat} and {@code depthFormat} attachments
     * (the renderer's), at most {@code maxDraws} draws (parts of levels) per view.
     */
    public ClassicLodGeometry(VulkanDevice device, MeshletRenderer renderer, int colorFormat, int depthFormat,
        int maxDraws) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        if (maxDraws < 1) {
            throw new IllegalArgumentException("Room for at least one draw: " + maxDraws);
        }
        this.pool = renderer.pool();
        this.maxDraws = maxDraws;
        this.recordsOffset = (16 + (long) COMMAND_BYTES * maxDraws + 15) & ~15L;
        try (ShaderModule selectShader = ShaderModule.load(device, "shaders/classic/select.spv");
             ShaderModule vertexShader = ShaderModule.load(device, "shaders/classic/draw.spv");
             ShaderModule shading = ShaderModule.load(device, "shaders/meshlets/draw.spv")) {
            select = Pipeline.compute(device, selectShader, "selectMain", SELECT_PUSH);
            draw = Pipeline.graphics(device)
                .stage(VK_SHADER_STAGE_VERTEX_BIT, vertexShader, "classicVertexMain")
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shading, "fragmentMain") // the meshlet paths' shading
                .color(colorFormat)
                .depth(depthFormat)
                .pushConstants(DRAW_PUSH)
                .build();
            drawDepth = Pipeline.graphics(device)
                .stage(VK_SHADER_STAGE_VERTEX_BIT, vertexShader, "classicVertexMain")
                .depth(depthFormat)
                .pushConstants(DRAW_PUSH)
                .build();
            if (device.primitiveIds()) {
                try (ShaderModule idsShader = ShaderModule.load(device, "shaders/classic/ids.spv")) {
                    drawIds = Pipeline.graphics(device)
                        .stage(VK_SHADER_STAGE_VERTEX_BIT, vertexShader, "classicVertexMain")
                        .stage(VK_SHADER_STAGE_FRAGMENT_BIT, idsShader, "classicIdsMain")
                        .color(MeshletRenderer.ID_FORMAT)
                        .depth(depthFormat)
                        .pushConstants(DRAW_PUSH)
                        .build();
                }
            } else {
                drawIds = null;
            }
        }
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        // Laid out as the pool's meshlet triangles: mesh triangle t's indices at the mesh's index base + 3t.
        indices = GpuBuffer.deviceLocal(device, 12L * pool.capacity().triangles(),
            storage | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        meshes = GpuBuffer.hostVisible(device, 8L * pool.capacity().meshes(), storage);
        meshes.memory().fill((byte) 0); // no levels: not drawn
        // A level and a part hold a meshlet at least.
        levels = GpuBuffer.hostVisible(device, 16L * pool.capacity().meshlets(), storage);
        parts = GpuBuffer.hostVisible(device, 16L * pool.capacity().meshlets(), storage);
    }

    /**
     * Draws the pool's mesh {@code mesh}, added to it from {@code data}, the classic way: uploads its indices and
     * splits its levels into parts. Waits until the upload has finished.
     *
     * @throws IllegalArgumentException if {@code data} is not that mesh's, or its levels are not whole meshes (a
     *     cluster hierarchy's), or it has 2^24 triangles or 128 submeshes or more
     * @throws IllegalStateException if the mesh is drawn so already
     */
    public ClassicLodGeometry add(int mesh, MeshData data) {
        if (closed) {
            throw new IllegalStateException("Closed");
        }
        Objects.checkIndex(mesh, pool.meshCount());
        if (pool.meshletCount(mesh) != data.meshletCount()) {
            throw new IllegalArgumentException("Mesh " + mesh + " of the pool has " + pool.meshletCount(mesh)
                + " meshlets, the data " + data.meshletCount());
        }
        if (data.triangleCount() >= MAX_TRIANGLES || data.materialSlots() > MAX_SUBMESHES) {
            throw new IllegalArgumentException("At most " + (MAX_TRIANGLES - 1) + " triangles and " + MAX_SUBMESHES
                + " submeshes: " + data.triangleCount() + ", " + data.materialSlots());
        }
        if (!wholeLevels(data)) {
            throw new IllegalArgumentException("Classic LOD draws whole levels: discrete LODs or flat meshes, not a "
                + "cluster hierarchy");
        }
        MemorySegment entry = meshes.memory().asSlice(8L * mesh, 8);
        if (entry.get(U32, 4) != 0) {
            throw new IllegalStateException("Mesh " + mesh + " is drawn the classic way already");
        }
        int neededParts = 0;
        for (MeshLevel level : data.levels()) {
            neededParts += partsOf(data, level, null, 0, 0, 0);
        }
        if (levelCount + data.levels().size() > levels.size() / 16 || partCount + neededParts > parts.size() / 16) {
            throw new IllegalStateException("No room for the levels of mesh " + mesh);
        }
        int indexBase = pool.indexBase(mesh);
        indices.write(4L * indexBase, data.expandIndices());
        entry.set(U32, 0, levelCount);
        for (MeshLevel level : data.levels()) {
            long o = 16L * levelCount++;
            int count = partsOf(data, level, parts.memory(), partCount, indexBase, pool.meshletBase(mesh));
            levels.memory().set(U32, o, partCount);
            levels.memory().set(U32, o + 4, count);
            levels.memory().set(F32, o + 8, (float) level.error());
            partCount += count;
        }
        entry.set(U32, 4, data.levels().size());
        return this;
    }

    /** Whether the levels are whole meshes: one level, or discrete LODs (every meshlet with the same group sphere). */
    private static boolean wholeLevels(MeshData data) {
        if (data.levels().size() == 1) {
            return true;
        }
        double[] first = new double[10];
        double[] lod = new double[10];
        data.meshletLod(0, first);
        for (int m = 1; m < data.meshletCount(); m++) {
            data.meshletLod(m, lod);
            for (int i = 0; i < 4; i++) {
                if (lod[i] != first[i]) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Splits {@code level} into parts, runs of one submesh's meshlets with contiguous triangles, and counts them;
     * with {@code table}, writes them from part {@code at} on: indices from the mesh's {@code indexBase}, meshlets from
     * its {@code meshletBase}.
     */
    private static int partsOf(MeshData data, MeshLevel level, MemorySegment table, int at, int indexBase,
        int meshletBase) {
        int count = 0;
        int end = level.firstMeshlet() + level.meshletCount();
        int m = level.firstMeshlet();
        while (m < end) {
            int submesh = data.meshletSubmesh(m);
            int firstIndex = data.meshletTriangleOffset(m);
            int indexCount = 0;
            int first = m;
            while (m < end && data.meshletSubmesh(m) == submesh
                && data.meshletTriangleOffset(m) == firstIndex + indexCount) {
                indexCount += 3 * data.meshletTriangleCount(m);
                m++;
            }
            if (table != null) {
                long o = 16L * (at + count);
                table.set(U32, o, indexBase + firstIndex);
                table.set(U32, o + 4, indexCount);
                table.set(U32, o + 8, meshletBase + first);
                table.set(U32, o + 12, CLASSIC_TRIANGLE | submesh << 24 | firstIndex / 3);
            }
            count++;
        }
        return count;
    }

    /** The renderer whose instances, views and materials this draws. */
    public MeshletRenderer renderer() {
        return renderer;
    }

    /** The index buffer's address, for passes rebuilding classic triangles (the visibility buffer's shading). */
    long indicesAddress() {
        return indices.deviceAddress();
    }

    @Override
    public void setView(int index, Camera camera, float errorPixels, int mode) {
        renderer.setView(index, camera, errorPixels, mode);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void drawFirst(GraphBuilder g, SceneDraw draw, SceneDraw.Targets targets, boolean load) {
        SurfaceOutput.Kind kind = draw.output().kind();
        if (kind == SurfaceOutput.Kind.IDS && drawIds == null) {
            throw new IllegalStateException("The device has no primitive IDs in fragment shaders (geometryShader)");
        }
        int view = draw.view();
        Camera camera = draw.camera();
        String name = switch (kind) {
            case DEPTH -> "classic depth, view " + view;
            case SHADED -> "classic LOD";
            case IDS -> "classic LOD ids";
        };
        Buffer list = g.createBuffer(name + " draws", recordsOffset + (long) RECORD_BYTES * maxDraws);
        g.pass(name + " reset").write(list, BufferAccess.TRANSFER_DST)
            .record(ctx -> vkCmdFillBuffer(ctx.commands(), ctx.buffer(list), 0, 16, 0));
        g.pass(name + " selection").write(list, BufferAccess.STORAGE_WRITE).record(ctx -> {
            if (camera != null) {
                renderer.setView(view, camera, 1, MeshletRenderer.MODE_LIT);
            }
            select(ctx.commands(), view, ctx.address(list));
        });
        GraphBuilder.PassBuilder pass = g.pass(name)
            .read(list, BufferAccess.INDIRECT)
            .read(list, BufferAccess.DRAW_READ)
            .write(targets.depth(), Access.DEPTH_ATTACHMENT);
        if (targets.color() != null) {
            pass.write(targets.color(), Access.COLOR_ATTACHMENT);
        }
        Buffer lights = targets.lights();
        if (lights != null) {
            pass.read(lights, BufferAccess.DRAW_READ);
        }
        ShadowInputs.Set shadows = targets.shadows();
        if (shadows != null) {
            shadows.readBy(pass, Access.SAMPLED_FRAGMENT, BufferAccess.DRAW_READ);
        }
        Pipeline pipeline = switch (kind) {
            case DEPTH -> drawDepth;
            case SHADED -> this.draw;
            case IDS -> drawIds;
        };
        float[] clear = kind == SurfaceOutput.Kind.SHADED ? draw.output().clear() : NO_COLOR;
        pass.record(ctx -> {
            if (shadows != null) {
                shadows.prepare(ctx);
            }
            long color = targets.color() == null ? 0 : ctx.view(targets.color());
            record(ctx, pipeline, view, color, ctx.view(targets.depth()), draw.width(), draw.height(), clear, load,
                lights == null ? 0 : ctx.address(lights), list);
        });
    }

    /** Selects view {@code view}'s draws into the draw list at {@code list}. */
    private void select(VkCommandBuffer commands, int view, long list) {
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, SELECT_PUSH);
            memPutLong(push, renderer.viewAddress(view));
            memPutLong(push + 8, pool.meshTable().deviceAddress());
            memPutLong(push + 16, renderer.instanceBuffer().deviceAddress());
            memPutLong(push + 24, meshes.deviceAddress());
            memPutLong(push + 32, levels.deviceAddress());
            memPutLong(push + 40, parts.deviceAddress());
            memPutLong(push + 48, list);
            memPutLong(push + 56, list + recordsOffset);
            memPutInt(push + 64, renderer.instanceCount());
            memPutInt(push + 68, maxDraws);
            select.bind(commands);
            select.push(commands, push, SELECT_PUSH);
            vkCmdDispatch(commands, (renderer.instanceCount() + 63) / 64, 1, 1);
        }
    }

    /** Draws the list's draws of view {@code view} with {@code pipeline}, over the targets with {@code load}. */
    private void record(PassContext ctx, Pipeline pipeline, int view, long colorView, long depthView, int width,
        int height, float[] clear, boolean load, long lights, Buffer list) {
        VkCommandBuffer commands = ctx.commands();
        long handle = ctx.buffer(list);
        try (MemoryStack stack = stackPush()) {
            MeshletRenderer.beginRendering(commands, stack, colorView, depthView, width, height, clear[0], clear[1],
                clear[2], load);
            long push = stack.ncalloc(8, 1, DRAW_PUSH);
            memPutLong(push, renderer.viewAddress(view));
            memPutLong(push + 8, pool.meshTable().deviceAddress());
            memPutLong(push + 16, renderer.instanceBuffer().deviceAddress());
            memPutLong(push + 24, pool.positions().deviceAddress());
            memPutLong(push + 32, pool.attributes().deviceAddress());
            memPutLong(push + 40, ctx.address(list) + recordsOffset);
            memPutLong(push + 80, renderer.materialsAddress());
            memPutLong(push + 88, lights);
            pipeline.bind(commands);
            MeshletRenderer.setViewport(commands, stack, width, height);
            pipeline.push(commands, push, DRAW_PUSH);
            vkCmdBindIndexBuffer(commands, indices.handle(), 0, VK_INDEX_TYPE_UINT32);
            vkCmdDrawIndexedIndirectCount(commands, handle, 16, handle, 0, maxDraws, COMMAND_BYTES);
            vkCmdEndRendering(commands);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            select.close();
            draw.close();
            drawDepth.close();
            if (drawIds != null) {
                drawIds.close();
            }
            indices.close();
            meshes.close();
            levels.close();
            parts.close();
        }
    }
}
