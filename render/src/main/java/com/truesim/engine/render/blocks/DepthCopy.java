package com.truesim.engine.render.blocks;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Request;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.layout.PyramidPush;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.Objects;

/**
 * A copy of a (reverse) depth image as it is now, texel for texel, in a {@code R32_SFLOAT} image of its size: for
 * passes that test the depth itself as their attachment and sample it too (transparent surfaces tracing their
 * reflections through the opaque depth, {@code TransparentReflections}). With a {@code view} (nullable), of its render
 * region in {@code depth} each frame, at the top left. Equal requests share one copy.
 *
 * <pre>{@code
 * Image opaque = g.request(new DepthCopy(depth, g.view()));   // sampled: textures[].Load
 * }</pre>
 */
public record DepthCopy(Image depth, ViewPort view) implements Request<Image> {

    public DepthCopy {
        Objects.requireNonNull(depth, "depth");
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Image build(GraphBuilder g) {
        Pipeline pipeline = g.computeProgram("shaders/blocks/pyramid.spv", "pyramidMain", PyramidPush.BYTES);
        Image copy = g.create("copy of " + depth.name(), ImageDesc.of(Format.R32_SFLOAT, depth.width(),
            depth.height()));
        GraphBuilder.PassBuilder pass = g.pass("copy of " + depth.name());
        if (view != null) {
            pass.region(view);
        }
        PyramidPush push = new PyramidPush().fromDepth(1).copy(1);
        pass.read(depth, Access.SAMPLED_COMPUTE).write(copy, Access.STORAGE_WRITE).record(ctx -> {
            int width = ctx.regionWidth(depth);
            int height = ctx.regionHeight(depth);
            ctx.dispatch(pipeline, push.sourceSize(width, height).targetSize(width, height)
                .source(ctx.sampled(depth)).target(ctx.storage(copy)), (width + 7) / 8, (height + 7) / 8, 1);
        });
        return copy;
    }
}
