package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.PassContext;
import java.util.Objects;

/**
 * Depth draws of a renderer's views into a rendering scope a pass opens itself, for techniques drawing several views
 * into one image (a shadow atlas: each view a tile, its viewport set by the pass): shadow casters, every face, both
 * sides (shadow rays meet every face too), by type where the material table has types with geometry hooks
 * ({@code keep()}: their holes alike in every pass), else with the shared pipelines.
 */
public final class DepthDraws {

    private final MeshletRenderer renderer;
    private final RasterPipelines.Geometry geometry; // null: every type with the shared pipelines

    private final boolean bothSides;

    private DepthDraws(MeshletRenderer renderer, RasterPipelines.Geometry geometry, boolean bothSides) {
        this.renderer = renderer;
        this.geometry = geometry;
        this.bothSides = bothSides;
    }

    /**
     * Depth draws of {@code views} of {@code renderer}, while {@code g} builds: each view's opaque lists split by type
     * with geometry hooks where the table has such types.
     */
    @AllowAllocation("building the graph, when it changes")
    public static DepthDraws of(GraphBuilder g, MeshletRenderer renderer, RenderView... views) {
        return of(g, renderer, true, views);
    }

    /**
     * Like {@link #of(GraphBuilder, MeshletRenderer, RenderView...)}, with {@code bothSides} false the casters' front
     * faces alone (one-sided materials' back faces culled, as a camera's depth): cheaper, but a light inside a closed
     * mesh is not shut in, and one-sided surfaces seen from behind cast nothing.
     */
    @AllowAllocation("building the graph, when it changes")
    public static DepthDraws of(GraphBuilder g, MeshletRenderer renderer, boolean bothSides, RenderView... views) {
        Objects.requireNonNull(g, "g");
        int role = bothSides ? RasterPipelines.SHADOW : RasterPipelines.DEPTH;
        RasterPipelines.Geometry geometry = renderer.geometryPipelines(role);
        if (geometry != null) {
            int opaque = (1 << MeshletRenderer.BIN_BLENDED) - 1;
            for (RenderView view : views) {
                view.splitByType(geometry.categories(), opaque, opaque);
            }
        }
        for (RenderView view : views) {
            view.bothSides(bothSides);
        }
        return new DepthDraws(renderer, geometry, bothSides);
    }

    /** Draws {@code view}'s depth as culled (its only phase) into the pass's attachments. Allocates nothing. */
    public void draw(PassContext ctx, RenderView view) {
        if (geometry != null) {
            renderer.drawGeometryAttached(ctx.unsafe().commands(), view, false, geometry);
        } else {
            if (bothSides) {
                renderer.drawCastersAttached(ctx.unsafe().commands(), view);
            } else {
                renderer.drawAttached(ctx.unsafe().commands(), view, false, true, 0);
            }
        }
    }
}
