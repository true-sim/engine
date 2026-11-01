package com.truesim.engine.render;

import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ViewPort;
import java.util.Objects;

/**
 * What a transparency technique has providers draw the blended raster bin into ({@link GeometryProvider#drawBlended}),
 * as {@link SurfaceOutput} says it for opaque draws: by {@link Kind}, with what shading reads, within {@code region}
 * (null: all of it).
 *
 * @param kind how the blended surfaces are drawn
 * @param color the scene's color ({@link Kind#OPAQUE}); null otherwise
 * @param accumulated weighted color times alpha, and alpha, added up ({@link Kind#WEIGHTED}); null otherwise
 * @param revealage the product of 1 - alpha ({@link Kind#WEIGHTED}); null otherwise
 * @param depth the scene's depth: tested ({@link Kind#WEIGHTED}), or tested and written ({@link Kind#OPAQUE})
 * @param lights the light list, or null
 * @param shadows the shadow inputs, or null
 * @param region the render region, or null
 */
public record BlendedTargets(Kind kind, Image color, Image accumulated, Image revealage, Image depth, Buffer lights,
    ShadowInputs.Set shadows, ViewPort region) {

    /** How blended surfaces are drawn. */
    public enum Kind {
        /** Into accumulated color and revealage, over the opaque depth (weighted blended transparency). */
        WEIGHTED,
        /** Into the scene's color and depth, as opaque. */
        OPAQUE
    }

    public BlendedTargets {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(depth, "depth");
        if (kind == Kind.WEIGHTED ? accumulated == null || revealage == null : color == null) {
            throw new IllegalArgumentException(kind + " takes its targets");
        }
    }

    /** {@code pass}, drawing within the render region of {@link #region} where there is one. */
    public GraphBuilder.PassBuilder inRegion(GraphBuilder.PassBuilder pass) {
        return region == null ? pass : pass.region(region);
    }
}
