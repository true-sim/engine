package com.truesim.engine.ui.testing;

import com.truesim.common.math.Maths;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;

/**
 * The UI renderer's benchmark scenes at 4K, as draw lists (design section 11): a HUD of 500 elements and 5,000 glyphs
 * and a flood of 100,000 glyphs, over a synthetic glyph atlas, an image and a nine-slice skin. Published with
 * engine-ui's test fixtures: shared by its batching benchmark (UiBenchmark) and the samples' UI suite (UiBench).
 */
public final class UiScenes {

    /** The screen: 4K. */
    public static final int W = 3840;
    public static final int H = 2160;
    /** Primitives each HUD element's range holds: a shadow, a box, a line and ten glyphs. */
    public static final int ELEMENT = 13;

    private UiScenes() {
    }

    /** A page of a glyph atlas: 16 x 16 cells of 32 texels, each a synthetic MTSDF disc (range 4 texels). */
    public static UiTexture atlas(UiTextures textures) {
        UiTexture page = textures.create("atlas", 512, 512, UiTexture.Format.RGBA8_UNORM);
        byte[] p = page.pixels();
        for (int y = 0; y < 512; y++) {
            for (int x = 0; x < 512; x++) {
                int cell = (y / 32) * 16 + x / 32;
                float radius = 6 + cell % 9;
                float d = radius - (float) Maths.hypot(x % 32 + 0.5f - 16, y % 32 + 0.5f - 16);
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

    /** A 16 x 16 sRGB nine-slice skin: a 4-texel frame, translucent middle. */
    public static UiTexture skin(UiTextures textures) {
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
    public static UiTexture photo(UiTextures textures) {
        UiTexture photo = textures.create("photo", 64, 64, UiTexture.Format.RGBA8_SRGB);
        byte[] p = photo.pixels();
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                double dx = x - 31.5;
                double dy = y - 31.5;
                double hue = (Maths.atan2(dy, dx) / (2 * Math.PI) + 1) % 1;
                int rgb = java.awt.Color.HSBtoRGB((float) hue, (float) Math.min(1, Maths.hypot(dx, dy) / 32), 1);
                int o = (y * 64 + x) * 4;
                p[o] = (byte) (rgb >> 16);
                p[o + 1] = (byte) (rgb >> 8);
                p[o + 2] = (byte) rgb;
                p[o + 3] = (byte) (Maths.hypot(dx, dy) < 32 ? 255 : 0);
            }
        }
        photo.markDirty(0, 0, 64, 64);
        return photo;
    }

    /** One glyph of cell {@code cell} of the atlas at {@code x, y}, {@code size} pixels. */
    private static void glyph(DrawList list, UiTexture atlas, int cell, float x, float y, float size, long color) {
        float u = (cell % 16) / 16f;
        float v = (cell / 16 % 16) / 16f;
        list.glyph(x, y, x + size, y + size, u, v, u + 1 / 16f, v + 1 / 16f, atlas.id(), 4 * size / 32, color, 0, 0,
            0);
    }

    /**
     * A HUD at 4K: 500 elements in panels along the screen's edges (about a quarter of it covered, as HUDs are), each
     * in its own range of the list: its box (rounded, bordered; some with a shadow, a gradient, an image or a
     * nine-slice skin, a divider line) and ten glyphs of text: 5,000 glyphs.
     */
    public static DrawList hud(UiTexture atlas, UiTexture image, UiTexture skin) {
        DrawList list = new DrawList();
        list.addClip(40, 1200, 900, 2100, 24); // clip 1: a scroll pane's rows
        for (int e = 0; e < 500; e++) {
            list.allocate(ELEMENT);
            element(list, e, atlas, image, skin, Rgba.srgb(236, 239, 244, 1));
        }
        return list;
    }

    /** Records HUD element {@code e} into its range, its text in {@code text}. */
    public static void element(DrawList list, int e, UiTexture atlas, UiTexture image, UiTexture skin, long text) {
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
    public static DrawList flood(UiTexture atlas, boolean backdrop) {
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
}
