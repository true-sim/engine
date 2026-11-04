package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueKind;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The transparency {@link Transparency#SLOT} ({@code render.transparency}) names, built as that one: sorted (the
 * default), weighted blended, opaque, or a game's own kind; the graph rebuilds when the setting changes. It lists the
 * scene's depth among the keys it modifies for the opaque mode, which writes it; the others leave it as it is.
 *
 * <pre>{@code
 * view.port().add(new ChosenTransparency(geometry, view));
 * Transparency.SLOT.choose(graph.settings(), Transparency.WEIGHTED);
 * }</pre>
 */
public final class ChosenTransparency implements Transparency {

    private final SortedTransparency sorted;
    private final WeightedBlendedTransparency weighted;
    private final OpaqueTransparency opaque;
    private final SceneGeometry geometry;
    private final SceneView view;
    private final Map<TechniqueKind<?, ?, ?>, Technique> made = new IdentityHashMap<>(); // per kind, once chosen

    /** {@code geometry}'s blended surfaces as {@code view} sees them; added to {@code view}'s port. */
    public ChosenTransparency(SceneGeometry geometry, SceneView view) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(view, "view");
        this.geometry = geometry;
        this.view = view;
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

    /** The geometry whose blended surfaces it draws. */
    public SceneGeometry geometry() {
        return geometry;
    }

    /** The view it draws them in. */
    public SceneView view() {
        return view;
    }

    /** The chosen kind's technique: built in this one's place. */
    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        TechniqueKind<?, ChosenTransparency, Technique> kind = g.technique(SLOT);
        made.computeIfAbsent(kind, k -> make(kind)).build(g);
    }

    /** {@code kind}'s technique, its config read from the view's graph's settings as they are when read. */
    @AllowAllocation("building the graph, when it changes")
    private <C extends Record & TechniqueConfig> Technique make(TechniqueKind<C, ChosenTransparency, Technique> kind) {
        return kind.create(this, () -> kind.config(view.port().settings()));
    }

    /** {@link #SORTED}'s technique: sorted transparency, the scene's depth tested and left as it is. */
    @AllowAllocation("building the graph, when it changes")
    Technique sorted() {
        return new Way(g -> sorted.build(g, g.modify(Keys.SCENE_DEPTH)));
    }

    /** {@link #WEIGHTED}'s technique. */
    @AllowAllocation("building the graph, when it changes")
    Technique weighted() {
        return new Way(g -> weighted.build(g, g.modify(Keys.SCENE_DEPTH)));
    }

    /** {@link #OPAQUE}'s technique, which writes the depth. */
    @AllowAllocation("building the graph, when it changes")
    Technique opaque() {
        return new Way(opaque::build);
    }

    /** One of the engine's ways, built in this one's place. */
    private record Way(java.util.function.Consumer<GraphBuilder> body) implements Technique {

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        @AllowAllocation("building the graph, when it changes")
        public void build(GraphBuilder g) {
            body.accept(g);
        }
    }
}
