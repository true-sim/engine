package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.History;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.layout.DenoisePush;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;

/**
 * The engine's denoiser (design §9, D6), for a signal of two images per pixel of a view: A (rgb a vector, such as a
 * bent normal; a the value that guides the filter, such as an occlusion) and B (rgb a color or a distance; a its
 * variance, which the denoiser writes), both {@link #FORMAT}. Two families, benchmarked against each other and both
 * kept where neither wins ({@link Mode}):
 *
 * <ul>
 *   <li>{@link Mode#SVGF} (Schied et al. 2017, 2018): temporal accumulation, then {@link #PASSES} steps of an
 *   edge-stopping a-trous wavelet filter guided by the depth's plane, the normal and the value's variance;
 *   <li>{@link Mode#REBLUR} (Zhdan, Ray Tracing Gems II, 2021): a blur, the temporal accumulation, and a blur whose
 *   radius shrinks as the history grows, the history holding the blurred result.
 * </ul>
 *
 * <p>The temporal accumulation (denoise/denoise.slang) finds each pixel's history where the motion vectors
 * ({@link Keys#MOTION}, or the camera's reprojection without them) say its surface was, keeping only taps that held
 * the same surface; its weight is one over the frames it holds, capped at {@link #MAX_FRAMES} (the still scene's
 * history, design D3), and a fast history of {@link #FAST_FRAMES} frames clamps it within {@link #CLAMP} of its
 * deviations (a change nothing announced shows within a few frames). Without temporal accumulation only the spatial
 * filter runs: this frame alone. Nothing waits on a later frame: no latency. Allocates nothing per frame.
 */
public final class Denoiser {

    /** The denoiser's family. */
    public enum Mode {
        /** Temporal accumulation and a variance-guided a-trous filter. */
        SVGF,
        /** A blur, temporal accumulation and a blur shrinking with the history (recurrent). */
        REBLUR
    }

    /** The format of both images of a signal. */
    static final Format FORMAT = Format.R16G16B16A16_SFLOAT;
    private static final Format GEOMETRY = Format.R32G32_UINT;

    /** The frames a still pixel's history holds at most: the static scene's lag (design D3: 30). */
    public static final Setting<Integer> MAX_FRAMES = Setting.integer("render.denoise.maxFrames", 30, 1, 1024,
        "frames a denoiser's history holds at most (the lag of a still scene)");
    /** The fast history's frames, which bound how late a change nothing announced shows. */
    public static final Setting<Integer> FAST_FRAMES = Setting.integer("render.denoise.fastFrames", 4, 1, 64,
        "frames of a denoiser's fast history, which the long one is clamped to");
    /**
     * How many deviations of the fast history's noise the long history may stray from it before the denoiser takes it
     * for a change and restarts the history from the fast one.
     */
    public static final Setting<Float> CLAMP = Setting.decimal("render.denoise.clamp", 3, 0, 100,
        "deviations of the fast history's noise within which a denoiser's long history must stay");
    /** Steps of SVGF's a-trous filter (1, 2, 4, ... pixels apart). */
    public static final Setting<Integer> PASSES = Setting.integer("render.denoise.passes", 4, 0, 8,
        "steps of SVGF's a-trous filter");
    /**
     * Steps of the a-trous filter without temporal accumulation (this frame alone): enough to smooth a noise pattern
     * of a few pixels, few enough to keep detail, since nothing but the geometry guides it.
     */
    public static final Setting<Integer> SPATIAL_PASSES = Setting.integer("render.denoise.spatialPasses", 2, 0, 8,
        "steps of the a-trous filter of a signal filtered within the frame alone");
    /** SVGF's edge-stopping on the value, in its deviations. */
    public static final Setting<Float> SIGMA = Setting.decimal("render.denoise.sigma", 4, 0, 100,
        "how many deviations of the value apart SVGF's filter still mixes pixels (0: geometry alone)");
    /** ReBLUR's blur radius, in meters at the pixel (the post-blur's shrinks as the history grows). */
    public static final Setting<Float> RADIUS = Setting.decimal("render.denoise.radius", 0.25f, 0.001f, 100,
        "the radius of the denoiser's blurs (ReBLUR), in meters");

    /** Its settings. */
    public static final List<Setting<?>> ALL = List.of(MAX_FRAMES, FAST_FRAMES, CLAMP, PASSES, SPATIAL_PASSES, SIGMA,
        RADIUS);

    private static final int HISTORY = 1;
    private static final int MOTION = 2;
    private static final int SHRINK = 4;

    private Denoiser() {
    }

    /** A denoised signal. */
    record Signal(Image a, Image b) {
    }

    /**
     * Records the passes denoising {@code input} of {@code view} ({@code name} its histories' and images' prefix):
     * temporal with {@code mode}'s family, or the spatial filter alone without {@code temporal}. {@code motion} may be
     * null (the camera's reprojection). Returns the images holding the result.
     */
    @AllowAllocation("building the graph, when it changes")
    static Signal build(GraphBuilder g, String name, SceneView view, Image depth, Image normal, Image motion,
        Signal input, Mode mode, boolean temporal) {
        return build(g, name, view, depth, normal, motion, input, mode, temporal, 0);
    }

