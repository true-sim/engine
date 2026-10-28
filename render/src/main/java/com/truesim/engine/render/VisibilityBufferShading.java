package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
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
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

/**
 * The scene shaded through a visibility buffer: provides {@link Keys#SCENE_COLOR} and {@link Keys#SCENE_DEPTH} from a
 * {@link SceneDraw} of IDs (instance, meshlet, triangle), then shades every pixel once in a compute pass that rebuilds
 * its triangle through the same vertex stage, intersects the pixel's ray with it, and lights it as
 * {@link ClusteredForwardShading} does, by the frame's {@link Keys#LIGHT_LIST} (the sun without one). Shading no longer grows with overdraw; triangles are read twice.
 * {@code graph.replace} swaps the two.
 *
 * <p>The color format must allow storage images (not sRGB). Needs
 * {@link com.truesim.engine.vulkan.VulkanDevice#primitiveIds()}, and meshlet geometry (for now the only provider whose
 * triangles the shading pass can rebuild).
 */
public final class VisibilityBufferShading implements Technique {

    private static final int PUSH_BYTES = 108;

    private final SceneGeometry geometry;
    private final MeshletRenderer renderer;
    private final int width;
    private final int height;
    private final int colorFormat;
    private final int depthFormat;
    private final float[] clear = new float[3];
    private boolean occlusion = true;

    public VisibilityBufferShading(SceneGeometry geometry, int width, int height, int colorFormat, int depthFormat) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        MeshletGeometry meshlets = geometry.provider(MeshletGeometry.class);
        if (meshlets == null || geometry.providers().size() != 1) {
            throw new IllegalArgumentException("The visibility buffer shades meshlet geometry alone, for now");
        }
        this.renderer = meshlets.renderer();
        this.width = width;
        this.height = height;
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
    }

    /** The color behind everything; read when recording. */
    public VisibilityBufferShading clearColor(float r, float g, float b) {
        clear[0] = r;
        clear[1] = g;
        clear[2] = b;
        return this;
    }

    /** Two-phase occlusion culling, on by default; the graph rebuilds when it changes. */
    public VisibilityBufferShading occlusion(boolean on) {
        this.occlusion = on;
        return this;
    }

    @Override
    public List<Key> provides() {
        return List.of(Keys.SCENE_COLOR, Keys.SCENE_DEPTH);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Pipeline shade = g.cached(VisibilityBufferShading.class, device -> {
            try (ShaderModule shader = ShaderModule.load(device, "shaders/visibility/shade.spv")) {
                return Pipeline.compute(device, shader, "shadeMain", PUSH_BYTES);
            }
        });
        SceneDraw.Targets targets = g.request(new SceneDraw(geometry, 0, null, SurfaceOutput.ids(), depthFormat,
            width, height, occlusion));
        Buffer lights = g.optionalBuffer(Keys.LIGHT_LIST);
        Image ids = targets.color();
        Image color = g.create("scene color", width, height, colorFormat);
        GraphBuilder.PassBuilder pass = g.pass("visibility shading")
            .read(ids, Access.STORAGE_READ)
            .write(color, Access.STORAGE_WRITE);
        if (lights != null) {
            pass.read(lights, BufferAccess.STORAGE_READ);
        }
        pass.record(ctx -> shade(ctx, shade, ids, color, lights));
        g.provide(Keys.SCENE_COLOR, color);
        g.provide(Keys.SCENE_DEPTH, targets.depth());
    }

    private void shade(PassContext ctx, Pipeline pipeline, Image ids, Image color, Buffer lights) {
        GeometryPool pool = renderer.pool();
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(16, PUSH_BYTES);
            memPutLong(push, renderer.viewBuffer().deviceAddress());
            memPutLong(push + 8, pool.meshTable().deviceAddress());
            memPutLong(push + 16, renderer.instanceBuffer().deviceAddress());
            memPutLong(push + 24, pool.positions().deviceAddress());
            memPutLong(push + 32, pool.attributes().deviceAddress());
            memPutLong(push + 40, pool.meshlets().deviceAddress());
            memPutLong(push + 48, pool.meshletVertices().deviceAddress());
            memPutLong(push + 56, pool.meshletTriangles().deviceAddress());
            memPutLong(push + 64, renderer.materialsAddress());
            memPutLong(push + 72, lights == null ? 0 : ctx.address(lights));
            memPutInt(push + 80, ctx.storage(ids));
            memPutInt(push + 84, ctx.storage(color));
            memPutInt(push + 88, width);
            memPutInt(push + 92, height);
            memPutFloat(push + 96, clear[0]);
            memPutFloat(push + 100, clear[1]);
            memPutFloat(push + 104, clear[2]);
            pipeline.bind(ctx.commands());
            pipeline.push(ctx.commands(), push, PUSH_BYTES);
            vkCmdDispatch(ctx.commands(), (width + 7) / 8, (height + 7) / 8, 1);
        }
    }
}
