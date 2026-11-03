package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.render.Keys;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.SoftwareGlyphRenderer;
import com.truesim.ui.text.TextAlign;
import com.truesim.ui.text.TextBlock;
import com.truesim.ui.text.TextSystem;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UI16 on the GPU: a small label drawn with ui.text.smallText HINTED (hinted raster glyphs, FLAG_RASTER) and MTSDF,
 * each compared pixel by pixel with the CPU reference (SoftwareGlyphRenderer, the shader's formula). With
 * {@code TRUESIM_SCREENSHOTS=true} it writes both as PNGs.
 */
class SmallTextTest {

    private static final int W = 320;
    private static final int H = 40;

    @Test
    void rasterGlyphsArePixelExactCoverage() {
        try (GpuRig rig = GpuRig.open(64, 16).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            UiTextures textures = new UiTextures();
            UiTexture page = textures.create("coverage", 8, 8, UiTexture.Format.RGBA8_UNORM);
            byte[] p = page.pixels();
            for (int i = 0; i < 64; i++) {
                byte v = (byte) (i * 4); // a ramp: each texel its own coverage
                p[i * 4] = v;
                p[i * 4 + 1] = v;
                p[i * 4 + 2] = v;
                p[i * 4 + 3] = v;
            }
            page.markDirty(0, 0, 8, 8);
            long half = Rgba.pack(1, 0.5f, 0.25f, 0.5f);
            DrawList list = UiRendererTest.list(1, l -> l.rasterGlyph(4, 2, 12, 10, 0, 0, 1, 1, page.id(), half));
            rig.frame(ui, textures, list);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    float coverage = (y * 8 + x) * 4 / 255f;
                    assertThat(rig.at(4 + x, 2 + y, 3)).as("texel %d, %d", x, y)
                        .isCloseTo(0.5f * coverage, org.assertj.core.data.Offset.offset(0.004f));
                    assertThat(rig.at(4 + x, 2 + y, 0)).isCloseTo(0.5f * coverage,
                        org.assertj.core.data.Offset.offset(0.004f));
                }
            }
            assertThat(rig.at(3, 5, 3)).as("outside the quad").isZero();
        }
    }

    /** The label recorded as a frame would (glyphs generated first); the list. */
    private static DrawList recorded(TextBlock b, TextSystem text) throws InterruptedException {
        DrawList list = new DrawList();
        int start = list.allocate(b.glyphCount());
        for (int pass = 0; pass < 2; pass++) {
            list.begin(start, b.glyphCount());
            b.record(list, 4.3f, 6, Rgba.WHITE, 0, 0, 0);
            list.end();
            text.atlas().finishAll();
        }
        return list;
    }

    @Test
    void aSmallLabelMatchesTheCpuReferenceInBothTechniques() throws Exception {
        UiTextures textures = new UiTextures();
        TextSystem text = UiDemoTest.text(textures);
        AtlasSettings settings = text.atlas().settings();
        TextBlock b = new TextBlock(text);
        String label = "11 px: Hull integrity 87.5 % — AV To Wa";
        b.set(label, List.of("Inter"), 11, 400, false, TextAlign.START, Float.POSITIVE_INFINITY, 0);
        float width = b.width();
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            float[][] gpu = new float[2][];
            for (String mode : new String[] {"HINTED", "MTSDF"}) {
                settings.smallText().select(mode);
                DrawList list = recorded(b, text);
                boolean raster = mode.equals("HINTED");
                assertThat(b.drewRaster()).isEqualTo(raster);
                assertThat(Primitive.flags(list.header(0)) & Primitive.FLAG_RASTER).isEqualTo(
                    raster ? Primitive.FLAG_RASTER : 0);
                assertThat(b.width()).as("the same layout").isEqualTo(width);
                rig.frame(ui, textures, list);
                rig.screenshot("ui16-gpu-11px-" + mode.toLowerCase(java.util.Locale.ROOT));
                float[] cpu = new float[W * H * 3]; // white text over black: the colour is the coverage
                SoftwareGlyphRenderer.render(list, textures, cpu, W, H);
                float worst = 0;
                float ink = 0;
                float[] alpha = new float[W * H];
                for (int i = 0; i < W * H; i++) {
                    alpha[i] = rig.rgba[i * 4 + 3];
                    worst = Math.max(worst, Math.abs(alpha[i] - cpu[i * 3]));
                    ink += alpha[i];
                }
                assertThat(ink).as(mode + " draws the label").isGreaterThan(100);
                assertThat(worst).as(mode + ": GPU against the CPU reference").isLessThan(raster ? 0.01f : 0.06f);
                gpu[raster ? 0 : 1] = alpha;
            }
            int differ = 0;
            for (int i = 0; i < W * H; i++) {
                differ += Math.abs(gpu[0][i] - gpu[1][i]) > 0.1f ? 1 : 0;
            }
            assertThat(differ).as("the techniques draw different pixels").isGreaterThan(50);
        } finally {
            settings.smallText().select("HINTED");
        }
    }
}
