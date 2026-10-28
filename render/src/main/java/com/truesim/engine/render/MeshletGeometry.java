package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.PassContext;
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
    public void setView(int index, Camera camera, float errorPixels, int mode) {
        renderer.setView(index, camera, errorPixels, mode);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void drawFirst(GraphBuilder g, SceneDraw draw, SceneDraw.Targets targets, boolean load) {
        if (load) {
            throw new IllegalStateException("Meshlets are drawn first, before other geometry");
        }
        int view = draw.view();
        Camera camera = draw.camera();
        MeshletCulling.Views culling = g.request(new MeshletCulling(renderer)).add(view, draw.occlusion(),
            camera == null ? null : () -> renderer.setView(view, camera, 1, MeshletRenderer.MODE_LIT));
        GraphBuilder.PassBuilder pass = g.pass(name(draw, draw.occlusion() ? " early" : ""))
            .read(culling.culled(), BufferAccess.INDIRECT);
        record(draw, targets, pass, false, null);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void drawLate(GraphBuilder g, SceneDraw draw, SceneDraw.Targets targets, Image pyramid) {
        GraphBuilder.PassBuilder pass = g.pass(name(draw, " late")).read(pyramid, Access.SAMPLED_COMPUTE);
        record(draw, targets, pass, true, pyramid);
    }

    @AllowAllocation("building the graph, when it changes")
    private static String name(SceneDraw draw, String phase) {
        return switch (draw.output().kind()) {
            case DEPTH -> "meshlet depth, view " + draw.view() + phase;
            case SHADED -> "meshlets" + phase;
            case IDS -> "meshlet ids" + phase;
        };
    }

    /** Declares the targets and records the draw (after the late phase's culling, with {@code late}). */
    @AllowAllocation("building the graph, when it changes")
    private void record(SceneDraw draw, SceneDraw.Targets targets, GraphBuilder.PassBuilder pass, boolean late,
        Image pyramid) {
        Image depth = targets.depth();
        Image color = targets.color();
        pass.write(depth, Access.DEPTH_ATTACHMENT);
        if (color != null) {
            pass.write(color, Access.COLOR_ATTACHMENT);
        }
        Buffer lights = targets.lights();
        if (lights != null) {
            pass.read(lights, BufferAccess.DRAW_READ);
        }
        ShadowInputs.Set shadows = targets.shadows();
        if (shadows != null) {
            shadows.readBy(pass, Access.SAMPLED_FRAGMENT, BufferAccess.DRAW_READ);
        }
        int view = draw.view();
        int width = draw.width();
        int height = draw.height();
        float[] clear = draw.output().clear();
        switch (draw.output().kind()) {
            case DEPTH -> pass.record(ctx -> {
                if (late) {
                    renderer.recordLate(ctx.commands(), 0, ctx.view(depth), width, height, ctx.sampled(pyramid),
                        pyramid.mips());
                } else {
                    renderer.drawView(ctx.commands(), view, 0, ctx.view(depth), width, height, 0, 0, 0);
                }
            });
            case SHADED -> pass.record(ctx -> {
                if (shadows != null) {
                    shadows.prepare(ctx);
                }
                if (late) {
                    renderer.recordLate(ctx.commands(), ctx.view(color), ctx.view(depth), width, height,
                        ctx.sampled(pyramid), pyramid.mips(), address(ctx, lights));
                } else {
                    renderer.drawView(ctx.commands(), view, ctx.view(color), ctx.view(depth), width, height, clear[0],
                        clear[1], clear[2], address(ctx, lights));
                }
            });
            case IDS -> pass.record(ctx -> {
                if (late) {
                    renderer.recordLateIds(ctx.commands(), ctx.view(color), ctx.view(depth), width, height,
                        ctx.sampled(pyramid), pyramid.mips());
                } else {
                    renderer.drawIds(ctx.commands(), view, ctx.view(color), ctx.view(depth), width, height);
                }
            });
        }
    }

    private static long address(PassContext ctx, Buffer lights) {
        return lights == null ? 0 : ctx.address(lights);
    }
}
