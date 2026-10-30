package com.truesim.engine.render.shadows;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneDraw;
import com.truesim.engine.render.ShadowInputs;
import com.truesim.engine.render.layout.TracedPush;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

/**
 * A light's shadows by hardware ray tracing, as a screen-space mask: every pixel of {@code depthSource}'s depth traces
 * one ray query towards the light (on the async compute queue), and shading reads the mask through shadow table entry
 * {@code entry}. Exact, with no resolution limit and no map to fit; works with any shading, forward included (which
 * cannot trace inline: {@link InlineRayShadows}). The depth comes from a {@link SceneDraw} of the camera drawn before
 * shading: the visibility buffer's own (shared), or a depth-only one for forward shading.
 */
public final class RayTracedShadows implements Technique {

    private static final int PUSH_BYTES = TracedPush.BYTES;

    private final RayTracing rayTracing;
    private final Lights lights;
    private final int light;
    private final int entry;
    private final SceneDraw depthSource;
    private final Camera camera;
    private float offset = 2.0e-3f;

    /**
     * Shadows of light {@code light} of {@code lights}, traced into a top level of the depth source's view's scene, for
     * the pixels of {@code depthSource} (drawn from {@code camera}), in shadow table entry {@code entry}.
     */
    public RayTracedShadows(RayTracing rayTracing, Lights lights, int light, int entry, SceneDraw depthSource,
        Camera camera) {
        this.rayTracing = Objects.requireNonNull(rayTracing, "rayTracing");
        this.lights = Objects.requireNonNull(lights, "lights");
        this.light = light;
        this.entry = entry;
        this.depthSource = Objects.requireNonNull(depthSource, "depthSource");
        this.camera = Objects.requireNonNull(camera, "camera");
    }

    /**
     * How far rays start off the surface towards the light, per unit of distance from the camera (2e-3 by default): the
     * drawn surface is a detail level within a pixel of the traced one, so a ray from it must clear that difference.
     */
    public RayTracedShadows offset(float perUnit) {
        this.offset = perUnit;
        return this;
    }

    @Override
    public List<Key> provides() {
        return List.of();
    }

    @Override
    public List<Key> modifies() {
        return List.of(Keys.LIGHT_LIST);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        g.modifyBuffer(Keys.LIGHT_LIST);
        Pipeline pipeline = g.cached(RayTracedShadows.class, device -> {
            try (ShaderModule shader = ShaderModule.load(device, "shaders/shadows/traced.spv")) {
                return Pipeline.compute(device, shader, "tracedMain", PUSH_BYTES);
            }
        });
        Image depth = g.request(depthSource).depth();
        Buffer topLevel = g.request(new TopLevel(rayTracing, depthSource.view()));
        Image mask = g.createLike("shadow mask of light " + light, depth, 1, VK_FORMAT_R8_UNORM);
        g.pass("ray-traced shadows of light " + light)
            .read(depth, Access.SAMPLED_COMPUTE)
            .read(topLevel, BufferAccess.STORAGE_READ)
            .write(mask, Access.STORAGE_WRITE)
            .async()
            .record(ctx -> trace(ctx, pipeline, depth, mask));
        g.request(new ShadowInputs()).sample(mask).setup(ctx -> {
            lights.shadowMask(entry, ctx.sampled(mask), 0);
            lights.shadows(light, entry, 1);
        });
    }

    private void trace(PassContext ctx, Pipeline pipeline, Image depth, Image mask) {
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(16, PUSH_BYTES);
            float[] inverse = camera.inverseViewProjection();
            for (int i = 0; i < 16; i++) {
                memPutFloat(push + TracedPush.INVERSE_VIEW_PROJECTION + 4L * i, inverse[i]);
            }
            memPutInt(push + TracedPush.DEPTH, ctx.sampled(depth));
            memPutInt(push + TracedPush.MASK, ctx.storage(mask));
            memPutInt(push + TracedPush.SCENE, rayTracing.slot());
            memPutInt(push + TracedPush.WIDTH, mask.width());
            memPutInt(push + TracedPush.HEIGHT, mask.height());
            memPutFloat(push + TracedPush.OFFSET, offset);
            memPutLong(push + TracedPush.LIGHTS, lights.lightsAddress());
            memPutInt(push + TracedPush.LIGHT, light);
            // The way from the camera's positions into the top level's (built relative to the depth source's view),
            // and the light relative to the camera: differences of world positions, small enough for floats.
            for (int axis = 0; axis < 3; axis++) {
                double p = camera.position(axis);
                memPutFloat(push + TracedPush.TO_TOP_LEVEL_X + 4L * axis, (float) (p - depthSource.view().position(axis)));
                memPutFloat(push + TracedPush.LIGHT_X + 4L * axis, (float) (lights.position(light, axis) - p));
            }
            pipeline.bind(ctx.commands());
            pipeline.push(ctx.commands(), push, PUSH_BYTES);
            vkCmdDispatch(ctx.commands(), (mask.width() + 7) / 8, (mask.height() + 7) / 8, 1);
        }
    }
}
