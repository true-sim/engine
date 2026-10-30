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
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
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
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_INFO;
import static org.lwjgl.vulkan.VK13.nvkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.layout.CommonConstants;
import com.truesim.engine.render.layout.CullPush;
import com.truesim.engine.render.layout.DrawPush;
import com.truesim.engine.render.layout.ExpandPush;
import com.truesim.engine.render.layout.InstancePush;
import com.truesim.engine.render.layout.MeshPush;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.Bindless;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

/**
 * Draws a {@link GpuScene}'s instances GPU-driven, from any number of views ({@link #createView}: cameras, shadow
 * maps, mirrors). An instance pass tests every instance's bounds for every view culled and splits each visible one into
 * chunks of 64 meshlets; a compute workgroup per chunk culls its meshlets (cluster LOD for the allowed screen error,
 * frustum, normal cone) and lists the visible ones in its view's lists, which are drawn on one of two paths over the
 * same data and shaders:
 *
 * <ul>
 *   <li>{@link Path#MESH_SHADERS}: a mesh workgroup per listed meshlet emits its vertices and triangles; where the
 *       device has mesh shaders. (Culling in task shaders instead left most lanes idle when instances need few
 *       meshlets: 0.95 ms against 0.59 ms for 30,000 rocks.)</li>
 *   <li>{@link Path#INDEXED}: an expansion pass writes the listed meshlets' triangles into the view's index buffer,
 *       and one indexed draw draws them all; on every device.</li>
 * </ul>
 *
 * <pre>{@code
 * GpuScene scene = new GpuScene(device, pool, 100_000, slots);
 * MeshletRenderer renderer = new MeshletRenderer(device, scene, colorFormat, depthFormat, slots);
 * RenderView camera = renderer.createView(ViewOptions.of(1 << 18, 1 << 22));
 * scene.set(0, car, 0, x, y, z, qx, qy, qz, qw, 1, 1, 1);                   // world position, in doubles
 * camera.set(eye.position(cx, cy, cz), 1.0f, MeshletRenderer.MODE_LIT);   // 1 pixel of error
 * renderer.record(commands, camera, colorView, depthView, width, height, 0, 0, 0);
 * }</pre>
 *
 * <p>Each view draws the instances relative to its camera's world position, so a moving camera rewrites only its view.
 * The CPU writes only instances and views: its cost does not depend on how many meshlets there are. Recording
 * allocates nothing.
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

    /** Chunks of 64 meshlets the views culled together share: the guaranteed dispatch size. */
    public static final int MAX_CHUNKS = 65_535;
    /** VK_PIPELINE_STAGE_2_MESH_SHADER_BIT_EXT: the same bit as the 32-bit stage. */
    private static final long MESH_SHADER_STAGE = VK_PIPELINE_STAGE_MESH_SHADER_BIT_EXT;
    /** Raster bins, as meshlets/common.slang has them: opaque, double-sided, masked. */
    static final int BINS = CommonConstants.BINS;
    static final int BIN_OPAQUE = CommonConstants.BIN_OPAQUE;
    static final int BIN_DOUBLE_SIDED = CommonConstants.BIN_DOUBLE_SIDED;
    static final int BIN_MASKED = CommonConstants.BIN_MASKED;
    private static final int PHASE_ALL = CommonConstants.PHASE_ALL;
    private static final int PHASE_EARLY = CommonConstants.PHASE_EARLY;
    private static final int PHASE_LATE = CommonConstants.PHASE_LATE;
    /** Rows of one instance dispatch: the guaranteed workgroup count. */
    private static final int MAX_ROWS = 65_535;
    /** Rows one buffer update writes: 8 bytes each, a few KiB of the stack. */
    private static final int ROWS_PER_UPDATE = 512;
    /** Both draw paths' pipelines take the larger push block: the mesh shaders', which the indexed one starts like. */
    private static final int DRAW_PUSH = MeshPush.BYTES;
    // The shaders' Occlusion struct, the same in the instance and cull pushes: its fields from its start.
    private static final int OCCLUSION_WIDTH = InstancePush.OCCLUSION_DEPTH_WIDTH - InstancePush.OCCLUSION;
    private static final int OCCLUSION_HEIGHT = InstancePush.OCCLUSION_DEPTH_HEIGHT - InstancePush.OCCLUSION;
    private static final int OCCLUSION_MIPS = InstancePush.OCCLUSION_MIPS - InstancePush.OCCLUSION;
    /** The visibility buffer's IDs: instance + 1 (0: nothing), and meshlet << 7 | triangle. */
    public static final int ID_FORMAT = org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_UINT;

    private final VulkanDevice device;
    private final GpuScene scene;
    private final GeometryPool pool;
    private final Bindless bindless;
    private final FrameSlots slots;
    private final int maxChunks;
    private final Pipeline instancesPass;
    private final Pipeline cull;
    private final Pipeline expand;
    // Per raster bin (BIN_*), the draws: shaded, depth only, the visibility buffer's IDs (null without primitive
    // IDs), on the indexed path and on mesh shaders (null without).
    private final Pipeline[] draw;
    private final Pipeline[] meshDraw;
    private final Pipeline[] drawDepth;
    private final Pipeline[] meshDepth;
    private final Pipeline[] idsDraw;
    private final Pipeline[] idsMesh;
    private final GpuBuffer chunks;
    private final GpuBuffer chunkCommand;
    private final List<RenderView> views = new ArrayList<>();
    private final java.util.BitSet indices = new java.util.BitSet(); // those of the open views
    // The views' addresses, by index, for the culling passes; and the rows the instance pass culls (a view and its
    // phase each). Both grow as views are made; the old ones go once no frame in flight reads them.
    private FrameBuffer viewTable;
    private GpuBuffer rows;
    private final Retiring retiring; // the old tables, rows and view lists, until no frame reads them
    // Read only: a frame graph may record passes on several threads at once, so recording changes no field.
    private static final boolean[] NOT_EARLY = {false};
    private static final boolean[] EARLY = {true};
    private Path path = Path.AUTO;
    private boolean closed;

    /** A renderer of {@code scene} into {@code colorFormat} and {@code depthFormat} attachments, one copy of its views. */
    public MeshletRenderer(VulkanDevice device, GpuScene scene, int colorFormat, int depthFormat) {
        this(device, scene, colorFormat, depthFormat, FrameSlots.single());
    }

    /**
     * A renderer whose views keep a copy per frame of {@code slots} (a frame graph's, as {@code scene}'s), so they may
     * change every frame while earlier frames are still drawn.
     */
    public MeshletRenderer(VulkanDevice device, GpuScene scene, int colorFormat, int depthFormat, FrameSlots slots) {
        this(device, scene, colorFormat, depthFormat, slots, MAX_CHUNKS);
    }

    /** Like the others, with room for {@code chunks} chunks of 64 meshlets shared by views culled together. */
    public MeshletRenderer(VulkanDevice device, GpuScene scene, int colorFormat, int depthFormat, FrameSlots slots,
        int chunks) {
        if (chunks < 1 || chunks > MAX_CHUNKS) {
            throw new IllegalArgumentException("1 to 65,535 chunks: " + chunks);
        }
        this.device = device;
        this.scene = Objects.requireNonNull(scene, "scene");
        this.pool = scene.pool();
        this.bindless = device.bindless();
        this.slots = Objects.requireNonNull(slots, "slots");
        this.maxChunks = chunks;
        this.retiring = new Retiring(slots);
        try (ShaderModule instanceShader = ShaderModule.load(device, "shaders/meshlets/instances.spv");
             ShaderModule cullShader = ShaderModule.load(device, "shaders/meshlets/cull.spv");
             ShaderModule expandShader = ShaderModule.load(device, "shaders/meshlets/expand.spv");
             ShaderModule drawShader = ShaderModule.load(device, "shaders/meshlets/draw.spv")) {
            instancesPass = Pipeline.compute(device, instanceShader, "instancesMain", InstancePush.BYTES);
            cull = Pipeline.compute(device, cullShader, "cullMain", CullPush.BYTES);
            expand = Pipeline.compute(device, expandShader, "expandMain", ExpandPush.BYTES);
            ShaderModule idsShader = device.primitiveIds() ? ShaderModule.load(device, "shaders/visibility/ids.spv")
                : null;
            // A pipeline per raster bin and role: the opaque bin culls back faces and discards nothing; the
            // double-sided one culls nothing; the masked one culls nothing and discards (masked texels, one-sided
            // materials' back faces), depth only too, so shadow maps mask what the camera does.
            draw = bins(bin -> raster(bin, drawShader, VK_SHADER_STAGE_VERTEX_BIT, "vertexMain")
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT, drawShader, bin == BIN_MASKED ? "maskedMain" : "fragmentMain")
                .color(colorFormat).depth(depthFormat).build());
            drawDepth = bins(bin -> depthOnly(raster(bin, drawShader, VK_SHADER_STAGE_VERTEX_BIT, "vertexMain"), bin,
                drawShader).depth(depthFormat).build());
            idsDraw = idsShader == null ? null : bins(bin -> raster(bin, drawShader, VK_SHADER_STAGE_VERTEX_BIT,
                "vertexMain").stage(VK_SHADER_STAGE_FRAGMENT_BIT, idsShader, bin == BIN_MASKED ? "maskedIdsMain"
                : "idsMain").color(ID_FORMAT).depth(depthFormat).build());
            if (device.meshShaders()) {
                try (ShaderModule meshShader = ShaderModule.load(device, "shaders/meshlets/mesh.spv")) {
                    meshDraw = bins(bin -> raster(bin, meshShader, VK_SHADER_STAGE_MESH_BIT_EXT, "meshMain")
                        .stage(VK_SHADER_STAGE_FRAGMENT_BIT, drawShader, bin == BIN_MASKED ? "maskedMain"
                            : "fragmentMain") // the same shading
                        .color(colorFormat).depth(depthFormat).build());
                    meshDepth = bins(bin -> depthOnly(raster(bin, meshShader, VK_SHADER_STAGE_MESH_BIT_EXT,
                        "meshMain"), bin, drawShader).depth(depthFormat).build());
                    idsMesh = idsShader == null ? null : bins(bin -> raster(bin, meshShader,
                        VK_SHADER_STAGE_MESH_BIT_EXT, "meshMain").stage(VK_SHADER_STAGE_FRAGMENT_BIT, idsShader,
                            bin == BIN_MASKED ? "maskedIdsMain" : "idsMain").color(ID_FORMAT).depth(depthFormat)
                        .build());
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
        this.chunks = GpuBuffer.deviceLocal(device, 16L * chunks, storage);
        chunkCommand = GpuBuffer.deviceLocal(device, 16,
            storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        viewTable = FrameBuffer.create(device, 8L * 4, storage, slots);
        rows = GpuBuffer.deviceLocal(device, 8L * 4, storage | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    /**
     * A new view of the scene, with lists and history as {@code options} say: as many as the frame needs (cameras,
     * shadow maps, mirrors), each at the lowest index free. Views live until closed, or until the renderer closes.
     */
    public RenderView createView(ViewOptions options) {
        checkOpen();
        int index = indices.nextClearBit(0);
        if (8L * (index + 1) > viewTable.size()) {
            grow(2 * (index + 1));
        }
        RenderView view = new RenderView(device, this, options, index, slots);
        if (path() == Path.INDEXED) {
            view.frameIndices(); // the indexed path draws it; on mesh shaders no view has one
        }
        views.add(view);
        indices.set(index);
        viewTable.putLong(8L * index, view.address());
        return view;
    }

    /**
     * Takes {@code view} (closed) out: its index is free for the next view, and its buffers go once no frame in flight
     * reads them.
     */
    @AllowAllocation("closing a view")
    void removeView(RenderView view) {
        views.remove(view);
        indices.clear(view.index());
        viewTable.putLong(8L * view.index(), 0);
        view.freeBuffers(retiring);
    }

    /** Makes room for {@code capacity} views: a new table and rows, the old ones kept until no frame reads them. */
    private void grow(int capacity) {
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        retire(viewTable);
        retire(rows);
        viewTable = FrameBuffer.create(device, 8L * capacity, storage, slots);
        for (RenderView view : views) {
            viewTable.putLong(8L * view.index(), view.address());
        }
        rows = GpuBuffer.deviceLocal(device, 8L * capacity, storage | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    /** Has {@code resource} freed once no frame in flight reads it. */
    void retire(AutoCloseable resource) {
        retiring.add(resource);
    }

    /** The views open, in the order they were made. */
    public List<RenderView> views() {
        return java.util.Collections.unmodifiableList(views);
    }

    /**
     * Chooses how meshlets are drawn; {@link Path#AUTO} by default. On mesh shaders the views' index buffers go (once
     * no frame reads them): only the indexed path has them, made as it first draws a view.
     */
    public void setPath(Path requested) {
        if (requested == Path.MESH_SHADERS && meshDraw == null) {
            throw new IllegalStateException("The device has no mesh shaders");
        }
        this.path = Objects.requireNonNull(requested, "path");
        if (path() == Path.MESH_SHADERS) {
            for (RenderView view : views) {
                view.dropIndices();
            }
        }
    }

    /** The GPU memory of all views ({@link RenderView#bytes}), in bytes. */
    public long viewBytes() {
        long bytes = 0;
        for (RenderView view : views) {
            bytes += view.bytes();
        }
        return bytes;
    }

    /** The path the next frame takes. */
    public Path path() {
        if (path == Path.AUTO) {
            return meshDraw != null && pool.fitsMeshShaders() ? Path.MESH_SHADERS : Path.INDEXED;
        }
        return path;
    }

    /**
     * Records {@code view}'s frame: the instance pass, culling and drawing into {@code colorView} and {@code depthView}
     * ({@code width} by {@code height}, in color and depth attachment layouts), cleared to ({@code r}, {@code g},
     * {@code b}) and depth 0. Allocates nothing.
     */
    public void record(VkCommandBuffer commands, RenderView view, long colorView, long depthView, int width,
        int height, float r, float g, float b) {
        cullView(commands, view, false);
        drawView(commands, view, colorView, depthView, width, height, r, g, b);
    }

    /** Culls {@code view} alone, in the early phase of occlusion culling or not. */
    private void cullView(VkCommandBuffer commands, RenderView view, boolean earlyPhase) {
        cullViews(commands, own(view).alone, earlyPhase ? EARLY : NOT_EARLY, 1);
    }

    /**
     * Draws {@code view}'s depth only (no color, no shading) into {@code depthView}, cleared to 0: e.g. a shadow map
     * from a light's orthographic view. Allocates nothing.
     */
    public void recordDepth(VkCommandBuffer commands, RenderView view, long depthView, int width, int height) {
        cullView(commands, view, false);
        drawView(commands, view, 0, depthView, width, height, 0, 0, 0);
    }

    /**
     * The early phase of two-phase occlusion culling of {@code view} (made with occlusion): like {@link #record}, but
     * only the instances it saw the last time {@link #recordLate} looked. Then build a depth pyramid of the depth
     * (e.g. engine-render's {@code DepthPyramid}) and record the late phase. Allocates nothing.
     */
    public void recordEarly(VkCommandBuffer commands, RenderView view, long colorView, long depthView, int width,
        int height, float r, float g, float b) {
        cullView(commands, view, true);
        drawView(commands, view, colorView, depthView, width, height, r, g, b);
    }

    /**
     * The late phase: tests every instance in {@code view}, and the meshlets of those newly visible, against the depth
     * pyramid of what the early phase drew (sampled slot {@code pyramid}, {@code mips} levels, made from the
     * {@code width} by {@code height} depth), and draws over the early phase's image (in the attachment layouts again)
     * what became visible. What is hidden now is not drawn early next frame; what appears is drawn late the same
     * frame, so nothing pops in. Allocates nothing.
     */
    public void recordLate(VkCommandBuffer commands, RenderView view, long colorView, long depthView, int width,
        int height, int pyramid, int mips) {
        recordLate(commands, view, colorView, depthView, width, height, pyramid, mips, 0);
    }

    /** Like {@link #recordLate(VkCommandBuffer, RenderView, long, long, int, int, int, int)}, lit by {@code lights}. */
    public void recordLate(VkCommandBuffer commands, RenderView view, long colorView, long depthView, int width,
        int height, int pyramid, int mips, long clusters) {
        cullLate(commands, view, pyramid, width, height, mips);
        boolean depthOnly = colorView == 0;
        draw(commands, view, true, colorView, depthView, width, height, 0, 0, 0, clusters,
            depthOnly ? drawDepth : draw, depthOnly ? meshDepth : meshDraw);
    }

    private void cullLate(VkCommandBuffer commands, RenderView view, int pyramid, int width, int height, int mips) {
        if (!own(view).hasHistory()) {
            throw new IllegalArgumentException("View " + view.index() + " culls no occlusion: made without it");
        }
        cull(commands, view.alone, null, 1, pyramid, width, height, mips);
    }

    /**
     * Culls {@code views[0]} to {@code views[count - 1]} together, however many: one instance pass and one cull pass for
     * them all, each view's meshlets into its own lists, for {@link #drawView}. Views with {@code early[i]} (made with
     * occlusion) run the early phase of occlusion culling ({@link #recordEarly}), each with its own history; the
     * others draw everything in view. Chunks are shared by the views. Allocates nothing.
     */
    public void cullViews(VkCommandBuffer commands, RenderView[] views, boolean[] early, int count) {
        if (count < 1 || count > views.length || count > early.length) {
            throw new IllegalArgumentException("Views to cull: 1 or more; " + count + " given");
        }
        try {
            for (int i = 0; i < count; i++) {
                RenderView view = own(views[i]);
                if (early[i] && !view.hasHistory()) {
                    throw new IllegalArgumentException("View " + view.index() + " culls no occlusion: made without it");
                }
                if (view.marked) {
                    throw new IllegalArgumentException("View " + view.index() + " is culled twice at once");
                }
                view.marked = true;
            }
        } finally {
            for (int i = 0; i < count; i++) {
                views[i].marked = false;
            }
        }
        cull(commands, views, early, count, 0, 0, 0, 0);
    }

    /** View {@code i}'s phase in a cull: late without {@code early}, else early or all. */
    private static int phase(boolean[] early, int i) {
        return early == null ? PHASE_LATE : early[i] ? PHASE_EARLY : PHASE_ALL;
    }

    private RenderView own(RenderView view) {
        if (view.renderer() != this) {
            throw new IllegalArgumentException("A view of another renderer");
        }
        view.checkOpen();
        return view;
    }

    /**
     * Draws {@code view}, as its early (or only) phase culled it, into {@code colorView} and {@code depthView}
     * ({@code width} by {@code height}, in the attachment layouts), cleared to ({@code r}, {@code g}, {@code b}) and
     * depth 0. The late phase draws itself ({@link #recordLate}). With {@code colorView} 0, depth only (no shading): a
     * shadow map, say. Allocates nothing.
     */
    public void drawView(VkCommandBuffer commands, RenderView view, long colorView, long depthView, int width,
        int height, float r, float g, float b) {
        drawView(commands, view, colorView, depthView, width, height, r, g, b, 0);
    }

    /**
     * Like {@link #drawView(VkCommandBuffer, RenderView, long, long, int, int, float, float, float)}, shaded with the
     * lights of {@code clusters}: the device address of the frame's light list ({@link Keys#LIGHT_LIST}; 0: the sun
     * alone).
     */
    public void drawView(VkCommandBuffer commands, RenderView view, long colorView, long depthView, int width,
        int height, float r, float g, float b, long clusters) {
        boolean depthOnly = colorView == 0;
        draw(commands, own(view), false, colorView, depthView, width, height, r, g, b, clusters,
            depthOnly ? drawDepth : draw, depthOnly ? meshDepth : meshDraw);
    }

    /**
     * Draws {@code view}, as its early (or only) phase culled it, into a visibility buffer: {@code idView}
     * ({@link #ID_FORMAT}) gets what each pixel shows, cleared to 0 (nothing), and {@code depthView} its depth. A
     * shading pass then shades each pixel once. Needs {@link VulkanDevice#primitiveIds()}. Allocates nothing.
     */
    public void drawIds(VkCommandBuffer commands, RenderView view, long idView, long depthView, int width,
        int height) {
        if (idsDraw == null) {
            throw new IllegalStateException("The device has no primitive IDs in fragment shaders (geometryShader)");
        }
        draw(commands, own(view), false, idView, depthView, width, height, 0, 0, 0, 0, idsDraw, idsMesh);
    }

    /** The late phase ({@link #recordLate}) into a visibility buffer ({@link #drawIds}). */
    public void recordLateIds(VkCommandBuffer commands, RenderView view, long idView, long depthView, int width,
        int height, int pyramid, int mips) {
        if (idsDraw == null) {
            throw new IllegalStateException("The device has no primitive IDs in fragment shaders (geometryShader)");
        }
        cullLate(commands, view, pyramid, width, height, mips);
        draw(commands, view, true, idView, depthView, width, height, 0, 0, 0, 0, idsDraw, idsMesh);
    }

    /** A graphics pipeline of raster bin {@code bin} drawing with {@code entry} of {@code shader}'s {@code stage}. */
    private Pipeline.Graphics raster(int bin, ShaderModule shader, int stage, String entry) {
        Pipeline.Graphics graphics = Pipeline.graphics(device).stage(stage, shader, entry).pushConstants(DRAW_PUSH);
        return bin == BIN_OPAQUE ? graphics : graphics.doubleSided(); // the masked bin culls one-sided back faces itself
    }

    /** Depth only: no fragment stage but the masked bin's, which discards what it masks. */
    private static Pipeline.Graphics depthOnly(Pipeline.Graphics graphics, int bin, ShaderModule drawShader) {
        return bin == BIN_MASKED ? graphics.stage(VK_SHADER_STAGE_FRAGMENT_BIT, drawShader, "maskedDepthMain")
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

    /**
     * Draws {@code v}'s raster bins, each with its pipeline of {@code indexed} or {@code mesh}: the late phase's lists
     * over the attachments with {@code late}, else the early (or only) phase's into cleared ones.
     */
    private void draw(VkCommandBuffer commands, RenderView v, boolean late, long colorView, long depthView, int width,
        int height, float r, float g, float b, long clusters, Pipeline[] indexed, Pipeline[] mesh) {
        long count = v.drawCount().deviceAddress();
        try (MemoryStack stack = stackPush()) {
            beginRendering(commands, stack, colorView, depthView, width, height, r, g, b, late);
            setViewport(commands, stack, width, height);
            for (int bin = 0; bin < BINS; bin++) {
                drawBin(commands, stack, v, bin, late, count, clusters, indexed[bin], mesh == null ? null : mesh[bin]);
            }
            vkCmdEndRendering(commands);
        }
    }

    private void drawBin(VkCommandBuffer commands, MemoryStack stack, RenderView v, int bin, boolean late, long count,
        long clusters, Pipeline indexed, Pipeline mesh) {
        if (path() == Path.MESH_SHADERS) {
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
            mesh.bind(commands);
            mesh.push(commands, push, DRAW_PUSH);
            vkCmdDrawMeshTasksIndirectEXT(commands, v.expandCommand().handle(), 16L * bin, 1, 12);
        } else {
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
            indexed.bind(commands);
            indexed.push(commands, push, DRAW_PUSH);
            vkCmdBindIndexBuffer(commands, v.frameIndices().handle(), 0, VK_INDEX_TYPE_UINT32);
            vkCmdDrawIndexedIndirect(commands, v.drawCommand().handle(), 24L * bin, 1, 20);
        }
    }

    /**
     * Culls {@code views[0]} to {@code views[count - 1]}, each in its phase ({@link #phase}; without {@code early} the
     * late one, against {@code pyramid}): resets their lists, then the instance pass (a row per view), the cull pass
     * over all their chunks and, on the indexed path, each view's expansion.
     */
    private void cull(VkCommandBuffer commands, RenderView[] views, boolean[] early, int count, int pyramid, int width,
        int height, int mips) {
        retiring.release(); // culls of one frame may run on several threads: it locks
        scene.upload(commands);
        boolean meshShading = path() == Path.MESH_SHADERS;
        int instanceCount = scene.highWater();
        bindless.bind(commands); // the late phase samples the depth pyramid through it
        // What came before (the early phase, the frame before) used the buffers this resets, and the visibility.
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            long reset = stack.nmalloc(4, 16); // the chunk command: no chunks yet, a dispatch of x by 1 by 1
            memPutInt(reset, 0);
            memPutInt(reset + 4, 1);
            memPutInt(reset + 8, 1);
            memPutInt(reset + 12, 0);
            nvkCmdUpdateBuffer(commands, chunkCommand.handle(), 0, 16, reset);
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
                FrameBuffer view = v.viewBuffer();
                view.putInt(168, instanceCount);
                // mesh shaders read no index buffer: every listed meshlet fits
                long indices = 3L * v.options().triangles();
                long binIndices = 3L * v.options().binTriangles();
                view.putInt(184, meshShading ? -1 : (int) indices);
                view.putInt(344, meshShading ? -1 : (int) binIndices);
                for (int bin = 1; bin < BINS; bin++) {
                    memPutInt(draws + 24L * bin + 8, (int) (indices + (bin - 1) * binIndices)); // the bin's first index
                }
                if (early != null) {
                    vkCmdFillBuffer(commands, v.drawCount().handle(), 0, VK_WHOLE_SIZE, 0); // counts, indices wanted
                }
                nvkCmdUpdateBuffer(commands, v.expandCommand().handle(), 0, 16 * BINS, dispatches);
                nvkCmdUpdateBuffer(commands, v.drawCommand().handle(), 0, 24 * BINS, draws);
            }
        }
        writeRows(commands, views, early, count);
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, InstancePush.BYTES);
            memPutLong(push + InstancePush.VIEWS, viewTable.deviceAddress());
            memPutLong(push + InstancePush.MESHES, pool.meshTable().deviceAddress());
            memPutLong(push + InstancePush.INSTANCES, scene.instanceAddress());
            memPutLong(push + InstancePush.CHUNKS, chunks.deviceAddress());
            memPutLong(push + InstancePush.CHUNK_COMMAND, chunkCommand.deviceAddress());
            memPutLong(push + InstancePush.LEVELS, pool.levels().deviceAddress());
            memPutInt(push + InstancePush.MAX_CHUNKS, maxChunks);
            occlusion(push + InstancePush.OCCLUSION, pyramid, width, height, mips);
            memPutLong(push + InstancePush.ROWS, rows.deviceAddress());
            instancesPass.bind(commands);
            // A row of workgroups per view, at most 65,535 rows a dispatch (the guaranteed size).
            for (int first = 0; first < count; first += MAX_ROWS) {
                memPutInt(push + InstancePush.FIRST_ROW, first);
                instancesPass.push(commands, push, InstancePush.BYTES);
                vkCmdDispatch(commands, (instanceCount + 63) / 64, Math.min(MAX_ROWS, count - first), 1);
            }
        }
        // The chunks feed the cull pass's indirect dispatch and the pass itself.
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, CullPush.BYTES);
            memPutLong(push + CullPush.VIEWS, viewTable.deviceAddress());
            memPutLong(push + CullPush.MESHES, pool.meshTable().deviceAddress());
            memPutLong(push + CullPush.INSTANCES, scene.instanceAddress());
            memPutLong(push + CullPush.CHUNKS, chunks.deviceAddress());
            memPutLong(push + CullPush.MESHLETS, pool.meshlets().deviceAddress());
            memPutLong(push + CullPush.CLUSTER_LOD, pool.clusterLod().deviceAddress());
            occlusion(push + CullPush.OCCLUSION, pyramid, width, height, mips);
            memPutLong(push + CullPush.MATERIALS, scene.materialsAddress()); // each meshlet's raster bin
            cull.bind(commands);
            cull.push(commands, push, CullPush.BYTES);
            vkCmdDispatchIndirect(commands, chunkCommand.handle(), 0);
        }
        if (meshShading) { // the lists and their dispatches feed the mesh shaders
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | MESH_SHADER_STAGE,
                VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
            return;
        }
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
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
                    vkCmdDispatchIndirect(commands, v.expandCommand().handle(), 16L * bin);
                }
            }
        }
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
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
                nvkCmdUpdateBuffer(commands, rows.handle(), 8L * first, 8L * n, data);
            }
        }
    }

    /** The scene it draws. */
    public GpuScene scene() {
        return scene;
    }

    /** The geometry pool whose meshes it draws, also for other passes shading the same instances. */
    public GeometryPool pool() {
        return pool;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The renderer is closed");
        }
    }

    /** Closes its views and pipelines; the scene stays open. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            instancesPass.close();
            cull.close();
            expand.close();
            for (Pipeline[] pipelines : new Pipeline[][] {draw, drawDepth, meshDraw, meshDepth, idsDraw, idsMesh}) {
                if (pipelines != null) {
                    for (Pipeline pipeline : pipelines) {
                        pipeline.close();
                    }
                }
            }
            retiring.close(); // the GPU is done: what is retired from now on goes at once
            for (RenderView view : views) {
                view.freeBuffers(retiring);
            }
            views.clear();
            viewTable.close();
            rows.close();
            chunks.close();
            chunkCommand.close();
        }
    }
}
