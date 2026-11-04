package com.truesim.engine.bench;

import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.app.profile.Profiler;
import com.truesim.engine.app.profile.ProfilerSettings;
import com.truesim.engine.app.profile.Zone;
import com.truesim.engine.graph.Profiling;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.input.Input;
import com.truesim.engine.render.Keys;
import com.truesim.engine.ui.UiPlugin;
import com.truesim.engine.vulkan.Format;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.ControlsGallery;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.profiler.FrameTimes;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * The profiler's overhead: a CPU zone's begin and end off and on, the frame graph's data path (the frame clock and the
 * overlay's rings) per frame, and whole engine frames (headless, the controls gallery as an idle UI drawn into a
 * texture, frames back to back as the engine runs them) with the profiler off, the overlay on, and the detailed
 * profiler on. Writes {@code docs/benchmarks-profiler.md}:
 * {@code ./gradlew :engine-bench:profilerBench [-Pquick=true]}. Run it without JaCoCo (it is a program).
 */
public final class ProfilerBench {

    private static final Zone ZONE = Profiler.zone("bench.zone");

    private ProfilerBench() {
    }

    /** Runs the suite; {@code args[0]}: the report's path. */
    public static void main(String[] args) throws IOException {
        boolean quick = Boolean.getBoolean("truesim.bench.quick");
        StringBuilder out = new StringBuilder();
        out.append("# Profiler overhead\n\n");
        out.append("`./gradlew :engine-bench:profilerBench`, ").append(LocalDate.now()).append(", ")
            .append(System.getProperty("os.name")).append(", ").append(Runtime.getRuntime().availableProcessors())
            .append(" hardware threads, no JaCoCo. Times of the main thread, frames back to back.\n\n");
        zones(out, quick ? 2_000_000 : 20_000_000);
        dataPath(out, quick ? 200_000 : 2_000_000);
        overlayView(out, quick ? 500 : 3000);
        frames(out, quick ? 500 : 3000, quick ? 500 : 2000);
        String report = out.toString();
        System.out.println(report);
        if (args.length > 0) {
            Files.writeString(Path.of(args[0]), report);
        }
        System.exit(0);
    }

    private static volatile long sink;

