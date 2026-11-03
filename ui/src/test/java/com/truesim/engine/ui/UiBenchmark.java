package com.truesim.engine.ui;

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
 * (pitfalls.md). Run without validation and without JaCoCo:
 * {@code TRUESIM_BENCHMARK=true ./gradlew :engine-ui:test --tests '*UiBenchmark'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class UiBenchmark {

    private static final int W = 3840;
    private static final int H = 2160;
    private static final int WARM_ROUNDS = 6;
    private static final int ROUNDS = 5;
    private static final int FRAMES = 60;

    /** A page of a glyph atlas: 16 x 16 cells of 32 texels, each a synthetic MTSDF disc (range 4 texels). */
    static UiTexture atlas(UiTextures textures) {
        UiTexture page = textures.create("atlas", 512, 512, UiTexture.Format.RGBA8_UNORM);
        byte[] p = page.pixels();
        for (int y = 0; y < 512; y++) {
            for (int x = 0; x < 512; x++) {
                int cell = (y / 32) * 16 + x / 32;
                float radius = 6 + cell % 9;
                float d = radius - (float) Math.hypot(x % 32 + 0.5f - 16, y % 32 + 0.5f - 16);
                int v = Math.round(Math.min(1, Math.max(0, 0.5f + d / 4)) * 255);
                int o = (y * 512 + x) * 4;
                p[o] = (byte) v;
                p[o + 1] = (byte) v;
                p[o + 2] = (byte) v;
                p[o + 3] = (byte) v;
            }
        }
        page.markDirty(0, 0, 512, 512);
        return page;
    }

    /** One glyph of cell {@code cell} of the atlas at {@code x, y}, {@code size} pixels. */
    private static void glyph(DrawList list, UiTexture atlas, int cell, float x, float y, float size, long color) {
        float u = (cell % 16) / 16f;
        float v = (cell / 16 % 16) / 16f;
        list.glyph(x, y, x + size, y + size, u, v, u + 1 / 16f, v + 1 / 16f, atlas.id(), 4 * size / 32, color, 0, 0,
            0);
    }

    /** Primitives each HUD element's range holds: a shadow, a box, a line and ten glyphs. */
    static final int ELEMENT = 13;

    /**
     * A HUD at 4K: 500 elements in panels along the screen's edges (about a quarter of it covered, as HUDs are), each
     * in its own range of the list: its box (rounded, bordered; some with a shadow, a gradient, an image or a
     * nine-slice skin, a divider line) and ten glyphs of text: 5,000 glyphs.
     */
    static DrawList hud(UiTexture atlas, UiTexture image, UiTexture skin) {
        DrawList list = new DrawList();
        list.addClip(40, 1200, 900, 2100, 24); // clip 1: a scroll pane's rows
        for (int e = 0; e < 500; e++) {
            list.allocate(ELEMENT);
            element(list, e, atlas, image, skin, Rgba.srgb(236, 239, 244, 1));
        }
        return list;
    }

    /** Records HUD element {@code e} into its range, its text in {@code text}. */
    static void element(DrawList list, int e, UiTexture atlas, UiTexture image, UiTexture skin, long text) {
        long panel = Rgba.srgb(46, 52, 64, 0.85f);
        long accent = Rgba.srgb(136, 192, 208, 1);
        // Five columns of 100 elements: two down each side, one along the bottom.
        int column = e / 100;
        int row = e % 100;
        float x;
        float y;
        float w = 180;
        float h = 40;
        if (column < 4) {
            x = column < 2 ? 40 + column * 200 : W - 440 + (column - 2) * 200;
            y = 40 + row * 21;
            h = 18;
        } else {
            x = 960 + (row % 25) * 76;
            y = H - 200 + (row / 25) * 44;
            w = 70;
        }
        list.begin(e * ELEMENT, ELEMENT);
        if (column == 0) {
            list.clip(1);
        }
        switch (e % 10) {
            case 0, 1, 2, 3 -> {
                if (e % 20 == 0) {
                    list.shadow(x + 2, y + 3, x + w + 2, y + h + 3, Rgba.pack(0, 0, 0, 0.5f), 6, 6, 6, 6, 4);
                }
                list.box(x, y, x + w, y + h, panel, 6, 6, 6, 6, 1.5f, accent);
            }
            case 4, 5 -> list.gradientBox(x, y, x + w, y + h, panel, Rgba.srgb(59, 66, 82, 0.9f), 1.57f, 4, 4, 4, 4,
                0, 0);
            case 6 -> list.image(x, y, x + h, y + h, 0, 0, 1, 1, image.id(), Rgba.WHITE);
            case 7 -> list.nineSlice(x, y, x + w, y + h, 0, 0, 1, 1, skin.id(), Rgba.WHITE, 6, 6, 6, 6, 4, 4, 4, 4);
            case 8 -> {
                list.box(x, y, x + w, y + h, panel, 0, 0, 0, 0, 0, 0);
                list.line(x + 4, y + h - 2, x + w - 4, y + h - 2, 1, accent);
            }
            default -> list.box(x, y, x + w, y + h, panel, 0, 0, 0, 0, 0, 0);
        }
        float size = Math.min(16, h - 2);
        for (int g = 0; g < 10; g++) {
            glyph(list, atlas, (e * 10 + g) % 256, x + 6 + g * (size * 0.6f), y + (h - size) / 2, size, text);
        }
        list.end();
    }

    /** A console flood at 4K: a backdrop (or none) and 100,000 glyphs, 500 to a line, 200 lines. */
    static DrawList flood(UiTexture atlas, boolean backdrop) {
        DrawList list = new DrawList();
        int start = list.allocate(100_001);
        list.begin(start, 100_001);
        if (backdrop) {
            list.box(0, 0, W, H, Rgba.pack(0, 0, 0, 0.6f), 0, 0, 0, 0, 0, 0);
        }
        float cw = W / 500f;
        float ch = H / 200f;
        for (int line = 0; line < 200; line++) {
            for (int c = 0; c < 500; c++) {
                glyph(list, atlas, (line * 500 + c) * 7 % 256, c * cw, line * ch, ch, Rgba.WHITE);
            }
        }
        list.end();
        return list;
    }

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
        UiTexture atlas = atlas(textures);
        DrawList hud = hud(atlas, UiScreenshots.photo(textures), UiScreenshots.skin(textures));
        compare("HUD 500 elements + 5,000 glyphs, 4K", hud, textures);
    }

    @Test
    void hundredThousandGlyphsAt4k() {
        UiTextures textures = new UiTextures();
        UiTexture atlas = atlas(textures);
        compare("100,000 glyphs over a backdrop, 4K", flood(atlas, true), textures);
        compare("100,000 glyphs, no backdrop, 4K", flood(atlas, false), textures);
    }

    @Test
    void submittingAnUnchangedHudCostsNothing() {
        UiTextures textures = new UiTextures();
        UiTexture atlas = atlas(textures);
        UiTexture image = UiScreenshots.photo(textures);
        UiTexture skin = UiScreenshots.skin(textures);
        DrawList hud = hud(atlas, image, skin);
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
                        element(hud, e, atlas, image, skin, color);
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
