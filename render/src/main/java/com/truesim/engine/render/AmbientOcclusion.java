package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.layout.AoPush;
import com.truesim.engine.render.shadows.ShadowSettings;
import com.truesim.engine.render.shadows.TopLevel;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.settings.Techniques;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ambient occlusion of a view's opaque surfaces (global illumination's first stage, S1; issue #34 for {@link #RAYS}),
 * as the kind chosen for {@link #SLOT} ({@code render.ao}) does it: how much of the hemisphere around each pixel's
 * normal is open within its radius, cosine-weighted. It replaces what shading added through the surface outputs ({@link
 * SurfaceOutputs}), as screen-space reflections do: only the diffuse light from all around the normal (the
 * environment's harmonics, the constant sky, or the probe volume where one lights the view) is darkened, weighed by the
 * pixel's diffuse weight (design D12); direct light, emission and the environment's reflections are not. The
 * reflections get a separate specular occlusion (each kind's {@code specular}): the cone of the visible directions
 * around the bent normal against the GGX lobe's cone, which it also provides under {@link Keys#SPECULAR_OCCLUSION} for
 * {@link ScreenSpaceReflections} to dim the environment it replaces by.
 *
 * <ul>
 *   <li>{@link #GTAO} ({@code gtao}; Jimenez et al. 2016, as Intel's XeGTAO): slices through the hemisphere searched
 *   along the screen for each side's highest horizon, the visible arc integrated in closed form; samples fade out over
 *   the last {@code falloff} of the radius; surfaces are taken to be infinitely thick (light never passes behind them);
 *   <li>{@link #BITMASK} ({@code bitmask}, the default; Therrien, Levesque and Gilet 2023): surfaces {@code thickness}
 *   thick, each slice's occlusion a mask of 32 sectors of equal cosine-weighted measure, and one short-range bounce of
 *   the scene's color from the sectors each sample covers ({@code bounce}), added through the diffuse weight;
 *   <li>{@link #RAYS} ({@code rays}, #34): {@code rays} cosine-distributed rays per pixel by ray queries into the
 *   frame's top level, as long as the radius: exact, off-screen occluders included, noisy; denoised
 *   ({@link Denoiser}). Devices without ray tracing, or a view without a ray tracing scene, get {@link #BITMASK}.
 * </ul>
 *
 * <p>The screen-space ones are single-frame by default (a noise pattern fixed in time, then the spatial filter: no
 * lag); with {@code temporal}, and always for rays, the kind's {@code denoiser} accumulates over frames, the
 * history found through the view's motion vectors ({@link Keys#MOTION}, or the camera's reprojection without a
 * {@link MotionVectors}). It modifies {@link Keys#SCENE_COLOR} at {@link Anchor#OPAQUE}, after the background, before
 * the reflections; per view, within its render region. Needs a shading technique providing the surface outputs.
 *
 * <p>A game's own kind ({@link Techniques#register}) makes a {@link Technique} built in this one's place, given this
 * one (its {@link #view()} and {@link #rayTracing()}): it modifies {@link Keys#SCENE_COLOR} and must provide
 * {@link Keys#SPECULAR_OCCLUSION} ({@link #SPECULAR_FORMAT}, 1 where nothing is occluded); this one's id, order and
 * keys stay.
 *
 * <pre>{@code
 * view.port().add(new AmbientOcclusion(view, rayTracing));  // rayTracing: null without rays
 * AmbientOcclusion.SLOT.choose(graph.settings(), AmbientOcclusion.GTAO);      // render.ao=gtao
 * graph.settings().set(AmbientOcclusion.GTAO.setting("radius"), 1f);         // render.ao.gtao.radius=1.0
 * }</pre>
 */
public final class AmbientOcclusion implements Technique, SurfaceOutputs.Consumer {

    /** The id of a view's ambient occlusion. */
    public static final Id<AmbientOcclusion> ID = Id.of("truesim.ambientOcclusion", AmbientOcclusion.class);

    /** How the engine's kinds find occlusion: the shader's variants. */
    enum Mode {
        /** Horizons searched in screen space, the visible arc integrated in closed form (infinitely thick surfaces). */
        GTAO,
        /** A mask of sectors per slice, surfaces of a thickness, and one bounce of the scene's color. */
        BITMASK,
        /** Rays traced by ray queries (devices with ray tracing; elsewhere {@link #BITMASK}), denoised. */
        RAYS
    }

    /** What the image shows ({@link #DEBUG}): the scene, or the occlusion alone. */
    public enum Show {
        /** The scene with its occlusion. */
        SCENE,
        /** The occlusion, as grey. */
        OCCLUSION,
        /** The specular occlusion, as grey. */
        SPECULAR,
        /** BITMASK's bounce alone. */
        BOUNCE
    }

    /** Where ambient occlusion plugs in: {@code render.ao}, a kind's id; the graph rebuilds when it changes. */
    public static final TechniqueSlot<AmbientOcclusion, Technique> SLOT = TechniqueSlot.of("render.ao", "bitmask", true,
        "ambient occlusion: off, GTAO, the visibility bitmask (with a bounce) or traced rays",
        techniques -> techniques.register(AmbientOcclusion.SLOT, AmbientOcclusion.GTAO)
            .register(AmbientOcclusion.SLOT, AmbientOcclusion.BITMASK)
            .register(AmbientOcclusion.SLOT, AmbientOcclusion.RAYS));

    /**
     * GTAO's tunables. {@code temporal} and {@code denoiser} are read when the graph builds (it rebuilds when they
     * change), the others as frames record.
     *
     * @param radius how far occluders count, in meters
     * @param slices slices of the hemisphere per pixel
     * @param steps samples per side of each slice
     * @param falloff the part of the radius over which samples fade out
     * @param multiBounce Jimenez et al.'s fit of interreflections between occluders by albedo
     * @param specular the specular occlusion of the environment's reflections
     * @param temporal accumulate over frames (a noise pattern moving in time)
     * @param denoiser the denoiser accumulating over frames, with {@code temporal}
     */
    public record Gtao(
        @Tunable(value = "how far ambient occlusion looks for occluders, in meters", min = 0.01, max = 100,
            formerly = "render.ao.radius") float radius,
        @Tunable(value = "slices of the hemisphere screen-space ambient occlusion searches per pixel", min = 1,
            max = 16, formerly = "render.ao.slices") int slices,
        @Tunable(value = "samples along each side of a slice of screen-space ambient occlusion", min = 1, max = 64,
            formerly = "render.ao.steps") int steps,
        @Tunable(value = "the part of the radius over which GTAO's samples fade out", min = 0.01, max = 1,
            formerly = "render.ao.falloff") float falloff,
        @Tunable(value = "GTAO's multiple-bounce fit by albedo", formerly = "render.ao.multiBounce")
        boolean multiBounce,
        @Tunable(value = "specular occlusion of the environment's reflections from the bent normal",
            formerly = "render.ao.specular") boolean specular,
        @Tunable(value = "screen-space ambient occlusion accumulates over frames", formerly = "render.ao.temporal")
        boolean temporal,
        @Tunable(value = "the denoiser of temporal ambient occlusion: SVGF or ReBLUR", formerly = "render.ao.denoiser")
        Denoiser.Mode denoiser) implements TechniqueConfig {
    }

    /**
     * The visibility bitmask's tunables. {@code temporal} and {@code denoiser} are read when the graph builds, the
     * others as frames record.
     *
     * @param radius how far occluders count, in meters
     * @param slices slices of the hemisphere per pixel
     * @param steps samples per side of each slice
     * @param thickness how thick surfaces are taken to be behind what the depth shows, in meters along the view
     * @param bounce one bounce of the scene's color from the occluders
     * @param specular the specular occlusion of the environment's reflections
     * @param temporal accumulate over frames (a noise pattern moving in time)
     * @param denoiser the denoiser accumulating over frames, with {@code temporal}
     */
    public record Bitmask(
        @Tunable(value = "how far ambient occlusion looks for occluders, in meters", min = 0.01, max = 100,
            formerly = "render.ao.radius") float radius,
        @Tunable(value = "slices of the hemisphere screen-space ambient occlusion searches per pixel", min = 1,
            max = 16, formerly = "render.ao.slices") int slices,
        @Tunable(value = "samples along each side of a slice of screen-space ambient occlusion", min = 1, max = 64,
            formerly = "render.ao.steps") int steps,
        @Tunable(value = "how thick the visibility bitmask takes surfaces to be, in meters", min = 0.001, max = 100,
            formerly = "render.ao.thickness") float thickness,
        @Tunable(value = "the visibility bitmask's short-range bounce of the scene's color",
            formerly = "render.ao.bounce") boolean bounce,
        @Tunable(value = "specular occlusion of the environment's reflections from the bent normal",
            formerly = "render.ao.specular") boolean specular,
        @Tunable(value = "screen-space ambient occlusion accumulates over frames", formerly = "render.ao.temporal")
        boolean temporal,
        @Tunable(value = "the denoiser of temporal ambient occlusion: SVGF or ReBLUR", formerly = "render.ao.denoiser")
        Denoiser.Mode denoiser) implements TechniqueConfig {
    }

    /**
     * Ray-traced ambient occlusion's tunables. {@code denoiser} is read when the graph builds, the others as frames
     * record.
     *
     * @param radius how far occluders count (the rays' length), in meters
     * @param rays rays per pixel each frame
     * @param multiBounce Jimenez et al.'s fit of interreflections between occluders by albedo
     * @param specular the specular occlusion of the environment's reflections
     * @param denoiser the denoiser accumulating over frames
     */
    public record Rays(
        @Tunable(value = "how far ambient occlusion looks for occluders, in meters", min = 0.01, max = 100,
            formerly = "render.ao.radius") float radius,
        @Tunable(value = "rays per pixel of ray-traced ambient occlusion", min = 1, max = 1024,
            formerly = "render.ao.rays") int rays,
        @Tunable(value = "GTAO's multiple-bounce fit by albedo, for rays", formerly = "render.ao.multiBounce")
        boolean multiBounce,
        @Tunable(value = "specular occlusion of the environment's reflections from the bent normal",
            formerly = "render.ao.specular") boolean specular,
        @Tunable(value = "the denoiser of ray-traced ambient occlusion: SVGF or ReBLUR",
            formerly = "render.ao.denoiser") Denoiser.Mode denoiser) implements TechniqueConfig {
    }

    /** Horizons searched in screen space, the visible arc integrated in closed form (infinitely thick surfaces). */
    public static final TechniqueKind<Gtao, AmbientOcclusion, Technique> GTAO = TechniqueKind.of(SLOT, "gtao",
        new Gtao(0.5f, 2, 8, 0.2f, false, true, false, Denoiser.Mode.SVGF),
        "horizons searched in screen space, the visible arc integrated in closed form",
        (ao, config) -> ao.new Engine(Mode.GTAO));
    /** A mask of sectors per slice, surfaces of a thickness, and one bounce of the scene's color: the default. */
    public static final TechniqueKind<Bitmask, AmbientOcclusion, Technique> BITMASK = TechniqueKind.of(SLOT, "bitmask",
        new Bitmask(0.5f, 2, 8, 0.25f, true, true, false, Denoiser.Mode.SVGF),
        "a mask of sectors per slice, surfaces of a thickness, and one bounce of the scene's color",
        (ao, config) -> ao.new Engine(Mode.BITMASK));
    /** Rays traced by ray queries, denoised: devices with ray tracing, elsewhere {@link #BITMASK}. */
    public static final TechniqueKind<Rays, AmbientOcclusion, Technique> RAYS = TechniqueKind.of(SLOT, "rays",
        new Rays(0.5f, 1, false, true, Denoiser.Mode.SVGF), "rays traced by ray queries, denoised",
        (ao, config) -> ao.new Engine(Mode.RAYS));

    /** What the image shows (debugging): every kind of the engine's. */
    public static final Setting<Show> DEBUG = Setting.choice("render.ao.debug", Show.SCENE,
        "what the image shows: the scene, or ambient occlusion's terms alone");

    /** Its settings: the choice and the engine's kinds' tunables, and what the image shows. */
    public static final List<Setting<?>> ALL = all();

    /** The specular occlusion's format. */
    static final Format SPECULAR_FORMAT = Format.R8_UNORM;

    private static final int AO_BOUNCE = 1;
    private static final int AO_MULTI_BOUNCE = 2;
    private static final int AO_SPECULAR = 4;
    private static final int AO_SHOW_AO = 8;
    private static final int AO_SHOW_SPECULAR = 16;
    private static final int AO_SHOW_BOUNCE = 32;

    private final SceneView view;
    private final RayTracing rayTracing; // or null: no RAYS
    private final AoPush tracePush = new AoPush();
    private final AoPush compositePush = new AoPush();
    private final Map<TechniqueKind<?, ?, ?>, Technique> made = new IdentityHashMap<>(); // per kind, once chosen

    /** Ambient occlusion in {@code view}, traced in {@code rayTracing} for {@link #RAYS} (null: none). */
    public AmbientOcclusion(SceneView view, RayTracing rayTracing) {
        this.view = Objects.requireNonNull(view, "view");
        this.rayTracing = rayTracing;
    }

    private static List<Setting<?>> all() {
        List<Setting<?>> all = new java.util.ArrayList<>();
        all.add(SLOT.setting());
        all.addAll(GTAO.settings());
        all.addAll(BITMASK.settings());
        all.addAll(RAYS.settings());
        all.add(DEBUG);
        return List.copyOf(all);
    }

    /** The view it works in. */
    public SceneView view() {
        return view;
    }

    /** The ray tracing scene rays are traced in, or null. */
    public RayTracing rayTracing() {
        return rayTracing;
    }

    @Override
    public Id<? extends Technique> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of(Keys.SPECULAR_OCCLUSION);
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR);
    }

    @Override
    public List<Key<?>> reads() {
        return List.of(Keys.SCENE_DEPTH);
    }

    /** At {@link Anchor#OPAQUE}, after the background, before screen-space reflections. */
    @Override
    public Order order() {
        return Order.at(Anchor.OPAQUE).after(SkyBackground.ID).before(ScreenSpaceReflections.ID);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public boolean wantsSurfaces(GraphBuilder g) {
        return g.technique(SLOT) != null;
    }

    /** The chosen kind's technique: built in this one's place. */
    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        view.checkBuilding(g, this);
        TechniqueKind<?, AmbientOcclusion, Technique> kind = g.technique(SLOT);
        if (kind == null) {
            // Nothing dims the reflections: an image nothing writes, which no one reads (they read the choice first)
            g.provide(Keys.SPECULAR_OCCLUSION, g.create("no specular occlusion", ImageDesc.of(SPECULAR_FORMAT, 1, 1)));
            return;
        }
        made.computeIfAbsent(kind, k -> make(kind)).build(g);
    }

    /** {@code kind}'s technique, its config read from the view's graph's settings as they are when read. */
    private <C extends Record & TechniqueConfig> Technique make(TechniqueKind<C, AmbientOcclusion, Technique> kind) {
        return kind.create(this, () -> kind.config(view.port().settings()));
    }

    /** One of the engine's kinds: this technique's own passes, in {@code mode}. */
    private final class Engine implements Technique {

        private final Mode mode;

        Engine(Mode mode) {
            this.mode = mode;
        }

        @Override
        public List<Key<?>> provides() {
            return List.of(Keys.SPECULAR_OCCLUSION);
        }

        @Override
        @AllowAllocation("building the graph, when it changes")
        public void build(GraphBuilder g) {
            // Rays only where the device and a ray tracing scene allow them
            buildEngine(g, mode == Mode.RAYS && (rayTracing == null || !g.capabilities().rayTracing()) ? Mode.BITMASK
                : mode);
        }
    }

    /** The engine's kinds' passes, as {@code mode} finds occlusion. */
    private void buildEngine(GraphBuilder g, Mode mode) {
        Image color = g.modify(Keys.SCENE_COLOR);
        Image depth = g.read(Keys.SCENE_DEPTH);
        Image normal = g.require(Keys.SURFACE_NORMAL);
        Image material = g.require(Keys.SURFACE_MATERIAL);
        Image reflectance = g.require(Keys.SURFACE_REFLECTANCE);
        Image diffuse = g.require(Keys.SURFACE_DIFFUSE);
        IndirectInputs.Set indirect = IndirectInputs.of(g);
        boolean rays = mode == Mode.RAYS;
        boolean temporal = rays || g.setting(mode == Mode.GTAO ? GTAO.<Boolean>setting("temporal")
            : BITMASK.<Boolean>setting("temporal"));
        Image motion = temporal ? g.optional(Keys.MOTION) : null;
        Buffer topLevel = rays ? g.request(new TopLevel(rayTracing, view.view().renderer())) : null;
        ViewPort region = g.view();
        ImageDesc signal = ImageDesc.of(Denoiser.FORMAT, color.width(), color.height());
        Image ao = g.create("ambient occlusion", signal);
        Image bounce = g.create("ambient occlusion bounce", signal);
        Image specular = g.create("specular occlusion", ImageDesc.of(SPECULAR_FORMAT, color.width(),
            color.height()));
        int indirectConstant = indirect != null ? 1 : 0;
        Pipeline trace = rays ? g.computeProgram("shaders/ao/ao.spv", "raysMain", AoPush.BYTES, 0, indirectConstant)
            : g.computeProgram("shaders/ao/ao.spv", "screenMain", AoPush.BYTES, mode == Mode.BITMASK ? 1 : 0,
                indirectConstant);
        Pipeline composite = g.computeProgram("shaders/ao/ao.spv", "compositeMain", AoPush.BYTES,
            mode == Mode.BITMASK ? 1 : 0, indirectConstant);
        EngineSettings settings = g.settings();
        boolean bitmask = mode == Mode.BITMASK;

        GraphBuilder.PassBuilder tracing = g.pass("ambient occlusion").read(depth, Access.SAMPLED_COMPUTE)
            .read(normal, Access.STORAGE_READ).write(ao, Access.STORAGE_WRITE).write(bounce, Access.STORAGE_WRITE);
        if (bitmask) {
            tracing.read(color, Access.SAMPLED_COMPUTE); // the bounce's light
        }
        if (topLevel != null) {
            tracing.read(topLevel, BufferAccess.ACCELERATION_STRUCTURE_READ);
        }
        if (region != null) {
            tracing.region(region);
        }
        tracing.record(ctx -> {
            push(tracePush, ctx, settings, mode, color, depth, normal, ao, bounce, null, temporal);
            if (bitmask) {
                tracePush.color(ctx.sampled(color));
            }
            ctx.dispatch(trace, tracePush, (ctx.regionWidth(ao) + 7) / 8, (ctx.regionHeight(ao) + 7) / 8, 1);
        });

        Denoiser.Signal result = Denoiser.build(g, "ambient occlusion", view, depth, normal, motion,
            new Denoiser.Signal(ao, bounce), g.setting(switch (mode) {
                case GTAO -> GTAO.<Denoiser.Mode>setting("denoiser");
                case BITMASK -> BITMASK.<Denoiser.Mode>setting("denoiser");
                case RAYS -> RAYS.<Denoiser.Mode>setting("denoiser");
            }), temporal);

        GraphBuilder.PassBuilder compositing = g.pass("ambient occlusion composite")
            .read(depth, Access.SAMPLED_COMPUTE).read(normal, Access.STORAGE_READ)
            .read(material, Access.STORAGE_READ).read(reflectance, Access.STORAGE_READ)
            .read(diffuse, Access.STORAGE_READ).read(result.a(), Access.STORAGE_READ)
            .read(result.b(), Access.STORAGE_READ).write(color, Access.STORAGE_WRITE)
            .write(specular, Access.STORAGE_WRITE);
        if (indirect != null) {
            indirect.readBy(compositing, Access.SAMPLED_COMPUTE, BufferAccess.STORAGE_READ);
        }
        if (region != null) {
            compositing.region(region);
        }
        compositing.record(ctx -> {
            push(compositePush, ctx, settings, mode, color, depth, normal, result.a(), result.b(), indirect,
                temporal).diffuse(ctx.storage(diffuse)).reflectance(ctx.storage(reflectance))
                .material(ctx.storage(material)).occlusion(ctx.storage(specular)).color(ctx.storage(color));
            ctx.dispatch(composite, compositePush, (ctx.regionWidth(color) + 7) / 8,
                (ctx.regionHeight(color) + 7) / 8, 1);
        });
        g.provide(Keys.SPECULAR_OCCLUSION, specular);
    }

    /** ao.slang's push block, as the frame records, with {@code mode}'s kind's tunables. Allocates nothing. */
    private AoPush push(AoPush push, PassContext ctx, EngineSettings settings, Mode mode, Image color, Image depth,
        Image normal, Image ao, Image bounce, IndirectInputs.Set indirect, boolean temporal) {
        boolean rays = mode == Mode.RAYS;
        float radius;
        int slices = 1; // rays read none
        int steps;
        float thickness = BITMASK.defaults().thickness();
        float falloff = GTAO.defaults().falloff();
        boolean bounces = false;
        boolean multiBounce = false;
        boolean specular;
        switch (mode) {
            case GTAO -> {
                Gtao config = GTAO.config(settings);
                radius = config.radius();
                slices = config.slices();
                steps = config.steps();
                falloff = config.falloff();
                multiBounce = config.multiBounce();
                specular = config.specular();
            }
            case BITMASK -> {
                Bitmask config = BITMASK.config(settings);
                radius = config.radius();
                slices = config.slices();
                steps = config.steps();
                thickness = config.thickness();
                bounces = config.bounce();
                specular = config.specular();
            }
            default -> {
                Rays config = RAYS.config(settings);
                radius = config.radius();
                steps = config.rays();
                multiBounce = config.multiBounce();
                specular = config.specular();
            }
        }
        int flags = (bounces ? AO_BOUNCE : 0) | (multiBounce ? AO_MULTI_BOUNCE : 0) | (specular ? AO_SPECULAR : 0);
        flags |= switch (settings.get(DEBUG)) {
            case SCENE -> 0;
            case OCCLUSION -> AO_SHOW_AO;
            case SPECULAR -> AO_SHOW_SPECULAR;
            case BOUNCE -> AO_SHOW_BOUNCE;
        };
        push.view(view.view().address())
            .indirect(indirect == null ? 0 : ctx.address(indirect.table()))
            .depth(ctx.sampled(depth))
            .normal(ctx.storage(normal))
            .ao(ctx.storage(ao))
            .bounce(ctx.storage(bounce))
            .size(ctx.regionWidth(color) | ctx.regionHeight(color) << 16)
            .frame(temporal ? (int) ctx.frame() : 0)
            .slices(slices)
            .steps(steps)
            .radius(radius)
            .thickness(thickness)
            .falloff(falloff)
            .rayOffset(settings.get(ShadowSettings.RAY_OFFSET))
            .flags(flags);
        if (rays) {
            GpuScene scene = view.view().renderer().scene();
            push.rays(rayTracing.sceneAddress(scene))
                .offset((float) (view.view().position(0) - rayTracing.origin(0)),
                    (float) (view.view().position(1) - rayTracing.origin(1)),
                    (float) (view.view().position(2) - rayTracing.origin(2)));
        }
        return push;
    }
}
