package com.truesim.engine.render.shadows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.annotations.AllowImpure;
import com.truesim.common.math.Maths;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
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
import com.truesim.scene.core.ShadowMethod;
import com.truesim.scene.core.ShadowSpec;
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
 * tile, drawn once. {@link ShadowSpec#AUTO} sizes come each frame from how big the light's reach shows in the views
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
    private long[] checkFrom = new long[0]; // per draw view: the frame from which its overflow is read again
    private Tile[] dirty = new Tile[64];
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

    /** What the shadows of the last frame took: counts for tests, benchmarks and tools. */
    public Stats stats() {
        return stats;
    }

    /** What a frame's shadows took. */
    public static final class Stats {
        int lights;
        int mapped;
        int traced;
        int tiles;
        int drawn;
        int waiting;
        int shrunk;
        int dropped;
        int overflowed;
        int grown;
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

        /** Tiles to draw that waited for a later frame: more than {@link ShadowSettings#ATLAS_DRAWS}. */
        public int waiting() {
            return waiting;
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
        View part = new View(view);
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
        boolean dirty = true;
        boolean valid; // drawn since allocated
        boolean usedNow;
        double priority;
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
        if (views.isEmpty()) {
            return;
        }
        EngineSettings settings = views.get(0).port.settings();
        int atlasSize = Integer.highestOneBit(settings.get(ShadowSettings.ATLAS_SIZE));
        if (atlas == null || atlas.size() != atlasSize) {
            atlas = new AtlasAllocator(atlasSize, MIN_TILE);
            forgetTiles();
            structure++;
        }
        float detail = settings.get(ShadowSettings.CASTER_DETAIL);
        if (detail != casterDetail) {
            casterDetail = detail;
            allDirty = true;
        }
        growOverflowed(frame);
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
        stats.lights = 0;
        stats.mapped = 0;
        stats.traced = 0;
        stats.shrunk = 0;
        stats.dropped = 0;
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
                stats.traced++;
                for (int v3 = 0; v3 < views.size(); v3++) {
                    View view = views.get(v3);
                    view.traced(light, spec);
                }
                continue;
            }
            stats.mapped++;
            if (directional) {
                for (int v4 = 0; v4 < views.size(); v4++) {
                    View view = views.get(v4);
                    view.cascades(light, spec, settings);
                }
            } else {
                localMaps(light, spec, type, settings);
            }
        }
        for (int light = 0; light < shared.length; light++) {
            if (shared[light] != null && !shared[light].usedNow) {
                releaseGroup(shared[light]);
            }
        }
        for (int v5 = 0; v5 < views.size(); v5++) {
            View view = views.get(v5);
            view.releaseUnused();
        }
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
     */
    @AllowAllocation("growing, rarely")
    private void growOverflowed(long frame) {
        if (checkFrom.length < slotViews.length) {
            checkFrom = Arrays.copyOf(checkFrom, slotViews.length);
        }
        for (int i = 0; i < slotViews.length; i++) {
            RenderView view = slotViews[i];
            if (frame >= checkFrom[i] && view.overflowed()) {
                ViewOptions options = view.options();
                view.resize(Math.min(2 * options.draws(), 1 << 24), Math.min(2 * options.triangles(), 1 << 28));
                checkFrom[i] = frame + 4; // its counts are the old cull's until it culls again
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
        }
    }

    @AllowAllocation("growing with the lights")
    private void grow() {
        shared = Arrays.copyOf(shared, lights.capacity());
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

    /** A spot or point light's maps: one tile, or six cube faces. */
    private void localMaps(int light, ShadowSpec spec, int type, EngineSettings settings) {
        Group group = shared[light];
        if (group == null) {
            group = newGroup();
            shared[light] = group;
        }
        boolean point = type == 0;
        int faces = point ? 6 : 1;
        double x = lights.position(light, 0);
        double y = lights.position(light, 1);
        double z = lights.position(light, 2);
        float range = lights.range(light);
        double reach = Float.isInfinite(range) ? Math.sqrt(lights.brightness(light)
            / settings.get(ClusteredLights.CUTOFF)) : range;
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
        double dx = 0;
        double dy = 0;
        double dz = 0;
        float cosOuter = lights.cosOuter(light);
        if (!point) {
            double[] d = lightParams;
            lights.direction(light, d);
            dx = d[0];
            dy = d[1];
            dz = d[2];
        }
        float near = settings.get(ShadowSettings.MAP_NEAR);
        float bias = Float.isNaN(spec.bias()) ? settings.get(ShadowSettings.CASCADE_BIAS) : spec.bias();
        double[] p = lightParams;
        p[0] = x;
        p[1] = y;
        p[2] = z;
        p[3] = dx;
        p[4] = dy;
        p[5] = dz;
        p[6] = reach;
        p[7] = cosOuter;
        p[8] = type;
        p[9] = near;
        p[10] = bias;
        p[11] = casterDetail;
        for (int f = faces; f < group.count; f++) {
            release(group.tiles[f]);
        }
        boolean changed = group.spec != spec || !Arrays.equals(p, group.params) || group.count != faces;
        if (changed) {
            System.arraycopy(p, 0, group.params, 0, p.length);
            group.spec = spec;
        }
        group.light = light;
        group.count = faces;
        group.usedNow = true;
        group.priority = spec.importance() * pixels;
        group.minimal = false;
        double wanted = auto ? pixels * settings.get(ShadowSettings.AUTO_SCALE) * spec.importance()
            : spec.resolution();
        int min = auto ? spec.minSize() : MIN_TILE;
        int max = auto ? spec.maxSize() : spec.resolution();
        float hysteresis = settings.get(ShadowSettings.AUTO_HYSTERESIS);
        for (int f = 0; f < faces; f++) {
            Tile tile = group.tiles[f];
            tile.wanted = sized(tile, wanted, min, max, hysteresis);
            tile.priority = group.priority;
            tile.range = reach;
            if (changed) {
                tile.dirty = true;
            }
            use(tile);
        }
        addGroup(group);
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
    }

    private void release(Tile tile) {
        if (tile.node >= 0) {
            atlas.release(tile.node);
            tile.node = -1;
        }
        tile.valid = false;
        tile.dirty = true;
        tile.size = 0;
        tile.usedNow = false;
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

    /**
     * Marks the tiles a moved, added or removed caster shows (before and after its move) to be drawn again, from the
     * scene's changes waiting to upload; and every tile once something not followed one by one changed.
     */
    private void followCasters(EngineSettings settings) {
        int capacity = scene.capacity();
        if (spheres.length < 4 * capacity) {
            growSpheres(capacity);
        }
        int changes = scene.pendingCount();
        com.truesim.engine.render.InstanceMotion motion = scene.motion();
        long tests = (2L * changes + motion.count()) * usedCount;
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
                        dirtyAround(spheres[o], spheres[o + 1], spheres[o + 2], r);
                    }
                }
                if (pass == 0) {
                    sphereOf(c, id);
                }
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
            int o = 4 * id;
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
                dirtyAround(x, y, z, r);
            }
        }
        if (any) {
            double r = 0.5 * Math.sqrt((maxX - minX) * (maxX - minX) + (maxY - minY) * (maxY - minY)
                + (maxZ - minZ) * (maxZ - minZ));
            dirtyAround((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2, r);
        }
        // Deformed copies change on the GPU every frame they are posed: the tiles around them are drawn again.
        for (int i = 0; i < deformedCount; i++) {
            int o = 4 * deformedIds[i];
            if (spheres[o + 3] >= 0) {
                dirtyAround(spheres[o], spheres[o + 1], spheres[o + 2], 2 * spheres[o + 3]);
            }
        }
        if (allDirty) {
            for (int i = 0; i < usedCount; i++) {
                used[i].dirty = true;
            }
            allDirty = false;
        }
    }

    @AllowAllocation("growing with the scene")
    private void growSpheres(int capacity) {
        int had = spheres.length / 4;
        spheres = Arrays.copyOf(spheres, 4 * capacity);
        deformed = Arrays.copyOf(deformed, capacity);
        reach = Arrays.copyOf(reach, capacity);
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

    /** Marks every tile whose view the sphere at world (x, y, z) of radius {@code r} may reach. */
    private void dirtyAround(double x, double y, double z, double r) {
        for (int i = 0; i < usedCount; i++) {
            Tile tile = used[i];
            if (!tile.dirty && tile.usedNow && reaches(tile, x, y, z, r)) {
                tile.dirty = true;
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

    /** Gives the frame's draw slots to the tiles to draw, most important first; more slots next build if wanted. */
    private void assignSlots(EngineSettings settings) {
        int cap = settings.get(ShadowSettings.ATLAS_DRAWS);
        int dirtyCount = 0;
        for (int i = 0; i < usedCount; i++) {
            Tile tile = used[i];
            if (tile.usedNow && tile.node >= 0 && tile.dirty) {
                if (dirtyCount == dirty.length) {
                    dirty = Arrays.copyOf(dirty, dirty.length * 2);
                }
                dirty[dirtyCount++] = tile;
            }
        }
        // Most important first (an insertion sort: the list is short, and allocates nothing).
        for (int i = 1; i < dirtyCount; i++) {
            Tile t = dirty[i];
            int j = i - 1;
            while (j >= 0 && dirty[j].priority < t.priority) {
                dirty[j + 1] = dirty[j];
                j--;
            }
            dirty[j + 1] = t;
        }
        int wanted = Math.min(Math.min(cap, dirtyCount), slotTiles.length);
        if (wanted > slotsWanted) {
            slotsWanted = Math.max(wanted, Math.min(Math.min(cap, slotTiles.length), 2 * slotsWanted));
            structure++;
        }
        int slots = slotViews.length;
        int drawn = Math.min(dirtyCount, slots);
        Arrays.fill(slotTiles, 0, slots, null);
        float detail = settings.get(ShadowSettings.CASTER_DETAIL);
        for (int i = 0; i < drawn; i++) {
            slotTiles[i] = dirty[i];
            slotViews[i].camera(dirty[i].camera, detail); // before anything selects or culls it this frame
        }
        anyDrawn = drawn > 0;
        stats.drawn = drawn;
        stats.waiting = dirtyCount - drawn;
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
        // More draw slots, as frames wanted
        if (slotViews.length < slotsWanted) {
            int had = slotViews.length;
            slotViews = Arrays.copyOf(slotViews, slotsWanted);
            ViewOptions options = ViewOptions.shadow(g.setting(ShadowSettings.TILE_DRAWS),
                g.setting(ShadowSettings.TILE_TRIANGLES));
            for (int i = had; i < slotsWanted; i++) {
                slotViews[i] = renderer.createView(options);
            }
        }
        // A new build: the atlas may be new (zeroed), every tile drawn again
        forgetDrawn();
        boolean meshlets = false;
        for (com.truesim.engine.render.GeometryProvider provider : geometry.providers()) {
            meshlets |= provider instanceof com.truesim.engine.render.MeshletGeometry;
        }
        SceneData.Buffers data = g.request(new SceneData(renderer));
        GraphBuilder.PassBuilder pass = data.read(g.pass("shadow atlas"), BufferAccess.DRAW_READ)
            .attachDepth(image, Load.LOAD, Store.STORE).when(() -> anyDrawn);
        DepthDraws draws = null;
        if (meshlets) { // culled with the frame's other views, while each draws a tile
            MeshletCulling.Views culling = g.request(new MeshletCulling(renderer));
            for (int i = 0; i < slotViews.length; i++) {
                int slot = i;
                culling.add(slotViews[i], false, null, () -> slotTiles[slot] != null);
                culling.drawing(pass, slotViews[i]);
            }
            draws = DepthDraws.of(g, renderer, slotViews);
        }
        DepthDraws meshletDraws = draws;
        RenderView[] views = slotViews;
        // Classic LOD geometry casts too: each slot's selection, run only while the slot draws a tile
        java.util.List<com.truesim.engine.render.ClassicLodGeometry> classics = new ArrayList<>();
        for (com.truesim.engine.render.GeometryProvider provider : geometry.providers()) {
            if (provider instanceof com.truesim.engine.render.ClassicLodGeometry classic) {
                classics.add(classic);
            }
        }
        com.truesim.engine.render.ClassicLodGeometry.Casters[][] casters =
            new com.truesim.engine.render.ClassicLodGeometry.Casters[classics.size()][views.length];
        for (int c = 0; c < classics.size(); c++) {
            for (int i = 0; i < views.length; i++) {
                int slot = i;
                int k = c;
                g.masked(() -> slotTiles[slot] != null,
                    () -> casters[k][slot] = classics.get(k).casters(g, views[slot]));
                casters[c][i].declare(pass);
            }
        }
        pass.record(ctx -> drawTiles(ctx, meshletDraws, views, casters));
        return image;
    }

    /** Draws the frame's tiles, each into its rectangle, and marks them drawn. Allocates nothing. */
    @AllowImpure("marks the tiles drawn when the frame records: what the next frame's prepare reads")
    private void drawTiles(com.truesim.engine.graph.PassContext ctx, DepthDraws draws, RenderView[] views,
        com.truesim.engine.render.ClassicLodGeometry.Casters[][] casters) {
        for (int i = 0; i < views.length; i++) {
            Tile tile = slotTiles[i];
            if (tile == null) {
                continue;
            }
            ctx.clearDepth(tile.x, tile.y, tile.size, tile.size, 0); // reverse depth: 0 is far
            ctx.viewport(tile.x, tile.y, tile.size, tile.size);
            ctx.scissor(tile.x, tile.y, tile.size, tile.size);
            if (draws != null) {
                draws.draw(ctx, views[i]);
            }
            for (int c = 0; c < casters.length; c++) {
                casters[c][i].draw(ctx);
            }
            tile.dirty = false;
            tile.valid = true;
        }
    }

    /** After a build every tile is drawn again; the frame's slots too, as many as there are now. */
    private void forgetDrawn() {
        allDirty = true;
        for (int i = 0; i < usedCount; i++) {
            used[i].dirty = true;
            used[i].valid = false;
        }
        if (views.isEmpty()) {
            return;
        }
        assignSlots(views.get(0).port.settings());
    }

    /** Writes shared map entries of light {@code light} from {@code first}; returns the entries used, 0 for none. */
    private int writeShared(ShadowTable table, int light, int first, int atlasSlot) {
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
                tile.split, tile.normalOffset, tile.near);
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

        View(SceneView view) {
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
            fit.fit(camera, count, distance, settings.get(ShadowSettings.CASCADE_LAMBDA), 0, direction[0],
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
                fit.fit(camera, group.count, distance, lambda, 0, direction[0], direction[1], direction[2]);
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
            for (int light = 0; light < count; light++) {
                int used = 0;
                Group group = cascades[light];
                if (atlasSlot >= 0) {
                    used = writeShared(table, light, entry, atlasSlot);
                    if (used == 0 && group != null && group.usedNow && !group.droppedNow) {
                        used = writeCascades(table, group, entry, atlasSlot);
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

        private int writeCascades(ShadowTable table, Group group, int first, int atlasSlot) {
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
                    group.count == 1 ? Float.MAX_VALUE : tile.split, tile.normalOffset, 0);
            }
            return group.count;
        }

        @Override
        public String toString() {
            return "shadows of " + port;
        }
    }
}
