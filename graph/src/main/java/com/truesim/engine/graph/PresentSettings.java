package com.truesim.engine.graph;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.PresentMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * How a {@link Presenter} shows a window's frames, as one value: to present with, to change while running
 * ({@link Presenter.Target#apply}), and to save and load ({@link #toMap()}, {@link #fromMap}), e.g. a game's video
 * options. A mode the device cannot present with falls back to vsync ({@link PresentMode#FIFO}).
 *
 * @param presentMode vsync and how late frames show: {@link PresentMode}
 */
@AllowAllocation("set up rarely")
public record PresentSettings(PresentMode presentMode) {

    public PresentSettings {
        Objects.requireNonNull(presentMode, "presentMode");
    }

    /** Vsync ({@link PresentMode#FIFO}): no tearing, at the display's refresh rate. */
    public static PresentSettings defaults() {
        return new PresentSettings(PresentMode.FIFO);
    }

    public PresentSettings withPresentMode(PresentMode presentMode) {
        return new PresentSettings(presentMode);
    }

    /** Every setting as text, by name: to save. {@link #fromMap} reads it back. */
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("presentMode", presentMode.name());
        return map;
    }

    /**
     * Settings saved by {@link #toMap()}: unknown keys are ignored, and missing or unreadable ones keep their default,
     * so settings saved by another version still load.
     */
    public static PresentSettings fromMap(Map<String, String> map) {
        PresentSettings settings = defaults();
        String mode = map.get("presentMode");
        if (mode != null) {
            try {
                settings = settings.withPresentMode(PresentMode.valueOf(mode.strip()));
            } catch (IllegalArgumentException e) {
                // another version's mode: the default
            }
        }
        return settings;
    }
}
