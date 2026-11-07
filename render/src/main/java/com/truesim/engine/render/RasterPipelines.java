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
import com.truesim.engine.render.layout.CommonConstants;
import com.truesim.engine.render.layout.ForwardPush;
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
 * A {@link MeshletRenderer}'s draws of the meshlets its culling listed, on the indexed path and on mesh shaders: into
 * color by material type (each shading bin with pipelines of its type's entry points, from the view's lists split by
 * shading bin), and into depth or the visibility buffer's IDs with pipelines shared by every type, a raster bin each.
 * Drawing allocates nothing.
 */
@AllowAllocation("created rarely")
final class RasterPipelines implements AutoCloseable {

    /** Depth only: a shadow map, say. */
    static final int DEPTH = 1;
    /** The visibility buffer's IDs ({@link MeshletRenderer#ID_FORMAT}) and depth. */
    static final int IDS = 2;
    /**
     * Shadow casters' depth: as {@link #DEPTH}, every face drawn (both sides, one-sided materials' back faces too), as
     * shadow rays meet every face: a light inside a closed mesh sees its inside.
     */
    static final int SHADOW = 3;
    /** Every raster pipeline takes forward.slang's push block. */
    private static final int DRAW_PUSH = ForwardPush.BYTES;
    private static final int BINS = MeshletRenderer.BINS;
    private static final int BIN_OPAQUE = MeshletRenderer.BIN_OPAQUE;
    private static final int BIN_MASKED = MeshletRenderer.BIN_MASKED;
    private static final int BIN_BLENDED = MeshletRenderer.BIN_BLENDED;

    private final VulkanDevice device;
    // Per role, then raster bin (BIN_*): on the indexed path, and on mesh shaders (null without); the IDs' role is null
    // without primitive IDs. Index 0 (color) is unused: color is drawn by type.
    private final Pipeline[][] indexed = new Pipeline[4][];
    private final Pipeline[][] mesh = new Pipeline[4][];
    private final boolean slimMeshes;
    private final ShadingPipelines store;

