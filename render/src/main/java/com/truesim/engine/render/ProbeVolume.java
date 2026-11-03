package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Request;
import com.truesim.engine.render.layout.IndirectConstants;
import com.truesim.engine.render.layout.IndirectLightLayout;
import com.truesim.engine.render.layout.InvalidatePush;
import com.truesim.engine.render.layout.PathPush;
import com.truesim.engine.render.layout.ProbePush;
import com.truesim.engine.render.layout.ProbeVolumeDataLayout;
import com.truesim.engine.render.shadows.TopLevel;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

/**
 * A ray-traced probe volume (global illumination's second stage, S2; DDGI: Majercik, Guertin, Nowrouzezahrai and
 * McGuire, JCGT 2019, and the 2021 paper's relocation, classification and scrolling cascades): cascades of probes
 * around {@code anchor}'s camera, each a grid of {@link #PROBES} x {@link #PROBES_UP} x {@link #PROBES} probes
 * {@link #SPACING} apart in the first and twice as far in each next, which scroll with the camera (a probe's slot is
 * its world grid coordinate modulo the grid: only probes that come into the grid start anew). Each probe stores, in
 * octahedral atlases, its irradiance (as irradiance / pi, 6 x 6 texels and a border) with the fraction of its rays that
 * met nothing (the sky's visibility, for specular occlusion), and the mean and mean square of the distance to the
 * nearest surface per direction (14 x 14 and a border), which lets a shaded point ignore probes that cannot see it
 * (Chebyshev's bound): no light through walls a probe sees as walls.
 *
 * <p>A frame-level shared request: every view (both eyes of a headset, design D7) asking for an equal volume reads one
 * update. Each frame (for every probe, or a share of them in turns, {@link #BUDGET}):
 *
 * <ul>
 *   <li><b>Rays shaded by type:</b> {@link #RAYS} rays per probe, turned anew each frame, as paths of the reference
 *   path tracer's wavefront ({@link ReferencePathTracer}, its probe mode): hits sorted by shading bin, each bin shaded
 *   by its material type's hit entry point with the same BRDF, its lights through the world light grid
 *   ({@link WorldLights}: the sun, punctual lights, the lights the GPU writes) with shadow rays, emission, the
 *   environment where rays meet nothing, and the volume's own light of the frame before at the hit: infinite bounces,
 *   one frame per further bounce.
 *   <li><b>Lag bounded (design D3):</b> no added latency (this frame's rays, this frame's image). A probe's history
 *   holds at most {@link #MAX_FRAMES} updates (a still scene's lag); probes near an instance that moved, changed or was
 *   skinned, or within the reach of a light that changed, restart their histories at once ({@link #INVALIDATE}), all
 *   of them when a directional light or the environment changes; a probe whose update disagrees with its history by
 *   more than its noise allows restarts it too ({@link #ADAPTIVE}: changes nothing announced).
 *   <li><b>Relocation and classification:</b> a probe inside geometry (most of a fixed set of rays meeting back faces)
 *   moves out of it, or is turned off; one too close to a surface moves away.
 * </ul>
 *
 * <p>Shading reads it through each view's indirect-light table ({@link ProbeVolumeLighting}): the volume's diffuse
 * light replaces the environment's spherical harmonics in every shading path, transparency included. Needs ray
 * tracing. Allocates nothing per frame.
 *
 * @param rayTracing   the scene for rays
 * @param renderer     the renderer whose scene is traced
 * @param lights       the scene's lights (with those the GPU writes)
 * @param environment  the distant light rays meet when they leave the scene
 * @param quality      how the environment is filtered: as the views' {@link EnvironmentLighting} filters it
 * @param anchor       the view whose camera the cascades follow and whose positions rays are relative to
 */
