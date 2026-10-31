package com.truesim.engine.graph;

import com.truesim.annotations.AllowAllocation;
import java.util.List;
import java.util.Objects;

/**
 * Chooses the render scale of views at a dynamic resolution ({@link ResolutionSettings#DYNAMIC}) from the GPU time of
 * the graph's frames ({@link FrameGraph#timings()}, turned on here): scaled down while frames take longer than
 * {@link ResolutionSettings#TARGET_MILLIS}, up while they take less. Rendering cost goes with the pixels drawn, the
 * square of the scale, so each update moves {@link ResolutionSettings#RESPONSE} of the way to the scale that would meet
 * the target; the views keep it between their minimum and maximum.
 *
 * <pre>{@code
 * DynamicResolution resolution = new DynamicResolution(graph, main);
 * while (running) {
 *     resolution.update(); // once a frame, before recording it
 *     ...
 * }
 * }</pre>
 */
public final class DynamicResolution {

    private final FrameGraph graph;
    private final List<ViewPort> views;
    private float scale = Float.NaN; // as last chosen; at first, the first view's
    private long timed = -1; // the frame of the timings last used

    /** Chooses the scale of {@code views} of {@code graph}, turning its timings on. */
    @AllowAllocation("set up rarely")
    public DynamicResolution(FrameGraph graph, ViewPort... views) {
        this.graph = Objects.requireNonNull(graph, "graph");
        if (views.length == 0) {
            throw new IllegalArgumentException("Dynamic resolution of no view");
        }
        this.views = List.of(views);
        graph.timings(true);
    }

    /**
     * Moves the views' render scale for the last finished frame's GPU time, once per frame timed: from the next frame
     * on, without a rebuild. Allocates nothing.
     */
    public void update() {
        GpuTimings timings = graph.timings();
        if (timings == null || timings.frame() < 0 || timings.frame() == timed) {
            return;
        }
        timed = timings.frame();
        update(timings.totalMillis());
    }

    /** Moves the scale for a frame that took {@code millis} on the GPU. */
    void update(double millis) {
        if (!(millis > 0)) {
            return;
        }
        if (Float.isNaN(scale)) {
            scale = views.getFirst().renderScale();
        }
        float target = graph.settings().get(ResolutionSettings.TARGET_MILLIS);
        float response = graph.settings().get(ResolutionSettings.RESPONSE);
        double meets = scale * Math.sqrt(target / millis);
        float min = Float.MAX_VALUE;
        float max = 0;
        for (ViewPort view : views) {
            min = Math.min(min, view.minScale());
            max = Math.max(max, view.maxScale());
        }
        scale = (float) Math.min(max, Math.max(min, scale + (meets - scale) * response));
        for (ViewPort view : views) {
            view.renderScale(scale);
        }
    }

    /** The scale chosen last, or {@code NaN} before the first update. */
    public float scale() {
        return scale;
    }
}
