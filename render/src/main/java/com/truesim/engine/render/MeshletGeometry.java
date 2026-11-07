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
        Runnable before = camera != null ? () -> view.camera(camera, casterDetail) // the view's mode kept
            : region != null ? () -> view.detail(region.renderScale()) : null;
        MeshletCulling.Views culling = g.request(new MeshletCulling(renderer));
        RasterPipelines.Typed typed = null;
        SurfaceOutput.Kind kind = draw.output().kind();
        if (kind == SurfaceOutput.Kind.SHADED
            || kind == SurfaceOutput.Kind.IDS && renderer.scene().materials().hasBlended()) {
            // Shaded by type, from lists split by shading bin as culled: the view's color, or the blended bin a
            // visibility buffer's view draws shaded after its IDs.
            RasterPipelines.Typed pipelines = renderer.forwardPipelines(draw.output().surfaces(), true,
                targets.compiledHooks());
            view.splitByType(pipelines.categories(), kind == SurfaceOutput.Kind.SHADED ? (1 << MeshletRenderer.BINS) - 1
                : 1 << MeshletRenderer.BIN_BLENDED, 0); // a visibility buffer's opaque bins draw IDs: below
            typed = kind == SurfaceOutput.Kind.SHADED ? pipelines : null;
        }
        RasterPipelines.Geometry geometry = geometry(view, kind);
        culling.add(view, draw.occlusion(), before);
        SceneData.Buffers scene = g.request(new SceneData(renderer));
        GraphBuilder.PassBuilder pass = culling.drawing(scene.read(targets.inRegion(g.pass(name(draw,
            draw.occlusion() ? " early" : ""))), BufferAccess.DRAW_READ), view);
        record(draw, targets, pass, false, typed, geometry);
    }

    /**
     * Depth or IDs ({@code kind}) of {@code view} by type with geometry hooks, its opaque bins' lists split so, where
     * the table has such types; else (and for shaded draws) null: every type with the shared pipelines, the lists as
     * culled, nothing split.
     */
    @AllowAllocation("building the graph, when it changes")
    private RasterPipelines.Geometry geometry(RenderView view, SurfaceOutput.Kind kind) {
        if (kind == SurfaceOutput.Kind.SHADED) {
            return null;
        }
        RasterPipelines.Geometry geometry = renderer.geometryPipelines(kind == SurfaceOutput.Kind.DEPTH
            ? RasterPipelines.DEPTH : RasterPipelines.IDS);
        if (geometry != null) {
            int opaque = (1 << MeshletRenderer.BIN_BLENDED) - 1;
            view.splitByType(geometry.categories(), opaque, opaque);
        }
        return geometry;
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
        SurfaceOutput.Kind kind = draw.output().kind();
        RasterPipelines.Geometry geometry = kind == SurfaceOutput.Kind.SHADED ? null
            : renderer.geometryPipelines(kind == SurfaceOutput.Kind.DEPTH ? RasterPipelines.DEPTH : RasterPipelines.IDS);
        record(draw, targets, pass, true, typed(g, view, kind == SurfaceOutput.Kind.SHADED, draw.output().surfaces(),
            true, targets.compiledHooks()), geometry);
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
     * Forward shading by type's pipelines for a draw of {@code view}, if it is {@code shaded} and culling splits the
     * view's lists (its shaded draw did: {@link #drawFirst}); else null.
     */
    @AllowAllocation("building the graph, when it changes")
    private RasterPipelines.Typed typed(GraphBuilder g, RenderView view, boolean shaded, boolean surfaces,
        boolean opaque, ShadingHooks.Set hooks) {
        return shaded && view.splitCategories() > 0 ? renderer.forwardPipelines(surfaces, opaque, hooks) : null;
    }

    /**
     * Declares the attachments (cleared, or with {@code late} loaded) and records the draw: the late phase's lists with
     * {@code late}, else the early (or only) phase's; shaded by type with {@code typed}, depth or IDs by type with
     * geometry hooks with {@code geometry} (null: shared by every type).
     */
    @AllowAllocation("building the graph, when it changes")
    private void record(SceneDraw draw, SceneDraw.Targets targets, GraphBuilder.PassBuilder pass, boolean late,
        RasterPipelines.Typed typed, RasterPipelines.Geometry geometry) {
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
        targets.attachSurfaces(pass, late);
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
        targets.readIndirect(pass, Access.SAMPLED_FRAGMENT, BufferAccess.DRAW_READ);
        IndirectInputs.Set indirect = targets.indirect();
        RenderView view = draw.view();
        switch (kind) {
            case DEPTH -> pass.record(ctx -> {
                if (geometry != null) {
                    renderer.drawGeometryAttached(ctx.unsafe().commands(), view, late, geometry);
                } else {
                    renderer.drawAttached(ctx.unsafe().commands(), view, late, true, 0);
                }
            });
            case SHADED -> pass.record(ctx -> {
                if (shadows != null) {
                    shadows.prepare(ctx);
                }
                renderer.drawTypedAttached(ctx.unsafe().commands(), view, late, address(ctx, lights),
                    indirect == null ? 0 : ctx.address(indirect.table()), typed);
            });
            case IDS -> pass.record(ctx -> {
                if (geometry != null) {
                    renderer.drawGeometryAttached(ctx.unsafe().commands(), view, late, geometry);
                } else {
                    renderer.drawIdsAttached(ctx.unsafe().commands(), view, late);
                }
            });
        }
    }

    /**
     * The blended bin of {@code view}'s lists (both phases', appended: drawn once, after the late cull, which the lists'
     * import orders), into the targets as their kind says.
     */
    @Override
    @AllowAllocation("building the graph, when it changes")
    public void drawBlended(GraphBuilder g, RenderView view, BlendedTargets targets, boolean load) {
        if (load) {
            throw new IllegalStateException("Meshlets are drawn first, before other geometry");
        }
        RasterPipelines.Typed typed = typed(g, view, true, false, false, targets.hooks()); // the opaque draw split them
        if (typed == null) {
            throw new IllegalStateException("View " + view.index() + " draws blended surfaces but its lists are not "
                + "split by shading bin: its opaque draw is built first, shaded or as IDs with blended materials");
        }
        if (targets.kind() == BlendedTargets.Kind.SORTED) {
            // Farthest first, by the culling: run slots enough for every shading bin drawing blended, and the setting's
            int[] numbers = typed.blendedNumbers();
            int drawn = 0;
            for (int number : numbers) {
                drawn = Math.max(drawn, number + 1);
            }
            if (drawn > 0) {
                int slots = Math.max(drawn, Math.min(g.setting(Transparency.SORTED_RUNS),
                    Materials.MAX_BINS / drawn));
                g.request(new MeshletCulling(renderer)).sortBlended(view, slots, numbers);
            }
        }
        SceneData.Buffers scene = g.request(new SceneData(renderer));
        GraphBuilder.PassBuilder pass = scene.read(targets.inRegion(g.pass("meshlets blended")),
            BufferAccess.DRAW_READ);
        view.declareDrawing(g, pass);
        if (targets.kind() == BlendedTargets.Kind.WEIGHTED) {
            pass.attach(targets.accumulated(), Clear.color(0, 0, 0, 0))
                .attach(targets.revealage(), Clear.color(1, 1, 1, 1))
                .attachDepthReadOnly(targets.depth());
        } else if (targets.kind() == BlendedTargets.Kind.SORTED) {
            pass.attach(targets.color(), Load.LOAD, Store.STORE).attachDepthReadOnly(targets.depth());
        } else {
            pass.attach(targets.color(), Load.LOAD, Store.STORE).attachDepth(targets.depth(), Load.LOAD, Store.STORE);
        }
        Buffer lights = targets.lights();
        if (lights != null) {
            pass.read(lights, BufferAccess.DRAW_READ);
        }
        ShadowInputs.Set shadows = targets.shadows();
        if (shadows != null) {
            shadows.readBy(pass, Access.SAMPLED_FRAGMENT, BufferAccess.DRAW_READ);
        }
        targets.readBehind(pass);
        BlendedTargets.Kind kind = targets.kind();
        com.truesim.engine.graph.Image behind = targets.behind();
        pass.record(ctx -> {
            if (shadows != null) {
                shadows.prepare(ctx);
            }
            renderer.drawBlendedTypedAttached(ctx.unsafe().commands(), view, kind, address(ctx, lights),
                SceneBehind.words(ctx, behind), targets.indirectAddress(ctx), typed);
        });
    }

    private static long address(PassContext ctx, Buffer lights) {
        return lights == null ? 0 : ctx.address(lights);
    }
}
