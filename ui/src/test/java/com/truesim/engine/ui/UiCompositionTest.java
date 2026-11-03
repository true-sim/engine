package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The renderer's UI composited by the display transform after the tone curve (UI13): SDR colours as the UI drew them,
 * HDR white at paper white, over a bright scene.
 */
class UiCompositionTest {

    private static final int W = 256;
    private static final int H = 128;
    private static final Key<Image> SCENE = Key.image("test.scene");
    private static final Key<Image> SHOWN = Key.image("test.shown");

    /** A bright scene: 4 times white, a little warm. */
    private record Scene() implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(SCENE);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.create("scene", ImageDesc.of(Format.R16G16B16A16_SFLOAT, W, H));
            g.pass("scene").write(image, Access.TRANSFER_DST)
                .record(ctx -> ctx.clear(image.whole(), 4, 3, 2, 1));
            g.provide(SCENE, image);
        }
    }

    /** A HUD: a white panel, a coloured half-transparent one, text-like glyphs. */
    private static DrawList hud(UiTextures textures) {
        UiTexture disc = UiRendererTest.disc(textures, 32, 10, 4);
        return UiRendererTest.list(8, l -> {
            l.shadow(20, 20, 120, 100, Rgba.pack(0, 0, 0, 0.6f), 10, 10, 10, 10, 6);
            l.box(16, 16, 116, 96, Rgba.WHITE, 10, 10, 10, 10, 2, Rgba.srgb(40, 40, 40, 1));
            l.box(140, 16, 240, 96, Rgba.pack(0.2f, 0.4f, 0.8f, 0.5f), 10, 10, 10, 10, 0, 0);
            for (int i = 0; i < 4; i++) {
                l.glyph(150 + i * 20, 40, 170 + i * 20, 60, 0, 0, 1, 1, disc.id(), 2.5f, Rgba.WHITE, 1,
                    Rgba.BLACK, 0);
            }
        });
    }

    private static GpuRig rig(DisplayEncoding encoding, GpuUiRenderer[] made) {
        GpuRig rig = GpuRig.open(W, H).show(SHOWN);
        made[0] = new GpuUiRenderer(rig.graph);
        rig.view.add(new Scene()).add(made[0]).add(new DisplayTransform().transform(SCENE, SHOWN, encoding));
        return rig;
    }

    /** Nits of a PQ-encoded value: SMPTE ST 2084's inverse. */
    private static double nits(double encoded) {
        double p = Math.pow(encoded, 1 / 78.84375);
        return 10000 * Math.pow(Math.max(p - 0.8359375, 0) / (18.8515625 - 18.6875 * p), 1 / 0.1593017578125);
    }

    @Test
    void sdrShowsTheUisColoursOverTheToneMappedScene() {
        GpuUiRenderer[] ui = new GpuUiRenderer[1];
        try (GpuRig rig = rig(DisplayEncoding.SDR_SRGB, ui)) {
            UiTextures textures = new UiTextures();
            rig.frame(ui[0], textures, hud(textures));
            assertThat(rig.at(60, 56)).as("the white panel: white, not tone mapped")
                .containsExactly(new float[] {1, 1, 1, 1}, within(2e-3f));
            float[] scene = rig.at(250, 120);
            float[] blended = rig.at(145, 90);
            for (int c = 0; c < 3; c++) {
                float ui50 = new float[] {0.2f, 0.4f, 0.8f}[c] * 0.5f;
                assertThat(blended[c]).as("half the scene under half the panel")
                    .isCloseTo(Math.min(1, scene[c] * 0.5f + ui50), within(3e-3f));
            }
            rig.screenshot("composited-sdr");
            ui[0].close();
        }
    }

    @Test
    void hdrShowsTheUisWhiteAtPaperWhite() {
        for (float paperWhite : new float[] {80, 203, 480}) {
            GpuUiRenderer[] ui = new GpuUiRenderer[1];
            try (GpuRig rig = rig(DisplayEncoding.HDR10_PQ, ui)) {
                rig.graph.settings().set(DisplayTransform.PAPER_WHITE, paperWhite);
                UiTextures textures = new UiTextures();
                rig.frame(ui[0], textures, hud(textures));
                for (int c = 0; c < 3; c++) {
                    assertThat(nits(rig.at(60, 56, c))).as("white at paper white " + paperWhite)
                        .isCloseTo(paperWhite, within(paperWhite * 0.01));
                }
                assertThat(nits(rig.at(250, 120, 0))).as("the scene beside it, brighter")
                    .isGreaterThan(paperWhite * 1.5);
                ui[0].close();
            }
        }
        GpuUiRenderer[] ui = new GpuUiRenderer[1];
        try (GpuRig rig = rig(DisplayEncoding.SCRGB_LINEAR, ui)) {
            rig.graph.settings().set(DisplayTransform.PAPER_WHITE, 240f);
            UiTextures textures = new UiTextures();
            rig.frame(ui[0], textures, hud(textures));
            assertThat(java.util.Arrays.copyOf(rig.at(60, 56), 3)).as("scRGB: 240 nits over 80")
                .containsExactly(new float[] {3, 3, 3}, within(0.01f));
            ui[0].close();
        }
    }
}
