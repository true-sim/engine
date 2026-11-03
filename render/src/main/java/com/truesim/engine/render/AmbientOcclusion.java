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
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

/**
 * Ambient occlusion of a view's opaque surfaces (global illumination's first stage, S1; issue #34 for
 * {@link Mode#RAYS}), as {@link #MODE} chooses: how much of the hemisphere around each pixel's normal is open within
 * {@link #RADIUS}, cosine-weighted. It replaces what shading added through the surface outputs ({@link SurfaceOutputs}),
 * as screen-space reflections do: only the diffuse light from all around the normal (the environment's harmonics, the
 * constant sky, or the probe volume where one lights the view) is darkened, weighed by the pixel's diffuse weight
 * (design D12); direct light, emission and the environment's reflections are not. The reflections get a separate
 * specular occlusion ({@link #SPECULAR}): the cone of the visible directions around the bent normal against the GGX
 * lobe's cone, which it also provides under {@link Keys#SPECULAR_OCCLUSION} for {@link ScreenSpaceReflections} to dim
 * the environment it replaces by.
 *
 * <ul>
 *   <li>{@link Mode#GTAO} (Jimenez et al. 2016, as Intel's XeGTAO): slices through the hemisphere searched along the
 *   screen for each side's highest horizon, the visible arc integrated in closed form; samples fade out over the last
 *   {@link #FALLOFF} of the radius; surfaces are taken to be infinitely thick (light never passes behind them);
 *   <li>{@link Mode#BITMASK} (Therrien, Levesque and Gilet 2023): surfaces {@link #THICKNESS} thick, each slice's
 *   occlusion a mask of 32 sectors of equal cosine-weighted measure, and one short-range bounce of the scene's color
 *   from the sectors each sample covers ({@link #BOUNCE}), added through the diffuse weight;
 *   <li>{@link Mode#RAYS} (#34): {@link #RAYS} cosine-distributed rays per pixel by ray queries into the frame's top
 *   level, as long as the radius: exact, off-screen occluders included, noisy; denoised ({@link Denoiser}).
 * </ul>
 *
 * <p>The screen-space ones are single-frame by default (a noise pattern fixed in time, then the spatial filter: no
 * lag); with {@link #TEMPORAL}, and always for rays, the denoiser of {@link #DENOISER} accumulates over frames, the
 * history found through the view's motion vectors ({@link Keys#MOTION}, or the camera's reprojection without a
 * {@link MotionVectors}). It modifies {@link Keys#SCENE_COLOR} at {@link Anchor#OPAQUE}, after the background, before
 * the reflections; per view, within its render region. Needs a shading technique providing the surface outputs.
 *
 * <pre>{@code
 * view.port().add(new AmbientOcclusion(view, rayTracing));  // rayTracing: null without RAYS
 * graph.settings().set(AmbientOcclusion.MODE, AmbientOcclusion.Mode.BITMASK);
 * }</pre>
 */
public final class AmbientOcclusion implements Technique, SurfaceOutputs.Consumer {

    /** The id of a view's ambient occlusion. */
    public static final Id<AmbientOcclusion> ID = Id.of("truesim.ambientOcclusion", AmbientOcclusion.class);

    /** How occlusion is found ({@link #MODE}). */
    public enum Mode {
        /** None. */
        OFF,
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

