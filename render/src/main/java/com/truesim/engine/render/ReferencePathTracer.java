package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.layout.PathPush;
import com.truesim.engine.render.shadows.TopLevel;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

/**
 * The reference: a view's scene path traced, progressively, as ground truth for what the engine's real-time lighting
 * approximates (global illumination, design S0). Provides {@link Keys#SCENE_COLOR} in place of a shading technique
 * ({@code port.replace}), the mean of every sample taken since the view, the scene or the materials last changed:
 * one path per pixel per frame, accumulated in 32-bit floats, until {@link #SAMPLES} are taken. Needs ray tracing.
 *
 * <pre>{@code
 * view.port().add(new ReferencePathTracer(geometry, rayTracing, view, Format.R16G16B16A16_SFLOAT.vk()))
 *     .add(new ClusteredLights(lights, view));  // its lights; without a list, the sun
 * }</pre>
 *
 * <ul>
 *   <li><b>The same scene:</b> the frame's top level ({@link TopLevel}, shared with ray-traced shadows), the scene's
 *   instances, posed copies and materials as drawing reads them, the view's light list ({@link Keys#LIGHT_LIST}: its
 *   lights and the lights the GPU wrote, particles' included) and its indirect-light table's environment (the constant
 *   sky without one), as shading takes them.
 *   <li><b>Every material type shades as itself at hits:</b> each bounce's hits are sorted by shading bin (a type and
 *   its features) and each bin shaded by one dispatch of its type's generated hit entry point
 *   ({@link MaterialType#hitShader}), evaluating the type's {@code surface()} with the engine's features, lit with the
 *   BRDF shading uses (shading.slang's {@code reflected}).
 *   <li><b>Unbiased but for the bounce limit:</b> next-event estimation of every light with a shadow ray, emission and
 *   the environment where paths meet them, Russian roulette from {@link #ROULETTE} bounces on, at most
 *   {@link #BOUNCES} bounces. Back faces of one-sided materials let paths through, as drawing culls them; blended
 *   materials cover by their alpha; transmission refracts through thin walls and into volumes (attenuated inside by
 *   Beer's law). Not traced: dispersion (one IOR for every wavelength), a material's baked occlusion (light is
 *   occluded by the scene itself), screen-space and temporal effects. Lights are points (the sun too, as shading
 *   has it).
 * </ul>
 *
 * <p>Allocates nothing per frame. Samples restart when the camera, the instances (their version), the materials or the
 * render region change, and by {@link #restart}; lights changing do not restart it (particle lights change every
 * frame).
 */
public final class ReferencePathTracer implements Technique {

    /** The most bounces a path takes (a ray from the camera is the first); the graph rebuilds when it changes. */
    public static final Setting<Integer> BOUNCES = Setting.integer("render.reference.bounces", 16, 1, 255,
        "the most bounces a reference path takes");

    /** The bounce from which Russian roulette may end paths by what they still carry. */
    public static final Setting<Integer> ROULETTE = Setting.integer("render.reference.roulette", 3, 1, 255,
        "the bounce from which Russian roulette ends reference paths");

    /** Samples per pixel the reference stops at; read each frame. */
    public static final Setting<Integer> SAMPLES = Setting.integer("render.reference.samples", 65536, 1,
        Integer.MAX_VALUE, "samples per pixel the reference path tracer stops at");

    /**
     * How hits find their lights, where {@link #worldLights} names them: every light of the view's light list, or the
     * world light grid ({@link WorldLights}, as {@link WorldLights#MODE} lays it out); the graph rebuilds when it
     * changes.
     */
    public static final Setting<Lighting> LIGHTS = Setting.choice("render.reference.lights", Lighting.ALL,
        "how reference paths find their lights: every light, or the world light grid");

    /** Its settings. */
    public static final List<Setting<?>> ALL = List.of(BOUNCES, ROULETTE, SAMPLES, LIGHTS);

    /** How hits find their lights ({@link #LIGHTS}). */
    public enum Lighting {
        /** Every light of the view's light list, each with a shadow ray: exact. */
        ALL,
        /** The world light grid of {@link #worldLights}: lists (exact) or reservoirs (unbiased, noisy). */
        GRID
    }

    /** Bytes of a path between bounces (paths.slang's PathState). */
    static final int PATH_BYTES = 80;
    private static final int ROW = 1024; // paths.slang's PATH_ROW
    private static final int ACCUMULATION = Format.R32G32B32A32_SFLOAT.vk();