public record ProbeVolume(RayTracing rayTracing, MeshletRenderer renderer, Lights lights, Environment environment,
    EnvironmentMaps.Quality quality, SceneView anchor) implements Request<ProbeVolume.Volume> {

    /** Cascades, each twice as far apart as the one before; the graph rebuilds. */
    public static final Setting<Integer> CASCADES = Setting.integer("render.gi.volume.cascades", 3, 1, 6,
        "cascades of the probe volume");
    /** Probes along each horizontal axis of a cascade; the graph rebuilds. */
    public static final Setting<Integer> PROBES = Setting.integer("render.gi.volume.probes", 16, 2, 32,
        "probes along each horizontal axis of a probe volume cascade");
    /** Probes along the vertical axis of a cascade; the graph rebuilds. */
    public static final Setting<Integer> PROBES_UP = Setting.integer("render.gi.volume.probesUp", 8, 2, 32,
        "probes along the vertical axis of a probe volume cascade");
    /** The first cascade's spacing, in meters; the graph rebuilds. */
    public static final Setting<Float> SPACING = Setting.decimal("render.gi.volume.spacing", 1, 0.05f, 1000,
        "the spacing of the probe volume's first cascade, in meters");
    /** Rays per probe and update; the graph rebuilds. */
    public static final Setting<Integer> RAYS = Setting.integer("render.gi.volume.rays", 128, 32, 256,
        "rays per probe and update");
    /** Of them, a fixed set never turned, for relocation and classification (at most half); the graph rebuilds. */
    public static final Setting<Integer> FIXED_RAYS = Setting.integer("render.gi.volume.fixedRays", 32, 8, 128,
        "rays per probe in a fixed set, for relocation and classification");
    /** The share of probes updated each frame, in turns (1: all; invalidated probes always). */
    public static final Setting<Float> BUDGET = Setting.decimal("render.gi.volume.budget", 1, 0.01f, 1,
        "the share of probes updated each frame, in turns");
    /** Surfaces a probe ray passes through at most (holes, what blended surfaces let pass); the graph rebuilds. */
    public static final Setting<Integer> LAYERS = Setting.integer("render.gi.volume.layers", 4, 1, 16,
        "surfaces a probe ray may pass through, plus one");
    /** A shaded point moves this far along its normal before it looks up probes, of the spacing. */
    public static final Setting<Float> NORMAL_BIAS = Setting.decimal("render.gi.volume.normalBias", 0.25f, 0, 2,
        "how far a shaded point moves along its normal before sampling probes, of their spacing");
    /** And this far towards the viewer, of the spacing. */
    public static final Setting<Float> VIEW_BIAS = Setting.decimal("render.gi.volume.viewBias", 0.25f, 0, 2,
        "how far a shaded point moves towards the viewer before sampling probes, of their spacing");
    /** Of a cascade's probes from its edge: where it blends into the next one. */
    public static final Setting<Float> BLEND = Setting.decimal("render.gi.volume.blend", 0.2f, 0.01f, 0.5f,
        "of a probe cascade from its edge, the part over which it blends into the next");
    /** The longest distance a probe's moments hold, of its spacing. */
    public static final Setting<Float> DEPTH_RANGE = Setting.decimal("render.gi.volume.depthRange", 1.5f, 0.5f, 100,
        "the longest distance a probe's distance moments hold, of its spacing");
    /** Probes move off surfaces near them. */
    public static final Setting<Boolean> RELOCATE = Setting.bool("render.gi.volume.relocate", true,
        "probes move off surfaces near them");
    /** Probes inside geometry are turned off. */
    public static final Setting<Boolean> CLASSIFY = Setting.bool("render.gi.volume.classify", true,
        "probes inside geometry are turned off");
    /** A probe's history holds at most this many updates: the lag of a still scene (design D3: 30). */
    public static final Setting<Integer> MAX_FRAMES = Setting.integer("render.gi.response.maxFrames", 30, 1, 1024,
        "updates a probe's history holds at most (the lag of a still scene)");
    /** A probe whose update disagrees with its history beyond its noise restarts it (changes nothing announced). */
    public static final Setting<Boolean> ADAPTIVE = Setting.bool("render.gi.response.adaptive", true,
        "a probe restarts its history where an update disagrees with it beyond its noise");
    /** How many deviations of an update's noise a texel may differ by before its probe restarts. */
    public static final Setting<Float> SENSITIVITY = Setting.decimal("render.gi.response.sensitivity", 4, 0.5f, 100,
        "deviations of a probe update's noise beyond which its history restarts");
    /** Probes near what the scene says changed (instances, lights, the environment) restart at once. */
    public static final Setting<Boolean> INVALIDATE = Setting.bool("render.gi.response.invalidate", true,
        "probes near changed instances and lights restart their histories at once");
    /** A changed light restarts the probes within this many times its reach (the surfaces it lit, seen from afar). */
    public static final Setting<Float> LIGHT_MARGIN = Setting.decimal("render.gi.response.lightMargin", 2, 1, 100,
        "how many times its reach a changed light restarts probes within");

    /** Its settings. */
    public static final List<Setting<?>> ALL = List.of(CASCADES, PROBES, PROBES_UP, SPACING, RAYS, FIXED_RAYS, BUDGET,
        LAYERS, NORMAL_BIAS, VIEW_BIAS, BLEND, DEPTH_RANGE, RELOCATE, CLASSIFY, MAX_FRAMES, ADAPTIVE, SENSITIVITY,
        INVALIDATE, LIGHT_MARGIN);

    static final Format IRRADIANCE_FORMAT = Format.R16G16B16A16_SFLOAT;
    static final Format DEPTH_FORMAT = Format.R16G16_SFLOAT;
    private static final Format RAY_FORMAT = Format.R16G16B16A16_SFLOAT;
    private static final int IRRADIANCE_TEXELS = 8;
    private static final int DEPTH_TEXELS = 16;
    private static final int STATE_BYTES = 32;
    private static final int RAY_ROW = 32;       // update.slang's
    private static final int ROW = 1024;         // paths.slang's PATH_ROW
    private static final int PATH_PROBES = 1 << 16;
    private static final int ADAPTIVE_FLAG = 1;
    private static final int RESET_ALL = 2;
    private static final int RELOCATE_FLAG = 4;
    private static final int CLASSIFY_FLAG = 8;

    public ProbeVolume {
        Objects.requireNonNull(rayTracing, "rayTracing");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(lights, "lights");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(anchor, "anchor");
    }

    /** A volume whose environment is filtered at {@link EnvironmentMaps#QUALITY}. */
    public ProbeVolume(RayTracing rayTracing, MeshletRenderer renderer, Lights lights, Environment environment,
        SceneView anchor) {
        this(rayTracing, renderer, lights, environment, EnvironmentMaps.QUALITY, anchor);
    }

    /**
     * What views read of a volume: its header ({@code data}, indirect.slang's {@code ProbeVolumeData}), the probes'
     * states, the atlases; and where its origin is (the first cascade's spacing and the coarsest one's).
     */
    public static final class Volume {
        final Buffer data;
        final Buffer states;
        final Image irradiance;
        final Image depth;
        final Camera camera;
        final double coarsest;

        Volume(Buffer data, Buffer states, Image irradiance, Image depth, Camera camera, double coarsest) {
            this.data = data;
            this.states = states;
            this.irradiance = irradiance;
            this.depth = depth;
            this.camera = camera;
            this.coarsest = coarsest;
        }

        /** Shading reads the volume through {@code set}. */
        @AllowAllocation("building the graph, when it changes")
        void readBy(IndirectInputs.Set set) {
            set.sample(irradiance).sample(depth).read(data).read(states);
        }

        /** The data's address. */
        long address(PassContext ctx) {
            return ctx.address(data);
        }

        /** Along {@code axis}, the volume's origin: the anchor camera snapped to the coarsest cascade's spacing. */
        double origin(int axis) {
            return origin(camera.position(axis), coarsest);
        }

        static double origin(double position, double coarsest) {
            return Math.floor(position / coarsest) * coarsest;
        }
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Volume build(GraphBuilder g) {
        if (!g.capabilities().rayTracing()) {
            throw new IllegalStateException("The probe volume traces rays: this device has no ray tracing");
        }
        int cascades = g.setting(CASCADES);
        int across = g.setting(PROBES);
        int up = g.setting(PROBES_UP);
        float spacing = g.setting(SPACING);
        int rays = g.setting(RAYS);
        int fixed = Math.min(g.setting(FIXED_RAYS), rays / 2);
        int layers = g.setting(LAYERS);
        int count = across * across * up * cascades;
        double coarsest = spacing * (double) (1 << (cascades - 1));
        EngineSettings settings = g.settings();
        Camera camera = anchor.camera();
        Image irradiance = g.persistent("probe irradiance", ImageDesc.of(IRRADIANCE_FORMAT,
            across * across * IRRADIANCE_TEXELS, up * cascades * IRRADIANCE_TEXELS));
        Image depth = g.persistent("probe distances", ImageDesc.of(DEPTH_FORMAT, across * across * DEPTH_TEXELS,
            up * cascades * DEPTH_TEXELS));
        Buffer states = g.persistentBuffer("probe states", (long) STATE_BYTES * count);
        Buffer data = g.createBuffer("probe volume", ProbeVolumeDataLayout.BYTES);
        Buffer table = g.createBuffer("probe volume indirect light", IndirectLightLayout.BYTES);
        Image rayImage = g.create("probe rays", ImageDesc.of(RAY_FORMAT, rays * RAY_ROW,
            (count + RAY_ROW - 1) / RAY_ROW));
        long capacity = (long) count * rays;
        Buffer[] paths = {g.createBuffer("probe paths", ReferencePathTracer.PATH_BYTES * capacity),
            g.createBuffer("probe paths (next)", ReferencePathTracer.PATH_BYTES * capacity)};
        Materials materials = renderer.scene().materials();
        int bins = materials.binCount();
        long tableWords = ((6L * bins + 3) / 4) * 4;
        long tablesBytes = 4 * tableWords * layers;
        Buffer binTables = g.createBuffer("probe bins", tablesBytes + 4 * capacity);
        Buffer counts = g.createBuffer("probe counts", 4L * (layers + 1));
        Buffer status = g.createBuffer("probe status", 16);
        int instanceCapacity = renderer.scene().capacity();
        Buffer previousInstances = g.persistentBuffer("probe instances before", 64L * instanceCapacity);
        Buffer previousLights = g.persistentBuffer("probe lights before", (long) Lights.LIGHT_BYTES
            * lights.capacity());
        GeometryPool pool = renderer.pool();
        int skinnedWords = (pool.capacity().meshes() + 31) / 32;
        Buffer skinned = g.createBuffer("probe skinned meshes", 4L * skinnedWords);
        Buffer topLevel = g.request(new TopLevel(rayTracing, renderer));
        SceneData.Buffers scene = g.request(new SceneData(renderer));
        WorldLights.Grid world = g.request(new WorldLights(lights, camera));
        EnvironmentMaps.Maps maps = g.request(new EnvironmentMaps(environment, quality));
        Image brdf = g.request(new EnvironmentMaps.BrdfTable());
        int specularMips = maps.specular().mips();
        int sheenMips = maps.sheen().mips();

        String update = "shaders/probes/update.spv";
        Pipeline prepare = g.computeProgram(update, "prepareMain", ProbePush.BYTES);
        Pipeline generate = g.computeProgram(update, "generateMain", ProbePush.BYTES);
        Pipeline blend = g.computeProgram(update, "blendMain", ProbePush.BYTES);
        Pipeline instancesPipeline = g.computeProgram("shaders/probes/invalidate.spv", "instancesMain",
            InvalidatePush.BYTES);
        Pipeline lightsPipeline = g.computeProgram("shaders/probes/invalidate.spv", "lightsMain",
            InvalidatePush.BYTES);
        String trace = "shaders/pathtracing/trace.spv";
        Pipeline tracing = g.computeProgram(trace, "traceMain", PathPush.BYTES);
        Pipeline layout = g.computeProgram(trace, "layoutMain", PathPush.BYTES);
        Pipeline scatter = g.computeProgram(trace, "scatterMain", PathPush.BYTES);
        Pipeline[] shade = new Pipeline[bins];
        for (int bin = 0; bin < bins; bin++) {
            MaterialType type = materials.binType(bin);
            if (type.hitShader() != null) {
                shade[bin] = g.computeProgram(type.hitShader(), type.hitEntry(), PathPush.BYTES,
                    materials.binFeatures(bin));
            }
        }
        Frame frame = new Frame(across, up, cascades, spacing, rays, fixed, count, skinnedWords);

        g.pass("probe volume setup").write(data, BufferAccess.TRANSFER_DST).write(table, BufferAccess.TRANSFER_DST)
            .write(status, BufferAccess.TRANSFER_DST).write(counts, BufferAccess.TRANSFER_DST)
            .write(binTables, BufferAccess.TRANSFER_DST).write(skinned, BufferAccess.TRANSFER_DST)
            .read(maps.sh(), BufferAccess.TRANSFER_SRC).read(maps.specular(), Access.SAMPLED_COMPUTE)
            .read(maps.sheen(), Access.SAMPLED_COMPUTE).read(brdf, Access.SAMPLED_COMPUTE)
            .read(irradiance, Access.SAMPLED_COMPUTE).read(depth, Access.SAMPLED_COMPUTE)
            .read(states, BufferAccess.STORAGE_READ)
            .record(ctx -> {
                frame.setup(ctx, settings, camera, coarsest, data, table, states, irradiance, depth, maps, brdf,
                    specularMips, sheenMips, skinned);
                ctx.fill(status, 0);
                ctx.fill(counts, 0);
                ctx.fill(binTables, 0, tablesBytes, 0);
            });

        InvalidatePush invalidatePush = new InvalidatePush();
        scene.read(g.pass("probe invalidation"), BufferAccess.STORAGE_READ).read(data, BufferAccess.STORAGE_READ)
            .read(skinned, BufferAccess.STORAGE_READ).write(previousInstances, BufferAccess.STORAGE_WRITE)
            .write(previousLights, BufferAccess.STORAGE_WRITE).write(states, BufferAccess.STORAGE_WRITE)
            .write(status, BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (!settings.get(INVALIDATE)) {
                    return;
                }
                frame.invalidatePush(invalidatePush, ctx, settings, camera, coarsest, data, scene.instances(),
                    previousInstances, previousLights, status, skinned);
                int instances = renderer.scene().capacity();
                ctx.dispatch(instancesPipeline, invalidatePush.instanceCount(instances), (instances + 63) / 64);
                int lightCount = Math.max(lights.count(), frame.lightsBefore);
                ctx.dispatch(lightsPipeline, invalidatePush, Math.max(1, (lightCount + 63) / 64));
                frame.lightsCounted(lights.count());
            });

        ProbePush preparePush = new ProbePush();
        g.pass("probe preparation").read(data, BufferAccess.STORAGE_READ).read(status, BufferAccess.STORAGE_READ)
            .write(states, BufferAccess.STORAGE_WRITE)
            .record(ctx -> ctx.dispatch(prepare, frame.probePush(preparePush, ctx, settings, camera, coarsest, data,
                status, null, null, null, null, null), (count + 63) / 64));

        ProbePush generatePush = new ProbePush();
        g.pass("probe rays").read(data, BufferAccess.STORAGE_READ).read(states, BufferAccess.STORAGE_READ)
            .write(paths[0], BufferAccess.STORAGE_WRITE).write(counts, BufferAccess.STORAGE_WRITE)
            .record(ctx -> ctx.dispatch(generate, frame.probePush(generatePush, ctx, settings, camera, coarsest, data,
                null, paths[0], counts, null, null, null), (rays + 63) / 64, Math.min(count, 65535),
                (count + 65534) / 65535));

        int pushes = 4 * layers;
        PathPush[] push = new PathPush[pushes];
        for (int i = 0; i < pushes; i++) {
            push[i] = new PathPush();
        }
        int groupRows = (int) (((capacity + 63) / 64 + ROW - 1) / ROW);
        for (int b = 0; b < layers; b++) {
            int bounce = b;
            Buffer current = paths[b & 1];
            Buffer next = paths[(b + 1) & 1];
            long binTable = 4 * tableWords * b;
            PathPush tracePush = push[4 * b];
            PathPush layoutPush = push[4 * b + 1];
            PathPush scatterPush = push[4 * b + 2];
            PathPush shadePush = push[4 * b + 3];
            scene.read(g.pass("probe trace " + b), BufferAccess.STORAGE_READ)
                .read(topLevel, BufferAccess.ACCELERATION_STRUCTURE_READ).read(table, BufferAccess.STORAGE_READ)
                .read(maps.specular(), Access.SAMPLED_COMPUTE)
                .write(current, BufferAccess.STORAGE_WRITE).read(counts, BufferAccess.STORAGE_READ)
                .write(binTables, BufferAccess.STORAGE_WRITE).write(rayImage, Access.STORAGE_WRITE)
                .record(ctx -> ctx.dispatch(tracing, pathPush(tracePush, ctx, rayImage, current, null, binTables,
                    counts, table, null, binTable, tablesBytes, bounce, bins, 0, layers), ROW, groupRows, 1));
            g.pass("probe bins " + b).write(binTables, BufferAccess.STORAGE_WRITE)
                .record(ctx -> ctx.dispatch(layout, pathPush(layoutPush, ctx, null, null, null, binTables, null,
                    null, null, binTable, tablesBytes, bounce, bins, 0, layers), 1));
            g.pass("probe scatter " + b).read(current, BufferAccess.STORAGE_READ)
                .read(counts, BufferAccess.STORAGE_READ).write(binTables, BufferAccess.STORAGE_WRITE)
                .record(ctx -> ctx.dispatch(scatter, pathPush(scatterPush, ctx, null, current, null, binTables,
                    counts, null, null, binTable, tablesBytes, bounce, bins, 0, layers), ROW, groupRows, 1));
            GraphBuilder.PassBuilder shadePass = scene.read(g.pass("probe shade " + b), BufferAccess.STORAGE_READ)
                .read(topLevel, BufferAccess.ACCELERATION_STRUCTURE_READ)
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
                        ctx.dispatchIndirect(shade[bin], pathPush(shadePush, ctx, rayImage, current, next, binTables,
                            counts, table, world, binTable, tablesBytes, bounce, bins, bin, layers), binTables,
                            binTable + 16L * bin);
                    }
                }
            });
        }

        ProbePush blendPush = new ProbePush();
        g.pass("probe blending").read(data, BufferAccess.STORAGE_READ).read(rayImage, Access.STORAGE_READ)
            .write(irradiance, Access.STORAGE_WRITE).write(depth, Access.STORAGE_WRITE)
            .write(states, BufferAccess.STORAGE_WRITE)
            .record(ctx -> ctx.dispatch(blend, frame.probePush(blendPush, ctx, settings, camera, coarsest, data,
                null, null, null, rayImage, irradiance, depth), Math.min(count, 65535), (count + 65534) / 65535,
                1));
        return new Volume(data, states, irradiance, depth, camera, coarsest);
    }

    /** paths.slang's push block for the probes' rays (PATH_PROBES): the resources a pass does not declare 0. */
    private PathPush pathPush(PathPush push, PassContext ctx, Image rayImage, Buffer paths, Buffer next,
        Buffer binTables, Buffer counts, Buffer table, WorldLights.Grid world, long binTable, long tablesBytes,
        int bounce, int bins, int bin, int layers) {
        GpuScene scene = renderer.scene();
        RenderView view = anchor.view();
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
            .size(0)
            .bounce(bounce).binCount(bins).bin(bin).sample((int) ctx.frame())
            .limits(layers & 0xff | (255 << 8) | PATH_PROBES)
            .list((int) ((tablesBytes - binTable) / 4));
    }

    /** What the volume's passes share within a build: the grid, and what changed between frames. */
    private final class Frame {
        final int across;
        final int up;
        final int cascades;
        final float spacing;
        final int rays;
        final int fixed;
        final int count;
        final int skinnedWords;
        final int[] skinnedBits;
        final int[] skinsBefore;
        int lightsBefore;
        long environmentVersion = Long.MIN_VALUE;
        float environmentIntensity = Float.NaN;
        final float[] environmentRotation = new float[4];
        long resetFrame = Long.MIN_VALUE; // the frame every probe restarts in (the environment changed)
        long firstFrame = Long.MIN_VALUE;

        Frame(int across, int up, int cascades, float spacing, int rays, int fixed, int count, int skinnedWords) {
            this.across = across;
            this.up = up;
            this.cascades = cascades;
            this.spacing = spacing;
            this.rays = rays;
            this.fixed = fixed;
            this.count = count;
            this.skinnedWords = skinnedWords;
            this.skinnedBits = new int[skinnedWords];
            this.skinsBefore = new int[skinnedWords * 32];
        }

        /** The volume's header and its own indirect-light table, the skinned meshes' bits. Allocates nothing. */
        synchronized void setup(PassContext ctx, EngineSettings settings, Camera camera, double coarsest, Buffer data,
            Buffer table, Buffer states, Image irradiance, Image depth, EnvironmentMaps.Maps maps, Image brdf,
            int specularMips, int sheenMips, Buffer skinned) {
            long now = ctx.frame();
            if (firstFrame == Long.MIN_VALUE) {
                firstFrame = now;
                resetFrame = now;
            }
            // The environment changed: every probe restarts
            boolean changed = environment.version() != environmentVersion
                || environment.intensity() != environmentIntensity;
            for (int i = 0; i < 4; i++) {
                changed |= environment.toEnvironment(i) != environmentRotation[i];
                environmentRotation[i] = environment.toEnvironment(i);
            }
            if (changed) {
                resetFrame = now;
            }
            environmentVersion = environment.version();
            environmentIntensity = environment.intensity();
            try (MemoryStack stack = stackPush()) {
                long header = stack.ncalloc(16, 1, ProbeVolumeDataLayout.BYTES);
                memPutInt(header + ProbeVolumeDataLayout.CASCADES, cascades);
                memPutInt(header + ProbeVolumeDataLayout.IRRADIANCE, ctx.sampled(irradiance));
                memPutInt(header + ProbeVolumeDataLayout.DEPTH, ctx.sampled(depth));
                memPutInt(header + ProbeVolumeDataLayout.PROBES, across);
                memPutInt(header + ProbeVolumeDataLayout.PROBES + 4, up);
                memPutInt(header + ProbeVolumeDataLayout.PROBES + 8, across);
                memPutFloat(header + ProbeVolumeDataLayout.NORMAL_BIAS, settings.get(NORMAL_BIAS));
                memPutFloat(header + ProbeVolumeDataLayout.VIEW_BIAS, settings.get(VIEW_BIAS));
                memPutFloat(header + ProbeVolumeDataLayout.BLEND, settings.get(BLEND));
                memPutFloat(header + ProbeVolumeDataLayout.MAX_DISTANCE, settings.get(DEPTH_RANGE));
                memPutLong(header + ProbeVolumeDataLayout.STATES, ctx.address(states));
                int[] probes = {across, up, across};
                for (int c = 0; c < cascades; c++) {
                    double s = spacing * (double) (1 << c);
                    for (int axis = 0; axis < 3; axis++) {
                        double origin = Volume.origin(camera.position(axis), coarsest);
                        long base = (long) Math.floor(camera.position(axis) / s) - probes[axis] / 2;
                        // Probes sit half a spacing off the grid's planes: walls on whole meters fall between them
                        memPutFloat(header + ProbeVolumeDataLayout.CASCADE + 16L * c + 4L * axis,
                            (float) ((base + 0.5) * s - origin));
                        memPutInt(header + ProbeVolumeDataLayout.BASE + 16L * c + 4L * axis, (int) base);
                    }
                    memPutFloat(header + ProbeVolumeDataLayout.CASCADE + 16L * c + 12, (float) s);
                }
                Commands.updateBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(data), 0,
                    ProbeVolumeDataLayout.BYTES, header);
                // The volume's own table, for its rays' hits: the environment, and the volume itself
                long t = stack.ncalloc(16, 1, IndirectLightLayout.BYTES);
                memPutInt(t + IndirectLightLayout.FLAGS, IndirectConstants.INDIRECT_ENVIRONMENT
                    | IndirectConstants.INDIRECT_PROBES);
                memPutInt(t + IndirectLightLayout.SPECULAR, ctx.sampled(maps.specular()));
                memPutInt(t + IndirectLightLayout.SHEEN, ctx.sampled(maps.sheen()));
                memPutInt(t + IndirectLightLayout.BRDF, ctx.sampled(brdf));
                memPutInt(t + IndirectLightLayout.SPECULAR_MIPS, specularMips);
                memPutInt(t + IndirectLightLayout.SHEEN_MIPS, sheenMips);
                memPutFloat(t + IndirectLightLayout.INTENSITY, environment.intensity());
                for (int i = 0; i < 4; i++) {
                    memPutFloat(t + IndirectLightLayout.ROTATION + 4L * i, environment.toEnvironment(i));
                }
                memPutLong(t + IndirectLightLayout.VOLUME, ctx.address(data));
                RenderView view = anchor.view();
                for (int axis = 0; axis < 3; axis++) {
                    memPutFloat(t + IndirectLightLayout.VOLUME_OFFSET + 4L * axis,
                        (float) (view.position(axis) - Volume.origin(camera.position(axis), coarsest)));
                }
                long handle = ctx.unsafe().buffer(table);
                Commands.updateBuffer(ctx.unsafe().commands(), handle, 0, IndirectLightLayout.SH, t);
                Commands.updateBuffer(ctx.unsafe().commands(), handle, IndirectLightLayout.RESERVED,
                    IndirectLightLayout.BYTES - IndirectLightLayout.RESERVED, t + IndirectLightLayout.RESERVED);
                // The meshes skinned since the frame before
                Deformation deformation = renderer.pool().deformation();
                java.util.Arrays.fill(skinnedBits, 0);
                for (int i = 0; i < deformation.size(); i++) {
                    int copy = deformation.copyAt(i);
                    if (copy >= skinsBefore.length) {
                        continue;
                    }
                    int skins = deformation.skins(copy);
                    if (skins != skinsBefore[copy]) {
                        skinsBefore[copy] = skins;
                        skinnedBits[copy >> 5] |= 1 << (copy & 31);
                    }
                }
                long bits = stack.ncalloc(16, skinnedWords, 4);
                for (int i = 0; i < skinnedWords; i++) {
                    memPutInt(bits + 4L * i, skinnedBits[i]);
                }
                Commands.updateBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(skinned), 0, 4L * skinnedWords,
                    bits);
            }
            ctx.copy(maps.sh(), 0, table, IndirectLightLayout.SH, 27 * 4L);
        }

        /** The lights this frame's invalidation compared: the next one's before. */
        synchronized void lightsCounted(int count) {
            lightsBefore = count;
        }

        /** invalidate.slang's push block. */
        InvalidatePush invalidatePush(InvalidatePush push, PassContext ctx, EngineSettings settings, Camera camera,
            double coarsest, Buffer data, Buffer instances, Buffer previousInstances, Buffer previousLights,
            Buffer status, Buffer skinned) {
            double x = Volume.origin(camera.position(0), coarsest);
            double y = Volume.origin(camera.position(1), coarsest);
            double z = Volume.origin(camera.position(2), coarsest);
            return push.volume(ctx.address(data)).instances(ctx.address(instances))
                .previous(ctx.address(previousInstances)).meshes(renderer.pool().meshTable().deviceAddress())
                .lights(lights.lightsAddress()).previousLights(ctx.address(previousLights))
                .status(ctx.address(status)).skinned(ctx.address(skinned))
                .origin((float) x, (float) y, (float) z)
                .originLow((float) (x - (float) x), (float) (y - (float) y), (float) (z - (float) z))
                .lightCount(lights.count()).previousLightCount(lightsBefore)
                .margin(settings.get(LIGHT_MARGIN)).cutoff(settings.get(ClusteredLights.CUTOFF));
        }

        /** update.slang's push block: the resources a pass does not declare null. */
        synchronized ProbePush probePush(ProbePush push, PassContext ctx, EngineSettings settings, Camera camera,
            double coarsest, Buffer data, Buffer status, Buffer paths, Buffer counts, Image rayImage,
            Image irradiance, Image depth) {
            long now = ctx.frame();
            float budget = settings.get(BUDGET);
            int flags = (settings.get(ADAPTIVE) ? ADAPTIVE_FLAG : 0) | (now == resetFrame ? RESET_ALL : 0)
                | (settings.get(RELOCATE) ? RELOCATE_FLAG : 0) | (settings.get(CLASSIFY) ? CLASSIFY_FLAG : 0);
            RenderView view = anchor.view();
            push.volume(ctx.address(data)).rays(paths == null ? 0 : ctx.address(paths))
                .counts(counts == null ? 0 : ctx.address(counts)).status(status == null ? 0 : ctx.address(status))
                .toAnchor((float) (Volume.origin(camera.position(0), coarsest) - view.position(0)),
                    (float) (Volume.origin(camera.position(1), coarsest) - view.position(1)),
                    (float) (Volume.origin(camera.position(2), coarsest) - view.position(2)))
                .rayCount(rays).fixedRays(fixed)
                .rayImage(rayImage == null ? 0 : ctx.storage(rayImage))
                .irradiance(irradiance == null ? 0 : ctx.storage(irradiance))
                .depth(depth == null ? 0 : ctx.storage(depth))
                .frame((int) now).period(Math.max(1, Math.round(1 / budget))).maxFrames(settings.get(MAX_FRAMES))
                .flags(flags).sensitivity(settings.get(SENSITIVITY));
            // This frame's turn of the rays: a random unit quaternion from the frame's number (Shoemake 1992)
            long h = mix(now * 0x9E3779B97F4A7C15L + 1);
            double u1 = (h >>> 11) * 0x1.0p-53;
            h = mix(h);
            double u2 = (h >>> 11) * 0x1.0p-53;
            h = mix(h);
            double u3 = (h >>> 11) * 0x1.0p-53;
            double a = Math.sqrt(1 - u1);
            double b = Math.sqrt(u1);
            push.rotation((float) (a * Maths.sin(2 * Math.PI * u2)), (float) (a * Maths.cos(2 * Math.PI * u2)),
                (float) (b * Maths.sin(2 * Math.PI * u3)), (float) (b * Maths.cos(2 * Math.PI * u3)));
            return push;
        }
    }

    /** SplitMix64's mixing of {@code z}. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Barriers between the invalidation's dispatches are not needed: both only set bits by atomics. */
    @SuppressWarnings("unused")
    private static void barrier(PassContext ctx) {
        Barriers.memory(ctx.unsafe().commands(), VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }
}
