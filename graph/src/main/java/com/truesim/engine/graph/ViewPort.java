package com.truesim.engine.graph;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
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
 * ViewPort main = graph.addView("main").size(1920, 1080).add(new ForwardShading(...)).output(SCENE_COLOR, window);
 * ViewPort mirror = graph.addView("mirror").size(512, 512).add(new ForwardShading(...)).output(SCENE_COLOR, mirrorTarget);
 * }</pre>
 */
@AllowAllocation("set up rarely")
public final class ViewPort {

    private final FrameGraph graph;
    private final String name;
    final List<Technique> techniques = new ArrayList<>();
    final Map<Key<Image>, ExternalImage> outputs = new LinkedHashMap<>();
    private final List<AutoCloseable> owned = new ArrayList<>();
    private int width;
    private int height;

    ViewPort(FrameGraph graph, String name) {
        this.graph = graph;
        this.name = name;
    }

    /** The view's name, unique in its graph: in errors, and qualifying its histories' names. */
    public String name() {
        return name;
    }

    /**
     * Sets the size of the view's images, which its techniques read when they build (none until set); the graph
     * rebuilds on its next frame when it changes.
     */
    public ViewPort size(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("A view has a size: " + width + " x " + height);
        }
        if (width != this.width || height != this.height) {
            this.width = width;
            this.height = height;
            graph.changed();
        }
        return this;
    }

    /** The width of the view's images ({@link #size}). */
    public int width() {
        checkSized();
        return width;
    }

    /** The height of the view's images ({@link #size}). */
    public int height() {
        checkSized();
        return height;
    }

    private void checkSized() {
        if (width == 0) {
            throw new IllegalStateException(this + " has no size: set one with size(width, height)");
        }
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

    /**
     * Swaps in {@code technique} for this view's techniques providing any of its keys, e.g. one shading technique for
     * another; removes nothing if none does. The graph rebuilds on its next frame.
     */
    public ViewPort replace(Technique technique) {
        List<Key<?>> keys = technique.provides();
        techniques.removeIf(t -> t.provides().stream().anyMatch(keys::contains));
        return add(technique);
    }

    /** Removes the view's technique whose {@link Technique#id() id} is {@code id}, if it has one; the graph rebuilds. */
    public ViewPort remove(Id<? extends Technique> id) {
        Objects.requireNonNull(id, "id");
        if (techniques.removeIf(t -> t.id() == id)) {
            graph.changed();
        }
        return this;
    }

    /**
     * Gives the view {@code resource}, made for it (its culling lists, say): closed when the view is removed or its
     * graph closes, in the reverse order given. Closed at once, while frames may be in flight: a resource that frames
     * read must retire what they use itself, as a renderer's view does.
     */
    public <T extends AutoCloseable> T own(T resource) {
        owned.add(Objects.requireNonNull(resource, "resource"));
        return resource;
    }

    /** Closes what the view owns, newest first; the first failure is thrown once all were tried. */
    void closeOwned() {
        IllegalStateException failure = null;
        for (int i = owned.size() - 1; i >= 0; i--) {
            AutoCloseable resource = owned.get(i);
            try {
                resource.close();
            } catch (Exception e) {
                IllegalStateException wrapped = new IllegalStateException("Could not close " + resource + " of " + this,
                    e);
                if (failure == null) {
                    failure = wrapped;
                } else {
                    failure.addSuppressed(wrapped);
                }
            }
        }
        owned.clear();
        if (failure != null) {
            throw failure;
        }
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