    /**
     * {@link #build} for a signal at a {@code shift}-th power of two below the view's resolution: {@code depth},
     * {@code normal} and {@code motion} (in its texels) at the signal's resolution.
     */
    @AllowAllocation("building the graph, when it changes")
    static Signal build(GraphBuilder g, String name, SceneView view, Image depth, Image normal, Image motion,
        Signal input, Mode mode, boolean temporal, int shift) {
        return build(g, name, view, depth, normal, motion, input, mode, temporal, shift, null);
    }

    /**
     * A value of a view denoised: {@code a}'s alpha (its rgb filtered alike), {@code b} zeros to start from (its alpha
     * the variance written), both {@code FORMAT} (RGBA16F) images of the view's size; the history restarts in frames
     * where {@code restart} says so (null: never), as when the value is another light's from then on. Returns the image
     * holding the result in its alpha. {@code normal}: packed view-space normals ({@link SurfaceOutputs#NORMAL_FORMAT});
     * {@code motion} may be null (the camera's reprojection).
     */
    @AllowAllocation("building the graph, when it changes")
    public static Image denoiseValue(GraphBuilder g, String name, SceneView view, Image depth, Image normal,
        Image motion, Image a, Image b, Mode mode, java.util.function.BooleanSupplier restart) {
        return build(g, name, view, depth, normal, motion, new Signal(a, b), mode, true, 0, restart).a();
    }

    /** The format of a denoised signal's images (RGBA16F). */
    public static Format format() {
        return FORMAT;
    }

    @AllowAllocation("building the graph, when it changes")
    private static Signal build(GraphBuilder g, String name, SceneView view, Image depth, Image normal, Image motion,
        Signal input, Mode mode, boolean temporal, int shift, java.util.function.BooleanSupplier restart) {
        String shader = "shaders/denoise/denoise.spv";
        Pipeline atrous = g.computeProgram(shader, "atrousMain", DenoisePush.BYTES);
        Pipeline temporalPass = g.computeProgram(shader, "temporalMain", DenoisePush.BYTES);
        Pipeline blur = g.computeProgram(shader, "blurMain", DenoisePush.BYTES);
        EngineSettings settings = g.settings();
        ViewPort region = g.view();
        int width = input.a().width();
        int height = input.a().height();
        ImageDesc signal = ImageDesc.of(FORMAT, width, height);
        Image[] scratch = {g.create(name + " filtered A", signal), g.create(name + " filtered B", signal),
            g.create(name + " filtered A'", signal), g.create(name + " filtered B'", signal)};
        int passes = g.setting(PASSES);
        if (!temporal) {
            return atrous(g, name, view, atrous, depth, normal, input, scratch, g.setting(SPATIAL_PASSES), 0, settings,
                region, shift);
        }
        History historyA = g.history(name + " accumulated A", signal);
        History historyB = g.history(name + " accumulated B", signal);
        History moments = g.history(name + " moments", signal);
        History geometry = g.history(name + " geometry", ImageDesc.of(GEOMETRY, width, height));
        Signal into = input;
        if (mode == Mode.REBLUR) {
            Signal blurred = new Signal(scratch[0], scratch[1]);
            blur(g, name + " blur", view, blur, depth, normal, input, blurred, null, false, settings, region, shift);
            into = blurred;
        }
        Signal accumulated = mode == Mode.REBLUR ? new Signal(scratch[2], scratch[3])
            : new Signal(historyA.current(), historyB.current());
        Signal from = into;
        DenoisePush push = new DenoisePush();
        GraphBuilder.PassBuilder pass = g.pass(name + " temporal").read(depth, Access.SAMPLED_COMPUTE)
            .read(normal, Access.STORAGE_READ).read(from.a(), Access.STORAGE_READ).read(from.b(), Access.STORAGE_READ)
            .read(historyA.previous(), Access.STORAGE_READ).read(historyB.previous(), Access.STORAGE_READ)
            .read(moments.previous(), Access.STORAGE_READ).read(geometry.previous(), Access.STORAGE_READ)
            .write(accumulated.a(), Access.STORAGE_WRITE).write(accumulated.b(), Access.STORAGE_WRITE)
            .write(moments.current(), Access.STORAGE_WRITE).write(geometry.current(), Access.STORAGE_WRITE);
        if (motion != null) {
            pass.read(motion, Access.STORAGE_READ);
        }
        if (region != null) {
            pass.region(region);
        }
        pass.record(ctx -> {
            boolean valid = ctx.historyValid(historyA) && ctx.historyValid(moments) && ctx.historyValid(geometry)
                && (restart == null || !restart.getAsBoolean());
            common(push, ctx, view, depth, normal, accumulated.a(), shift)
                .motion(motion == null ? 0 : ctx.storage(motion))
                .inputA(ctx.storage(from.a())).inputB(ctx.storage(from.b()))
                .historyA(ctx.storage(historyA.previous())).historyB(ctx.storage(historyB.previous()))
                .moments(ctx.storage(moments.previous())).geometry(ctx.storage(geometry.previous()))
                .outputA(ctx.storage(accumulated.a())).outputB(ctx.storage(accumulated.b()))
                .outputMoments(ctx.storage(moments.current())).outputGeometry(ctx.storage(geometry.current()))
                .maxFrames(settings.get(MAX_FRAMES)).fastFrames(settings.get(FAST_FRAMES))
                .clampSigma(settings.get(CLAMP))
                .flags((valid ? HISTORY : 0) | (motion != null ? MOTION : 0));
            dispatch(ctx, temporalPass, push, accumulated.a());
        });
        if (mode == Mode.REBLUR) {
            Signal recurrent = new Signal(historyA.current(), historyB.current());
            blur(g, name + " post-blur", view, blur, depth, normal, accumulated, recurrent, moments.current(), true,
                settings, region, shift);
            return recurrent;
        }
        return atrous(g, name, view, atrous, depth, normal, accumulated, scratch, passes, settings.get(SIGMA),
            settings, region, shift);
    }

