package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_INFO;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.layout.DrawPush;
import com.truesim.engine.render.layout.MeshPush;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

/**
 * A {@link MeshletRenderer}'s draws of the meshlets its culling listed: a pipeline per raster bin and role (shaded,
 * depth only, the visibility buffer's IDs), on the indexed path and on mesh shaders. Drawing allocates nothing.
 */
@AllowAllocation("created rarely")
final class RasterPipelines implements AutoCloseable {

    /** Shaded, into color and depth. */
    static final int SHADED = 0;
    /** Depth only: a shadow map, say. */
    static final int DEPTH = 1;
    /** The visibility buffer's IDs ({@link MeshletRenderer#ID_FORMAT}) and depth. */
    static final int IDS = 2;
    /** Both draw paths' pipelines take the larger push block: the mesh shaders', which the indexed one starts like. */
    private static final int DRAW_PUSH = MeshPush.BYTES;
    private static final int BINS = MeshletRenderer.BINS;
    private static final int BIN_OPAQUE = MeshletRenderer.BIN_OPAQUE;
    private static final int BIN_MASKED = MeshletRenderer.BIN_MASKED;
    private static final int BIN_BLENDED = MeshletRenderer.BIN_BLENDED;

    private final VulkanDevice device;
    // Per role, then raster bin (BIN_*): on the indexed path, and on mesh shaders (null without); the IDs' role is null
    // without primitive IDs.
    private final Pipeline[][] indexed = new Pipeline[3][];
    private final Pipeline[][] mesh = new Pipeline[3][];
    // The blended bin into the transparency pass's targets: on the indexed path, and on mesh shaders (null without).
    private final Pipeline indexedBlended;
    private Pipeline meshBlended;

