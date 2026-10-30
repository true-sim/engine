package com.truesim.engine.render.blocks;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Request;
import com.truesim.engine.render.layout.PyramidPush;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

/**
 * The farthest-depth pyramid of a (reverse) depth image: a {@code R32_SFLOAT} image half its size (rounded up) with a
 * full mip chain (each level half the one before, rounded down), each texel the smallest depth (the farthest surface)
 * of the texels it covers below, down to one texel for the whole image. Occlusion culling tests bounds against it:
 * something whose nearest depth is farther than the pyramid's farthest over its footprint is hidden. One pass, a
 * dispatch per level.
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
        int mips = GpuImage.fullMipCount(width, height);
        Image pyramid = g.create("depth pyramid of " + depth.name(), width, height, VK_FORMAT_R32_SFLOAT, mips);
        g.pass("depth pyramid of " + depth.name())
            .read(depth, Access.SAMPLED_COMPUTE)
            .write(pyramid, Access.STORAGE_WRITE)
            .record(ctx -> levels(ctx, pipeline, pyramid));
        return pyramid;
    }

    private void levels(PassContext ctx, Pipeline pipeline, Image pyramid) {
        pipeline.bind(ctx.commands());
        int sourceWidth = depth.width();
        int sourceHeight = depth.height();
        int width = pyramid.width();
        int height = pyramid.height();
        for (int mip = 0; mip < pyramid.mips(); mip++) {
            try (MemoryStack stack = stackPush()) {
                if (mip > 0) {
                    // The level below was written by the previous dispatch.
                    Barriers.memory(ctx.commands(), VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                        VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                        VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
                }
                long push = stack.nmalloc(8, PUSH_BYTES);
                memPutInt(push + PyramidPush.SOURCE, mip == 0 ? ctx.sampled(depth) : ctx.storage(pyramid, mip - 1));
                memPutInt(push + PyramidPush.TARGET, ctx.storage(pyramid, mip));
                memPutInt(push + PyramidPush.SOURCE_SIZE, sourceWidth);
                memPutInt(push + PyramidPush.SOURCE_SIZE + 4, sourceHeight);
                memPutInt(push + PyramidPush.TARGET_SIZE, width);
                memPutInt(push + PyramidPush.TARGET_SIZE + 4, height);
                memPutInt(push + PyramidPush.FROM_DEPTH, mip == 0 ? 1 : 0);
                pipeline.push(ctx.commands(), push, PUSH_BYTES);
                vkCmdDispatch(ctx.commands(), (width + 7) / 8, (height + 7) / 8, 1);
            }
            sourceWidth = width;
            sourceHeight = height;
            width = Math.max(1, width / 2); // Vulkan's mip sizes
            height = Math.max(1, height / 2);
        }
    }
}
