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
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.settings.Techniques;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Screen-space reflections of a view's opaque surfaces, as the kind chosen for {@link #SLOT} ({@code render.ssr})
 * finds them: each pixel's mirror ray followed
 * through the depth in screen space, the scene's color where it meets a surface replacing the environment's reflection
 * that shading added along the same direction ({@link SurfaceOutputs}: the normal, roughness and the reflectance that
 * term was weighed by), so energy stays as shading had it. Rough surfaces take the scene blurred by the footprint of
 * their GGX lobe's cone where it meets it (a level of a mip chain of the scene's color); above the
 * {@code roughnessCutoff}, the environment alone. Where the ray leaves the image, passes the maximum distance, finds
 * nothing or only surfaces it passes behind ({@code thickness}), the environment's reflection stays, fading in
 * smoothly at the image's edges ({@code edgeFade}), the distance's end and the cutoff. No history: each frame's
 * reflections are its own (no latency, no ghosting).
 *
 * <p>It modifies {@link Keys#SCENE_COLOR} at {@link Anchor#OPAQUE}, after the background ({@link SkyBackground}),
 * before transparency: blended and transmissive surfaces get none, and are not seen in reflections. Per view (each eye,
 * each window), within the view's render region (dynamic resolution); one per view under {@link #ID}, as transparency
 * is, where ray-traced reflections will be another kind (#379). Needs a shading technique providing the surface outputs
 * ({@link ClusteredForwardShading}, {@link VisibilityBufferShading}), which writes them only while this asks (the slot
 * not off).
 *
 * <p>A game's own kind ({@link Techniques#register}) makes a {@link Technique} built in this one's place, given this
 * one ({@link #view()}): it modifies {@link Keys#SCENE_COLOR} and reads {@link Keys#SCENE_DEPTH} as this one does.
 *
 * <pre>{@code
 * view.port().add(new ScreenSpaceReflections(view));
 * ScreenSpaceReflections.SLOT.choose(graph.settings(), ScreenSpaceReflections.LINEAR);   // render.ssr=linear
 * }</pre>
 */
public final class ScreenSpaceReflections implements Technique, SurfaceOutputs.Consumer {

    /** The id of a view's reflections. */
    public static final Id<ScreenSpaceReflections> ID = Id.of("truesim.reflections", ScreenSpaceReflections.class);

    /** How the engine's kinds march: the shader's variants. */
    enum Mode {
        /**
         * A march in equal steps through the depth, the crossing refined by bisection: passes behind surfaces thicker
         * than the thickness, finding what is behind them; its cost grows with the maximum of steps.
         */
        LINEAR,
        /**
         * The hierarchical march over a nearest-depth pyramid: long rays in few steps; the first surface the ray goes
         * behind ends it, a hit only within the thickness (no reflections of what is behind a thick surface).
         */
        HIZ
    }

    /**
     * Where screen-space reflections plug in: {@code render.ssr}, its kind chosen by id; the graph rebuilds when it
     * changes. {@link #HIZ} by default: under half of {@link #LINEAR}'s time at the same maximum of steps (README, the
     * reflections benchmark).
     */
    public static final TechniqueSlot<ScreenSpaceReflections, Technique> SLOT = TechniqueSlot.of("render.ssr", "hiz",
        true, "screen-space reflections: off, a linear march, or a hierarchical one over the depth pyramid",
        techniques -> techniques.register(ScreenSpaceReflections.SLOT, ScreenSpaceReflections.LINEAR)
            .register(ScreenSpaceReflections.SLOT, ScreenSpaceReflections.HIZ));

    /**
     * The engine's kinds' tunables: {@code resolutionScale} is read when the graph builds (it rebuilds when it
     * changes), the others as frames record.
     *
     * @param maxSteps steps a ray takes at most (linear: equal steps; hiz: cells visited)
     * @param thickness how thick surfaces are taken to be, in meters along their normal (seen at a grazing angle, a ray
     *     behind one by up to four times this along the view still meets it): a ray within it of the surface it went
     *     behind meets that surface, fading out to twice it, past which the ray passes behind
     * @param maxDistance how far rays go, in meters; reflections fade over their last fifth
     * @param roughnessCutoff the perceptual roughness from which surfaces take the environment's reflection alone,
     *     fading from three quarters of it
     * @param edgeFade of the image's size, along each edge: hits there fade into the environment
     * @param resolutionScale the resolution rays are traced at, of the view's
     */
    public record March(
        @Tunable(value = "steps a screen-space reflection ray takes at most", min = 4, max = 1024,
            formerly = "render.ssr.maxSteps") int maxSteps,
        @Tunable(value = "how thick surfaces are taken to be along their normal, in meters", min = 0.001, max = 1000,
            formerly = "render.ssr.thickness") float thickness,
        @Tunable(value = "how far screen-space reflection rays go, in meters", min = 0.01, max = 1_000_000,
            formerly = "render.ssr.maxDistance") float maxDistance,
        @Tunable(value = "roughness from which surfaces reflect the environment alone", min = 0, max = 1,
            formerly = "render.ssr.roughnessCutoff") float roughnessCutoff,
        @Tunable(value = "how far in from the image's edges screen-space reflections fade, of its size", min = 0.001,
            max = 0.5, formerly = "render.ssr.edgeFade") float edgeFade,
        @Tunable(value = "the resolution screen-space reflection rays are traced at, of the view's", min = 0.25,
            max = 1, formerly = "render.ssr.resolutionScale") float resolutionScale) implements TechniqueConfig {
    }

    /** The defaults of both engine kinds. */
    private static final March MARCH = new March(64, 0.25f, 100, 0.6f, 0.1f, 1);

    /** A march in equal steps through the depth, refined by bisection; finds what is behind thick surfaces. */
    public static final TechniqueKind<March, ScreenSpaceReflections, Technique> LINEAR = TechniqueKind.of(SLOT,
        "linear", MARCH, "a march in equal steps through the depth", (ssr, config) -> ssr.new Engine(Mode.LINEAR));
    /** The hierarchical march over a nearest-depth pyramid: long rays in few steps. The default. */
    public static final TechniqueKind<March, ScreenSpaceReflections, Technique> HIZ = TechniqueKind.of(SLOT, "hiz",
        MARCH, "a hierarchical march over the depth pyramid", (ssr, config) -> ssr.new Engine(Mode.HIZ));

    /** Every setting of screen-space reflections: the choice and the engine's kinds' tunables. */
    public static final List<Setting<?>> ALL = all();

    /** The hit image's format: where (0 to 1 of the region), how far (of the maximum distance), how trusted. */
    static final Format HITS_FORMAT = Format.R16G16B16A16_UNORM;

    private final SceneView view;
    private final ReflectPush tracePush = new ReflectPush(); // one per pass: passes may record at once
    private final ReflectPush compositePush = new ReflectPush();
    private final Map<TechniqueKind<?, ?, ?>, Technique> made = new IdentityHashMap<>(); // per kind, once chosen

    /** Reflections in {@code view}; added to its port. */
    public ScreenSpaceReflections(SceneView view) {
        this.view = Objects.requireNonNull(view, "view");
    }

    private static List<Setting<?>> all() {
        List<Setting<?>> all = new ArrayList<>();
        all.add(SLOT.setting());
        all.addAll(LINEAR.settings());
        all.addAll(HIZ.settings());
        return List.copyOf(all);
    }

    /** The view it works in. */
    public SceneView view() {
        return view;
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

    /** While the slot is not off. */
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
        TechniqueKind<?, ScreenSpaceReflections, Technique> kind = g.technique(SLOT);
        if (kind != null) {
            made.computeIfAbsent(kind, k -> make(kind)).build(g);
        }
    }

    /** {@code kind}'s technique, its config read from the view's graph's settings as they are when read. */
    @AllowAllocation("building the graph, when it changes")
    private <C extends Record & TechniqueConfig> Technique make(
        TechniqueKind<C, ScreenSpaceReflections, Technique> kind) {
        return kind.create(this, () -> kind.config(view.port().settings()));
    }

    /** One of the engine's kinds: this technique's own passes, marching as {@code mode} does. */
    private final class Engine implements Technique {

        private final Mode mode;

        Engine(Mode mode) {
            this.mode = mode;
        }

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        @AllowAllocation("building the graph, when it changes")
        public void build(GraphBuilder g) {
            buildEngine(g, mode);
        }
    }

    /** The engine's kinds' passes. */
    @AllowAllocation("building the graph, when it changes")
    private void buildEngine(GraphBuilder g, Mode mode) {
        TechniqueKind<March, ScreenSpaceReflections, Technique> kind = mode == Mode.HIZ ? HIZ : LINEAR;
        Image color = g.modify(Keys.SCENE_COLOR);
        Image depth = g.read(Keys.SCENE_DEPTH);
        Image normal = g.require(Keys.SURFACE_NORMAL);
        Image material = g.require(Keys.SURFACE_MATERIAL);
        Image reflectance = g.require(Keys.SURFACE_REFLECTANCE);
        IndirectInputs.Set indirect = IndirectInputs.of(g);
        // Ambient occlusion's specular occlusion, where it dimmed the environment's reflection
        Image occlusion = g.technique(AmbientOcclusion.SLOT) != null ? g.optional(Keys.SPECULAR_OCCLUSION) : null;
        ViewPort region = g.view();
        boolean hierarchical = mode == Mode.HIZ;
        Image pyramid = hierarchical ? g.request(new DepthPyramid(depth, region, true)) : null;
        Image chain = SceneBehind.chain(g, color, region, "reflected scene");
        float scale = g.setting(kind.<Float>setting("resolutionScale"));
        Image hits = g.create("reflection hits", ImageDesc.of(HITS_FORMAT,
            Math.max(1, (int) Math.ceil(color.width() * scale)), Math.max(1, (int) Math.ceil(color.height() * scale))));
        int[] constants = {hierarchical ? 1 : 0, indirect != null ? 1 : 0, occlusion != null ? 1 : 0};
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
            push(tracePush, ctx, kind.config(settings), color, depth, normal, material, reflectance, null, pyramid,
                hits, scale, null); // the scene and the environment are the composite's
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
        if (occlusion != null) {
            compositing.read(occlusion, Access.STORAGE_READ);
        }
        if (region != null) {
            compositing.region(region);
        }
        compositing.record(ctx -> {
            push(compositePush, ctx, kind.config(settings), color, depth, normal, material, reflectance, chain, null,
                hits, scale, indirect).occlusion(occlusion == null ? 0 : ctx.storage(occlusion));
            int width = ctx.regionWidth(color);
            int height = ctx.regionHeight(color);
            ctx.dispatch(composite, compositePush, (width + 7) / 8, (height + 7) / 8, 1);
        });
    }

    /**
     * reflect.slang's push block, into {@code push}, as the frame records, with the kind's tunables: the scene's chain
     * and color with {@code chain} (the composite), the pyramid where given (the trace). Allocates nothing.
     */
    private ReflectPush push(ReflectPush push, PassContext ctx, March config, Image color, Image depth,
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
            .maxSteps(config.maxSteps())
            .thickness(config.thickness())
            .maxDistance(config.maxDistance())
            .cutoff(config.roughnessCutoff())
            .edgeFade(config.edgeFade())
            .scale(scale);
    }
}
