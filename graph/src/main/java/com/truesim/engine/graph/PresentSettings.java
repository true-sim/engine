package com.truesim.engine.graph;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.vulkan.PresentMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How a {@link Presenter} shows a window's frames, as one value read from {@link EngineSettings} ({@link #from}): the
 * graph's settings by default, a window's own scoped view, or a value of its own. A mode the device cannot present
 * with falls back to vsync ({@link PresentMode#FIFO}).
 *
 * @param presentMode vsync and how late frames show: {@link PresentMode}
 */
@AllowAllocation("set up rarely")
public record PresentSettings(PresentMode presentMode) {

    /** Vsync and how late frames show. */
    public static final Setting<PresentMode> PRESENT_MODE = Setting.choice("present.mode", PresentMode.FIFO,
        "vsync and how late frames show: FIFO (vsync), FIFO_RELAXED, MAILBOX, IMMEDIATE (no vsync)");

    /** Every present setting. */
    public static final List<Setting<?>> ALL = List.of(PRESENT_MODE);

    public PresentSettings {
        Objects.requireNonNull(presentMode, "presentMode");
    }

    /** Each setting's default ({@link #ALL}): vsync. */
    public static PresentSettings defaults() {
        return from(new EngineSettings());
    }

    /** The present settings {@code settings} holds: defaults where none is set. */
    public static PresentSettings from(EngineSettings settings) {
        return new PresentSettings(settings.get(PRESENT_MODE));
    }

    /** Writes these into {@code settings}, telling its listeners what changed. */
    public EngineSettings store(EngineSettings settings) {
        return settings.set(PRESENT_MODE, presentMode);
    }

    public PresentSettings withPresentMode(PresentMode presentMode) {
        return new PresentSettings(presentMode);
    }

    /** Every setting as text, by name: to save. {@link #fromMap} reads it back. */
    public Map<String, String> toMap() {
        return store(new EngineSettings()).toMap();
    }

    /**
     * Settings saved by {@link #toMap()}: unknown keys are ignored, and missing or unreadable ones keep their default,
     * so settings saved by another version still load.
     */
    public static PresentSettings fromMap(Map<String, String> map) {
        return from(new EngineSettings().load(map));
    }
}
