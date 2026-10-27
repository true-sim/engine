package com.truesim.engine.platform;

import java.util.Objects;

/** How to create a window; change anything later through {@link Window}. */
public record WindowOptions(String title, int width, int height, boolean resizable, boolean hidden,
    boolean highPixelDensity, boolean vulkan) {

    public WindowOptions {
        Objects.requireNonNull(title, "title");
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Window size must be positive: " + width + " x " + height);
        }
    }

    /** A resizable, visible, high-DPI window for Vulkan rendering. */
    public static WindowOptions of(String title, int width, int height) {
        return new WindowOptions(title, width, height, true, false, true, true);
    }

    public WindowOptions withResizable(boolean resizable) {
        return new WindowOptions(title, width, height, resizable, hidden, highPixelDensity, vulkan);
    }

    /** Hidden windows show nothing until {@link Window#show()}, e.g. for tests or until the first frame is ready. */
    public WindowOptions withHidden(boolean hidden) {
        return new WindowOptions(title, width, height, resizable, hidden, highPixelDensity, vulkan);
    }

    public WindowOptions withHighPixelDensity(boolean highPixelDensity) {
        return new WindowOptions(title, width, height, resizable, hidden, highPixelDensity, vulkan);
    }

    /** Without Vulkan, the window cannot get a Vulkan surface. */
    public WindowOptions withVulkan(boolean vulkan) {
        return new WindowOptions(title, width, height, resizable, hidden, highPixelDensity, vulkan);
    }
}
