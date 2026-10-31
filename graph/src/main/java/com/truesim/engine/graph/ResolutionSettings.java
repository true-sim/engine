package com.truesim.engine.graph;

import com.truesim.engine.settings.Setting;
import java.util.List;

/**
 * How large a {@link ViewPort} renders, relative to the size it shows at ({@link ViewPort#size}): settings of the
 * graph ({@link FrameGraph#settings()}), and of one view where set in its own ({@link ViewPort#ownSettings()}).
 *
 * <p>With a fixed scale (the default) the view's images are its size times {@link #SCALE}, and a new size or scale
 * rebuilds the graph. With {@link #DYNAMIC} resolution its images are allocated for {@link #MAX_SCALE}, with
 * {@link #HEADROOM} to spare, and each frame renders into a region of them, the size times
 * {@link ViewPort#renderScale(float)}: neither a new scale nor a size that still fits rebuilds anything. An allocation
 * larger than needed for {@link #SHRINK_FRAMES} frames in a row is made anew, smaller.
 */
public final class ResolutionSettings {

    /** The render size relative to the size the view shows at; dynamic resolution's first scale. */
    public static final Setting<Float> SCALE = Setting.decimal("resolution.scale", 1, 0.25f, 2,
        "the render size relative to the shown size: below 1 renders fewer pixels and scales them up");

    /** Whether the render region may change every frame, without a rebuild. */
    public static final Setting<Boolean> DYNAMIC = Setting.bool("resolution.dynamic", false,
        "dynamic resolution: the render scale changes every frame, between the minimum and the maximum");

    /** The smallest dynamic render scale. */
    public static final Setting<Float> MIN_SCALE = Setting.decimal("resolution.minScale", 0.5f, 0.25f, 2,
        "the smallest render scale of dynamic resolution");

    /** The largest dynamic render scale: what the view's images are allocated for. */
    public static final Setting<Float> MAX_SCALE = Setting.decimal("resolution.maxScale", 1, 0.25f, 2,
        "the largest render scale of dynamic resolution, which the images are allocated for");

    /** How much larger than needed dynamic resolution allocates, so a growing window rebuilds now and then only. */
    public static final Setting<Float> HEADROOM = Setting.decimal("resolution.headroom", 1.25f, 1, 2,
        "how much larger than needed dynamic resolution allocates its images, so resizing rarely rebuilds");

    /** Frames an allocation may stay larger than needed (with headroom) before it shrinks. */
    public static final Setting<Integer> SHRINK_FRAMES = Setting.integer("resolution.shrinkFrames", 300, 1, 1_000_000,
        "frames dynamic resolution keeps an allocation larger than needed before it shrinks");

    /** Every resolution setting. */
    public static final List<Setting<?>> ALL = List.of(SCALE, DYNAMIC, MIN_SCALE, MAX_SCALE, HEADROOM, SHRINK_FRAMES);

    private ResolutionSettings() {
    }
}
