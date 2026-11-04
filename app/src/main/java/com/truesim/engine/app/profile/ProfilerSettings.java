package com.truesim.engine.app.profile;

import com.truesim.engine.settings.Setting;
import java.util.List;

/**
 * The profiler's settings ({@code profiler.*}), every part one: what is on, how much is kept, how often counters are
 * sampled, what counts as a hitch. Developer settings, read every frame by the parts using them (the UI's profiler, the
 * {@code Profiling} plugin of engine-graph); the console sets them as variables ({@code profiler.overlay 1}).
 */
public final class ProfilerSettings {

    /** The frame graph overlay: frame times as lines, shippable and cheap. */
    public static final Setting<Boolean> OVERLAY = Setting.bool("profiler.overlay", false,
        "the frame graph overlay: CPU, GPU, wait and frame times");
    /** The detailed profiler's view (timeline, flame graph, zone table, counters). */
    public static final Setting<Boolean> VIEW = Setting.bool("profiler.view", false,
        "the detailed profiler: timeline, flame graph, zone table, counters");
    /** CPU zones: recorded while on, or while the detailed view shows. */
    public static final Setting<Boolean> ZONES = Setting.bool("profiler.zones", false,
        "records CPU zones per thread (also while the profiler view shows)");
    /** GPU passes per queue on the timeline and the GPU frame time (the frame graph's timestamps). */
    public static final Setting<Boolean> GPU = Setting.bool("profiler.gpu", true,
        "times the frame graph's passes on the GPU while the profiler shows");
    /** The cheap counters: memory, CPU load, GPU memory, the GPU's busy share. */
    public static final Setting<Boolean> COUNTERS = Setting.bool("profiler.counters", true,
        "samples the cheap counters (memory, CPU, GPU memory) while the profiler shows");
    /** Garbage collections, safepoints and compilations as markers (a JFR stream; detailed). */
    public static final Setting<Boolean> RUNTIME = Setting.bool("profiler.runtime", false,
        "garbage collections, safepoints and compilations on the timeline (JFR)");
    /** The operating system's counters: GPU engines' load, every core's load (detailed). */
    public static final Setting<Boolean> OS = Setting.bool("profiler.os", false,
        "the operating system's GPU engine and per-core load counters");
    /** Pipeline statistics queries per pass (detailed, where the GPU has them). */
    public static final Setting<Boolean> PIPELINE_STATISTICS = Setting.bool("profiler.pipelineStatistics", false,
        "counts vertices, primitives and shader invocations per pass on the GPU");
    /** Seconds of zones, frames and counters kept. */
    public static final Setting<Float> HISTORY = Setting.decimal("profiler.history", 10, 0.5f, 600,
        "seconds of zones, frames and counters the profiler keeps");
    /** The most zones kept per lane. */
    public static final Setting<Integer> ZONES_PER_LANE = Setting.integer("profiler.zonesPerLane", 1 << 18, 1024,
        1 << 26, "most zones the profiler keeps per thread or queue");
    /** Events a thread's ring holds between two drains (a begin or an end each). */
    public static final Setting<Integer> RING_EVENTS = Setting.integer("profiler.ringEvents", 1 << 16, 256,
        1 << 24, "zone events a thread records between two frames before it loses the oldest");
    /** Frames the frame graph shows. */
    public static final Setting<Integer> OVERLAY_FRAMES = Setting.integer("profiler.overlay.frames", 300, 16, 10_000,
        "frames the frame graph overlay shows");
    /** The frame rates whose budgets the frame graph draws; {@code display}: the display's. */
    public static final Setting<String> BUDGETS = Setting.text("profiler.overlay.budgets", "60,120,144,display",
        "frame rates whose budgets the frame graph draws (display: the display's refresh rate)");
    /** A frame is a hitch when its interval is this many times the mean of the frames before. */
    public static final Setting<Float> HITCH_FACTOR = Setting.decimal("profiler.hitch.factor", 2, 1.1f, 100,
        "a frame is a hitch when it takes this many times the recent mean");
    /** A frame is a hitch when its interval is at least this many milliseconds (0: only the factor). */
    public static final Setting<Float> HITCH_MILLIS = Setting.decimal("profiler.hitch.millis", 0, 0, 10_000,
        "a frame is a hitch when it takes at least this many milliseconds (0: off)");
    /** Freeze the recording after a hitch. */
    public static final Setting<Boolean> FREEZE_ON_HITCH = Setting.bool("profiler.hitch.freeze", false,
        "freezes the profiler shortly after a hitch");
    /** Frames recorded after a hitch before freezing (to see what followed). */
    public static final Setting<Integer> FREEZE_AFTER = Setting.integer("profiler.hitch.freezeAfter", 10, 0, 10_000,
        "frames recorded after a hitch before the profiler freezes");
    /** Milliseconds between counter samples. */
    public static final Setting<Integer> COUNTER_INTERVAL = Setting.integer("profiler.counters.interval", 250, 10,
        60_000, "milliseconds between counter samples");
    /** Seconds between calibrations of the GPU's clock against the CPU's. */
    public static final Setting<Float> CALIBRATE_EVERY = Setting.decimal("profiler.gpu.calibrateEvery", 2, 0.1f, 600,
        "seconds between calibrations of the GPU clock against the CPU clock");
    /** Where exports go (empty: the settings folder's {@code profiles}). */
    public static final Setting<String> EXPORT_FOLDER = Setting.text("profiler.exportFolder", "",
        "folder profiler exports go to (empty: the app's settings folder, profiles)");

    /** Every profiler setting, for registries and options screens. */
    public static final List<Setting<?>> ALL = List.of(OVERLAY, VIEW, ZONES, GPU, COUNTERS, RUNTIME, OS,
        PIPELINE_STATISTICS, HISTORY, ZONES_PER_LANE, RING_EVENTS, OVERLAY_FRAMES, BUDGETS, HITCH_FACTOR, HITCH_MILLIS,
        FREEZE_ON_HITCH, FREEZE_AFTER, COUNTER_INTERVAL, CALIBRATE_EVERY, EXPORT_FOLDER);

    private ProfilerSettings() {
    }
}
