package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.app.StartupFailure;
import com.truesim.engine.app.profile.Profiler;
import com.truesim.engine.app.profile.ProfilerSettings;
import com.truesim.engine.graph.Profiling;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.input.Input;
import com.truesim.engine.render.Keys;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuMemory;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.profiler.LaneKind;
import com.truesim.ui.profiler.Recording;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The profiler running in an engine (headless, a view rendering the UI into a texture, validation on): the frame
 * clock, CPU zones of the app's phases and the frame graph, GPU passes on the CPU's clock, counters, freezing and
 * stepping, exports; frames with the profiler on allocate nothing; with it off nothing is recorded.
 */
class UiProfilerTest {

    /** A view rendering the UI into a texture, so frames have passes. */
    private static final class Views implements Plugin {
        static final PluginKey<Views> KEY = PluginKey.of("test.profiler.views", Views.class);

        @Override
        public PluginKey<Views> key() {
            return KEY;
        }

        @Override
        public List<PluginKey<?>> requires() {
            return List.of(Rendering.KEY);
        }

        @Override
        public void start(Stage stage, Startup startup) {
            if (stage == Stage.GRAPH) {
                var graph = startup.get(Rendering.KEY).graph();
                graph.addView("main").size(1280, 720)
                    .output(Keys.UI, graph.texture("profiler.ui", Format.R16G16B16A16_SFLOAT, 1280, 720));
            }
        }
    }

    static UiTextures textures;