    private final MeshletRenderer renderer;
    private final RayTracing rayTracing;
    private final SceneView view;
    private final int colorFormat;
    private Lights worldLights; // or null: the light list's
    private String singleShader; // or null: hits shaded by bin, a dispatch per bin
    private String singleEntry;
    private final float[] lastMatrix = new float[16];
    private final double[] lastPosition = new double[3];
    private long lastVersion = Long.MIN_VALUE;
    private long lastMaterials = Long.MIN_VALUE;
    private int lastRegion = -1;
    private long frame = Long.MIN_VALUE; // the frame the sample count was taken for
    private long start;                  // the frame of sample 0
    private boolean restart = true;
    private int sample;                  // this frame's: the samples taken before it
    private int samples;                 // samples taken, this frame's included (if it took one)

    /**
     * {@code geometry}'s scene path traced as {@code view} sees it, at its port's size, into a {@code colorFormat}
     * image (one shaders can store to, not sRGB); added to {@code view}'s port.
     */
    public ReferencePathTracer(SceneGeometry geometry, RayTracing rayTracing, SceneView view, int colorFormat) {
        Objects.requireNonNull(geometry, "geometry");
        this.rayTracing = Objects.requireNonNull(rayTracing, "rayTracing");
        this.view = Objects.requireNonNull(view, "view");
        this.renderer = view.view().renderer();
        this.colorFormat = colorFormat;
    }

    /**
     * Lights hits through a world light grid of {@code lights} around the view's camera ({@link WorldLights}) where
     * {@link #LIGHTS} says {@link Lighting#GRID}; null: the light list's lights always. Rebuilds the graph.
     */
    @AllowAllocation("set up rarely")
    public ReferencePathTracer worldLights(Lights lights) {
        this.worldLights = lights;
        return this;
    }

    /**
     * Shades every hit with {@code entry} of {@code shader} in one dispatch, in place of a dispatch per shading bin of
     * the types' own hit entry points (and the bins' layout and scatter): for measuring a generated shader switching
     * over the types (design D5; null: by bin, the default). The shader takes paths.slang's push block and shades the
     * paths with a hit ({@code shadeHit}). Rebuilds the graph.
     */
    @AllowAllocation("set up rarely")
    public ReferencePathTracer singleHitShader(String shader, String entry) {
        this.singleShader = shader;
        this.singleEntry = entry;
        return this;
    }

    /** Starts the samples anew from the next frame (lights moved, say). */
    public synchronized ReferencePathTracer restart() {
        restart = true;
        return this;
    }

    /** Samples taken into the image of the frame recorded last. */
    public synchronized int samples() {
        return samples;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of(Keys.SCENE_COLOR);
    }

