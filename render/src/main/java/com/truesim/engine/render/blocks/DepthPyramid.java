package com.truesim.engine.render.blocks;


import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Request;
import com.truesim.engine.graph.Subresource;
import com.truesim.engine.render.layout.PyramidPush;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.Objects;

/**
 * The farthest-depth pyramid of a (reverse) depth image: a {@code R32_SFLOAT} image half its size (rounded up) with a
 * full mip chain (each level half the one before, rounded down), each texel the smallest depth (the farthest surface)
 * of the texels it covers below, down to one texel for the whole image. Occlusion culling tests bounds against it:
 * something whose nearest depth is farther than the pyramid's farthest over its footprint is hidden. A pass per level,
 * each reading the level below (per-mip accesses: the graph puts the barriers between them).
 *
 * <pre>{@code
 * Image hzb = g.request(new DepthPyramid(depth));   // read it as Access.SAMPLED_COMPUTE, levels by textures[].Load
 * }</pre>
 */
public record DepthPyramid(Image depth) implements Request<Image> {


    public DepthPyramid {
        Objects.requireNonNull(depth, "depth");
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Image build(GraphBuilder g) {
        Pipeline pipeline = g.computeProgram("shaders/blocks/pyramid.spv", "pyramidMain", PyramidPush.BYTES);
        int width = (depth.width() + 1) / 2;
        int height = (depth.height() + 1) / 2;
        Image pyramid = g.create("depth pyramid of " + depth.name(),
            ImageDesc.of(Format.R32_SFLOAT, width, height).fullMips());
        // A pass per level, each reading the level below as the one before wrote it: the graph orders them.
        int sourceWidth = depth.width();
        int sourceHeight = depth.height();
        int levelWidth = width;
        int levelHeight = height;
        for (int mip = 0; mip < pyramid.mips(); mip++) {
            GraphBuilder.PassBuilder pass = g.pass("depth pyramid of " + depth.name() + ", level " + mip);
            Subresource below = mip == 0 ? null : pyramid.mip(mip - 1); // made here: recording allocates nothing
            Subresource target = pyramid.mip(mip);
            if (below == null) {
                pass.read(depth, Access.SAMPLED_COMPUTE);
            } else {
                pass.read(below, Access.STORAGE_READ);
            }
            // What the level's size decides is set now; the slots, which a rebuild may change, each frame.
            PyramidPush push = new PyramidPush().sourceSize(sourceWidth, sourceHeight).targetSize(levelWidth, levelHeight)
                .fromDepth(below == null ? 1 : 0);
            int groupsX = (levelWidth + 7) / 8;
            int groupsY = (levelHeight + 7) / 8;
            pass.write(target, Access.STORAGE_WRITE).record(ctx -> ctx.dispatch(pipeline, push
                .source(below == null ? ctx.sampled(depth) : ctx.storage(below)).target(ctx.storage(target)), groupsX,
                groupsY, 1));
            sourceWidth = levelWidth;
            sourceHeight = levelHeight;
            levelWidth = Math.max(1, levelWidth / 2); // Vulkan's mip sizes
            levelHeight = Math.max(1, levelHeight / 2);
        }
        return pyramid;
    }

}