    /** Nanoseconds per begin/end pair off and on, against an empty loop. */
    private static void zones(StringBuilder out, int n) {
        Profiler.zones(false);
        double empty = Double.MAX_VALUE;
        double off = Double.MAX_VALUE;
        double on = Double.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            long t0 = System.nanoTime();
            long x = 0;
            for (int i = 0; i < n; i++) {
                x += i;
            }
            sink = x;
            empty = Math.min(empty, (System.nanoTime() - t0) / (double) n);
            Profiler.zones(false);
            t0 = System.nanoTime();
            x = 0;
            for (int i = 0; i < n; i++) {
                Profiler.begin(ZONE);
                x += i;
                Profiler.end(ZONE);
            }
            sink = x;
            off = Math.min(off, (System.nanoTime() - t0) / (double) n);
            Profiler.zones(true);
            t0 = System.nanoTime();
            x = 0;
            for (int i = 0; i < n; i++) {
                Profiler.begin(ZONE);
                x += i;
                Profiler.end(ZONE);
                if ((i & 4095) == 0) {
                    Profiler.discard(); // as a drain would, so the ring never overflows
                }
            }
            sink = x;
            on = Math.min(on, (System.nanoTime() - t0) / (double) n);
            Profiler.zones(false);
            Profiler.discard();
        }
        out.append("## A CPU zone (begin and end)\n\n| | ns per zone |\n|---|---|\n");
        out.append("| empty loop | ").append(f(empty, 2)).append(" |\n");
        out.append("| profiler off | ").append(f(off, 2)).append(" (").append(f(off - empty, 2))
            .append(" over the loop) |\n");
        out.append("| profiler on | ").append(f(on, 2)).append(" (").append(f(on - empty, 2))
            .append(" over the loop: two `nanoTime` calls and two ring writes) |\n\n");
    }

    /** The frame clock and the overlay's rings, per frame. */
    private static void dataPath(StringBuilder out, int frames) {
        FrameTimes times = new FrameTimes(new com.truesim.common.state.ChangeClock(), 300);
        Profiler.frames(true);
        double best = Double.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < frames; i++) {
                Profiler.frameStart(System.nanoTime());
                Profiler.waitBegin();
                Profiler.waitEnd();
                Profiler.waitBegin();
                Profiler.waitEnd();
                Profiler.submitted(i);
                Profiler.frameEnd();
                long n = Profiler.lastFrame();
                times.frame(n, (Profiler.lastFrameSubmit() - Profiler.lastFrameStart()) / 1e6,
                    Profiler.lastFrameWait() / 1e6, Profiler.lastFrameInterval() / 1e6, false);
                times.gpu(n, 1.0);
            }
            best = Math.min(best, (System.nanoTime() - t0) / (double) frames);
        }
        Profiler.frames(false);
        out.append("## The frame graph's data path\n\nThe frame clock (start, two waits, submit, end) and the overlay's "
            + "rings (a CPU frame and a GPU frame appended): **").append(f(best / 1000, 3))
            .append(" us per frame** (budget 10 us).\n\n");
    }

    /** The frame graph view's own cost: a UI with it against the same UI without it, a frame appended each. */
    private static void overlayView(StringBuilder out, int frames) {
        double[] p50 = new double[2];
        for (int withGraph = 0; withGraph < 2; withGraph++) {
            Ui ui = ui();
            FrameTimes times = new FrameTimes(ui.store().clock(), 300);
            if (withGraph == 1) {
                com.truesim.ui.profiler.FrameGraphView graph = new com.truesim.ui.profiler.FrameGraphView(ui, times)
                    .budgets(60, 120, 144);
                ui.layerStack().add("profiler", 550).root().add(graph.element());
            }
            com.truesim.ui.core.input.UiInput input = new com.truesim.ui.core.input.UiInput(4);
            long now = 1_000_000_000L;
            long[] t = new long[frames];
            for (int i = 0; i < frames * 2; i++) {
                long t0 = System.nanoTime();
                times.frame(i, 4 + (i % 7) * 0.3, 0.2, 16.6, i % 500 == 0);
                times.gpu(i, 3 + (i % 5) * 0.2);
                input.beginFrame();
                ui.update(1920, 1080, input, now += 16_600_000L);
                if (i >= frames) {
                    t[i - frames] = System.nanoTime() - t0;
                }
            }
            Arrays.sort(t);
            p50[withGraph] = t[frames / 2] / 1e6;
        }
        out.append("## The frame graph view\n\nA UI (the controls gallery, idle) updated with a frame appended each "
            + "time, with and without the frame graph (statistics every 0.5 s): ").append(f(p50[0], 4))
            .append(" ms against ").append(f(p50[1], 4)).append(" ms p50, so the view costs **")
            .append(f((p50[1] - p50[0]) * 1000, 1)).append(" us a frame** on the CPU (its lines appended to and "
            + "scrolled by an offset, the rest of the chart recording nothing).\n\n");
    }

    /** A view drawing the UI into a texture, so frames have passes. */
    private static final class Views implements Plugin {
        static final PluginKey<Views> KEY = PluginKey.of("bench.profiler.views", Views.class);

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
                graph.addView("main").size(1920, 1080)
                    .output(Keys.UI, graph.texture("bench.ui", Format.R16G16B16A16_SFLOAT, 1920, 1080));
            }
        }
    }

    private static Ui ui() {
        NativeTextBackend backend = new NativeTextBackend();
        UiTextures textures = new UiTextures();
        TextSystem text = new TextSystem(BundledCjkFonts.registry(backend), backend.newShaper(),
            new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
                new AtlasSettings()));
        Ui ui = new Ui(Tss.defaultTheme(), text, textures);
        ControlsGallery.build(new com.truesim.ui.controls.basic.Controls(ui), ui.root());
        return ui;
    }

    private static void frames(StringBuilder out, int measured, int warm) {
        out.append("## Engine frames\n\nHeadless, the controls gallery idle at 1920 x 1080 drawn into a texture; "
            + "CPU time of `engine.step()`, ").append(measured).append(" frames after ").append(warm)
            .append(" warm ones; GPU from the frame graph's timestamps (only measured with the profiler on).\n\n")
            .append("| Profiler | CPU mean | CPU p50 | CPU p99 | over off (p50) | GPU p50 |\n|---|---|---|---|---|---|\n");
        double offP50 = Double.NaN;
        String[] modes = {"off", "overlay, no GPU timings", "overlay", "detailed (zones, GPU, counters)",
            "detailed + OS + JFR + statistics"};
        for (int mode = 0; mode < modes.length; mode++) {
            UiPlugin plugin = new UiPlugin(t -> ui()).view("main");
            try (Engine engine = Engine.create(new Application() {
                @Override
                public void configure(AppConfig app) {
                    app.id("profiler-bench").use(new Rendering().headless(true), new Input(), new Views(),
                        new Profiling(), plugin);
                }
            })) {
                plugin.profiler();
                var s = engine.settings();
                s.set(ProfilerSettings.OVERLAY, mode >= 1);
                s.set(ProfilerSettings.GPU, mode != 1);
                s.set(ProfilerSettings.VIEW, mode >= 3);
                s.set(ProfilerSettings.OS, mode >= 4);
                s.set(ProfilerSettings.RUNTIME, mode >= 4);
                s.set(ProfilerSettings.PIPELINE_STATISTICS, mode >= 4);
                s.set(ProfilerSettings.HISTORY, 2f);
                for (int i = 0; i < warm; i++) {
                    engine.step();
                }
                long[] cpu = new long[measured];
                double[] gpu = new double[measured];
                for (int i = 0; i < measured; i++) {
                    long t0 = System.nanoTime();
                    engine.step();
                    cpu[i] = System.nanoTime() - t0;
                    Profiling p = engine.get(Profiling.KEY);
                    gpu[i] = p.gpuFrame() >= 0 ? (p.gpuEnd() - p.gpuStart()) / 1e6 : Double.NaN;
                }
                Arrays.sort(cpu);
                double mean = Arrays.stream(cpu).average().orElse(0) / 1e6;
                double p50 = cpu[measured / 2] / 1e6;
                double p99 = cpu[(int) (measured * 0.99)] / 1e6;
                double[] g = Arrays.stream(gpu).filter(v -> v == v).sorted().toArray();
                if (mode == 0) {
                    offP50 = p50;
                }
                out.append("| ").append(modes[mode]).append(" | ").append(f(mean, 3)).append(" ms | ")
                    .append(f(p50, 3)).append(" ms | ").append(f(p99, 3)).append(" ms | ")
                    .append(mode == 0 ? "-" : "+" + f(p50 - offP50, 3) + " ms").append(" | ")
                    .append(g.length == 0 ? "-" : f(g[g.length / 2], 3) + " ms").append(" |\n");
            }
        }
        out.append('\n');
    }

    private static String f(double v, int decimals) {
        return String.format(java.util.Locale.ROOT, "%." + decimals + "f", v);
    }
}
