package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.render.Keys;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * Rotating transform entries on the GPU (ui-core's {@code DrawList.rotated}: turned text, a chart's Y titles and
 * slanted labels): a local entry under a parent turns its primitives about its origin, then the clip's offset and the
 * parent place them; boxes, lines and MTSDF glyphs turn with their quads; the parent moving or fading moves and fades
 * them; the drawn place is where the draw list's {@code drawnBounds} says.
 */
class RotatedPrimitivesGpuTest {

    private static final int W = 256;
    private static final int H = 160;
    private static final long RED = Rgba.pack(1, 0, 0, 1);
    private static final Offset<Float> CLOSE = within(0.02f);

    /**
     * An MTSDF-like texture of a bar: every channel the signed distance to a {@code bw} by {@code bh} rectangle in the
     * middle of a {@code size} texture (0.5 on the edge, inside above), {@code range} texels from 0 to 1.
     */
    private static UiTexture bar(UiTextures textures, int size, float bw, float bh, float range) {
        UiTexture texture = textures.create("bar", size, size, UiTexture.Format.RGBA8_UNORM);
        byte[] pixels = texture.pixels();
        float c = size / 2f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float qx = Math.abs(x + 0.5f - c) - bw / 2;
                float qy = Math.abs(y + 0.5f - c) - bh / 2;
                float outside = (float) Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0);
                int v = Math.round(Math.min(1, Math.max(0, 0.5f - outside / range)) * 255);
                int o = (y * size + x) * 4;
                pixels[o] = (byte) v;
                pixels[o + 1] = (byte) v;
                pixels[o + 2] = (byte) v;
                pixels[o + 3] = (byte) v;
            }
        }
        texture.markDirty(0, 0, size, size);
        return texture;
    }

    @Test
    void aLocalEntryTurnsItsPrimitivesUnderItsParent() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            UiTextures textures = new UiTextures();
            UiTexture bar = bar(textures, 64, 40, 8, 8);
            int[] entries = new int[2];
            DrawList list = UiRendererTest.list(4, l -> {
                int parent = l.addTransform();
                l.setTransform(parent, 1, 10, 5, 1);
                entries[0] = parent;
                l.useTransform(parent);
                // a 60 x 16 box about (64, 64) turned a quarter clockwise: 16 wide, 60 tall, at (74, 69) after the
                // parent's offset
                l.transform(1, 64, 64);
                entries[1] = l.rotated(0, (float) (Math.PI / 2));
                l.box(-30, -8, 30, 8, RED, 0, 0, 0, 0, 0, 0);
                l.unrotated();
                // a glyph of a horizontal bar turned an eighth about (180, 60): a diagonal bar
                l.transform(1, 180, 60);
                l.rotated(0, (float) (Math.PI / 4));
                l.glyph(-32, -32, 32, 32, 0, 0, 1, 1, bar.id(), 8, Rgba.WHITE, 0, 0, 0);
                l.unrotated();
            });
            rig.frame(ui, textures, list);
            rig.screenshot("rotated-primitives");
            // the box: tall, where drawnBounds puts it
            float[] b = new float[4];
            list.drawnBounds(0, -30, -8, 30, 8, b);
            assertThat(b[0]).isCloseTo(66, within(1e-3f));
            assertThat(b[2]).isCloseTo(82, within(1e-3f));
            assertThat(b[1]).isCloseTo(39, within(1e-3f));
            assertThat(b[3]).isCloseTo(99, within(1e-3f));
            assertThat(rig.at(74, 45, 0)).as("inside the turned box, near its top").isCloseTo(1, CLOSE);
            assertThat(rig.at(74, 95, 0)).as("near its bottom").isCloseTo(1, CLOSE);
            assertThat(rig.at(67, 69, 0)).as("its left edge's inner pixel").isCloseTo(1, CLOSE);
            assertThat(rig.at(64, 69, 3)).as("left of it").isZero();
            assertThat(rig.at(100, 69, 3)).as("where the box would lie unturned: nothing").isZero();
            assertThat(rig.at(74, 37, 3)).as("above it").isZero();
            // the glyph: a bar from the top left to the bottom right, through its centre (190, 65 after the offset)
            assertThat(rig.at(190, 65, 3)).as("the bar's centre").isGreaterThan(0.95f);
            assertThat(rig.at(190 + 11, 65 + 11, 3)).as("along the diagonal").isGreaterThan(0.95f);
            assertThat(rig.at(190 - 11, 65 - 11, 3)).isGreaterThan(0.95f);
            assertThat(rig.at(190 + 11, 65 - 11, 3)).as("across it: nothing").isLessThan(0.02f);
            assertThat(rig.at(190 + 18, 65, 3)).as("where the unturned bar's end would be").isLessThan(0.02f);

            // the parent moves and fades: the turned primitives move and fade with it, the list's primitives unchanged
            list.setTransform(entries[0], 1, 30, 5, 0.5f);
            rig.frame(ui, textures, list);
            assertThat(rig.at(94, 45, 0)).as("moved 20 right, at half opacity").isCloseTo(0.5f, CLOSE);
            assertThat(rig.at(74, 45, 3)).as("gone from where it was").isZero();
            assertThat(rig.at(210, 65, 3)).isCloseTo(0.5f, within(0.03f));
            // a scaled parent scales the turned box about the parent's origin
            list.setTransform(entries[0], 2, 0, 0, 1);
            rig.frame(ui, textures, list);
            list.drawnBounds(0, -30, -8, 30, 8, b);
            assertThat(b[2] - b[0]).isCloseTo(32, within(1e-3f));
            assertThat(rig.at((int) ((b[0] + b[2]) / 2), (int) (b[1] + 4), 0)).isCloseTo(1, CLOSE);
            assertThat(rig.at((int) b[0] - 2, (int) ((b[1] + b[3]) / 2), 3)).isZero();
        }
    }

    @Test
    void aTurnedBoxIsCutByItsClipAtThePixel() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            DrawList list = UiRendererTest.list(2, l -> {
                int clip = l.addClip(0, 0, 80, 160, 0);
                l.clip(clip);
                l.transform(1, 80, 80);
                l.rotated(0, (float) (Math.PI / 4));
                l.box(-40, -10, 40, 10, RED, 0, 0, 0, 0, 0, 0);
                l.unrotated();
            });
            rig.frame(ui, null, list);
            rig.screenshot("rotated-clipped");
            assertThat(rig.at(70, 70, 0)).as("inside, left of the cut").isCloseTo(1, CLOSE);
            assertThat(rig.at(90, 90, 3)).as("inside the box, right of the cut").isZero();
        }
    }
}
