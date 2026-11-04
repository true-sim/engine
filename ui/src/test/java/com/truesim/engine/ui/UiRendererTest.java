package com.truesim.engine.ui;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.render.Keys;
import com.truesim.engine.ui.layout.PrimitiveConstants;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/** The UI renderer on the GPU: each kind of primitive, clipping, batching, uploads; read back from the UI image. */
class UiRendererTest {

    private static final int W = 256;
    private static final int H = 128;
    private static final long RED = Rgba.pack(1, 0, 0, 1);
    private static final long GREEN = Rgba.pack(0, 1, 0, 1);
    private static final long BLUE = Rgba.pack(0, 0, 1, 1);
    private static final Offset<Float> CLOSE = within(0.01f);
    private static final long TRANSFORM_BYTES = DrawList.TRANSFORM_FLOATS * 4L;

    /** A list of one range holding what {@code record} records. */
    static DrawList list(int capacity, java.util.function.Consumer<DrawList> record) {
        DrawList list = new DrawList();
        int start = list.allocate(capacity);
        list.begin(start, capacity);
        record.accept(list);
        assertThat(list.end()).as("fits").isTrue();
        return list;
    }

    /**
     * A synthetic MTSDF texture of a disc: every channel the true signed distance (0.5 on the edge, positive inside),
     * {@code range} texels from 0 to 1, as msdfgen encodes it.
     */
    static UiTexture disc(UiTextures textures, int size, float radius, float range) {
        UiTexture texture = textures.create("disc", size, size, UiTexture.Format.RGBA8_UNORM);
        byte[] pixels = texture.pixels();
        float centre = size / 2f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float d = radius - (float) Math.hypot(x + 0.5f - centre, y + 0.5f - centre);
                int v = Math.round(Math.min(1, Math.max(0, 0.5f + d / range)) * 255);
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
    void theShadersConstantsAreThePrimitiveLayout() throws IllegalAccessException {
        int checked = 0;
        for (Field constant : PrimitiveConstants.class.getFields()) {
            if (!Modifier.isStatic(constant.getModifiers())) {
                continue;
            }
            Object expected = constant.getName().equals("CLIP_FLOATS") ? DrawList.CLIP_FLOATS
                : constant.getName().equals("TRANSFORM_FLOATS") ? DrawList.TRANSFORM_FLOATS
                : constant.getName().equals("VERTEX_FLOATS") ? DrawList.VERTEX_FLOATS
                : field(Primitive.class, constant.getName());
            assertThat(constant.get(null)).as(constant.getName()).isEqualTo(expected);
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(30);
        assertThat(PrimitiveConstants.WORDS * 4).isEqualTo(Primitive.BYTES);
        // and every layout constant of Primitive is mirrored
        for (Field constant : Primitive.class.getFields()) {
            if (Modifier.isStatic(constant.getModifiers()) && constant.getType() == int.class
                && !constant.getName().equals("BYTES")) {
                assertThat(field(PrimitiveConstants.class, constant.getName())).as(constant.getName())
                    .isEqualTo(constant.get(null));
            }
        }
    }

    private static Object field(Class<?> type, String name) {
        try {
            return type.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(type.getSimpleName() + " has no " + name, e);
        }
    }

    @Test
    void boxesFillBorderAndRoundTheirCorners() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            DrawList list = list(3, l -> {
                l.box(10.5f, 10, 60, 60, RED, 0, 0, 0, 0, 0, 0);
                l.box(80, 10, 180, 110, GREEN, 30, 0, 10, 0, 6, BLUE);
                l.gradientBox(190, 10, 250, 40, RED, BLUE, 0, 0, 0, 0, 0, 0, 0);
            });
            rig.frame(ui, null, list);
            assertThat(rig.at(30, 30)).as("inside: the fill, premultiplied").containsExactly(new float[] {1, 0, 0, 1},
                CLOSE);
            assertThat(rig.at(5, 30)).as("outside").containsExactly(new float[] {0, 0, 0, 0}, CLOSE);
            // The edge at x = 10.5 runs through pixel 10's centre: half covered; the pixels beside it not, and fully.
            assertThat(rig.at(10, 30, 3)).as("anti-aliased edge").isCloseTo(0.5f, within(0.05f));
            assertThat(rig.at(9, 30, 3)).isZero();
            assertThat(rig.at(11, 30, 3)).isEqualTo(1f, CLOSE);
            assertThat(rig.at(130, 60)).as("green inside the border").containsExactly(new float[] {0, 1, 0, 1}, CLOSE);
            assertThat(rig.at(83, 60)).as("the border").containsExactly(new float[] {0, 0, 1, 1}, CLOSE);
            assertThat(rig.at(176, 60)).as("the border on the right").containsExactly(new float[] {0, 0, 1, 1},
                CLOSE);
            assertThat(rig.at(82, 12, 3)).as("cut off by the 30 px corner").isZero();
            assertThat(rig.at(178, 12)).as("the sharp top right corner").containsExactly(new float[] {0, 0, 1, 1},
                CLOSE);
            assertThat(rig.at(178, 108, 3)).as("the 10 px bottom right corner").isZero();
            // Gradient left to right in linear light, premultiplied.
            assertThat(rig.at(190, 25, 0)).isGreaterThan(0.95f);
            assertThat(rig.at(249, 25, 2)).isGreaterThan(0.95f);
            assertThat(rig.at(219, 25)).as("half way").containsExactly(new float[] {0.5f, 0, 0.5f, 1},
                within(0.03f));
            rig.screenshot("boxes");
        }
    }

