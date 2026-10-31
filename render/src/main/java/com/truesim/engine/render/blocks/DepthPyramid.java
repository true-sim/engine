package com.truesim.engine.render.blocks;


import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Request;
import com.truesim.engine.graph.Subresource;
import com.truesim.engine.graph.ViewPort;
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
public record DepthPyramid(Image depth, ViewPort view) implements Request<Image> {

    /**
     * The pyramid of {@code depth}, all of it; with a {@code view} (nullable), of its render region in {@code depth}
     * each frame (dynamic resolution): levels cover the region only, sized from it as from a depth image of its size.
     */
    public DepthPyramid {
        Objects.requireNonNull(depth, "depth");
    }

    /** The pyramid of all of {@code depth}. */
    public DepthPyramid(Image depth) {
        this(depth, null);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Image build(GraphBuilder g) {
        Pipeline pipeline = g.computeProgram("shaders/blocks/pyramid.spv", "pyramidMain", PyramidPush.BYTES);
        int width = (depth.width() + 1) / 2;
        int height = (depth.height() + 1) / 2;
        Image pyramid = g.create("depth pyramid of " + depth.name(),
            ImageDesc.of(Format.R32_SFLOAT, width, height).fullMips());
        // A pass per level, each reading the level below as the one before wrote it: the graph orders them. Sizes come
        // from the depth's render region each frame, as of an image of its size (levels halving, rounding down).
        for (int mip = 0; mip < pyramid.mips(); mip++) {
            GraphBuilder.PassBuilder pass = g.pass("depth pyramid of " + depth.name() + ", level " + mip);
            if (view != null) {
                pass.region(view);
            }
            Subresource below = mip == 0 ? null : pyramid.mip(mip - 1); // made here: recording allocates nothing
            Subresource target = pyramid.mip(mip);
            if (below == null) {
                pass.read(depth, Access.SAMPLED_COMPUTE);
            } else {
                pass.read(below, Access.STORAGE_READ);
            }
            PyramidPush push = new PyramidPush().fromDepth(below == null ? 1 : 0);
            int level = mip;
            pass.write(target, Access.STORAGE_WRITE).record(ctx -> {
                int regionWidth = ctx.regionWidth(depth);
                int regionHeight = ctx.regionHeight(depth);
                int levelWidth = levelSize(regionWidth, level);
                int levelHeight = levelSize(regionHeight, level);
                ctx.dispatch(pipeline, push.sourceSize(level == 0 ? regionWidth : levelSize(regionWidth, level - 1),
                    level == 0 ? regionHeight : levelSize(regionHeight, level - 1)).targetSize(levelWidth, levelHeight)
                    .source(below == null ? ctx.sampled(depth) : ctx.storage(below)).target(ctx.storage(target)),
                    (levelWidth + 7) / 8, (levelHeight + 7) / 8, 1);
            });
        }
        return pyramid;
    }

    /** Level {@code mip}'s size along a depth image side of {@code size}: half of it rounded up, then halving down. */
    static int levelSize(int size, int mip) {
        return Math.max(1, ((size + 1) / 2) >> mip);
    }

}
