package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.render.Keys;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.text.TextEditor;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.fonts.emoji.BundledEmojiFonts;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.SourceLoader;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.text.SoftwareGlyphRenderer;
import com.truesim.ui.text.TextSystem;
import org.junit.jupiter.api.Test;

/**
 * A text field from ui-controls drawn by the engine: typed text with a selection, the IME's preedit underlined at the
 * caret and a colour emoji (FLAG_COLOR on an sRGB page), checked against the CPU reference and written as screenshots
 * with {@code TRUESIM_SCREENSHOTS=true}.
 */
class TextFieldGpuTest {

    private static final int W = 420;
    private static final int H = 120;

    private static StyleSheet sheet() {
        SourceLoader theme = SourceLoader.classpath(Tss.class.getClassLoader(), Tss.THEMES);
        String own = """
            @import "default-theme";
            root { layout: flex; direction: column; gap: 12px; padding: 12px; align-items: start; }
            textfield { width: 380px; font-size: 18px; }
            """;
        return Tss.compile(path -> path.equals("field.tss") ? own : theme.read(path), "field.tss").sheet();
    }

    @Test
    void aFieldWithSelectionPreeditAndEmojiDraws() {
        UiTextures textures = new UiTextures();
        TextSystem text = UiDemoTest.text(textures);
        BundledEmojiFonts.register(text.fonts());
        Ui ui = new Ui(sheet(), text, textures);
        TextEditor selected = TextEditor.field(ui, null, "Selected");
        TextEditor composing = TextEditor.field(ui, null, "Composing");
        ui.root().add(selected.element());
        ui.root().add(composing.element());
        UiInput input = new UiInput(16);
        long now = 0;
        selected.text("Hello \uD83D\uDE00 world \uD83D\uDC4D\uD83C\uDFFD");
        selected.focus();
        ui.update(W, H, input, now);
        selected.editable().select(0, 5);
        composing.focus();
        input.beginFrame();
        input.text("kana: ");
        ui.update(W, H, input, now += 16_000_000L);
        input.beginFrame();
        input.composition("\u304B\u306A", 2);
        for (int i = 0; i < 30; i++) { // the glyphs generate
            ui.update(W, H, input, now += 16_000_000L);
            input.beginFrame();
        }
        assertThat(composing.editable().composing()).isTrue();
        DrawList list = ui.layers()[0];
        int colour = -1;
        for (int i = 0; i < list.size(); i++) {
            if ((Primitive.flags(list.header(i)) & Primitive.FLAG_COLOR) != 0
                && list.floatAt(i, Primitive.X1) > list.floatAt(i, Primitive.X0)) {
                colour = i;
                break;
            }
        }
        assertThat(colour).as("a colour emoji").isNotNegative();
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer renderer = new GpuUiRenderer(rig.graph)) {
            rig.view.add(renderer);
            rig.frame(renderer, textures, ui.layers());
            rig.screenshot("text-field-gpu");
            // the grinning face's middle is yellow: red and green high, blue low (premultiplied, opaque there)
            int cx = (int) ((list.floatAt(colour, Primitive.X0) + list.floatAt(colour, Primitive.X1)) / 2);
            int cy = (int) ((list.floatAt(colour, Primitive.Y0) * 0.7f + list.floatAt(colour, Primitive.Y1) * 0.3f));
            float[] px = rig.at(cx, cy);
            assertThat(px[0]).as("red").isGreaterThan(0.4f);
            assertThat(px[2]).as("blue").isLessThan(px[0] * 0.5f);

            // one colour glyph alone against the CPU reference
            DrawList one = new DrawList();
            int start = one.allocate(1);
            one.begin(start, 1);
            one.colorGlyph(20, 10, 84, 74, uv(list, colour, 0), uv(list, colour, 1), uv(list, colour, 2),
                uv(list, colour, 3), list.intAt(colour, Primitive.TEXTURE), Rgba.pack(1, 1, 1, 1));
            one.end();
            rig.frame(renderer, textures, one);
            rig.screenshot("colour-glyph-gpu");
            float[] cpu = new float[W * H * 3];
            SoftwareGlyphRenderer.render(one, textures, cpu, W, H); // over black: premultiplied
            float worst = 0;
            for (int y = 10; y < 74; y++) {
                for (int x = 20; x < 84; x++) {
                    for (int c = 0; c < 3; c++) {
                        worst = Math.max(worst, Math.abs(rig.at(x, y, c) - cpu[(y * W + x) * 3 + c]));
                    }
                }
            }
            assertThat(worst).as("GPU against the CPU reference, linear").isLessThan(0.03f);
        }
    }

    private static float uv(DrawList list, int i, int corner) {
        int w = list.intAt(i, Primitive.UV + corner / 2);
        return ((corner % 2 == 0 ? w : w >>> 16) & 0xFFFF) / 65535f;
    }
}
