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
import com.truesim.engine.render.layout.IndirectLightLayout;
import com.truesim.engine.render.layout.PathPush;
import com.truesim.engine.render.layout.ProbeVolumeDataLayout;
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
 * theirs start, nor between their coarser and finer directions' starts: no light from the far side of a wall). Beyond
 * its grid, shading takes the probes at the grid's border nearest to the point (their light weighed by what they see),
 * not the environment. Changes show in the frame they happen; only the further bounces (hits lit by the first cascade's
 * probes of the frame before) take a frame per bounce. Hits shading many lights by the world grid's reservoirs pick a
 * light at random, the one noise left (a scene of few lights lists them all). The first cascade's merged light becomes
 * a probe volume of one cascade (irradiance / pi with the sky's visibility, distance moments from its rays), which
 * shading samples as it samples {@link ProbeVolume}'s, through {@link ProbeVolumeLighting} ({@link
 * ProbeVolumeLighting#CASCADES}): the same {@link ProbeVolume.Volume}.
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

    /** Its settings: the cascades kind's tunables ({@link ProbeVolumeLighting.Cascades}). */
    public static final List<Setting<?>> ALL = List.of(CASCADES, PROBES, PROBES_UP, SPACING, DIRECTIONS, INTERVAL,
        VISIBILITY, CLASSIFY);

    private static final Format RAY_FORMAT = Format.R16G16B16A16_SFLOAT;
    private static final int IRRADIANCE_TEXELS = 8;
    private static final int DEPTH_TEXELS = 16;
    private static final int STATE_BYTES = 48;
    private static final int LEVEL_BYTES = 80;   // cascades.slang's CascadeLevel
    private static final int MAX_CASCADES = 6;
    private static final int ROW = 1024;         // paths.slang's PATH_ROW
    private static final int PATH_PROBES = 1 << 16;
    private static final int PATH_INTERVALS = 1 << 17;
    private static final int VISIBILITY_FLAG = 1;
    private static final int CLASSIFY_FLAG = 2;

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

    /** The grids: each cascade's probes along each axis, its directions a side, where it starts in the lists. */
    record Grid(int cascades, int[][] probes, int[] side, int[] firstProbe, int[] row, long[] rays, int width,
        int height, int totalProbes, long totalRays) {

        static Grid of(int cascades, int across, int up, int directions) {
            int[][] probes = new int[cascades][];
            int[] side = new int[cascades];
            int[] firstProbe = new int[cascades];
            int[] row = new int[cascades];
            long[] rays = new long[cascades];
            int width = 0;
            int height = 0;
            int totalProbes = 0;
            long totalRays = 0;
            for (int c = 0; c < cascades; c++) {
                // Each next cascade as many probes as enclose the one before's (half as many and two more)
                probes[c] = c == 0 ? new int[] {across, up, across}
                    : new int[] {probes[c - 1][0] / 2 + 2, probes[c - 1][1] / 2 + 2, probes[c - 1][2] / 2 + 2};
                side[c] = directions << c;
                firstProbe[c] = totalProbes;
                row[c] = height;
                int count = probes[c][0] * probes[c][1] * probes[c][2];
                rays[c] = (long) count * side[c] * side[c];
                width = Math.max(width, probes[c][0] * probes[c][2] * side[c]);
                height += probes[c][1] * side[c];
                totalProbes += count;
                totalRays += rays[c];
            }
            return new Grid(cascades, probes, side, firstProbe, row, rays, width, height, totalProbes, totalRays);
        }

        int count(int c) {
            return probes[c][0] * probes[c][1] * probes[c][2];
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
        Grid grid = Grid.of(cascades, across, up, directions);
        int first = grid.count(0);
        double coarsest = spacing * (double) (1 << (cascades - 1));
        EngineSettings settings = g.settings();
        Camera camera = anchor.camera();
        Image irradiance = g.persistent("cascade irradiance", ImageDesc.of(ProbeVolume.IRRADIANCE_FORMAT,
            across * across * IRRADIANCE_TEXELS, up * IRRADIANCE_TEXELS));
        Image depth = g.persistent("cascade distances", ImageDesc.of(ProbeVolume.DEPTH_FORMAT,
            across * across * DEPTH_TEXELS, up * DEPTH_TEXELS));
        Buffer states = g.persistentBuffer("cascade states", (long) STATE_BYTES * first);
        Buffer data = g.createBuffer("cascade volume", ProbeVolumeDataLayout.BYTES);
        Buffer levels = g.createBuffer("cascade levels", (long) LEVEL_BYTES * MAX_CASCADES);
        Buffer table = g.createBuffer("cascade indirect light", ProbeVolume.ownTableBytes(renderer));
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
        int hook = ProbeVolume.hookIndex(renderer);
        Pipeline[] shade = new Pipeline[bins];
        for (int bin = 0; bin < bins; bin++) {
            MaterialType type = materials.binType(bin);
            if (type.hitShader() != null) {
                shade[bin] = g.computeProgram(type.hitShader(), type.hitEntry(), PathPush.BYTES,
                    ShadingHooks.Mask.NONE.with(hook).constants(materials.binFeatures(bin))); // its own
            }
        }
        Frame frame = new Frame(grid, spacing, coarsest, hook);

        pass(g, "cascade setup").write(data, BufferAccess.TRANSFER_DST).write(table, BufferAccess.TRANSFER_DST)
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
                if (light != null) {
                    light.reset(ctx);
                }
                ctx.fill(binTables, 0, tablesBytes, 0);
            });

        CascadePush[] generatePush = new CascadePush[cascades];
        for (int c = 0; c < cascades; c++) {
            generatePush[c] = new CascadePush();
        }
        pass(g, "cascade rays").read(data, BufferAccess.STORAGE_READ).read(levels, BufferAccess.STORAGE_READ)
            .write(states, BufferAccess.STORAGE_WRITE)
            .write(paths[0], BufferAccess.STORAGE_WRITE).write(counts, BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                for (int c = 0; c < cascades; c++) {
                    int count = grid.count(c);
                    int side = grid.side()[c];
                    ctx.dispatch(generate, frame.push(generatePush[c], ctx, settings, camera, data, levels, paths[0],
                        counts, null, null, null, null, c), (side * side + 63) / 64, Math.min(count, 65535),
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
                .read(table, BufferAccess.STORAGE_READ).read(data, BufferAccess.STORAGE_READ)
                .read(states, BufferAccess.STORAGE_READ).read(irradiance, Access.SAMPLED_COMPUTE)
                .read(depth, Access.SAMPLED_COMPUTE).read(maps.specular(), Access.SAMPLED_COMPUTE)
                .read(maps.sheen(), Access.SAMPLED_COMPUTE).read(brdf, Access.SAMPLED_COMPUTE);
            world.readBy(shadePass);
            shadePass.record(ctx -> {
                for (int bin = 0; bin < shade.length; bin++) {
                    if (shade[bin] != null) {
                        ctx.dispatchIndirect(shade[bin], pathPush(shadePush, ctx, settings, rayImage, current, next,
                            binTables, counts, table, world, binTable, tablesBytes, bounce, bins, bin, layers)
                            .lightRays(lightWords), binTables, binTable + 16L * bin);
                    }
                }
            });
        }

        if (light != null) {
            light.resolve(g, "cascade", name -> pass(g, name), topLevel, rayImage);
        }
        // The merges, from the last cascade to the first, which writes the volume shading samples
        for (int c = cascades - 1; c >= 0; c--) {
            int cascade = c;
            int count = grid.count(c);
            CascadePush mergePush = new CascadePush();
            GraphBuilder.PassBuilder pass = reading(g, scene, topLevel, pass(g, "cascade merge " + c))
                .read(data, BufferAccess.STORAGE_READ)
                .read(levels, BufferAccess.STORAGE_READ).write(inside, BufferAccess.STORAGE_WRITE)
                .write(rayImage, Access.STORAGE_WRITE);
            if (c == 0) {
                pass.write(irradiance, Access.STORAGE_WRITE).write(depth, Access.STORAGE_WRITE)
                    .write(states, BufferAccess.STORAGE_WRITE);
            }
            pass.record(ctx -> ctx.dispatch(merge, frame.push(mergePush, ctx, settings, camera, data, levels, null,
                null, inside, rayImage, cascade == 0 ? irradiance : null, cascade == 0 ? depth : null, cascade),
                Math.min(count, 65535), (count + 65534) / 65535, 1));
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
            .offset((float) (view.position(0) - rayTracing.origin(0)), (float) (view.position(1)
                - rayTracing.origin(1)), (float) (view.position(2) - rayTracing.origin(2)))
            .accumulation(rayImage == null ? 0 : ctx.storage(rayImage))
            .size(Float.floatToRawIntBits(interval))
            .bounce(bounce).binCount(bins).bin(bin).sample((int) ctx.frame())
            .limits(layers & 0xff | (255 << 8) | PATH_PROBES | PATH_INTERVALS)
            .list((int) ((tablesBytes - binTable) / 4));
    }

    /** What the cascades' passes share within a build: the grids. */
    private final class Frame {
        final Grid grid;
        final float spacing;
        final double coarsest;
        final int hook; // the probe volume's shading hook's index: the cascades' rays' hits are lit by the first
        final long[] base = new long[3]; // each cascade's lowest probe's world grid coordinate, while set up

        Frame(Grid grid, float spacing, double coarsest, int hook) {
            this.grid = grid;
            this.hook = hook;
            this.spacing = spacing;
            this.coarsest = coarsest;
        }

        /** The first cascade as a volume of one cascade, the levels, the cascades' own table. Allocates nothing. */
        void setup(PassContext ctx, EngineSettings settings, Camera camera, Buffer data, Buffer levels, Buffer table,
            Buffer states, Image irradiance, Image depth, EnvironmentMaps.Maps maps, Image brdf, int specularMips,
            int sheenMips) {
            float interval = settings.get(INTERVAL) * spacing;
            try (MemoryStack stack = stackPush()) {
                long header = stack.ncalloc(16, 1, ProbeVolumeDataLayout.BYTES);
                int[] first = grid.probes()[0];
                memPutInt(header + ProbeVolumeDataLayout.CASCADES, 1);
                memPutInt(header + ProbeVolumeDataLayout.FLAGS, com.truesim.engine.render.layout.VolumeConstants.PROBE_VOLUME_CLAMP);
                memPutInt(header + ProbeVolumeDataLayout.IRRADIANCE, ctx.sampled(irradiance));
                memPutInt(header + ProbeVolumeDataLayout.DEPTH, ctx.sampled(depth));
                for (int axis = 0; axis < 3; axis++) {
                    memPutInt(header + ProbeVolumeDataLayout.PROBES + 4L * axis, first[axis]);
                }
                memPutFloat(header + ProbeVolumeDataLayout.NORMAL_BIAS, settings.get(ProbeVolume.NORMAL_BIAS));
                memPutFloat(header + ProbeVolumeDataLayout.VIEW_BIAS, settings.get(ProbeVolume.VIEW_BIAS));
                memPutFloat(header + ProbeVolumeDataLayout.BLEND, settings.get(ProbeVolume.BLEND));
                memPutFloat(header + ProbeVolumeDataLayout.MAX_DISTANCE, settings.get(ProbeVolume.DEPTH_RANGE));
                memPutLong(header + ProbeVolumeDataLayout.STATES, ctx.address(states));
                long level = stack.ncalloc(16, MAX_CASCADES, LEVEL_BYTES);
                for (int c = 0; c < grid.cascades(); c++) {
                    double s = spacing * (double) (1 << c);
                    // Probes of the first cascade half a spacing off the grid's planes (walls on whole meters fall
                    // between them), the others half the first's spacing further: none on another's planes
                    double shift = c == 0 ? 0 : 0.5 * spacing;
                    long at = level + (long) LEVEL_BYTES * c;
                    for (int axis = 0; axis < 3; axis++) {
                        double origin = ProbeVolume.Volume.origin(camera.position(axis), coarsest);
                        // Each next cascade's grid encloses the one before's
                        base[axis] = c == 0 ? (long) Math.floor(camera.position(axis) / s) - grid.probes()[0][axis] / 2
                            : Math.floorDiv(base[axis] - 1, 2);
                        float position = (float) ((base[axis] + 0.5) * s + shift - origin);
                        memPutFloat(at + 4L * axis, position);
                        memPutInt(at + 16 + 4L * axis, (int) base[axis]);
                        memPutInt(at + 32 + 4L * axis, grid.probes()[c][axis]);
                        if (c == 0) {
                            memPutFloat(header + ProbeVolumeDataLayout.CASCADE + 4L * axis, position);
                            memPutInt(header + ProbeVolumeDataLayout.BASE + 4L * axis, (int) base[axis]);
                        }
                    }
                    memPutFloat(at + 12, (float) s);
                    memPutInt(at + 28, grid.side()[c]);
                    memPutInt(at + 44, grid.firstProbe()[c]);
                    float length = interval * (float) (1L << (2 * c));
                    memPutFloat(at + 48, interval * ((1L << (2 * c)) - 1) / 3f);
                    memPutFloat(at + 52, c + 1 < grid.cascades() ? length : 0);
                    memPutInt(at + 64, grid.row()[c]);
                }
                memPutFloat(header + ProbeVolumeDataLayout.CASCADE + 12, spacing);
                long handle = ctx.unsafe().buffer(data);
                Commands.updateBuffer(ctx.unsafe().commands(), handle, 0, ProbeVolumeDataLayout.BYTES, header);
                Commands.updateBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(levels), 0,
                    (long) LEVEL_BYTES * MAX_CASCADES, level);
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
                ProbeVolume.writeOwnHook(ctx, renderer, table, hook, ctx.address(data),
                    view.position(0) - ProbeVolume.Volume.origin(camera.position(0), coarsest),
                    view.position(1) - ProbeVolume.Volume.origin(camera.position(1), coarsest),
                    view.position(2) - ProbeVolume.Volume.origin(camera.position(2), coarsest));
            }
            ctx.copy(maps.sh(), 0, table, IndirectLightLayout.SH, 27 * 4L);
        }

        /** cascades.slang's push block: the resources a pass does not use null. */
        CascadePush push(CascadePush push, PassContext ctx, EngineSettings settings, Camera camera, Buffer data,
            Buffer levels, Buffer paths, Buffer counts, Buffer inside, Image rayImage, Image irradiance, Image depth,
            int cascade) {
            RenderView view = anchor.view();
            GpuScene scene = renderer.scene();
            int flags = (settings.get(VISIBILITY) ? VISIBILITY_FLAG : 0) | (settings.get(CLASSIFY) ? CLASSIFY_FLAG : 0);
            return push.volume(ctx.address(data)).levels(ctx.address(levels))
                .rays(paths == null ? 0 : ctx.address(paths)).counts(counts == null ? 0 : ctx.address(counts))
                .inside(inside == null ? 0 : ctx.address(inside))
                .scene(rayTracing.sceneAddress(scene))
                .toAnchor((float) (ProbeVolume.Volume.origin(camera.position(0), coarsest) - view.position(0)),
                    (float) (ProbeVolume.Volume.origin(camera.position(1), coarsest) - view.position(1)),
                    (float) (ProbeVolume.Volume.origin(camera.position(2), coarsest) - view.position(2)))
                .rayImage(rayImage == null ? 0 : ctx.storage(rayImage))
                .offset((float) (view.position(0) - rayTracing.origin(0)), (float) (view.position(1)
                    - rayTracing.origin(1)), (float) (view.position(2) - rayTracing.origin(2)))
                .irradiance(irradiance == null ? 0 : ctx.storage(irradiance))
                .depth(depth == null ? 0 : ctx.storage(depth))
                .cascade(cascade).cascades(grid.cascades()).flags(flags);
        }
    }
}
