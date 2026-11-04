package com.truesim.engine.ui;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.app.profile.Counters;
import com.truesim.engine.app.profile.Profiler;
import com.truesim.engine.app.profile.ProfilerSettings;
import com.truesim.engine.app.profile.RuntimeEvents;
import com.truesim.engine.app.profile.ZoneSink;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.Profiling;
import com.truesim.engine.input.Input;
import com.truesim.engine.input.Player;
import com.truesim.engine.platform.Display;
import com.truesim.engine.platform.Platform;
import com.truesim.engine.platform.Window;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.ui.console.Arg;
import com.truesim.ui.console.ConsoleCommand;
import com.truesim.ui.console.ConsoleShell;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.profiler.ChromeTrace;
import com.truesim.ui.profiler.FrameGraphView;
import com.truesim.ui.profiler.FrameTimes;
import com.truesim.ui.profiler.LaneKind;
import com.truesim.ui.profiler.ProfileFile;
import com.truesim.ui.profiler.ProfilerView;
import com.truesim.ui.profiler.Recording;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * The profiler in the UI (ui-profiler's views over the engine's {@link Profiling}): the frame graph overlay
 * ({@code profiler.overlay}) and the detailed profiler ({@code profiler.view}) on a layer of their own
 * ({@code profiler}, above the HUD, under the console), fed once a frame at its start from what the engine measured:
 * the frame clock's last frame (CPU start to submit, waiting, interval, hitch), the GPU's last finished frame (its
 * passes per queue on the CPU's clock), the CPU zones every thread recorded ({@link Profiler#drain}), the runtime's
 * markers, and the counters every {@code profiler.counters.interval}. GPU times come when the GPU finished their frame
 * and are shown under that frame's number: nothing waits for them.
 *
 * <p>The recording keeps {@code profiler.history} seconds and freezes on {@link ProfilerView#frozen()} (the toolbar,
 * {@link ProfilerActions#FREEZE}, the console's {@code profiler.freeze}), takes single frames while frozen
 * ({@code profiler.step}), and freezes by itself {@code profiler.hitch.freezeAfter} frames after a hitch when
 * {@code profiler.hitch.freeze} (or the toolbar's toggle) is on. Exports go to {@code profiler.exportFolder}: the own
 * format ({@code .tsprof}) and Chrome trace JSON ({@code .json}, which Perfetto opens).
 *
 * <p>Per frame it allocates nothing once its rings and the views' text have grown, but for names, lanes and counters
 * met for the first time.
 */
public final class UiProfiler implements ZoneSink, RuntimeEvents.Sink {

    private final Ui ui;
    private final EngineSettings settings;
    private final Profiling profiling;
    private final Input input;
    private final Platform platform;
    private final Window window;
    private final Path defaultFolder;
    private final Recording recording;
    private final FrameTimes times;
    private final FrameGraphView frameGraph;
    private final ProfilerView view;
    private final Element host;
    private Layer layer;
    private boolean overlayShown;
    private boolean viewShown;
    // mappings from the engine's ids to the recording's
    private int[] threadLane = new int[0];
    private int[] zoneName = new int[0];
    private long[][] open = new long[0][]; // per thread, the record of the zone open at each depth
    private int[] openDepth = new int[0]; // per thread, how many are open
    private int graphicsLane = -1;
    private int computeLane = -1;
    private int gcLane = -1;
    private int safepointLane = -1;
    private int jitLane = -1;
    private int gpuFrameName = -1;
    private int frameStartName = -1;
    private int[] counterIds = new int[0];
    private double[] counterValues = new double[64];
    private long counterSeen = -1;
    private int busyCounter = -1;
    private long frameSeen = -1;
    private long gpuSeen = -1;
    private long pendingFreeze = -1; // frames left before freezing after a hitch, -1: none
    private int stepping;
    private String budgetsText;
    private int displayId = -1;
    private final double[] budgetHz = new double[4];
    private String lastExport = "";

    /**
     * The profiler over {@code ui}, made with {@code controls}, fed by {@code profiling}; {@code window} and
     * {@code platform} (may be null: headless) give the display's refresh rate, exports go to
     * {@code defaultFolder} unless {@code profiler.exportFolder} says otherwise.
     */
    @AllowAllocation("set up once")
    public UiProfiler(Ui ui, Controls controls, EngineSettings settings, Profiling profiling, Input input,
        Platform platform, Window window, Path defaultFolder) {
        this.ui = ui;
        this.settings = settings;
        this.profiling = profiling;
        this.input = input;
        this.platform = platform;
        this.window = window;
        this.defaultFolder = defaultFolder;
        recording = new Recording(settings.get(ProfilerSettings.ZONES_PER_LANE), 1 << 16, 1 << 14);
        times = new FrameTimes(ui.store().clock(), settings.get(ProfilerSettings.OVERLAY_FRAMES));
        frameGraph = new FrameGraphView(ui, times);
        ui.interaction().pickable(frameGraph.chart().element(), PointerEvents.NONE); // clicks reach the game
        view = new ProfilerView(controls, recording).onStep(this::step).onExport(this::exportAll);
        host = ui.panel("profiler-host");
        busyCounter = recording.counter("gpu.busy", "%");
    }

    /** The recording the detailed view shows (and the exports write). */
    public Recording recording() {
        return recording;
    }

    /** The frame graph's frame times. */
    public FrameTimes times() {
        return times;
    }

    /** The frame graph overlay. */
    public FrameGraphView frameGraph() {
        return frameGraph;
    }

    /** The detailed profiler. */
    public ProfilerView view() {
        return view;
    }

    /** The folder exports go to now. */
    public Path exportFolder() {
        String folder = settings.get(ProfilerSettings.EXPORT_FOLDER);
        return folder.isEmpty() ? defaultFolder : Path.of(folder);
    }

    /** The last export's files, or empty. */
    public String lastExport() {
        return lastExport;
    }

    /**
     * Registers the profiler's console commands: {@code profiler.freeze [on|off]}, {@code profiler.step},
     * {@code profiler.clear}, {@code profiler.export [tsprof|chrome|both]}, {@code profiler.counters} (every counter's
     * value and what is unavailable here). The settings ({@code profiler.overlay}, {@code profiler.view},
     * {@code profiler.zones}, ...) are console variables already.
     */
    @AllowAllocation("set up once")
    public UiProfiler commands(ConsoleShell shell) {
        shell.register(ConsoleCommand.named("profiler.freeze").describe("Freezes the profiler's recording (or not)")
            .arg(Arg.bool("on").optional()).developer()
            .action(call -> view.frozen().set(call.has("on") ? call.bool("on") : !view.frozen().get())));
        shell.register(ConsoleCommand.named("profiler.step").describe("Takes one frame into the frozen recording")
            .developer().action(call -> step()));
        shell.register(ConsoleCommand.named("profiler.clear").describe("Forgets everything the profiler recorded")
            .developer().action(call -> {
                recording.clear();
                times.clear();
            }));
        shell.register(ConsoleCommand.named("profiler.export")
            .describe("Saves the recording: own format, Chrome trace JSON (Perfetto) or both")
            .arg(Arg.choice("format", "both", "tsprof", "chrome").optional()).developer()
            .action(call -> {
                String format = call.text("format", "both");
                try {
                    lastExport = export(format.equals("chrome") ? null : "tsprof",
                        format.equals("tsprof") ? null : "json");
                    call.print("profiler: saved " + lastExport);
                } catch (IOException e) {
                    call.print("profiler: could not save: " + e.getMessage());
                }
            }));
        shell.register(ConsoleCommand.named("profiler.counters").describe("Prints every counter and what is missing")
            .developer().action(call -> {
                StringBuilder out = new StringBuilder();
                for (int c = 0; c < recording.counters(); c++) {
                    out.append(recording.counterName(c)).append(" = ");
                    com.truesim.ui.profiler.Units.value(out, recording.latestValue(c), recording.counterUnit(c));
                    out.append('\n');
                }
                for (String missing : profiling.counters().unavailable()) {
                    out.append("unavailable: ").append(missing).append('\n');
                }
                call.print(out);
            }));
        return this;
    }

    private void step() {
        stepping = 1;
    }

    @AllowAllocation("exporting, on request")
    private void exportAll() {
        try {
            lastExport = export("tsprof", "json");
        } catch (IOException e) {
            lastExport = "failed: " + e.getMessage();
        }
    }

    /** Saves the recording as {@code own} ({@code .tsprof}, null: not) and {@code chrome} JSON; returns the files. */
    @AllowAllocation("exporting, on request")
    public String export(String own, String chrome) throws IOException {
        Path folder = exportFolder();
        Files.createDirectories(folder);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        StringBuilder files = new StringBuilder();
        if (own != null) {
            Path file = folder.resolve("profile-" + stamp + ".tsprof");
            try (OutputStream out = Files.newOutputStream(file)) {
                ProfileFile.write(recording, out);
            }
            files.append(file);
        }
        if (chrome != null) {
            Path file = folder.resolve("profile-" + stamp + ".json");
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                ChromeTrace.write(recording, out);
            }
            files.append(files.isEmpty() ? "" : ", ").append(file);
        }
        return files.toString();
    }

    // ---------------------------------------------------------------- each frame

    /** Once a frame, at its start: the actions, what shows, and what the engine measured since the last frame. */
    public void update() {
        actions();
        boolean overlay = settings.get(ProfilerSettings.OVERLAY);
        boolean detailed = settings.get(ProfilerSettings.VIEW);
        show(overlay, detailed);
        budgets();
        if (!overlay && !detailed) {
            Profiler.discard();
            return;
        }
        boolean freezeOnHitch = settings.get(ProfilerSettings.FREEZE_ON_HITCH) || view.freezeOnHitch().get();
        boolean frozen = view.frozen().get() && stepping == 0;
        boolean taking = detailed && !frozen;
        if (stepping > 0 && view.frozen().get()) {
            stepping--;
        }
        long frame = Profiler.lastFrame();
        if (frame >= 0 && frame != frameSeen) {
            frameSeen = frame;
            long start = Profiler.lastFrameStart();
            long submit = Profiler.lastFrameSubmit();
            long interval = Profiler.lastFrameInterval();
            boolean hitch = profiling.hitch(frame);
            times.frame(frame, (submit - start) / 1e6, Profiler.lastFrameWait() / 1e6,
                interval < 0 ? Double.NaN : interval / 1e6, hitch);
            if (taking) {
                recording.frame(frame, start, submit, Profiler.lastFrameWait(), hitch);
            }
            if (hitch && freezeOnHitch && pendingFreeze < 0 && !view.frozen().get()) {
                pendingFreeze = settings.get(ProfilerSettings.FREEZE_AFTER);
            }
            if (pendingFreeze >= 0 && pendingFreeze-- == 0) {
                view.frozen().set(true);
                pendingFreeze = -1;
            }
        }
        long gpu = profiling.gpuFrame();
        if (gpu >= 0 && gpu != gpuSeen) {
            gpuSeen = gpu;
            times.gpu(gpu, (profiling.gpuEnd() - profiling.gpuStart()) / 1e6);
            if (taking) {
                gpuZones(gpu);
            }
        }
        if (taking) {
            Profiler.drain(this);
            if (profiling.runtime() != null) {
                profiling.runtime().drain(this);
            }
            counters();
        } else {
            Profiler.discard();
        }
        if (taking) {
            recording.retain((long) (settings.get(ProfilerSettings.HISTORY) * 1e9));
            recording.commit();
        }
    }

    private void actions() {
        Player p = input.player(0);
        if (p.pressed(ProfilerActions.OVERLAY)) {
            settings.set(ProfilerSettings.OVERLAY, settings.get(ProfilerSettings.OVERLAY) ? Boolean.FALSE : Boolean.TRUE);
        }
        if (p.pressed(ProfilerActions.VIEW)) {
            settings.set(ProfilerSettings.VIEW, settings.get(ProfilerSettings.VIEW) ? Boolean.FALSE : Boolean.TRUE);
        }
        if (p.pressed(ProfilerActions.FREEZE)) {
            view.frozen().set(!view.frozen().get());
        }
        if (p.pressed(ProfilerActions.STEP)) {
            step();
        }
        if (p.pressed(ProfilerActions.EXPORT)) {
            exportAll();
        }
    }

    @AllowAllocation("showing or hiding the views, on a setting's change")
    private void show(boolean overlay, boolean detailed) {
        if (overlay == overlayShown && detailed == viewShown) {
            return;
        }
        if (layer == null) {
            layer = ui.layerStack().add("profiler", 550); // over the HUD and pop-ups, under the console (600)
            layer.root().add(host);
        }
        frameGraph.element().detach();
        view.element().detach();
        if (overlay) {
            host.add(frameGraph.element());
        }
        if (detailed) {
            host.add(view.element());
        }
        overlayShown = overlay;
        viewShown = detailed;
    }

    @AllowAllocation("reading the budgets, when the setting or the display changes")
    private void budgets() {
        String text = settings.get(ProfilerSettings.BUDGETS);
        int display = window == null ? -1 : window.display();
        if (text == budgetsText && display == displayId) {
            return;
        }
        budgetsText = text;
        displayId = display;
        Arrays.fill(budgetHz, 0);
        int n = 0;
        for (String part : text.split(",")) {
            String p = part.trim();
            double hz = 0;
            if (p.equalsIgnoreCase("display")) {
                hz = displayHz(display);
            } else {
                try {
                    hz = Double.parseDouble(p);
                } catch (NumberFormatException e) {
                    hz = 0;
                }
            }
            if (hz > 0 && n < budgetHz.length && !contains(hz, n)) {
                budgetHz[n++] = hz;
            }
        }
        frameGraph.budgets(budgetHz);
    }

    private boolean contains(double hz, int n) {
        for (int i = 0; i < n; i++) {
            if (Math.abs(budgetHz[i] - hz) < 0.5) {
                return true;
            }
        }
        return false;
    }

    private double displayHz(int display) {
        if (platform == null || display < 0) {
            return 0;
        }
        for (Display d : platform.displays()) {
            if (d.id() == display && d.current() != null) {
                return d.current().refreshRate();
            }
        }
        return 0;
    }

    private void gpuZones(long frame) {
        if (graphicsLane < 0) {
            lanes();
        }
        long start = profiling.gpuStart();
        long end = profiling.gpuEnd();
        recording.zone(graphicsLane, start, end, gpuFrameName, 0);
        if (profiling.frameStartBegin() != 0) {
            recording.zone(graphicsLane, profiling.frameStartBegin(), profiling.frameStartEnd(), frameStartName, 1);
        }
        for (int i = 0; i < profiling.passCount(); i++) {
            long s = profiling.passStart(i);
            if (s == 0) {
                continue;
            }
            int name = recording.name(profiling.passName(i));
            if (profiling.passOnCompute(i)) {
                recording.zone(computeLane, s, profiling.passEnd(i), name, 0);
            } else {
                recording.zone(graphicsLane, s, profiling.passEnd(i), name, 1);
            }
        }
        recording.gpuFrame(frame, start, end);
        recording.sample(busyCounter, end, profiling.gpuBusy());
        GpuTimings t = profiling.graph().timings();
        if (t != null && t.statisticsOn() && t.statistic(0) >= 0) {
            statistics(t, end);
        }
    }

    private int[] statisticIds;

    @AllowAllocation("the statistics' counters, made once")
    private void statistics(GpuTimings t, long at) {
        if (statisticIds == null) {
            statisticIds = new int[GpuTimings.STATISTICS];
            for (int k = 0; k < statisticIds.length; k++) {
                statisticIds[k] = recording.counter("gpu.pipeline." + GpuTimings.STATISTIC_NAMES.get(k)
                    .replace(' ', '_'), "");
            }
        }
        for (int k = 0; k < statisticIds.length; k++) {
            recording.sample(statisticIds[k], at, t.statistic(k));
        }
    }

    @AllowAllocation("lanes made once")
    private void lanes() {
        graphicsLane = recording.lane("GPU graphics", LaneKind.GPU);
        computeLane = recording.lane("GPU compute", LaneKind.GPU);
        gpuFrameName = recording.name("GPU frame");
        frameStartName = recording.name("frame start");
    }

    private void counters() {
        Counters c = profiling.counters();
        if (c == null || c.sampled() == counterSeen) {
            return;
        }
        counterSeen = c.sampled();
        int n = c.count();
        if (counterIds.length < n || counterValues.length < n) {
            growCounters(c, n);
        }
        c.read(counterValues);
        long now = System.nanoTime();
        for (int i = 0; i < n; i++) {
            if (counterValues[i] == counterValues[i]) {
                recording.sample(counterIds[i], now, counterValues[i]);
            }
        }
    }

    @AllowAllocation("counters met for the first time")
    private void growCounters(Counters c, int n) {
        int old = counterIds.length;
        counterIds = Arrays.copyOf(counterIds, n);
        counterValues = Arrays.copyOf(counterValues, Math.max(n, counterValues.length));
        for (int i = old; i < n; i++) {
            counterIds[i] = recording.counter(c.name(i), c.unit(i));
        }
    }

    // ---------------------------------------------------------------- zones and markers

    @Override
    public void begin(int thread, long nanos, int zone, int depth) {
        if (thread >= threadLane.length || threadLane[thread] < 0) {
            newThread(thread);
        }
        if (zone >= zoneName.length || zoneName[zone] < 0) {
            newZone(zone);
        }
        long[] stack = open[thread];
        if (depth >= stack.length) {
            stack = growStack(thread, depth);
        }
        stack[depth] = recording.open(threadLane[thread], nanos, zoneName[zone], depth);
        openDepth[thread] = depth + 1;
    }

    @Override
    public void end(int thread, long nanos, int depth) {
        if (thread < open.length && depth < openDepth[thread]) {
            recording.close(threadLane[thread], open[thread][depth], nanos);
            openDepth[thread] = depth;
        }
    }

    @Override
    public void lost(int thread, long nanos) {
        if (thread < open.length) {
            for (int d = 0; d < openDepth[thread]; d++) {
                recording.close(threadLane[thread], open[thread][d], nanos);
            }
            openDepth[thread] = 0;
        }
    }

    @Override
    public void marker(RuntimeEvents.Kind kind, String name, long start, long end) {
        if (gcLane < 0) {
            markerLanes();
        }
        int lane = switch (kind) {
            case GC -> gcLane;
            case SAFEPOINT -> safepointLane;
            case JIT -> jitLane;
        };
        recording.zone(lane, start, end, recording.name(name), 0);
    }

    @AllowAllocation("lanes made once")
    private void markerLanes() {
        gcLane = recording.lane("GC", LaneKind.MARKERS);
        safepointLane = recording.lane("Safepoints", LaneKind.MARKERS);
        jitLane = recording.lane("JIT", LaneKind.MARKERS);
    }

    @AllowAllocation("a thread met for the first time")
    private void newThread(int thread) {
        if (thread >= threadLane.length) {
            int old = threadLane.length;
            threadLane = Arrays.copyOf(threadLane, thread + 1);
            open = Arrays.copyOf(open, thread + 1);
            openDepth = Arrays.copyOf(openDepth, thread + 1);
            for (int i = old; i <= thread; i++) {
                threadLane[i] = -1;
                open[i] = new long[16];
            }
        }
        threadLane[thread] = recording.lane(Profiler.threadName(thread), LaneKind.THREAD);
    }

    @AllowAllocation("a zone met for the first time")
    private void newZone(int zone) {
        if (zone >= zoneName.length) {
            int old = zoneName.length;
            zoneName = Arrays.copyOf(zoneName, Math.max(zone + 1, Profiler.zoneCount()));
            Arrays.fill(zoneName, old, zoneName.length, -1);
        }
        zoneName[zone] = recording.name(Profiler.zoneName(zone));
    }

    @AllowAllocation("a deeper zone met for the first time")
    private long[] growStack(int thread, int depth) {
        open[thread] = Arrays.copyOf(open[thread], Math.max(depth + 1, open[thread].length * 2));
        return open[thread];
    }
}
