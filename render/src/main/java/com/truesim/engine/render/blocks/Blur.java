package com.truesim.engine.render.blocks;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Ratio;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Request;
import com.truesim.engine.render.layout.BlurPush;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

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
        g.pass(name + " across")
            .read(source, Access.SAMPLED_COMPUTE)
            .write(across, Access.STORAGE_WRITE)
            .async()
            .record(ctx -> dispatch(ctx, pipeline, source, across, stepX, 0, radius));
        g.pass(name + " down")
            .read(across, Access.SAMPLED_COMPUTE)
            .write(result, Access.STORAGE_WRITE)
            .async()
            .record(ctx -> dispatch(ctx, pipeline, across, result, 0, stepY, radius));
        return result;
    }

    private static void dispatch(PassContext ctx, Pipeline pipeline, Image from, Image to, float stepX, float stepY,
        int radius) {
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, BlurPush.BYTES);
            memPutInt(push + BlurPush.SOURCE, ctx.sampled(from));
            memPutInt(push + BlurPush.TARGET, ctx.storage(to));
            memPutInt(push + BlurPush.SIZE, to.width());
            memPutInt(push + BlurPush.SIZE + 4, to.height());
            memPutFloat(push + BlurPush.STEP, stepX);
            memPutFloat(push + BlurPush.STEP + 4, stepY);
            memPutInt(push + BlurPush.RADIUS, radius);
            pipeline.bind(ctx.unsafe().commands());
            pipeline.push(ctx.unsafe().commands(), push, BlurPush.BYTES);
            Commands.dispatch(ctx.unsafe().commands(), (to.width() + 7) / 8, (to.height() + 7) / 8, 1);
        }
    }
}
