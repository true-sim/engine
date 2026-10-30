package com.truesim.engine.render.blocks;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutInt;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Request;
import com.truesim.engine.graph.Subresource;
import com.truesim.engine.render.layout.PyramidPush;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

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

    private static final int PUSH_BYTES = PyramidPush.BYTES;

    public DepthPyramid {
        Objects.requireNonNull(depth, "depth");
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Image build(GraphBuilder g) {
        Pipeline pipeline = g.cached(DepthPyramid.class, device -> {
            try (ShaderModule shader = ShaderModule.load(device, "shaders/blocks/pyramid.spv")) {
                return Pipeline.compute(device, shader, "pyramidMain", PUSH_BYTES);
            }
        });
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
            int[] sizes = {sourceWidth, sourceHeight, levelWidth, levelHeight};
            pass.write(target, Access.STORAGE_WRITE).record(ctx -> level(ctx, pipeline, below, target, sizes));
            sourceWidth = levelWidth;
            sourceHeight = levelHeight;
            levelWidth = Math.max(1, levelWidth / 2); // Vulkan's mip sizes
            levelHeight = Math.max(1, levelHeight / 2);
        }
        return pyramid;
    }

    /** Level {@code target} from the depth (level 0: no {@code below}) or the level below; {@code sizes}: their sizes. */
    private void level(PassContext ctx, Pipeline pipeline, Subresource below, Subresource target, int[] sizes) {
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, PUSH_BYTES);
            memPutInt(push + PyramidPush.SOURCE, below == null ? ctx.sampled(depth) : ctx.storage(below));
            memPutInt(push + PyramidPush.TARGET, ctx.storage(target));
            memPutInt(push + PyramidPush.SOURCE_SIZE, sizes[0]);
            memPutInt(push + PyramidPush.SOURCE_SIZE + 4, sizes[1]);
            memPutInt(push + PyramidPush.TARGET_SIZE, sizes[2]);
            memPutInt(push + PyramidPush.TARGET_SIZE + 4, sizes[3]);
            memPutInt(push + PyramidPush.FROM_DEPTH, below == null ? 1 : 0);
            pipeline.bind(ctx.commands());
            pipeline.push(ctx.commands(), push, PUSH_BYTES);
            Commands.dispatch(ctx.commands(), (sizes[2] + 7) / 8, (sizes[3] + 7) / 8, 1);
        }
    }
}
