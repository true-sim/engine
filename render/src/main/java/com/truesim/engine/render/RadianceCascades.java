package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Request;
import com.truesim.engine.render.layout.CascadePush;
import com.truesim.engine.render.layout.IndirectConstants;
import com.truesim.engine.render.layout.EmitterListLayout;
import com.truesim.engine.render.layout.IndirectLightLayout;
import com.truesim.engine.render.layout.PathPush;
import com.truesim.engine.render.layout.ProbeVolumeDataLayout;
import com.truesim.engine.render.layout.VolumeConstants;
import com.truesim.engine.render.shadows.TopLevel;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

/**
 * Radiance cascades in world space (global illumination's design D9; Sannikov 2023, its penumbra condition and merge
 * carried to three dimensions): {@link #CASCADES} grids of probes around {@code anchor}'s camera, the first
 * {@link #PROBES} x {@link #PROBES_UP} x {@link #PROBES} probes {@link #SPACING} apart, each next one twice as far
 * apart (as many as cover the one before), with four times as many directions ({@link #DIRECTIONS} along each side of
 * the first's octahedral tiles, twice as many each next), each direction a ray along an interval four times as long
 * as the one before's ({@link #INTERVAL}), starting where it ended; the last cascade's rays go on to the environment.
 *
 * <p>No history: every frame traces every interval anew (fixed directions, so a still scene's light is still), and
 * merges the cascades from the last to the first: a direction whose ray met nothing within its interval takes what the
 * next cascade's probes around it found beyond, in the four directions refining it, weighed trilinearly over those not
 * inside geometry whose intervals continue this one ({@link #VISIBILITY}: nothing between where it ends and where
 * theirs start, nor between their coarser and finer directions' starts: no light from the far side of a wall).
 * Changes show in the frame they happen; only the further bounces (hits lit by the cascades of the frame before) take
 * a frame per bounce. Hits shading many lights by the world grid's reservoirs pick a light at random, the one noise
 * left (a scene of few lights lists them all).
 *
 * <p>Every cascade's merged light becomes a cascade of a probe volume (irradiance / pi with the sky's visibility,
 * distance moments), which shading samples as it samples {@link ProbeVolume}'s, through {@link ProbeVolumeLighting}
 * ({@link ProbeVolumeLighting#CASCADES}): the same {@link ProbeVolume.Volume}. A point takes the finest cascade whose
 * probes enclose it wherever the camera is, handing over to the next coarser one near that box's edges and from the
 * last to the environment ({@code PROBE_VOLUME_ANCHORED}): its light moves with the camera continuously, not by the
 * steps the grids snap by. A coarser cascade's probes gather their light into the first cascade's directions; their
 * intervals start away from them, so each also traces those directions itself up to where its intervals start
 * ({@code PATH_NEAR}), a surface met there hiding what the intervals found beyond. Each cascade's tiles have its own
 * probe counts, packed in the atlases below the first's.
 *
 * <p>Rays are paths of the reference path tracer's wavefront in its probe mode with intervals
 * ({@link ReferencePathTracer}): hits shaded by their material type, their lights through the world light grid with
 * shadow rays, emission. A frame-level shared request, as the probe volume: both eyes of a headset read one. Needs ray
 * tracing. Allocates nothing per frame.
 *
 * @param rayTracing   the scene for rays
 * @param renderer     the renderer whose scene is traced
 * @param lights       the scene's lights (with those the GPU writes)
 * @param environment  the distant light rays meet when they leave the scene
 * @param quality      how the environment is filtered: as the views' {@link EnvironmentLighting} filters it
 * @param anchor       the view whose camera the cascades follow and whose positions rays are relative to
 */
