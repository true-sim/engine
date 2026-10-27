package com.truesim.engine.platform;

/** How a window covers the screen. */
public enum WindowMode {
    /** A normal window with decorations. */
    WINDOWED,
    /** Covers its display at the desktop resolution, without changing the display mode. */
    BORDERLESS_FULLSCREEN,
    /** Owns its display, switched to a chosen {@link DisplayMode}. */
    EXCLUSIVE_FULLSCREEN
}