    RasterPipelines(VulkanDevice device, int colorFormat, int depthFormat) {
        this.device = device;
        try (ShaderModule drawShader = ShaderModule.load(device, "shaders/meshlets/draw.spv")) {
            ShaderModule idsShader = device.primitiveIds() ? ShaderModule.load(device, "shaders/visibility/ids.spv")
                : null;
            // A pipeline per raster bin and role: the opaque bin culls back faces and discards nothing; the
            // double-sided one culls nothing; the masked one culls nothing and discards (masked texels, one-sided
            // materials' back faces), depth only too, so shadow maps mask what the camera does.
            indexed[SHADED] = bins(bin -> raster(bin, drawShader, VK_SHADER_STAGE_VERTEX_BIT, "vertexMain")
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT, drawShader, masking(bin) ? "maskedMain" : "fragmentMain")
                .color(colorFormat).depth(depthFormat).build());
            indexed[DEPTH] = bins(bin -> depthOnly(raster(bin, drawShader, VK_SHADER_STAGE_VERTEX_BIT, "vertexMain"),
                bin, drawShader).depth(depthFormat).build());
            indexedBlended = blended(raster(BIN_BLENDED, drawShader, VK_SHADER_STAGE_VERTEX_BIT, "vertexMain")
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT, drawShader, "blendedMain"), depthFormat);
            indexed[IDS] = idsShader == null ? null : bins(bin -> raster(bin, drawShader, VK_SHADER_STAGE_VERTEX_BIT,
                "vertexMain").stage(VK_SHADER_STAGE_FRAGMENT_BIT, idsShader, masking(bin) ? "maskedIdsMain"
                : "idsMain").color(MeshletRenderer.ID_FORMAT).depth(depthFormat).build());
            // Barycentrics and primitive IDs in fragment shaders: the mesh path's lean and slim interfaces (lean.slang, mesh.slang).
            boolean slimMeshes = device.barycentrics() && device.primitiveIds();
            if (device.meshShaders()) {
                try (ShaderModule meshShader = ShaderModule.load(device, "shaders/meshlets/mesh.spv")) {
                    if (slimMeshes) {
                        // What interpolates, and the rest from the triangle (lean.slang); depth alone from positions.
                        try (ShaderModule leanShader = ShaderModule.load(device, "shaders/meshlets/lean.spv")) {
                            meshBlended = blended(raster(BIN_BLENDED, leanShader, VK_SHADER_STAGE_MESH_BIT_EXT,
                                "meshLeanMain").stage(VK_SHADER_STAGE_FRAGMENT_BIT, leanShader, "leanBlendedMain"),
                                depthFormat);
                            mesh[SHADED] = bins(bin -> raster(bin, leanShader, VK_SHADER_STAGE_MESH_BIT_EXT,
                                "meshLeanMain").stage(VK_SHADER_STAGE_FRAGMENT_BIT, leanShader, masking(bin)
                                ? "leanMaskedMain" : "leanMain").color(colorFormat).depth(depthFormat).build());
                        }
                        // Opaque bins' depth from positions alone (no fragment shader); the masked bin's fragments
                        // need texture coordinates, from the full vertices: in depth-only pipelines on an RX 9070 XT
                        // the fragment shader did not see the primitive ID the mesh shader wrote (pitfalls.md).
                        mesh[DEPTH] = bins(bin -> (masking(bin) ? depthOnly(raster(bin, meshShader,
                            VK_SHADER_STAGE_MESH_BIT_EXT, "meshIdsMain"), bin, drawShader) : raster(bin, meshShader,
                            VK_SHADER_STAGE_MESH_BIT_EXT, "meshMain")).depth(depthFormat).build());
                    } else {
                        meshBlended = blended(raster(BIN_BLENDED, meshShader, VK_SHADER_STAGE_MESH_BIT_EXT,
                            "meshIdsMain").stage(VK_SHADER_STAGE_FRAGMENT_BIT, drawShader, "blendedMain"), depthFormat);
                        mesh[SHADED] = bins(bin -> raster(bin, meshShader, VK_SHADER_STAGE_MESH_BIT_EXT, "meshIdsMain")
                            .stage(VK_SHADER_STAGE_FRAGMENT_BIT, drawShader, masking(bin) ? "maskedMain"
                                : "fragmentMain") // the same shading
                            .color(colorFormat).depth(depthFormat).build());
                        mesh[DEPTH] = bins(bin -> depthOnly(raster(bin, meshShader, VK_SHADER_STAGE_MESH_BIT_EXT,
                            "meshIdsMain"), bin, drawShader).depth(depthFormat).build());
                    }
                    mesh[IDS] = idsShader == null ? null : bins(bin -> raster(bin, meshShader,
                        VK_SHADER_STAGE_MESH_BIT_EXT, "meshIdsMain").stage(VK_SHADER_STAGE_FRAGMENT_BIT, idsShader,
                            masking(bin) ? "maskedIdsMain" : "idsMain").color(MeshletRenderer.ID_FORMAT)
                        .depth(depthFormat).build());
                }
            }
            if (idsShader != null) {
                idsShader.close();
            }
        }
    }

    /** Whether the device draws on mesh shaders. */
    boolean meshShaders() {
        return mesh[SHADED] != null;
    }

    /** Throws without primitive IDs in fragment shaders: no visibility buffer. */
    void checkIds() {
        if (indexed[IDS] == null) {
            throw new IllegalStateException("The device has no primitive IDs in fragment shaders (geometryShader)");
        }
    }

    /**
     * Draws {@code v}'s raster bins in {@code role} into its own rendering scope: the late phase's lists over the
     * attachments with {@code late}, else the early (or only) phase's into cleared ones.
     */
    void draw(VkCommandBuffer commands, GpuScene scene, RenderView v, boolean meshShading, int role, boolean late,
        long colorView, long depthView, int width, int height, float r, float g, float b, long clusters) {
        try (MemoryStack stack = stackPush()) {
            beginRendering(commands, stack, colorView, depthView, width, height, r, g, b, late);
            setViewport(commands, stack, width, height);
        }
        drawBins(commands, scene, v, meshShading, role, late, clusters);
        Commands.endRendering(commands);
    }

    /**
     * Draws {@code v}'s blended bin, its lists both phases' (appended), into a transparency technique's attachments
     * open on {@code commands}: weighted, into accumulated color and revealage; as opaque, with the shaded pipelines
     * (the masked entry points: one-sided back faces discarded).
     */
    void drawBlended(VkCommandBuffer commands, GpuScene scene, RenderView v, boolean meshShading,
        BlendedTargets.Kind kind, long clusters, long behind) {
        boolean weighted = kind == BlendedTargets.Kind.WEIGHTED;
        try (MemoryStack stack = stackPush()) {
            if (meshShading) {
                drawMeshes(commands, stack, scene, v, BIN_BLENDED, false, v.drawCount().deviceAddress(), clusters,
                    weighted ? meshBlended : mesh[SHADED][BIN_BLENDED], behind);
            } else {
                drawIndexed(commands, stack, scene, v, BIN_BLENDED, clusters,
                    weighted ? indexedBlended : indexed[SHADED][BIN_BLENDED], behind);
            }
        }
    }

    /**
     * A pipeline drawing blended fragments ({@code graphics}, its stages set) into the transparency pass's targets:
     * accumulated color added up, revealage multiplied down, the opaque depth tested and kept.
     */
    static Pipeline blended(Pipeline.Graphics graphics, int depthFormat) {
        return graphics.color(WeightedBlendedTransparency.ACCUMULATED_FORMAT).color(WeightedBlendedTransparency.REVEALAGE_FORMAT)
            .blend(0, Pipeline.Blend.ADDITIVE).blend(1, Pipeline.Blend.TRANSMIT).depth(depthFormat)
            .depthTest(VK_COMPARE_OP_GREATER_OR_EQUAL, false).build();
    }

    /** Draws {@code v}'s opaque raster bins in {@code role} into the rendering scope open on {@code commands}. */
    void drawBins(VkCommandBuffer commands, GpuScene scene, RenderView v, boolean meshShading, int role, boolean late,
        long clusters) {
        long count = v.drawCount().deviceAddress();
        Pipeline[] pipelines = meshShading ? mesh[role] : indexed[role];
        try (MemoryStack stack = stackPush()) {
            for (int bin = 0; bin < BIN_BLENDED; bin++) {
                if (meshShading) {
                    drawMeshes(commands, stack, scene, v, bin, late, count, clusters, pipelines[bin], 0);
                } else {
                    drawIndexed(commands, stack, scene, v, bin, clusters, pipelines[bin], 0);
                }
            }
        }
    }

    private static void drawMeshes(VkCommandBuffer commands, MemoryStack stack, GpuScene scene, RenderView v, int bin,
        boolean late, long count, long clusters, Pipeline pipeline, long behind) {
        GeometryPool pool = scene.pool();
        long push = stack.nmalloc(8, DRAW_PUSH);
        memPutLong(push + MeshPush.VIEW, v.address());
        memPutLong(push + MeshPush.MESHES, pool.meshTable().deviceAddress());
        memPutLong(push + MeshPush.INSTANCES, scene.instanceAddress());
        memPutLong(push + MeshPush.VISIBLE, v.visible().deviceAddress());
        memPutLong(push + MeshPush.VISIBLE_COUNT, count);
        memPutLong(push + MeshPush.MESHLETS, pool.meshlets().deviceAddress());
        memPutLong(push + MeshPush.MESHLET_VERTICES, pool.meshletVertices().deviceAddress());
        memPutLong(push + MeshPush.MESHLET_TRIANGLES, pool.meshletTriangles().deviceAddress());
        memPutLong(push + MeshPush.POSITIONS, pool.positions().deviceAddress());
        memPutLong(push + MeshPush.ATTRIBUTES, pool.attributes().deviceAddress());
        memPutLong(push + MeshPush.MATERIALS, scene.materialsAddress());
        memPutLong(push + MeshPush.LIGHTS, clusters);
        memPutInt(push + MeshPush.BIN, bin);
        memPutInt(push + MeshPush.LATE, late ? 1 : 0);
        memPutLong(push + MeshPush.BACKGROUND, behind); // its slot, then its region: SceneBehind.words
        pipeline.bind(commands);
        pipeline.push(commands, push, DRAW_PUSH);
        Commands.drawMeshTasksIndirect(commands, v.expandCommand().handle(), 16L * bin, 1, 12);
    }

    private static void drawIndexed(VkCommandBuffer commands, MemoryStack stack, GpuScene scene, RenderView v, int bin,
        long clusters, Pipeline pipeline, long behind) {
        GeometryPool pool = scene.pool();
        long push = stack.ncalloc(8, 1, DRAW_PUSH);
        memPutLong(push + DrawPush.VIEW, v.address());
        memPutLong(push + DrawPush.MESHES, pool.meshTable().deviceAddress());
        memPutLong(push + DrawPush.INSTANCES, scene.instanceAddress());
        memPutLong(push + DrawPush.POSITIONS, pool.positions().deviceAddress());
        memPutLong(push + DrawPush.ATTRIBUTES, pool.attributes().deviceAddress());
        memPutLong(push + DrawPush.VISIBLE, v.visible().deviceAddress());
        memPutLong(push + DrawPush.MESHLETS, pool.meshlets().deviceAddress());
        memPutLong(push + DrawPush.MESHLET_VERTICES, pool.meshletVertices().deviceAddress());
        memPutLong(push + DrawPush.MATERIALS, scene.materialsAddress());
        memPutLong(push + DrawPush.LIGHTS, clusters);
        memPutLong(push + DrawPush.BACKGROUND, behind);
        pipeline.bind(commands);
        pipeline.push(commands, push, DRAW_PUSH);
        Commands.bindIndexBuffer(commands, v.frameIndices().handle(), 0, VK_INDEX_TYPE_UINT32);
        Commands.drawIndexedIndirect(commands, v.drawCommand().handle(), 24L * bin, 1, 20);
    }

    /** A graphics pipeline of raster bin {@code bin} drawing with {@code entry} of {@code shader}'s {@code stage}. */
    private Pipeline.Graphics raster(int bin, ShaderModule shader, int stage, String entry) {
        Pipeline.Graphics graphics = Pipeline.graphics(device).stage(stage, shader, entry).pushConstants(DRAW_PUSH);
        return bin == BIN_OPAQUE ? graphics : graphics.doubleSided(); // the masked bin culls one-sided back faces itself
    }

    /**
     * Whether bin {@code bin} draws with the masked entry points: the masked bin, and the blended one drawn as opaque
     * ({@link OpaqueTransparency}; both discard one-sided materials' back faces, drawn without culling).
     */
    private static boolean masking(int bin) {
        return bin == BIN_MASKED || bin == BIN_BLENDED;
    }

    /** Depth only: no fragment stage but the masked bins', which discard what they mask. */
    private static Pipeline.Graphics depthOnly(Pipeline.Graphics graphics, int bin, ShaderModule drawShader) {
        return masking(bin) ? graphics.stage(VK_SHADER_STAGE_FRAGMENT_BIT, drawShader, "maskedDepthMain")
            : graphics;
    }

    /** A pipeline per raster bin, made by {@code make}. */
    private static Pipeline[] bins(java.util.function.IntFunction<Pipeline> make) {
        Pipeline[] pipelines = new Pipeline[BINS];
        for (int bin = 0; bin < BINS; bin++) {
            pipelines[bin] = make.apply(bin);
        }
        return pipelines;
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
        Commands.beginRendering(commands, rendering);
    }

    static void setViewport(VkCommandBuffer commands, MemoryStack stack, int width, int height) {
        long viewport = stack.ncalloc(VkViewport.ALIGNOF, 1, VkViewport.SIZEOF);
        memPutFloat(viewport + VkViewport.WIDTH, width);
        memPutFloat(viewport + VkViewport.HEIGHT, height);
        memPutFloat(viewport + VkViewport.MAXDEPTH, 1);
        Commands.setViewport(commands, 0, 1, viewport);
        long scissor = stack.ncalloc(VkRect2D.ALIGNOF, 1, VkRect2D.SIZEOF);
        memPutInt(scissor + VkRect2D.EXTENT, width);
        memPutInt(scissor + VkRect2D.EXTENT + 4, height);
        Commands.setScissor(commands, 0, 1, scissor);
    }

    private static void attachment(long info, long view, int layout, boolean load) {
        memPutInt(info + VkRenderingAttachmentInfo.STYPE, VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO);
        memPutLong(info + VkRenderingAttachmentInfo.IMAGEVIEW, view);
        memPutInt(info + VkRenderingAttachmentInfo.IMAGELAYOUT, layout);
        memPutInt(info + VkRenderingAttachmentInfo.LOADOP,
            load ? VK_ATTACHMENT_LOAD_OP_LOAD : VK_ATTACHMENT_LOAD_OP_CLEAR);
        memPutInt(info + VkRenderingAttachmentInfo.STOREOP, VK_ATTACHMENT_STORE_OP_STORE);
    }

    @Override
    public void close() {
        indexedBlended.close();
        if (meshBlended != null) {
            meshBlended.close();
        }
        for (Pipeline[][] paths : new Pipeline[][][] {indexed, mesh}) {
            for (Pipeline[] pipelines : paths) {
                if (pipelines != null) {
                    for (Pipeline pipeline : pipelines) {
                        pipeline.close();
                    }
                }
            }
        }
    }
}
