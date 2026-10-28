package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.blocks.DepthPyramid;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

/**
 * The scene drawn by a {@link MeshletRenderer}, as a frame graph technique: provides {@link Keys#SCENE_COLOR} and
 * {@link Keys#SCENE_DEPTH}, cleared to a color and drawn with the renderer's current instances and view (view 0). The
 * renderer is set up as usual (instances, view, path, materials); the graph records it.
 *
 * <p>With occlusion culling (the default), the scene is drawn in two phases around a {@link DepthPyramid}: first what
 * was visible last frame, then what the pyramid of that shows has come into view. Hidden objects cost their instance
 * test only; nothing pops in, as what appears is drawn the frame it appears. Culling is shared with the renderer's
 * other views (a shadow map's, say): one pass for all.
 *
 * <p>How the drawn meshlets are shaded is a choice, {@link Shading}: the same surfaces and lights either way (the sun,
 * or {@link #lights} through the shared {@link LightClusters}).
 *
 * <pre>{@code
 * graph.add(new MeshletScene(renderer, 1920, 1080, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT)
 *     .lights(lights).shading(MeshletScene.Shading.VISIBILITY_BUFFER));
 * }</pre>
 */
public final class MeshletScene implements Technique {

    /** How the scene's meshlets are shaded. */
    public enum Shading {
        /** As they are drawn: each fragment lit by its cluster's lights, overdraw shaded too. */
        CLUSTERED_FORWARD,
        /**
         * Drawn first as IDs only (instance, meshlet, triangle: {@link MeshletRenderer#drawIds}), then each pixel
         * shaded once by a compute pass that rebuilds its triangle: shading no longer grows with overdraw, triangles
         * are read twice. The color format must allow storage images (not sRGB); needs
         * {@link com.truesim.engine.vulkan.VulkanDevice#primitiveIds()}.
         */
        VISIBILITY_BUFFER
    }

    private static final int SHADE_PUSH = 108;

    private final MeshletRenderer renderer;
    private final int width;
    private final int height;
    private final int colorFormat;
    private final int depthFormat;
    private final float[] clear = new float[3];
    private boolean occlusion = true;
    private Lights lights;
    private Shading shading = Shading.CLUSTERED_FORWARD;

