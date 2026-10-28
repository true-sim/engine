package com.truesim.engine.render.shadows;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Keys;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import java.util.List;
import org.lwjgl.system.MemoryStack;

/**
 * The scene's color with the sun's shadows: provides {@link Keys#SHADOWED_COLOR} from {@link Keys#SCENE_COLOR} and
 * {@link Keys#SHADOW_MASK}, whichever technique makes the mask. Shadowed pixels keep {@code ambient} of their light.
 */
public final class ShadowedScene implements Technique {

    private static final int PUSH_BYTES = 24;

    private final float ambient;

    /** Shadows leaving {@code ambient} (0 to 1) of the light. */
    public ShadowedScene(float ambient) {
        this.ambient = ambient;
    }

    @Override
    public List<Key> provides() {
        return List.of(Keys.SHADOWED_COLOR);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Pipeline pipeline = g.cached(ShadowedScene.class, device -> {
            try (ShaderModule shader = ShaderModule.load(device, "shaders/shadows/composite.spv")) {
                return Pipeline.compute(device, shader, "compositeMain", PUSH_BYTES);
            }
        });
        Image color = g.require(Keys.SCENE_COLOR);
        Image mask = g.require(Keys.SHADOW_MASK);
        Image shadowed = g.createLike("shadowed scene color", color, 1);
        g.pass("shadowed scene")
            .read(color, Access.SAMPLED_COMPUTE)
            .read(mask, Access.SAMPLED_COMPUTE)
            .write(shadowed, Access.STORAGE_WRITE)
            .record(ctx -> composite(ctx, pipeline, color, mask, shadowed));
        g.provide(Keys.SHADOWED_COLOR, shadowed);
    }

    private void composite(PassContext ctx, Pipeline pipeline, Image color, Image mask, Image shadowed) {
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, PUSH_BYTES);
            memPutInt(push, ctx.sampled(color));
            memPutInt(push + 4, ctx.sampled(mask));
            memPutInt(push + 8, ctx.storage(shadowed));
            memPutInt(push + 12, shadowed.width());
            memPutInt(push + 16, shadowed.height());
            memPutFloat(push + 20, ambient);
            pipeline.bind(ctx.commands());
            pipeline.push(ctx.commands(), push, PUSH_BYTES);
            vkCmdDispatch(ctx.commands(), (shadowed.width() + 7) / 8, (shadowed.height() + 7) / 8, 1);
        }
    }
}