public record RadianceCascades(RayTracing rayTracing, MeshletRenderer renderer, Lights lights, Environment environment,
    EnvironmentMaps.Quality quality, SceneView anchor) implements Request<ProbeVolume.Volume> {

    /** Cascades; the graph rebuilds. */
    public static final Setting<Integer> CASCADES = ProbeVolumeLighting.CASCADES.setting("count");
    /** Probes along each horizontal axis of the first cascade; the graph rebuilds. */
    public static final Setting<Integer> PROBES = ProbeVolumeLighting.CASCADES.setting("probes");
    /** Probes along the vertical axis of the first cascade; the graph rebuilds. */
    public static final Setting<Integer> PROBES_UP = ProbeVolumeLighting.CASCADES.setting("probesUp");
    /** The first cascade's spacing, in meters; the graph rebuilds. */
    public static final Setting<Float> SPACING = ProbeVolumeLighting.CASCADES.setting("spacing");
    /** Directions along each side of the first cascade's octahedral tiles (rays per probe: the square); rebuilds. */
    public static final Setting<Integer> DIRECTIONS = ProbeVolumeLighting.CASCADES.setting("directions");
    /** How long the first cascade's intervals are, of its spacing (each next one's four times as long). */
    public static final Setting<Float> INTERVAL = ProbeVolumeLighting.CASCADES.setting("interval");
    /** Merges take only the next cascade's intervals that continue a probe's (rays between where they start). */
    public static final Setting<Boolean> VISIBILITY = ProbeVolumeLighting.CASCADES.setting("visibility");
    /** First-cascade probes inside geometry are not sampled. */
    public static final Setting<Boolean> CLASSIFY = ProbeVolumeLighting.CASCADES.setting("classify");
    /**
     * How far from the camera the light reaches, in meters, horizontally (vertically as the first cascade's probes up
     * to across): where the last cascade does not, a grid of probes beyond it, as finely spaced as 32 along an axis
     * reach as far with (a cascade's spacing, at most the last's), each tracing the first cascade's directions on to
     * the environment; past it the environment alone. 0: none, the environment past the last cascade. The graph
     * rebuilds.
     */
    public static final Setting<Float> REACH = ProbeVolumeLighting.CASCADES.setting("reach");

    /** Its settings: the cascades kind's tunables ({@link ProbeVolumeLighting.Cascades}). */
    public static final List<Setting<?>> ALL = List.of(CASCADES, PROBES, PROBES_UP, SPACING, DIRECTIONS, INTERVAL,
        VISIBILITY, CLASSIFY, REACH);

    private static final Format RAY_FORMAT = Format.R16G16B16A16_SFLOAT;
    private static final int IRRADIANCE_TEXELS = 8;
    private static final int DEPTH_TEXELS = 16;
    private static final int STATE_BYTES = 48;
    private static final int GRID_BYTES = 16;     // volume.slang's ProbeVolumeData.grid, inner: per cascade
    private static final int LEVEL_BYTES = 80;   // cascades.slang's CascadeLevel
    private static final int MAX_LEVELS = 7;     // volume.slang's PROBE_CASCADES: six and the reach's
    private static final int ROW = 1024;         // paths.slang's PATH_ROW
    private static final int PATH_PROBES = 1 << 16;
    private static final int PATH_INTERVALS = 1 << 17;
    private static final int VISIBILITY_FLAG = 1;
    private static final int CLASSIFY_FLAG = 2;
    /**
     * The grid the cascades' frame lies on ({@link #cameraFromScene}), in meters (2^-17): floats on it are exact within
     * 128 m, and it moves a hit from the camera's frame by 4 micrometers at most, well within the 0.1 mm its shadow rays
     * start off its surface (1/1024 m moved hits through their own surfaces: shadowed by themselves).
     */
    private static final double GRAIN = 0x1p-17;

    public RadianceCascades {
        Objects.requireNonNull(rayTracing, "rayTracing");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(lights, "lights");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(anchor, "anchor");
    }

    /** Cascades lit by what {@code volume} is lit by: its scene, lights, environment and anchor. */
    public static RadianceCascades of(ProbeVolume volume) {
        return new RadianceCascades(volume.rayTracing(), volume.renderer(), volume.lights(), volume.environment(),
            volume.quality(), volume.anchor());
    }

    /**
     * The grids: the cascades and, where {@link #REACH} goes beyond the last, the reach's level ({@code far}): each
     * level's probes along each axis, its directions a side, where it starts in the lists and the ray image (its
     * intervals' rows; the coarser levels' own rays, a tile of the first's directions per probe, side by side below
     * them), where its tiles start in the atlases ({@code tile}: column, row; the first's at the top, the others side
     * by side below, on as many rows as they need), and the atlases' size in tiles.
     */
    record Grid(int cascades, int levels, int farShift, int directions, double spacing, int[][] probes, int[] side,
        int[] firstProbe, int[] row, int[][] near, long[] rays, int width, int height, int totalProbes, long totalRays,
        int[][] tile, int tilesWide, int tilesHigh) {

        /** The most probes along an axis of the reach's level (its tiles in a row of the atlases at most). */
        static final int MOST_FAR = 32;

        static Grid of(int cascades, int across, int up, int directions, double spacing, double reach) {
            int[][] cascade = new int[cascades][];
            for (int c = 0; c < cascades; c++) {
                // Each next cascade as many probes as enclose the one before's (half as many and two more)
                cascade[c] = c == 0 ? new int[] {across, up, across}
                    : new int[] {cascade[c - 1][0] / 2 + 2, cascade[c - 1][1] / 2 + 2, cascade[c - 1][2] / 2 + 2};
            }
            // Beyond the last, where it does not reach as far from the camera (vertically as the first cascade's probes
            // up to across), as many probes as do, as finely spaced as the most along an axis allow (a cascade's
            // spacing, at most the last's)
            double[] wanted = {reach, reach * up / across, reach};
            int farShift = 0;
            while (farShift < cascades - 1 && farReach(MOST_FAR, cascades, farShift, spacing) < reach) {
                farShift++;
            }
            int[] far = new int[3];
            boolean further = false;
            for (int axis = 0; axis < 3; axis++) {
                double last = reachOf(cascade, cascades - 1, axis, spacing);
                further |= wanted[axis] > last;
                int n = 2;
                while (n < MOST_FAR && farReach(n, cascades, farShift, spacing) < wanted[axis]) {
                    n++;
                }
                far[axis] = n;
            }
            int levels = cascades + (further ? 1 : 0);
            int[][] probes = new int[levels][];
            System.arraycopy(cascade, 0, probes, 0, cascades);
            if (further) {
                probes[cascades] = far;
            }
            int[] side = new int[levels];
            int[] firstProbe = new int[levels];
            int[] row = new int[levels];
            int[][] near = new int[levels][];
            int[][] tile = new int[levels][];
            long[] rays = new long[levels];
            int width = 0;
            int height = 0;
            int totalProbes = 0;
            long totalRays = 0;
            for (int c = 0; c < levels; c++) {
                boolean interval = c < cascades;
                side[c] = interval ? directions << c : directions;
                firstProbe[c] = totalProbes;
                row[c] = height;
                int count = probes[c][0] * probes[c][1] * probes[c][2];
                rays[c] = (long) count * ((interval ? side[c] * side[c] : 0) + (c > 0 ? directions * directions : 0));
                if (interval) {
                    width = Math.max(width, probes[c][0] * probes[c][2] * side[c]);
                    height += probes[c][1] * side[c];
                }
                totalProbes += count;
                totalRays += rays[c];
            }
            // The coarser levels' own rays side by side below the intervals', their tiles below the first's
            int column = 0;
            int band = 0;
            int tilesWide = 0;
            for (int c = 0; c < levels; c++) {
                tilesWide = Math.max(tilesWide, probes[c][0] * probes[c][2]);
            }
            int shelf = up;
            int shelfHigh = 0;
            int tileColumn = 0;
            near[0] = new int[] {0, 0};
            tile[0] = new int[] {0, 0};
            for (int c = 1; c < levels; c++) {
                near[c] = new int[] {column, height};
                column += probes[c][0] * probes[c][2] * directions;
                band = Math.max(band, probes[c][1] * directions);
                int wide = probes[c][0] * probes[c][2];
                if (tileColumn + wide > tilesWide) {
                    shelf += shelfHigh;
                    shelfHigh = 0;
                    tileColumn = 0;
                }
                tile[c] = new int[] {tileColumn, shelf};
                tileColumn += wide;
                shelfHigh = Math.max(shelfHigh, probes[c][1]);
            }
            width = Math.max(width, column);
            height += band;
            return new Grid(cascades, levels, farShift, directions, spacing, probes, side, firstProbe, row, near, rays,
                width,
                height, totalProbes, totalRays, tile, tilesWide, shelf + shelfHigh);
        }

        int count(int c) {
            return probes[c][0] * probes[c][1] * probes[c][2];
        }

        /** Whether level {@code c} is the reach's: probes whose own rays go on to the environment, no intervals. */
        boolean far(int c) {
            return c >= cascades;
        }

        /** Level {@code c}'s spacing. */
        double spacing(int c) {
            return spacing * (double) (1L << shift(c, cascades, farShift));
        }

        /** Level {@code c}'s spacing as a power of two of the first's. */
        private static int shift(int c, int cascades, int farShift) {
            return c < cascades ? c : farShift;
        }

        /**
         * Each level's lowest probe's world grid coordinate along an axis, into {@code bases}, the camera {@code k}
         * steps of the first cascade's spacing along it ({@code floor(position / spacing)}). Allocates nothing.
         */
        void bases(long k, int axis, long[] bases) {
            bases(probes, cascades, levels, farShift, k, axis, bases);
        }

        private static void bases(int[][] probes, int cascades, int levels, int farShift, long k, int axis,
            long[] bases) {
            for (int c = 0; c < levels; c++) {
                if (c == 0) {
                    bases[c] = k - probes[0][axis] / 2;
                } else if (c < cascades) {
                    bases[c] = Math.floorDiv(bases[c - 1] - 1, 2); // each next cascade's grid encloses the one before's
                } else {
                    bases[c] = Math.floorDiv(k, 1L << farShift) - probes[c][axis] / 2;
                }
            }
        }

        /** Where level {@code c}'s probe of world grid coordinate {@code base} lies along an axis. */
        double lowest(long base, int c) {
            return lowest(base, c, cascades, farShift, spacing);
        }

        private static double lowest(long base, int c, int cascades, int farShift, double spacing) {
            double s = spacing * (double) (1L << shift(c, cascades, farShift));
            // Probes of the first cascade half a spacing off the grid's planes (walls on whole meters fall between
            // them), the others half the first's spacing further: none on another's planes
            return (base + 0.5) * s + (c == 0 ? 0 : 0.5 * spacing);
        }

        /**
         * Per level and axis, how far from the anchor camera its probes reach on either side wherever the camera is:
         * the box around the camera within which a point has all eight probes around it. The grids snap to their
         * spacing as the camera moves, the same way each period of the coarsest: the nearest the camera comes to a
         * grid's lowest probe is at the start of a step of the first's, to its highest at the end.
         */
        double[][] inner() {
            double[][] inner = new double[levels][];
            for (int c = 0; c < levels; c++) {
                inner[c] = new double[3];
                for (int axis = 0; axis < 3; axis++) {
                    inner[c][axis] = reachOf(probes, cascades, levels, farShift, c, axis, spacing);
                }
            }
            return inner;
        }

        private static double reachOf(int[][] cascade, int c, int axis, double spacing) {
            return reachOf(cascade, cascade.length, cascade.length, 0, c, axis, spacing);
        }

        /** The reach's level of {@code n} probes along an axis: how far it reaches from the camera wherever it is. */
        private static double farReach(int n, int cascades, int farShift, double spacing) {
            int[][] probes = new int[cascades + 1][];
            for (int c = 0; c < cascades; c++) {
                probes[c] = new int[] {2, 2, 2}; // the cascades' sizes do not move the reach's grid
            }
            probes[cascades] = new int[] {n, n, n};
            return reachOf(probes, cascades, cascades + 1, farShift, cascades, 0, spacing);
        }

        private static double reachOf(int[][] probes, int cascades, int levels, int farShift, int c, int axis,
            double spacing) {
            double reach = Double.MAX_VALUE;
            long period = 1L << cascades;
            long[] bases = new long[levels];
            for (long k = -period; k <= period; k++) {
                bases(probes, cascades, levels, farShift, k, axis, bases);
                double s = spacing * (double) (1L << shift(c, cascades, farShift));
                double low = lowest(bases[c], c, cascades, farShift, spacing);
                double high = low + (probes[c][axis] - 1) * s;
                reach = Math.min(reach, Math.min(k * spacing - low, high - (k + 1) * spacing));
            }
            return reach;
        }
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public ProbeVolume.Volume build(GraphBuilder g) {
        if (!g.capabilities().rayTracing()) {
            throw new IllegalStateException("Radiance cascades trace rays: this device has no ray tracing");
        }
        int cascades = g.setting(CASCADES);
        int across = g.setting(PROBES);
        int up = g.setting(PROBES_UP);
        float spacing = g.setting(SPACING);
        int directions = g.setting(DIRECTIONS);
        int layers = g.setting(ProbeVolume.LAYERS);
        Grid grid = Grid.of(cascades, across, up, directions, spacing, g.setting(REACH));
        int tiles = grid.tilesWide() * grid.tilesHigh();
        double coarsest = spacing * (double) (1 << (cascades - 1));
        EngineSettings settings = g.settings();
        Camera camera = anchor.camera();
        Image irradiance = g.persistent("cascade irradiance", ImageDesc.of(ProbeVolume.IRRADIANCE_FORMAT,
            grid.tilesWide() * IRRADIANCE_TEXELS, grid.tilesHigh() * IRRADIANCE_TEXELS));
        Image depth = g.persistent("cascade distances", ImageDesc.of(ProbeVolume.DEPTH_FORMAT,
            grid.tilesWide() * DEPTH_TEXELS, grid.tilesHigh() * DEPTH_TEXELS));
        Buffer states = g.persistentBuffer("cascade states", (long) STATE_BYTES * tiles); // a state per tile
        Buffer data = g.createBuffer("cascade volume", ProbeVolumeDataLayout.BYTES);
        Buffer levels = g.createBuffer("cascade levels", (long) LEVEL_BYTES * MAX_LEVELS);
        Buffer table = g.createBuffer("cascade indirect light", IndirectLightLayout.BYTES);
        Buffer inside = g.createBuffer("cascade probes inside", 4L * grid.totalProbes());
        Image rayImage = g.create("cascade rays", ImageDesc.of(RAY_FORMAT, grid.width(), grid.height()));
        long capacity = grid.totalRays();
        Buffer[] paths = {g.createBuffer("cascade paths", ReferencePathTracer.PATH_BYTES * capacity),
            g.createBuffer("cascade paths (next)", ReferencePathTracer.PATH_BYTES * capacity)};
        Materials materials = renderer.scene().materials();
        int bins = materials.binCount();
        long tableWords = ((6L * bins + 3) / 4) * 4;
        long tablesBytes = 4 * tableWords * layers;
        Buffer binTables = g.createBuffer("cascade bins", tablesBytes + 4 * capacity);
        // The counts, then where the material table has types with geometry hooks, the shadow rays waiting for keep()
        LightRays light = LightRays.of(g, renderer, rayTracing, anchor.view(), grid.width(), grid.height());
        long countsBytes = (4L * (layers + 1) + 15) / 16 * 16;
        Buffer counts = g.createBuffer("cascade counts", countsBytes + (light == null ? 0 : light.bytes()));
        if (light != null) {
            light.place(counts, countsBytes);
        }
        int lightWords = light == null ? 0 : light.words();
        Buffer topLevel = g.request(new TopLevel(rayTracing, renderer));
        SceneData.Buffers scene = g.request(new SceneData(renderer));
        WorldLights.Grid world = g.request(new WorldLights(lights, camera));
        EnvironmentMaps.Maps maps = g.request(new EnvironmentMaps(environment, quality));
        Image brdf = g.request(new EnvironmentMaps.BrdfTable());
        int specularMips = maps.specular().mips();
        int sheenMips = maps.sheen().mips();

        String program = "shaders/probes/cascades.spv";
        Pipeline generate = g.computeProgram(program, "generateMain", CascadePush.BYTES);
        Pipeline merge = g.computeProgram(program, "mergeMain", CascadePush.BYTES);
        String trace = "shaders/pathtracing/trace.spv";
        Pipeline tracing = g.computeProgram(trace, "traceMain", PathPush.BYTES);
        Pipeline layout = g.computeProgram(trace, "layoutMain", PathPush.BYTES);
        Pipeline scatter = g.computeProgram(trace, "scatterMain", PathPush.BYTES);
        ProbeHooks own = new ProbeHooks(g, anchor.view(), data, camera, coarsest); // its hits lit by itself
        Pipeline[] shade = new Pipeline[bins];
        for (int bin = 0; bin < bins; bin++) {
            MaterialType type = materials.binType(bin);
            if (type.hitShader() != null) {
                shade[bin] = g.computeProgram(type.hitShader(), type.hitEntry(), PathPush.BYTES,
                    own.specialization(materials.binFeatures(bin)));
            }
        }
        Frame frame = new Frame(grid, spacing, coarsest, own, grid.inner());

        // No emitters sampled: an empty list for the shaders' pointer (pathtracing/emitters.slang), never null
        Buffer emitters = g.createBuffer("cascade emitters", EmitterListLayout.BYTES);
        pass(g, "cascade setup").write(data, BufferAccess.TRANSFER_DST).write(table, BufferAccess.TRANSFER_DST)
            .write(emitters, BufferAccess.TRANSFER_DST)
            .write(levels, BufferAccess.TRANSFER_DST).write(counts, BufferAccess.TRANSFER_DST)
            .write(binTables, BufferAccess.TRANSFER_DST)
            .read(maps.sh(), BufferAccess.TRANSFER_SRC).read(maps.specular(), Access.SAMPLED_COMPUTE)
            .read(maps.sheen(), Access.SAMPLED_COMPUTE).read(brdf, Access.SAMPLED_COMPUTE)
            .read(irradiance, Access.SAMPLED_COMPUTE).read(depth, Access.SAMPLED_COMPUTE)
            .read(states, BufferAccess.STORAGE_READ)
            .record(ctx -> {
                frame.setup(ctx, settings, camera, data, levels, table, states, irradiance, depth, maps, brdf,
                    specularMips, sheenMips);
                ctx.fill(counts, 0, countsBytes, 0);
                ctx.fill(emitters, 0);
                if (light != null) {
                    light.reset(ctx);
                }
                ctx.fill(binTables, 0, tablesBytes, 0);
            });

        CascadePush[] generatePush = new CascadePush[grid.levels()];
        for (int c = 0; c < grid.levels(); c++) {
            generatePush[c] = new CascadePush();
        }
        pass(g, "cascade rays").read(data, BufferAccess.STORAGE_READ).read(levels, BufferAccess.STORAGE_READ)
            .write(states, BufferAccess.STORAGE_WRITE)
            .write(paths[0], BufferAccess.STORAGE_WRITE).write(counts, BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                for (int c = 0; c < grid.levels(); c++) {
                    int count = grid.count(c);
                    int side = grid.side()[c];
                    int intervals = grid.far(c) ? 0 : side * side;
                    int near = c > 0 ? directions * directions : 0; // its probes' own rays
                    ctx.dispatch(generate, frame.push(generatePush[c], ctx, settings, camera, data, levels, paths[0],
                        counts, null, null, null, null, c), (intervals + near + 63) / 64, Math.min(count, 65535),
                        (count + 65534) / 65535);
                }
            });

        int groupRows = (int) (((capacity + 63) / 64 + ROW - 1) / ROW);
        int pushes = 4 * layers;
        PathPush[] push = new PathPush[pushes];
        for (int i = 0; i < pushes; i++) {
            push[i] = new PathPush();
        }
        for (int b = 0; b < layers; b++) {
            int bounce = b;
            Buffer current = paths[b & 1];
            Buffer next = paths[(b + 1) & 1];
            long binTable = 4 * tableWords * b;
            PathPush tracePush = push[4 * b];
            PathPush layoutPush = push[4 * b + 1];
            PathPush scatterPush = push[4 * b + 2];
            PathPush shadePush = push[4 * b + 3];
            reading(g, scene, topLevel, pass(g, "cascade trace " + b))
                .read(table, BufferAccess.STORAGE_READ)
                .read(maps.specular(), Access.SAMPLED_COMPUTE)
                .write(current, BufferAccess.STORAGE_WRITE).read(counts, BufferAccess.STORAGE_READ)
                .write(binTables, BufferAccess.STORAGE_WRITE).write(rayImage, Access.STORAGE_WRITE)
                .record(ctx -> ctx.dispatch(tracing, pathPush(tracePush, ctx, settings, rayImage, current, null,
                    binTables, counts, table, null, binTable, tablesBytes, bounce, bins, 0, layers), ROW, groupRows,
                    1));
            pass(g, "cascade bins " + b).write(binTables, BufferAccess.STORAGE_WRITE)
                .record(ctx -> ctx.dispatch(layout, pathPush(layoutPush, ctx, settings, null, null, null, binTables,
                    null, null, null, binTable, tablesBytes, bounce, bins, 0, layers), 1));
            pass(g, "cascade scatter " + b).read(current, BufferAccess.STORAGE_READ)
                .read(counts, BufferAccess.STORAGE_READ).write(binTables, BufferAccess.STORAGE_WRITE)
                .record(ctx -> ctx.dispatch(scatter, pathPush(scatterPush, ctx, settings, null, current, null,
                    binTables, counts, null, null, binTable, tablesBytes, bounce, bins, 0, layers), ROW, groupRows,
                    1));
            GraphBuilder.PassBuilder shadePass = reading(g, scene, topLevel, pass(g, "cascade shade " + b))
                .read(current, BufferAccess.STORAGE_READ).write(next, BufferAccess.STORAGE_WRITE)
                .write(counts, BufferAccess.STORAGE_WRITE).read(binTables, BufferAccess.INDIRECT)
                .read(binTables, BufferAccess.STORAGE_READ).write(rayImage, Access.STORAGE_WRITE)
                .read(table, BufferAccess.STORAGE_READ).read(emitters, BufferAccess.STORAGE_READ)
                .read(data, BufferAccess.STORAGE_READ)
                .read(states, BufferAccess.STORAGE_READ).read(irradiance, Access.SAMPLED_COMPUTE)
                .read(depth, Access.SAMPLED_COMPUTE).read(maps.specular(), Access.SAMPLED_COMPUTE)
                .read(maps.sheen(), Access.SAMPLED_COMPUTE).read(brdf, Access.SAMPLED_COMPUTE);
            world.readBy(shadePass);
            shadePass.record(ctx -> {
                for (int bin = 0; bin < shade.length; bin++) {
                    if (shade[bin] != null) {
                        ctx.dispatchIndirect(shade[bin], pathPush(shadePush, ctx, settings, rayImage, current, next,
                            binTables, counts, table, world, binTable, tablesBytes, bounce, bins, bin, layers)
                            .lightRays(lightWords).emitters(ctx.address(emitters)), binTables, binTable + 16L * bin);
                    }
                }
            });
        }

        if (light != null) {
            light.resolve(g, "cascade", name -> pass(g, name), topLevel, rayImage);
        }
        // The reach's probes, then the merges from the last cascade to the first, each writing its level of the volume
        // shading samples
        for (int c = grid.levels() - 1; c >= 0; c--) {
            int cascade = c;
            int count = grid.count(c);
            CascadePush mergePush = new CascadePush();
            reading(g, scene, topLevel, pass(g, grid.far(c) ? "cascade reach" : "cascade merge " + c))
                .read(data, BufferAccess.STORAGE_READ)
                .read(levels, BufferAccess.STORAGE_READ).write(inside, BufferAccess.STORAGE_WRITE)
                .write(rayImage, Access.STORAGE_WRITE)
                .write(irradiance, Access.STORAGE_WRITE).write(depth, Access.STORAGE_WRITE)
                .write(states, BufferAccess.STORAGE_WRITE)
                .record(ctx -> ctx.dispatch(merge, frame.push(mergePush, ctx, settings, camera, data, levels, null,
                    null, inside, rayImage, irradiance, depth, cascade), Math.min(count, 65535),
                    (count + 65534) / 65535, 1));
        }
        return new ProbeVolume.Volume(data, states, irradiance, depth, camera, coarsest);
    }

    /**
     * Declares that {@code pass} reads the scene's buffers and its top level. On the async compute queue the buffers
     * cannot be declared (the graphics queue owns them): the pass is ordered after the top level, which is built from
     * them (as the ray-traced shadows are), and reads them by address.
     */
    private static GraphBuilder.PassBuilder reading(GraphBuilder g, SceneData.Buffers scene, Buffer topLevel,
        GraphBuilder.PassBuilder pass) {
        return (g.setting(ProbeVolume.ASYNC) ? pass : scene.read(pass, BufferAccess.STORAGE_READ))
            .read(topLevel, BufferAccess.ACCELERATION_STRUCTURE_READ);
    }

    /** A pass of the update: on the async compute queue with {@link ProbeVolume#ASYNC}. */
    private static GraphBuilder.PassBuilder pass(GraphBuilder g, String name) {
        GraphBuilder.PassBuilder pass = g.pass(name);
        return g.setting(ProbeVolume.ASYNC) ? pass.async() : pass;
    }

    /**
     * Where the cascades' rays and their hits are taken relative to: the anchor camera less the top level's origin
     * along {@code axis}, on a grid of {@link #GRAIN}. The probes' positions relative to the volume's origin lie on it,
     * and so does that origin ({@link #volumeFromScene}): a ray from a probe starts at exactly the same point of the top
     * level whatever the camera's position, the sums of these being exact (within 128 m of the top level's origin).
     * Taken from the camera itself, rounding moved a ray's start by a micrometer or so as the camera moved, enough to
     * make the first cascade's directions from a probe meet a small bright lamp in one frame and miss it in the next.
     */
    private double cameraFromScene(int axis) {
        return grain(anchor.view().position(axis) - rayTracing.origin(axis));
    }

    /** The cascades' volume's origin less the top level's origin along {@code axis}, on a grid of {@link #GRAIN}. */
    private double volumeFromScene(Camera camera, double coarsest, int axis) {
        return grain(ProbeVolume.Volume.origin(camera.position(axis), coarsest) - rayTracing.origin(axis));
    }

    /** {@code value} to the nearest multiple of {@link #GRAIN}. */
    private static double grain(double value) {
        return Math.rint(value / GRAIN) * GRAIN;
    }

    /** paths.slang's push block for the cascades' rays (PATH_PROBES, PATH_INTERVALS): what a pass does not use 0. */
    private PathPush pathPush(PathPush push, PassContext ctx, EngineSettings settings, Image rayImage, Buffer paths,
        Buffer next, Buffer binTables, Buffer counts, Buffer table, WorldLights.Grid world, long binTable,
        long tablesBytes, int bounce, int bins, int bin, int layers) {
        GpuScene scene = renderer.scene();
        RenderView view = anchor.view();
        float interval = settings.get(INTERVAL) * settings.get(SPACING);
        return push.paths(paths == null ? 0 : ctx.address(paths)).next(next == null ? 0 : ctx.address(next))
            .bins(binTables == null ? 0 : ctx.address(binTables) + binTable)
            .counts(counts == null ? 0 : ctx.address(counts))
            .view(view.address()).scene(scene.tableAddress())
            .rays(rayTracing.sceneAddress(scene))
            .lights(0)
            .indirect(table == null ? 0 : ctx.address(table))
            .world(world == null ? 0 : ctx.address(world.grid()))
            .offset((float) cameraFromScene(0), (float) cameraFromScene(1), (float) cameraFromScene(2))
            .accumulation(rayImage == null ? 0 : ctx.storage(rayImage))
            .size(Float.floatToRawIntBits(interval))
            .shading(bin | bins << 16).sample((int) ctx.frame())
            .limits(layers & 0xff | (255 << 8) | PATH_PROBES | PATH_INTERVALS | bounce << 24)
            .list((int) ((tablesBytes - binTable) / 4));
    }

    /** What the cascades' passes share within a build: the grids. */
    private final class Frame {
        final Grid grid;
        final float spacing;
        final double coarsest;
        final ProbeHooks own; // the cascades' rays' hits' hook block: lit by the cascades
        final double[][] inner; // per cascade and axis: how far its probes reach from the camera wherever it is
        final long[][] base; // per axis, each level's lowest probe's world grid coordinate, while set up

        Frame(Grid grid, float spacing, double coarsest, ProbeHooks own, double[][] inner) {
            this.grid = grid;
            this.own = own;
            this.spacing = spacing;
            this.coarsest = coarsest;
            this.inner = inner;
            base = new long[3][grid.levels()];
        }

        /** The cascades as a probe volume, the levels, the cascades' own table. Allocates nothing. */
        void setup(PassContext ctx, EngineSettings settings, Camera camera, Buffer data, Buffer levels, Buffer table,
            Buffer states, Image irradiance, Image depth, EnvironmentMaps.Maps maps, Image brdf, int specularMips,
            int sheenMips) {
            float interval = settings.get(INTERVAL) * spacing;
            try (MemoryStack stack = stackPush()) {
                long header = stack.ncalloc(16, 1, ProbeVolumeDataLayout.BYTES);
                int[] first = grid.probes()[0];
                memPutInt(header + ProbeVolumeDataLayout.CASCADES, grid.levels());
                memPutInt(header + ProbeVolumeDataLayout.FLAGS, VolumeConstants.PROBE_VOLUME_ANCHORED);
                memPutInt(header + ProbeVolumeDataLayout.IRRADIANCE, ctx.sampled(irradiance));
                memPutInt(header + ProbeVolumeDataLayout.DEPTH, ctx.sampled(depth));
                for (int axis = 0; axis < 3; axis++) {
                    memPutInt(header + ProbeVolumeDataLayout.PROBES + 4L * axis, first[axis]);
                }
                memPutInt(header + ProbeVolumeDataLayout.TILES, grid.tilesWide());
                memPutInt(header + ProbeVolumeDataLayout.TILES + 4, grid.tilesHigh());
                float blend = settings.get(ProbeVolume.BLEND);
                memPutFloat(header + ProbeVolumeDataLayout.NORMAL_BIAS, settings.get(ProbeVolume.NORMAL_BIAS));
                memPutFloat(header + ProbeVolumeDataLayout.VIEW_BIAS, settings.get(ProbeVolume.VIEW_BIAS));
                memPutFloat(header + ProbeVolumeDataLayout.BLEND, blend);
                memPutFloat(header + ProbeVolumeDataLayout.MAX_DISTANCE, settings.get(ProbeVolume.DEPTH_RANGE));
                memPutLong(header + ProbeVolumeDataLayout.STATES, ctx.address(states));
                long level = stack.ncalloc(16, MAX_LEVELS, LEVEL_BYTES);
                for (int axis = 0; axis < 3; axis++) {
                    double origin = ProbeVolume.Volume.origin(camera.position(axis), coarsest);
                    memPutFloat(header + ProbeVolumeDataLayout.ANCHOR + 4L * axis,
                        (float) (camera.position(axis) - origin));
                    grid.bases((long) Math.floor(camera.position(axis) / spacing), axis, base[axis]);
                }
                for (int c = 0; c < grid.levels(); c++) {
                    double s = grid.spacing(c);
                    long at = level + (long) LEVEL_BYTES * c;
                    long cascade = header + ProbeVolumeDataLayout.CASCADE + 16L * c;
                    long gridAt = header + ProbeVolumeDataLayout.GRID + (long) GRID_BYTES * c;
                    long innerAt = header + ProbeVolumeDataLayout.INNER + (long) GRID_BYTES * c;
                    double narrowest = Double.MAX_VALUE;
                    for (int axis = 0; axis < 3; axis++) {
                        double origin = ProbeVolume.Volume.origin(camera.position(axis), coarsest);
                        long lowest = base[axis][c];
                        float position = (float) (grid.lowest(lowest, c) - origin);
                        memPutFloat(at + 4L * axis, position);
                        memPutInt(at + 16 + 4L * axis, (int) lowest);
                        memPutInt(at + 32 + 4L * axis, grid.probes()[c][axis]);
                        memPutFloat(cascade + 4L * axis, position);
                        memPutInt(header + ProbeVolumeDataLayout.BASE + 16L * c + 4L * axis, (int) lowest);
                        memPutInt(gridAt + 4L * axis, grid.probes()[c][axis]);
                        memPutFloat(innerAt + 4L * axis, (float) inner[c][axis]);
                        narrowest = Math.min(narrowest, inner[c][axis]);
                    }
                    memPutFloat(cascade + 12, (float) s);
                    memPutInt(gridAt + 12, grid.tile()[c][0] << 16 | grid.tile()[c][1]);
                    // It blends into the next over the share of its extent the probe volume's cascades do
                    memPutFloat(innerAt + 12, (float) (blend * 2 * narrowest));
                    memPutFloat(at + 12, (float) s);
                    memPutInt(at + 28, grid.side()[c]);
                    memPutInt(at + 44, grid.firstProbe()[c]);
                    float length = interval * (float) (1L << (2 * c));
                    memPutFloat(at + 48, grid.far(c) ? 0 : interval * ((1L << (2 * c)) - 1) / 3f);
                    memPutFloat(at + 52, c + 1 < grid.cascades() ? length : 0);
                    memPutInt(at + 64, grid.row()[c]);
                    memPutInt(at + 68, grid.near()[c][0]);
                    memPutInt(at + 72, grid.near()[c][1]);
                    memPutInt(at + 76, grid.far(c) ? 1 : 0);
                }
                long handle = ctx.unsafe().buffer(data);
                Commands.updateBuffer(ctx.unsafe().commands(), handle, 0, ProbeVolumeDataLayout.BYTES, header);
                Commands.updateBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(levels), 0,
                    (long) LEVEL_BYTES * MAX_LEVELS, level);
                // The cascades' own table, for their rays' hits: the environment, and the first cascade of the frame
                // before (further bounces)
                long t = stack.ncalloc(16, 1, IndirectLightLayout.BYTES);
                memPutInt(t + IndirectLightLayout.FLAGS, IndirectConstants.INDIRECT_ENVIRONMENT);
                memPutInt(t + IndirectLightLayout.SPECULAR, ctx.sampled(maps.specular()));
                memPutInt(t + IndirectLightLayout.SHEEN, ctx.sampled(maps.sheen()));
                memPutInt(t + IndirectLightLayout.BRDF, ctx.sampled(brdf));
                memPutInt(t + IndirectLightLayout.SPECULAR_MIPS, specularMips);
                memPutInt(t + IndirectLightLayout.SHEEN_MIPS, sheenMips);
                memPutFloat(t + IndirectLightLayout.INTENSITY, environment.intensity());
                for (int i = 0; i < 4; i++) {
                    memPutFloat(t + IndirectLightLayout.ROTATION + 4L * i, environment.toEnvironment(i));
                }
                RenderView view = anchor.view();
                long handleTable = ctx.unsafe().buffer(table);
                Commands.updateBuffer(ctx.unsafe().commands(), handleTable, 0, IndirectLightLayout.SH, t);
                own.point(ctx, table); // its hits' hook block, which the view's hook inputs write
            }
            ctx.copy(maps.sh(), 0, table, IndirectLightLayout.SH, 27 * 4L);
        }

        /** cascades.slang's push block: the resources a pass does not use null. */
        CascadePush push(CascadePush push, PassContext ctx, EngineSettings settings, Camera camera, Buffer data,
            Buffer levels, Buffer paths, Buffer counts, Buffer inside, Image rayImage, Image irradiance, Image depth,
            int cascade) {
            GpuScene scene = renderer.scene();
            int flags = (settings.get(VISIBILITY) ? VISIBILITY_FLAG : 0) | (settings.get(CLASSIFY) ? CLASSIFY_FLAG : 0);
            return push.volume(ctx.address(data)).levels(ctx.address(levels))
                .rays(paths == null ? 0 : ctx.address(paths)).counts(counts == null ? 0 : ctx.address(counts))
                .inside(inside == null ? 0 : ctx.address(inside))
                .scene(rayTracing.sceneAddress(scene))
                .toAnchor((float) (volumeFromScene(camera, coarsest, 0) - cameraFromScene(0)),
                    (float) (volumeFromScene(camera, coarsest, 1) - cameraFromScene(1)),
                    (float) (volumeFromScene(camera, coarsest, 2) - cameraFromScene(2)))
                .rayImage(rayImage == null ? 0 : ctx.storage(rayImage))
                .offset((float) cameraFromScene(0), (float) cameraFromScene(1), (float) cameraFromScene(2))
                .irradiance(irradiance == null ? 0 : ctx.storage(irradiance))
                .depth(depth == null ? 0 : ctx.storage(depth))
                .cascade(cascade).cascades(grid.cascades()).flags(flags);
        }
    }
}
