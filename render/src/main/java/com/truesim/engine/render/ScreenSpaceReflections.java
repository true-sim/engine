package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.blocks.DepthPyramid;
import com.truesim.engine.render.layout.ReflectPush;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

/**
 * Screen-space reflections of a view's opaque surfaces, as {@link #MODE} chooses: each pixel's mirror ray followed
 * through the depth in screen space, the scene's color where it meets a surface replacing the environment's reflection
 * that shading added along the same direction ({@link SurfaceOutputs}: the normal, roughness and the reflectance that
 * term was weighed by), so energy stays as shading had it. Rough surfaces take the scene blurred by the footprint of
 * their GGX lobe's cone where it meets it (a level of a mip chain of the scene's color); above
 * {@link #ROUGHNESS_CUTOFF}, the environment alone. Where the ray leaves the image, passes the maximum distance, finds
 * nothing or a surface thicker than {@link #THICKNESS} in front of it, the environment's reflection stays, fading in
 * smoothly at the image's edges ({@link #EDGE_FADE}), the distance's end and the cutoff. No history: each frame's
 * reflections are its own (no latency, no ghosting).
 *
 * <p>It modifies {@link Keys#SCENE_COLOR} at {@link Anchor#OPAQUE}, after the background ({@link SkyBackground}), before
 * transparency: blended and transmissive surfaces get none, and are not seen in reflections. Per view (each eye, each
 * window), within the view's render region (dynamic resolution); one per view under {@link #ID}, as transparency is,
 * where ray-traced reflections will be another mode (#379). Needs a shading technique providing the surface outputs
 * ({@link ClusteredForwardShading}, {@link VisibilityBufferShading}), which writes them only while this asks
 * ({@link #MODE} not {@link Mode#OFF}).
 *
 * <pre>{@code
 * view.port().add(new ScreenSpaceReflections(view));
 * graph.settings().set(ScreenSpaceReflections.MODE, ScreenSpaceReflections.Mode.HIZ);
 * }</pre>
 */
public final class ScreenSpaceReflections implements Technique, SurfaceOutputs.Consumer {

    /** The id of a view's reflections. */
    public static final Id<ScreenSpaceReflections> ID = Id.of("truesim.reflections", ScreenSpaceReflections.class);

    /** How reflections are found ({@link #MODE}). */
    public enum Mode {
        /** None: the environment's reflections alone, and no surface outputs written for them. */
        OFF,
        /**
         * A march in equal steps through the depth, the crossing refined by bisection: passes behind surfaces thicker
         * than the thickness, finding what is behind them; its cost grows with {@link #MAX_STEPS}.
         */
        LINEAR,
        /**
         * The hierarchical march over a nearest-depth pyramid: long rays in few steps; the first surface the ray goes
         * behind ends it, a hit only within the thickness (no reflections of what is behind a thick surface).
         */
        HIZ
    }