    /** A pass per shading bin's hits: rebuilt when bins are made or the table changes (as the visibility buffer). */
    @Override
    public long buildVersion() {
        Materials materials = renderer.scene().materials();
        return ((long) System.identityHashCode(materials) << 32 | materials.binCount())
            + 31 * (materials.graphBuffersVersion() + 31L * materials.drawnVersion())
            + (worldLights == null ? 0 : 7L * System.identityHashCode(worldLights))
            + (singleShader == null ? 0 : 11L * singleShader.hashCode());
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        view.checkBuilding(g, this);
        if (!g.capabilities().rayTracing()) {
            throw new IllegalStateException("The reference path tracer traces rays: this device has no ray tracing");
        }
        int width = view.width();
        int height = view.height();
        if (width > 0xffff || height > 0xffff) {
            throw new IllegalStateException("The reference traces up to 65,535 pixels per side: " + width + " x "
                + height);
        }
        int bounces = g.setting(BOUNCES);
        EngineSettings settings = g.settings();
        Buffer topLevel = g.request(new TopLevel(rayTracing, renderer));
        SceneData.Buffers scene = g.request(new SceneData(renderer));
        Buffer lights = g.optional(Keys.LIGHT_LIST);
        WorldLights.Grid world = worldLights != null && g.setting(LIGHTS) == Lighting.GRID
            ? g.request(new WorldLights(worldLights, view.camera())) : null;
        IndirectInputs.Set indirect = IndirectInputs.of(g);
        Materials materials = renderer.scene().materials();
        int bins = materials.binCount();
        Pipeline[] shade = new Pipeline[bins];
        for (int bin = 0; bin < bins; bin++) {
            MaterialType type = materials.binType(bin);
            if (type.hitShader() != null) {
                shade[bin] = g.computeProgram(type.hitShader(), type.hitEntry(), PathPush.BYTES,
                    materials.binFeatures(bin));
            }
        }
        Pipeline single = singleShader == null ? null
            : g.computeProgram(singleShader, singleEntry, PathPush.BYTES); // every hit in one dispatch: no bins
        String shader = "shaders/pathtracing/trace.spv";
        Pipeline generate = g.computeProgram(shader, "generateMain", PathPush.BYTES);
        Pipeline trace = g.computeProgram(shader, "traceMain", PathPush.BYTES);
        Pipeline layout = g.computeProgram(shader, "layoutMain", PathPush.BYTES);
        Pipeline scatter = g.computeProgram(shader, "scatterMain", PathPush.BYTES);
        Pipeline resolve = g.computeProgram(shader, "resolveMain", PathPush.BYTES);
        long capacity = (long) width * height;
        Buffer[] paths = {g.createBuffer("reference paths", PATH_BYTES * capacity),
            g.createBuffer("reference paths (next)", PATH_BYTES * capacity)};
        // Per bounce a bin table (paths.slang: dispatch and count, first slots, cursors), then one slot list.
        long tableWords = ((6L * bins + 3) / 4) * 4;
        long tablesBytes = 4 * tableWords * bounces;
        Buffer binTables = g.createBuffer("reference bins", tablesBytes + 4 * capacity);
        // The counts, then where the material table has types with geometry hooks, the shadow rays waiting for keep()
        LightRays light = LightRays.of(g, renderer, rayTracing, view.view(), width, height);
        long countsBytes = (4L * (bounces + 1) + 15) / 16 * 16;
        Buffer counts = g.createBuffer("reference counts", countsBytes + (light == null ? 0 : light.bytes()));
        if (light != null) {
            light.place(counts, countsBytes);
        }
        int lightWords = light == null ? 0 : light.words();
        Image accumulation = g.persistent("reference accumulation", ImageDesc.of(Format.of(ACCUMULATION), width,
            height));
        Image color = g.create("scene color", ImageDesc.of(Format.of(colorFormat), width, height));
        int pushes = 2 + 3 * bounces;
        PathPush[] push = new PathPush[pushes + bounces];
        for (int i = 0; i < push.length; i++) {
            push[i] = new PathPush();
        }
        g.pass("reference start").write(counts, BufferAccess.TRANSFER_DST).write(binTables, BufferAccess.TRANSFER_DST)
            .record(ctx -> {
                if (light != null) {
                    light.reset(ctx); // every frame: no light rays waiting in one that takes no sample
                }
                if (!tracing(ctx, color, settings)) {
                    return;
                }
                ctx.fill(counts, 0, 4, ctx.regionWidth(color) * ctx.regionHeight(color)); // the camera's paths
                ctx.fill(counts, 4, 4L * bounces, 0);
                ctx.fill(binTables, 0, tablesBytes, 0);
            });
        g.pass("reference camera rays").write(paths[0], BufferAccess.STORAGE_WRITE)
            .write(accumulation, Access.STORAGE_WRITE)
            .record(ctx -> {
                if (tracing(ctx, color, settings)) {
                    ctx.dispatch(generate, push(push[0], ctx, color, accumulation, paths[0], null, null,
                        null, null, null, 0, tablesBytes, 0, bins, 0, settings), (ctx.regionWidth(color) + 7) / 8,
                        (ctx.regionHeight(color) + 7) / 8, 1);
                }
            });
        for (int b = 0; b < bounces; b++) {
            int bounce = b;
            Buffer current = paths[b & 1];
            Buffer next = paths[(b + 1) & 1];
            long table = 4 * tableWords * b;
            PathPush tracePush = push[1 + 3 * b];
            PathPush layoutPush = push[2 + 3 * b];
            PathPush scatterPush = push[3 + 3 * b];
            PathPush shadePush = push[pushes + b];
            GraphBuilder.PassBuilder tracePass = scene.read(g.pass("reference trace " + b), BufferAccess.STORAGE_READ)
                .read(topLevel, BufferAccess.ACCELERATION_STRUCTURE_READ)
                .write(current, BufferAccess.STORAGE_WRITE).read(counts, BufferAccess.STORAGE_READ)
                .write(binTables, BufferAccess.STORAGE_WRITE).write(accumulation, Access.STORAGE_WRITE);
            if (indirect != null) {
                indirect.readBy(tracePass, Access.SAMPLED_COMPUTE, BufferAccess.STORAGE_READ);
            }
            tracePass.record(ctx -> {
                if (tracing(ctx, color, settings)) {
                    ctx.dispatch(trace, push(tracePush, ctx, color, accumulation, current, null, binTables, counts,
                        null, indirect, table, tablesBytes, bounce, bins, 0, settings), ROW, groupRows(ctx, color), 1);
                }
            });
            if (single == null) { // hits sorted by shading bin: laid out, scattered
                g.pass("reference bins " + b).write(binTables, BufferAccess.STORAGE_WRITE).record(ctx -> {
                    if (tracing(ctx, color, settings)) {
                        ctx.dispatch(layout, push(layoutPush, ctx, color, null, null, null, binTables, null, null,
                            null, table, tablesBytes, bounce, bins, 0, settings), 1);
                    }
                });
                g.pass("reference scatter " + b).read(current, BufferAccess.STORAGE_READ)
                    .read(counts, BufferAccess.STORAGE_READ).write(binTables, BufferAccess.STORAGE_WRITE)
                    .record(ctx -> {
                        if (tracing(ctx, color, settings)) {
                            ctx.dispatch(scatter, push(scatterPush, ctx, color, null, current, null, binTables,
                                counts, null, null, table, tablesBytes, bounce, bins, 0, settings), ROW,
                                groupRows(ctx, color), 1);
                        }
                    });
            }
            GraphBuilder.PassBuilder shadePass = scene.read(g.pass("reference shade " + b), BufferAccess.STORAGE_READ)
                .read(topLevel, BufferAccess.ACCELERATION_STRUCTURE_READ)
                .read(current, BufferAccess.STORAGE_READ).write(next, BufferAccess.STORAGE_WRITE)
                .write(counts, BufferAccess.STORAGE_WRITE).read(binTables, BufferAccess.INDIRECT)
                .read(binTables, BufferAccess.STORAGE_READ).write(accumulation, Access.STORAGE_WRITE);
            if (lights != null) {
                shadePass.read(lights, BufferAccess.STORAGE_READ);
            }
            if (world != null) {
                world.readBy(shadePass);
            }
            if (indirect != null) {
                indirect.readBy(shadePass, Access.SAMPLED_COMPUTE, BufferAccess.STORAGE_READ);
            }
            shadePass.record(ctx -> {
                if (!tracing(ctx, color, settings)) {
                    return;
                }
                if (single != null) { // every hit by one shader
                    ctx.dispatch(single, push(shadePush, ctx, color, accumulation, current, next, binTables, counts,
                        lights, indirect, table, tablesBytes, bounce, bins, 0, settings)
                        .world(world == null ? 0 : ctx.address(world.grid())).lightRays(lightWords), ROW,
                        groupRows(ctx, color), 1);
                    return;
                }
                for (int bin = 0; bin < shade.length; bin++) {
                    if (shade[bin] != null) {
                        ctx.dispatchIndirect(shade[bin], push(shadePush, ctx, color, accumulation, current, next,
                            binTables, counts, lights, indirect, table, tablesBytes, bounce, bins, bin, settings)
                            .world(world == null ? 0 : ctx.address(world.grid())).lightRays(lightWords), binTables,
                            table + 16L * bin);
                    }
                }
            });
        }
        if (light != null) {
            light.resolve(g, "reference", g::pass, topLevel, accumulation);
        }
        PathPush resolvePush = push[pushes - 1];
        g.pass("reference resolve").read(accumulation, Access.STORAGE_READ).write(color, Access.STORAGE_WRITE)
            .record(ctx -> {
                int taken = samplesTaken(ctx, color, settings);
                ctx.dispatch(resolve, push(resolvePush, ctx, color, accumulation, null, null, null, null, null,
                    null, 0, tablesBytes, 0, bins, ctx.storage(color), settings).sample(taken),
                    (ctx.regionWidth(color) + 7) / 8, (ctx.regionHeight(color) + 7) / 8, 1);
            });
        g.provide(Keys.SCENE_COLOR, color);
    }

