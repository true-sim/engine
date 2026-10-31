package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The scene's views in a frame graph, and the techniques every one of them gets: each view a {@link ViewPort} of the
 * graph with a {@link SceneView} of {@code geometry} (its render view owned by the port), given the techniques of
 * every {@link #addPerView factory} when it is added. A view may then drop one ({@link ViewPort#remove}) or take more.
 *
 * <pre>{@code
 * SceneViews views = new SceneViews(graph, geometry)
 *     .addPerView(v -> new ClusteredLights(lights, v))
 *     .addPerView(v -> new VisibilityBufferShading(geometry, v, colorFormat, depthFormat));
 * SceneView main = views.add("main", player, ViewOptions.of(1 << 20, 1 << 24).withOcclusion(true));
 * main.port().size(1920, 1080).output(Keys.SCENE_COLOR, window);
 * SceneView mirror = views.add("mirror", mirrorCamera, ViewOptions.of(1 << 16, 1 << 20));
 * mirror.port().size(512, 512).output(Keys.SCENE_COLOR, mirrorTarget);
 * }</pre>
 */
@AllowAllocation("set up rarely")
public final class SceneViews {

    private final FrameGraph graph;
    private final SceneGeometry geometry;
    private final List<Function<SceneView, ? extends Technique>> perView = new ArrayList<>();
    private final List<SceneView> views = new ArrayList<>();

    /** The views of {@code geometry} in {@code graph}: none yet. */
    public SceneViews(FrameGraph graph, SceneGeometry geometry) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.geometry = Objects.requireNonNull(geometry, "geometry");
    }

    /**
     * Adds the technique {@code factory} makes for a view to every view: those there now and those added later. The
     * graph rebuilds on its next frame.
     */
    public SceneViews addPerView(Function<SceneView, ? extends Technique> factory) {
        perView.add(Objects.requireNonNull(factory, "factory"));
        for (SceneView view : views) {
            view.port().add(factory.apply(view));
        }
        return this;
    }

    /**
     * A new view named {@code name} (unique in the graph) looking through {@code camera}, its render view's lists and
     * history as {@code options} say, with the techniques of every factory; give its port a size and outputs.
     */
    public SceneView add(String name, Camera camera, ViewOptions options) {
        SceneView view = geometry.createView(graph.addView(name), camera, options);
        views.add(view);
        for (Function<SceneView, ? extends Technique> factory : perView) {
            view.port().add(factory.apply(view));
        }
        return view;
    }

    /** Removes {@code view}: its port from the graph, with its techniques and outputs, and its render view. */
    public SceneViews remove(SceneView view) {
        if (views.remove(view)) {
            graph.removeView(view.port());
        }
        return this;
    }

    /** The views, in the order added. */
    public List<SceneView> views() {
        return List.copyOf(views);
    }
}
