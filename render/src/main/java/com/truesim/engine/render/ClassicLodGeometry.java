package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.Clear;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Load;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Store;
import com.truesim.engine.render.layout.ClassicPush;
import com.truesim.engine.render.layout.CommonConstants;
import com.truesim.engine.render.layout.SelectPush;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.MeshLevel;
import java.lang.foreign.Arena;
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
    static final int CLASSIC_TRIANGLE = CommonConstants.CLASSIC_TRIANGLE;
    private static final int MAX_TRIANGLES = 1 << 24;
    private static final int MAX_SUBMESHES = 128;
    private static final int SELECT_PUSH = SelectPush.BYTES;
    private static final int DRAW_PUSH = ClassicPush.BYTES;
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
    // Per raster bin: shaded, depth only, the visibility buffer's IDs (null without primitive IDs).
    private final Pipeline[] draw;
    private final Pipeline[] drawDepth;
    private final Pipeline[] drawIds;
    private final Pipeline drawBlended; // the blended bin into weighted transparency's targets
    // Grown with the pool (indices, meshes) or as meshes come (levels, parts); replaced ones freed once no frame reads
    // them.
    private final VulkanDevice device;
    private final Retiring oldBuffers;
    private GpuBuffer indices;
    private GpuBuffer meshes;
    private GpuBuffer levels;
    private GpuBuffer parts;
    private int levelCount;
    private int partCount;
    private boolean closed;

    private static final int INDEX_USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
        | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    private static final int TABLE_USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

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
        pool.onRemove(forget);
        this.device = device;
        this.oldBuffers = new Retiring(pool.frameSlots());
        this.maxDraws = maxDraws;
        // The bins' counts, each bin's commands, then a record per draw slot of every bin.
        this.recordsOffset = (16 + (long) COMMAND_BYTES * maxDraws * MeshletRenderer.BINS + 15) & ~15L;
        try (ShaderModule selectShader = ShaderModule.load(device, "shaders/classic/select.spv");
             ShaderModule vertexShader = ShaderModule.load(device, "shaders/classic/draw.spv");
             ShaderModule shading = ShaderModule.load(device, "shaders/meshlets/draw.spv")) {
            select = Pipeline.compute(device, selectShader, "selectMain", SELECT_PUSH);
            // A pipeline per raster bin, as the meshlet paths have them: the double-sided and masked bins cull
            // nothing, the masked one discards what it masks (in depth only too).
            draw = new Pipeline[MeshletRenderer.BINS];
            drawDepth = new Pipeline[MeshletRenderer.BINS];
            drawIds = device.primitiveIds() ? new Pipeline[MeshletRenderer.BINS] : null;
            drawBlended = RasterPipelines.blended(raster(device, MeshletRenderer.BIN_BLENDED, vertexShader)
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shading, "blendedMain"), depthFormat);
            try (ShaderModule idsShader = device.primitiveIds() ? ShaderModule.load(device, "shaders/classic/ids.spv")
                : null) {
                for (int bin = 0; bin < MeshletRenderer.BINS; bin++) {
                    // The blended bin drawn as opaque takes the masked entry points: one-sided back faces discarded.
                    boolean masked = bin == MeshletRenderer.BIN_MASKED || bin == MeshletRenderer.BIN_BLENDED;
                    draw[bin] = raster(device, bin, vertexShader)
                        .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shading, masked ? "maskedMain" : "fragmentMain")
                        .color(colorFormat).depth(depthFormat).build(); // the meshlet paths' shading
                    Pipeline.Graphics depth = raster(device, bin, vertexShader).depth(depthFormat);
                    drawDepth[bin] = (masked ? depth.stage(VK_SHADER_STAGE_FRAGMENT_BIT, shading, "maskedDepthMain")
                        : depth).build();
                    if (idsShader != null) {
                        drawIds[bin] = raster(device, bin, vertexShader).stage(VK_SHADER_STAGE_FRAGMENT_BIT, idsShader,
                            masked ? "maskedClassicIdsMain" : "classicIdsMain")
                            .color(MeshletRenderer.ID_FORMAT).depth(depthFormat).build();
                    }
                }
            }
        }
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        // Laid out as the pool's meshlet triangles: mesh triangle t's indices at the mesh's index base + 3t.
        indices = GpuBuffer.deviceLocal(device, 12L * pool.capacity().triangles(),
            INDEX_USAGE);
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
        if (!pool.contains(mesh)) {
            throw new IllegalArgumentException("No mesh " + mesh + " in the pool");
        }
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
        coverPool();
        MemorySegment entry = meshes.memory().asSlice(8L * mesh, 8);
        if (entry.get(U32, 4) != 0) {
            throw new IllegalStateException("Mesh " + mesh + " is drawn the classic way already");
        }
        int neededParts = 0;
        for (MeshLevel level : data.levels()) {
            neededParts += partsOf(data, level, null, 0, 0, 0);
        }
        if (levelCount + data.levels().size() > levels.size() / 16) {
            levels = grownTable(levels, 16L * Math.max(2L * levelCount, levelCount + data.levels().size()));
        }
        if (partCount + neededParts > parts.size() / 16) {
            parts = grownTable(parts, 16L * Math.max(2L * partCount, partCount + neededParts));
        }
        int indexBase = pool.indexBase(mesh);
        long indexEnd = 4L * (indexBase + 3L * data.triangleCount());
        if (indexEnd > indices.size()) {
            growIndices(Math.max(12L * pool.capacity().triangles(), indexEnd));
        }
        try (Arena arena = Arena.ofConfined()) { // the indices only until copied
            indices.write(4L * indexBase, data.expandIndices(arena));
        }
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

    private final java.util.function.IntConsumer forget = this::forget;

    /**
     * Mesh {@code mesh} leaves the pool: no longer drawn the classic way, so its number, reused, starts clean. Its levels'
     * and parts' room stays taken until the geometry closes (they are appended, not reused).
     */
    private synchronized void forget(int mesh) {
        if (8L * mesh < meshes.size()) {
            meshes.memory().asSlice(8L * mesh, 8).fill((byte) 0);
        }
    }

    /** Grows the per-mesh table to the pool's mesh numbers, for any instance's mesh to be looked up. */
    private synchronized void coverPool() {
        if (meshes.size() < 8L * pool.capacity().meshes()) {
            meshes = grownTable(meshes, 8L * pool.capacity().meshes());
        }
    }

    /** A host-visible table of {@code bytes} holding {@code old}'s contents, the rest zero. */
    private GpuBuffer grownTable(GpuBuffer old, long bytes) {
        GpuBuffer grown = GpuBuffer.hostVisible(device, bytes, TABLE_USAGE);
        MemorySegment.copy(old.memory(), 0, grown.memory(), 0, old.size());
        grown.memory().asSlice(old.size()).fill((byte) 0);
        oldBuffers.add(old); // frames in flight read it; those recorded from now on read the new one
        return grown;
    }

    /** The index buffer at {@code bytes}, its indices copied over: waits, as adding does. */
    private void growIndices(long bytes) {
        GpuBuffer old = indices;
        GpuBuffer grown = GpuBuffer.deviceLocal(device, bytes, INDEX_USAGE);
        device.submitAndWait(device.graphics(), commands -> {
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
            try (MemoryStack stack = stackPush()) {
                long region = stack.ncalloc(org.lwjgl.vulkan.VkBufferCopy.ALIGNOF, 1,
                    org.lwjgl.vulkan.VkBufferCopy.SIZEOF);
                memPutLong(region + org.lwjgl.vulkan.VkBufferCopy.SIZE, old.size());
                Commands.copyBuffer(commands, old.handle(), grown.handle(), 1, region);
            }
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        });
        indices = grown;
        oldBuffers.add(old);
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
    @AllowAllocation("building the graph, when it changes")
    public void drawFirst(GraphBuilder g, SceneDraw draw, SceneDraw.Targets targets, boolean load) {
        SurfaceOutput.Kind kind = draw.output().kind();
        if (kind == SurfaceOutput.Kind.IDS && drawIds == null) {
            throw new IllegalStateException("The device has no primitive IDs in fragment shaders (geometryShader)");
        }
        RenderView view = draw.view();
        Camera camera = draw.camera();
        String name = switch (kind) {
            case DEPTH -> "classic depth, view " + view.index();
            case SHADED -> "classic LOD";
            case IDS -> "classic LOD ids";
        };
        Buffer list = g.request(new Selection(this, view, camera, targets.region()));
        SceneData.Buffers scene = g.request(new SceneData(renderer));
        GraphBuilder.PassBuilder pass = scene.read(targets.inRegion(g.pass(name)), BufferAccess.DRAW_READ)
            .read(list, BufferAccess.INDIRECT)
            .read(list, BufferAccess.DRAW_READ);
        if (targets.color() != null) {
            if (load) {
                pass.attach(targets.color(), Load.LOAD, Store.STORE);
            } else {
                pass.attach(targets.color(), Clear.color(kind == SurfaceOutput.Kind.SHADED ? draw.output().clear()
                    : NO_COLOR));
            }
        }
        if (load) {
            pass.attachDepth(targets.depth(), Load.LOAD, Store.STORE);
        } else {
            pass.attachDepth(targets.depth(), Clear.FAR);
        }
        Buffer lights = targets.lights();
        if (lights != null) {
            pass.read(lights, BufferAccess.DRAW_READ);
        }
        ShadowInputs.Set shadows = targets.shadows();
        if (shadows != null) {
            shadows.readBy(pass, Access.SAMPLED_FRAGMENT, BufferAccess.DRAW_READ);
        }
        Pipeline[] pipeline = switch (kind) {
            case DEPTH -> drawDepth;
            case SHADED -> this.draw;
            case IDS -> drawIds;
        };
        pass.record(ctx -> {
            if (shadows != null) {
                shadows.prepare(ctx);
            }
            record(ctx, pipeline, view, lights == null ? 0 : ctx.address(lights), list);
        });
    }

    /**
     * View {@code view}'s draw list for a frame: reset, then selected (each part's detail level and raster bin) on the
     * GPU, the camera set first where given (a light's), the detail for {@code region}'s render scale where given. A
     * request: the opaque draw and the blended one of a view share one selection.
     */
    record Selection(ClassicLodGeometry geometry, RenderView view, Camera camera,
        com.truesim.engine.graph.ViewPort region) implements com.truesim.engine.graph.Request<Buffer> {

        @Override
        @AllowAllocation("building the graph, when it changes")
        public Buffer build(GraphBuilder g) {
            String name = "classic LOD, view " + view.index();
            Buffer list = g.createBuffer(name + " draws", geometry.recordsOffset + (long) RECORD_BYTES
                * geometry.maxDraws * MeshletRenderer.BINS);
            g.pass(name + " reset").write(list, BufferAccess.TRANSFER_DST)
                .record(ctx -> Commands.fillBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(list), 0, 16, 0));
            SceneData.Buffers scene = g.request(new SceneData(geometry.renderer));
            float casterDetail = camera != null
                ? g.setting(com.truesim.engine.render.shadows.ShadowSettings.CASTER_DETAIL) : 1;
            scene.read(g.pass(name + " selection"), BufferAccess.STORAGE_READ)
                .write(list, BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (camera != null) {
                        view.camera(camera, casterDetail); // the view's mode kept
                    } else if (region != null) {
                        view.detail(region.renderScale()); // detail for the size it renders this frame
                    }
                    geometry.select(ctx.unsafe().commands(), view, ctx.address(list));
                });
            return list;
        }
    }

    /**
     * The blended bin of the view's draw list (the selection its opaque draw requested: equal requests share it), into
     * the targets as their kind says.
     */
    @Override
    @AllowAllocation("building the graph, when it changes")
    public void drawBlended(GraphBuilder g, RenderView view, BlendedTargets targets, boolean load) {
        Buffer list = g.request(new Selection(this, view, null, targets.region()));
        SceneData.Buffers scene = g.request(new SceneData(renderer));
        GraphBuilder.PassBuilder pass = scene.read(targets.inRegion(g.pass("classic LOD blended")),
            BufferAccess.DRAW_READ).read(list, BufferAccess.INDIRECT).read(list, BufferAccess.DRAW_READ);
        boolean weighted = targets.kind() == BlendedTargets.Kind.WEIGHTED;
        if (weighted) {
            if (load) {
                pass.attach(targets.accumulated(), Load.LOAD, Store.STORE).attach(targets.revealage(), Load.LOAD,
                    Store.STORE);
            } else {
                pass.attach(targets.accumulated(), Clear.color(0, 0, 0, 0)).attach(targets.revealage(),
                    Clear.color(1, 1, 1, 1));
            }
            pass.attachDepthReadOnly(targets.depth());
        } else {
            pass.attach(targets.color(), Load.LOAD, Store.STORE).attachDepth(targets.depth(), Load.LOAD, Store.STORE);
        }
        Buffer lights = targets.lights();
        if (lights != null) {
            pass.read(lights, BufferAccess.DRAW_READ);
        }
        ShadowInputs.Set shadows = targets.shadows();
        if (shadows != null) {
            shadows.readBy(pass, Access.SAMPLED_FRAGMENT, BufferAccess.DRAW_READ);
        }
        targets.readBehind(pass);
        com.truesim.engine.graph.Image behind = targets.behind();
        Pipeline pipeline = weighted ? drawBlended : draw[MeshletRenderer.BIN_BLENDED];
        pass.record(ctx -> {
            if (shadows != null) {
                shadows.prepare(ctx);
            }
            recordBin(ctx, pipeline, MeshletRenderer.BIN_BLENDED, view, lights == null ? 0 : ctx.address(lights),
                list, SceneBehind.words(ctx, behind));
        });
    }

    /** Selects view {@code view}'s draws into the draw list at {@code list}. */
    private void select(VkCommandBuffer commands, RenderView view, long list) {
        view.refresh(); // from where its camera is now
        GpuScene scene = renderer.scene();
        scene.upload(commands);
        coverPool();
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, SELECT_PUSH);
            memPutLong(push + SelectPush.VIEW, view.address());
            memPutLong(push + SelectPush.MESHES, pool.meshTable().deviceAddress());
            memPutLong(push + SelectPush.INSTANCES, scene.instanceAddress());
            memPutLong(push + SelectPush.CLASSIC_MESHES, meshes.deviceAddress());
            memPutLong(push + SelectPush.LEVELS, levels.deviceAddress());
            memPutLong(push + SelectPush.PARTS, parts.deviceAddress());
            memPutLong(push + SelectPush.DRAWS, list);
            memPutLong(push + SelectPush.RECORDS, list + recordsOffset);
            memPutInt(push + SelectPush.INSTANCE_COUNT, scene.highWater());
            memPutInt(push + SelectPush.MAX_DRAWS, maxDraws);
            memPutLong(push + SelectPush.MATERIALS, scene.materialsAddress()); // each part's raster bin
            select.bind(commands);
            select.push(commands, push, SELECT_PUSH);
            Commands.dispatch(commands, (scene.highWater() + 63) / 64, 1, 1);
        }
    }

    /** A graphics pipeline of raster bin {@code bin}: the opaque one culls back faces, the others nothing. */
    private static Pipeline.Graphics raster(VulkanDevice device, int bin, ShaderModule vertexShader) {
        Pipeline.Graphics graphics = Pipeline.graphics(device)
            .stage(VK_SHADER_STAGE_VERTEX_BIT, vertexShader, "classicVertexMain").pushConstants(DRAW_PUSH);
        return bin == MeshletRenderer.BIN_OPAQUE ? graphics : graphics.doubleSided();
    }

    /** Draws the list's draws of view {@code view}, each opaque bin with its {@code pipeline}, into the attachments. */
    private void record(PassContext ctx, Pipeline[] pipeline, RenderView view, long lights, Buffer list) {
        for (int bin = 0; bin < MeshletRenderer.BIN_BLENDED; bin++) {
            recordBin(ctx, pipeline[bin], bin, view, lights, list, 0);
        }
    }

    /**
     * Draws the list's draws of view {@code view} in bin {@code bin} with {@code pipeline}, transmissive surfaces showing
     * the scene behind of push words {@code behind} ({@link SceneBehind}; 0: none).
     */
    private void recordBin(PassContext ctx, Pipeline pipeline, int bin, RenderView view, long lights, Buffer list,
        long behind) {
        VkCommandBuffer commands = ctx.unsafe().commands();
        long handle = ctx.unsafe().buffer(list);
        try (MemoryStack stack = stackPush()) {
            long push = stack.ncalloc(8, 1, DRAW_PUSH);
            memPutLong(push + ClassicPush.VIEW, view.address());
            memPutLong(push + ClassicPush.MESHES, pool.meshTable().deviceAddress());
            memPutLong(push + ClassicPush.INSTANCES, renderer.scene().instanceAddress());
            memPutLong(push + ClassicPush.POSITIONS, pool.positions().deviceAddress());
            memPutLong(push + ClassicPush.ATTRIBUTES, pool.attributes().deviceAddress());
            memPutLong(push + ClassicPush.RECORDS, ctx.address(list) + recordsOffset);
            memPutLong(push + ClassicPush.MATERIALS, renderer.scene().materialsAddress());
            memPutLong(push + ClassicPush.LIGHTS, lights);
            memPutLong(push + ClassicPush.BACKGROUND, behind);
            Commands.bindIndexBuffer(commands, indices.handle(), 0, VK_INDEX_TYPE_UINT32);
            pipeline.bind(commands);
            pipeline.push(commands, push, DRAW_PUSH);
            Commands.drawIndexedIndirectCount(commands, handle, 16 + (long) COMMAND_BYTES * maxDraws * bin, handle,
                4L * bin, maxDraws, COMMAND_BYTES);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            select.close();
            drawBlended.close();
            for (int bin = 0; bin < MeshletRenderer.BINS; bin++) {
                draw[bin].close();
                drawDepth[bin].close();
            }
            if (drawIds != null) {
                for (Pipeline pipeline : drawIds) {
                    pipeline.close();
                }
            }
            pool.stopFollowing(forget);
            indices.close();
            meshes.close();
            levels.close();
            parts.close();
            oldBuffers.close();
        }
    }
}
