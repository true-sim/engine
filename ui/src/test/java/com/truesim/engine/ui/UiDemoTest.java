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
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.SourceLoader;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The A1 demo: a HUD styled by the default theme ({@code .tss}), laid out by ui-layout, its text shaped by HarfBuzz and
 * drawn from msdfgen MTSDF atlases (FreeType's hinted raster below 13 px) over the bundled fonts, rendered by the
 * engine into the UI image and composited after the tone curve over a bright scene, in SDR and HDR10. With {@code TRUESIM_SCREENSHOTS=true} it writes the frames as PNGs.
 */
class UiDemoTest {

    private static final Key<Image> SCENE = Key.image("demo.scene");
    private static final Key<Image> SHOWN = Key.image("demo.shown");

    /** The demo's own rules over the default theme. */
    static final String DEMO = """
        @import "default-theme";

        panel.columns { layout: flex; direction: row; gap: 16px; align-items: start; anchors: full; }
        panel.column { layout: stack; direction: column; gap: 16px; }
        panel.card { width: 380px; }
        panel.skin { background: nine-slice("skin", 6 6 6 6); padding: 10px 14px; }
        panel.row { layout: flex; direction: row; justify-content: space-between; align-items: center; }
        panel.meter > panel { width: 72%; }
        label.huge { font-size: 64px; font-weight: 700; }
        label.tiny { font-size: 10px; }
        label.small { font-size: 12px; }
        label.script { font-size: 20px; }
        """;

    /** A scene brighter than white in places, so tone mapping matters. */
    private record Scene(int width, int height) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(SCENE);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.create("scene", ImageDesc.of(Format.R16G16B16A16_SFLOAT, width, height));
            g.pass("scene").write(image, Access.TRANSFER_DST).record(ctx -> ctx.clear(image.whole(), 1.6f, 0.9f, 0.5f, 1));
            g.provide(SCENE, image);
        }
    }

    static StyleSheet sheet() {
        SourceLoader theme = SourceLoader.classpath(Tss.class.getClassLoader(), Tss.THEMES);
        SourceLoader loader = path -> path.equals("demo.tss") ? DEMO : theme.read(path);
        return Tss.compile(loader, "demo.tss").sheet();
    }

    /**
     * The real text path over the bundled fonts (the same on every machine): HarfBuzz, msdfgen MTSDF, and FreeType's
     * hinted raster for text below 13 px (ui.text.smallText HINTED, the default).
     */
    static TextSystem text(UiTextures textures) {
        NativeTextBackend backend = new NativeTextBackend();
        FontRegistry fonts = BundledCjkFonts.registry(backend);
        GlyphAtlas atlas = new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
            new AtlasSettings());
        return new TextSystem(fonts, backend.newShaper(), atlas);
    }

    /** The demo HUD; returns the health value label. */
    static Element build(Ui ui) {
        BufferedImage skin = new BufferedImage(18, 18, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 18; x++) {
                boolean frame = x < 6 || y < 6 || x >= 12 || y >= 12;
                boolean edge = x < 2 || y < 2 || x >= 16 || y >= 16;
                skin.setRGB(x, y, edge ? 0xFFEBCB8B : frame ? 0xE0303846 : 0xC0222831);
            }
        }
        ui.images().add("skin", skin);

        Element hud = ui.panel("hud");
        Element columns = ui.panel("columns");
        ui.root().add(hud);
        hud.add(columns);

        Element left = ui.panel("column");
        Element status = ui.panel("card");
        columns.add(left);
        left.add(status);
        status.add(ui.label("title", "Ship status"));
        Element health = ui.label("value", "87.5 %");
        Element row = ui.panel("row");
        row.add(ui.label("caption", "Hull integrity")).add(health);
        status.add(row);
        Element meter = ui.panel("meter");
        meter.add(ui.panel(null));
        status.add(meter);
        Element buttons = ui.panel("row");
        Element launch = ui.panel("primary");
        launch.add(ui.label(null, "Launch"));
        Element abort = ui.panel("danger");
        abort.add(ui.label(null, "Abort"));
        buttons.add(launch).add(abort);
        status.add(buttons);
        launch.state(UiState.HOVER, true);

        Element type = ui.panel("card");
        left.add(type);
        type.add(ui.label("caption", "Kerning and ligatures"));
        type.add(ui.label("script", "AVATAR Wave To office affluent fjord"));
        type.add(ui.label("caption", "Arabic, Devanagari, CJK, mixed direction"));
        type.add(ui.label("script", "مرحبا بالعالم 2026"));
        type.add(ui.label("script", "नमस्ते दुनिया"));
        type.add(ui.label("script", "你好，世界 · こんにちは"));
        type.add(ui.label("small", "12 px: The quick brown fox jumps over the lazy dog."));
        type.add(ui.label("tiny", "10 px: The quick brown fox jumps over the lazy dog."));

        Element right = ui.panel("column");
        columns.add(right);
        Element big = ui.panel("skin");
        right.add(big);
        big.add(ui.label("huge", "Wave 12"));
        Element hudText = ui.panel("card");
        right.add(hudText);
        hudText.add(ui.label("hud", "Outlined HUD text keeps its edge over any scene."));
        return health;
    }

    private static GpuRig rig(int width, int height, DisplayEncoding encoding, GpuUiRenderer[] made) {
        GpuRig rig = GpuRig.open(width, height).show(SHOWN);
        made[0] = new GpuUiRenderer(rig.graph);
        rig.view.add(new Scene(width, height)).add(made[0])
            .add(new DisplayTransform().transform(SCENE, SHOWN, encoding));
        return rig;
    }

    private static void settle(Ui ui, int width, int height) {
        for (int i = 0; i < 500 && (i < 2 || ui.textSystem().atlas().pending() > 0 || ui.recorded() > 0); i++) {
            ui.update(width, height);
        }
    }

    @Test
    void theDemoHudRendersStyledTextAndBoxesOverTheScene() {
        int w = 1280;
        int h = 720;
        UiTextures textures = new UiTextures();
        Ui ui = new Ui(sheet(), text(textures), textures);
        Element health = build(ui);
        settle(ui, w, h);
        GpuUiRenderer[] renderer = new GpuUiRenderer[1];
        try (GpuRig rig = rig(w, h, DisplayEncoding.SDR_SRGB, renderer)) {
            rig.frame(renderer[0], textures, ui.layers());
            rig.screenshot("demo-hud-sdr");
            // the card's surface is darker than the tone-mapped scene beside it
            float sceneRed = rig.at(w - 4, h - 4, 0);
            float cardX = ui.layout().x(health.slot()) - 4;
            float cardY = ui.layout().y(health.slot());
            assertThat(rig.at((int) cardX, (int) cardY, 0)).isLessThan(sceneRed * 0.5f);
            // text: the label's box has pixels close to the theme's light foreground
            float brightest = 0;
            for (int y = (int) ui.layout().y(health.slot()); y < ui.layout().y(health.slot())
                + ui.layout().height(health.slot()); y++) {
                for (int x = (int) ui.layout().x(health.slot()); x < ui.layout().x(health.slot())
                    + ui.layout().width(health.slot()); x++) {
                    brightest = Math.max(brightest, rig.at(x, y, 1));
                }
            }
            assertThat(brightest).as("glyph pixels").isGreaterThan(0.6f);

            // a counter changing re-records only its label and uploads a few hundred bytes
            ui.layers()[0].clean();
            rig.frame(renderer[0], textures, ui.layers());
            assertThat(renderer[0].uploadedBytes()).as("an unchanged frame").isZero();
            ui.text(health, "86.0 %");
            ui.update(w, h);
            rig.frame(renderer[0], textures, ui.layers());
            assertThat(renderer[0].uploadedBytes()).isPositive().isLessThan(2_000);
            renderer[0].close();
        }

        // the UI image alone, and the UI at twice the scale (ui.scale)
        try (GpuRig rig = GpuRig.open(w, h).show(com.truesim.engine.render.Keys.UI);
            GpuUiRenderer alone = new GpuUiRenderer(rig.graph)) {
            rig.view.add(alone);
            rig.frame(alone, textures, ui.layers());
            rig.screenshot("demo-hud-ui-image");
        }
        Layout.UI_SCALE.set(2);
        try {
            int w2 = 2 * w;
            int h2 = 2 * h;
            settle(ui, w2, h2);
            GpuRenderer2x.render(ui, textures, w2, h2);
        } finally {
            Layout.UI_SCALE.set(1);
        }
    }

    /** The same HUD at {@code ui.scale} 2 on a 2560 x 1440 view: crisp at the larger size, same layout. */
    private static final class GpuRenderer2x {
        static void render(Ui ui, UiTextures textures, int w, int h) {
            GpuUiRenderer[] renderer = new GpuUiRenderer[1];
            try (GpuRig rig = rig(w, h, DisplayEncoding.SDR_SRGB, renderer)) {
                rig.frame(renderer[0], textures, ui.layers());
                rig.screenshot("demo-hud-sdr-2x");
                renderer[0].close();
            }
        }
    }

    @Test
    void theHudsWhiteShowsAtPaperWhiteInHdr10() {
        int w = 640;
        int h = 360;
        UiTextures textures = new UiTextures();
        StyleSheet white = Tss.compileString("white.tss", "panel { background: box(white); width: 100px; height: 100px; }");
        Ui ui = new Ui(white, text(textures), textures);
        ui.root().add(ui.panel(null));
        ui.update(w, h);
        for (float paperWhite : new float[] {100, 203, 400}) {
            GpuUiRenderer[] renderer = new GpuUiRenderer[1];
            try (GpuRig rig = rig(w, h, DisplayEncoding.HDR10_PQ, renderer)) {
                rig.graph.settings().set(DisplayTransform.PAPER_WHITE, paperWhite);
                rig.frame(renderer[0], textures, ui.layers());
                double p = Math.pow(rig.at(50, 50, 1), 1 / 78.84375);
                double nits = 10000 * Math.pow(Math.max(p - 0.8359375, 0) / (18.8515625 - 18.6875 * p),
                    1 / 0.1593017578125);
                assertThat(nits).as("style white at paper white " + paperWhite).isCloseTo(paperWhite,
                    within(paperWhite * 0.01));
                renderer[0].close();
            }
        }
    }
}
