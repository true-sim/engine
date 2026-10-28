package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.blocks.DepthPyramid;
import java.util.List;
import java.util.Objects;

/**
 * The scene drawn by a {@link MeshletRenderer}, as a frame graph technique: provides {@link Keys#SCENE_COLOR} and
 * {@link Keys#SCENE_DEPTH}, cleared to a color and drawn with the renderer's current instances and view. The renderer
 * is set up as usual (instances, view, path); the graph records it.
 *
 * <p>With occlusion culling (the default), the scene is drawn in two phases around a {@link DepthPyramid}: first what
 * was visible last frame, then what the pyramid of that shows has come into view. Hidden objects cost their instance
 * test only; nothing pops in, as what appears is drawn the frame it appears.
 *
 * <pre>{@code
 * graph.add(new MeshletScene(renderer, 1920, 1080, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT));
 * }</pre>
 */
public final class MeshletScene implements Technique {

    private final MeshletRenderer renderer;
    private final int width;
    private final int height;
    private final int colorFormat;
    private final int depthFormat;
    private final float[] clear = new float[3];
    private boolean occlusion = true;
    private Lights lights;

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
     * Shades with {@code lights} (null, the default: the sun alone): clustered forward shading, each pixel lit by the
     * lights of its cluster ({@link LightClusters}, shared with other techniques shading the view). The graph rebuilds
     * when it changes.
     */
    public MeshletScene lights(Lights lights) {
        this.lights = lights;
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
        GraphBuilder.PassBuilder early = g.pass(occlusion ? "meshlets early" : "meshlets")
            .read(culling.culled(), BufferAccess.INDIRECT)
            .write(color, Access.COLOR_ATTACHMENT)
            .write(depth, Access.DEPTH_ATTACHMENT);
        lit(early, clusters).record(ctx -> renderer.drawView(ctx.commands(), 0, ctx.view(color), ctx.view(depth), width,
            height, clear[0], clear[1], clear[2], clusters == null ? 0 : ctx.address(clusters.header())));
        if (occlusion) {
            Image pyramid = g.request(new DepthPyramid(depth));
            GraphBuilder.PassBuilder late = g.pass("meshlets late")
                .read(pyramid, Access.SAMPLED_COMPUTE)
                .write(color, Access.COLOR_ATTACHMENT)
                .write(depth, Access.DEPTH_ATTACHMENT);
            lit(late, clusters).record(ctx -> renderer.recordLate(ctx.commands(), ctx.view(color), ctx.view(depth),
                width, height, ctx.sampled(pyramid), pyramid.mips(),
                clusters == null ? 0 : ctx.address(clusters.header())));
        }
        g.provide(Keys.SCENE_COLOR, color);
        g.provide(Keys.SCENE_DEPTH, depth);
    }

    /** {@code pass}, reading the light clusters if there are any. */
    private static GraphBuilder.PassBuilder lit(GraphBuilder.PassBuilder pass, LightClusters.Grid clusters) {
        return clusters == null ? pass : clusters.readBy(pass, BufferAccess.DRAW_READ);
    }
}
