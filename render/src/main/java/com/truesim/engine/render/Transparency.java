package com.truesim.engine.render;

import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.settings.Setting;
import java.util.List;

/**
 * How blended materials ({@link Material#alphaBlend}) are drawn over a view's opaque scene: a technique that modifies
 * {@link Keys#SCENE_COLOR} at {@link Anchor#TRANSPARENT}, after the opaque scene's changes and before post effects. One
 * per view, under one id, so {@code port.replace(...)} swaps one for another and two in one view fail the build:
 * {@link SortedTransparency} (back to front, exact for surfaces apart), {@link WeightedBlendedTransparency}
 * (order-independent, approximate, cheap for masses of thin layers), {@link OpaqueTransparency}; or
 * {@link ChosenTransparency}, whichever {@link #MODE} names. A view without one draws no blended surfaces.
 * Transmissive materials are drawn with the blended ones, showing the opaque scene behind them ({@link SceneBehind}).
 *
 * <pre>{@code
 * view.port().add(new VisibilityBufferShading(geometry, view, color, depth))
 *     .add(new ChosenTransparency(geometry, view));                  // as render.transparency says
 * view.port().replace(new OpaqueTransparency(geometry, view));       // cheaper: blended drawn as opaque
 * }</pre>
 */
public interface Transparency extends Technique {

    /** The id every transparency technique has: one per view. */
    Id<Transparency> ID = Id.of("truesim.transparency", Transparency.class);

    /** The ways blended surfaces are drawn ({@link #MODE}). */
    enum Mode {
        /** {@link SortedTransparency}: back to front, blended over the scene; exact for surfaces apart. */
        SORTED,
        /** {@link WeightedBlendedTransparency}: order-independent, approximate; cheap for many thin layers. */
        WEIGHTED,
        /** {@link OpaqueTransparency}: drawn as opaque, hiding what is behind them. */
        OPAQUE
    }

    /** How {@link ChosenTransparency} draws blended surfaces; the graph rebuilds when it changes. */
    Setting<Mode> MODE = Setting.choice("render.transparency", Mode.SORTED,
        "how blended surfaces are drawn: sorted back to front, weighted (order-independent), or as opaque");

    /**
     * Runs of one shading bin {@link SortedTransparency} draws back to front across shading bins (at least one per
     * shading bin drawing blended): past them, a view's blended meshlets are drawn shading bin by shading bin, each's
     * back to front. Each slot costs an empty indirect draw per such shading bin.
     */
    Setting<Integer> SORTED_RUNS = Setting.integer("render.transparency.runs", 16, 1, Materials.MAX_BINS,
        "runs of one material kind that sorted transparency draws back to front across material kinds");

    /** Nothing: transparency modifies the scene's color. */
    @Override
    default List<Key<?>> provides() {
        return List.of();
    }

    @Override
    default Id<? extends Technique> id() {
        return ID;
    }

    /** Over the opaque scene: at {@link Anchor#TRANSPARENT}. */
    @Override
    default Order order() {
        return Order.at(Anchor.TRANSPARENT);
    }

    /**
     * Whether {@code materials} has blended ones and transmissive ones, as a build version: the passes come and go
     * with them; and its shading bins with the raster bins they draw in, since the blended bin draws by type: a bin
     * first drawn blended rebuilds the graph with its pipelines.
     */
    static long buildVersion(Materials materials) {
        return materials == null ? 0 : ((materials.hasBlended() ? 1 : 0) | (materials.hasTransmissive() ? 2 : 0))
            + 4 * materials.drawnVersion();
    }

    /**
     * The opaque scene behind transmissive surfaces, of {@code color} as it is now, where {@code materials} has
     * transmissive ones ({@link SceneBehind}); else null.
     */
    static com.truesim.engine.graph.Image behind(GraphBuilder g, Materials materials,
        com.truesim.engine.graph.Image color) {
        return materials != null && materials.hasTransmissive() ? g.request(new SceneBehind(color, g.view())) : null;
    }
}
