package com.truesim.engine.ui;

import com.truesim.engine.render.Keys;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A gallery of every kind of primitive at several scales, written as PNGs to look at: {@code TRUESIM_SCREENSHOTS=true
 * TRUESIM_SCREENSHOTS_DIR=<dir> ./gradlew :engine-ui:test --tests '*UiScreenshots'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class UiScreenshots {

    private static final int W = 1024;
    private static final int H = 640;

    /** The gallery at {@code scale}: everything's positions and sizes multiplied by it. */
    static DrawList gallery(UiTextures textures, UiTexture disc, UiTexture skin, UiTexture photo, float s) {
        DrawList list = new DrawList();
        int clip = list.addClip(20 * s, 330 * s, 300 * s, 470 * s, 18 * s);
        int start = list.allocate(400);
        list.begin(start, 400);
        long panel = Rgba.srgb(46, 52, 64, 1);
        long accent = Rgba.srgb(136, 192, 208, 1);
        long warm = Rgba.srgb(235, 203, 139, 1);
        // Row 1: boxes, radii, borders, gradients, a glow above white.
        for (int i = 0; i < 6; i++) {
            float x = 20 + i * 70;
            list.box(x * s, 20 * s, (x + 60) * s, 80 * s, i % 2 == 0 ? panel : accent, i * 6 * s, i * 6 * s,
                i * 3 * s, i * 2 * s, i * s, warm);
        }
        list.box(450 * s, 20 * s, 510 * s, 80 * s, Rgba.pack(1, 1, 1, 0.25f), 30 * s, 30 * s, 30 * s, 30 * s, 2 * s,
            Rgba.WHITE);
        for (int i = 0; i < 4; i++) {
            float x = 530 + i * 115;
            list.gradientBox(x * s, 20 * s, (x + 100) * s, 80 * s, accent, Rgba.srgb(191, 97, 106, 1),
                (float) (i * Math.PI / 4), 12 * s, 12 * s, 12 * s, 12 * s, 0, 0);
        }
        // Row 2: shadows of growing blur under panels.
        for (int i = 0; i < 6; i++) {
            float x = 30 + i * 160;
            float sigma = (1 + i * 3) * s;
            list.shadow((x + 8) * s, (120 + 10) * s, (x + 128) * s, (190 + 10) * s, Rgba.pack(0, 0, 0, 0.9f),
                10 * s, 10 * s, 10 * s, 10 * s, sigma);
            list.box(x * s, 120 * s, (x + 120) * s, 190 * s, Rgba.srgb(229, 233, 240, 1), 10 * s, 10 * s, 10 * s,
                10 * s, 0, 0);
        }
        // Row 3: glyphs (a synthetic MTSDF disc) from 6 to 96 px, outlined, bold; an image; a nine-slice.
        float gx = 20;
        for (int size : new int[] {6, 8, 12, 16, 24, 32, 48, 64, 96}) {
            float range = 4 * size / 32f; // the atlas's 4 texels at this size
            list.glyph(gx * s, (230 + 96 - size) * s, (gx + size) * s, (230 + 96) * s, 0, 0, 1, 1, disc.id(),
                range * s, Rgba.WHITE, size >= 32 ? 3 * s : 0, Rgba.srgb(191, 97, 106, 1), size == 96 ? 2 * s : 0);
            gx += size + 10;
        }
        list.image(450 * s, 230 * s, 546 * s, 326 * s, 0, 0, 1, 1, photo.id(), Rgba.WHITE);
        list.nineSlice(570 * s, 230 * s, 990 * s, 320 * s, 0, 0, 1, 1, skin.id(), Rgba.WHITE, 12 * s, 12 * s,
            12 * s, 12 * s, 4, 4, 4, 4);
        // Row 4: a rounded scroll pane clipping rows; lines of several widths and angles.
        list.clip(clip);
        for (int i = 0; i < 8; i++) {
            float y = 320 + i * 22;
            list.box(20 * s, y * s, 300 * s, (y + 20) * s, i % 2 == 0 ? panel : Rgba.srgb(59, 66, 82, 1), 0, 0, 0,
                0, 0, 0);
            list.line(30 * s, (y + 10) * s, 290 * s, (y + 10) * s, 1 * s, accent);
        }
        list.clip(DrawList.NO_CLIP);
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI / 12;
            float cx = 520;
            float cy = 420;
            list.line((float) (cx + Math.cos(angle) * 20) * s, (float) (cy + Math.sin(angle) * 20) * s,
                (float) (cx + Math.cos(angle) * 100) * s, (float) (cy + Math.sin(angle) * 100) * s, (0.25f + i * 0.5f)
                    * s, warm);
        }
        for (int i = 0; i < 6; i++) {
            list.line(660 * s, (340 + i * 30) * s, 1000 * s, (360 + i * 30) * s, (0.5f + i) * s, Rgba.WHITE);
        }
        list.end();
        return list;
    }

    /** A 16 x 16 skin: a 4-texel frame, a lighter inside, a darker corner pixel to see the corners kept. */
    static UiTexture skin(UiTextures textures) {
        UiTexture skin = textures.create("skin", 16, 16, UiTexture.Format.RGBA8_SRGB);
        byte[] p = skin.pixels();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean frame = x < 4 || y < 4 || x >= 12 || y >= 12;
                boolean corner = (x < 4 || x >= 12) && (y < 4 || y >= 12);
                int o = (y * 16 + x) * 4;
                p[o] = (byte) (corner ? 191 : frame ? 94 : 76);
                p[o + 1] = (byte) (corner ? 97 : frame ? 129 : 86);
                p[o + 2] = (byte) (corner ? 106 : frame ? 172 : 106);
                p[o + 3] = (byte) (frame ? 255 : 200);
            }
        }
        skin.markDirty(0, 0, 16, 16);
        return skin;
    }

    /** A 64 x 64 sRGB colour wheel, its alpha fading at the corners. */
    static UiTexture photo(UiTextures textures) {
        UiTexture photo = textures.create("photo", 64, 64, UiTexture.Format.RGBA8_SRGB);
        byte[] p = photo.pixels();
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                double dx = x - 31.5;
                double dy = y - 31.5;
                double hue = (Math.atan2(dy, dx) / (2 * Math.PI) + 1) % 1;
                int rgb = java.awt.Color.HSBtoRGB((float) hue, (float) Math.min(1, Math.hypot(dx, dy) / 32), 1);
                int o = (y * 64 + x) * 4;
                p[o] = (byte) (rgb >> 16);
                p[o + 1] = (byte) (rgb >> 8);
                p[o + 2] = (byte) rgb;
                p[o + 3] = (byte) (Math.hypot(dx, dy) < 32 ? 255 : 0);
            }
        }
        photo.markDirty(0, 0, 64, 64);
        return photo;
    }

    @Test
    void gallery() {
        for (float scale : new float[] {0.5f, 1}) {
            try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
                rig.view.add(ui);
                UiTextures textures = new UiTextures();
                UiTexture disc = UiRendererTest.disc(textures, 64, 24, 8);
                DrawList list = gallery(textures, disc, skin(textures), photo(textures), scale);
                rig.frame(ui, textures, list);
                rig.screenshot("gallery-" + (scale == 1 ? "1x" : "half"));
                ui.batching(GpuUiRenderer.Batching.PER_KIND);
                rig.frame(ui, textures, list);
                rig.screenshot("gallery-" + (scale == 1 ? "1x" : "half") + "-per-kind");
            }
        }
        // Twice as large: a UI of half the view's pixels, scaled up by the renderer.
        try (GpuRig rig = GpuRig.open(2 * W, 2 * H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            UiTextures textures = new UiTextures();
            UiTexture disc = UiRendererTest.disc(textures, 64, 24, 8);
            DrawList list = gallery(textures, disc, skin(textures), photo(textures), 2);
            rig.frame(ui, textures, list);
            rig.screenshot("gallery-2x");
        }
    }
}
