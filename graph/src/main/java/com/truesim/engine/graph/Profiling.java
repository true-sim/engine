package com.truesim.engine.graph;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.app.profile.Counters;
import com.truesim.engine.app.profile.JvmCounters;
import com.truesim.engine.app.profile.OsCounters;
import com.truesim.engine.app.profile.Profiler;
import com.truesim.engine.app.profile.ProfilerSettings;
import com.truesim.engine.app.profile.RuntimeEvents;
import com.truesim.engine.platform.NativeMemory;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingGroup;
import com.truesim.engine.vulkan.GpuClock;
import com.truesim.engine.vulkan.GpuCounters;
import java.util.List;

/**
 * The profiler's engine side as a plugin: it follows the {@link ProfilerSettings} each frame and switches the parts on
 * and off (CPU zones, the frame clock, the frame graph's GPU timings and pipeline statistics, the counters thread with
 * its cheap and detailed sources, the runtime's JFR markers), puts each finished frame's GPU passes on the CPU's clock
 * ({@link GpuClock}) per queue, and marks hitches. Views (the UI's profiler) read it each frame; nothing here waits for
 * the GPU: a frame's GPU times are there once the GPU finished it, frames after its CPU time.
 *
 * <pre>{@code
 * app.use(new Rendering(), new Profiling());
 * engine.settings().set(ProfilerSettings.OVERLAY, true);
 * }</pre>
 *
 * <p>With everything off its frame reads a few settings. Per frame it allocates nothing.
 */
public final class Profiling implements Plugin {

    /** Its key. */
    public static final PluginKey<Profiling> KEY = PluginKey.of("profiling", Profiling.class);

    private EngineSettings settings;
    private FrameGraph graph;
    private Counters counters;
    private GpuClock clock;
    private GpuCounters gpuCounters;
    private RuntimeEvents runtime;
    private boolean runtimeOn;
    private boolean anyOn;
    // the last finished GPU frame, on the CPU's clock
    private long gpuFrame = -1;
    private long gpuStart;
    private long gpuEnd;
    private int passCount;
    private String[] passNames = new String[0];
    private long[] passStart = new long[0];
    private long[] passEnd = new long[0];
    private boolean[] passCompute = new boolean[0];
    private long frameStartBegin;
    private long frameStartEnd;
    private double busy = Double.NaN;
    // submits of recent frames, to bound the GPU's offset without calibration
    private final long[] submitFrame = new long[16];
    private final long[] submitAt = new long[16];
    // hitches
    private double meanInterval = Double.NaN;
    private long hitchFrame = -1;
    private long seenFrame = -1;

    @Override
    public PluginKey<Profiling> key() {
        return KEY;
    }

    @Override
    public List<PluginKey<?>> requires() {
        return List.of(Rendering.KEY);
    }

    @Override
    @AllowAllocation("starting up")
    public void start(Stage stage, Startup startup) {
        switch (stage) {
            case BEFORE_INSTANCE -> {
                settings = startup.settings();
                SettingGroup group = SettingGroup.root("profiler", "settings.profiler", 90);
                startup.registry().add(group, ProfilerSettings.ALL.toArray(new Setting<?>[0]));
            }
            case GRAPH -> {
                Rendering rendering = startup.get(Rendering.KEY);
                graph = rendering.graph();
                clock = new GpuClock(rendering.device());
                gpuCounters = new GpuCounters(rendering.device(), clock);
                counters = new Counters().add(new JvmCounters()).add(new OsCounters()).add(gpuCounters)
                    .add(new NativeCounters());
                runtime = new RuntimeEvents();
                for (int i = 0; i < submitFrame.length; i++) {
                    submitFrame[i] = -1;
                }
            }
            default -> {
                // nothing in the other stages
            }
        }
    }

    /** The counters (started while the profiler shows). */
    public Counters counters() {
        return counters;
    }

    /** The runtime's markers (started while {@code profiler.runtime} is on and the view shows). */
    public RuntimeEvents runtime() {
        return runtime;
    }

    /** The GPU clock the passes are placed by. */
    public GpuClock clock() {
        return clock;
    }