    /** The renderer's pipelines must have been made for {@code colorFormat} and {@code depthFormat}. */
    public MeshletScene(MeshletRenderer renderer, int width, int height, int colorFormat, int depthFormat) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.width = width;
        this.height = height;
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
    }

    /** The color behind everything; read when recording, so it may change every frame. */
    public MeshletScene clearColor(float r, float g, float b) {
        clear[0] = r;
        clear[1] = g;
        clear[2] = b;
        return this;
    }

    /** Two-phase occlusion culling against a depth pyramid, on by default; the graph rebuilds when it changes. */
    public MeshletScene occlusion(boolean on) {
        this.occlusion = on;
        return this;
    }

    /**
     * Shades with {@code lights} (null, the default: the sun alone): each pixel lit by the lights of its cluster
     * ({@link LightClusters}, shared with other techniques shading the view). The graph rebuilds when it changes.
     */
    public MeshletScene lights(Lights lights) {
        this.lights = lights;
        return this;
    }

    /** How to shade, {@link Shading#CLUSTERED_FORWARD} by default; the graph rebuilds when it changes. */
    public MeshletScene shading(Shading shading) {
        this.shading = Objects.requireNonNull(shading, "shading");
        return this;
    }

    @Override
    public List<Key> provides() {
        return List.of(Keys.SCENE_COLOR, Keys.SCENE_DEPTH);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Image color = g.create("scene color", width, height, colorFormat);
        Image depth = g.create("scene depth", width, height, depthFormat);
        // Culled with the renderer's other views (a shadow map's, say) in one pass.
        MeshletCulling.Views culling = g.request(new MeshletCulling(renderer)).add(0, occlusion, null);
        LightClusters.Grid clusters = lights == null ? null
            : g.request(new LightClusters(lights, renderer, width, height));
        if (shading == Shading.CLUSTERED_FORWARD) {
            forward(g, culling, clusters, color, depth);
        } else {
            visibility(g, culling, clusters, color, depth);
        }
        g.provide(Keys.SCENE_COLOR, color);
        g.provide(Keys.SCENE_DEPTH, depth);
    }

    /** Draws and shades at once. */
    @AllowAllocation("building the graph, when it changes")
    private void forward(GraphBuilder g, MeshletCulling.Views culling, LightClusters.Grid clusters, Image color,
        Image depth) {
        GraphBuilder.PassBuilder early = g.pass(occlusion ? "meshlets early" : "meshlets")
            .read(culling.culled(), BufferAccess.INDIRECT)
            .write(color, Access.COLOR_ATTACHMENT)
            .write(depth, Access.DEPTH_ATTACHMENT);
        lit(early, clusters, BufferAccess.DRAW_READ).record(ctx -> renderer.drawView(ctx.commands(), 0,
            ctx.view(color), ctx.view(depth), width, height, clear[0], clear[1], clear[2], address(ctx, clusters)));
        if (occlusion) {
            Image pyramid = g.request(new DepthPyramid(depth));
            GraphBuilder.PassBuilder late = g.pass("meshlets late")
                .read(pyramid, Access.SAMPLED_COMPUTE)
                .write(color, Access.COLOR_ATTACHMENT)
                .write(depth, Access.DEPTH_ATTACHMENT);
            lit(late, clusters, BufferAccess.DRAW_READ).record(ctx -> renderer.recordLate(ctx.commands(),
                ctx.view(color), ctx.view(depth), width, height, ctx.sampled(pyramid), pyramid.mips(),
                address(ctx, clusters)));
        }
    }

    /** Draws IDs, then shades every pixel once. */
    @AllowAllocation("building the graph, when it changes")
    private void visibility(GraphBuilder g, MeshletCulling.Views culling, LightClusters.Grid clusters, Image color,
        Image depth) {
        Pipeline shade = g.cached(MeshletScene.class, device -> {
            try (ShaderModule shader = ShaderModule.load(device, "shaders/visibility/shade.spv")) {
                return Pipeline.compute(device, shader, "shadeMain", SHADE_PUSH);
            }
        });
        Image ids = g.create("meshlet ids", width, height, MeshletRenderer.ID_FORMAT);
        g.pass(occlusion ? "meshlet ids early" : "meshlet ids")
            .read(culling.culled(), BufferAccess.INDIRECT)
            .write(ids, Access.COLOR_ATTACHMENT)
            .write(depth, Access.DEPTH_ATTACHMENT)
            .record(ctx -> renderer.drawIds(ctx.commands(), 0, ctx.view(ids), ctx.view(depth), width, height));
        if (occlusion) {
            Image pyramid = g.request(new DepthPyramid(depth));
            g.pass("meshlet ids late")
                .read(pyramid, Access.SAMPLED_COMPUTE)
                .write(ids, Access.COLOR_ATTACHMENT)
                .write(depth, Access.DEPTH_ATTACHMENT)
                .record(ctx -> renderer.recordLateIds(ctx.commands(), ctx.view(ids), ctx.view(depth), width, height,
                    ctx.sampled(pyramid), pyramid.mips()));
        }
        GraphBuilder.PassBuilder pass = g.pass("visibility shading")
            .read(ids, Access.STORAGE_READ)
            .write(color, Access.STORAGE_WRITE);
        lit(pass, clusters, BufferAccess.STORAGE_READ).record(ctx -> shade(ctx, shade, ids, color, clusters));
    }

    private void shade(PassContext ctx, Pipeline pipeline, Image ids, Image color, LightClusters.Grid clusters) {
        GeometryPool pool = renderer.pool();
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(16, SHADE_PUSH);
            memPutLong(push, renderer.viewBuffer().deviceAddress());
            memPutLong(push + 8, pool.meshTable().deviceAddress());
            memPutLong(push + 16, renderer.instanceBuffer().deviceAddress());
            memPutLong(push + 24, pool.positions().deviceAddress());
            memPutLong(push + 32, pool.attributes().deviceAddress());
            memPutLong(push + 40, pool.meshlets().deviceAddress());
            memPutLong(push + 48, pool.meshletVertices().deviceAddress());
            memPutLong(push + 56, pool.meshletTriangles().deviceAddress());
            memPutLong(push + 64, renderer.materialsAddress());
            memPutLong(push + 72, address(ctx, clusters));
            memPutInt(push + 80, ctx.storage(ids));
            memPutInt(push + 84, ctx.storage(color));
            memPutInt(push + 88, width);
            memPutInt(push + 92, height);
            memPutFloat(push + 96, clear[0]);
            memPutFloat(push + 100, clear[1]);
            memPutFloat(push + 104, clear[2]);
            pipeline.bind(ctx.commands());
            pipeline.push(ctx.commands(), push, SHADE_PUSH);
            vkCmdDispatch(ctx.commands(), (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    /** {@code pass}, reading the light clusters with {@code access} if there are any. */
    private static GraphBuilder.PassBuilder lit(GraphBuilder.PassBuilder pass, LightClusters.Grid clusters,
        BufferAccess access) {
        return clusters == null ? pass : clusters.readBy(pass, access);
    }

    private static long address(PassContext ctx, LightClusters.Grid clusters) {
        return clusters == null ? 0 : ctx.address(clusters.header());
    }
}