    /** How occlusion is found; the graph rebuilds when it changes. */
    public static final Setting<Mode> MODE = Setting.choice("render.ao", Mode.BITMASK,
        "ambient occlusion: off, GTAO, the visibility bitmask (with a bounce) or traced rays");
    /** How far occluders count, in meters. */
    public static final Setting<Float> RADIUS = Setting.decimal("render.ao.radius", 0.5f, 0.01f, 100,
        "how far ambient occlusion looks for occluders, in meters");
    /** GTAO and BITMASK: slices of the hemisphere per pixel. */
    public static final Setting<Integer> SLICES = Setting.integer("render.ao.slices", 2, 1, 16,
        "slices of the hemisphere screen-space ambient occlusion searches per pixel");
    /** GTAO and BITMASK: samples per side of each slice. */
    public static final Setting<Integer> STEPS = Setting.integer("render.ao.steps", 8, 1, 64,
        "samples along each side of a slice of screen-space ambient occlusion");
    /** BITMASK: how thick surfaces are taken to be behind what the depth shows, in meters along the view. */
    public static final Setting<Float> THICKNESS = Setting.decimal("render.ao.thickness", 0.25f, 0.001f, 100,
        "how thick the visibility bitmask takes surfaces to be, in meters");
    /** GTAO: the part of the radius over which samples fade out. */
    public static final Setting<Float> FALLOFF = Setting.decimal("render.ao.falloff", 0.2f, 0.01f, 1,
        "the part of the radius over which GTAO's samples fade out");
    /** RAYS: rays per pixel each frame. */
    public static final Setting<Integer> RAYS = Setting.integer("render.ao.rays", 1, 1, 1024,
        "rays per pixel of ray-traced ambient occlusion");
    /** BITMASK: one bounce of the scene's color from the occluders. */
    public static final Setting<Boolean> BOUNCE = Setting.bool("render.ao.bounce", true,
        "the visibility bitmask's short-range bounce of the scene's color");
    /** GTAO and RAYS: Jimenez et al.'s fit of interreflections between occluders by albedo. */
    public static final Setting<Boolean> MULTI_BOUNCE = Setting.bool("render.ao.multiBounce", false,
        "GTAO's multiple-bounce fit by albedo, for GTAO and rays");
    /** The specular occlusion of the environment's reflections. */
    public static final Setting<Boolean> SPECULAR = Setting.bool("render.ao.specular", true,
        "specular occlusion of the environment's reflections from the bent normal");
    /** The denoiser of rays, and of the screen-space ones with {@link #TEMPORAL}; the graph rebuilds. */
    public static final Setting<Denoiser.Mode> DENOISER = Setting.choice("render.ao.denoiser", Denoiser.Mode.SVGF,
        "the denoiser of ray-traced (and temporal) ambient occlusion: SVGF or ReBLUR");
    /** GTAO and BITMASK accumulate over frames (a noise pattern moving in time); the graph rebuilds. */
    public static final Setting<Boolean> TEMPORAL = Setting.bool("render.ao.temporal", false,
        "screen-space ambient occlusion accumulates over frames");
    /** What the image shows (debugging). */
    public static final Setting<Show> DEBUG = Setting.choice("render.ao.debug", Show.SCENE,
        "what the image shows: the scene, or ambient occlusion's terms alone");

    /** Its settings. */
    public static final List<Setting<?>> ALL = List.of(MODE, RADIUS, SLICES, STEPS, THICKNESS, FALLOFF, RAYS, BOUNCE,
        MULTI_BOUNCE, SPECULAR, DENOISER, TEMPORAL, DEBUG);

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

    /** Ambient occlusion in {@code view}, traced in {@code rayTracing} for {@link Mode#RAYS} (null: none). */
    public AmbientOcclusion(SceneView view, RayTracing rayTracing) {
        this.view = Objects.requireNonNull(view, "view");
        this.rayTracing = rayTracing;
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
        return g.setting(MODE) != Mode.OFF;
    }

    /** The mode this build uses: rays only where the device and a ray tracing scene allow them. */
    private Mode mode(GraphBuilder g) {
        Mode mode = g.setting(MODE);
        return mode == Mode.RAYS && (rayTracing == null || !g.capabilities().rayTracing()) ? Mode.BITMASK : mode;
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        view.checkBuilding(g, this);
        Mode mode = mode(g);
        if (mode == Mode.OFF) {
            // Nothing dims the reflections: an image nothing writes, which no one reads (they read the mode first)
            g.provide(Keys.SPECULAR_OCCLUSION, g.create("no specular occlusion", ImageDesc.of(SPECULAR_FORMAT, 1, 1)));
            return;
        }
        Image color = g.modify(Keys.SCENE_COLOR);
        Image depth = g.read(Keys.SCENE_DEPTH);
        Image normal = g.require(Keys.SURFACE_NORMAL);
        Image material = g.require(Keys.SURFACE_MATERIAL);
        Image reflectance = g.require(Keys.SURFACE_REFLECTANCE);
        Image diffuse = g.require(Keys.SURFACE_DIFFUSE);
        IndirectInputs.Set indirect = IndirectInputs.of(g);
        boolean rays = mode == Mode.RAYS;
        boolean temporal = rays || g.setting(TEMPORAL);
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
            new Denoiser.Signal(ao, bounce), g.setting(DENOISER), temporal);

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

    /** ao.slang's push block, as the frame records. Allocates nothing. */
    private AoPush push(AoPush push, PassContext ctx, EngineSettings settings, Mode mode, Image color, Image depth,
        Image normal, Image ao, Image bounce, IndirectInputs.Set indirect, boolean temporal) {
        boolean rays = mode == Mode.RAYS;
        int flags = (mode == Mode.BITMASK && settings.get(BOUNCE) ? AO_BOUNCE : 0)
            | (mode != Mode.BITMASK && settings.get(MULTI_BOUNCE) ? AO_MULTI_BOUNCE : 0)
            | (settings.get(SPECULAR) ? AO_SPECULAR : 0);
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
            .slices(settings.get(SLICES))
            .steps(rays ? settings.get(RAYS) : settings.get(STEPS))
            .radius(settings.get(RADIUS))
            .thickness(settings.get(THICKNESS))
            .falloff(settings.get(FALLOFF))
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
