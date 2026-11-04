package com.truesim.engine.bench;

import com.truesim.common.math.Maths;
import com.truesim.common.state.VersionedDoubleRing;
import com.truesim.engine.ui.GpuUiRenderer;
import com.truesim.ui.charts.Chart;
import com.truesim.ui.charts.Columns;
import com.truesim.ui.charts.LineSeries;
import com.truesim.ui.console.ConsoleGallery;
import com.truesim.ui.console.LogRing;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.basic.ControlsGallery;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * The UI's benchmark suite (ui's design, section 11), as one program: the renderer on the GPU at 4K (a HUD, 100,000
 * glyphs alone and over a backdrop, a streaming chart's lines and filled areas), its submits on the CPU, and the CPU
 * scenes reachable from ui's fixtures and public API (an idle controls gallery, a chart streaming 1,000,000 samples,
 * 10,000,000 samples zoomed and panned, a console flooded with 1,000 lines a frame). Each case warms up, then times
 * every frame; the table gives the median (p50) and p99 against the section's budget, with the machine and the date,
 * written as markdown to the path given (default {@code docs/benchmarks-ui.md}).
 *
 * <p>{@code ./gradlew :engine-bench:uiBench} (no JaCoCo, no validation). {@code -Pquick=true} runs fewer frames, to try
 * the suite; numbers for the docs come from a full run on a quiet machine.
 */
public final class UiBench {

    /** One measured case. */
    record Result(String target, String budget, String measured, double p50, double p99, double limit, String note) {
    }

    private static final boolean QUICK = Boolean.getBoolean("truesim.bench.quick");
    private static final int GPU_FRAMES = QUICK ? 60 : 600;
    private static final int GPU_WARM = QUICK ? 30 : 300;
    private static final int CPU_FRAMES = QUICK ? 300 : 3000;
    private static final int CPU_WARM = QUICK ? 200 : 2000;

    private final List<Result> results = new ArrayList<>();
    private String gpu = "no Vulkan 1.3 GPU found: GPU cases skipped";
    private long nanos = 1_000_000_000L;