    /** Rows of path dispatches for the region's paths: groups of 64, {@link #ROW} groups a row. */
    private static int groupRows(PassContext ctx, Image color) {
        long groups = ((long) ctx.regionWidth(color) * ctx.regionHeight(color) + 63) / 64;
        return (int) ((groups + ROW - 1) / ROW);
    }

    /** Whether this frame takes a sample: fewer than {@link #SAMPLES} taken. */
    private boolean tracing(PassContext ctx, Image color, EngineSettings settings) {
        return sampleIndex(ctx, color, settings) >= 0;
    }

    /** The samples in the image after this frame. */
    private synchronized int samplesTaken(PassContext ctx, Image color, EngineSettings settings) {
        sampleIndex(ctx, color, settings);
        return samples;
    }

    /**
     * This frame's sample (the samples taken before it), or -1 if it takes none; decided once per frame, by whichever
     * pass records first (passes may record on several threads), restarting where the view, scene or materials changed.
     */
    private synchronized int sampleIndex(PassContext ctx, Image color, EngineSettings settings) {
        long now = ctx.frame();
        if (now != frame) {
            frame = now;
            if (changed(ctx.regionWidth(color) | ctx.regionHeight(color) << 16)) {
                start = now;
            }
            long index = now - start;
            int max = settings.get(SAMPLES);
            sample = index < max ? (int) index : -1;
            samples = (int) Math.min(index + 1, max);
        }
        return sample;
    }