    @Test
    void aClipsOffsetMovesItsPrimitivesButNotItsRectangle() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            DrawList list = new DrawList();
            int clip = list.addClip(20, 20, 120, 120, 0);
            int start = list.allocate(2);
            list.begin(start, 2);
            list.clip(clip);
            list.box(10, 30, 50, 70, Rgba.pack(1, 0, 0, 1), 0, 0, 0, 0, 0, 0); // a scrolled region's content
            list.line(10, 100, 200, 100, 2, Rgba.pack(0, 1, 0, 1));
            list.end();
            rig.frame(ui, null, list);
            assertThat(rig.at(15, 50, 3)).as("left of the clip").isZero();
            assertThat(rig.at(30, 50)).containsExactly(new float[] {1, 0, 0, 1}, CLOSE);
            list.setClipOffset(clip, 40, -10); // scrolled: nothing else re-recorded
            rig.frame(ui, null, list);
            assertThat(ui.uploadedBytes()).as("only the clip table").isEqualTo(2L * DrawList.CLIP_FLOATS * 4);
            assertThat(rig.at(30, 50, 3)).as("moved away").isZero();
            assertThat(rig.at(70, 40)).as("drawn 40 right, 10 up").containsExactly(new float[] {1, 0, 0, 1}, CLOSE);
            assertThat(rig.at(91, 40, 3)).as("its right edge moved too: 50 + 40").isZero();
            assertThat(rig.at(88, 40, 3)).isEqualTo(1f, CLOSE);
            assertThat(rig.at(100, 90, 1)).as("the line moved up into the clip").isEqualTo(1f, CLOSE);
            assertThat(rig.at(130, 90, 1)).as("still cut by the clip's own rectangle").isZero();
            rig.screenshot("clip-offset");
        }
    }

    @Test
    void shadowsBlurByTheirSigma() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            long black = Rgba.pack(0, 0, 0, 0.8f);
            DrawList list = list(2, l -> {
                l.shadow(40, 30, 120, 90, black, 8, 8, 8, 8, 6);
                l.shadow(150, 30, 230, 90, black, 0, 0, 0, 0, 0);
            });
            rig.frame(ui, null, list);
            assertThat(rig.at(80, 60, 3)).as("well inside: its alpha").isCloseTo(0.8f, within(0.02f));
            assertThat(rig.at(40, 60, 3)).as("on the edge: half").isCloseTo(0.4f, within(0.05f));
            assertThat(rig.at(30, 60, 3)).as("a sigma and a half out: faint").isBetween(0.01f, 0.1f);
            assertThat(rig.at(21, 60, 3)).as("beyond three sigma: nothing").isLessThan(0.005f);
            assertThat(rig.at(42, 32, 3)).as("the rounded corner is softer than the side")
                .isLessThan(rig.at(42, 60, 3));
            assertThat(rig.at(190, 60, 3)).as("no blur: a hard box").isCloseTo(0.8f, within(0.01f));
            assertThat(rig.at(148, 60, 3)).isZero();
            rig.screenshot("shadows");
        }
    }

    @Test
    void glyphsDecodeTheMtsdfAtTheirPixelRange() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            UiTextures textures = new UiTextures();
            UiTexture disc = disc(textures, 64, 20, 8);
            // Drawn at twice the atlas size: the disc's radius is 40 px, the range 16 px on screen; the first centred on
            // pixel (64, 64)'s centre.
            DrawList list = list(3, l -> {
                l.glyph(0.5f, 0.5f, 128.5f, 128.5f, 0, 0, 1, 1, disc.id(), 16, Rgba.WHITE, 0, 0, 0);
                l.glyph(128, 0, 256, 128, 0, 0, 1, 1, disc.id(), 16, GREEN, 4, RED, 0);
            });
            rig.frame(ui, textures, list);
            assertThat(rig.at(64, 64)).containsExactly(new float[] {1, 1, 1, 1}, CLOSE);
            assertThat(rig.at(64 + 37, 64, 3)).as("3 px inside the edge").isGreaterThan(0.97f);
            assertThat(rig.at(64 + 43, 64, 3)).as("3 px outside").isLessThan(0.03f);
            assertThat(rig.at(64 + 40, 64, 3)).as("on the edge").isCloseTo(0.5f, within(0.15f));
            // The outlined one: green inside, a 4 px red ring outside the edge.
            assertThat(rig.at(192, 64)).containsExactly(new float[] {0, 1, 0, 1}, CLOSE);
            assertThat(rig.at(192 + 42, 64)).as("the outline").containsExactly(new float[] {1, 0, 0, 1},
                within(0.05f));
            assertThat(rig.at(192 + 47, 64, 3)).as("past the outline").isLessThan(0.03f);
            assertThat(ui.slot(disc.id())).isNotNegative();
            rig.screenshot("glyphs");
        }
    }

    @Test
    void boldGlyphsGrowByTheirWeight() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            UiTextures textures = new UiTextures();
            UiTexture disc = disc(textures, 64, 20, 8);
            DrawList list = list(1, l -> l.glyph(0, 0, 128, 128, 0, 0, 1, 1, disc.id(), 16, Rgba.WHITE, 0, 0, 3));
            rig.frame(ui, textures, list);
            assertThat(rig.at(64 + 42, 64, 3)).as("2 px outside the edge, inside the bold one").isGreaterThan(0.97f);
            assertThat(rig.at(64 + 46, 64, 3)).isLessThan(0.03f);
        }
    }

    @Test
    void outlinesAndWeightsStayWithinWhatTheAtlasEncodes() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            UiTextures textures = new UiTextures();
            UiTexture disc = disc(textures, 64, 20, 8);
            // At the atlas's size the range is 8 px: distances reach 4 px from the edge; a 10 px outline and a
            // weight of 10 would otherwise fill the whole quad from the clamped encoding.
            DrawList list = list(2, l -> {
                l.glyph(0, 0, 64, 64, 0, 0, 1, 1, disc.id(), 8, Rgba.WHITE, 10, RED, 0);
                l.glyph(64, 0, 128, 64, 0, 0, 1, 1, disc.id(), 8, Rgba.WHITE, 0, 0, 10);
            });
            rig.frame(ui, textures, list);
            assertThat(rig.at(2, 2, 3)).as("the outlined quad's corner").isZero();
            assertThat(rig.at(66, 2, 3)).as("the bold quad's corner").isZero();
            assertThat(rig.at(32 + 22, 32)).as("the outline, as wide as encoded").containsExactly(
                new float[] {1, 0, 0, 1}, within(0.05f));
        }
    }

    @Test
    void imagesAreSampledAsLinearAndNineSlicesKeepTheirCorners() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            UiTextures textures = new UiTextures();
            UiTexture gray = textures.create("gray", 2, 2, UiTexture.Format.RGBA8_SRGB);
            java.util.Arrays.fill(gray.pixels(), (byte) 188); // sRGB 188: linear 0.5, opaque
            for (int i = 3; i < 16; i += 4) {
                gray.pixels()[i] = (byte) 255;
            }
            gray.markDirty(0, 0, 2, 2);
            // A 12 x 12 skin: a 3-texel red frame around a green middle.
            UiTexture skin = textures.create("skin", 12, 12, UiTexture.Format.RGBA8_UNORM);
            byte[] p = skin.pixels();
            for (int y = 0; y < 12; y++) {
                for (int x = 0; x < 12; x++) {
                    boolean frame = x < 3 || y < 3 || x >= 9 || y >= 9;
                    int o = (y * 12 + x) * 4;
                    p[o] = (byte) (frame ? 255 : 0);
                    p[o + 1] = (byte) (frame ? 0 : 255);
                    p[o + 3] = (byte) 255;
                }
            }
            skin.markDirty(0, 0, 12, 12);
            DrawList list = list(3, l -> {
                l.image(10, 10, 50, 50, 0, 0, 1, 1, gray.id(), Rgba.pack(1, 1, 1, 0.5f));
                l.nineSlice(60, 10, 250, 120, 0, 0, 1, 1, skin.id(), Rgba.WHITE, 12, 12, 12, 12, 3, 3, 3, 3);
                l.image(10, 60, 50, 100, 0, 0, 0, 0, 0, BLUE);
            });
            rig.frame(ui, textures, list);
            assertThat(rig.at(30, 30)).as("sRGB 188 is linear 0.5; tinted to half alpha, premultiplied")
                .containsExactly(new float[] {0.25f, 0.25f, 0.25f, 0.5f}, within(0.01f));
            assertThat(rig.at(30, 80)).as("no texture: the tint").containsExactly(new float[] {0, 0, 1, 1}, CLOSE);
            assertThat(rig.at(65, 65)).as("the left side, 12 px wide").containsExactly(new float[] {1, 0, 0, 1},
                CLOSE);
            assertThat(rig.at(155, 15)).as("the top").containsExactly(new float[] {1, 0, 0, 1}, CLOSE);
            assertThat(rig.at(245, 115)).as("the corner").containsExactly(new float[] {1, 0, 0, 1}, CLOSE);
            assertThat(rig.at(155, 65)).as("the middle, stretched").containsExactly(new float[] {0, 1, 0, 1}, CLOSE);
            assertThat(rig.at(200, 65)).containsExactly(new float[] {0, 1, 0, 1}, CLOSE);
            rig.screenshot("images");
        }
    }

    @Test
    void linesAreAntiAliasedSegments() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            DrawList list = list(3, l -> {
                l.line(20, 20, 220, 20, 4, RED);
                l.line(20, 40, 120, 120, 2, GREEN);
                l.line(150, 60.5f, 240, 60.5f, 0.5f, Rgba.WHITE);
            });
            rig.frame(ui, null, list);
            assertThat(rig.at(120, 19)).containsExactly(new float[] {1, 0, 0, 1}, CLOSE);
            assertThat(rig.at(120, 21)).containsExactly(new float[] {1, 0, 0, 1}, CLOSE);
            assertThat(rig.at(120, 24, 3)).isZero();
            assertThat(rig.at(15, 20, 3)).as("butt ends").isZero();
            assertThat(rig.at(70, 80, 1)).as("on the diagonal").isGreaterThan(0.9f);
            assertThat(rig.at(80, 70, 3)).as("beside it").isZero();
            assertThat(rig.at(200, 60, 3)).as("a hair line: a pixel, fainter").isCloseTo(0.5f, within(0.05f));
            rig.screenshot("lines");
        }
    }

    @Test
    void primitivesAreClippedByRoundedRectangles() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            DrawList list = new DrawList();
            int sharp = list.addClip(20, 20, 100, 100, 0);
            int round = list.addClip(140, 20, 220, 100, 20);
            int start = list.allocate(4);
            list.begin(start, 4);
            list.clip(sharp);
            list.box(0, 0, 120, 120, RED, 0, 0, 0, 0, 0, 0);
            list.clip(round);
            list.box(130, 0, 250, 120, GREEN, 0, 0, 0, 0, 0, 0);
            list.line(130, 60, 250, 60, 4, BLUE);
            list.clip(DrawList.NO_CLIP);
            list.box(0, 110, 10, 120, BLUE, 0, 0, 0, 0, 0, 0);
            assertThat(list.end()).isTrue();
            rig.frame(ui, null, list);
            assertThat(rig.at(10, 60, 3)).as("left of the clip").isZero();
            assertThat(rig.at(60, 60)).containsExactly(new float[] {1, 0, 0, 1}, CLOSE);
            assertThat(rig.at(105, 60, 3)).as("right of it").isZero();
            assertThat(rig.at(142, 22, 3)).as("outside the clip's rounded corner").isZero();
            assertThat(rig.at(180, 22)).containsExactly(new float[] {0, 1, 0, 1}, CLOSE);
            assertThat(rig.at(180, 60)).as("the line over it").containsExactly(new float[] {0, 0, 1, 1}, CLOSE);
            assertThat(rig.at(135, 60, 3)).as("the line clipped too").isZero();
            assertThat(rig.at(5, 115)).as("unclipped").containsExactly(new float[] {0, 0, 1, 1}, CLOSE);
            // Moving a clip moves what it lets through, and uploads the clip table only.
            list.setClip(sharp, 20, 20, 50, 100, 0);
            rig.frame(ui, null, list);
            assertThat(ui.uploadedBytes()).isEqualTo(3L * DrawList.CLIP_FLOATS * 4);
            assertThat(rig.at(60, 60, 3)).isZero();
            assertThat(rig.at(40, 60, 0)).isEqualTo(1f, CLOSE);
            rig.screenshot("clipping");
        }
    }

    @Test
    void aTextureAnotherRendererUploadedFirstIsStillUploadedHere() {
        // UIs sharing a text system share its atlas pages: a world panel's renderer and the screen's upload the same
        // textures. The UI gallery lost glyphs when the panel's renderer took the page's dirty region every frame.
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph);
             GpuUiRenderer panel = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            UiTextures textures = new UiTextures();
            UiTexture page = textures.create("atlas 0", 4, 4, UiTexture.Format.RGBA8_UNORM);
            fill(page, 255, 0, 0);
            DrawList list = list(1, l -> l.image(10, 10, 50, 50, 0, 0, 1, 1, page.id(), Rgba.WHITE));
            rig.frame(ui, textures, list);
            assertThat(rig.at(30, 30)).containsExactly(new float[] {1, 0, 0, 1}, CLOSE);
            fill(page, 0, 255, 0); // a glyph installed: the other renderer's submit comes first this frame
            DrawList none = new DrawList();
            panel.submit(new DrawList[] {none}, 1, textures, W, H);
            rig.frame(ui, textures, list);
            assertThat(ui.uploadedBytes()).as("its own upload of the change").isEqualTo(4 * 4 * 4);
            assertThat(rig.at(30, 30)).as("the new pixels").containsExactly(new float[] {0, 1, 0, 1}, CLOSE);
            rig.frame(ui, textures, list);
            assertThat(ui.uploadedBytes()).as("then nothing").isZero();
        }
    }

    private static void fill(UiTexture texture, int r, int g, int b) {
        byte[] p = texture.pixels();
        for (int i = 0; i < p.length; i += 4) {
            p[i] = (byte) r;
            p[i + 1] = (byte) g;
            p[i + 2] = (byte) b;
            p[i + 3] = (byte) 255;
        }
        texture.markDirty(0, 0, texture.width(), texture.height());
    }

    @Test
    void anUnchangedFrameUploadsNothingAndChangesUploadTheirRange() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            UiTextures textures = new UiTextures();
            UiTexture disc = disc(textures, 32, 10, 4);
            DrawList list = new DrawList();
            int first = list.allocate(2);
            int second = list.allocate(2);
            list.begin(first, 2);
            list.box(10, 10, 50, 50, RED, 0, 0, 0, 0, 0, 0);
            list.end();
            list.begin(second, 2);
            list.glyph(60, 10, 92, 42, 0, 0, 1, 1, disc.id(), 4, Rgba.WHITE, 0, 0, 0);
            list.end();
            rig.frame(ui, textures, list);
            assertThat(ui.uploadedBytes()).as("all of it once: 4 primitives, a clip, a transform, the texture")
                .isEqualTo(4L * Primitive.BYTES + DrawList.CLIP_FLOATS * 4 + TRANSFORM_BYTES + 32 * 32 * 4);
            rig.frame(ui, textures, list);
            assertThat(ui.uploadedBytes()).as("nothing changed").isZero();
            // The UI records the same again: still nothing.
            list.begin(first, 2);
            list.box(10, 10, 50, 50, RED, 0, 0, 0, 0, 0, 0);
            list.end();
            rig.frame(ui, textures, list);
            assertThat(ui.uploadedBytes()).isZero();
            // One box changes colour: its 64 bytes.
            list.begin(first, 2);
            list.box(10, 10, 50, 50, BLUE, 0, 0, 0, 0, 0, 0);
            list.end();
            rig.frame(ui, textures, list);
            assertThat(ui.uploadedBytes()).isEqualTo(Primitive.BYTES);
            assertThat(rig.at(30, 30)).containsExactly(new float[] {0, 0, 1, 1}, CLOSE);
            // A texture region written: that region only.
            disc.markDirty(4, 4, 8, 2);
            rig.frame(ui, textures, list);
            assertThat(ui.uploadedBytes()).isEqualTo(8 * 2 * 4);
            assertThat(ui.totalUploadedBytes()).isEqualTo(4L * Primitive.BYTES + DrawList.CLIP_FLOATS * 4
                + TRANSFORM_BYTES + 32 * 32 * 4 + Primitive.BYTES + 8 * 2 * 4);
            // A primitive removed (an empty slot): drawn as nothing.
            list.begin(second, 2);
            list.end();
            rig.frame(ui, textures, list);
            assertThat(rig.at(76, 26, 3)).isZero();
            assertThat(rig.at(30, 30, 3)).isEqualTo(1f, CLOSE);
        }
    }

    @Test
    void aScaledAndMovedSubtreeDrawsAndClipsWhereItsTransformEntryPutsIt() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            DrawList list = new DrawList();
            int t = list.addTransform();
            int clip = list.addClip(10, 10, 50, 50, 0); // a clipping panel drawn by the transform, as its content
            list.setClipTransform(clip, t);
            int start = list.allocate(4);
            list.begin(start, 4);
            list.box(4, 104, 12, 112, BLUE, 0, 0, 0, 0, 0, 0); // untransformed, for reference
            list.useTransform(t);
            list.box(10, 10, 50, 50, GREEN, 0, 0, 0, 0, 0, 0); // the panel
            list.clip(clip);
            list.box(30, 30, 80, 80, RED, 0, 0, 0, 0, 2, BLUE); // its content, passing its edge
            assertThat(list.end()).isTrue();
            rig.frame(ui, null, list);
            assertThat(rig.at(20, 20)).as("identity: the panel").containsExactly(new float[] {0, 1, 0, 1}, CLOSE);
            assertThat(rig.at(40, 40)).as("the content").containsExactly(new float[] {1, 0, 0, 1}, CLOSE);
            assertThat(rig.at(60, 60, 3)).as("cut by the clip").isZero();
            list.setTransform(t, 2, 100, 0, 0.5f); // scaled twice about the origin, moved, half faded
            rig.frame(ui, null, list);
            assertThat(ui.uploadedBytes()).as("the entry alone").isEqualTo(TRANSFORM_BYTES);
            assertThat(rig.at(20, 20, 3)).as("moved away").isZero();
            assertThat(rig.at(130, 30)).as("the panel where it draws now, half faded")
                .containsExactly(new float[] {0, 0.5f, 0, 0.5f}, CLOSE);
            float[] over = {0.5f, 0.25f, 0, 0.75f}; // half red over half green
            assertThat(rig.at(170, 70)).as("the content scaled with it").containsExactly(over, CLOSE);
            assertThat(rig.at(190, 90)).as("still inside the clip, which scaled too").containsExactly(over, CLOSE);
            assertThat(rig.at(205, 90, 3)).as("cut where the clip draws: x 200").isZero();
            assertThat(rig.at(8, 108)).as("the untransformed box stays").containsExactly(new float[] {0, 0, 1, 1},
                CLOSE);
            rig.screenshot("transformed-clipped");
        }
    }

    @Test
    void trianglesAntiAliasTheirOutlineAndShareHardEdgesWithoutSeams() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            int all = Primitive.EDGE_AB | Primitive.EDGE_BC | Primitive.EDGE_CA;
            long half = Rgba.pack(0, 1, 0, 0.5f);
            DrawList list = list(3, l -> {
                l.triangle(10, 10, 110, 10, 10, 110, RED, all);
                // A translucent square of two triangles, wound opposite ways: the outline anti-aliased, the diagonal
                // (through pixel centres) hard and shared.
                l.triangle(130, 10, 230, 10, 230, 110, half, Primitive.EDGE_AB | Primitive.EDGE_BC);
                l.triangle(130, 10, 130, 110, 230, 110, half, Primitive.EDGE_AB | Primitive.EDGE_BC);
            });
            ui.batching(GpuUiRenderer.Batching.UBER);
            rig.frame(ui, null, list);
            assertThat(rig.at(30, 30)).containsExactly(new float[] {1, 0, 0, 1}, CLOSE);
            assertThat(rig.at(80, 80, 3)).as("past the long edge").isZero();
            assertThat(rig.at(5, 30, 3)).isZero();
            // Pixel (59, 60)'s centre lies on the edge x + y = 120: half covered.
            assertThat(rig.at(59, 60, 3)).as("on the anti-aliased edge").isCloseTo(0.5f, within(0.05f));
            assertThat(rig.at(61, 60, 3)).isLessThan(0.02f);
            assertThat(rig.at(57, 60, 3)).isGreaterThan(0.98f);
            for (int y = 12; y < 108; y++) {
                for (int x = 132; x < 228; x++) {
                    assertThat(rig.at(x, y)).as("one coverage at (%d, %d), on the diagonal too", x, y)
                        .containsExactly(new float[] {0, 0.5f, 0, 0.5f}, within(1e-3f));
                }
            }
            assertThat(rig.at(129, 60, 3)).as("left of the square").isLessThan(0.01f);
            float[] uber = rig.rgba.clone();
            ui.batching(GpuUiRenderer.Batching.PER_KIND);
            rig.frame(ui, null, list);
            assertThat(rig.rgba).as("triangleMain draws as the uber path").containsExactly(uber, within(1e-3f));
            rig.screenshot("triangles");
        }
    }

    @Test
    void aTrianglesCornersMoveByItsClipsOffsetAndTransformEntryAndItsClipCutsIt() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            DrawList list = new DrawList();
            int t = list.addTransform();
            int clip = list.addClip(10, 10, 30, 50, 0);
            list.setClipTransform(clip, t);
            list.setClipOffset(clip, 5, 0); // scrolled content: the corners move 5 right, the clip stays
            int start = list.allocate(1);
            list.begin(start, 1);
            list.useTransform(t);
            list.clip(clip);
            list.triangle(10, 10, 50, 10, 10, 50, RED, Primitive.EDGE_AB | Primitive.EDGE_BC | Primitive.EDGE_CA);
            assertThat(list.end()).isTrue();
            list.setTransform(t, 2, 100, 0, 1); // drawn at (130, 20), (210, 20), (130, 100), cut at x 120 to 160
            for (GpuUiRenderer.Batching batching : new GpuUiRenderer.Batching[] {GpuUiRenderer.Batching.UBER,
                GpuUiRenderer.Batching.PER_KIND}) {
                ui.batching(batching);
                rig.frame(ui, null, list);
                assertThat(rig.at(135, 30)).as(batching + ": inside").containsExactly(new float[] {1, 0, 0, 1}, CLOSE);
                assertThat(rig.at(125, 30, 3)).as("left of the moved corner, inside the clip").isZero();
                assertThat(rig.at(165, 30, 3)).as("inside the triangle, cut by the scaled clip").isZero();
                // The long edge draws at x + y = 230: pixel (149, 80)'s centre is on it; distances in drawn pixels.
                assertThat(rig.at(149, 80, 3)).as("on the edge").isCloseTo(0.5f, within(0.05f));
                assertThat(rig.at(150, 80, 3)).as("0.7 drawn pixels out").isLessThan(0.02f);
                assertThat(rig.at(148, 80, 3)).isGreaterThan(0.98f);
            }
            rig.screenshot("triangle-transformed-clipped");
        }
    }

    @Test
    void changesFarApartUploadApart() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            DrawList list = list(1000, l -> {
                for (int i = 0; i < 1000; i++) {
                    l.box(i % 50 * 5, i / 50 * 5, i % 50 * 5 + 4, i / 50 * 5 + 4, RED, 0, 0, 0, 0, 0, 0);
                }
            });
            rig.frame(ui, null, list);
            list.begin(10, 1);
            list.box(50, 0, 54, 4, BLUE, 0, 0, 0, 0, 0, 0);
            list.end();
            list.begin(990, 1);
            list.box(200, 95, 204, 99, BLUE, 0, 0, 0, 0, 0, 0);
            list.end();
            rig.frame(ui, null, list);
            assertThat(ui.uploadedBytes()).as("two primitives, not the 981 between them")
                .isEqualTo(2L * Primitive.BYTES);
            assertThat(rig.at(52, 2)).containsExactly(new float[] {0, 0, 1, 1}, CLOSE);
            assertThat(rig.at(202, 97)).containsExactly(new float[] {0, 0, 1, 1}, CLOSE);
        }
    }

    @Test
    void submittingAnUnchangedOrSlightlyChangedFrameAllocatesNothing() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            UiTextures textures = new UiTextures();
            disc(textures, 32, 10, 4);
            DrawList[] layers = {list(200, l -> {
                for (int i = 0; i < 100; i++) {
                    l.box(i, i, i + 10, i + 10, RED, 2, 2, 2, 2, 1, BLUE);
                    l.glyph(i, i, i + 8, i + 8, 0, 0, 1, 1, 1, 4, Rgba.WHITE, 0, 0, 0);
                }
            }), new DrawList()};
            rig.frame(ui, textures, layers);
            ui.batching(GpuUiRenderer.Batching.PER_KIND);
            float[] x = {0};
            int t = layers[1].addTransform();
            assertAllocationFree(() -> {
                ui.submit(layers, 2, textures, W, H);
                layers[1].setTransform(t, 1 + x[0], x[0], 0, 0.5f); // an animated entry
                // one box moves: the dirty range and the runs found again
                layers[0].begin(0, 200);
                for (int i = 0; i < 100; i++) {
                    layers[0].box(i + x[0], i, i + 10, i + 10, RED, 2, 2, 2, 2, 1, BLUE);
                    layers[0].glyph(i, i, i + 8, i + 8, 0, 0, 1, 1, 1, 4, Rgba.WHITE, 0, 0, 0);
                }
                layers[0].end();
                x[0] = x[0] == 0 ? 1 : 0;
                ui.submit(layers, 2, textures, W, H);
            });
        }
    }

    @Test
    void everyBatchingDrawsTheSameImage() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            UiTextures textures = new UiTextures();
            UiTexture disc = disc(textures, 32, 10, 4);
            // Mixed short runs, then a run of 300 glyphs (a block of text), then a box over them.
            DrawList list = list(400, l -> {
                for (int i = 0; i < 8; i++) {
                    float x = 10 + i * 30;
                    l.shadow(x + 2, 22, x + 24, 52, Rgba.pack(0, 0, 0, 0.5f), 4, 4, 4, 4, 3);
                    l.box(x, 20, x + 22, 50, Rgba.pack(0.2f, 0.3f, 0.8f, 1), 4, 4, 4, 4, 1, Rgba.WHITE);
                    l.glyph(x + 3, 25, x + 19, 41, 0, 0, 1, 1, disc.id(), 2, Rgba.WHITE, 0, 0, 0);
                    l.line(x, 60 + i * 5, x + 25, 70 + i * 5, 1.5f, GREEN);
                }
                for (int i = 0; i < 300; i++) {
                    l.glyph(4 + (i % 50) * 5, 80 + (i / 50) * 7, 10 + (i % 50) * 5, 86 + (i / 50) * 7, 0, 0, 1, 1,
                        disc.id(), 0.75f, Rgba.pack(1, 1, 0, 1), 0, 0, 0);
                }
                l.box(100, 90, 140, 110, Rgba.pack(1, 0, 1, 0.5f), 5, 5, 5, 5, 0, 0);
            });
            assertThat(ui.batching()).as("the default").isEqualTo(GpuUiRenderer.Batching.ADAPTIVE);
            ui.batching(GpuUiRenderer.Batching.UBER);
            rig.frame(ui, textures, list);
            float[] uber = rig.rgba.clone();
            assertThat(ui.draws()).isEqualTo(1);
            ui.batching(GpuUiRenderer.Batching.PER_KIND);
            rig.frame(ui, textures, list);
            assertThat(ui.draws()).as("a run per primitive, the text one run, the last box").isEqualTo(32 + 1 + 1);
            assertThat(rig.rgba).containsExactly(uber, within(1e-3f));
            ui.batching(GpuUiRenderer.Batching.ADAPTIVE);
            rig.frame(ui, textures, list);
            assertThat(ui.draws()).as("the short runs together, the text alone, the box").isEqualTo(3);
            assertThat(ui.uploadedBytes()).as("planning again uploads nothing").isZero();
            assertThat(rig.rgba).containsExactly(uber, within(1e-3f));
            rig.screenshot("batchings");
        }
    }

    @Test
    void aFullScreenBackdropIsClearedToInsteadOfDrawnWithTheSamePixels() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            long backdrop = Rgba.pack(0.2f, 0.4f, 0.6f, 0.6f);
            DrawList empty = new DrawList();
            DrawList list = list(4, l -> {
                l.box(-5, -5, W + 5, H + 5, backdrop, 0, 0, 0, 0, 0, 0); // a console's translucent backdrop
                l.box(20, 20, 60, 60, Rgba.pack(1, 0, 0, 0.5f), 4, 4, 4, 4, 0, 0);
                l.line(10, 100, 200, 100, 2, GREEN);
            });
            ui.foldBackdrop(false);
            rig.frame(ui, null, empty, list);
            float[] drawn = rig.rgba.clone();
            assertThat(ui.foldedBackdrop()).isEqualTo(-1);
            ui.foldBackdrop(true);
            rig.frame(ui, null, empty, list);
            assertThat(ui.foldedBackdrop()).as("layer 1, its first primitive; the empty layer below skipped")
                .isEqualTo(1L << 32);
            assertThat(rig.rgba).containsExactly(drawn, within(1e-3f));
            assertThat(rig.at(5, 5)).as("the backdrop, premultiplied").containsExactly(
                new float[] {0.12f, 0.24f, 0.36f, 0.6f}, within(2e-3f));
            // A panel with rounded corners and a border: cleared to its fill, its edges drawn over the clear.
            DrawList panel = list(2, l -> {
                l.box(0, 0, W, H, backdrop, 12, 4, 0, 20, 2, Rgba.pack(1, 1, 1, 0.8f));
                l.box(30, 30, 90, 90, RED, 0, 0, 0, 0, 0, 0);
            });
            ui.foldBackdrop(false);
            rig.frame(ui, null, panel);
            float[] drawnPanel = rig.rgba.clone();
            ui.foldBackdrop(true);
            rig.frame(ui, null, panel);
            assertThat(ui.foldedBackdrop()).isZero();
            assertThat(rig.rgba).containsExactly(drawnPanel, within(1e-3f));
            assertThat(rig.at(0, 0, 3)).as("outside the rounded corner").isZero();
            assertThat(rig.at(W - 1, H / 2, 3)).as("the border").isCloseTo(0.8f, within(0.02f));
            rig.screenshot("backdrop-panel-folded");
            // Not a backdrop: it leaves a pixel out, has a gradient, or something is drawn before it.
            DrawList narrow = list(1, l -> l.box(1, 0, W, H, backdrop, 0, 0, 0, 0, 0, 0));
            rig.frame(ui, null, narrow);
            assertThat(ui.foldedBackdrop()).isEqualTo(-1);
            assertThat(rig.at(0, 5, 3)).isZero();
            DrawList gradient = list(1, l -> l.gradientBox(0, 0, W, H, backdrop, RED, 0, 0, 0, 0, 0, 0, 0));
            rig.frame(ui, null, gradient);
            assertThat(ui.foldedBackdrop()).isEqualTo(-1);
            DrawList under = list(2, l -> {
                l.line(10, 10, 20, 20, 1, RED);
                l.box(0, 0, W, H, backdrop, 0, 0, 0, 0, 0, 0);
            });
            rig.frame(ui, null, under);
            assertThat(ui.foldedBackdrop()).isEqualTo(-1);
            // Faded by its transform entry: the clear is faded alike.
            DrawList faded = new DrawList();
            int t = faded.addTransform();
            faded.setTransform(t, 1, 0, 0, 0.5f);
            int start = faded.allocate(1);
            faded.begin(start, 1);
            faded.useTransform(t);
            faded.box(0, 0, W, H, backdrop, 0, 0, 0, 0, 0, 0);
            faded.end();
            rig.frame(ui, null, faded);
            assertThat(ui.foldedBackdrop()).isZero();
            assertThat(rig.at(100, 100)).containsExactly(new float[] {0.06f, 0.12f, 0.18f, 0.3f}, within(2e-3f));
            rig.screenshot("backdrop-folded");
        }
    }

    @Test
    void boxesDrawnFirstOverAFoldedPanelAreClearedWithTheSamePixels() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            long panel = Rgba.pack(0.05f, 0.07f, 0.1f, 0.86f);
            long plot = Rgba.pack(0.01f, 0.01f, 0.02f, 0.35f);
            // A chart: its panel (folded), its plot's translucent background, an opaque box over it, then the grid.
            DrawList chart = list(5, l -> {
                l.box(0, 0, W, H, panel, 6, 6, 6, 6, 1, Rgba.pack(0.3f, 0.3f, 0.3f, 1));
                l.box(20, 12, 240, 110, plot, 0, 0, 0, 0, 0, 0);
                l.box(40, 30, 60, 50, RED, 0, 0, 0, 0, 0, 0);
                l.line(20, 60.5f, 240, 60.5f, 1, Rgba.pack(0.5f, 0.5f, 0.5f, 0.2f));
                l.box(100, 20, 120, 40, Rgba.pack(0, 0, 1, 0.5f), 0, 0, 0, 0, 0, 0); // after the line: drawn
            });
            ui.foldBackdrop(false);
            rig.frame(ui, null, chart);
            float[] drawn = rig.rgba.clone();
            assertThat(ui.clearedBoxes()).isEmpty();
            ui.foldBackdrop(true);
            rig.frame(ui, null, chart);
            assertThat(ui.foldedBackdrop()).isZero();
            assertThat(ui.clearedBoxes()).as("the plot's background and the opaque box").containsExactly(1, 2);
            assertThat(rig.rgba).containsExactly(drawn, within(1e-3f));
            float under = 1 - 0.35f;
            assertThat(rig.at(30, 80)).as("the plot over the panel, premultiplied").containsExactly(new float[] {
                0.01f * 0.35f + 0.05f * 0.86f * under, 0.01f * 0.35f + 0.07f * 0.86f * under,
                0.02f * 0.35f + 0.1f * 0.86f * under, 0.35f + 0.86f * under}, within(2e-3f));
            rig.screenshot("backdrop-plot-cleared");
            // Drawn instead: translucent over the panel's corner, over a box cleared before, off whole pixels, rounded.
            DrawList corner = list(2, l -> {
                l.box(0, 0, W, H, panel, 6, 6, 6, 6, 1, Rgba.pack(0.3f, 0.3f, 0.3f, 1));
                l.box(2, 2, 40, 40, plot, 0, 0, 0, 0, 0, 0);
            });
            rig.frame(ui, null, corner);
            assertThat(ui.clearedBoxes()).isEmpty();
            DrawList stacked = list(3, l -> {
                l.box(0, 0, W, H, panel, 6, 6, 6, 6, 1, Rgba.pack(0.3f, 0.3f, 0.3f, 1));
                l.box(20, 20, 80, 80, plot, 0, 0, 0, 0, 0, 0);
                l.box(60, 60, 100, 100, plot, 0, 0, 0, 0, 0, 0);
            });
            ui.foldBackdrop(false);
            rig.frame(ui, null, stacked);
            float[] stackedDrawn = rig.rgba.clone();
            ui.foldBackdrop(true);
            rig.frame(ui, null, stacked);
            assertThat(ui.clearedBoxes()).as("the first; the second lies over it").containsExactly(1);
            assertThat(rig.rgba).containsExactly(stackedDrawn, within(1e-3f));
            DrawList fractional = list(2, l -> {
                l.box(0, 0, W, H, panel, 0, 0, 0, 0, 0, 0);
                l.box(20.5f, 20, 80, 80, RED, 0, 0, 0, 0, 0, 0);
            });
            ui.foldBackdrop(false);
            rig.frame(ui, null, fractional);
            float[] fractionalDrawn = rig.rgba.clone();
            ui.foldBackdrop(true);
            rig.frame(ui, null, fractional);
            assertThat(ui.clearedBoxes()).as("found, then drawn: off whole pixels").containsExactly(1);
            assertThat(rig.rgba).containsExactly(fractionalDrawn, within(1e-3f));
            DrawList rounded = list(2, l -> {
                l.box(0, 0, W, H, panel, 0, 0, 0, 0, 0, 0);
                l.box(20, 20, 80, 80, RED, 3, 3, 3, 3, 0, 0);
            });
            rig.frame(ui, null, rounded);
            assertThat(ui.clearedBoxes()).isEmpty();
            // Without a backdrop: opaque or over the image's transparency, the first boxes are cleared too.
            DrawList bare = list(2, l -> {
                l.box(10, 10, 50, 50, plot, 0, 0, 0, 0, 0, 0);
                l.box(30, 30, 70, 70, RED, 0, 0, 0, 0, 0, 0);
            });
            ui.foldBackdrop(false);
            rig.frame(ui, null, bare);
            float[] bareDrawn = rig.rgba.clone();
            ui.foldBackdrop(true);
            rig.frame(ui, null, bare);
            assertThat(ui.foldedBackdrop()).isEqualTo(-1);
            assertThat(ui.clearedBoxes()).containsExactly(0, 1);
            assertThat(rig.rgba).containsExactly(bareDrawn, within(1e-3f));
        }
    }

    @Test
    void aUiOfAnotherSizeIsScaledToTheView() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            DrawList list = list(1, l -> l.box(10, 10, 20, 20, RED, 0, 0, 0, 0, 0, 0));
            ui.submit(new DrawList[] {list}, 1, null, W / 2, H / 2); // the UI in half the pixels: drawn twice as large
            rig.frame();
            assertThat(rig.at(30, 30)).containsExactly(new float[] {1, 0, 0, 1}, CLOSE);
            assertThat(rig.at(42, 30, 3)).isZero();
            assertThat(rig.at(18, 30, 3)).isZero();
        }
    }
}
