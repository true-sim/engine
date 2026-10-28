package com.truesim.engine.render.shadows;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
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
import com.truesim.engine.render.SceneDraw;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SurfaceOutput;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

/**
 * Sun shadows the classic way, by a shadow map: provides {@link Keys#SHADOW_MASK}, as {@link RayTracedShadows} does
 * ({@code graph.replace} swaps them). The scene's depth is drawn from the sun's orthographic view ({@code light}, a
 * {@link SceneDraw} of the geometry's view {@code lightView}, culled with the camera's), then every pixel of
 * {@link Keys#SCENE_DEPTH} is compared with it over 3 x 3 texels. Cheap, with the classic trade-offs: the map's
 * resolution over its area, a bias between acne and shadows that come loose from their casters, and nothing outside
 * the map.
 */
public final class ShadowMapShadows implements Technique {

    private static final int PUSH_BYTES = 96;

    private final SceneGeometry geometry;
    private final Camera camera;
    private final Camera light;
    private final int size;
    private final int lightView;
    private final float bias;
    private final float[] clipToLight = new float[16];

    /**
     * Shadows of {@code geometry}, for the scene {@code camera} sees: a {@code size} x {@code size} map from
     * {@code light} (an orthographic {@link Camera#setOrthographic} along the sun, read when recording), drawn as the
     * geometry's view {@code lightView}, compared with {@code bias} (the sun's depth units: a texel's worth of depth
     * slope is a good start).
     */
    public ShadowMapShadows(SceneGeometry geometry, Camera camera, Camera light, int size, int lightView,
        float bias) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.camera = Objects.requireNonNull(camera, "camera");
        this.light = Objects.requireNonNull(light, "light");
        this.size = size;
        this.lightView = Objects.checkIndex(lightView, MeshletRenderer.VIEWS);
        this.bias = bias;
    }

    @Override
    public List<Key> provides() {
        return List.of(Keys.SHADOW_MASK);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Pipeline pipeline = g.cached(ShadowMapShadows.class, device -> {
            try (ShaderModule shader = ShaderModule.load(device, "shaders/shadows/mapped.spv")) {
                return Pipeline.compute(device, shader, "mappedMain", PUSH_BYTES);
            }
        });
        Image map = g.request(new SceneDraw(geometry, lightView, light, SurfaceOutput.depth(), VK_FORMAT_D32_SFLOAT,
            size, size, false)).depth();
        Image depth = g.require(Keys.SCENE_DEPTH);
        Image mask = g.createLike("shadow mask", depth, 1, VK_FORMAT_R8_UNORM);
        g.pass("shadow-mapped shadows")
            .read(depth, Access.SAMPLED_COMPUTE)
            .read(map, Access.SAMPLED_COMPUTE)
            .write(mask, Access.STORAGE_WRITE)
            .async()
            .record(ctx -> compare(ctx, pipeline, depth, map, mask));
        g.provide(Keys.SHADOW_MASK, mask);
    }

    private void compare(PassContext ctx, Pipeline pipeline, Image depth, Image map, Image mask) {
        multiply(light.viewProjection(), camera.inverseViewProjection(), clipToLight);
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(16, PUSH_BYTES);
            for (int i = 0; i < 16; i++) {
                memPutFloat(push + 4L * i, clipToLight[i]);
            }
            memPutInt(push + 64, ctx.sampled(depth));
            memPutInt(push + 68, ctx.storage(mask));
            memPutInt(push + 72, ctx.sampled(map));
            memPutInt(push + 76, mask.width());
            memPutInt(push + 80, mask.height());
            memPutInt(push + 84, size);
            memPutFloat(push + 88, bias);
            pipeline.bind(ctx.commands());
            pipeline.push(ctx.commands(), push, PUSH_BYTES);
            vkCmdDispatch(ctx.commands(), (mask.width() + 7) / 8, (mask.height() + 7) / 8, 1);
        }
    }

    /** {@code out} = {@code a} * {@code b}, column-major 4 x 4. */
    static void multiply(float[] a, float[] b, float[] out) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                double sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += (double) a[k * 4 + row] * b[column * 4 + k];
                }
                out[column * 4 + row] = (float) sum;
            }
        }
    }
}