    /** Whether what the samples show changed since they started, noting it as it is now. */
    private boolean changed(int region) {
        boolean changed = restart;
        restart = false;
        float[] matrix = view.camera().viewProjection();
        for (int i = 0; i < 16; i++) {
            if (matrix[i] != lastMatrix[i]) {
                changed = true;
                lastMatrix[i] = matrix[i];
            }
        }
        for (int axis = 0; axis < 3; axis++) {
            double p = view.view().position(axis);
            if (p != lastPosition[axis]) {
                changed = true;
                lastPosition[axis] = p;
            }
        }
        long version = renderer.scene().version();
        long materials = renderer.scene().materials().version();
        if (version != lastVersion || materials != lastMaterials || region != lastRegion) {
            changed = true;
            lastVersion = version;
            lastMaterials = materials;
            lastRegion = region;
        }
        return changed;
    }

    /** paths.slang's PathPush, into {@code push}: the resources a pass does not declare null (0). */
    private PathPush push(PathPush push, PassContext ctx, Image color, Image accumulation, Buffer paths, Buffer next,
        Buffer binTables, Buffer counts, Buffer lights, IndirectInputs.Set indirect, long table, long tablesBytes,
        int bounce, int bins, int bin, EngineSettings settings) {
        GpuScene scene = renderer.scene();
        int index = sampleIndex(ctx, color, settings);
        return push.paths(paths == null ? 0 : ctx.address(paths)).next(next == null ? 0 : ctx.address(next))
            .bins(binTables == null ? 0 : ctx.address(binTables) + table)
            .counts(counts == null ? 0 : ctx.address(counts))
            .view(view.view().address()).scene(scene.tableAddress())
            .rays(rayTracing.sceneAddress(scene))
            .lights(lights == null ? 0 : ctx.address(lights))
            .indirect(indirect == null ? 0 : ctx.address(indirect.table())).world(0)
            .offset((float) (view.view().position(0) - rayTracing.origin(0)),
                (float) (view.view().position(1) - rayTracing.origin(1)),
                (float) (view.view().position(2) - rayTracing.origin(2)))
            .accumulation(accumulation == null ? 0 : ctx.storage(accumulation))
            .size(ctx.regionWidth(color) | ctx.regionHeight(color) << 16)
            .bounce(bounce).binCount(bins).bin(bin).sample(Math.max(index, 0))
            .limits(settings.get(BOUNCES) & 0xff | (settings.get(ROULETTE) & 0xff) << 8)
            .list((int) ((tablesBytes - table) / 4)); // the slot list follows every bounce's table
    }

    /**
     * Registers its settings in {@code registry} for discovery ({@link SettingRegistry#all(String)}), hidden. The
     * plugin of the module holding it calls this.
     */
    public static void register(SettingRegistry registry) {
        registry.addHidden(ALL.toArray(new Setting<?>[0]));
    }
}
