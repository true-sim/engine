package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.app.profile.Counters;
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
     * wanted more ({@link #chunksOverflowed()}), up to {@link MeshletSettings#CHUNK_LIMIT}; until then culls run in
     * rounds ({@link MeshletSettings#CULL_ROUNDS}), so nothing is left out.
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

    private final VulkanDevice device;
    private final int depthFormat;
    private final GpuScene scene;
    private final GeometryPool pool;
    private final RenderViews views; // the open views, their constants array and culled rows
    private final CullPasses culling; // the instance, cull and expansion passes, and the chunks they share
    private final RasterPipelines raster; // the draws of what culling listed
    private final ShadingPipelines shading; // the material types' pipelines, made as they are first wanted or ahead
    // Shaded draws outside a frame graph (record, drawView): the pipelines by type for the table as last drawn.
    private RasterPipelines.Typed immediate;
    // The sets of shading hooks graphs' views made pipelines with: pipelines made ahead are made for each; the last one
    // asked for is the one draws outside a graph run
    private volatile ShadingHooks.Set[] preparedHooks = {ShadingHooks.Set.NONE};
    private volatile ShadingHooks.Set lastHooks = ShadingHooks.Set.NONE;
    private volatile boolean surfacesWanted; // a graph drew with the surface outputs: prepare their pipelines too
    private Materials immediateTable;
    private long immediateVersion;
    // ... and their depth and IDs where types have geometry hooks (by the same split, per shading bin); null: none.
    private final RasterPipelines.Geometry[] immediateGeometry = new RasterPipelines.Geometry[4];
    private final Retiring retiring; // the old tables, rows and view lists, until no frame reads them
    private final Runnable followScene = this::followScene;
    // Pipelines made ahead: the scene's table followed, each shading bin's pipelines made as its materials are written.
    private final java.util.function.Consumer<Materials> followTable = this::followTable;
    private final Materials.DrawListener prepare = this::prepare;
    private Materials listened;
    private final List<ClassicLodGeometry> classics = new java.util.concurrent.CopyOnWriteArrayList<>();
    // Read only: a frame graph may record passes on several threads at once, so recording changes no field.
    private static final boolean[] NOT_EARLY = {false};
    private static final boolean[] EARLY = {true};
    private Path path = Path.AUTO;
    private boolean closed;
    // Views' lists grown as frames end, to the limits the last graph built with (MeshletSettings).
    private final Runnable growViews = this::growViews;
    private volatile int viewDrawLimit = MeshletSettings.VIEW_DRAW_LIMIT.defaultValue();
    private volatile int viewTriangleLimit = MeshletSettings.VIEW_TRIANGLE_LIMIT.defaultValue();
    private long ends; // frame ends seen: a single frame slot counts none (pitfalls.md)
    private volatile int viewsGrown;
    private volatile int viewsAtLimit;
    private boolean limitLogged;
    private Counters.Source counters;

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
        this.device = Objects.requireNonNull(device, "device");
        this.depthFormat = depthFormat;
        shading = new ShadingPipelines(device, colorFormat, depthFormat);
        raster = new RasterPipelines(device, shading, depthFormat);
        views = new RenderViews(device, slots, retiring);
        culling = new CullPasses(device, scene, slots, views, retiring, chunks);
        scene.onGrow(followScene);
        slots.onEnd(growViews);
        scene.onMaterials(followTable);
        followTable(scene.materials());
    }

    /**
     * Follows {@code table}, the scene's now: has the pipelines its shading bins draw with made ahead, those it has now
     * and those its materials ask for when written ({@link #prepare}).
     */
    @AllowAllocation("a table set")
    private synchronized void followTable(Materials table) {
        if (closed) {
            return;
        }
        if (listened != null) {
            listened.stopListening(prepare);
        }
        listened = table;
        table.listen(prepare);
        for (int bin = 0; bin < table.binCount(); bin++) {
            prepare(table, bin, table.binRasters(bin));
        }
    }

    /**
     * Has the pipelines shading bin {@code bin} of {@code materials} draws with in raster bins {@code rasters} (a bit
     * each) made ahead, on the pipelines' own thread, for the path frames take now: its forward ones (and the blended
     * bin's weighted ones), classic LOD's where geometry draws classic, and its type's depth and ID ones where it has
     * geometry hooks. Called as a material is written: the frame first drawing it finds them made, or waits for them
     * no longer than making them then would take.
     */
    @AllowAllocation("a material written")
    private void prepare(Materials materials, int bin, int rasters) {
        if (closed) {
            return;
        }
        MaterialType type = materials.binType(bin);
        int features = materials.binFeatures(bin);
        boolean meshShading = path() == Path.MESH_SHADERS;
        for (int raster = 0; raster < BINS; raster++) {
            if ((rasters & 1 << raster) == 0) {
                continue;
            }
            for (ShadingHooks.Set hooks : preparedHooks) { // every set of hooks a graph made pipelines with
                shading.prepare(this.raster.forwardKey(type, features, raster, meshShading, ShadingPipelines.COLOR,
                    hooks));
                if (surfacesWanted && raster != BIN_BLENDED) {
                    shading.prepare(this.raster.forwardKey(type, features, raster, meshShading,
                        ShadingPipelines.COLOR_SURFACES, hooks));
                }
                if (raster == BIN_BLENDED) {
                    shading.prepare(this.raster.forwardKey(type, features, raster, meshShading,
                        ShadingPipelines.WEIGHTED, hooks));
                    shading.prepare(this.raster.forwardKey(type, features, raster, meshShading, ShadingPipelines.OVER,
                        hooks));
                }
                for (ClassicLodGeometry classic : classics) {
                    for (int list = raster; list < 2 * BINS; list += BINS) {
                        shading.prepare(ClassicLodGeometry.forwardKey(type, features, list, ShadingPipelines.COLOR,
                            hooks));
                        if (surfacesWanted && raster != BIN_BLENDED) {
                            shading.prepare(ClassicLodGeometry.forwardKey(type, features, list,
                                ShadingPipelines.COLOR_SURFACES, hooks));
                        }
                        if (raster == BIN_BLENDED) {
                            shading.prepare(ClassicLodGeometry.forwardKey(type, features, list,
                                ShadingPipelines.WEIGHTED, hooks));
                            shading.prepare(ClassicLodGeometry.forwardKey(type, features, list, ShadingPipelines.OVER,
                                hooks));
                        }
                    }
                }
            }
            if (type.geometry() != 0 && raster < BIN_BLENDED) {
                for (int role : new int[] {RasterPipelines.DEPTH, RasterPipelines.IDS}) {
                    if (role == RasterPipelines.IDS && !this.raster.ids()) {
                        continue;
                    }
                    shading.prepare(this.raster.geometryKey(type, raster, role, meshShading));
                    for (int shared = 0; shared < BIN_BLENDED; shared++) { // the other types' category
                        shading.prepare(this.raster.geometryKey(null, shared, role, meshShading));
                    }
                    for (ClassicLodGeometry classic : classics) {
                        for (int list = raster; list < 2 * BINS; list += BINS) {
                            shading.prepare(ClassicLodGeometry.geometryKey(type, list, role == RasterPipelines.DEPTH
                                ? ShadingPipelines.DEPTH : ShadingPipelines.IDS));
                        }
                    }
                }
            }
        }
    }

    /** Classic LOD drawing this renderer's instances: its pipelines are made ahead too. */
    void addClassic(ClassicLodGeometry classic) {
        classics.add(classic);
        Materials table = scene.materials();
        for (int bin = 0; bin < table.binCount(); bin++) {
            prepare(table, bin, table.binRasters(bin));
        }
    }

    void removeClassic(ClassicLodGeometry classic) {
        classics.remove(classic);
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

    /**
     * Whether a frame wanted more chunks than there was room for: its culls ran in rounds (or, past
     * {@link MeshletSettings#CULL_ROUNDS}, left instances out: {@link #droppedInstances()}), until the room grew.
     */
    public boolean chunksOverflowed() {
        return wantedChunks() > chunkCapacity();
    }

    /**
     * Instances (one in one view each) culling left out for want of room since the renderer was made: every round of a
     * cull full, or the room at {@link MeshletSettings#CHUNK_LIMIT}. Counted by the GPU, read as frames end (a frame or
     * more late); the first drop is reported in the log.
     */
    public long droppedInstances() {
        return culling.droppedInstances();
    }

    /** The most rounds a cull ran since culls last settled into one ({@link MeshletSettings#CULL_ROUNDS}). */
    public int cullRounds() {
        return culling.roundsUsed();
    }

    /** Whether culls run in one round: frames fitted their chunks with room to spare. */
    public boolean cullsSettled() {
        return culling.settled();
    }

    /**
     * Views whose lists were at {@link MeshletSettings#VIEW_DRAW_LIMIT} or {@link MeshletSettings#VIEW_TRIANGLE_LIMIT}
     * and still too small, counted at each check that found one so: what did not fit was not drawn. The first is
     * reported in the log.
     */
    public int viewsAtLimit() {
        return viewsAtLimit;
    }

    /** How often a view's lists grew as a frame ended, since the renderer was made. */
    public int viewsGrown() {
        return viewsGrown;
    }

    /**
     * Takes the limits of a graph's build ({@link MeshletSettings}): the chunks' and the views' lists', and the rounds a
     * cull runs. Allocates nothing.
     */
    public void limits(int chunkLimit, int cullRounds, int viewDraws, int viewTriangles) {
        culling.configure(Math.max(chunkLimit, chunkCapacity()), cullRounds);
        viewDrawLimit = viewDraws;
        viewTriangleLimit = viewTriangles;
    }

    /**
     * As a frame ends: grows the lists of the views (but those their owners grow, {@link RenderView#grownByOwner}) that
     * held less than the last finished frame wanted, each read again once the frames in flight culled with its new
     * lists. A view at the limits is counted, the first reported.
     */
    @AllowAllocation("growing, rarely")
    private void growViews() {
        if (closed) {
            return;
        }
        ends++;
        for (int i = 0; i < views.openCount(); i++) { // closing views (rarely) happens on this thread too
            RenderView view = views.open(i);
            if (view.grownByOwner() || ends < view.growCheckFrom) {
                continue;
            }
            int grown = view.grow(viewDrawLimit, viewTriangleLimit);
            if (grown == RenderView.GROWN) {
                viewsGrown++;
            } else if (grown == RenderView.AT_LIMIT) {
                viewLimited(view);
            }
            if (grown != RenderView.FITTED) { // its counts are the old lists' until frames culled with the new
                view.growCheckFrom = ends + scene.frameSlots().count() + 2;
            }
        }
    }

    /** Counts {@code view} at its limits, reporting the first in the log. */
    public void viewLimited(RenderView view) {
        viewsAtLimit++;
        if (!limitLogged) {
            limitLogged = true;
            ViewOptions options = view.options();
            org.slf4j.LoggerFactory.getLogger(MeshletRenderer.class).warn("View {}'s lists hold {} meshlets and {} "
                    + "triangles ({} and {} in the other bins), at the limits (render.meshlets.viewDrawLimit {}, "
                    + "render.meshlets.viewTriangleLimit {}), and frames want more: what does not fit is not drawn",
                view.index(), options.draws(), options.triangles(), options.binDraws(), options.binTriangles(),
                viewDrawLimit, viewTriangleLimit);
        }
    }

    /** The most meshlets views' lists grow to, per raster bin (owners growing their views' read it too). */
    public int viewDrawLimit() {
        return viewDrawLimit;
    }

    /** Like {@link #viewDrawLimit()}, of triangles. */
    public int viewTriangleLimit() {
        return viewTriangleLimit;
    }

    /**
     * The culling's counters for the profiler ({@code profiling.counters().add(renderer.counters())}):
     * {@code meshlets.chunks} (room) and {@code meshlets.chunksWanted} (the most a frame wanted) in chunks of 64
     * meshlets, {@code meshlets.cullRounds}, {@code meshlets.dropped} instances, {@code meshlets.viewsGrown} and
     * {@code meshlets.viewsAtLimit}.
     */
    @AllowAllocation("made once")
    public synchronized Counters.Source counters() {
        if (counters == null) {
            counters = new CullCounters(this);
        }
        return counters;
    }

    /** The culling's counters, read on the profiler's sampling thread. */
    private static final class CullCounters implements Counters.Source {
        private final MeshletRenderer renderer;
        private int chunks;
        private int wanted;
        private int rounds;
        private int dropped;
        private int grown;
        private int limited;

        CullCounters(MeshletRenderer renderer) {
            this.renderer = renderer;
        }

        @Override
        public void declare(Counters into) {
            chunks = into.counter("meshlets.chunks", "chunks");
            wanted = into.counter("meshlets.chunksWanted", "chunks");
            rounds = into.counter("meshlets.cullRounds", "rounds");
            dropped = into.counter("meshlets.dropped", "instances");
            grown = into.counter("meshlets.viewsGrown", "views");
            limited = into.counter("meshlets.viewsAtLimit", "views");
        }

        @Override
        public void sample(Counters into) {
            into.set(chunks, renderer.chunkCapacity());
            into.set(wanted, Integer.toUnsignedLong(renderer.wantedChunks()));
            into.set(rounds, renderer.cullRounds());
            into.set(dropped, renderer.droppedInstances());
            into.set(grown, renderer.viewsGrown());
            into.set(limited, renderer.viewsAtLimit());
        }
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
     * The pipelines by type of shaded draws outside a frame graph, for the scene's table as it is now: made again when
     * it changed (another table, a shading bin new or drawn in another raster bin).
     */
    @AllowAllocation("when the table's shading bins change")
    private RasterPipelines.Typed immediate() {
        Materials materials = scene.materials();
        if (immediate == null || materials != immediateTable || materials.drawnVersion() != immediateVersion) {
            immediateTable = materials;
            immediateVersion = materials.drawnVersion();
            immediate = raster.typed(materials, path() == Path.MESH_SHADERS);
            immediateGeometry[RasterPipelines.DEPTH] = null;
            immediateGeometry[RasterPipelines.IDS] = null;
            immediateGeometry[RasterPipelines.SHADOW] = null;
        }
        return immediate;
    }

    /**
     * Depth or IDs ({@code role}) outside a frame graph where the table has types with geometry hooks: by the views'
     * split per shading bin, each bin of such a type with its pipelines, the others with shared ones; else null.
     */
    @AllowAllocation("when the table's shading bins change")
    private RasterPipelines.Geometry immediateGeometry(int role) {
        Materials materials = scene.materials();
        RasterPipelines.Typed typed = immediate();
        if (!materials.drawsGeometry()) {
            return null;
        }
        if (immediateGeometry[role] == null) {
            MaterialType[] types = new MaterialType[typed.categories()];
            for (int bin = 0; bin < types.length; bin++) {
                MaterialType type = materials.binType(bin);
                types[bin] = type.geometry() != 0 ? type : null;
            }
            immediateGeometry[role] = raster.geometry(types, role, path() == Path.MESH_SHADERS);
        }
        return immediateGeometry[role];
    }

    /**
     * Depth or IDs ({@code role}) for a graph drawing views whose lists culling splits by type with geometry hooks:
     * category 0 every type without (shared pipelines), category n the table's type n with them; null when the table
     * has none (then the lists stay as culled and draw with the shared pipelines). While a graph builds.
     */
    @AllowAllocation("building the graph, when it changes")
    RasterPipelines.Geometry geometryPipelines(int role) {
        Materials materials = scene.materials();
        int count = materials.geometryTypeCount();
        if (count == 0 || !materials.drawsGeometry()) {
            return null;
        }
        MaterialType[] types = new MaterialType[count + 1];
        for (int number = 1; number <= count; number++) {
            types[number] = materials.geometryType(number);
        }
        return raster.geometry(types, role, path() == Path.MESH_SHADERS);
    }

    /**
     * Like {@link #drawAttached} into depth, or {@link #drawIdsAttached}, by type with geometry hooks
     * ({@code pipelines}' role), from the view's lists as culling split them. Allocates nothing.
     */
    void drawGeometryAttached(VkCommandBuffer commands, RenderView view, boolean late,
        RasterPipelines.Geometry pipelines) {
        raster.drawGeometry(commands, scene, own(view), path() == Path.MESH_SHADERS, late, pipelines);
    }

    /** Draws {@code v}'s depth or IDs ({@code role}) attached: by geometry type where the table has some. */
    private void drawRole(VkCommandBuffer commands, RenderView v, int role, boolean late) {
        RasterPipelines.Geometry geometry = immediateGeometry(role);
        if (geometry != null) {
            raster.drawGeometry(commands, scene, v, path() == Path.MESH_SHADERS, late, geometry);
        } else {
            raster.drawBins(commands, scene, v, path() == Path.MESH_SHADERS, role, late);
        }
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
        if (colorView == 0) {
            draw(commands, view, true, RasterPipelines.DEPTH, colorView, depthView, width, height);
        } else {
            raster.drawShaded(commands, scene, view, path() == Path.MESH_SHADERS, true, colorView, depthView, width,
                height, 0, 0, 0, clusters, immediate());
        }
    }

    /**
     * The late phase's culling alone, for a graph drawing what it finds in a pass of its own ({@link #drawAttached}):
     * tests every instance in {@code view}, and the meshlets of those newly visible, against the depth pyramid (sampled
     * slot {@code pyramid}, {@code mips} levels, of the {@code width} by {@code height} depth). Allocates nothing.
     */
    public void cullLate(VkCommandBuffer commands, RenderView view, int pyramid, int width, int height, int mips) {
        own(view).splitByType(immediate().categories()); // for a shaded draw by type
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
        int categories = immediate().categories();
        for (int i = 0; i < Math.min(count, views.length); i++) {
            own(views[i]).splitByType(categories); // their lists split by shading bin, for shaded draws by type
        }
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
        if (colorView == 0) {
            draw(commands, own(view), false, RasterPipelines.DEPTH, colorView, depthView, width, height);
        } else {
            raster.drawShaded(commands, scene, own(view), path() == Path.MESH_SHADERS, false, colorView, depthView,
                width, height, r, g, b, clusters, immediate());
        }
    }

    /**
     * Draws {@code view}, as its early (or only) phase culled it, into a visibility buffer: {@code idView}
     * ({@link #ID_FORMAT}) gets what each pixel shows, cleared to 0 (nothing), and {@code depthView} its depth. A
     * shading pass then shades each pixel once. Needs {@link VulkanDevice#primitiveIds()}. Allocates nothing.
     */
    public void drawIds(VkCommandBuffer commands, RenderView view, long idView, long depthView, int width,
        int height) {
        raster.checkIds();
        draw(commands, own(view), false, RasterPipelines.IDS, idView, depthView, width, height);
    }

    /** The late phase ({@link #recordLate}) into a visibility buffer ({@link #drawIds}). */
    public void recordLateIds(VkCommandBuffer commands, RenderView view, long idView, long depthView, int width,
        int height, int pyramid, int mips) {
        raster.checkIds();
        cullLate(commands, view, pyramid, width, height, mips);
        draw(commands, view, true, RasterPipelines.IDS, idView, depthView, width, height);
    }

    /** Draws {@code v} in {@code role} into its own rendering scope: the late phase's lists with {@code late}. */
    private void draw(VkCommandBuffer commands, RenderView v, boolean late, int role, long colorView, long depthView,
        int width, int height) {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            RasterPipelines.beginRendering(commands, stack, colorView, depthView, width, height, 0, 0, 0, late);
            RasterPipelines.setViewport(commands, stack, width, height);
        }
        drawRole(commands, v, role, late);
        com.truesim.engine.vulkan.Commands.endRendering(commands);
    }

    /**
     * Draws {@code view} into the attachments of the rendering scope open on {@code commands}, as the graph opens one
     * for a pass with attachments: as its early (or only) phase culled it, or with {@code late} what the late phase
     * ({@link #cullLate}) found newly visible. With {@code depthOnly} no shading (a depth attachment alone), else shaded
     * with the lights of {@code clusters} (0: the sun alone). Allocates nothing.
     */
    public void drawAttached(VkCommandBuffer commands, RenderView view, boolean late, boolean depthOnly,
        long clusters) {
        drawAttached(commands, view, late, depthOnly, clusters, 0);
    }

    /**
     * Like {@link #drawAttached(VkCommandBuffer, RenderView, boolean, boolean, long)}, lit from all around by the
     * indirect-light table at {@code indirect} ({@link Keys#INDIRECT_LIGHT}; 0: the constant sky).
     */
    public void drawAttached(VkCommandBuffer commands, RenderView view, boolean late, boolean depthOnly,
        long clusters, long indirect) {
        if (depthOnly) {
            drawRole(commands, own(view), RasterPipelines.DEPTH, late);
        } else {
            raster.drawTyped(commands, scene, own(view), path() == Path.MESH_SHADERS, late, clusters, indirect,
                immediate());
        }
    }

    /**
     * Draws {@code view}'s shadow casters' depth into the rendering scope open on {@code commands}: as
     * {@link #drawAttached} depth only, every face drawn, both sides (a light inside a closed mesh sees its inside, as
     * shadow rays do). Allocates nothing.
     */
    public void drawCastersAttached(VkCommandBuffer commands, RenderView view) {
        drawRole(commands, own(view), RasterPipelines.SHADOW, false);
    }

    /** Like {@link #drawAttached}, into a visibility buffer ({@link #drawIds}) and its depth. */
    public void drawIdsAttached(VkCommandBuffer commands, RenderView view, boolean late) {
        raster.checkIds();
        drawRole(commands, own(view), RasterPipelines.IDS, late);
    }

    /**
     * Draws the blended bin of {@code view}'s lists (both phases', appended) into a transparency technique's
     * attachments as {@code kind} says ({@link BlendedTargets}), lit by the light list at {@code clusters} (0: the
     * sun), transmissive surfaces showing the scene behind of push words {@code behind} ({@link SceneBehind}; 0: none),
     * lit from all around by the indirect-light table at {@code indirect} (0: the constant sky).
     */
    public void drawBlendedAttached(VkCommandBuffer commands, RenderView view, BlendedTargets.Kind kind,
        long clusters, long behind, long indirect) {
        raster.drawBlendedTyped(commands, scene, own(view), path() == Path.MESH_SHADERS, kind, clusters, behind,
            indirect, immediate());
    }

    /**
     * Forward shading by type: the pipelines of the scene's material table's shading bins as they are now, for the
     * path the next frame takes, made where they are not yet. While a graph builds.
     */
    RasterPipelines.Typed forwardPipelines() {
        return forwardPipelines(false, true);
    }

    /**
     * Like {@link #forwardPipelines()}: with {@code surfaces}, the opaque bins' writing the surface outputs too (from
     * then on also made ahead as materials are written); without {@code opaque}, the blended bin's only.
     */
    RasterPipelines.Typed forwardPipelines(boolean surfaces, boolean opaque) {
        return forwardPipelines(surfaces, opaque, lastHooks);
    }

    /**
     * Like {@link #forwardPipelines(boolean, boolean)}, running the shading hooks {@code hooks} (a view's
     * {@link ShadingHookInputs.Set#compiled}): pipelines made ahead from then on are made with them too, and draws
     * outside a graph run them (their view's block's bits deciding).
     */
    RasterPipelines.Typed forwardPipelines(boolean surfaces, boolean opaque, ShadingHooks.Set hooks) {
        return forwardPipelines(surfaces, opaque, hooks, false);
    }

    /**
     * Like {@link #forwardPipelines(boolean, boolean, ShadingHooks.Set)}; with {@code reflecting}, the blended bin's
     * pipelines trace transparent surfaces' screen-space reflections ({@link TransparentReflections}).
     */
    RasterPipelines.Typed forwardPipelines(boolean surfaces, boolean opaque, ShadingHooks.Set hooks,
        boolean reflecting) {
        if (surfaces) {
            surfacesWanted = true;
        }
        lastHooks = hooks;
        ShadingHooks.Set[] sets = preparedHooks;
        if (!java.util.Arrays.asList(sets).contains(hooks)) {
            ShadingHooks.Set[] more = java.util.Arrays.copyOf(sets, sets.length + 1);
            more[sets.length] = hooks;
            preparedHooks = more;
        }
        return raster.typed(scene.materials(), path() == Path.MESH_SHADERS, surfaces, opaque, hooks, reflecting);
    }

    /** A graph draws with the surface outputs: from now on their pipelines are made ahead too. */
    void wantSurfaces() {
        surfacesWanted = true;
    }

    /**
     * How many pipelines of material types were made so far: a shading bin's for each
     * raster bin its materials draw in, on the path drawn.
     */
    public int forwardPipelineCount() {
        return shading.count();
    }

    /** How many pipelines of material types into {@code target} ({@link ShadingPipelines#COLOR} ...) were made. */
    int pipelineCount(int target) {
        return shading.count(target);
    }

    /** Nanoseconds spent making forward shading's pipelines by type so far ({@link #forwardPipelineCount}). */
    public long forwardPipelineNanos() {
        return shading.nanos();
    }

    /** Its draws of what culling listed. */
    RasterPipelines raster() {
        return raster;
    }

    /** The material types' pipelines, shared by the geometry paths drawing this renderer's instances. */
    ShadingPipelines shading() {
        return shading;
    }

    /**
     * Like {@link #drawAttached}, shaded by type: each shading bin with {@code pipelines}, from the view's lists split
     * by shading bin as culling split them ({@link RenderView#splitByType}). Allocates nothing.
     */
    void drawTypedAttached(VkCommandBuffer commands, RenderView view, boolean late, long clusters, long indirect,
        RasterPipelines.Typed pipelines) {
        raster.drawTyped(commands, scene, own(view), path() == Path.MESH_SHADERS, late, clusters, indirect, pipelines);
    }

    /**
     * Like {@link #drawBlendedAttached}, shaded by type ({@link #drawTypedAttached}), with transparent surfaces'
     * reflections' push words at {@code reflections} (0: none). Allocates nothing.
     */
    void drawBlendedTypedAttached(VkCommandBuffer commands, RenderView view, BlendedTargets.Kind kind, long clusters,
        long behind, long indirect, long reflections, RasterPipelines.Typed pipelines) {
        raster.drawBlendedTyped(commands, scene, own(view), path() == Path.MESH_SHADERS, kind, clusters, behind,
            indirect, reflections, pipelines);
    }

    /** The device it draws on. */
    public VulkanDevice device() {
        return device;
    }

    /** The depth format its pipelines draw into ({@code VK_FORMAT_*}). */
    public int depthFormat() {
        return depthFormat;
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
            scene.frameSlots().removeOnEnd(growViews);
            scene.stopFollowing(followScene);
            scene.stopOnMaterials(followTable);
            synchronized (this) {
                if (listened != null) {
                    listened.stopListening(prepare);
                }
            }
            raster.close();
            shading.close();
            retiring.close(); // the GPU is done: what is retired from now on goes at once
            views.close();
            culling.close();
        }
    }
}
