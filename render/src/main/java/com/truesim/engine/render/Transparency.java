package com.truesim.engine.render;

import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import java.util.List;

/**
 * How blended materials ({@link Material#alphaBlend}) are drawn over a view's opaque scene: a technique that modifies
 * {@link Keys#SCENE_COLOR} at {@link Anchor#TRANSPARENT}, after the opaque scene's changes and before post effects. One
 * per view, under one id, so {@code port.replace(...)} swaps one for another and two in one view fail the build:
 * {@link SortedTransparency} (back to front, exact for surfaces apart), {@link WeightedBlendedTransparency}
 * (order-independent, approximate, cheap for masses of thin layers), {@link OpaqueTransparency}; or
 * {@link ChosenTransparency}, whichever kind {@link #SLOT} ({@code render.transparency}) names. A view without one
 * draws no blended surfaces.
 * Transmissive materials are drawn with the blended ones, showing the opaque scene behind them ({@link SceneBehind}).
 *
 * <pre>{@code
 * view.port().add(new VisibilityBufferShading(geometry, view, color, depth))
 *     .add(new ChosenTransparency(geometry, view));                  // as render.transparency says
 * Transparency.SLOT.choose(graph.settings(), Transparency.WEIGHTED); // render.transparency=weighted
 * view.port().replace(new OpaqueTransparency(geometry, view));       // cheaper: blended drawn as opaque
 * }</pre>
 */
public interface Transparency extends Technique {

    /** The id every transparency technique has: one per view. */
    Id<Transparency> ID = Id.of("truesim.transparency", Transparency.class);

    /**
     * Where {@link ChosenTransparency} takes its way of drawing blended surfaces from: {@code render.transparency}, a
     * kind's id; never off. The graph rebuilds when it changes. A game's own kind makes a {@link Technique} built in
     * the chosen one's place, given it ({@link ChosenTransparency#geometry()}, {@link ChosenTransparency#view()}): it
     * modifies {@link Keys#SCENE_COLOR} and {@link Keys#SCENE_DEPTH} (taking the depth with {@code g.modify} even when
     * it leaves it as it is).
     */
    TechniqueSlot<ChosenTransparency, Technique> SLOT = TechniqueSlot.of("render.transparency", "sorted", false,
        "how blended surfaces are drawn: sorted back to front, weighted (order-independent), or as opaque",
        techniques -> techniques.register(Transparency.SLOT, Transparency.SORTED)
            .register(Transparency.SLOT, Transparency.WEIGHTED).register(Transparency.SLOT, Transparency.OPAQUE));

    /**
     * {@link SortedTransparency}'s tunables, read when the graph builds.
     *
     * @param runs runs of one shading bin drawn back to front across shading bins (at least one per shading bin
     *     drawing blended): past them, a view's blended meshlets are drawn shading bin by shading bin, each's back to
     *     front. Each slot costs an empty indirect draw per such shading bin.
     */
    record Sorted(
        @Tunable(value = "runs of one material kind that sorted transparency draws back to front across material kinds",
            min = 1, max = Materials.MAX_BINS, formerly = "render.transparency.runs") int runs)
        implements TechniqueConfig {
    }

    /** {@link WeightedBlendedTransparency}'s tunables: none. */
    record Weighted() implements TechniqueConfig {
    }

    /** {@link OpaqueTransparency}'s tunables: none. */
    record Opaque() implements TechniqueConfig {
    }

    /** {@link SortedTransparency}: back to front, blended over the scene; exact for surfaces apart. The default. */
    TechniqueKind<Sorted, ChosenTransparency, Technique> SORTED = TechniqueKind.of(SLOT, "sorted", new Sorted(16),
        "back to front, blended over the scene", (chosen, config) -> chosen.sorted());
    /** {@link WeightedBlendedTransparency}: order-independent, approximate; cheap for many thin layers. */
    TechniqueKind<Weighted, ChosenTransparency, Technique> WEIGHTED = TechniqueKind.of(SLOT, "weighted",
        new Weighted(), "order-independent, approximate", (chosen, config) -> chosen.weighted());
    /** {@link OpaqueTransparency}: drawn as opaque, hiding what is behind them. */
    TechniqueKind<Opaque, ChosenTransparency, Technique> OPAQUE = TechniqueKind.of(SLOT, "opaque", new Opaque(),
        "drawn as opaque, hiding what is behind them", (chosen, config) -> chosen.opaque());

    /**
     * {@link #SORTED}'s runs ({@code render.transparency.sorted.runs}), which the culling reads for any sorted
     * transparency of a view, chosen or added directly.
     */
    Setting<Integer> SORTED_RUNS = SORTED.setting("runs");

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