    private static Ui ui() {
        NativeTextBackend backend = new NativeTextBackend();
        textures = new UiTextures();
        TextSystem text = new TextSystem(BundledCjkFonts.registry(backend), backend.newShaper(),
            new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
                new AtlasSettings()));
        return new Ui(Tss.defaultTheme(), text, textures);
    }

    static Engine engine(UiPlugin plugin, boolean validation) {
        try {
            return Engine.create(new Application() {
                @Override
                public void configure(AppConfig app) {
                    app.use(new Rendering().headless(true).validation(validation), new Input(), new Views(),
                        new Profiling(), plugin);
                }
            });
        } catch (StartupFailure e) {
            assumeThat(e).as("no Vulkan available: " + e.reason()).isNull();
            throw e;
        }
    }

    @Test
    void theProfilerRecordsFramesZonesGpuPassesAndCounters(@TempDir Path folder) throws Exception {
        UiPlugin plugin = new UiPlugin(t -> ui()).view("main");
        try (Engine engine = engine(plugin, true)) {
            UiProfiler profiler = plugin.profiler();
            assertThat(plugin.profiler()).isSameAs(profiler);
            assertThat(engine.registry().find("profiler.overlay")).isNotNull();
            assertThat(engine.get(Input.KEY).action("profiler.overlay")).isNotNull();
            engine.step();
            assertThat(Profiler.zones()).isFalse();
            assertThat(profiler.recording().lanes()).as("nothing recorded while off").isZero();
            engine.settings().set(ProfilerSettings.OVERLAY, true);
            engine.settings().set(ProfilerSettings.VIEW, true);
            engine.settings().set(ProfilerSettings.PIPELINE_STATISTICS, true);
            engine.settings().set(ProfilerSettings.COUNTER_INTERVAL, 20);
            engine.settings().set(ProfilerSettings.EXPORT_FOLDER, folder.toString());
            for (int i = 0; i < 120; i++) {
                engine.step();
            }
            Thread.sleep(100); // a counters sample or more
            for (int i = 0; i < 10; i++) {
                engine.step();
            }
            assertThat(Profiler.zones()).isTrue();
            Recording r = profiler.recording();
            int main = lane(r, Thread.currentThread().getName(), LaneKind.THREAD);
            assertThat(main).isNotNegative();
            List<String> names = zoneNames(r, main);
            assertThat(names).contains("frame", "BEFORE_UPDATE", "FIXED", "UPDATE", "ANIMATE", "LATE",
                "AFTER_UPDATE", "end frame", "render.record", "render.submit", "wait for frame slot");
            int graphics = lane(r, "GPU graphics", LaneKind.GPU);
            assertThat(graphics).as("GPU passes recorded").isNotNegative();
            assertThat(zoneNames(r, graphics)).contains("GPU frame");
            // GPU work of a frame starts after its submit (on the CPU's clock, calibrated or bounded)
            long f = r.endFrame() - 5;
            assertThat(r.frameGpuStart(f)).isPositive();
            assertThat(r.frameGpuStart(f)).isGreaterThanOrEqualTo(r.frameEnd(f) - 1_000_000);
            assertThat(profiler.times().cpu().size()).isPositive();
            assertThat(profiler.times().gpu().size()).isPositive();
            assertThat(counter(r, "memory.heap")).isPositive();
            assertThat(counter(r, "gpu.memory.render_targets")).isPositive();
            assertThat(GpuMemory.bytes(GpuMemory.Category.RENDER_TARGETS)).isPositive();
            System.out.println("Profiler counters here:");
            for (int c = 0; c < r.counters(); c++) {
                System.out.println("  " + r.counterName(c) + " = " + r.latestValue(c) + " " + r.counterUnit(c));
            }
            for (String missing : engine.get(Profiling.KEY).counters().unavailable()) {
                System.out.println("  unavailable: " + missing);
            }
            System.out.println("  calibrated GPU clock: " + engine.get(Profiling.KEY).clock().calibrated()
                + " (deviation " + engine.get(Profiling.KEY).clock().deviation() + " ns)");
            // freeze: nothing new; a step: one more frame
            profiler.view().frozen().set(true);
            engine.step();
            long frozenAt = r.endFrame();
            int frames = count(r, main, "frame");
            for (int i = 0; i < 5; i++) {
                engine.step();
            }
            assertThat(r.endFrame()).isEqualTo(frozenAt);
            assertThat(count(r, main, "frame")).isEqualTo(frames);
            assertThat(profiler.times().latest()).as("the overlay keeps running").isGreaterThan(frozenAt);
            profiler.view().stepButton().click();
            engine.step();
            engine.step();
            assertThat(r.endFrame()).isGreaterThan(frozenAt);
            assertThat(count(r, main, "frame")).as("one frame taken").isEqualTo(frames + 1);
            profiler.view().frozen().set(false);
            // exports
            String files = profiler.export("tsprof", "json");
            assertThat(files).contains(".tsprof").contains(".json");
            try (var list = Files.list(folder)) {
                assertThat(list.count()).isEqualTo(2);
            }
            // off again: zones stop
            engine.settings().set(ProfilerSettings.VIEW, false);
            engine.settings().set(ProfilerSettings.OVERLAY, false);
            engine.step();
            assertThat(Profiler.zones()).isFalse();
            assertThat(Profiler.frames()).isFalse();
        }
    }

    @Test
    void actionsConsoleCommandsBudgetsAndFreezingOnAHitch(@TempDir Path folder) throws Exception {
        UiPlugin plugin = new UiPlugin(t -> ui()).view("main");
        try (Engine engine = engine(plugin, false)) {
            var console = plugin.console("profiler-test", com.truesim.ui.console.HistoryStore.memory());
            UiProfiler profiler = plugin.profiler();
            assertThat(plugin.profilerViews()).isSameAs(profiler);
            var s = engine.settings();
            s.set(ProfilerSettings.EXPORT_FOLDER, folder.toString());
            var events = engine.get(Input.KEY).events();
            press(engine, events, com.truesim.engine.platform.Key.F3, false);
            assertThat(s.get(ProfilerSettings.OVERLAY)).isTrue();
            press(engine, events, com.truesim.engine.platform.Key.F3, true);
            assertThat(s.get(ProfilerSettings.VIEW)).isTrue();
            for (int i = 0; i < 20; i++) {
                engine.step();
            }
            press(engine, events, com.truesim.engine.platform.Key.F4, false);
            assertThat(profiler.view().frozen().get()).isTrue();
            press(engine, events, com.truesim.engine.platform.Key.F4, true); // step
            press(engine, events, com.truesim.engine.platform.Key.F4, false);
            assertThat(profiler.view().frozen().get()).isFalse();
            s.set(com.truesim.engine.ui.console.SettingPermissions.DEVELOPER, true);
            var shell = console.console().shell();
            assertThat(shell.execute("profiler.freeze on")).isTrue();
            assertThat(profiler.view().frozen().get()).isTrue();
            shell.execute("profiler.freeze");
            assertThat(profiler.view().frozen().get()).isFalse();
            shell.execute("profiler.step");
            shell.execute("profiler.counters");
            shell.execute("profiler.export tsprof");
            shell.execute("profiler.export chrome");
            assertThat(profiler.lastExport()).endsWith(".json");
            shell.execute("profiler.clear");
            assertThat(profiler.times().latest()).isEqualTo(-1);
            assertThat(profiler.exportFolder()).isEqualTo(folder);
            // budgets: a list with the display's rate (none headless) and a bad entry
            s.set(ProfilerSettings.BUDGETS, "30, display, x, 30, 240");
            engine.step();
            assertThat(profiler.frameGraph().budgetCount()).isEqualTo(2);
            // freezing a few frames after a hitch
            s.set(ProfilerSettings.FREEZE_ON_HITCH, true);
            s.set(ProfilerSettings.FREEZE_AFTER, 2);
            s.set(ProfilerSettings.HITCH_MIN, 1f);
            for (int i = 0; i < 30; i++) {
                engine.step();
            }
            Thread.sleep(40);
            for (int i = 0; i < 10 && !profiler.view().frozen().get(); i++) {
                engine.step();
            }
            assertThat(profiler.view().frozen().get()).as("frozen after the hitch").isTrue();
            press(engine, events, com.truesim.engine.platform.Key.F4, UiActions.SHORTCUT == com.truesim.engine.input.Controls.CONTROL ? "ctrl" : "meta");
            assertThat(profiler.lastExport()).contains(".tsprof");
            assertThat(profiler.recording()).isNotNull();
        }
    }

    private static void press(Engine engine, com.truesim.engine.platform.PlatformListener events,
        com.truesim.engine.platform.Key key, boolean shift) {
        if (shift) {
            events.key(0, 1, 0, com.truesim.engine.platform.Key.LEFT_SHIFT, 0, 0, true, false);
        }
        events.key(0, 1, 0, key, 0, 0, true, false);
        engine.step();
        events.key(0, 1, 0, key, 0, 0, false, false);
        if (shift) {
            events.key(0, 1, 0, com.truesim.engine.platform.Key.LEFT_SHIFT, 0, 0, false, false);
        }
        engine.step();
    }

    private static void press(Engine engine, com.truesim.engine.platform.PlatformListener events,
        com.truesim.engine.platform.Key key, String modifier) {
        com.truesim.engine.platform.Key mod = modifier.equals("ctrl") ? com.truesim.engine.platform.Key.LEFT_CONTROL
            : com.truesim.engine.platform.Key.LEFT_META;
        events.key(0, 1, 0, mod, 0, 0, true, false);
        events.key(0, 1, 0, key, 0, 0, true, false);
        engine.step();
        events.key(0, 1, 0, key, 0, 0, false, false);
        events.key(0, 1, 0, mod, 0, 0, false, false);
        engine.step();
    }

    @Test
    void framesWithTheProfilerOnAllocateNothing() {
        int off = allocatingFrames(false, false);
        int overlay = allocatingFrames(true, false);
        int detailed = allocatingFrames(true, true);
        System.out.println("frames allocating of 1500: off " + off + ", overlay " + overlay + ", detailed " + detailed);
        // pitfalls.md: count the frames that allocate; one in a few hundred is the compiler
        assertThat(overlay).as("overlay: frames allocating of 1500").isLessThanOrEqualTo(Math.max(6, off + 3));
        assertThat(detailed).as("detailed: frames allocating of 1500").isLessThanOrEqualTo(Math.max(6, off + 3));
    }

    private static int allocatingFrames(boolean overlay, boolean detailed) {
        UiPlugin plugin = new UiPlugin(t -> ui()).view("main");
        try (Engine engine = engine(plugin, false)) {
            plugin.profiler();
            engine.settings().set(ProfilerSettings.OVERLAY, overlay);
            engine.settings().set(ProfilerSettings.VIEW, detailed);
            engine.settings().set(ProfilerSettings.COUNTER_INTERVAL, 50);
            engine.settings().set(ProfilerSettings.HISTORY, 0.5f); // the rings reach their size while warming
            for (int i = 0; i < 2000; i++) {
                engine.step(); // rings, text and the JIT settle
            }
            com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            int allocating = 0;
            for (int i = 0; i < 1500; i++) {
                long before = mx.getCurrentThreadAllocatedBytes();
                engine.step();
                long bytes = mx.getCurrentThreadAllocatedBytes() - before;
                if (bytes != 0) {
                    allocating++;
                }
            }
            return allocating;
        }
    }

    private static int lane(Recording r, String name, LaneKind kind) {
        for (int l = 0; l < r.lanes(); l++) {
            if (r.laneName(l).equals(name) && r.laneKind(l) == kind) {
                return l;
            }
        }
        return -1;
    }

    private static int count(Recording r, int lane, String name) {
        int n = 0;
        for (long z = r.firstZone(lane); z < r.endZone(lane); z++) {
            n += r.nameOf(r.zoneName(lane, z)).equals(name) ? 1 : 0;
        }
        return n;
    }

    private static List<String> zoneNames(Recording r, int lane) {
        List<String> names = new ArrayList<>();
        for (long z = r.firstZone(lane); z < r.endZone(lane); z++) {
            String n = r.nameOf(r.zoneName(lane, z));
            if (!names.contains(n)) {
                names.add(n);
            }
        }
        return names;
    }

    private static double counter(Recording r, String name) {
        for (int c = 0; c < r.counters(); c++) {
            if (r.counterName(c).equals(name)) {
                return r.latestValue(c);
            }
        }
        return Double.NaN;
    }
}
