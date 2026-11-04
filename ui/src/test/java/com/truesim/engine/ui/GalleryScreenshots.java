package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.render.Keys;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.basic.ControlsGallery;
import com.truesim.ui.controls.data.DataGallery;
import com.truesim.ui.controls.text.TextGallery;
import com.truesim.ui.controls.windows.WindowsScene;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.fonts.emoji.BundledEmojiFonts;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * GPU screenshots of ui-controls' galleries (its test fixtures), in the default and the garish test theme, to look at:
 * the windows scene, the controls gallery (and its pop-ups open), the data gallery and the text controls. With
 * {@code TRUESIM_SCREENSHOTS=true TRUESIM_SCREENSHOTS_DIR=<dir>}, into {@code <dir>/ui-windows}, {@code ui-controls},
 * {@code ui-data} and {@code ui-text}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class GalleryScreenshots {

    private static final String[] THEMES = {"default", "test"};

    private static StyleSheet theme(String name) {
        return name.equals("default") ? Tss.defaultTheme() : Tss.testTheme();
    }

    /** The real text path over the bundled fonts and colour emoji, its atlas in {@code textures}. */
    private static TextSystem text(UiTextures textures) {
        NativeTextBackend backend = new NativeTextBackend();
        FontRegistry fonts = BundledCjkFonts.registry(backend);
        BundledEmojiFonts.register(fonts);
        GlyphAtlas atlas = new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
            new AtlasSettings());
        return new TextSystem(fonts, backend.newShaper(), atlas);
    }

    /** Builds a UI by {@code scene} in each theme and writes its screenshot as {@code dir/name-theme.png}. */
    private static void shoot(String dir, String name, int w, int h, Consumer<Ui> scene) {
        for (String theme : THEMES) {
            UiTextures textures = new UiTextures();
            Ui ui = new Ui(theme(theme), text(textures), textures);
            scene.accept(ui);
            render(ui, textures, w, h, dir + "/" + name + "-" + theme);
        }
    }

    private static void render(Ui ui, UiTextures textures, int w, int h, String name) {
        try (GpuRig rig = GpuRig.open(w, h).show(Keys.UI); GpuUiRenderer renderer = new GpuUiRenderer(rig.graph)) {
            rig.view.add(renderer);
            rig.frame(renderer, textures, ui.layers());
            rig.screenshot(name, 0.006f); // over #0d1117, as the CPU previews
            float alpha = 0;
            for (int i = 3; i < rig.rgba.length; i += 4) {
                alpha += rig.rgba[i];
            }
            assertThat(alpha).as("%s draws something", name).isPositive();
        }
    }

    private static long nanos = 50_000_000_000L; // the UI clock: it only goes forward, across settles

    /** Frames until the atlas has every glyph and animations end. */
    private static void settle(Ui ui, int w, int h) {
        UiInput input = new UiInput(4);
        for (int i = 0; i < 400; i++) {
            input.beginFrame();
            ui.update(w, h, input, nanos += 16_000_000L);
            if (i > 20 && ui.textSystem().atlas().pending() == 0 && ui.animator().running() == 0
                && ui.recorded() == 0) {
                return;
            }
        }
    }

    @Test
    void theWindowsScene() {
        shoot("ui-windows", "windows", 1280, 720, ui -> {
            WindowsScene.build(ui, 1280, 720).frames(60);
        });
    }

    @Test
    void theControlsGalleryAndItsPopups() {
        shoot("ui-controls", "controls", 1280, 800, ui -> {
            ControlsGallery.registerIcons(ui);
            ControlsGallery.build(new Controls(ui), ui.root());
            settle(ui, 1280, 800);
        });
        shoot("ui-controls", "controls-overlays", 1280, 800, ui -> {
            ControlsGallery.registerIcons(ui);
            ControlsGallery.Gallery g = ControlsGallery.build(new Controls(ui), ui.root());
            settle(ui, 1280, 800);
            g.openOverlays();
            settle(ui, 1280, 800);
        });
    }

    @Test
    void theDataGallery() {
        shoot("ui-data", "data", 1600, 1000, ui -> {
            DataGallery gallery = DataGallery.build(ui);
            DataGallery.settle(ui, 1600, 1000);
            gallery.pose(ui, 1600, 1000);
            settle(ui, 1600, 1000);
        });
    }

    @Test
    void theTextControls() {
        shoot("ui-text", "text", 1280, 720, ui -> {
            TextGallery.build(ui, 1280, 720);
        });
    }
}
