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
 * mirror.enabled(mirrorVisible);   // from the next frame, without a rebuild
 * security.rate(4);                // a security camera: every 4th frame
 * }</pre>
 *
 * <p>A view skipped in a frame ({@link #enabled}, {@link #rate}) runs none of its passes, nor shared work only it asked
 * for. Its outputs into a {@link Texture} keep what it last rendered; its other outputs (a window's image) are not
 * touched at all that frame, as a window's image is not acquired then (a {@link Presenter} presents only frames it ran
 * in). Its histories stay as they are, so its next frame reads what its last one wrote.
 */
@AllowAllocation("set up rarely")
public final class ViewPort {

    private final FrameGraph graph;
    private final String name;
    final List<Technique> techniques = new ArrayList<>();
    final Map<Key<Image>, List<ExternalImage>> outputs = new LinkedHashMap<>(); // each key into one target or more
    private final List<AutoCloseable> owned = new ArrayList<>();
    private int width;
    private int height;
    private boolean enabled = true;
    private int rate = 1;
    private int skipped; // frames skipped since it last ran, while enabled
    boolean active; // runs this frame: set when the frame starts
    private ExternalImage[] targets = new ExternalImage[0]; // every output's, to check holds without allocating

    ViewPort(FrameGraph graph, String name) {
        this.graph = graph;
        this.name = name;
    }

    /**
     * The view's own settings: the graph's, scoped to {@code view.<name>}. A {@link Switch} of the view reads its
     * setting here where it is set, else from the graph's.
     */
    public com.truesim.engine.settings.EngineSettings ownSettings() {
        return graph.settings().scoped("view." + name);
    }

    /** The settings of the view's graph ({@link FrameGraph#settings()}), as they are now. */
    public com.truesim.engine.settings.EngineSettings settings() {
        return graph.settings();
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

    /**
     * Turns the view on or off from the next frame, without a rebuild: off, it runs nothing. Turned on, it runs that
     * frame, whatever its {@link #rate}.
     */
    public ViewPort enabled(boolean on) {
        if (on && !enabled) {
            skipped = rate; // due
        }
        enabled = on;
        return this;
    }

    /** Whether the view is on ({@link #enabled(boolean)}). */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Runs the view every {@code frames}th frame (1: every frame), without a rebuild; the next frame it runs in is the
     * next one.
     */
    public ViewPort rate(int frames) {
        if (frames < 1) {
            throw new IllegalArgumentException("A view runs every 1st frame or less often: " + frames);
        }
        rate = frames;
        skipped = frames; // due
        return this;
    }

    /** How often the view runs: every {@code rate()}th frame. */
    public int rate() {
        return rate;
    }

    /** Whether the view runs in the frame being recorded, or for a pass body, the one it records. */
    public boolean active() {
        return active;
    }

    /** Starts a frame: whether the view runs in it. */
    void startFrame() {
        active = enabled && ++skipped >= rate && !allHeld(); // held while due: runs when it may again
        if (active) {
            skipped = 0;
        }
    }

    /**
     * Whether the view is due in the next frame to start: on, and at its rate. It runs then unless every output is
     * held ({@link ExternalImage#hold}).
     */
    boolean dueNextFrame() {
        return enabled && skipped + 1 >= rate;
    }

    /** Whether it has outputs and every one is held: nothing to draw into this frame. */
    private boolean allHeld() {
        if (targets.length == 0) {
            return false;
        }
        for (ExternalImage target : targets) {
            if (!target.held) {
                return false;
            }
        }
        return true;
    }

    /** How many targets its outputs draw into. */
    public int outputCount() {
        return targets.length;
    }

    /** Its outputs' targets, again: they changed. */
    private void retarget() {
        List<ExternalImage> all = new ArrayList<>();
        for (List<ExternalImage> list : outputs.values()) {
            all.addAll(list);
        }
        targets = all.toArray(new ExternalImage[0]);
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

    /**
     * Draws the view's result {@code key} into {@code target} every frame, besides any other targets it has (a headset's
     * eye and a desktop window showing it, say); the graph rebuilds on its next frame.
     */
    public ViewPort output(Key<Image> key, ExternalImage target) {
        Objects.requireNonNull(target, "target");
        List<ExternalImage> targets = outputs.computeIfAbsent(Objects.requireNonNull(key, "key"), k -> new ArrayList<>());
        for (ExternalImage existing : targets) {
            if (existing == target) {
                return this;
            }
        }
        targets.add(target);
        retarget();
        graph.changed();
        return this;
    }

    /** Stops drawing {@code key} into {@code target}, its other targets kept. */
    public ViewPort removeOutput(Key<Image> key, ExternalImage target) {
        List<ExternalImage> targets = outputs.get(key);
        if (targets != null && targets.removeIf(t -> t == target)) {
            if (targets.isEmpty()) {
                outputs.remove(key);
            }
            retarget();
            graph.changed();
        }
        return this;
    }

    /** Stops drawing {@code key} into {@code texture}, its other targets kept. */
    public ViewPort removeOutput(Key<Image> key, Texture texture) {
        return removeOutput(key, texture.external);
    }

    /**
     * Copies the view's result {@code key} into {@code texture} every frame the view runs; a frame it is skipped leaves
     * the texture as it was. The graph rebuilds on its next frame.
     */
    public ViewPort output(Key<Image> key, Texture texture) {
        return output(key, texture.external);
    }

    /** Stops drawing {@code key} into any target. */
    public ViewPort removeOutput(Key<Image> key) {
        if (outputs.remove(key) != null) {
            retarget();
            graph.changed();
        }
        return this;
    }

    @Override
    public String toString() {
        return "view " + name;
    }
}
