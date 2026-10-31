package com.truesim.engine.graph;

import com.truesim.engine.vulkan.DisplayEncoding;

/**
 * How a {@link Presenter} turns a view's result into what a window shows: tone mapping and encoding for the window's
 * actual display ({@link DisplayEncoding}: SDR, HDR10 or scRGB), made per window as a technique of its view. Given
 * none, the presenter copies the result as it is, in SDR. The render module's {@code DisplayTransform} is one.
 */
@FunctionalInterface
public interface OutputTransform {

    /**
     * A technique of the window's view that provides {@code target} from its result {@code source}: an image of the
     * view's size, made for {@code encoding} (16-bit floats, which the presenter's copy into the window's image turns
     * into the image's format: sRGB-encoded for SDR, as they are for HDR10 and scRGB).
     */
    Technique transform(Key<Image> source, Key<Image> target, DisplayEncoding encoding);

    /**
     * What HDR windows tell their displays about the content, as {@code settings} (the graph's) say now: read again
     * when they change. Null (the default): nothing, the display guesses.
     */
    default com.truesim.engine.vulkan.HdrMetadata metadata(com.truesim.engine.settings.EngineSettings settings) {
        return null;
    }
}
