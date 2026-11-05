package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.PassContext;
import java.util.Objects;

/**
 * Depth draws of a renderer's views into a rendering scope a pass opens itself, for techniques drawing several views
 * into one image (a shadow atlas: each view a tile, its viewport set by the pass): as {@link MeshletGeometry} draws a
 * {@link SceneDraw}'s depth, by type where the material table has types with geometry hooks ({@code keep()}: their
 * holes alike in every pass), else with the shared pipelines. Meshlet geometry only.
 */
public final class DepthDraws {

    private final MeshletRenderer renderer;
    private final RasterPipelines.Geometry geometry; // null: every type with the shared pipelines

    private DepthDraws(MeshletRenderer renderer, RasterPipelines.Geometry geometry) {
        this.renderer = renderer;
        this.geometry = geometry;
    }

    /**
     * Depth draws of {@code views} of {@code renderer}, while {@code g} builds: each view's opaque lists split by type
     * with geometry hooks where the table has such types.
     */
    @AllowAllocation("building the graph, when it changes")
    public static DepthDraws of(GraphBuilder g, MeshletRenderer renderer, RenderView... views) {
        Objects.requireNonNull(g, "g");
        RasterPipelines.Geometry geometry = renderer.geometryPipelines(RasterPipelines.DEPTH);
        if (geometry != null) {
            int opaque = (1 << MeshletRenderer.BIN_BLENDED) - 1;
            for (RenderView view : views) {
                view.splitByType(geometry.categories(), opaque, opaque);
            }
        }
        return new DepthDraws(renderer, geometry);
    }

    /** Draws {@code view}'s depth as culled (its only phase) into the pass's attachments. Allocates nothing. */
    public void draw(PassContext ctx, RenderView view) {
        if (geometry != null) {
            renderer.drawGeometryAttached(ctx.unsafe().commands(), view, false, geometry);
        } else {
            renderer.drawAttached(ctx.unsafe().commands(), view, false, true, 0);
        }
    }
}
