package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.Clear;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Load;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Store;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.shadows.ShadowSettings;
import java.util.Objects;

/**
 * A {@link MeshletRenderer}'s instances as scene geometry: meshlets with cluster LOD, culled on the GPU, every view of
 * the frame in one culling pass ({@link MeshletCulling}), the camera with two-phase occlusion culling. Draws depth,
 * shaded color and visibility buffer IDs.
 */
public final class MeshletGeometry implements GeometryProvider {

    private final MeshletRenderer renderer;

    public MeshletGeometry(MeshletRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    public MeshletRenderer renderer() {
        return renderer;
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void drawFirst(GraphBuilder g, SceneDraw draw, SceneDraw.Targets targets, boolean load) {
        if (load) {
            throw new IllegalStateException("Meshlets are drawn first, before other geometry");
        }
        RenderView view = draw.view();
        Camera camera = draw.camera();
        // A light's camera set each frame, with the detail casters allow; a view's own draw's detail chosen at the size
        // it renders this frame.
        ViewPort region = targets.region();
        float casterDetail = camera != null ? g.setting(ShadowSettings.CASTER_DETAIL) : 1;
        Runnable before = camera != null ? () -> view.set(camera, casterDetail, MeshletRenderer.MODE_LIT)
            : region != null ? () -> view.detail(region.renderScale()) : null;
        MeshletCulling.Views culling = g.request(new MeshletCulling(renderer)).add(view, draw.occlusion(), before);
        SceneData.Buffers scene = g.request(new SceneData(renderer));
        GraphBuilder.PassBuilder pass = culling.drawing(scene.read(targets.inRegion(g.pass(name(draw,
            draw.occlusion() ? " early" : ""))), BufferAccess.DRAW_READ), view);
        record(draw, targets, pass, false);
    }

    /**
     * The late phase in two passes: culling against the pyramid (compute), then drawing what became visible over the
     * early phase's attachments. The view's lists pass between them, which orders them.
     */
    @Override
    @AllowAllocation("building the graph, when it changes")
    public void drawLate(GraphBuilder g, SceneDraw draw, SceneDraw.Targets targets, Image pyramid) {
        RenderView view = draw.view();
        Image depth = targets.depth();
        SceneData.Buffers scene = g.request(new SceneData(renderer));
        GraphBuilder.PassBuilder culling = scene.read(targets.inRegion(g.pass(name(draw, " late culling"))),
            BufferAccess.STORAGE_READ).read(pyramid, Access.SAMPLED_COMPUTE);
        renderer.declareCulling(g, culling);
        view.declareCulling(g, culling);
        // Against the pyramid of the depth's render region, which may change every frame.
        culling.record(ctx -> renderer.cullLate(ctx.unsafe().commands(), view, ctx.sampled(pyramid),
            ctx.regionWidth(depth), ctx.regionHeight(depth), pyramid.mips(), true));
        GraphBuilder.PassBuilder pass = scene.read(targets.inRegion(g.pass(name(draw, " late"))),
            BufferAccess.DRAW_READ);
        view.declareDrawing(g, pass);
        record(draw, targets, pass, true);
    }

    @AllowAllocation("building the graph, when it changes")
    private static String name(SceneDraw draw, String phase) {
        return switch (draw.output().kind()) {
            case DEPTH -> "meshlet depth, view " + draw.view().index() + phase;
            case SHADED -> "meshlets" + phase;
            case IDS -> "meshlet ids" + phase;
        };
    }

    /**
     * Declares the attachments (cleared, or with {@code late} loaded) and records the draw: the late phase's lists with
     * {@code late}, else the early (or only) phase's.
     */
    @AllowAllocation("building the graph, when it changes")
    private void record(SceneDraw draw, SceneDraw.Targets targets, GraphBuilder.PassBuilder pass, boolean late) {
        Image depth = targets.depth();
        Image color = targets.color();
        SurfaceOutput.Kind kind = draw.output().kind();
        if (color != null) {
            if (late) {
                pass.attach(color, Load.LOAD, Store.STORE);
            } else {
                pass.attach(color, kind == SurfaceOutput.Kind.SHADED ? Clear.color(draw.output().clear())
                    : Clear.color(0, 0, 0, 0)); // IDs: 0 is nothing
            }
        }
        if (late) {
            pass.attachDepth(depth, Load.LOAD, Store.STORE);
        } else {
            pass.attachDepth(depth, Clear.FAR);
        }
        Buffer lights = targets.lights();
        if (lights != null) {
            pass.read(lights, BufferAccess.DRAW_READ);
        }
        ShadowInputs.Set shadows = targets.shadows();
        if (shadows != null) {
            shadows.readBy(pass, Access.SAMPLED_FRAGMENT, BufferAccess.DRAW_READ);
        }
        RenderView view = draw.view();
        switch (kind) {
            case DEPTH -> pass.record(ctx -> renderer.drawAttached(ctx.unsafe().commands(), view, late, true, 0));
            case SHADED -> pass.record(ctx -> {
                if (shadows != null) {
                    shadows.prepare(ctx);
                }
                renderer.drawAttached(ctx.unsafe().commands(), view, late, false, address(ctx, lights));
            });
            case IDS -> pass.record(ctx -> renderer.drawIdsAttached(ctx.unsafe().commands(), view, late));
        }
    }

    private static long address(PassContext ctx, Buffer lights) {
        return lights == null ? 0 : ctx.address(lights);
    }
}
