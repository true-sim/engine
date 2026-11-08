package com.truesim.engine.render.shadows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.annotations.AllowImpure;
import com.truesim.engine.app.profile.Counters;
import com.truesim.common.math.Maths;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.GraphicsProgram;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Load;
import com.truesim.engine.graph.Request;
import com.truesim.engine.graph.Store;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.DepthDraws;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.MeshletCulling;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneData;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadowInputs;
import com.truesim.engine.render.ShadowTable;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.render.layout.ShadowLayerPush;
import com.truesim.scene.core.ShadowMethod;
import com.truesim.scene.core.ShadowSpec;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Every light's shadows, as data (engine#120, frame graph v2 §4.15): one owner draws the shadows of all lights by each
 * light's {@link ShadowSpec} ({@link Lights#shadow}, set from scene's {@code LightSource} by {@code LightSync}), so giving
 * a light shadows, changing its method, size or softness, or turning them off takes effect from the next frame without
 * rebuilding the graph. Add {@link #view its part} to every view that shades:
 *
 * <pre>{@code
 * Shadows shadows = new Shadows(geometry, lights).rayTracing(rayTracing);   // rayTracing: optional
 * views.addPerView(shadows::view);                                          // or port.add(shadows.view(main))
 * lights.shadow(sun, ShadowSpec.cascades(ShadowSpec.AUTO, ShadowSpec.AUTO, 300));
 * lights.shadow(lamp, ShadowSpec.maps(ShadowSpec.AUTO));                    // any time: no rebuild
 * }</pre>
 *
 * <p><b>Maps</b> ({@link ShadowMethod#MAPS}, {@link ShadowMethod#CASCADES}) are tiles of one shadow atlas
 * ({@link ShadowSettings#ATLAS_SIZE}): a spot light one tile, a point light six (cube faces), a directional light one
 * per cascade per view (fitted to the view's camera). Tiles are allocated by size in the atlas (a quadtree; a light
 * keeps its tile while its size stays) and drawn in one pass, each through a view of its own culled with the frame's
 * other views. A tile is drawn again only when something it shows changed: its light moved or its spec changed, a caster
 * within it moved ({@link GpuScene}'s changes), materials' shapes changed; a still light over a still scene keeps its
 * tile, drawn once. Casters that moved lately ({@link ShadowSettings#CASTER_SETTLE}) are drawn apart: a tile keeps its
 * still casters in a layer of its own (a second atlas, {@link ShadowSettings#CASTER_LAYERS}), and when only moving
 * casters changed it is that layer's depth written into it, the moving casters drawn on top (the depth test merges
 * them). The scene's {@link com.truesim.scene.core.Mobility} is a hint ({@link ShadowSettings#CASTER_MOBILITY},
 * {@link ShadowSettings#LIGHT_MOBILITY}; {@link GpuScene#setMobility}, {@link Lights#mobility}): a static caster joins
 * the still layers at once and a static light's own change tests are skipped while nothing writes it; by default the
 * rest is detected ({@code STATIC_ONLY}: unmarked nodes are dynamic), with {@code USE_HINT} a dynamic caster never
 * joins the layers and a dynamic light's tiles are updated first. A static caster or light that changes all the same is
 * drawn where it is now, as detection would have it. {@link ShadowSpec#AUTO} sizes come each frame from how big the light's reach shows in the views
 * ({@link ShadowSettings#AUTO_SCALE}), within the spec's bounds, with hysteresis; over the atlas, the least important
 * lights' tiles shrink first, then the least important lights cast none.
 *
 * <p><b>Rays</b> ({@link ShadowMethod#RAY_TRACED}, {@link ShadowMethod#SOFT_RAY_TRACED}) are masks per view, traced
 * from the view's depth before shading ({@link TracedMasks}). Without ray tracing they fall back to maps.
 *
 * <p>Nothing allocates per frame; the graph builds again only when the structure grows (the atlas first used, more
 * tiles drawn in a frame than ever, more traced lights in a view than ever), never shrinking.
 */
public final class Shadows implements AutoCloseable {

    private static final int MIN_TILE = 16;
    private static final double MANY = 1.0e9;

    private final SceneGeometry geometry;
    private final MeshletRenderer renderer;
    private final GpuScene scene;
    private final GeometryPool pool;
    private final Lights lights;
    private RayTracing rayTracing;
    private final List<View> views = new ArrayList<>();
    private final Stats stats = new Stats();
    // Ray-traced lights of the frame, ranked for the budget; which were traced last frame
    private int[] candidates = new int[16];
    private double[] candidateRank = new double[16];
    private int candidateCount;
    private boolean[] wasTraced = new boolean[0];
    private ShadowSpec[] hardFrom = new ShadowSpec[0]; // per light: the soft spec its hard one was made from
    private ShadowSpec[] hard = new ShadowSpec[0];
    private double tracedKeep;

    // Spot and point lights' maps, shared by every view: per light id.
    private Group[] shared = new Group[0];
    private final double[] lightParams = new double[12];
    // The tiles used this frame (shared and every view's), and the groups, for the budget.
    private Tile[] used = new Tile[64];
    private int usedCount;
    private Group[] groups = new Group[16];
    private int groupCount;
    private AtlasAllocator atlas;
    private boolean mapsUsed; // sticky: the atlas and its draws are in the graph from the first use on
    // Draw slots: views drawing a tile each in a frame.
    private RenderView[] slotViews = new RenderView[0];
    private final Tile[] slotTiles = new Tile[4096];
    private int slotsWanted; // grows: the graph builds again
    private boolean anyDrawn;
    private final boolean[] slotFull = new boolean[4096]; // per slot this frame: every caster, or the moving ones only
    // Caster layers: draw slots of the still casters' layers
    private RenderView[] layerViews = new RenderView[0];
    private final Tile[] layerTiles = new Tile[4096];
    private boolean anyLayered;
    private int movingDrawn;
    private final ShadowLayerPush layerPush = new ShadowLayerPush();
    private boolean layering; // CASTER_LAYERS as the graph was built
    // Moving casters: per instance the frame of its last move, and a flag while it counts as moving (two bits per id,
    // sixteen a word: the draw views' highlight flags), uploaded each frame; the moving ids
    private long[] movedAt = new long[0];
    private int[] movingFlags = new int[0];
    private MemorySegment movingSegment = MemorySegment.NULL;
    private int[] movingIds = new int[16];
    private MemorySegment movingIdSegment = MemorySegment.ofArray(movingIds);
    private int movingCount;
    // Static casters new to the scene lately, each with the frame it came (frameNow): drawn into the still layers of
    // the tiles they reach, over what those hold, while a tile waits for them
    private int[] addedIds = new int[16];
    private long[] addedAt = new long[16];
    private int addedCount;
    private int[] addIds = new int[16]; // this frame's to draw into layers, uploaded after the moving ids
    private MemorySegment addIdSegment = MemorySegment.ofArray(addIds);
    private int addCount;
    private final boolean[] layerAdding = new boolean[4096]; // per layer slot this frame: newcomers only, no clear
    // Mobility hints (GpuScene's numbers): per instance the hint as last applied (MOVING: kept moving, never
    // settling); the policies as last applied; whether every instance's hint is to be applied again
    private byte[] hintSeen = new byte[0];
    private ShadowSettings.MobilityUse casterUse;
    private ShadowSettings.MobilityUse lightUse = ShadowSettings.MobilityUse.STATIC_ONLY;
    private float movingLightWeight = 1;
    private boolean rehint = true;
    private int settleFrames;
    // What spot and point lights' parameters take from settings, as last read: their change counts as the lights'
    private float paramNear = Float.NaN;
    private float paramBias = Float.NaN;
    private float paramCutoff = Float.NaN;
    private long paramEpoch;
    private FrameBuffer movingUpload;
    private final List<FrameBuffer> outgrown = new ArrayList<>(); // freed with the owner: frames in flight read them
    private long frameNow; // frames prepared
    private long[] checkFrom = new long[0]; // per draw view: the frame (frameNow) from which its overflow is read again
    private Tile[] dirty = new Tile[64];
    private double[] dirtyImportance = new double[64];
    private int[] dirtyWaited = new int[64];
    private final TileSchedule schedule = new TileSchedule();
    private Counters.Source counters;
    // Change tracking
    private double[] spheres = new double[0]; // per instance: world x, y, z, radius (< 0: none)
    private boolean[] deformed = new boolean[0];
    private double[] reach = new double[0]; // per instance: how far its mesh reaches from its origin, unscaled; NaN
    private int[] deformedIds = new int[16];
    private int deformedCount;
    private final double[] pose = new double[10];
    private final float[] bounds = new float[4];
    private long shapes = Long.MIN_VALUE;
    private float casterDetail = Float.NaN;
    private boolean allDirty = true;
    private long preparedFrame = -1;
    private long structure;

    /** The shadows of {@code lights}, maps drawing {@code geometry} (meshlets). */
    @AllowAllocation("made rarely")
    public Shadows(SceneGeometry geometry, Lights lights) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.lights = Objects.requireNonNull(lights, "lights");
        this.renderer = geometry.renderer();
        this.scene = renderer.scene();
        this.pool = scene.pool();
    }

    /**
     * Traces the rays of lights whose method is ray traced into {@code rays}' top level (null, or a device without ray
     * tracing: those lights take maps). Set before the graph builds.
     */
    public Shadows rayTracing(RayTracing rays) {
        this.rayTracing = rays;
        return this;
    }

    /**
     * Where light {@code light}'s map {@code face} (a point light's cube face, else 0) lies in the atlas this frame, for
     * tests and tools: its x, y (16 bits each) and size (from bit 32), packed; -1 if it has none.
     */
    public long tile(int light, int face) {
        Group group = light >= 0 && light < shared.length ? shared[light] : null;
        if (group == null || !group.usedNow || group.droppedNow || face < 0 || face >= group.count) {
            return -1;
        }
        Tile tile = group.tiles[face];
        return tile.node < 0 ? -1 : tile.x | (long) tile.y << 16 | (long) tile.size << 32;
    }

    /** What the shadows of the last frame took: counts for tests, benchmarks and tools. */
    public Stats stats() {
        return stats;
    }

    /**
     * The atlas's update counters for the profiler ({@code profiling.counters().add(shadows.counters())}), from the
     * last frame: {@code shadows.atlas.drawn} and {@code shadows.atlas.waiting} tiles, {@code shadows.atlas.forced}
     * (drawn beyond the budget), {@code shadows.atlas.maxWait} and {@code shadows.atlas.meanLag} (of every changed
     * tile, the waiting too) in frames.
     */
    @AllowAllocation("made once")
    public Counters.Source counters() {
        if (counters == null) {
            counters = new AtlasCounters(stats);
        }
        return counters;
    }

    /** The atlas's update counters, read on the profiler's sampling thread (a frame's values, as last written). */
    private static final class AtlasCounters implements Counters.Source {
        private final Stats stats;
        private int drawn;
        private int waiting;
        private int forced;
        private int maxWait;
        private int meanLag;

        AtlasCounters(Stats stats) {
            this.stats = stats;
        }

        @Override
        public void declare(Counters into) {
            drawn = into.counter("shadows.atlas.drawn", "tiles");
            waiting = into.counter("shadows.atlas.waiting", "tiles");
            forced = into.counter("shadows.atlas.forced", "tiles");
            maxWait = into.counter("shadows.atlas.maxWait", "frames");
            meanLag = into.counter("shadows.atlas.meanLag", "frames");
        }

        @Override
        public void sample(Counters into) {
            int d = stats.drawn;
            into.set(drawn, d);
            into.set(waiting, stats.waiting);
            into.set(forced, stats.forced);
            into.set(maxWait, stats.maxWait);
            int changed = d + stats.waiting;
            into.set(meanLag, changed == 0 ? 0 : stats.late / (double) changed);
        }
    }

    /** What a frame's shadows took. */
    public static final class Stats {
        int lights;
        int mapped;
        int traced;
        int tiles;
        int drawn;
        int waiting;
        int forced;
        int maxWait;
        long waited;
        long late;
        int shrunk;
        int dropped;
        String structureChange = "none";
        int rayFallbacks;
        int hardened;
        int layered;
        int moving;
        int overflowed;
        int grown;
        int stillLights;
        int movingCasters;
        int drawnMovingLight;
        long prepareNanos;
        int layersAdded;
        long texels;
        long atlasTexels;

        /** Lights casting shadows this frame. */
        public int lights() {
            return lights;
        }

        /** Of them, lights shadowed by maps. */
        public int mapped() {
            return mapped;
        }

        /** Of them, lights shadowed by rays, in some view. */
        public int traced() {
            return traced;
        }

        /** Tiles in the atlas. */
        public int tiles() {
            return tiles;
        }

        /** Tiles drawn this frame (the rest were kept). */
        public int drawn() {
            return drawn;
        }

        /** Ray-traced lights mapped instead this frame: more than {@link ShadowSettings#RAY_LIGHTS}. */
        public int rayFallbacks() {
            return rayFallbacks;
        }

        /** Soft ray-traced lights traced hard this frame: more than {@link ShadowSettings#SOFT_LIGHTS}. */
        public int hardened() {
            return hardened;
        }

        /** Of the tiles drawn, those whose layer of still casters was drawn again first. */
        public int layered() {
            return layered;
        }

        /**
         * Of the tiles drawn, those whose current layer had static casters new to the scene drawn into it first (only
         * those culled and drawn, over what it held), not {@link #layered} again.
         */
        public int layersAdded() {
            return layersAdded;
        }

        /** Of the tiles drawn, those drawing only their moving casters, over their layer's depth. */
        public int moving() {
            return moving;
        }

        /** Spot and point lights marked static whose own change tests were skipped this frame (nothing wrote them). */
        public int stillLights() {
            return stillLights;
        }

        /**
         * Casters counting as moving this frame (caster layers on): drawn over the still layers, never in them; those
         * marked dynamic always count.
         */
        public int movingCasters() {
            return movingCasters;
        }

        /** Of the tiles drawn, those of lights marked dynamic. */
        public int drawnMovingLight() {
            return drawnMovingLight;
        }

        /** The CPU time the frame's preparation took (which lights cast how, which tiles change and draw), in ns. */
        public long prepareNanos() {
            return prepareNanos;
        }

        /** Changed tiles left for a later frame ({@link ShadowSettings#ATLAS_UPDATES}): their light keeps its map. */
        public int waiting() {
            return waiting;
        }

        /** Of the tiles drawn, those drawn beyond {@link ShadowSettings#ATLAS_DRAWS}: urgent, or waited the lag. */
        public int forced() {
            return forced;
        }

        /** The most frames a changed tile drawn or still waiting this frame has waited (0: none waited). */
        public int maxWait() {
            return maxWait;
        }

        /** The frames the tiles drawn this frame waited, summed: over {@link #drawn()}, their mean lag. */
        public long waited() {
            return waited;
        }

        /**
         * The frames every changed tile, drawn or waiting, has waited, summed: over {@link #drawn()} and
         * {@link #waiting()}, how many frames late the changed tiles' shadows are on average (the waiting included).
         */
        public long late() {
            return late;
        }

        /** Lights whose maps were made smaller than they wanted, for the atlas's budget. */
        public int shrunk() {
            return shrunk;
        }

        /** Lights casting no shadow for the atlas's budget. */
        public int dropped() {
            return dropped;
        }

        /** Lights casting no shadow in some view for want of shadow table entries. */
        public int overflowed() {
            return overflowed;
        }

        /**
         * What last changed what the graph is built for (each change, a build): "maps first used", "more draw slots",
         * "more traced slots", ...; "none" yet. For tests and tools.
         */
        public String structureChange() {
            return structureChange;
        }

        /** How many times a tile view's lists overflowed and grew (each, a build and every tile drawn again). */
        public int grown() {
            return grown;
        }

        /** Texels of the atlas in tiles. */
        public long texels() {
            return texels;
        }

        /** The atlas's texels (0 before maps are used). */
        public long atlasTexels() {
            return atlasTexels;
        }
    }

    /**
     * The owner's part in {@code view}: add it to the view's port (it modifies the view's light list, as every shadow
     * technique). One per view; its directional maps fit the view's camera, its traced masks are of its pixels.
     */
    @AllowAllocation("set up rarely")
    public Technique view(SceneView view) {
        return add(new View(view, null, 0));
    }

    /**
     * The owner's part in {@code view}, sharing the directional lights' cascades of {@code cascadesOf}'s part (added
     * before) instead of fitting its own: views close together, such as a headset's eyes. Those cascades reach
     * {@code margin} units beyond their view's frustum, enough to cover this one (the distance between the eyes). Half
     * the cascade work of the two views; spot and point lights' maps are every view's anyway.
     */
    @AllowAllocation("set up rarely")
    public Technique view(SceneView view, Technique cascadesOf, double margin) {
        if (!(cascadesOf instanceof View anchor) || anchor.owner() != this || anchor.anchor != null) {
            throw new IllegalArgumentException("Cascades are shared from a part of this owner's that has its own");
        }
        if (!(margin >= 0)) {
            throw new IllegalArgumentException("A margin is not negative: " + margin);
        }
        anchor.margin = Math.max(anchor.margin, margin);
        return add(new View(view, anchor, margin));
    }

    @AllowAllocation("set up rarely")
    private Technique add(View part) {
        SceneView view = part.view;
        views.add(part);
        view.port().own(() -> {
            views.remove(part);
            part.close();
        });
        return part;
    }

    @Override
    public void close() {
        for (int v1 = 0; v1 < views.size(); v1++) {
            View part = views.get(v1);
            part.close();
        }
        for (RenderView view : slotViews) {
            view.close();
        }
        slotViews = new RenderView[0];
        for (RenderView view : layerViews) {
            view.close();
        }
        layerViews = new RenderView[0];
        if (movingUpload != null) {
            movingUpload.close();
            movingUpload = null;
        }
        for (FrameBuffer old : outgrown) {
            old.close();
        }
        outgrown.clear();
    }

    // ------------------------------------------------------------------ per frame

    /** A light's maps of one kind: its tiles, the spec they were made for. */
    private static final class Group {
        final Tile[] tiles = new Tile[Math.max(6, ShadowSpec.MAX_CASCADES)];
        int count;
        int light;
        double priority;
        boolean minimal; // at the smallest it may be
        boolean usedNow;
        boolean droppedNow;
        boolean shrunkNow;
        ShadowSpec spec;
        long lightVersion = -1; // its light's version (Lights.version) when its params were last taken
        long epoch = -1; // paramEpoch then
        final double[] params = new double[12];
        final float[] splits = new float[ShadowSpec.MAX_CASCADES];

        Group() {
            for (int i = 0; i < tiles.length; i++) {
                tiles[i] = new Tile();
            }
        }
    }

    /** A tile of the atlas: one map of a light. */
    private static final class Tile {
        final Camera camera = new Camera();
        final float[] matrix = new float[16];
        final double[] position = new double[3];
        int size; // as allocated
        int wanted; // as this frame wants it
        double wantedLog = Double.NaN; // the continuous size wanted (log2), for hysteresis
        int node = -1;
        int x;
        int y;
        boolean dirty = true; // every caster to draw again
        boolean moved; // moving casters to draw again (over its layer of still ones)
        boolean layered; // the layer atlas holds its still casters, as now
        boolean valid; // drawn since allocated
        boolean usedNow;
        double priority;
        double brightness = Double.NaN; // its light's, for the update order (directional: NaN, not compared)
        boolean movingLight; // its light is marked dynamic: ranked LIGHT_MOVING_WEIGHT times as important
        long waitFrom = -1; // the frame (frameNow) it was first found changed and not drawn since; -1: drawn
        long addsFrom = -1; // its layer as drawn lacks the static casters added since this frame (frameNow); -1: none
        float bias;
        float normalOffset;
        float near; // > 0: perspective
        float split = Float.MAX_VALUE;
        double range; // perspective: the light's reach, for caster tests
    }

    /**
     * Brings the frame's tiles up to date, once per frame (from the parts' {@link Technique#buildVersion}, read as each
     * frame starts): which lights cast how, their sizes and tiles, which tiles to draw. Allocates nothing unless the
     * lights or scene grew.
     */
    void prepare() {
        long frame = scene.frameSlots().frames();
        if (frame == preparedFrame && !scene.frameSlots().isSingle()) {
            return;
        }
        preparedFrame = frame;
        frameNow++; // frames prepared (a single frame slot counts no frames)
        if (views.isEmpty()) {
            return;
        }
        long began = System.nanoTime();
        EngineSettings settings = views.get(0).port.settings();
        boolean layersOn = settings.get(ShadowSettings.CASTER_LAYERS);
        if (layersOn != layering) {
            layering = layersOn;
            structure++; // the layer atlas and its draws in the graph, or not
            stats.structureChange = "caster layers switched";
            allDirty = true;
            forgetMoving();
            Arrays.fill(hintSeen, GpuScene.UNKNOWN); // pinned no more: applied again
            rehint = true;
        }
        ShadowSettings.MobilityUse casters = settings.get(ShadowSettings.CASTER_MOBILITY);
        if (casters != casterUse) {
            casterUse = casters;
            rehint = true;
        }
        lightUse = settings.get(ShadowSettings.LIGHT_MOBILITY);
        movingLightWeight = lightUse == ShadowSettings.MobilityUse.USE_HINT
            ? settings.get(ShadowSettings.LIGHT_MOVING_WEIGHT) : 1;
        settleFrames = settings.get(ShadowSettings.CASTER_SETTLE);
        float near = settings.get(ShadowSettings.MAP_NEAR);
        float bias = settings.get(ShadowSettings.CASCADE_BIAS);
        float cutoff = settings.get(ClusteredLights.CUTOFF);
        if (near != paramNear || bias != paramBias || cutoff != paramCutoff) {
            paramNear = near;
            paramBias = bias;
            paramCutoff = cutoff;
            paramEpoch++; // every light's parameters taken again
        }
        int atlasSize = Integer.highestOneBit(settings.get(ShadowSettings.ATLAS_SIZE));
        if (atlas == null || atlas.size() != atlasSize) {
            atlas = new AtlasAllocator(atlasSize, MIN_TILE);
            forgetTiles();
            structure++;
            stats.structureChange = "a new atlas size";
        }
        float detail = settings.get(ShadowSettings.CASTER_DETAIL);
        if (detail != casterDetail) {
            casterDetail = detail;
            allDirty = true;
            paramEpoch++; // in every light's parameters
        }
        growOverflowed();
        long shapesNow = scene.materials().shapesVersion();
        if (shapesNow != shapes) {
            shapes = shapesNow;
            allDirty = true;
        }
        if (scene.gpuCount() > 0) {
            allDirty = true; // instances the GPU writes (mesh particles): where they are is not known here
        }
        int count = lights.count();
        if (shared.length < lights.capacity()) {
            grow();
        }
        usedCount = 0;
        groupCount = 0;
        tracedKeep = Maths.pow(2, settings.get(ShadowSettings.AUTO_HYSTERESIS));
        stats.rayFallbacks = 0;
        stats.hardened = 0;
        stats.lights = 0;
        stats.mapped = 0;
        stats.traced = 0;
        stats.shrunk = 0;
        stats.dropped = 0;
        stats.stillLights = 0;
        boolean tracing = rayTracing != null; // made only where the device traces rays
        for (int v2 = 0; v2 < views.size(); v2++) {
            View view = views.get(v2);
            view.startFrame(count);
        }
        for (int light = 0; light < count; light++) {
            ShadowSpec spec = lights.shadow(light);
            Group group = shared[light];
            if (group != null) {
                group.usedNow = false;
            }
            if (!spec.casts() || !(lights.brightness(light) > 0)) {
                continue;
            }
            stats.lights++;
            int type = lights.type(light);
            boolean directional = type == 2;
            ShadowMethod method = spec.method();
            if ((method == ShadowMethod.RAY_TRACED || method == ShadowMethod.SOFT_RAY_TRACED) && tracing) {
                // Ranked once every light is seen: the most important within the budget traced, the rest mapped
                double rank = directional ? Double.MAX_VALUE : spec.importance() * coverage(light, settings);
                if (wasTraced[light]) {
                    rank *= tracedKeep;
                }
                if (candidateCount == candidates.length) {
                    growCandidates();
                }
                candidates[candidateCount] = light;
                candidateRank[candidateCount++] = rank;
                continue;
            }
            mapped(light, spec, type, directional, settings);
        }
        traceMostImportant(settings);
        for (int light = 0; light < shared.length; light++) {
            if (shared[light] != null && !shared[light].usedNow) {
                releaseGroup(shared[light]);
            }
        }
        for (int v5 = 0; v5 < views.size(); v5++) {
            View view = views.get(v5);
            view.releaseUnused();
        }
        afterLights(atlasSize, settings);
        stats.prepareNanos = System.nanoTime() - began;
    }

    /** Light {@code light}'s maps: the sun's cascades in each view, a spot or point light's tiles. */
    private void mapped(int light, ShadowSpec spec, int type, boolean directional, EngineSettings settings) {
        stats.mapped++;
        if (directional) {
            for (int v4 = 0; v4 < views.size(); v4++) {
                View view = views.get(v4);
                if (view.anchor == null) {
                    view.cascades(light, spec, settings);
                }
            }
        } else {
            localMaps(light, spec, type, settings);
        }
    }

    /**
     * Traces the most important of the frame's ray-traced lights, {@link ShadowSettings#RAY_LIGHTS} at most (directional
     * ones first, then by how big their reach shows, those traced last frame kept a little longer: no flicker at the
     * edge); the rest take maps (AUTO, their spec's bounds).
     */
    private void traceMostImportant(EngineSettings settings) {
        // Most important first (an insertion sort: allocates nothing)
        for (int i = 1; i < candidateCount; i++) {
            int l = candidates[i];
            double r = candidateRank[i];
            int j = i - 1;
            while (j >= 0 && candidateRank[j] < r) {
                candidates[j + 1] = candidates[j];
                candidateRank[j + 1] = candidateRank[j];
                j--;
            }
            candidates[j + 1] = l;
            candidateRank[j + 1] = r;
        }
        Arrays.fill(wasTraced, false);
        int budget = settings.get(ShadowSettings.RAY_LIGHTS);
        int softBudget = settings.get(ShadowSettings.SOFT_LIGHTS);
        int soft = 0;
        for (int i = 0; i < candidateCount; i++) {
            int light = candidates[i];
            ShadowSpec spec = lights.shadow(light);
            int type = lights.type(light);
            if (i < budget || type == 2) {
                stats.traced++;
                wasTraced[light] = true;
                if (spec.method() == ShadowMethod.SOFT_RAY_TRACED && type != 2 && soft++ >= softBudget) {
                    spec = hardOf(light, spec); // past the soft budget: traced hard
                    stats.hardened++;
                }
                for (int v3 = 0; v3 < views.size(); v3++) {
                    View view = views.get(v3);
                    view.traced(light, spec);
                }
            } else {
                stats.rayFallbacks++;
                mapped(light, spec, type, false, settings);
            }
        }
        candidateCount = 0;
    }

    /** How many pixels across a light's reach shows, the most of any view (many: a view within it). */
    private double coverage(int light, EngineSettings settings) {
        double x = lights.position(light, 0);
        double y = lights.position(light, 1);
        double z = lights.position(light, 2);
        float range = lights.range(light);
        double reach = Float.isInfinite(range) ? Math.sqrt(lights.brightness(light)
            / settings.get(ClusteredLights.CUTOFF)) : range;
        double pixels = 0;
        for (int v = 0; v < views.size(); v++) {
            Camera camera = views.get(v).view.camera();
            double ex = x - camera.position(0);
            double ey = y - camera.position(1);
            double ez = z - camera.position(2);
            double d = Math.sqrt(ex * ex + ey * ey + ez * ez);
            pixels = Math.max(pixels, d <= reach ? MANY : camera.projectionScale() * reach / (d - reach));
        }
        return pixels;
    }

    /** {@code spec} traced hard: made once per spec, kept per light. */
    private ShadowSpec hardOf(int light, ShadowSpec spec) {
        if (hardFrom[light] != spec) {
            hardFrom[light] = spec;
            hard[light] = hardened(spec);
        }
        return hard[light];
    }

    @AllowAllocation("once per spec traced hard")
    private static ShadowSpec hardened(ShadowSpec spec) {
        return spec.method(ShadowMethod.RAY_TRACED);
    }

    @AllowAllocation("growing with the lights")
    private void growCandidates() {
        candidates = Arrays.copyOf(candidates, candidates.length * 2);
        candidateRank = Arrays.copyOf(candidateRank, candidateRank.length * 2);
    }

    /** The rest of the frame's preparation, once every light's shadows are placed. */
    private void afterLights(int atlasSize, EngineSettings settings) {
        if (usedCount > 0) {
            mapsUsedNow();
        }
        budget(atlasSize);
        allocate();
        followCasters(settings); // kept up even without maps: their tiles need where casters were
        assignSlots(settings);
        stats.atlasTexels = mapsUsed ? (long) atlasSize * atlasSize : 0;
        stats.texels = atlas.used();
        stats.tiles = 0;
        for (int i = 0; i < usedCount; i++) {
            stats.tiles += used[i].node >= 0 ? 1 : 0;
        }
    }

    /**
     * Grows the lists of draw views whose last cull wanted more than they hold (read a frame or more late, as the GPU
     * finished it): twice the room, the graph built again, every tile drawn again (what overflowed was left out).
     * Frames are counted here (frames prepared): a scene on a single frame slot counts none.
     */
    @AllowAllocation("growing, rarely")
    private void growOverflowed() {
        if (checkFrom.length < slotViews.length) {
            checkFrom = Arrays.copyOf(checkFrom, slotViews.length);
        }
        for (int i = 0; i < slotViews.length; i++) {
            RenderView view = slotViews[i];
            if (frameNow >= checkFrom[i] && view.overflowed()) {
                ViewOptions options = view.options();
                view.resize(Math.min(2 * options.draws(), 1 << 24), Math.min(2 * options.triangles(), 1 << 28));
                checkFrom[i] = frameNow + 4; // its counts are the old cull's until it culls again
                allDirty = true;
                stats.grown++;
            }
        }
    }

    @AllowAllocation("the first maps, once")
    private void mapsUsedNow() {
        if (!mapsUsed) {
            mapsUsed = true;
            structure++;
            stats.structureChange = "maps first used";
        }
    }

    @AllowAllocation("growing with the lights")
    private void grow() {
        shared = Arrays.copyOf(shared, lights.capacity());
        wasTraced = Arrays.copyOf(wasTraced, lights.capacity());
        hardFrom = Arrays.copyOf(hardFrom, lights.capacity());
        hard = Arrays.copyOf(hard, lights.capacity());
        for (int v6 = 0; v6 < views.size(); v6++) {
            View view = views.get(v6);
            view.grow(lights.capacity());
        }
    }

    /** Every tile's place forgotten (a new atlas): allocated and drawn anew. */
    private void forgetTiles() {
        for (Group group : shared) {
            if (group != null) {
                for (Tile tile : group.tiles) {
                    tile.node = -1;
                    tile.valid = false;
                    tile.dirty = true;
                }
            }
        }
        for (int v7 = 0; v7 < views.size(); v7++) {
            View view = views.get(v7);
            view.forgetTiles();
        }
    }

    /**
     * A spot or point light's maps: one tile, or six cube faces. A light marked static ({@link
     * ShadowSettings#LIGHT_MOBILITY}) that nothing wrote since its parameters were taken keeps them without reading or
     * comparing them again; any write ({@link Lights#version}) has them taken and compared as for every light.
     */
    private void localMaps(int light, ShadowSpec spec, int type, EngineSettings settings) {
        Group group = shared[light];
        if (group == null) {
            group = newGroup();
            shared[light] = group;
        }
        boolean point = type == 0;
        int faces = point ? 6 : 1;
        byte hint = hint(lightUse, lights.mobilityHint(light));
        long version = lights.version(light);
        // Static, unwritten, nothing in its parameters' settings changed: its parameters stand
        boolean known = hint == GpuScene.STILL && group.spec == spec && group.count == faces
            && group.lightVersion == version && group.epoch == paramEpoch;
        double[] p = known ? group.params : lightParams;
        double x;
        double y;
        double z;
        double reach;
        if (known) {
            x = p[0];
            y = p[1];
            z = p[2];
            reach = p[6];
            stats.stillLights++;
        } else {
            x = lights.position(light, 0);
            y = lights.position(light, 1);
            z = lights.position(light, 2);
            float range = lights.range(light);
            reach = Float.isInfinite(range) ? Math.sqrt(lights.brightness(light) / paramCutoff) : range;
        }
        // How big its reach shows: the most pixels across in any view.
        double pixels = 0;
        double nearest = Double.MAX_VALUE;
        for (int v8 = 0; v8 < views.size(); v8++) {
            View view = views.get(v8);
            Camera camera = view.view.camera();
            double ex = x - camera.position(0);
            double ey = y - camera.position(1);
            double ez = z - camera.position(2);
            double d = Math.sqrt(ex * ex + ey * ey + ez * ez);
            nearest = Math.min(nearest, d);
            pixels = Math.max(pixels, d <= reach ? MANY : camera.projectionScale() * reach / (d - reach));
        }
        float cutoff = settings.get(ShadowSettings.AUTO_CUTOFF);
        boolean auto = spec.resolution() == ShadowSpec.AUTO;
        if (auto && cutoff > 0 && nearest - reach > cutoff) {
            return; // too far from every view: none
        }
        for (int f = faces; f < group.count; f++) {
            release(group.tiles[f]);
        }
        boolean changed = false;
        if (!known) {
            double dx = 0;
            double dy = 0;
            double dz = 0;
            float cosOuter = lights.cosOuter(light);
            if (!point) {
                lights.direction(light, p);
                dx = p[0];
                dy = p[1];
                dz = p[2];
            }
            float bias = Float.isNaN(spec.bias()) ? paramBias : spec.bias();
            p[0] = x;
            p[1] = y;
            p[2] = z;
            p[3] = dx;
            p[4] = dy;
            p[5] = dz;
            p[6] = reach;
            p[7] = cosOuter;
            p[8] = type;
            p[9] = paramNear;
            p[10] = bias;
            p[11] = casterDetail;
            changed = group.spec != spec || !Arrays.equals(p, group.params) || group.count != faces;
            if (changed) {
                System.arraycopy(p, 0, group.params, 0, p.length);
                group.spec = spec;
            }
            group.lightVersion = version;
            group.epoch = paramEpoch;
        }
        group.light = light;
        group.count = faces;
        group.usedNow = true;
        group.priority = spec.importance() * pixels;
        group.minimal = false;
        float brightness = lights.brightness(light);
        double wanted = auto ? pixels * settings.get(ShadowSettings.AUTO_SCALE) * spec.importance()
            : spec.resolution();
        int min = auto ? spec.minSize() : MIN_TILE;
        int max = auto ? spec.maxSize() : spec.resolution();
        float hysteresis = settings.get(ShadowSettings.AUTO_HYSTERESIS);
        boolean movingLight = hint == GpuScene.MOVING;
        for (int f = 0; f < faces; f++) {
            Tile tile = group.tiles[f];
            tile.wanted = sized(tile, wanted, min, max, hysteresis);
            tile.priority = group.priority;
            tile.brightness = brightness;
            tile.range = reach;
            tile.movingLight = movingLight;
            if (changed) {
                tile.dirty = true;
            }
            use(tile);
        }
        addGroup(group);
    }

    /** {@code hint} (GpuScene's numbers) as {@code use} takes it: UNKNOWN where it is not to be used. */
    private static byte hint(ShadowSettings.MobilityUse use, byte hint) {
        return switch (use) {
            case USE_HINT -> hint;
            case STATIC_ONLY -> hint == GpuScene.STILL ? GpuScene.STILL : GpuScene.UNKNOWN;
            case DETECT -> GpuScene.UNKNOWN;
        };
    }

    /** A power-of-two size near {@code wanted}, within bounds, changed from the tile's only past the hysteresis. */
    private static int sized(Tile tile, double wanted, int min, int max, float hysteresis) {
        double target = Maths.log(Math.max(wanted, 1)) / Maths.log(2);
        int lo = Integer.numberOfTrailingZeros(min);
        int hi = Integer.numberOfTrailingZeros(max);
        int now = tile.size > 0 ? Integer.numberOfTrailingZeros(tile.size) : -1;
        int exponent;
        if (now >= lo && now <= hi && Math.abs(target - now) <= 0.5 + hysteresis) {
            exponent = now;
        } else {
            exponent = (int) Math.max(lo, Math.min(hi, Math.round(target)));
        }
        return 1 << exponent;
    }

    /** Lays out the tiles of a light's map (its camera, bias) once its tile size is known: from {@link #allocate}. */
    private void placeLocal(Group group, Tile tile, int face) {
        double[] p = group.params;
        double reach = p[6];
        boolean point = p[8] == 0;
        float near = (float) p[9];
        int size = tile.size;
        double fov;
        double fx;
        double fy;
        double fz;
        double ux = 0;
        double uy = 1;
        double uz = 0;
        if (point) {
            // Cube faces +x, -x, +y, -y, +z, -z (lights.slang's order), each a little wider than 90 degrees: two texels
            // past each edge, so filtering near an edge stays within the face.
            fov = 2 * Maths.atan((double) size / (size - 4));
            fx = face == 0 ? 1 : face == 1 ? -1 : 0;
            fy = face == 2 ? 1 : face == 3 ? -1 : 0;
            fz = face == 4 ? 1 : face == 5 ? -1 : 0;
            if (face == 2 || face == 3) {
                uy = 0;
                uz = 1;
            }
        } else {
            double cone = Maths.acos(Math.max(-1, Math.min(1, p[7])));
            fov = Math.min(2 * cone + 4.0 / size, 170 * Math.PI / 180);
            fx = p[3];
            fy = p[4];
            fz = p[5];
            if (Math.abs(fy) > 0.99) {
                uy = 0;
                uz = 1;
            }
        }
        tile.camera.position(p[0], p[1], p[2]).set(fx, fy, fz, ux, uy, uz, fov, size, size, near)
            .farPlane(reach);
        double texelAngle = 2 * Maths.tan(fov / 2) / size;
        float bias = (float) p[10];
        float casterError = Math.max(0, (float) p[11] - 1);
        tile.near = near;
        tile.normalOffset = (float) (bias * texelAngle);
        tile.bias = (float) ((bias / 3 + casterError) * texelAngle);
        tile.split = Float.MAX_VALUE;
    }

    @AllowAllocation("growing, rarely")
    private Group newGroup() {
        return new Group();
    }

    private void use(Tile tile) {
        tile.usedNow = true;
        if (usedCount == used.length) {
            used = Arrays.copyOf(used, used.length * 2);
        }
        used[usedCount++] = tile;
    }

    private void addGroup(Group group) {
        group.droppedNow = false;
        group.shrunkNow = false;
        if (groupCount == groups.length) {
            groups = Arrays.copyOf(groups, groups.length * 2);
        }
        groups[groupCount++] = group;
    }

    private void releaseGroup(Group group) {
        for (Tile tile : group.tiles) {
            release(tile);
        }
        group.spec = null;
        group.lightVersion = -1;
    }

    private void release(Tile tile) {
        if (tile.node >= 0) {
            atlas.release(tile.node);
            tile.node = -1;
        }
        tile.valid = false;
        tile.dirty = true;
        tile.moved = false;
        tile.layered = false;
        tile.addsFrom = -1;
        tile.size = 0;
        tile.usedNow = false;
        tile.waitFrom = -1;
    }

    /**
     * Fits the tiles wanted into the atlas: while they need more texels than it has, the least important light's tiles
     * halve (not below their spec's least size), then the least important lights cast none.
     */
    private void budget(int atlasSize) {
        long capacity = (long) atlasSize * atlasSize;
        long total = 0;
        for (int i = 0; i < usedCount; i++) {
            Tile tile = used[i];
            tile.wanted = Math.min(tile.wanted, atlasSize);
            total += (long) tile.wanted * tile.wanted;
        }
        while (total > capacity) {
            Group least = null;
            for (int g = 0; g < groupCount; g++) {
                Group group = groups[g];
                if (!group.droppedNow && (least == null || group.priority < least.priority)) {
                    if (shrinkable(group)) {
                        least = group;
                    }
                }
            }
            if (least != null) {
                least.shrunkNow = true;
                for (int t = 0; t < least.count; t++) {
                    Tile tile = least.tiles[t];
                    if (tile.wanted > MIN_TILE) {
                        total -= 3L * (tile.wanted / 2) * (tile.wanted / 2);
                        tile.wanted /= 2;
                    }
                }
                continue;
            }
            for (int g = 0; g < groupCount; g++) {
                Group group = groups[g];
                if (!group.droppedNow && (least == null || group.priority < least.priority)) {
                    least = group;
                }
            }
            if (least == null) {
                break;
            }
            least.droppedNow = true;
            for (int t = 0; t < least.count; t++) {
                Tile tile = least.tiles[t];
                total -= (long) tile.wanted * tile.wanted;
                tile.usedNow = false;
            }
        }
        for (int g = 0; g < groupCount; g++) {
            stats.shrunk += groups[g].shrunkNow && !groups[g].droppedNow ? 1 : 0;
            stats.dropped += groups[g].droppedNow ? 1 : 0;
        }
    }

    private static boolean shrinkable(Group group) {
        int floor = group.spec != null && group.spec.resolution() == ShadowSpec.AUTO ? group.spec.minSize() : MIN_TILE;
        for (int t = 0; t < group.count; t++) {
            if (group.tiles[t].wanted > floor) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gives each tile wanted its place: kept where its size stays; else freed and allocated again, largest first; if
     * the atlas is too fragmented for that, every tile laid out anew (and drawn again).
     */
    private void allocate() {
        for (int i = 0; i < usedCount; i++) {
            Tile tile = used[i];
            if (!tile.usedNow || tile.size != tile.wanted) {
                if (tile.node >= 0) {
                    atlas.release(tile.node);
                    tile.node = -1;
                }
                tile.valid = false;
                tile.dirty = true;
                tile.size = tile.usedNow ? tile.wanted : 0;
            }
        }
        boolean fits = place();
        if (!fits) {
            for (int i = 0; i < usedCount; i++) {
                Tile tile = used[i];
                if (tile.node >= 0) {
                    atlas.release(tile.node);
                    tile.node = -1;
                    tile.valid = false;
                    tile.dirty = true;
                }
            }
            place();
        }
        // Cameras of tiles laid out anew: spot and point lights' (cascades are placed by their view each frame).
        for (int light = 0; light < shared.length; light++) {
            Group group = shared[light];
            if (group != null && group.usedNow && !group.droppedNow) {
                for (int f = 0; f < group.count; f++) {
                    Tile tile = group.tiles[f];
                    if (tile.dirty && tile.node >= 0) {
                        placeLocal(group, tile, f);
                    }
                }
            }
        }
        for (int v9 = 0; v9 < views.size(); v9++) {
            View view = views.get(v9);
            view.placeCascades();
        }
    }

    /** Allocates the tiles without a place, largest first; false if one found none. */
    private boolean place() {
        for (int size = atlas.size(); size >= MIN_TILE; size /= 2) {
            for (int i = 0; i < usedCount; i++) {
                Tile tile = used[i];
                if (tile.usedNow && tile.node < 0 && tile.size == size) {
                    tile.node = atlas.allocate(size);
                    if (tile.node < 0) {
                        return false;
                    }
                    tile.x = atlas.x(tile.node);
                    tile.y = atlas.y(tile.node);
                    tile.dirty = true;
                    tile.valid = false;
                }
            }
        }
        return true;
    }

    /** What a change does to the tiles it reaches: every caster drawn again. */
    private static final int FULL = 0;
    /** The moving casters drawn again, over the tile's layer of still ones. */
    private static final int MOVED = 1;
    /** The still casters changed too (a caster starting to move left them): the layer drawn again, then as MOVED. */
    private static final int RELAYER = 2;
    /** The still casters changed, the moving drawn as they are (a caster that stopped joins them): the layer stale. */
    private static final int UNLAYER = 3;
    /**
     * A static caster new to the scene: drawn into the tile's still layer over what it holds (the depth test keeps the
     * nearest, as a layer drawn again would), the tile then as MOVED; a stale layer is drawn again whole, with it.
     */
    private static final int ADDED = 4;
    /** A frame long before any: never moved. */
    private static final long NEVER = Long.MIN_VALUE / 4;

    /**
     * Marks the tiles a moved, added or removed caster shows (before and after its move) to be drawn again, from the
     * scene's changes waiting to upload; and every tile once something not followed one by one changed. With caster
     * layers, a changed caster counts as moving ({@link ShadowSettings#CASTER_SETTLE} frames from its last change):
     * its tiles draw their moving casters again over their still layer; the layers it leaves or joins are drawn again.
     */
    private void followCasters(EngineSettings settings) {
        int capacity = scene.capacity();
        if (spheres.length < 4 * capacity) {
            growSpheres(capacity);
        }
        boolean split = layering;
        if (rehint) {
            rehint = false;
            for (int id = 0, high = scene.highWater(); id < high; id++) {
                if (scene.contains(id)) {
                    applyHint(id, split);
                }
            }
        }
        int changes = scene.pendingCount();
        com.truesim.engine.render.InstanceMotion motion = scene.motion();
        long tests = (2L * changes + motion.count()) * groupCount;
        boolean asOne = tests > settings.get(ShadowSettings.CHANGE_TESTS);
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        boolean any = false;
        for (int c = 0; c < changes; c++) {
            int id = scene.changedId(c);
            int o = 4 * id;
            boolean was = split && moving(id);
            // Marked static and not moved lately: straight into the still layers where it was and is (no settle
            // wait); moved again within the settle frames, it is detected moving as any caster
            byte hint = hint(casterUse, scene.mobilityHint(id));
            boolean still = split && hint == GpuScene.STILL && frameNow - movedAt[id] >= settleFrames;
            if (still) {
                movedAt[id] = frameNow;
                if (was) {
                    unmarkMoving(id);
                }
            } else if (split) {
                markMoving(id);
            }
            hintSeen[id] = hint;
            boolean fresh = still && spheres[o + 3] == -1; // new to the scene (or to its id): nothing to take out
            for (int pass = 0; pass < 2; pass++) {
                double r = spheres[o + 3];
                if (r == -2) {
                    allDirty = true; // where it was is not known
                } else if (r >= 0) {
                    if (asOne) {
                        minX = Math.min(minX, spheres[o] - r);
                        minY = Math.min(minY, spheres[o + 1] - r);
                        minZ = Math.min(minZ, spheres[o + 2] - r);
                        maxX = Math.max(maxX, spheres[o] + r);
                        maxY = Math.max(maxY, spheres[o + 1] + r);
                        maxZ = Math.max(maxZ, spheres[o + 2] + r);
                        any = true;
                    } else {
                        // Where it was: a still caster leaves the still layers there
                        dirtyAround(spheres[o], spheres[o + 1], spheres[o + 2], r,
                            !split ? FULL : still ? (pass == 0 && was ? MOVED : fresh ? ADDED : RELAYER)
                                : pass == 0 && !was ? RELAYER : MOVED);
                        if (fresh) {
                            added(id);
                        }
                    }
                }
                if (pass == 0) {
                    sphereOf(c, id);
                }
            }
            if (spheres[o + 3] == -1) { // gone: the next instance under its id starts afresh
                hintSeen[id] = GpuScene.UNKNOWN;
                movedAt[id] = NEVER;
            }
        }
        // Hints changed without a move: a caster marked dynamic leaves the still layers, one marked static joins them
        for (int i = 0, n = scene.mobilityChangedCount(); i < n; i++) {
            int id = scene.mobilityChangedId(i);
            if (scene.contains(id)) {
                applyHint(id, split);
            }
        }
        // Instances drawn between two steps' poses move on the GPU every frame: the tiles around the way between
        // them are drawn again, and the instance's sphere is that way's from then on.
        for (int slot = 0, slots = motion.highWater(); slot < slots && motion.count() > 0; slot++) {
            int id = motion.slotInstance(slot);
            if (id < 0) {
                continue;
            }
            if (id >= reach.length || Double.isNaN(reach[id])) {
                allDirty = true; // not seen yet: its size is not known
                continue;
            }
            int o = 4 * id;
            if (split && !moving(id) && spheres[o + 3] >= 0) {
                dirtyAround(spheres[o], spheres[o + 1], spheres[o + 2], spheres[o + 3], RELAYER);
            }
            if (split) {
                markMoving(id);
            }
            double fx = motion.slotPose(slot, 0);
            double fy = motion.slotPose(slot, 1);
            double fz = motion.slotPose(slot, 2);
            double tx = motion.slotPose(slot, 3);
            double ty = motion.slotPose(slot, 4);
            double tz = motion.slotPose(slot, 5);
            double half = 0.5 * Math.sqrt((tx - fx) * (tx - fx) + (ty - fy) * (ty - fy) + (tz - fz) * (tz - fz));
            double r = half + motion.slotPose(slot, 6) * reach[id];
            double x = (fx + tx) / 2;
            double y = (fy + ty) / 2;
            double z = (fz + tz) / 2;
            spheres[o] = x;
            spheres[o + 1] = y;
            spheres[o + 2] = z;
            spheres[o + 3] = r;
            if (asOne) {
                minX = Math.min(minX, x - r);
                minY = Math.min(minY, y - r);
                minZ = Math.min(minZ, z - r);
                maxX = Math.max(maxX, x + r);
                maxY = Math.max(maxY, y + r);
                maxZ = Math.max(maxZ, z + r);
                any = true;
            } else {
                dirtyAround(x, y, z, r, split ? MOVED : FULL);
            }
        }
        if (any) {
            double r = 0.5 * Math.sqrt((maxX - minX) * (maxX - minX) + (maxY - minY) * (maxY - minY)
                + (maxZ - minZ) * (maxZ - minZ));
            dirtyAround((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2, r, split ? RELAYER : FULL);
        }
        // Deformed copies change on the GPU every frame they are posed: the tiles around them are drawn again.
        for (int i = 0; i < deformedCount; i++) {
            int id = deformedIds[i];
            int o = 4 * id;
            if (split) {
                markMoving(id);
            }
            if (spheres[o + 3] >= 0) {
                dirtyAround(spheres[o], spheres[o + 1], spheres[o + 2], 2 * spheres[o + 3], split ? MOVED : FULL);
            }
        }
        if (split) {
            settle(settleFrames);
        }
        stats.movingCasters = movingCount;
        if (allDirty) {
            for (int i = 0; i < usedCount; i++) {
                used[i].dirty = true;
            }
            allDirty = false;
        }
    }

    /** Whether instance {@code id} counts as moving. */
    private boolean moving(int id) {
        return (movingFlags[id >> 4] >>> ((id & 15) * 2) & 3) != 0;
    }

    /** Instance {@code id} moved this frame: moving from now on, until it settles. */
    private void markMoving(int id) {
        movedAt[id] = frameNow;
        if (moving(id)) {
            return;
        }
        movingFlags[id >> 4] |= 1 << ((id & 15) * 2);
        if (movingCount == movingIds.length) {
            growMoving();
        }
        movingIds[movingCount++] = id;
    }

    @AllowAllocation("growing with the moving casters")
    private void growMoving() {
        movingIds = Arrays.copyOf(movingIds, movingIds.length * 2);
        movingIdSegment = MemorySegment.ofArray(movingIds);
    }

    /** Casters that stopped {@code frames} frames ago join the still ones: the layers around them are stale. */
    private void settle(int frames) {
        for (int i = movingCount - 1; i >= 0; i--) {
            int id = movingIds[i];
            if (hintSeen[id] == GpuScene.MOVING || frameNow - movedAt[id] < frames) {
                continue; // marked dynamic: never settles
            }
            movingFlags[id >> 4] &= ~(3 << ((id & 15) * 2));
            movingIds[i] = movingIds[--movingCount];
            int o = 4 * id;
            if (spheres[o + 3] >= 0) {
                dirtyAround(spheres[o], spheres[o + 1], spheres[o + 2], spheres[o + 3], UNLAYER);
            }
        }
    }

    /** Instance {@code id} counts as still from now on (a hint changed: rare, so a search of the moving ids). */
    private void unmarkMoving(int id) {
        movingFlags[id >> 4] &= ~(3 << ((id & 15) * 2));
        for (int i = 0; i < movingCount; i++) {
            if (movingIds[i] == id) {
                movingIds[i] = movingIds[--movingCount];
                return;
            }
        }
    }

    /**
     * Applies instance {@code id}'s hint as it is now, where it differs from the one applied: marked dynamic, it counts
     * as moving for good (its tiles' still layers drawn again without it, when next needed); marked static, a moving
     * caster joins the still ones at once (likewise); no longer dynamic, it settles as detected. Its tiles' images
     * stay right either way: only which layer holds it changes.
     */
    private void applyHint(int id, boolean split) {
        byte now = hint(casterUse, scene.mobilityHint(id));
        byte before = hintSeen[id];
        if (now == before) {
            return;
        }
        hintSeen[id] = now;
        if (!split) {
            return;
        }
        int o = 4 * id;
        if (now == GpuScene.MOVING && !moving(id)) {
            markMoving(id);
        } else if (now == GpuScene.STILL && moving(id)) {
            unmarkMoving(id);
            movedAt[id] = NEVER;
        } else {
            return;
        }
        if (spheres[o + 3] >= 0) {
            dirtyAround(spheres[o], spheres[o + 1], spheres[o + 2], spheres[o + 3], UNLAYER);
        } else if (spheres[o + 3] == -2) {
            allDirty = true; // where it is is not known
        }
    }

    /** No caster moving: the layers start again (caster layers turned on, or off). */
    private void forgetMoving() {
        for (int i = 0; i < movingCount; i++) {
            int id = movingIds[i];
            movingFlags[id >> 4] &= ~(3 << ((id & 15) * 2));
        }
        movingCount = 0;
    }

    @AllowAllocation("growing with the scene")
    private void growSpheres(int capacity) {
        int had = spheres.length / 4;
        spheres = Arrays.copyOf(spheres, 4 * capacity);
        deformed = Arrays.copyOf(deformed, capacity);
        reach = Arrays.copyOf(reach, capacity);
        movedAt = Arrays.copyOf(movedAt, capacity);
        Arrays.fill(movedAt, had, capacity, NEVER);
        hintSeen = Arrays.copyOf(hintSeen, capacity);
        rehint = true; // instances already there: their hints applied
        movingFlags = Arrays.copyOf(movingFlags, (capacity + 15) / 16);
        movingSegment = MemorySegment.ofArray(movingFlags);
        Arrays.fill(reach, had, capacity, Double.NaN);
        for (int i = had; i < capacity; i++) {
            // Instances there before the owner followed the scene: where they are is not known (-2), so their first
            // change draws every tile again; free ids: nothing there (-1)
            spheres[4 * i + 3] = scene.contains(i) ? -2 : -1;
        }
    }

    /** Instance {@code id}'s sphere after its change {@code c}, kept for its next. */
    private void sphereOf(int c, int id) {
        int o = 4 * id;
        int mesh = scene.changedCaster(c, pose);
        if (mesh < 0 || !pool.contains(mesh)) {
            spheres[o + 3] = -1;
            reach[id] = Double.NaN;
            setDeformed(id, false);
            return;
        }
        pool.bounds(mesh, bounds);
        double sx = pose[7] * bounds[0];
        double sy = pose[8] * bounds[1];
        double sz = pose[9] * bounds[2];
        // The center turned by the instance's rotation (q v q*).
        double qx = pose[3];
        double qy = pose[4];
        double qz = pose[5];
        double qw = pose[6];
        double tx = 2 * (qy * sz - qz * sy);
        double ty = 2 * (qz * sx - qx * sz);
        double tz = 2 * (qx * sy - qy * sx);
        spheres[o] = pose[0] + sx + qw * tx + (qy * tz - qz * ty);
        spheres[o + 1] = pose[1] + sy + qw * ty + (qz * tx - qx * tz);
        spheres[o + 2] = pose[2] + sz + qw * tz + (qx * ty - qy * tx);
        double scale = Math.max(Math.abs(pose[7]), Math.max(Math.abs(pose[8]), Math.abs(pose[9])));
        spheres[o + 3] = scale * bounds[3];
        reach[id] = Math.sqrt(bounds[0] * bounds[0] + bounds[1] * bounds[1] + bounds[2] * bounds[2]) + bounds[3];
        setDeformed(id, pool.deformedFrom(mesh) >= 0);
    }

    private void setDeformed(int id, boolean on) {
        if (deformed[id] == on) {
            return;
        }
        deformed[id] = on;
        if (on) {
            if (deformedCount == deformedIds.length) {
                deformedIds = Arrays.copyOf(deformedIds, deformedCount * 2);
            }
            deformedIds[deformedCount++] = id;
        } else {
            for (int i = 0; i < deformedCount; i++) {
                if (deformedIds[i] == id) {
                    deformedIds[i] = deformedIds[--deformedCount];
                    break;
                }
            }
        }
    }

    /**
     * Marks every tile whose view the sphere at world (x, y, z) of radius {@code r} may reach, as {@code how} says
     * ({@link #FULL}, {@link #MOVED}, {@link #RELAYER}, {@link #UNLAYER}): a light's reach first, then its tiles.
     */
    private void dirtyAround(double x, double y, double z, double r, int how) {
        for (int g = 0; g < groupCount; g++) {
            Group group = groups[g];
            Tile first = group.tiles[0];
            if (first.near > 0) { // a spot or point light: its tiles share where it is and how far it reaches
                double px = x - first.camera.position(0);
                double py = y - first.camera.position(1);
                double pz = z - first.camera.position(2);
                if (px * px + py * py + pz * pz > (first.range + r) * (first.range + r)) {
                    continue;
                }
            }
            for (int t = 0; t < group.count; t++) {
                Tile tile = group.tiles[t];
                if (tile.usedNow && reaches(tile, x, y, z, r)) {
                    switch (how) {
                        case FULL -> tile.dirty = true;
                        case MOVED -> tile.moved = true;
                        case RELAYER -> {
                            tile.layered = false;
                            tile.moved = true;
                        }
                        case ADDED -> {
                            tile.moved = true;
                            if (tile.addsFrom < 0) {
                                tile.addsFrom = frameNow;
                            }
                        }
                        default -> tile.layered = false;
                    }
                }
            }
        }
    }

    private static boolean reaches(Tile tile, double x, double y, double z, double r) {
        Camera camera = tile.camera;
        double px = x - camera.position(0);
        double py = y - camera.position(1);
        double pz = z - camera.position(2);
        if (tile.near > 0 && px * px + py * py + pz * pz > (tile.range + r) * (tile.range + r)) {
            return false;
        }
        float[] planes = camera.planes();
        for (int k = 0; k < 6; k++) {
            if (planes[4 * k] * px + planes[4 * k + 1] * py + planes[4 * k + 2] * pz + planes[4 * k + 3] < -r) {
                return false;
            }
        }
        return true;
    }

    /**
     * Gives the frame's draw slots to the tiles to draw, most important first; more slots next build if wanted. A tile
     * whose still casters changed draws every caster; one where only moving casters did draws them over the depth of its
     * layer of still casters, drawn first where stale (a layer slot; without one free, every caster).
     */
    private void assignSlots(EngineSettings settings) {
        ShadowSettings.Updates updates = settings.get(ShadowSettings.ATLAS_UPDATES);
        int cap = settings.get(ShadowSettings.ATLAS_DRAWS);
        boolean split = layering;
        int dirtyCount = 0;
        double brightest = 0;
        for (int i = 0; i < usedCount; i++) {
            Tile tile = used[i];
            if (tile.usedNow && tile.brightness > brightest) {
                brightest = tile.brightness;
            }
        }
        int maxWait = 0;
        long late = 0;
        for (int i = 0; i < usedCount; i++) {
            Tile tile = used[i];
            if (tile.usedNow && tile.node >= 0 && (tile.dirty || tile.moved)) {
                if (dirtyCount == dirty.length) {
                    growDirty();
                }
                if (tile.waitFrom < 0) {
                    tile.waitFrom = frameNow;
                }
                int waited = (int) Math.min(Integer.MAX_VALUE, frameNow - tile.waitFrom);
                // the brighter of the lights first: relative to the brightest mapped light (cascades: as they are)
                double relative = tile.brightness >= 0 && brightest > 0 ? tile.brightness / brightest : 1;
                dirty[dirtyCount] = tile;
                // a dynamic light's tiles first: what moves with it shows its lag most (LIGHT_MOVING_WEIGHT)
                dirtyImportance[dirtyCount] = tile.priority * relative * (tile.movingLight ? movingLightWeight : 1);
                dirtyWaited[dirtyCount++] = waited;
                maxWait = Math.max(maxWait, waited);
                late += waited;
            }
        }
        int toDraw = schedule.schedule(updates, dirtyCount, dirtyImportance, dirtyWaited, cap,
            settings.get(ShadowSettings.ATLAS_URGENT), settings.get(ShadowSettings.ATLAS_LAG));
        int limit = updates == ShadowSettings.Updates.CAPPED ? Math.min(cap, slotTiles.length) : slotTiles.length;
        int wanted = Math.min(toDraw, slotTiles.length);
        if (wanted > slotsWanted) {
            slotsWanted = Math.max(wanted, Math.min(limit, 2 * slotsWanted));
            structure++;
            stats.structureChange = "more draw slots";
        }
        int slots = slotViews.length;
        int drawn = Math.min(toDraw, slots);
        Arrays.fill(slotTiles, 0, slots, null);
        Arrays.fill(layerTiles, 0, layerViews.length, null);
        float detail = settings.get(ShadowSettings.CASTER_DETAIL);
        long flags = 0;
        int flagged = movingFlags.length * 16;
        addCount = 0;
        if (split && drawn > 0) {
            listAdds(drawn);
            flags = uploadMoving();
        }
        long adds = flags + 4L * movingFlags.length + 4L * movingCount; // the newcomers' ids after the moving ids
        int layerSlots = 0;
        int layersAdded = 0;
        movingDrawn = 0;
        int movingLightDrawn = 0;
        long waitedSum = 0;
        for (int i = 0; i < drawn; i++) {
            int d = schedule.order(i);
            Tile tile = dirty[d];
            waitedSum += dirtyWaited[d];
            tile.waitFrom = -1; // drawn this frame
            movingLightDrawn += tile.movingLight ? 1 : 0;
            boolean full = tile.dirty || !split;
            boolean adding = !full && tile.layered && tile.addsFrom >= 0; // newcomers into a current layer
            if (!full && (!tile.layered || adding)) {
                if (layerSlots < layerViews.length) {
                    layerTiles[layerSlots] = tile;
                    layerAdding[layerSlots] = adding;
                    layersAdded += adding ? 1 : 0;
                    // the still casters, or of them only the newcomers' ids (culled alone)
                    layerViews[layerSlots++].camera(tile.camera, detail).unhighlightedOnly(flags, flagged)
                        .instances(adding ? adds : 0, addCount);
                } else {
                    full = true; // no layer slot free this frame: every caster
                }
            }
            tile.addsFrom = -1; // its layer drawn with them, or stale (drawn again whole when next needed)
            slotTiles[i] = tile;
            slotFull[i] = full;
            slotViews[i].camera(tile.camera, detail); // before anything selects or culls it this frame
            slotViews[i].highlightedOnly(full ? 0 : flags, flagged);
            // only the moving casters culled, not every instance: the moving ids follow the flags
            slotViews[i].instances(full ? 0 : flags + 4L * movingFlags.length, movingCount);
            movingDrawn += full ? 0 : 1;
        }
        anyDrawn = drawn > 0;
        anyLayered = layerSlots > 0;
        forgetAdds();
        stats.drawn = drawn;
        stats.layered = layerSlots - layersAdded;
        stats.layersAdded = layersAdded;
        stats.moving = movingDrawn;
        stats.drawnMovingLight = movingLightDrawn;
        stats.waiting = dirtyCount - drawn;
        stats.forced = Math.min(schedule.forced(), drawn);
        stats.maxWait = maxWait;
        stats.waited = waitedSum;
        stats.late = late;
    }

    /**
     * Lists the static casters added since the earliest frame whose newcomers a tile drawn this frame (of the first
     * {@code drawn} in the schedule's order) lacks in its layer, and still there: those its layer slot draws over the
     * layer. A newcomer drawn into a layer that already holds it changes nothing (the same depth).
     */
    private void listAdds(int drawn) {
        long from = Long.MAX_VALUE;
        for (int i = 0; i < drawn; i++) {
            Tile tile = dirty[schedule.order(i)];
            if (!tile.dirty && tile.layered && tile.addsFrom >= 0) {
                from = Math.min(from, tile.addsFrom);
            }
        }
        if (from == Long.MAX_VALUE) {
            return;
        }
        if (addIds.length < addedCount) {
            growAddIds(addedCount);
        }
        for (int i = 0; i < addedCount; i++) {
            int id = addedIds[i];
            if (addedAt[i] >= from && scene.contains(id)) {
                addIds[addCount++] = id;
            }
        }
    }

    /** Static caster {@code id} new to the scene this frame: listed for the layers of the tiles it reaches. */
    private void added(int id) {
        if (addedCount == addedIds.length) {
            growAdded();
        }
        addedIds[addedCount] = id;
        addedAt[addedCount++] = frameNow;
    }

    /** Drops the newcomers no tile's layer waits for any more: those older than the oldest a waiting tile lacks. */
    private void forgetAdds() {
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < usedCount; i++) {
            if (used[i].addsFrom >= 0) {
                oldest = Math.min(oldest, used[i].addsFrom);
            }
        }
        int kept = 0;
        for (int i = 0; i < addedCount; i++) {
            if (addedAt[i] >= oldest) {
                addedIds[kept] = addedIds[i];
                addedAt[kept++] = addedAt[i];
            }
        }
        addedCount = kept;
    }

    @AllowAllocation("growing with the casters added in a while")
    private void growAdded() {
        addedIds = Arrays.copyOf(addedIds, 2 * addedIds.length);
        addedAt = Arrays.copyOf(addedAt, addedIds.length);
    }

    @AllowAllocation("growing with the casters added in a while")
    private void growAddIds(int count) {
        addIds = Arrays.copyOf(addIds, Math.max(count, 2 * addIds.length));
        addIdSegment = MemorySegment.ofArray(addIds);
    }

    @AllowAllocation("growing with the tiles")
    private void growDirty() {
        dirty = Arrays.copyOf(dirty, dirty.length * 2);
        dirtyImportance = Arrays.copyOf(dirtyImportance, dirty.length);
        dirtyWaited = Arrays.copyOf(dirtyWaited, dirty.length);
    }

    /**
     * This frame's copy of the moving casters' flags, then their ids, then the static newcomers' to draw into layers
     * ({@link #listAdds}); its device address. Allocates nothing, unless it grows.
     */
    private long uploadMoving() {
        long bytes = 4L * movingFlags.length;
        long ids = 4L * movingCount;
        long adds = 4L * addCount;
        if (movingUpload == null || movingUpload.size() < bytes + ids + adds) {
            growUpload(2 * (bytes + ids + adds));
        }
        movingUpload.put(0, movingSegment, 0, bytes);
        if (ids > 0) {
            movingUpload.put(bytes, movingIdSegment, 0, ids); // the moving ids after the flags
        }
        if (adds > 0) {
            movingUpload.put(bytes + ids, addIdSegment, 0, adds); // then the static newcomers' ids
        }
        return movingUpload.deviceAddress();
    }

    @AllowAllocation("growing with the scene")
    private void growUpload(long bytes) {
        if (movingUpload != null) {
            outgrown.add(movingUpload);
        }
        movingUpload = FrameBuffer.create(renderer.device(), Math.max(bytes, 1024),
            org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, scene.frameSlots());
    }

    /** The structure the graph is built for: it builds again when this changes (only ever growing). */
    long structure() {
        return structure;
    }

    // ------------------------------------------------------------------ the atlas in the graph

    /** The atlas and its draws: one request, shared by every view's part. */
    private record AtlasDraw(Shadows owner) implements Request<Image> {

        @Override
        @AllowAllocation("building the graph, when it changes")
        public Image build(GraphBuilder g) {
            return owner.buildAtlas(g);
        }
    }

    @AllowAllocation("building the graph, when it changes")
    private Image buildAtlas(GraphBuilder g) {
        int size = atlas.size();
        Image image = g.persistent("shadow atlas", ImageDesc.of(Format.of(renderer.depthFormat()), size, size));
        ViewOptions options = ViewOptions.shadow(g.setting(ShadowSettings.TILE_DRAWS),
            g.setting(ShadowSettings.TILE_TRIANGLES));
        // More draw slots, as frames wanted
        if (slotViews.length < slotsWanted) {
            int had = slotViews.length;
            slotViews = Arrays.copyOf(slotViews, slotsWanted);
            for (int i = had; i < slotsWanted; i++) {
                slotViews[i] = renderer.createView(options);
            }
        }
        boolean split = layering;
        if (split && layerViews.length < slotsWanted) { // a layer slot per draw slot: moving casters build nothing
            int had = layerViews.length;
            layerViews = Arrays.copyOf(layerViews, slotsWanted);
            for (int i = had; i < slotsWanted; i++) {
                layerViews[i] = renderer.createView(options);
            }
        }
        RenderView[] layerSlots = split ? layerViews : new RenderView[0];
        // A new build: the atlas may be new (zeroed), every tile drawn again
        forgetDrawn();
        boolean bothSides = g.setting(ShadowSettings.CASTER_FACES) == ShadowSettings.Faces.BOTH;
        boolean meshlets = false;
        for (com.truesim.engine.render.GeometryProvider provider : geometry.providers()) {
            meshlets |= provider instanceof com.truesim.engine.render.MeshletGeometry;
        }
        SceneData.Buffers data = g.request(new SceneData(renderer));
        // Caster layers: the still casters of tiles whose layer is stale, then the layers copied into the atlas
        Image layers = null;
        GraphBuilder.PassBuilder layerPass = null;
        Pipeline overLayer = null;
        if (split) {
            layers = g.persistent("shadow layers", ImageDesc.of(Format.of(renderer.depthFormat()), size, size));
            layerPass = data.read(g.pass("shadow layers"), BufferAccess.DRAW_READ)
                .attachDepth(layers, Load.LOAD, Store.STORE).when(() -> anyLayered);
            overLayer = g.graphicsProgram(GraphicsProgram.of(ShadowLayerPush.BYTES)
                .vertex("shaders/shadows/layer.spv", "layerVertexMain").fragment("shaders/shadows/layer.spv", "layerMain")
                .depth(Format.of(renderer.depthFormat())).drawBackFaces().overwriteDepth());
        }
        MeshletCulling.Views culling = meshlets ? g.request(new MeshletCulling(renderer)) : null;
        DepthDraws layerDraws = null;
        if (split && meshlets) {
            for (int i = 0; i < layerSlots.length; i++) {
                int slot = i;
                culling.add(layerSlots[i], false, null, () -> layerTiles[slot] != null);
                culling.drawing(layerPass, layerSlots[i]);
            }
            layerDraws = DepthDraws.of(g, renderer, bothSides, layerSlots);
        }
        // Classic LOD geometry casts too: each slot's selection, run only while the slot draws a tile
        java.util.List<com.truesim.engine.render.ClassicLodGeometry> classics = new ArrayList<>();
        for (com.truesim.engine.render.GeometryProvider provider : geometry.providers()) {
            if (provider instanceof com.truesim.engine.render.ClassicLodGeometry classic) {
                classics.add(classic);
            }
        }
        com.truesim.engine.render.ClassicLodGeometry.Casters[][] layerCasters = classicCasters(g, classics, layerSlots,
            layerTiles, bothSides, layerPass);
        if (split) {
            DepthDraws draws = layerDraws;
            layerPass.record(ctx -> drawLayers(ctx, draws, layerSlots, layerCasters));
        }
        GraphBuilder.PassBuilder pass = data.read(g.pass("shadow atlas"), BufferAccess.DRAW_READ)
            .attachDepth(image, Load.LOAD, Store.STORE).when(() -> anyDrawn);
        if (split) {
            pass.read(layers, Access.SAMPLED_FRAGMENT);
        }
        DepthDraws draws = null;
        if (meshlets) { // culled with the frame's other views, while each draws a tile
            for (int i = 0; i < slotViews.length; i++) {
                int slot = i;
                culling.add(slotViews[i], false, null, () -> slotTiles[slot] != null);
                culling.drawing(pass, slotViews[i]);
            }
            draws = DepthDraws.of(g, renderer, bothSides, slotViews);
        }
        DepthDraws meshletDraws = draws;
        RenderView[] views = slotViews;
        com.truesim.engine.render.ClassicLodGeometry.Casters[][] casters = classicCasters(g, classics, views,
            slotTiles, bothSides, pass);
        Image layerImage = layers;
        Pipeline layerProgram = overLayer;
        pass.record(ctx -> drawTiles(ctx, meshletDraws, views, casters, layerImage, layerProgram));
        return image;
    }

    /** Classic LOD geometry's casters in each of {@code views}, selected only while its slot draws a tile. */
    @AllowAllocation("building the graph, when it changes")
    private static com.truesim.engine.render.ClassicLodGeometry.Casters[][] classicCasters(GraphBuilder g,
        java.util.List<com.truesim.engine.render.ClassicLodGeometry> classics, RenderView[] views, Tile[] tiles,
        boolean bothSides, GraphBuilder.PassBuilder pass) {
        com.truesim.engine.render.ClassicLodGeometry.Casters[][] casters =
            new com.truesim.engine.render.ClassicLodGeometry.Casters[classics.size()][views.length];
        for (int c = 0; c < classics.size(); c++) {
            for (int i = 0; i < views.length; i++) {
                int slot = i;
                int k = c;
                g.masked(() -> tiles[slot] != null,
                    () -> casters[k][slot] = classics.get(k).casters(g, views[slot], bothSides));
                casters[c][i].declare(pass);
            }
        }
        return casters;
    }

    /**
     * Draws the frame's stale layers of still casters, each into its tile's rectangle; or, into a current layer, the
     * static casters added since it was drawn, over what it holds. Allocates nothing.
     */
    private void drawLayers(com.truesim.engine.graph.PassContext ctx, DepthDraws draws, RenderView[] views,
        com.truesim.engine.render.ClassicLodGeometry.Casters[][] casters) {
        for (int i = 0; i < views.length; i++) {
            Tile tile = layerTiles[i];
            if (tile == null) {
                continue;
            }
            if (!layerAdding[i]) {
                ctx.clearDepth(tile.x, tile.y, tile.size, tile.size, 0); // reverse depth: 0 is far
            }
            ctx.viewport(tile.x, tile.y, tile.size, tile.size);
            ctx.scissor(tile.x, tile.y, tile.size, tile.size);
            if (draws != null) {
                draws.draw(ctx, views[i]);
            }
            for (int c = 0; c < casters.length; c++) {
                casters[c][i].draw(ctx);
            }
        }
    }

    /**
     * Draws the frame's tiles, each into its rectangle: every caster, or the moving ones over the layer's depth;
     * and marks them drawn. Allocates nothing.
     */
    @AllowImpure("marks the tiles drawn when the frame records: what the next frame's prepare reads")
    private void drawTiles(com.truesim.engine.graph.PassContext ctx, DepthDraws draws, RenderView[] views,
        com.truesim.engine.render.ClassicLodGeometry.Casters[][] casters, Image layers, Pipeline overLayer) {
        if (layers != null) {
            layerPush.layer(ctx.sampled(layers));
        }
        for (int i = 0; i < views.length; i++) {
            Tile tile = slotTiles[i];
            if (tile == null) {
                continue;
            }
            if (slotFull[i]) {
                ctx.clearDepth(tile.x, tile.y, tile.size, tile.size, 0); // reverse depth: 0 is far
            }
            ctx.viewport(tile.x, tile.y, tile.size, tile.size);
            ctx.scissor(tile.x, tile.y, tile.size, tile.size);
            if (!slotFull[i]) {
                // the still casters' depth over every texel (no clear first: as much again), the moving drawn over it
                ctx.draw(overLayer, layerPush, 3, 1);
            }
            if (draws != null) {
                draws.draw(ctx, views[i]);
            }
            for (int c = 0; c < casters.length; c++) {
                casters[c][i].draw(ctx);
            }
            tile.layered = !slotFull[i]; // a full draw leaves the layer as it was: stale
            tile.dirty = false;
            tile.moved = false;
            tile.valid = true;
        }
    }

    /** After a build every tile is drawn again; the frame's slots too, as many as there are now. */
    private void forgetDrawn() {
        allDirty = true;
        for (int i = 0; i < usedCount; i++) {
            used[i].dirty = true;
            used[i].valid = false;
            used[i].layered = false;
        }
        if (views.isEmpty()) {
            return;
        }
        assignSlots(views.get(0).port.settings());
    }

    /** Writes shared map entries of light {@code light} from {@code first}; returns the entries used, 0 for none. */
    private int writeShared(ShadowTable table, int light, int first, int atlasSlot, int filter, float radius,
        float widest) {
        Group group = shared[light];
        if (group == null || !group.usedNow || group.droppedNow) {
            return 0;
        }
        for (int f = 0; f < group.count; f++) {
            if (!group.tiles[f].valid) {
                return 0; // not drawn yet: lit until it is
            }
        }
        if (first + group.count > table.capacity()) {
            return -1;
        }
        for (int f = 0; f < group.count; f++) {
            Tile tile = group.tiles[f];
            table.atlasMap(first + f, tile.camera, atlasSlot, atlas.size(), tile.x, tile.y, tile.size, tile.bias,
                tile.split, tile.normalOffset, tile.near).filter(first + f, filter, radius, widest);
        }
        return group.count;
    }

    // ------------------------------------------------------------------ a view's part

    /** The owner's part in one view: its directional lights' cascades, its traced masks, its shadow table. */
    private final class View implements Technique {

        private final SceneView view;
        private final ViewPort port;
        private Group[] cascades = new Group[0]; // per light id
        private final CascadeFit fit = new CascadeFit(ShadowSpec.MAX_CASCADES);
        private final double[] direction = new double[3];
        private boolean[] named = new boolean[0]; // lights this part named in its table last frame
        private final TracedMasks traced;

        private final View anchor; // whose cascades it uses, or null: its own
        private double margin; // how far its cascades reach beyond its frustum: for the parts sharing them

        View(SceneView view, View anchor, double margin) {
            this.anchor = anchor;
            this.view = view;
            this.port = view.port();
            this.traced = new TracedMasks(Shadows.this, view, lights);
            grow(lights.capacity());
        }

        @AllowAllocation("growing with the lights")
        void grow(int capacity) {
            if (cascades.length < capacity) {
                cascades = Arrays.copyOf(cascades, capacity);
                named = Arrays.copyOf(named, capacity);
            }
        }

        void startFrame(int count) {
            if (cascades.length < lights.capacity()) {
                grow(lights.capacity());
            }
            for (Group group : cascades) {
                if (group != null) {
                    group.usedNow = false;
                }
            }
            traced.startFrame();
        }

        void forgetTiles() {
            for (Group group : cascades) {
                if (group != null) {
                    for (Tile tile : group.tiles) {
                        tile.node = -1;
                        tile.valid = false;
                        tile.dirty = true;
                    }
                }
            }
        }

        Shadows owner() {
            return Shadows.this;
        }

        void traced(int light, ShadowSpec spec) {
            traced.add(light, spec);
        }

        /** A directional light's cascades in this view: count and sizes (AUTO from the view), fitted to its camera. */
        void cascades(int light, ShadowSpec spec, EngineSettings settings) {
            Group group = cascades[light];
            if (group == null) {
                group = newGroup();
                cascades[light] = group;
            }
            double distance = spec.distance() > 0 ? spec.distance() : settings.get(ShadowSettings.MAP_DISTANCE);
            int count = spec.method() == ShadowMethod.MAPS ? 1 : spec.cascades() != ShadowSpec.AUTO ? spec.cascades()
                : (int) Math.max(1, Math.min(4, Math.round(Maths.log(Math.max(distance / 2, 1)) / Maths.log(4))));
            Camera camera = view.camera();
            lights.direction(light, direction);
            fit.fit(camera, count, distance, settings.get(ShadowSettings.CASCADE_LAMBDA), margin, direction[0],
                direction[1], direction[2]);
            if (group.count != count) {
                for (int i = count; i < group.count; i++) {
                    release(group.tiles[i]);
                }
            }
            group.count = count;
            group.light = light;
            group.spec = spec;
            group.usedNow = true;
            group.priority = MANY * spec.importance();
            boolean auto = spec.resolution() == ShadowSpec.AUTO;
            float scale = settings.get(ShadowSettings.AUTO_SCALE) * spec.importance();
            float hysteresis = settings.get(ShadowSettings.AUTO_HYSTERESIS);
            double near = camera.viewProjection()[14];
            for (int i = 0; i < count; i++) {
                Tile tile = group.tiles[i];
                // A texel per pixel where the cascade ends: about the view's width per side, finer than the pixels
                // nearer in it (a texel per pixel at its start would take its ratio of splits more per side).
                double wanted = auto ? 2 * fit.radius(i) * scale * camera.projectionScale() / Math.max(fit.split(i),
                    near) : spec.resolution();
                tile.wanted = sized(tile, wanted, auto ? spec.minSize() : MIN_TILE, auto ? spec.maxSize()
                    : spec.resolution(), hysteresis);
                tile.priority = group.priority * (count - i); // the near ones first
                tile.near = 0;
                group.splits[i] = fit.split(i);
                use(tile);
            }
            addGroup(group);
        }

        /** Places this view's cascades at their sizes; a tile whose light camera moved is drawn again. */
        void placeCascades() {
            EngineSettings settings = port.settings();
            float casters = settings.get(ShadowSettings.CASCADE_CASTERS);
            float lambda = settings.get(ShadowSettings.CASCADE_LAMBDA);
            float casterError = Math.max(0, casterDetail - 1);
            Camera camera = view.camera();
            for (Group group : cascades) {
                if (group == null || !group.usedNow || group.droppedNow) {
                    continue;
                }
                ShadowSpec spec = group.spec;
                double distance = spec.distance() > 0 ? spec.distance() : settings.get(ShadowSettings.MAP_DISTANCE);
                lights.direction(group.light, direction);
                fit.fit(camera, group.count, distance, lambda, margin, direction[0], direction[1], direction[2]);
                float biasTexels = Float.isNaN(spec.bias()) ? settings.get(ShadowSettings.CASCADE_BIAS) : spec.bias();
                for (int i = 0; i < group.count; i++) {
                    Tile tile = group.tiles[i];
                    if (tile.node < 0) {
                        continue;
                    }
                    double texel = fit.place(i, tile.size, casters, tile.camera);
                    double depth = 2 * fit.radius(i) + casters;
                    tile.bias = (float) ((biasTexels / 3 + casterError) * texel / depth);
                    tile.normalOffset = (float) (biasTexels * texel);
                    tile.split = fit.split(i);
                    tile.range = 0;
                    float[] m = tile.camera.viewProjection();
                    boolean moved = tile.camera.position(0) != tile.position[0]
                        || tile.camera.position(1) != tile.position[1] || tile.camera.position(2) != tile.position[2];
                    for (int k = 0; k < 16 && !moved; k++) {
                        moved = m[k] != tile.matrix[k];
                    }
                    if (moved) {
                        System.arraycopy(m, 0, tile.matrix, 0, 16);
                        for (int a = 0; a < 3; a++) {
                            tile.position[a] = tile.camera.position(a);
                        }
                        tile.dirty = true;
                    }
                }
            }
        }

        void releaseUnused() {
            traced.assign();
            for (Group group : cascades) {
                if (group != null && !group.usedNow && group.spec != null) {
                    releaseGroup(group);
                }
            }
        }

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(Keys.LIGHT_LIST);
        }

        void close() {
            traced.close();
            for (Group group : cascades) {
                if (group != null && atlas != null) {
                    releaseGroup(group); // its tiles free for the others
                }
            }
        }

        @Override
        public long buildVersion() {
            prepare();
            return structure * 31 + traced.structure();
        }

        @Override
        @AllowAllocation("building the graph, when it changes")
        public void build(GraphBuilder g) {
            g.modify(Keys.LIGHT_LIST);
            prepare();
            ShadowTable table = lights.shadowTable(g.view());
            ShadowInputs.Set inputs = g.request(new ShadowInputs(g.view()));
            Image map = mapsUsed ? g.request(new AtlasDraw(Shadows.this)) : null;
            if (map != null) {
                inputs.sample(map);
            }
            traced.build(g, inputs, rayTracing);
            inputs.setup(ctx -> write(table, map == null ? -1 : ctx.sampled(map), ctx));
        }

        /** Fills this view's table: each shadowed light's entries, and lights no longer shadowed named so. */
        private void write(ShadowTable table, int atlasSlot, com.truesim.engine.graph.PassContext ctx) {
            int entry = 0;
            int count = Math.min(lights.count(), named.length);
            int overflowed = 0;
            EngineSettings settings = port.settings();
            int filter = settings.get(ShadowSettings.MAP_FILTER) == ShadowSettings.Filter.PCSS ? ShadowTable.PCSS
                : ShadowTable.PCF;
            float radius = settings.get(ShadowSettings.MAP_FILTER_RADIUS);
            float widest = settings.get(ShadowSettings.MAP_PENUMBRA);
            for (int light = 0; light < count; light++) {
                int used = 0;
                Group group = (anchor != null ? anchor : this).cascades[light];
                if (atlasSlot >= 0) {
                    used = writeShared(table, light, entry, atlasSlot, filter, radius, widest);
                    if (used == 0 && group != null && group.usedNow && !group.droppedNow) {
                        used = writeCascades(table, group, entry, atlasSlot, filter, radius, widest);
                    }
                }
                if (used == 0) {
                    used = traced.write(table, light, entry, ctx);
                }
                if (used > 0) {
                    table.light(light, entry, used);
                    entry += used;
                    named[light] = true;
                } else {
                    overflowed += used < 0 ? 1 : 0;
                    if (named[light]) {
                        table.noShadows(light);
                        named[light] = false;
                    }
                }
            }
            for (int light = count; light < named.length; light++) {
                if (named[light]) {
                    table.noShadows(light);
                    named[light] = false;
                }
            }
            stats.overflowed = overflowed;
        }

        private int writeCascades(ShadowTable table, Group group, int first, int atlasSlot, int filter, float radius,
            float widest) {
            for (int i = 0; i < group.count; i++) {
                if (!group.tiles[i].valid) {
                    return 0;
                }
            }
            if (first + group.count > table.capacity()) {
                return -1;
            }
            for (int i = 0; i < group.count; i++) {
                Tile tile = group.tiles[i];
                table.atlasMap(first + i, tile.camera, atlasSlot, atlas.size(), tile.x, tile.y, tile.size, tile.bias,
                    group.count == 1 ? Float.MAX_VALUE : tile.split, tile.normalOffset, 0).filter(first + i, filter,
                    radius, widest);
            }
            return group.count;
        }

        @Override
        public String toString() {
            return "shadows of " + port;
        }
    }
}