    RasterPipelines(VulkanDevice device, ShadingPipelines store, int depthFormat) {
        this.device = device;
        this.store = store;
        this.slimMeshes = device.meshShaders() && device.barycentrics() && device.primitiveIds();
        try (ShaderModule drawShader = ShaderModule.load(device, "shaders/meshlets/draw.spv")) {
            ShaderModule idsShader = device.primitiveIds() ? ShaderModule.load(device, "shaders/visibility/ids.spv")
                : null;
            // A pipeline per raster bin and role: the opaque bin culls back faces and discards nothing; the
            // double-sided one culls nothing; the masked one culls nothing and discards (masked texels, one-sided
            // materials' back faces), depth only too, so shadow maps mask what the camera does.
            indexed[DEPTH] = bins(bin -> depthOnly(raster(bin, drawShader, VK_SHADER_STAGE_VERTEX_BIT, "vertexMain"),
                bin, drawShader).depth(depthFormat).build());
            indexed[SHADOW] = bins(bin -> shadowOnly(raster(bin, drawShader, VK_SHADER_STAGE_VERTEX_BIT,
                "vertexMain").doubleSided(), bin, drawShader).depth(depthFormat).build());
            indexed[IDS] = idsShader == null ? null : bins(bin -> raster(bin, drawShader, VK_SHADER_STAGE_VERTEX_BIT,
                "vertexMain").stage(VK_SHADER_STAGE_FRAGMENT_BIT, idsShader, masking(bin) ? "maskedIdsMain"
                : "idsMain").color(MeshletRenderer.ID_FORMAT).depth(depthFormat).build());
            if (device.meshShaders()) {
                try (ShaderModule meshShader = ShaderModule.load(device, "shaders/meshlets/mesh.spv")) {
                    if (slimMeshes) {
                        // Opaque bins' depth from positions alone (no fragment shader); the masked bin's fragments
                        // need texture coordinates, from the full vertices: in depth-only pipelines on an RX 9070 XT
                        // the fragment shader did not see the primitive ID the mesh shader wrote (pitfalls.md).
                        mesh[DEPTH] = bins(bin -> (masking(bin) ? depthOnly(raster(bin, meshShader,
                            VK_SHADER_STAGE_MESH_BIT_EXT, "meshIdsMain"), bin, drawShader) : raster(bin, meshShader,
                            VK_SHADER_STAGE_MESH_BIT_EXT, "meshMain")).depth(depthFormat).build());
                        mesh[SHADOW] = bins(bin -> (masking(bin) ? shadowOnly(raster(bin, meshShader,
                            VK_SHADER_STAGE_MESH_BIT_EXT, "meshIdsMain").doubleSided(), bin, drawShader)
                            : raster(bin, meshShader, VK_SHADER_STAGE_MESH_BIT_EXT, "meshMain").doubleSided())
                            .depth(depthFormat).build());
                    } else {
                        mesh[DEPTH] = bins(bin -> depthOnly(raster(bin, meshShader, VK_SHADER_STAGE_MESH_BIT_EXT,
                            "meshIdsMain"), bin, drawShader).depth(depthFormat).build());
                        mesh[SHADOW] = bins(bin -> shadowOnly(raster(bin, meshShader, VK_SHADER_STAGE_MESH_BIT_EXT,
                            "meshIdsMain").doubleSided(), bin, drawShader).depth(depthFormat).build());
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
        return mesh[DEPTH] != null;
    }

    /** Whether the device draws the visibility buffer's IDs (primitive IDs in fragment shaders). */
    boolean ids() {
        return indexed[IDS] != null;
    }

    /** Whether shaded draws on mesh shaders take the lean path (fragments from barycentrics and the triangle). */
    boolean leanColor() {
        return slimMeshes;
    }

    /** Throws without primitive IDs in fragment shaders: no visibility buffer. */
    void checkIds() {
        if (indexed[IDS] == null) {
            throw new IllegalStateException("The device has no primitive IDs in fragment shaders (geometryShader)");
        }
    }

    /**
     * Draws {@code v} shaded by type into its own rendering scope, cleared to ({@code r}, {@code g}, {@code b}) unless
     * {@code late}: {@link #drawTyped} with {@code pipelines}.
     */
    void drawShaded(VkCommandBuffer commands, GpuScene scene, RenderView v, boolean meshShading, boolean late,
        long colorView, long depthView, int width, int height, float r, float g, float b, long clusters,
        Typed pipelines) {
        try (MemoryStack stack = stackPush()) {
            beginRendering(commands, stack, colorView, depthView, width, height, r, g, b, late);
            setViewport(commands, stack, width, height);
        }
        drawTyped(commands, scene, v, meshShading, late, clusters, 0, pipelines);
        Commands.endRendering(commands);
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
    void drawBins(VkCommandBuffer commands, GpuScene scene, RenderView v, boolean meshShading, int role, boolean late) {
        long count = v.drawCount().deviceAddress();
        Pipeline[] pipelines = meshShading ? mesh[role] : indexed[role];
        try (MemoryStack stack = stackPush()) {
            for (int bin = 0; bin < BIN_BLENDED; bin++) {
                if (meshShading) {
                    drawMeshes(commands, stack, scene, v, bin, late, count, pipelines[bin]);
                } else {
                    drawIndexed(commands, stack, scene, v, bin, pipelines[bin]);
                }
            }
        }
    }

    /**
     * What a pipeline drawing raster bin {@code bin} of a shading bin of {@code type} with {@code features} into color
     * into {@code target} (the blended bin also {@link ShadingPipelines#WEIGHTED}, weighted transparency's targets, or
     * {@link ShadingPipelines#OVER}, over the scene's color) is made of: the type's forward entry point behind the
     * geometry stage reading the split list, on mesh shaders or the indexed path.
     */
    @AllowAllocation("building the graph, when it changes")
    ShadingPipelines.Key forwardKey(MaterialType type, int features, int bin, boolean meshShading, int target,
        ShadingHooks.Set hooks) {
        boolean lean = meshShading && slimMeshes;
        boolean weighted = target == ShadingPipelines.WEIGHTED;
        boolean surfaces = target == ShadingPipelines.COLOR_SURFACES;
        String suffix = weighted ? "BlendedMain" : surfaces ? (masking(bin) ? "MaskedSurfaceMain" : "SurfaceMain")
            : masking(bin) ? "MaskedMain" : lean ? "Main" : "FragmentMain";
        return new ShadingPipelines.Key(!meshShading ? "shaders/meshlets/draw.spv" : lean
            ? "shaders/meshlets/lean.spv" : "shaders/meshlets/mesh.spv",
            !meshShading ? "vertexMain" : lean ? "meshLeanTypedMain" : "meshIdsTypedMain", meshShading,
            lean ? type.forwardLeanShader() : type.forwardShader(), type.name() + (lean ? "Lean" : "") + suffix,
            features, type.geometry(), bin == BIN_OPAQUE ? ShadingPipelines.CULL_BACK : ShadingPipelines.DOUBLE_SIDED,
            target, DRAW_PUSH, hooks);
    }

    /**
     * Forward shading by type, for a material table as a graph built: per shading bin and raster bin its materials draw
     * in, a pipeline of its type's forward entry point with only the bin's features (and the blended bin's weighted
     * one), per path; made for the path of the build, and for the other when a frame first draws on it.
     */
    final class Typed {
        private final boolean surfaces; // the opaque bins' pipelines write the surface outputs too
        private final boolean opaque;   // false: the blended bin's pipelines only (a transparency pass's)
        private final ShadingHooks.Set hooks; // the shading hooks the view runs (SHADING_HOOKS_<n>)
        private final MaterialType[] types;
        private final int[] features;
        private final int[] rasters;
        // Per path (0: indexed, 1: mesh shaders): per shading bin * BINS + raster bin; null where none draws or not yet
        private final Pipeline[][] shaded = new Pipeline[2][];
        private final Pipeline[][] weighted = new Pipeline[2][];
        private final Pipeline[][] over = new Pipeline[2][];

        @AllowAllocation("building the graph, when it changes")
        private Typed(Materials materials, boolean meshShading, boolean surfaces, boolean opaque,
            ShadingHooks.Set hooks) {
            this.surfaces = surfaces;
            this.hooks = hooks;
            this.opaque = opaque;
            int bins = materials.binCount();
            types = new MaterialType[bins];
            features = new int[bins];
            rasters = new int[bins];
            for (int category = 0; category < bins; category++) {
                types[category] = materials.binType(category);
                features[category] = materials.binFeatures(category);
                rasters[category] = materials.binDrawn(category);
            }
            make(meshShading ? 1 : 0);
        }

        /** How many shading bins it draws. */
        int categories() {
            return types.length;
        }

        /** Makes (or takes from the store) the pipelines of path {@code path}. */
        @AllowAllocation("building the graph, or a frame first drawing on the other path")
        private void make(int path) {
            Pipeline[] s = new Pipeline[types.length * BINS];
            Pipeline[] w = new Pipeline[types.length];
            Pipeline[] o = new Pipeline[types.length];
            for (int category = 0; category < types.length; category++) {
                for (int bin = 0; bin < BINS; bin++) {
                    if ((rasters[category] & 1 << bin) != 0 && (opaque || bin == BIN_BLENDED)) {
                        s[category * BINS + bin] = store.get(forwardKey(types[category], features[category], bin,
                            path == 1, surfaces && bin != BIN_BLENDED ? ShadingPipelines.COLOR_SURFACES
                                : ShadingPipelines.COLOR, hooks));
                        if (bin == BIN_BLENDED) {
                            w[category] = store.get(forwardKey(types[category], features[category], bin, path == 1,
                                ShadingPipelines.WEIGHTED, hooks));
                            o[category] = store.get(forwardKey(types[category], features[category], bin, path == 1,
                                ShadingPipelines.OVER, hooks));
                        }
                    }
                }
            }
            weighted[path] = w;
            over[path] = o;
            shaded[path] = s;
        }

        /** Shading bin {@code category}'s pipeline for raster bin {@code bin} on the path; null where none draws. */
        Pipeline shaded(boolean meshShading, int category, int bin) {
            int path = meshShading ? 1 : 0;
            if (shaded[path] == null) {
                make(path);
            }
            return shaded[path][category * BINS + bin];
        }

        /** Shading bin {@code category}'s pipeline blending over the scene's color; null where none draws blended. */
        Pipeline over(boolean meshShading, int category) {
            int path = meshShading ? 1 : 0;
            if (over[path] == null) {
                make(path);
            }
            return over[path][category];
        }

        /** The shading bins drawing blended, numbered among themselves (-1: none), as sorts number them. */
        @AllowAllocation("building the graph, when it changes")
        int[] blendedNumbers() {
            int[] numbers = new int[types.length];
            int next = 0;
            for (int category = 0; category < types.length; category++) {
                numbers[category] = (rasters[category] & 1 << BIN_BLENDED) != 0 ? next++ : -1;
            }
            return numbers;
        }

        /** Shading bin {@code category}'s weighted blended pipeline on the path; null where it draws nothing blended. */
        Pipeline weighted(boolean meshShading, int category) {
            int path = meshShading ? 1 : 0;
            if (weighted[path] == null) {
                make(path);
            }
            return weighted[path][category];
        }
    }

    /**
     * What a pipeline drawing raster bin {@code bin} into depth or IDs ({@code role}) from a split list is made of: the
     * shared fragment stage for every type without geometry hooks ({@code type} null), else {@code type}'s depth or ID
     * entry point running its hooks, behind the geometry stage reading the split list (full vertices on mesh shaders,
     * but for the shared opaque depth, from positions alone).
     */
    @AllowAllocation("building the graph, when it changes")
    ShadingPipelines.Key geometryKey(MaterialType type, int bin, int role, boolean meshShading) {
        boolean masked = masking(bin);
        boolean shadow = role == SHADOW;
        boolean depth = role == DEPTH || shadow;
        boolean slim = meshShading && slimMeshes && depth && !masked && type == null;
        String geometryShader = !meshShading ? "shaders/meshlets/draw.spv" : "shaders/meshlets/mesh.spv";
        String geometryEntry = !meshShading ? "vertexMain" : slim ? "meshTypedMain" : "meshIdsTypedMain";
        String fragmentShader;
        String fragmentEntry;
        if (type != null) {
            fragmentShader = type.geometryShader();
            fragmentEntry = type.name() + (masked ? "Masked" : "") + (shadow ? "ShadowMain" : depth ? "DepthMain"
                : "IdsMain");
        } else if (depth) {
            fragmentShader = masked ? "shaders/meshlets/draw.spv" : null;
            fragmentEntry = masked ? (shadow ? "maskedShadowMain" : "maskedDepthMain") : null;
        } else {
            fragmentShader = "shaders/visibility/ids.spv";
            fragmentEntry = masked ? "maskedIdsMain" : "idsMain";
        }
        return new ShadingPipelines.Key(geometryShader, geometryEntry, meshShading, fragmentShader, fragmentEntry, 0,
            type == null ? 0 : type.geometry(), bin == BIN_OPAQUE && !shadow ? ShadingPipelines.CULL_BACK
            : ShadingPipelines.DOUBLE_SIDED, depth ? ShadingPipelines.DEPTH : ShadingPipelines.IDS, DRAW_PUSH,
            ShadingHooks.Set.NONE);
    }

    /**
     * Depth or IDs ({@link #DEPTH}, {@link #IDS}) by type with geometry hooks, from lists split into categories: per
     * category its type with hooks (null: every type without, which share pipelines), and per opaque raster bin a
     * pipeline running its hooks; per path, made for the path of the build and for the other when a frame first draws
     * on it.
     */
    final class Geometry {
        private final MaterialType[] types;
        private final int role;
        private final Pipeline[][] pipelines = new Pipeline[2][];

        @AllowAllocation("building the graph, when it changes")
        private Geometry(MaterialType[] types, int role, boolean meshShading) {
            this.types = types;
            this.role = role;
            make(meshShading ? 1 : 0);
        }

        /** How many categories it draws. */
        int categories() {
            return types.length;
        }

        @AllowAllocation("building the graph, or a frame first drawing on the other path")
        private void make(int path) {
            Pipeline[] made = new Pipeline[types.length * BINS];
            for (int category = 0; category < types.length; category++) {
                for (int bin = 0; bin < BIN_BLENDED; bin++) {
                    made[category * BINS + bin] = store.get(geometryKey(types[category], bin, role, path == 1));
                }
            }
            pipelines[path] = made;
        }

        Pipeline pipeline(boolean meshShading, int category, int bin) {
            int path = meshShading ? 1 : 0;
            if (pipelines[path] == null) {
                make(path);
            }
            return pipelines[path][category * BINS + bin];
        }
    }

    /**
     * Depth or IDs ({@code role}) by type with geometry hooks of lists split into categories, category c drawn as
     * {@code types[c]} (null: shared by every type without hooks).
     */
    @AllowAllocation("building the graph, when it changes")
    Geometry geometry(MaterialType[] types, int role, boolean meshShading) {
        if (role == IDS) {
            checkIds();
        }
        return new Geometry(types, role, meshShading);
    }

    /**
     * Draws {@code v}'s opaque raster bins into depth or IDs ({@code pipelines}' role) by category of its split lists,
     * into the rendering scope open on {@code commands}: an indirect draw per raster bin and category.
     */
    void drawGeometry(VkCommandBuffer commands, GpuScene scene, RenderView v, boolean meshShading, boolean late,
        Geometry pipelines) {
        int room = v.splitCategories();
        if (room < pipelines.categories()) {
            throw new IllegalStateException("View " + v.index() + "'s lists are split for " + room
                + " categories, not " + pipelines.categories());
        }
        try (MemoryStack stack = stackPush()) {
            long push = typedPush(stack, scene, v, late, 0, 0, 0, room);
            if (!meshShading) {
                Commands.bindIndexBuffer(commands, v.frameIndices().handle(), 0, VK_INDEX_TYPE_UINT32);
            }
            for (int bin = 0; bin < BIN_BLENDED; bin++) {
                for (int category = 0; category < pipelines.categories(); category++) {
                    drawTyped(commands, v, meshShading, push, bin, category, room,
                        pipelines.pipeline(meshShading, category, bin));
                }
            }
        }
    }

    /** The typed pipelines of {@code materials}' shading bins, as they are now (made where they are not yet). */
    @AllowAllocation("building the graph, when it changes")
    Typed typed(Materials materials, boolean meshShading) {
        return new Typed(materials, meshShading, false, true, ShadingHooks.Set.NONE);
    }

    /**
     * Like {@link #typed(Materials, boolean)}: with {@code surfaces} the opaque bins' pipelines write the surface
     * outputs too; without {@code opaque}, the blended bin's pipelines only (for a transparency pass); running the
     * shading hooks {@code hooks} (the view's {@link IndirectInputs.Set#hooks}).
     */
    @AllowAllocation("building the graph, when it changes")
    Typed typed(Materials materials, boolean meshShading, boolean surfaces, boolean opaque,
        ShadingHooks.Set hooks) {
        return new Typed(materials, meshShading, surfaces, opaque, hooks);
    }

    /**
     * Draws {@code v}'s opaque raster bins shaded by type ({@code pipelines}), from its split lists, into the rendering
     * scope open on {@code commands}: an indirect draw per raster bin and shading bin with a pipeline.
     */
    void drawTyped(VkCommandBuffer commands, GpuScene scene, RenderView v, boolean meshShading, boolean late,
        long clusters, long indirect, Typed pipelines) {
        int room = checkSplit(v, pipelines);
        try (MemoryStack stack = stackPush()) {
            long push = typedPush(stack, scene, v, late, clusters, 0, indirect, room);
            if (!meshShading) {
                Commands.bindIndexBuffer(commands, v.frameIndices().handle(), 0, VK_INDEX_TYPE_UINT32);
            }
            for (int bin = 0; bin < BIN_BLENDED; bin++) {
                for (int category = 0; category < pipelines.categories(); category++) {
                    drawTyped(commands, v, meshShading, push, bin, category, room,
                        pipelines.shaded(meshShading, category, bin));
                }
            }
        }
    }

    /**
     * Draws {@code v}'s blended bin, its lists both phases' (appended), shaded by type ({@code pipelines}) from its
     * split lists, into a transparency technique's attachments open on {@code commands}: weighted, into accumulated
     * color and revealage; as opaque, with the masked entry points (one-sided back faces discarded).
     */
    void drawBlendedTyped(VkCommandBuffer commands, GpuScene scene, RenderView v, boolean meshShading,
        BlendedTargets.Kind kind, long clusters, long behind, long indirect, Typed pipelines) {
        int room = checkSplit(v, pipelines);
        boolean weighted = kind == BlendedTargets.Kind.WEIGHTED;
        try (MemoryStack stack = stackPush()) {
            long push = typedPush(stack, scene, v, false, clusters, behind, indirect, room);
            if (!meshShading) {
                Commands.bindIndexBuffer(commands, v.frameIndices().handle(), 0, VK_INDEX_TYPE_UINT32);
            }
            if (kind == BlendedTargets.Kind.SORTED && v.sortSlots() > 0) {
                drawRuns(commands, v, meshShading, push, room, pipelines);
                return;
            }
            for (int category = 0; category < pipelines.categories(); category++) {
                drawTyped(commands, v, meshShading, push, BIN_BLENDED, category, room,
                    weighted ? pipelines.weighted(meshShading, category)
                        : kind == BlendedTargets.Kind.SORTED ? pipelines.over(meshShading, category)
                        : pipelines.shaded(meshShading, category, BIN_BLENDED));
            }
        }
    }

    /**
     * The blended bin sorted farthest first by culling ({@link RenderView#sortBlended}): run slot by run slot, the
     * entry of each drawn shading bin (one of them holds the slot's run, the others nothing), each drawn over the
     * scene's color with its pipeline, so the meshlets blend back to front across shading bins.
     */
    private static void drawRuns(VkCommandBuffer commands, RenderView v, boolean meshShading, long push, int room,
        Typed pipelines) {
        int slots = v.sortSlots();
        int drawn = v.sortCategories();
        int[] numbers = v.sortDrawn();
        for (int slot = 0; slot < slots; slot++) {
            for (int category = 0; category < Math.min(numbers.length, pipelines.categories()); category++) {
                int number = numbers[category];
                if (number >= 0) {
                    drawTyped(commands, v, meshShading, push, BIN_BLENDED, slot * drawn + number, room,
                        pipelines.over(meshShading, category));
                }
            }
        }
    }

    /** The room {@code v}'s split lists have, which must hold {@code pipelines}' shading bins. */
    private static int checkSplit(RenderView v, Typed pipelines) {
        int room = v.splitCategories();
        if (room < pipelines.categories()) {
            throw new IllegalStateException("View " + v.index() + "'s lists are split for " + room
                + " shading bins, not " + pipelines.categories());
        }
        return room;
    }

    /** The push block of typed draws of {@code v}, on {@code stack}; each draw sets its raster and shading bin. */
    private static long typedPush(MemoryStack stack, GpuScene scene, RenderView v, boolean late, long clusters,
        long behind, long indirect, int room) {
        long push = stack.ncalloc(8, 1, DRAW_PUSH);
        memPutLong(push + ForwardPush.VIEW, v.address());
        memPutLong(push + ForwardPush.SCENE, scene.tableAddress());
        memPutLong(push + ForwardPush.VISIBLE, v.visible().deviceAddress());
        memPutLong(push + ForwardPush.VISIBLE_COUNT, v.drawCount().deviceAddress());
        memPutLong(push + ForwardPush.LIGHTS, clusters);
        memPutInt(push + ForwardPush.LATE, late ? 1 : 0);
        memPutLong(push + ForwardPush.BACKGROUND, behind);
        memPutLong(push + ForwardPush.INDIRECT, indirect);
        memPutLong(push + ForwardPush.TYPED, v.split().deviceAddress());
        memPutInt(push + ForwardPush.CATEGORIES, room);
        return push;
    }

    /** One typed draw: raster bin {@code bin}'s meshlets of shading bin {@code category}, by its split list's draw. */
    private static void drawTyped(VkCommandBuffer commands, RenderView v, boolean meshShading, long push, int bin,
        int category, int room, Pipeline pipeline) {
        if (pipeline == null) {
            return; // no material of the shading bin draws in the raster bin
        }
        memPutInt(push + ForwardPush.BIN, bin);
        memPutInt(push + ForwardPush.CATEGORY, category);
        pipeline.bind(commands);
        pipeline.push(commands, push, DRAW_PUSH);
        long entry = 4L * ((long) (bin * room + category) * CommonConstants.SPLIT_WORDS);
        if (meshShading) {
            Commands.drawMeshTasksIndirect(commands, v.split().handle(),
                entry + 4L * CommonConstants.SPLIT_MESH_COMMAND, 1, 12);
        } else {
            Commands.drawIndexedIndirect(commands, v.split().handle(),
                entry + 4L * CommonConstants.SPLIT_DRAW_COMMAND, 1, 20);
        }
    }

    private static void drawMeshes(VkCommandBuffer commands, MemoryStack stack, GpuScene scene, RenderView v, int bin,
        boolean late, long count, Pipeline pipeline) {
        long push = stack.ncalloc(8, 1, DRAW_PUSH);
        memPutLong(push + ForwardPush.VIEW, v.address());
        memPutLong(push + ForwardPush.SCENE, scene.tableAddress());
        memPutLong(push + ForwardPush.VISIBLE, v.visible().deviceAddress());
        memPutLong(push + ForwardPush.VISIBLE_COUNT, count);
        memPutInt(push + ForwardPush.BIN, bin);
        memPutInt(push + ForwardPush.LATE, late ? 1 : 0);
        pipeline.bind(commands);
        pipeline.push(commands, push, DRAW_PUSH);
        Commands.drawMeshTasksIndirect(commands, v.expandCommand().handle(), 16L * bin, 1, 12);
    }

    private static void drawIndexed(VkCommandBuffer commands, MemoryStack stack, GpuScene scene, RenderView v, int bin,
        Pipeline pipeline) {
        long push = stack.ncalloc(8, 1, DRAW_PUSH);
        memPutLong(push + ForwardPush.VIEW, v.address());
        memPutLong(push + ForwardPush.SCENE, scene.tableAddress());
        memPutLong(push + ForwardPush.VISIBLE, v.visible().deviceAddress());
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

    /** Casters' depth: as {@link #depthOnly}, the masked bins keeping back faces. */
    private static Pipeline.Graphics shadowOnly(Pipeline.Graphics graphics, int bin, ShaderModule drawShader) {
        return masking(bin) ? graphics.stage(VK_SHADER_STAGE_FRAGMENT_BIT, drawShader, "maskedShadowMain")
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
