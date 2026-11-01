package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.render.layout.CommonConstants;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.List;
import java.util.Objects;
import org.lwjgl.vulkan.VkCommandBuffer;

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

    /**
     * Chunks of 64 meshlets the views culled together share, to start with: the buffer grows between frames when a frame
     * wanted more (those past it are dropped that frame, and {@link #chunksOverflowed()} says so).
     */
    public static final int MAX_CHUNKS = 65_535;
    /** The most chunks the buffer grows to: 16 bytes each. */
    private static final int CHUNK_LIMIT = CullPasses.CHUNK_LIMIT;
    /** Raster bins, as meshlets/common.slang has them: opaque, double-sided, masked. */
    static final int BINS = CommonConstants.BINS;
    static final int BIN_OPAQUE = CommonConstants.BIN_OPAQUE;
    static final int BIN_DOUBLE_SIDED = CommonConstants.BIN_DOUBLE_SIDED;
    static final int BIN_MASKED = CommonConstants.BIN_MASKED;
    static final int BIN_BLENDED = CommonConstants.BIN_BLENDED;
    /** The visibility buffer's IDs: instance + 1 (0: nothing), and meshlet << 7 | triangle. */
    public static final int ID_FORMAT = org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_UINT;

    private final GpuScene scene;
    private final GeometryPool pool;
    private final RenderViews views; // the open views, their constants array and culled rows
    private final CullPasses culling; // the instance, cull and expansion passes, and the chunks they share
    private final RasterPipelines raster; // the draws of what culling listed
    private final Retiring retiring; // the old tables, rows and view lists, until no frame reads them
    private final Runnable followScene = this::followScene;
    // Read only: a frame graph may record passes on several threads at once, so recording changes no field.
    private static final boolean[] NOT_EARLY = {false};
    private static final boolean[] EARLY = {true};
    private Path path = Path.AUTO;
    private boolean closed;

    /**
     * A renderer of {@code scene} into {@code colorFormat} and {@code depthFormat} attachments, its views keeping a copy
     * per frame of the scene's frame slots.
     */
    public MeshletRenderer(VulkanDevice device, GpuScene scene, int colorFormat, int depthFormat) {
        this(device, scene, colorFormat, depthFormat, scene.frameSlots());
    }

    /**
     * A renderer whose views keep a copy per frame of {@code slots}, which must be {@code scene}'s (a frame graph's), so
     * they may change every frame while earlier frames are still drawn.
     */
    public MeshletRenderer(VulkanDevice device, GpuScene scene, int colorFormat, int depthFormat, FrameSlots slots) {
        this(device, scene, colorFormat, depthFormat, slots, MAX_CHUNKS);
    }

    /** Like the others, with room for {@code chunks} chunks of 64 meshlets shared by views culled together. */
    public MeshletRenderer(VulkanDevice device, GpuScene scene, int colorFormat, int depthFormat, FrameSlots slots,
        int chunks) {
        if (chunks < 1 || chunks > CHUNK_LIMIT) {
            throw new IllegalArgumentException("1 to " + CHUNK_LIMIT + " chunks: " + chunks);
        }
        this.scene = Objects.requireNonNull(scene, "scene");
        this.pool = scene.pool();
        Objects.requireNonNull(slots, "slots");
        if (slots != scene.frameSlots()) {
            throw new IllegalArgumentException("A renderer's frame slots are its scene's: views and instances change "
                + "with the same frames in flight");
        }
        this.retiring = new Retiring(slots);
        raster = new RasterPipelines(device, colorFormat, depthFormat);
        views = new RenderViews(device, slots, retiring);
        culling = new CullPasses(device, scene, slots, views, retiring, chunks);
        scene.onGrow(followScene);
    }

    /**
     * A new view of the scene, with lists and history as {@code options} say: as many as the frame needs (cameras,
     * shadow maps, mirrors), each at the lowest index free. Views live until closed, or until the renderer closes.
     */
    public RenderView createView(ViewOptions options) {
        checkOpen();
        RenderView view = views.create(this, options);
        if (path() == Path.INDEXED) {
            view.frameIndices(); // the indexed path draws it; on mesh shaders no view has one
        }
        return view;
    }

    /**
     * Takes {@code view} (closed) out: its index is free for the next view, and its buffers go once no frame in flight
     * reads them.
     */
    @AllowAllocation("closing a view")
    void removeView(RenderView view) {
        views.remove(view);
    }

    /** The most chunks of 64 meshlets a frame wanted so far: what the views culled together needed. */
    public int wantedChunks() {
        return culling.wantedChunks();
    }

    /** How many chunks there is room for now: it grows between frames to what frames wanted. */
    public int chunkCapacity() {
        return culling.capacity();
    }

    /** Whether a frame wanted more chunks than there was room for: instances were left out of it, until it grew. */
    public boolean chunksOverflowed() {
        return wantedChunks() > chunkCapacity();
    }

    /** Grows the views' per-instance buffers as the scene grows. */
    private void followScene() {
        for (RenderView view : views.open()) {
            view.followInstances();
        }
    }

    /** Has {@code resource} freed once no frame in flight reads it. */
    void retire(AutoCloseable resource) {
        retiring.add(resource);
    }

    /** The views' entries ({@link RenderView#BYTES} each, by {@link RenderView#index()}): the view constants array. */
    FrameBuffer viewConstants() {
        return views.constants();
    }

    /**
     * The device address of the frame's view constants array: every view's {@code View} (meshlets.common), at
     * {@link RenderView#index()}, for shaders of passes of the frame recording. Its capacity grows with the views
     * made, so a pass takes it as it records.
     */
    public long viewConstantsAddress() {
        return views.constants().deviceAddress();
    }

    /** The views open, in the order they were made. */
    public List<RenderView> views() {
        return views.open();
    }

    /**
     * Chooses how meshlets are drawn; {@link Path#AUTO} by default. On mesh shaders the views' index buffers go (once
     * no frame reads them): only the indexed path has them, made as it first draws a view.
     */
    public void setPath(Path requested) {
        if (requested == Path.MESH_SHADERS && !raster.meshShaders()) {
            throw new IllegalStateException("The device has no mesh shaders");
        }
        this.path = Objects.requireNonNull(requested, "path");
        if (path() == Path.MESH_SHADERS) {
            for (RenderView view : views.open()) {
                view.dropIndices();
            }
        }
    }

    /** The GPU memory of all views ({@link RenderView#bytes}), in bytes. */
    public long viewBytes() {
        return views.bytes();
    }

    /** The path the next frame takes. */
    public Path path() {
        if (path == Path.AUTO) {
            return raster.meshShaders() && pool.fitsMeshShaders() ? Path.MESH_SHADERS : Path.INDEXED;
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
        draw(commands, view, true, colorView == 0 ? RasterPipelines.DEPTH : RasterPipelines.SHADED, colorView,
            depthView, width, height, 0, 0, 0, clusters);
    }

    /**
     * The late phase's culling alone, for a graph drawing what it finds in a pass of its own ({@link #drawAttached}):
     * tests every instance in {@code view}, and the meshlets of those newly visible, against the depth pyramid (sampled
     * slot {@code pyramid}, {@code mips} levels, of the {@code width} by {@code height} depth). Allocates nothing.
     */
    public void cullLate(VkCommandBuffer commands, RenderView view, int pyramid, int width, int height, int mips) {
        cullLate(commands, view, pyramid, width, height, mips, false);
    }

    /**
     * Like {@link #cullLate(VkCommandBuffer, RenderView, int, int, int, int)}, in a frame graph pass that, with
     * {@code declared}, declares the culling's buffers and the view's lists ({@link #declareCulling},
     * {@link RenderView#declareCulling}): the graph orders them, not barriers of its own (unless the indexed
     * path's index buffers changed since the build).
     */
    void cullLate(VkCommandBuffer commands, RenderView view, int pyramid, int width, int height, int mips,
        boolean declared) {
        if (!own(view).hasHistory()) {
            throw new IllegalArgumentException("View " + view.index() + " culls no occlusion: made without it");
        }
        culling.cull(commands, view.alone, null, 1, pyramid, width, height, mips, declared,
            path() == Path.MESH_SHADERS);
    }

    /**
     * Declares that {@code pass} culls with this renderer: it writes the chunks and rows (transfers, then compute) and
     * reads the chunk command it writes for its indirect dispatch. Each culled view declares its own lists
     * ({@link RenderView#declareCulling}).
     */
    void declareCulling(GraphBuilder g, GraphBuilder.PassBuilder pass) {
        culling.declare(g, pass);
    }

    /**
     * Culls {@code views[0]} to {@code views[count - 1]} together, however many: one instance pass and one cull pass for
     * them all, each view's meshlets into its own lists, for {@link #drawView}. Views with {@code early[i]} (made with
     * occlusion) run the early phase of occlusion culling ({@link #recordEarly}), each with its own history; the
     * others draw everything in view. Chunks are shared by the views. Allocates nothing.
     */
    public void cullViews(VkCommandBuffer commands, RenderView[] views, boolean[] early, int count) {
        cullViews(commands, views, early, count, false);
    }

    /** Like {@link #cullViews(VkCommandBuffer, RenderView[], boolean[], int)}, declared as {@link #cullLate} says. */
    void cullViews(VkCommandBuffer commands, RenderView[] views, boolean[] early, int count, boolean declared) {
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
        culling.cull(commands, views, early, count, 0, 0, 0, 0, declared, path() == Path.MESH_SHADERS);
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
        draw(commands, own(view), false, colorView == 0 ? RasterPipelines.DEPTH : RasterPipelines.SHADED, colorView,
            depthView, width, height, r, g, b, clusters);
    }

    /**
     * Draws {@code view}, as its early (or only) phase culled it, into a visibility buffer: {@code idView}
     * ({@link #ID_FORMAT}) gets what each pixel shows, cleared to 0 (nothing), and {@code depthView} its depth. A
     * shading pass then shades each pixel once. Needs {@link VulkanDevice#primitiveIds()}. Allocates nothing.
     */
    public void drawIds(VkCommandBuffer commands, RenderView view, long idView, long depthView, int width,
        int height) {
        raster.checkIds();
        draw(commands, own(view), false, RasterPipelines.IDS, idView, depthView, width, height, 0, 0, 0, 0);
    }

    /** The late phase ({@link #recordLate}) into a visibility buffer ({@link #drawIds}). */
    public void recordLateIds(VkCommandBuffer commands, RenderView view, long idView, long depthView, int width,
        int height, int pyramid, int mips) {
        raster.checkIds();
        cullLate(commands, view, pyramid, width, height, mips);
        draw(commands, view, true, RasterPipelines.IDS, idView, depthView, width, height, 0, 0, 0, 0);
    }

    /** Draws {@code v} in {@code role} into its own rendering scope: the late phase's lists with {@code late}. */
    private void draw(VkCommandBuffer commands, RenderView v, boolean late, int role, long colorView, long depthView,
        int width, int height, float r, float g, float b, long clusters) {
        raster.draw(commands, scene, v, path() == Path.MESH_SHADERS, role, late, colorView, depthView, width, height,
            r, g, b, clusters);
    }

    /**
     * Draws {@code view} into the attachments of the rendering scope open on {@code commands}, as the graph opens one
     * for a pass with attachments: as its early (or only) phase culled it, or with {@code late} what the late phase
     * ({@link #cullLate}) found newly visible. With {@code depthOnly} no shading (a depth attachment alone), else shaded
     * with the lights of {@code clusters} (0: the sun alone). Allocates nothing.
     */
    public void drawAttached(VkCommandBuffer commands, RenderView view, boolean late, boolean depthOnly,
        long clusters) {
        raster.drawBins(commands, scene, own(view), path() == Path.MESH_SHADERS,
            depthOnly ? RasterPipelines.DEPTH : RasterPipelines.SHADED, late, clusters);
    }

    /** Like {@link #drawAttached}, into a visibility buffer ({@link #drawIds}) and its depth. */
    public void drawIdsAttached(VkCommandBuffer commands, RenderView view, boolean late) {
        raster.checkIds();
        raster.drawBins(commands, scene, own(view), path() == Path.MESH_SHADERS, RasterPipelines.IDS, late, 0);
    }

    /**
     * Draws the blended bin of {@code view}'s lists (both phases', appended) into a transparency technique's
     * attachments as {@code kind} says ({@link BlendedTargets}), lit by the light list at {@code clusters} (0: the
     * sun).
     */
    public void drawBlendedAttached(VkCommandBuffer commands, RenderView view, BlendedTargets.Kind kind,
        long clusters) {
        raster.drawBlended(commands, scene, own(view), path() == Path.MESH_SHADERS, kind, clusters);
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
            scene.stopFollowing(followScene);
            raster.close();
            retiring.close(); // the GPU is done: what is retired from now on goes at once
            views.close();
            culling.close();
        }
    }
}
