package com.truesim.engine.platform;

import java.util.List;

/**
 * A connected display as of when it was listed: its bounds, the part not covered by task bars, its content scale
 * (for DPI-aware UI), its current and desktop modes, and the modes exclusive fullscreen can use, best first.
 */
public record Display(int id, String name, Bounds bounds, Bounds usableBounds, float contentScale, DisplayMode current,
    DisplayMode desktop, List<DisplayMode> fullscreenModes, boolean primary) {
}
