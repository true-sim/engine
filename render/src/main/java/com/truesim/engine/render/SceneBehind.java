package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Request;
import com.truesim.engine.graph.Subresource;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.layout.BehindPush;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.Objects;

/**
 * The opaque scene behind transmissive surfaces ({@link Material#FEATURE_TRANSMISSION}): a copy of the scene's color as
 * it is before transparency, with a mip chain (each level averaging the one below), which their shading samples where
 * the light passing through comes from, coarser levels for rougher surfaces. Transparency techniques request it when
 * the materials have transmissive ones, before drawing; equal requests share one copy. Covers the render region of
 * {@code view} (nullable: all of {@code color}), at the top left, as the color has it.
 *
 * <pre>{@code
 * Image behind = g.request(new SceneBehind(color, g.view()));  // sampled in fragment shaders
 * }</pre>
 */
public record SceneBehind(Image color, ViewPort view) implements Request<Image> {

    /** Its format: the scene's light, in half floats. */
    public static final Format FORMAT = Format.R16G16B16A16_SFLOAT;

    public SceneBehind {
        Objects.requireNonNull(color, "color");
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Image build(GraphBuilder g) {
        Pipeline pipeline = g.computeProgram("shaders/blocks/behind.spv", "behindMain", BehindPush.BYTES);
        Image behind = g.create("scene behind " + color.name(), ImageDesc.of(FORMAT, color.width(), color.height())
            .fullMips());
        for (int mip = 0; mip < behind.mips(); mip++) {
            GraphBuilder.PassBuilder pass = g.pass("scene behind " + color.name() + ", level " + mip);
            if (view != null) {
                pass.region(view);
            }
            Subresource below = mip == 0 ? null : behind.mip(mip - 1); // made here: recording allocates nothing
            Subresource target = behind.mip(mip);
            if (below == null) {
                pass.read(color, Access.STORAGE_READ);
            } else {
                pass.read(below, Access.STORAGE_READ);
            }
            BehindPush push = new BehindPush().copy(below == null ? 1 : 0);
            int level = mip;
            pass.write(target, Access.STORAGE_WRITE).record(ctx -> {
                int width = ctx.regionWidth(color);
                int height = ctx.regionHeight(color);
                int levelWidth = Math.max(1, width >> level);
                int levelHeight = Math.max(1, height >> level);
                ctx.dispatch(pipeline, push.sourceSize(level == 0 ? width : Math.max(1, width >> (level - 1)),
                    level == 0 ? height : Math.max(1, height >> (level - 1))).targetSize(levelWidth, levelHeight)
                    .source(below == null ? ctx.storage(color) : ctx.storage(below)).target(ctx.storage(target)),
                    (levelWidth + 7) / 8, (levelHeight + 7) / 8, 1);
            });
        }
        return behind;
    }

    /**
     * The two push words shaders take for {@code behind} ({@code null}: none) in a pass with context {@code ctx}: its
     * sampled slot plus 1, and the render region's size (width in the low 16 bits). Allocates nothing.
     */
    static long words(com.truesim.engine.graph.PassContext ctx, Image behind) {
        if (behind == null) {
            return 0;
        }
        long slot = ctx.sampled(behind) + 1L;
        long region = (ctx.regionWidth(behind) & 0xffffL) | (ctx.regionHeight(behind) & 0xffffL) << 16;
        return slot | region << 32;
    }
}
