package com.truesim.engine.render.blocks;


import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Ratio;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Request;
import com.truesim.engine.render.layout.BlurPush;
import com.truesim.engine.vulkan.Pipeline;
import java.util.Objects;

/**
 * A blurred copy of {@code source}: {@code radius} texels of the source on each side, at {@code scale} times its size
 * (1/2 for half). Equal blurs (the same image, radius and scale) requested by any techniques are computed once. By
 * default a separable Gaussian in two compute passes, async: on the compute queue, beside graphics work that does not
 * need it; {@code FrameGraph.implement(Blur.class, ...)} swaps in another
 * way for every blur of the graph.
 *
 * <pre>{@code
 * Image soft = g.request(new Blur(sceneColor, 8, Ratio.of(1, 2)));
 * }</pre>
 *
 * <p>The source must be sampleable and its format usable as a storage image (e.g. {@code R16G16B16A16_SFLOAT} or
 * {@code R8G8B8A8_UNORM}, not an sRGB format).
 */
public record Blur(Image source, int radius, Ratio scale) implements Request<Image> {

    public Blur {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(scale, "scale");
        if (radius < 1 || radius > 64 || scale.numerator() > scale.denominator()) {
            throw new IllegalArgumentException("A blur takes a radius of 1 to 64 texels and a scale up to 1: " + radius
                + ", " + scale);
        }
    }

    /** The Gaussian blur: horizontally into a temporary image, then vertically into the result. */
    @Override
    @AllowAllocation("building the graph, when it changes")
    public Image build(GraphBuilder g) {
        Pipeline pipeline = g.computeProgram("shaders/blocks/blur.spv", "blurMain", BlurPush.BYTES);
        String name = "blur " + radius + " of " + source.name();
        Image across = g.create(name + " (across)", ImageDesc.like(source, scale));
        Image result = g.create(name, ImageDesc.like(source, scale));
        float stepX = 1f / source.width();
        float stepY = 1f / source.height();
        BlurPush acrossPush = push(across, stepX, 0);
        BlurPush downPush = push(result, 0, stepY);
        g.pass(name + " across")
            .read(source, Access.SAMPLED_COMPUTE)
            .write(across, Access.STORAGE_WRITE)
            .async()
            .record(ctx -> dispatch(ctx, pipeline, across, acrossPush.source(ctx.sampled(source))));
        g.pass(name + " down")
            .read(across, Access.SAMPLED_COMPUTE)
            .write(result, Access.STORAGE_WRITE)
            .async()
            .record(ctx -> dispatch(ctx, pipeline, result, downPush.source(ctx.sampled(across))));
        return result;
    }

    /** A pass's push constants into {@code to}, with what the build decides set: its own, as passes may record at once. */
    @AllowAllocation("building the graph, when it changes")
    private BlurPush push(Image to, float stepX, float stepY) {
        return new BlurPush().size(to.width(), to.height()).step(stepX, stepY).radius(radius);
    }

    private static void dispatch(PassContext ctx, Pipeline pipeline, Image to, BlurPush push) {
        ctx.dispatch(pipeline, push.target(ctx.storage(to)), (to.width() + 7) / 8, (to.height() + 7) / 8, 1);
    }
}