    /**
     * How reflections are found; the graph rebuilds when it changes. {@link Mode#HIZ} by default: about a third of
     * {@link Mode#LINEAR}'s time at the same maximum of steps (README, the reflections benchmark).
     */
    public static final Setting<Mode> MODE = Setting.choice("render.ssr", Mode.HIZ,
        "screen-space reflections: off, a linear march, or a hierarchical one over the depth pyramid");
    /** Steps a ray takes at most (LINEAR: equal steps; HIZ: cells visited). Read as frames record. */
    public static final Setting<Integer> MAX_STEPS = Setting.integer("render.ssr.maxSteps", 64, 4, 1024,
        "steps a screen-space reflection ray takes at most");
    /** How deep a surface counts as, behind its depth, in view depth units (meters). Read as frames record. */
    public static final Setting<Float> THICKNESS = Setting.decimal("render.ssr.thickness", 0.25f, 0.001f, 1000,
        "how thick surfaces are taken to be behind their depth, in meters");
    /** How far rays go, in meters; reflections fade over their last fifth. Read as frames record. */
    public static final Setting<Float> MAX_DISTANCE = Setting.decimal("render.ssr.maxDistance", 100, 0.01f, 1_000_000,
        "how far screen-space reflection rays go, in meters");
    /**
     * The perceptual roughness from which surfaces take the environment's reflection alone, fading from three quarters
     * of it. Read as frames record.
     */
    public static final Setting<Float> ROUGHNESS_CUTOFF = Setting.decimal("render.ssr.roughnessCutoff", 0.6f, 0, 1,
        "roughness from which surfaces reflect the environment alone");
    /** Of the image's size, along each edge: hits there fade into the environment. Read as frames record. */
    public static final Setting<Float> EDGE_FADE = Setting.decimal("render.ssr.edgeFade", 0.1f, 0.001f, 0.5f,
        "how far in from the image's edges screen-space reflections fade, of its size");
    /** The resolution rays are traced at, of the view's; the graph rebuilds when it changes. */
    public static final Setting<Float> RESOLUTION_SCALE = Setting.decimal("render.ssr.resolutionScale", 1, 0.25f, 1,
        "the resolution screen-space reflection rays are traced at, of the view's");

    /** Every setting of screen-space reflections. */
    public static final List<Setting<?>> ALL = List.of(MODE, MAX_STEPS, THICKNESS, MAX_DISTANCE, ROUGHNESS_CUTOFF,
        EDGE_FADE, RESOLUTION_SCALE);

    /** The hit image's format: where (0 to 1 of the region), how far (of the maximum distance), how trusted. */
    static final Format HITS_FORMAT = Format.R16G16B16A16_UNORM;

    private final SceneView view;
    private final ReflectPush tracePush = new ReflectPush(); // one per pass: passes may record at once
    private final ReflectPush compositePush = new ReflectPush();

    /** Reflections in {@code view}; added to its port. */
    public ScreenSpaceReflections(SceneView view) {
        this.view = Objects.requireNonNull(view, "view");
    }