    /** {@code passes} a-trous steps from {@code input}, ping-ponging through the scratch images. */
    @AllowAllocation("building the graph, when it changes")
    private static Signal atrous(GraphBuilder g, String name, SceneView view, Pipeline atrous, Image depth,
        Image normal, Signal input, Image[] scratch, int passes, float sigma, EngineSettings settings,
        ViewPort region, int shift) {
        Signal from = input;
        for (int i = 0; i < passes; i++) {
            Signal to = (i & 1) == 0 ? new Signal(scratch[0], scratch[1]) : new Signal(scratch[2], scratch[3]);
            Signal source = from;
            int step = 1 << i;
            DenoisePush push = new DenoisePush();
            boolean guided = sigma > 0;
            GraphBuilder.PassBuilder pass = g.pass(name + " filter " + i).read(depth, Access.SAMPLED_COMPUTE)
                .read(normal, Access.STORAGE_READ).read(source.a(), Access.STORAGE_READ)
                .read(source.b(), Access.STORAGE_READ).write(to.a(), Access.STORAGE_WRITE)
                .write(to.b(), Access.STORAGE_WRITE);
            if (region != null) {
                pass.region(region);
            }
            pass.record(ctx -> {
                common(push, ctx, view, depth, normal, to.a(), shift).inputA(ctx.storage(source.a()))
                    .inputB(ctx.storage(source.b())).outputA(ctx.storage(to.a())).outputB(ctx.storage(to.b()))
                    .step(step).sigmaValue(guided ? settings.get(SIGMA) : 0).flags(0);
                dispatch(ctx, atrous, push, to.a());
            });
            from = to;
        }
        return from;
    }

    /** A blur from {@code input} into {@code output}; shrinking with this frame's {@code moments}' frames. */
    @AllowAllocation("building the graph, when it changes")
    private static void blur(GraphBuilder g, String name, SceneView view, Pipeline blur, Image depth, Image normal,
        Signal input, Signal output, Image moments, boolean shrink, EngineSettings settings, ViewPort region,
        int shift) {
        DenoisePush push = new DenoisePush();
        GraphBuilder.PassBuilder pass = g.pass(name).read(depth, Access.SAMPLED_COMPUTE)
            .read(normal, Access.STORAGE_READ).read(input.a(), Access.STORAGE_READ)
            .read(input.b(), Access.STORAGE_READ).write(output.a(), Access.STORAGE_WRITE)
            .write(output.b(), Access.STORAGE_WRITE);
        if (moments != null) {
            pass.read(moments, Access.STORAGE_READ);
        }
        if (region != null) {
            pass.region(region);
        }
        pass.record(ctx -> {
            common(push, ctx, view, depth, normal, output.a(), shift).inputA(ctx.storage(input.a()))
                .inputB(ctx.storage(input.b())).outputA(ctx.storage(output.a())).outputB(ctx.storage(output.b()))
                .moments(moments == null ? 0 : ctx.storage(moments)).radius(settings.get(RADIUS))
                .frame((int) ctx.frame()).flags(shrink ? SHRINK : 0);
            dispatch(ctx, blur, push, output.a());
        });
    }

    private static DenoisePush common(DenoisePush push, PassContext ctx, SceneView view, Image depth, Image normal,
        Image size, int shift) {
        int round = (1 << shift) - 1;
        return push.view(view.view().address()).depth(ctx.sampled(depth)).normal(ctx.storage(normal))
            .size(ctx.regionWidth(size) | ctx.regionHeight(size) << 16)
            .previousSize((view.port().previousRegionWidth() + round) >> shift
                | ((view.port().previousRegionHeight() + round) >> shift) << 16);
    }

    private static void dispatch(PassContext ctx, Pipeline pipeline, DenoisePush push, Image size) {
        ctx.dispatch(pipeline, push, (ctx.regionWidth(size) + 7) / 8, (ctx.regionHeight(size) + 7) / 8, 1);
    }
}
