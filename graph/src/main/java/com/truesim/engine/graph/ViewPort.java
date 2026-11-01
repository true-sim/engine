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
    private int outputWidth; // the size it shows at
    private int outputHeight;
    private int width; // its images': the render size, or dynamic resolution's allocation
    private int height;
    private int regionWidth; // this frame's render region
    private int regionHeight;
    private int previousRegionWidth; // the one of the frame it last ran in
    private int previousRegionHeight;
    // The resolution settings as of the last build (ResolutionSettings), and the dynamic scale asked for.
    private boolean dynamic;
    private float scale = 1;
    private float minScale = 1;
    private float maxScale = 1;
    private float headroom = 1;
    private int shrinkFrames = 1;
    private float renderScale = Float.NaN; // none asked for: the settings' scale
    private int oversized; // frames in a row its allocation was larger than needed, with headroom
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
     * Sets the size the view shows at (none until set): its images are that size times the render scale
     * ({@link ResolutionSettings}), which its techniques read when they build. The graph rebuilds on its next frame
     * when the images' size changes; under dynamic resolution, a size that still fits its images only moves the render
     * region.
     */
    public ViewPort size(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("A view has a size: " + width + " x " + height);
        }
        if (width != outputWidth || height != outputHeight) {
            outputWidth = width;
            outputHeight = height;
            if (allocate(false)) {
                graph.changed();
            }
        }
        return this;
    }

    /**
     * Allocates the view's images for its size and settings: the size times the scale, or dynamic resolution's maximum
     * with headroom, kept while it fits (and, with {@code shrink}, while it is not larger than needed). Whether that
     * changed their size.
     */
    private boolean allocate(boolean shrink) {
        int newWidth;
        int newHeight;
        if (dynamic) {
            int needWidth = scaled(outputWidth, maxScale);
            int needHeight = scaled(outputHeight, maxScale);
            boolean fits = width >= needWidth && height >= needHeight;
            boolean larger = width > scaled(needWidth, headroom) || height > scaled(needHeight, headroom);
            if (fits && !(shrink && larger)) {
                return false;
            }
            newWidth = scaled(needWidth, headroom);
            newHeight = scaled(needHeight, headroom);
        } else {
            newWidth = scaled(outputWidth, scale);
            newHeight = scaled(outputHeight, scale);
        }
        oversized = 0;
        if (newWidth == width && newHeight == height) {
            return false;
        }
        width = newWidth;
        height = newHeight;
        regionWidth = Math.min(regionWidth == 0 ? width : regionWidth, width);
        regionHeight = Math.min(regionHeight == 0 ? height : regionHeight, height);
        if (!dynamic) {
            regionWidth = width;
            regionHeight = height;
        }
        previousRegionWidth = Math.min(previousRegionWidth == 0 ? width : previousRegionWidth, width);
        previousRegionHeight = Math.min(previousRegionHeight == 0 ? height : previousRegionHeight, height);
        return true;
    }

    /** {@code size} times {@code factor}, rounded up, at least 1. */
    private static int scaled(int size, float factor) {
        return Math.max(1, (int) Math.ceil(size * (double) factor - 1e-4));
    }

    /**
     * Reads the resolution settings for the build under way, the view's own where set, noting their names in
     * {@code read} (a change rebuilds), and allocates the images for them.
     */
    @AllowAllocation("building the graph, when it changes")
    void resolve(com.truesim.engine.settings.EngineSettings settings, java.util.Set<String> read) {
        com.truesim.engine.settings.EngineSettings own = settings.scoped("view." + name);
        boolean wasDynamic = dynamic;
        dynamic = resolution(settings, own, ResolutionSettings.DYNAMIC, read);
        scale = resolution(settings, own, ResolutionSettings.SCALE, read);
        minScale = resolution(settings, own, ResolutionSettings.MIN_SCALE, read);
        maxScale = Math.max(minScale, resolution(settings, own, ResolutionSettings.MAX_SCALE, read));
        headroom = resolution(settings, own, ResolutionSettings.HEADROOM, read);
        shrinkFrames = resolution(settings, own, ResolutionSettings.SHRINK_FRAMES, read);
        if (outputWidth > 0) {
            if (dynamic != wasDynamic) {
                width = 0; // allocated anew for the other mode
                height = 0;
            }
            allocate(oversized >= shrinkFrames);
            if (dynamic) {
                updateRegion(); // within the images as allocated now
            }
        }
    }

    private static <T> T resolution(com.truesim.engine.settings.EngineSettings settings,
        com.truesim.engine.settings.EngineSettings own, com.truesim.engine.settings.Setting<T> setting,
        java.util.Set<String> read) {
        read.add(settings.nameOf(setting));
        read.add(own.nameOf(setting));
        return own.isSet(setting) ? own.get(setting) : settings.get(setting);
    }

    /** The width of the view's images: {@link #size} times the render scale, or dynamic resolution's allocation. */
    public int width() {
        checkSized();
        return width;
    }

    /** The height of the view's images. */
    public int height() {
        checkSized();
        return height;
    }

    /** The width the view shows at ({@link #size}). */
    public int outputWidth() {
        checkSized();
        return outputWidth;
    }

    /** The height the view shows at. */
    public int outputHeight() {
        checkSized();
        return outputHeight;
    }

    /** The smallest render scale of its dynamic resolution, as of the last build. */
    public float minScale() {
        return minScale;
    }

    /** The largest render scale of its dynamic resolution, as of the last build. */
    public float maxScale() {
        return maxScale;
    }

    /** Whether the view renders at a dynamic resolution ({@link ResolutionSettings#DYNAMIC}), as of the last build. */
    public boolean dynamic() {
        return dynamic;
    }

    /**
     * Asks for a render scale from the next frame on, under dynamic resolution: the render region is the view's size
     * times {@code factor}, kept between the settings' minimum and maximum. No rebuild; ignored at a fixed resolution.
     * Allocates nothing.
     */
    public ViewPort renderScale(float factor) {
        if (!(factor > 0)) {
            throw new IllegalArgumentException("A render scale is positive: " + factor);
        }
        renderScale = factor;
        return this;
    }

    /** The render scale of the frame being recorded: the region's width over the width the view shows at. */
    public float renderScale() {
        return outputWidth == 0 ? 1 : regionWidth / (float) outputWidth;
    }

    /**
     * The width of the render region of the frame being recorded: the part of the view's images, from their top left
     * corner, that its passes render into ({@link GraphBuilder.PassBuilder#region()}); all of them at a fixed
     * resolution.
     */
    public int regionWidth() {
        return regionWidth;
    }

    /** The height of the render region of the frame being recorded. */
    public int regionHeight() {
        return regionHeight;
    }

    /** The width of the render region of the last frame the view ran in before this one: for temporal work. */
    public int previousRegionWidth() {
        return previousRegionWidth;
    }

    /** The height of the render region of the last frame the view ran in before this one. */
    public int previousRegionHeight() {
        return previousRegionHeight;
    }

    /** The render region for the scale asked for, within the images. Allocates nothing. */
    private void updateRegion() {
        float factor = Float.isNaN(renderScale) ? scale : renderScale;
        factor = Math.min(maxScale, Math.max(minScale, factor));
        regionWidth = Math.min(width, scaled(outputWidth, factor));
        regionHeight = Math.min(height, scaled(outputHeight, factor));
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
            previousRegionWidth = regionWidth;
            previousRegionHeight = regionHeight;
            if (dynamic) {
                updateRegion();
            }
        }
        if (dynamic && outputWidth > 0) {
            int needWidth = scaled(scaled(outputWidth, maxScale), headroom);
            int needHeight = scaled(scaled(outputHeight, maxScale), headroom);
            oversized = width > needWidth || height > needHeight ? oversized + 1 : 0;
            if (oversized == shrinkFrames) {
                graph.changed(); // larger than needed for long enough: shrinks when it rebuilds
            }
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
        String place = graph.placeOf(technique);
        if (place != null) {
            throw new IllegalArgumentException(technique + " is in " + place + " already: a technique builds for one "
                + "view, so give each view one of its own");
        }
        techniques.add(technique);
        graph.changed();
        return this;
    }

    /**
     * Swaps in {@code technique} for this view's techniques providing any of its keys, e.g. one shading technique for
     * another, and for those of its {@link Technique#id() id}, e.g. one transparency technique for another; removes
     * nothing if none does. The graph rebuilds on its next frame.
     */
    public ViewPort replace(Technique technique) {
        List<Key<?>> keys = technique.provides();
        Id<? extends Technique> id = technique.id();
        techniques.removeIf(t -> t.provides().stream().anyMatch(keys::contains) || id != null && t.id() == id);
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