    @Override
    public Id<? extends Technique> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR);
    }

    /** The opaque scene's depth, read at its place: transparency may write the depth after it. */
    @Override
    public List<Key<?>> reads() {
        return List.of(Keys.SCENE_DEPTH);
    }

    /** At {@link Anchor#OPAQUE}, after the background, so glossy reflections blur the sky too. */
    @Override
    public Order order() {
        return Order.at(Anchor.OPAQUE).after(SkyBackground.ID);
    }

    /** While {@link #MODE} is not {@link Mode#OFF}. */
    @Override
    @AllowAllocation("building the graph, when it changes")
    public boolean wantsSurfaces(GraphBuilder g) {
        return g.setting(MODE) != Mode.OFF;
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        view.checkBuilding(g, this);
        Mode mode = g.setting(MODE);
        if (mode == Mode.OFF) {
            return;
        }
        Image color = g.modify(Keys.SCENE_COLOR);
        Image depth = g.read(Keys.SCENE_DEPTH);
        Image normal = g.require(Keys.SURFACE_NORMAL);
        Image material = g.require(Keys.SURFACE_MATERIAL);
        Image reflectance = g.require(Keys.SURFACE_REFLECTANCE);
        IndirectInputs.Set indirect = IndirectInputs.of(g);
        ViewPort region = g.view();
        boolean hierarchical = mode == Mode.HIZ;
        Image pyramid = hierarchical ? g.request(new DepthPyramid(depth, region, true)) : null;
        Image chain = SceneBehind.chain(g, color, region, "reflected scene");
        float scale = g.setting(RESOLUTION_SCALE);
        Image hits = g.create("reflection hits", ImageDesc.of(HITS_FORMAT,
            Math.max(1, (int) Math.ceil(color.width() * scale)), Math.max(1, (int) Math.ceil(color.height() * scale))));
        int[] constants = {hierarchical ? 1 : 0, indirect != null ? 1 : 0};
        Pipeline trace = g.computeProgram("shaders/reflections/reflect.spv", "traceMain", ReflectPush.BYTES,
            constants);
        Pipeline composite = g.computeProgram("shaders/reflections/reflect.spv", "compositeMain", ReflectPush.BYTES,
            constants);
        EngineSettings settings = g.settings();

        GraphBuilder.PassBuilder tracing = g.pass("reflection trace").read(depth, Access.SAMPLED_COMPUTE)
            .read(normal, Access.STORAGE_READ).read(material, Access.STORAGE_READ)
            .read(reflectance, Access.STORAGE_READ).write(hits, Access.STORAGE_WRITE);
        if (pyramid != null) {
            tracing.read(pyramid, Access.SAMPLED_COMPUTE);
        }
        if (region != null) {
            tracing.region(region);
        }
        tracing.record(ctx -> {
            push(tracePush, ctx, settings, color, depth, normal, material, reflectance, null, pyramid, hits, scale,
                null); // the scene and the environment are the composite's
            int width = ctx.regionWidth(hits);
            int height = ctx.regionHeight(hits);
            ctx.dispatch(trace, tracePush, (width + 7) / 8, (height + 7) / 8, 1);
        });

        GraphBuilder.PassBuilder compositing = g.pass("reflection composite").read(depth, Access.SAMPLED_COMPUTE)
            .read(normal, Access.STORAGE_READ).read(material, Access.STORAGE_READ)
            .read(reflectance, Access.STORAGE_READ).read(hits, Access.STORAGE_READ)
            .read(chain, Access.SAMPLED_COMPUTE).write(color, Access.STORAGE_WRITE);
        if (indirect != null) {
            indirect.readBy(compositing, Access.SAMPLED_COMPUTE, BufferAccess.STORAGE_READ);
        }
        if (region != null) {
            compositing.region(region);
        }
        compositing.record(ctx -> {
            push(compositePush, ctx, settings, color, depth, normal, material, reflectance, chain, null, hits,
                scale, indirect);
            int width = ctx.regionWidth(color);
            int height = ctx.regionHeight(color);
            ctx.dispatch(composite, compositePush, (width + 7) / 8, (height + 7) / 8, 1);
        });
    }

    /**
     * reflect.slang's push block, into {@code push}, as the frame records: the scene's chain and color with
     * {@code chain} (the composite), the pyramid where given (the trace). Allocates nothing.
     */
    private ReflectPush push(ReflectPush push, PassContext ctx, EngineSettings settings, Image color, Image depth,
        Image normal, Image material, Image reflectance, Image chain, Image pyramid, Image hits, float scale,
        IndirectInputs.Set indirect) {
        int width = ctx.regionWidth(color);
        int height = ctx.regionHeight(color);
        return push.view(view.view().address())
            .indirect(indirect == null ? 0 : ctx.address(indirect.table()))
            .depth(ctx.sampled(depth))
            .normal(ctx.storage(normal))
            .material(ctx.storage(material))
            .reflectance(ctx.storage(reflectance))
            .color(chain == null ? 0 : ctx.storage(color))
            .chain(chain == null ? 0 : ctx.sampled(chain))
            .chainMips(chain == null ? 0 : chain.mips())
            .pyramid(pyramid == null ? 0 : ctx.sampled(pyramid))
            .pyramidMips(pyramid == null ? 0 : pyramid.mips())
            .hits(ctx.storage(hits))
            .size(width | height << 16)
            .hitSize(ctx.regionWidth(hits) | ctx.regionHeight(hits) << 16)
            .imageSize(color.width() | color.height() << 16)
            .maxSteps(settings.get(MAX_STEPS))
            .thickness(settings.get(THICKNESS))
            .maxDistance(settings.get(MAX_DISTANCE))
            .cutoff(settings.get(ROUGHNESS_CUTOFF))
            .edgeFade(settings.get(EDGE_FADE))
            .scale(scale);
    }
}
