package com.truesim.engine.ui;

import com.truesim.engine.ui.testing.UiScenes;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.render.Keys;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The UI renderer's GPU and CPU costs at 4K (design section 11): a static HUD of 500 elements and 5,000 glyphs, a flood
 * of 100,000 glyphs, each drawn with both batchings (UI15: one draw per layer, or a draw per run of a kind), and the CPU
 * cost of submitting an unchanged frame. GPU time is the UI pass's plus the frame start's (uploads), from the graph's
 * timings; configurations take turns round after round, after warm-up rounds, and every round's median is printed
 * (pitfalls.md). The scenes are engine-ui's test fixtures' ({@link UiScenes}), whose suite (the samples'
 * {@code :bench:uiBench}) records section 11's numbers; this compares the batchings. Run without validation and without JaCoCo:
 * {@code TRUESIM_BENCHMARK=true ./gradlew :engine-ui:test --tests '*UiBenchmark'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class UiBenchmark {

    private static final int W = UiScenes.W;
    private static final int H = UiScenes.H;
    private static final int WARM_ROUNDS = 6;
    private static final int ROUNDS = 5;
    private static final int FRAMES = 60;

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    /** The median GPU time of the UI (pass and frame start) over {@link #FRAMES} frames, ms. */
    private static double gpuMillis(GpuRig rig) {
        double[] ms = new double[FRAMES];
        for (int i = -4; i < FRAMES; i++) {
            rig.device.submitAndWait(rig.device.graphics(), rig.graph::record);
            GpuTimings timings = rig.graph.timings();
            if (i < 0 || timings == null) {
                continue;
            }
            double start = timings.frameStartMillis();
            double sum = Double.isNaN(start) ? 0 : start; // NaN: nothing recorded at the frame start
            for (int t = 0; t < timings.count(); t++) {
                if (timings.pass(t).equals("ui") && !Double.isNaN(timings.millis(t))) {
                    sum += timings.millis(t);
                }
            }
            ms[i] = sum;
        }
        return median(ms);
    }

    private static void compare(String name, DrawList list, UiTextures textures) {
        try (GpuRig rig = GpuRig.open(W, H, false).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            DrawList[] layers = {list};
            ui.submit(layers, 1, textures, W, H);
            rig.graph.timings(true);
            GpuUiRenderer.Batching[] modes = GpuUiRenderer.Batching.values();
            double[][] rounds = new double[modes.length][ROUNDS];
            int[] draws = new int[modes.length];
            for (int round = -WARM_ROUNDS; round < ROUNDS; round++) {
                StringBuilder line = new StringBuilder(name + (round < 0 ? " warm-up" : " round " + round) + ":");
                for (int m = 0; m < modes.length; m++) {
                    ui.batching(modes[m]);
                    ui.submit(layers, 1, textures, W, H);
                    draws[m] = ui.draws();
                    double ms = gpuMillis(rig);
                    if (round >= 0) {
                        rounds[m][round] = ms;
                    }
                    line.append(String.format("  %s %.4f ms", modes[m], ms));
                }
                System.out.println(line);
            }
            for (int m = 0; m < modes.length; m++) {
                double[] r = rounds[m];
                System.out.printf("%s %s: GPU %.4f ms (rounds %.4f to %.4f), %d draws, %d primitives%n", name, modes[m],
                    median(r), Arrays.stream(r).min().orElse(0), Arrays.stream(r).max().orElse(0), draws[m],
                    list.size());
            }
            rig.graph.timings(false);
        }
    }

    @Test
    void hudAt4k() {
        UiTextures textures = new UiTextures();
        UiTexture atlas = UiScenes.atlas(textures);
        DrawList hud = UiScenes.hud(atlas, UiScenes.photo(textures), UiScenes.skin(textures));
        compare("HUD 500 elements + 5,000 glyphs, 4K", hud, textures);
    }

    @Test
    void hundredThousandGlyphsAt4k() {
        UiTextures textures = new UiTextures();
        UiTexture atlas = UiScenes.atlas(textures);
        compare("100,000 glyphs over a backdrop, 4K", UiScenes.flood(atlas, true), textures);
        compare("100,000 glyphs, no backdrop, 4K", UiScenes.flood(atlas, false), textures);
    }

    @Test
    void submittingAnUnchangedHudCostsNothing() {
        UiTextures textures = new UiTextures();
        UiTexture atlas = UiScenes.atlas(textures);
        UiTexture image = UiScenes.photo(textures);
        UiTexture skin = UiScenes.skin(textures);
        DrawList hud = UiScenes.hud(atlas, image, skin);
        DrawList[] layers = {hud};
        try (GpuRig rig = GpuRig.open(W, H, false).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            ui.submit(layers, 1, textures, W, H);
            rig.frame();
            for (int round = -3; round < ROUNDS; round++) {
                long[] ns = new long[20_000];
                long bytes = 0;
                for (int i = 0; i < ns.length; i++) {
                    long t0 = System.nanoTime();
                    ui.submit(layers, 1, textures, W, H);
                    ns[i] = System.nanoTime() - t0;
                    bytes += ui.uploadedBytes();
                }
                Arrays.sort(ns);
                System.out.printf("unchanged HUD submit%s: median %.2f us, p99 %.2f us, %d bytes uploaded%n",
                    round < 0 ? " (warm-up)" : " round " + round, ns[ns.length / 2] / 1e3,
                    ns[ns.length * 99 / 100] / 1e3, bytes);
            }
            // A tenth of the elements re-recorded each frame with their text in another colour, as counters ticking.
            for (int round = -3; round < ROUNDS; round++) {
                long[] ns = new long[2_000];
                long bytes = 0;
                for (int i = 0; i < ns.length; i++) {
                    long color = Rgba.pack(1, i % 2, 0, 1);
                    for (int e = 0; e < 500; e += 10) {
                        UiScenes.element(hud, e, atlas, image, skin, color);
                    }
                    long t0 = System.nanoTime();
                    ui.submit(layers, 1, textures, W, H);
                    ns[i] = System.nanoTime() - t0;
                    bytes += ui.uploadedBytes();
                }
                Arrays.sort(ns);
                System.out.printf("HUD submit, 50 elements changing%s: median %.2f us, p99 %.2f us, %d bytes a frame%n",
                    round < 0 ? " (warm-up)" : " round " + round, ns[ns.length / 2] / 1e3,
                    ns[ns.length * 99 / 100] / 1e3, bytes / ns.length);
            }
        }
    }
}
