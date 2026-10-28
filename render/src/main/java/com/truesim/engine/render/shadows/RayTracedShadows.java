package com.truesim.engine.render.shadows;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

/**
 * Sun shadows by hardware ray tracing: provides {@link Keys#SHADOW_MASK}. Each frame the top level is rebuilt from the
 * renderer's instances, then every pixel of {@link Keys#SCENE_DEPTH} traces one ray query towards the sun. Exact,
 * with no resolution limit and no shadow map to fit: what costs is the top level's build and a ray per pixel. Runs as
 * async compute. Interchangeable with {@link ShadowMapShadows} ({@code graph.replace}).
 */
public final class RayTracedShadows implements Technique {

    private static final int PUSH_BYTES = 88;

    private final RayTracing rayTracing;
    private final MeshletRenderer renderer;
    private final Camera camera;
    private float offset = 2.0e-3f;

    /** Shadows of {@code renderer}'s instances, traced through {@code rayTracing}, for the scene {@code camera} sees. */
    public RayTracedShadows(RayTracing rayTracing, MeshletRenderer renderer, Camera camera) {
        this.rayTracing = Objects.requireNonNull(rayTracing, "rayTracing");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.camera = Objects.requireNonNull(camera, "camera");
    }

    /**
     * How far rays start off the surface towards the sun, per unit of distance from the camera (2e-3 by default): the
     * drawn surface is a detail level within a pixel of the traced one, so a ray from it must clear that difference.
     */
    public RayTracedShadows offset(float perUnit) {
        this.offset = perUnit;
        return this;
    }

    @Override
    public List<Key> provides() {
        return List.of(Keys.SHADOW_MASK);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Pipeline pipeline = g.cached(RayTracedShadows.class, device -> {
            try (ShaderModule shader = ShaderModule.load(device, "shaders/shadows/traced.spv")) {
                return Pipeline.compute(device, shader, "tracedMain", PUSH_BYTES);
            }
        });
        Image depth = g.require(Keys.SCENE_DEPTH);
        Image mask = g.createLike("shadow mask", depth, 1, VK_FORMAT_R8_UNORM);
        g.pass("ray-traced shadows")
            .read(depth, Access.SAMPLED_COMPUTE)
            .write(mask, Access.STORAGE_WRITE)
            .async()
            .record(ctx -> trace(ctx, pipeline, depth, mask));
        g.provide(Keys.SHADOW_MASK, mask);
    }

    private void trace(PassContext ctx, Pipeline pipeline, Image depth, Image mask) {
        rayTracing.buildTopLevel(ctx.commands(), renderer);
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(16, PUSH_BYTES);
            float[] inverse = camera.inverseViewProjection();
            for (int i = 0; i < 16; i++) {
                memPutFloat(push + 4L * i, inverse[i]);
            }
            memPutInt(push + 64, ctx.sampled(depth));
            memPutInt(push + 68, ctx.storage(mask));
            memPutInt(push + 72, rayTracing.slot());
            memPutInt(push + 76, mask.width());
            memPutInt(push + 80, mask.height());
            memPutFloat(push + 84, offset);
            pipeline.bind(ctx.commands());
            pipeline.push(ctx.commands(), push, PUSH_BYTES);
            vkCmdDispatch(ctx.commands(), (mask.width() + 7) / 8, (mask.height() + 7) / 8, 1);
        }
    }
}
