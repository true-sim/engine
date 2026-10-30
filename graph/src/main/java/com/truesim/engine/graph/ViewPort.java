package com.truesim.engine.graph;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A view of a {@link FrameGraph}: a camera's share of the frame, such as the main camera, a mirror, a second player or
 * an editor's viewport, with techniques and outputs of its own ({@link FrameGraph#addView}). Its techniques' keys are
 * the view's: two views can each provide the scene's color, and a technique of a view requiring a key gets its view's
 * version, or the frame's where the view provides none. Techniques added to the graph itself are the frame's, built
 * once for every view: work that depends on no camera, such as uploading the scene. Requests are shared by every equal
 * request, whichever view makes them: one that reads a view's keys names its view among its values.
 *
 * <pre>{@code
 * ViewPort main = graph.addView("main").add(new ForwardShading(...)).output(SCENE_COLOR, window);
 * ViewPort mirror = graph.addView("mirror").add(new ForwardShading(...)).output(SCENE_COLOR, mirrorTarget);
 * }</pre>
 */
@AllowAllocation("set up rarely")
public final class ViewPort {

    private final FrameGraph graph;
    private final String name;
    final List<Technique> techniques = new ArrayList<>();
    final Map<Key<Image>, ExternalImage> outputs = new LinkedHashMap<>();

    ViewPort(FrameGraph graph, String name) {
        this.graph = graph;
        this.name = name;
    }

    /** The view's name, unique in its graph: in errors, and qualifying its histories' names. */
    public String name() {
        return name;
    }

    /** Adds a technique of this view; the graph rebuilds on its next frame. */
    public ViewPort add(Technique technique) {
        Objects.requireNonNull(technique, "technique");
        for (Technique added : techniques) {
            if (added == technique) {
                throw new IllegalArgumentException(technique + " is in view " + name + " already");
            }
        }
        techniques.add(technique);
        graph.changed();
        return this;
    }

    /** Removes a technique of this view, if it is one; the graph rebuilds on its next frame. */
    public ViewPort remove(Technique technique) {
        if (techniques.remove(technique)) {
            graph.changed();
        }
        return this;
    }

    /** Draws the view's result {@code key} into {@code target} every frame; the graph rebuilds on its next frame. */
    public ViewPort output(Key<Image> key, ExternalImage target) {
        outputs.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(target, "target"));
        graph.changed();
        return this;
    }

    /** Stops drawing {@code key} into a target. */
    public ViewPort removeOutput(Key<Image> key) {
        if (outputs.remove(key) != null) {
            graph.changed();
        }
        return this;
    }

    @Override
    public String toString() {
        return "view " + name;
    }
}