    /** The frame graph. */
    public FrameGraph graph() {
        return graph;
    }

    /** Whether any part of the profiler shows (the overlay or the view). */
    public boolean on() {
        return anyOn;
    }

    /** Whether the detailed view shows. */
    public boolean detailed() {
        return settings.get(ProfilerSettings.VIEW);
    }

    @Override
    public void beforeUpdate(Frame frame) {
        boolean overlay = settings.get(ProfilerSettings.OVERLAY);
        boolean view = settings.get(ProfilerSettings.VIEW);
        boolean on = overlay || view;
        anyOn = on;
        Profiler.frames(on);
        Profiler.zones(view || settings.get(ProfilerSettings.ZONES));
        Profiler.ringEvents(settings.get(ProfilerSettings.RING_EVENTS));
        graph.timings(on && settings.get(ProfilerSettings.GPU));
        graph.pipelineStatistics(view && settings.get(ProfilerSettings.PIPELINE_STATISTICS));
        if (on && settings.get(ProfilerSettings.COUNTERS)) {
            gpuCounters.calibrateEvery(settings.get(ProfilerSettings.CALIBRATE_EVERY));
            clock.window((long) (settings.get(ProfilerSettings.CALIBRATE_EVERY) * 1e9));
            counters.detailed(view && settings.get(ProfilerSettings.OS));
            startCounters(settings.get(ProfilerSettings.COUNTER_INTERVAL));
        }
        boolean runtimeWanted = view && settings.get(ProfilerSettings.RUNTIME);
        if (runtimeWanted != runtimeOn) {
            switchRuntime(runtimeWanted);
        }
        if (on) {
            noteFrame();
            collectGpu();
        }
    }

    @AllowAllocation("starting the counters thread, once")
    private void startCounters(int millis) {
        if (!counters.started()) {
            counters.start(millis);
        } else {
            counters.start(millis); // follows the interval
        }
    }

    @AllowAllocation("switching the JFR stream, on a setting's change")
    private void switchRuntime(boolean on) {
        runtimeOn = on;
        if (on) {
            runtime.start();
        } else {
            runtime.close();
            runtime = new RuntimeEvents();
        }
    }

    /** The frame the profiler's clock ended last: its submit kept, its interval checked for a hitch. */
    private void noteFrame() {
        long n = Profiler.lastFrame();
        if (n < 0 || n == seenFrame) {
            return;
        }
        seenFrame = n;
        int s = (int) Math.floorMod(n, (long) submitFrame.length);
        submitFrame[s] = n;
        submitAt[s] = Profiler.lastFrameSubmit();
        long interval = Profiler.lastFrameInterval();
        if (interval > 0) {
            double ms = interval / 1e6;
            double factor = settings.get(ProfilerSettings.HITCH_FACTOR);
            double floor = settings.get(ProfilerSettings.HITCH_MILLIS);
            boolean hitch = ms >= settings.get(ProfilerSettings.HITCH_MIN)
                && (meanInterval == meanInterval && ms > factor * meanInterval || floor > 0 && ms >= floor);
            if (hitch) {
                hitchFrame = n;
            } else {
                meanInterval = meanInterval != meanInterval ? ms : meanInterval * 0.95 + ms * 0.05;
            }
        }
    }

    /** Whether frame {@code frame} (of {@link Profiler#lastFrame}) was a hitch. */
    public boolean hitch(long frame) {
        return frame == hitchFrame;
    }

    /** The recent mean frame interval in milliseconds (hitches left out), or NaN. */
    public double meanInterval() {
        return meanInterval;
    }

