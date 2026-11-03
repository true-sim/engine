package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import java.util.List;
import java.util.Objects;

/**
 * The transparency {@link Transparency#MODE} ({@code render.transparency}) names, built as that one: sorted (the
 * default), weighted blended or opaque; the graph rebuilds when the setting changes. It lists the scene's depth among
 * the keys it modifies for the opaque mode, which writes it; the others leave it as it is.
 *
 * <pre>{@code
 * view.port().add(new ChosenTransparency(geometry, view));
 * graph.settings().set(Transparency.MODE, Transparency.Mode.WEIGHTED);
 * }</pre>
 */
public final class ChosenTransparency implements Transparency {

    private final SortedTransparency sorted;
    private final WeightedBlendedTransparency weighted;
    private final OpaqueTransparency opaque;

    /** {@code geometry}'s blended surfaces as {@code view} sees them; added to {@code view}'s port. */
    public ChosenTransparency(SceneGeometry geometry, SceneView view) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(view, "view");
        sorted = new SortedTransparency(geometry, view);
        weighted = new WeightedBlendedTransparency(geometry, view);
        opaque = new OpaqueTransparency(geometry, view);
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR, Keys.SCENE_DEPTH);
    }

    @Override
    public long buildVersion() {
        return sorted.buildVersion();
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        switch (g.setting(MODE)) {
            case SORTED -> sorted.build(g, g.modify(Keys.SCENE_DEPTH)); // tested, left as it is
            case WEIGHTED -> weighted.build(g, g.modify(Keys.SCENE_DEPTH));
            case OPAQUE -> opaque.build(g);
        }
    }
}