    private UiBench() {
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "docs/benchmarks-ui.md");
        UiBench bench = new UiBench();
        bench.renderer();
        bench.charts();
        bench.idleGallery();
        bench.console();
        String table = bench.markdown();
        System.out.println(table);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, table);
        System.out.println("written to " + out.toAbsolutePath());
    }

    // ---------------------------------------------------------------- statistics

    private static double percentile(double[] values, double p) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * p))];
    }

    private void add(String target, String budget, String measured, double[] ms, double limit, String note) {
        double p50 = percentile(ms, 0.5);
        double p99 = percentile(ms, 0.99);
        results.add(new Result(target, budget, measured, p50, p99, limit, note));
        System.out.printf(Locale.ROOT, "%s, %s: p50 %.4f ms, p99 %.4f ms%n", target, measured, p50, p99);
    }

    /** GPU milliseconds of {@code frames} frames after {@code warm} (frames without timings left out). */
    private static double[] gpuFrames(GpuBench b, int warm, int frames) {
        for (int i = 0; i < warm; i++) {
            b.frame();
        }
        double[] ms = new double[frames];
        int n = 0;
        while (n < frames) {
            double t = b.frame();
            if (!Double.isNaN(t)) {
                ms[n++] = t;
            }
        }
        return ms;
    }

    // ---------------------------------------------------------------- the renderer: HUD and glyph floods

    private void renderer() {
        try (GpuBench b = GpuBench.open(UiScenes.W, UiScenes.H)) {
            if (b == null) {
                return;
            }
            gpu = b.gpuName();
            UiTextures textures = new UiTextures();
            UiTexture atlas = UiScenes.atlas(textures);
            UiTexture image = UiScenes.photo(textures);
            UiTexture skin = UiScenes.skin(textures);
            DrawList hud = UiScenes.hud(atlas, image, skin);
            DrawList[] layers = {hud};
            b.renderer.batching(GpuUiRenderer.Batching.ADAPTIVE);
            b.renderer.submit(layers, 1, textures, UiScenes.W, UiScenes.H);
            add("Full HUD (500 elements, 5,000 glyphs): GPU at 4K", "≤ 0.15 ms",
                "UI pass + uploads, adaptive batching", gpuFrames(b, GPU_WARM, GPU_FRAMES), 0.15, "");
            // CPU: submitting it unchanged (version checks, no upload) and with a tenth re-recorded.
            double[] unchanged = new double[CPU_FRAMES * 10];
            for (int i = -CPU_WARM * 10; i < unchanged.length; i++) {
                long t0 = System.nanoTime();
                b.renderer.submit(layers, 1, textures, UiScenes.W, UiScenes.H);
                long t = System.nanoTime() - t0;
                if (i >= 0) {
                    unchanged[i] = t / 1e6;
                }
            }
            add("Full HUD with nothing changed: CPU", "≤ 0.05 ms", "the renderer's submit (draw lists given)",
                unchanged, 0.05, "the UI's own idle frame: the controls gallery below");
            double[] changing = new double[CPU_FRAMES];
            for (int i = -CPU_WARM; i < changing.length; i++) {
                long color = Rgba.pack(1, i & 1, 0, 1);
                long t0 = System.nanoTime();
                for (int e = 0; e < 500; e += 10) {
                    UiScenes.element(hud, e, atlas, image, skin, color);
                }
                b.renderer.submit(layers, 1, textures, UiScenes.W, UiScenes.H);
                long t = System.nanoTime() - t0;
                if (i >= 0) {
                    changing[i] = t / 1e6;
                }
            }
            add("Full HUD with 10% of elements changing: CPU", "≤ 0.3 ms",
                "50 elements re-recorded into the list, submitted", changing, 0.3,
                "recording only; the UI's styles and layout of a changing HUD are ui's HudBenchmarkTest");
            DrawList flood = UiScenes.flood(atlas, false);
            b.renderer.submit(new DrawList[] {flood}, 1, textures, UiScenes.W, UiScenes.H);
            add("100,000 glyphs on screen: GPU", "≤ 0.4 ms", "4K, 500 x 200 glyphs", gpuFrames(b, GPU_WARM, GPU_FRAMES),
                0.4, "");
            DrawList backdrop = UiScenes.flood(atlas, true);
            b.renderer.submit(new DrawList[] {backdrop}, 1, textures, UiScenes.W, UiScenes.H);
            add("100,000 glyphs on screen: GPU", "≤ 0.4 ms", "over a full-screen translucent backdrop",
                gpuFrames(b, GPU_WARM, GPU_FRAMES), 0.4, "");
        }
    }

    // ---------------------------------------------------------------- charts

    /** A UI over the real text path (bundled fonts), its atlas in {@code textures}. */
    private static Ui ui(UiTextures textures) {
        NativeTextBackend backend = new NativeTextBackend();
        FontRegistry fonts = BundledCjkFonts.registry(backend);
        GlyphAtlas atlas = new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
            new AtlasSettings());
        return new Ui(Tss.defaultTheme(), new TextSystem(fonts, backend.newShaper(), atlas), textures);
    }

    private long frame(Ui ui, UiInput input, int width, int height) {
        input.beginFrame();
        long t0 = System.nanoTime();
        ui.update(width, height, input, nanos += 16_000_000L);
        return System.nanoTime() - t0;
    }

    private void settle(Ui ui, UiInput input, int width, int height) {
        int quiet = 0;
        for (int i = 0; i < 400 && quiet < 3; i++) {
            frame(ui, input, width, height);
            boolean glyphs = ui.textSystem() == null || ui.textSystem().atlas().pending() == 0;
            quiet = glyphs && ui.recorded() == 0 ? quiet + 1 : 0;
        }
    }

    private static Chart chart(Ui ui, String name, int width, int height) {
        Chart chart = new Chart(ui, name);
        ui.root().add(chart.element());
        ui.styles().inline(chart.element().slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(width))
            .set(Styles.HEIGHT, Length.px(height)).build());
        return chart;
    }

    private void charts() {
        int width = UiScenes.W;
        int height = 1000;
        for (boolean area : new boolean[] {false, true}) {
            UiTextures textures = new UiTextures();
            Ui ui = ui(textures);
            VersionedDoubleRing ring = ui.store().clock().doubleRing(1_000_000);
            Chart chart = chart(ui, "stream", width, height);
            LineSeries line = chart.line("signal", Columns.index(0, 0.001), Columns.of(ring));
            if (area) {
                line.fill(0);
            }
            chart.x().range(0, 1000).fits(false).follow(true); // the whole ring across the plot
            SplittableRandom random = new SplittableRandom(1);
            double[] fill = new double[1_000_000];
            for (int i = 0; i < fill.length; i++) {
                fill[i] = Maths.sin(i * 0.0007) + random.nextGaussian() * 0.1;
            }
            ring.append(fill, 0, fill.length);
            UiInput input = new UiInput(4);
            settle(ui, input, width, height);
            double[] chunk = new double[1000];
            int frames = QUICK ? 200 : 1500;
            int warm = QUICK ? 100 : 500;
            // The CPU's frames back to back, as the engine runs them while the GPU draws the frames before (frames in
            // flight): a GPU frame waited for between them (the GPU run below) puts the CPU to sleep, which added up to
            // 0.2 ms to its p99 on this machine.
            double[] cpu = new double[frames];
            for (int f = -warm * 4; f < frames; f++) {
                stream(ring, chunk, random);
                long t = frame(ui, input, width, height);
                if (f >= 0) {
                    cpu[f] = t / 1e6;
                }
            }
            String what = (area ? "a filled area" : "a line") + ", "
                + (int) (chart.plotRight() - chart.plotLeft()) + " pixel columns";
            add("Line chart: 1,000,000 points streaming 1,000 a frame, 4K: CPU", "≤ 0.5 ms",
                what + "; the whole UI frame, frames back to back", cpu, 0.5, "");
            double[] gpuMs = new double[frames];
            double[] starts = new double[frames];
            long uploaded = 0;
            try (GpuBench b = GpuBench.open(width, height)) {
                for (int f = -warm; f < frames && b != null; f++) {
                    stream(ring, chunk, random);
                    frame(ui, input, width, height);
                    b.renderer.submit(ui.layers(), ui.layerCount(), ui.textures(), width, height);
                    double g = b.frame();
                    if (f >= 0) {
                        gpuMs[f] = g;
                        starts[f] = b.lastStart;
                        uploaded += b.renderer.uploadedBytes();
                    }
                }
                if (b != null) {
                    double[] valid = Arrays.stream(gpuMs).filter(v -> !Double.isNaN(v)).toArray();
                    if (valid.length > 0) {
                        System.out.printf(Locale.ROOT, "  of which the frame start (uploads) p50 %.4f ms, %d bytes a "
                            + "frame, %d primitives%n", percentile(starts, 0.5), uploaded / frames, ui.layers()[0].size());
                        add("Line chart: 1,000,000 points streaming 1,000 a frame, 4K: GPU", "≤ 0.2 ms",
                            what + "; UI pass + uploads", valid, 0.2, "");
                    }
                }
            }
        }
        zooming(width, height);
    }

    /** Appends 1,000 samples of the streaming chart's signal. */
    private static void stream(VersionedDoubleRing ring, double[] chunk, SplittableRandom random) {
        long base = ring.appended();
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = Maths.sin((base + i) * 0.0007) + random.nextGaussian() * 0.1;
        }
        ring.append(chunk, 0, chunk.length);
    }

    private void zooming(int width, int height) {
        int n = 10_000_000;
        double[] ys = new double[n];
        SplittableRandom random = new SplittableRandom(2);
        for (int i = 0; i < n; i++) {
            ys[i] = Maths.sin(i * 1e-5) + random.nextGaussian() * 0.2;
        }
        UiTextures textures = new UiTextures();
        Ui ui = ui(textures);
        Chart chart = chart(ui, "big", width, height);
        chart.line("ten million", Columns.index(0, 1), Columns.of(ys));
        UiInput input = new UiInput(4);
        settle(ui, input, width, height);
        int frames = QUICK ? 300 : 1500;
        int warm = QUICK ? 100 : 300;
        double[] ms = new double[frames];
        for (int f = -warm; f < frames; f++) {
            double phase = (f + warm) * 0.01; // zooming in and out over the whole range, panning as it goes
            double span = n * (0.02 + 0.98 * (0.5 + 0.5 * Maths.cos(phase)));
            double centre = n * (0.5 + 0.45 * Maths.sin(phase * 0.7));
            chart.x().range(centre - span / 2, centre + span / 2);
            long t = frame(ui, input, width, height);
            if (f >= 0) {
                ms[f] = t / 1e6;
            }
        }
        add("Line chart: 10,000,000 points, zoom and pan at frame rate: CPU", "≤ 1 ms",
            "evenly spaced X, pyramid; the whole UI frame", ms, 1, "");
    }

    // ---------------------------------------------------------------- CPU scenes of ui's fixtures

    private void idleGallery() {
        Ui ui = ui(new UiTextures());
        ControlsGallery.registerIcons(ui);
        ControlsGallery.build(new Controls(ui), ui.root());
        UiInput input = new UiInput(4);
        for (int i = 0; i < 100; i++) {
            frame(ui, input, 1280, 800);
        }
        settle(ui, input, 1280, 800);
        double[] ms = new double[CPU_FRAMES];
        for (int f = -CPU_WARM; f < ms.length; f++) {
            long t = frame(ui, input, 1280, 800);
            if (f >= 0) {
                ms[f] = t / 1e6;
            }
        }
        add("Full HUD with nothing changed: CPU", "≤ 0.05 ms",
            "the controls gallery (about 970 elements) idle: the whole UI frame", ms, 0.05, "");
    }

    private void console() {
        Ui ui = ui(new UiTextures());
        ConsoleGallery g = ConsoleGallery.build(ui, 1920, 1080);
        LogRing log = g.console.log();
        String[] messages = new String[64];
        for (int i = 0; i < messages.length; i++) {
            messages[i] = "Frame graph pass " + i + " recorded 1,204 draws into 3 render targets in 0.42 ms";
        }
        String[] loggers = {"com.truesim.render.Graph", "com.truesim.game.World", "com.truesim.net.Session"};
        int frames = QUICK ? 100 : 1000;
        int warm = QUICK ? 100 : 2000; // past the compiler's thresholds of what runs once a frame
        double[] ms = new double[frames];
        int s = 0;
        for (int f = -warm; f < frames; f++) {
            for (int k = 0; k < 1000; k++) {
                int n = s++;
                log.log(n % 5, loggers[n % 3], messages[n % messages.length], n % 4 == 0 ? "Graph.java:120" : null, n);
            }
            long t0 = System.nanoTime();
            g.frames(1);
            long t = System.nanoTime() - t0;
            if (f >= 0) {
                ms[f] = t / 1e6;
            }
        }
        add("Console flood (a changing UI): CPU", "≤ 0.3 ms",
            "1,000 lines a frame into the open console, 1920 x 1080: the whole UI frame", ms, 0.3,
            "lines logged before the frame; their appending is not in it");
    }

    // ---------------------------------------------------------------- the report

    private static String cpuName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.startsWith("windows")) {
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "(Get-CimInstance Win32_Processor).Name").redirectErrorStream(true).start();
                try (InputStream in = p.getInputStream()) {
                    String name = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (!name.isEmpty()) {
                        return name;
                    }
                }
            } else if (Files.isReadable(Path.of("/proc/cpuinfo"))) {
                for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                    if (line.startsWith("model name")) {
                        return line.substring(line.indexOf(':') + 1).trim();
                    }
                }
            }
        } catch (IOException e) {
            // fall through to the environment
        }
        return System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown CPU");
    }

    private String markdown() {
        StringBuilder md = new StringBuilder();
        md.append("# UI benchmarks\n\n");
        md.append("The UI's targets of ui's design, section 11, measured by `engine-bench` "
            + "(`./gradlew :engine-bench:uiBench`): GPU times are the UI pass plus the frame start (uploads) from the "
            + "graph's timestamps, at 3840 x 2160 unless noted; CPU times the code named, per frame, after warm-up, "
            + "frames back to back (no GPU frame waited for between them). "
            + "No JaCoCo, no validation.\n\n");
        md.append(String.format(Locale.ROOT, "- Date: %s\n- CPU: %s (%d threads)\n- GPU: %s\n- OS: %s %s; Java %s\n",
            LocalDate.now(), cpuName(), Runtime.getRuntime().availableProcessors(), gpu,
            System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("java.version")));
        md.append(QUICK ? "- A quick run (-Pquick=true): fewer frames, not for comparison.\n" : "");
        md.append("\n| Target | Budget | Measured | p50 | p99 | Within |\n|---|---|---|---|---|---|\n");
        for (Result r : results) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %s%s | %.4f ms | %.4f ms | %s |\n", r.target(),
                r.budget(), r.measured(), r.note().isEmpty() ? "" : " (" + r.note() + ")", r.p50(), r.p99(),
                r.p99() <= r.limit() ? "yes" : r.p50() <= r.limit() ? "p50 only" : "no"));
        }
        md.append("\nNot measured here (no public scene to reach from the engine): lists and tables scrolling, "
            + "picking, style reload and text reshaping run as ui's own benchmark tests "
            + "(`TRUESIM_BENCHMARK=true`, see ui's docs/ui.md).\n");
        return md.toString();
    }
}