    private void collectGpu() {
        GpuTimings t = graph.timings();
        if (t == null || t.frame() < 0 || t.frame() == gpuFrame) {
            return;
        }
        int n = t.count();
        if (passStart.length < n) {
            grow(n);
        }
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        double fs = t.frameStartMillis();
        if (fs == fs) {
            first = t.frameStartTicks();
            last = t.frameStartEndTicks();
        }
        for (int i = 0; i < n; i++) {
            if (t.millis(i) == t.millis(i)) {
                first = Math.min(first, t.startTicks(i));
                last = Math.max(last, t.endTicks(i));
            }
        }
        if (first == Long.MAX_VALUE) {
            return;
        }
        int s = (int) Math.floorMod(t.frame(), (long) submitFrame.length);
        if (submitFrame[s] == t.frame()) {
            clock.observe(first, submitAt[s]);
        }
        if (!clock.ready()) {
            return;
        }
        gpuFrame = t.frame();
        passCount = n;
        for (int i = 0; i < n; i++) {
            passNames[i] = t.pass(i);
            boolean valid = t.millis(i) == t.millis(i);
            passStart[i] = valid ? clock.toCpu(t.startTicks(i)) : 0;
            passEnd[i] = valid ? clock.toCpu(t.endTicks(i)) : 0;
            passCompute[i] = t.family(i) != t.graphicsFamily();
        }
        frameStartBegin = fs == fs ? clock.toCpu(t.frameStartTicks()) : 0;
        frameStartEnd = fs == fs ? clock.toCpu(t.frameStartEndTicks()) : 0;
        long previousStart = gpuStart;
        gpuStart = clock.toCpu(first);
        gpuEnd = clock.toCpu(last);
        double period = gpuStart - previousStart;
        double interval = meanInterval == meanInterval ? meanInterval * 1e6 : period;
        busy = interval > 0 ? Math.min(100, 100.0 * (gpuEnd - gpuStart) / interval) : Double.NaN;
    }

    @AllowAllocation("grows to the most passes, when the graph changes")
    private void grow(int n) {
        passNames = java.util.Arrays.copyOf(passNames, n);
        passStart = java.util.Arrays.copyOf(passStart, n);
        passEnd = java.util.Arrays.copyOf(passEnd, n);
        passCompute = java.util.Arrays.copyOf(passCompute, n);
    }

    /** The last frame the GPU finished whose times are here (the frame graph's number), or -1. */
    public long gpuFrame() {
        return gpuFrame;
    }

    /** Its first timestamp on the CPU's clock ({@link System#nanoTime()}). */
    public long gpuStart() {
        return gpuStart;
    }

    /** Its last timestamp on the CPU's clock. */
    public long gpuEnd() {
        return gpuEnd;
    }

    /** The GPU's busy share of the frame interval (its span over the recent mean interval), in %, or NaN. */
    public double gpuBusy() {
        return busy;
    }

    /** Its passes. */
    public int passCount() {
        return passCount;
    }

    /** Pass {@code i}'s name. */
    public String passName(int i) {
        return passNames[i];
    }

    /** Pass {@code i}'s start on the CPU's clock (0 if it had no timestamps). */
    public long passStart(int i) {
        return passStart[i];
    }

    /** Pass {@code i}'s end on the CPU's clock. */
    public long passEnd(int i) {
        return passEnd[i];
    }

    /** Whether pass {@code i} ran on the compute queue (async). */
    public boolean passOnCompute(int i) {
        return passCompute[i];
    }

    /** The frame start's commands (uploads) on the CPU's clock, or 0. */
    public long frameStartBegin() {
        return frameStartBegin;
    }

    /** Their end. */
    public long frameStartEnd() {
        return frameStartEnd;
    }

    @Override
    public void stop() {
        Profiler.zones(false);
        Profiler.frames(false);
        if (counters != null) {
            counters.close();
        }
        if (runtime != null) {
            runtime.close();
        }
    }

    /** LWJGL's native memory as the engine counts it ({@link NativeMemory}). */
    private static final class NativeCounters implements Counters.Source {
        private int bytes;
        private int count;

        @Override
        public void declare(Counters into) {
            bytes = into.counter("memory.native", "bytes");
            count = into.counter("memory.nativeAllocations", "");
            if (!NativeMemory.active()) {
                into.unavailable("native memory: LWJGL's allocator was set up before the counting one");
            }
        }

        @Override
        public void sample(Counters into) {
            boolean active = NativeMemory.active();
            into.set(bytes, active ? NativeMemory.bytes() : Double.NaN);
            into.set(count, active ? NativeMemory.count() : Double.NaN);
        }
    }
}
