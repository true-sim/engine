package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

class DisplayTransformTest {

    private static final int SIZE = 8;
    private static final Key<Image> SCENE = Key.image("test.scene");
    private static final Key<Image> SHOWN = Key.image("test.shown");

    private VulkanInstance instance;
    private VulkanDevice device;
    private GpuImage target;
    private GpuBuffer pixels;

    @BeforeEach
    void start() {
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        pixels = GpuBuffer.hostVisible(device, SIZE * SIZE * 8L, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            pixels.close();
            target.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** The scene's linear color, {@code rgb} everywhere, in 16-bit floats. */
    private record Scene(float red, float green, float blue) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(SCENE);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.create("scene", ImageDesc.of(Format.R16G16B16A16_SFLOAT, SIZE, SIZE));
            g.pass("scene").write(image, Access.TRANSFER_DST)
                .record(ctx -> ctx.clear(image.whole(), red, green, blue, 1));
            g.provide(SCENE, image);
        }
    }

    /** A view's UI: premultiplied {@code rgba} everywhere, provided as {@link Keys#UI}. */
    private record Ui(float red, float green, float blue, float alpha) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(Keys.UI);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.create("ui", ImageDesc.of(Format.R16G16B16A16_SFLOAT, SIZE, SIZE));
            g.pass("ui").write(image, Access.TRANSFER_DST)
                .record(ctx -> ctx.clear(image.whole(), red, green, blue, alpha));
            g.provide(Keys.UI, image);
        }
    }

    /** The scope of the settings view the transform reads ({@code inheriting}), or null: the graph's settings. */
    private String window;
    /** The transform the views show through. */
    private DisplayTransform transform = new DisplayTransform();
    /** Techniques added to the view before the transform's (display effects, an exposure), or none. */
    private List<Technique> extra = List.of();

    /** The first pixel's red, green and blue after transforming scene color {@code rgb} for {@code encoding}. */
    private float[] shown(DisplayEncoding encoding, DisplayTransform.Curve curve, float... rgb) {
        return shown(encoding, curve, null, settings -> { }, rgb);
    }

    /**
     * The first pixel's red, green and blue after transforming scene color {@code rgb} with {@code ui} over it (or
     * none), the graph's settings changed by {@code settings}, dither off unless they turn it on.
     */
    private float[] shown(DisplayEncoding encoding, DisplayTransform.Curve curve, Ui ui,
        Consumer<EngineSettings> settings, float... rgb) {
        float[] all = pixels(encoding, Format.R16G16B16A16_SFLOAT, ui, s -> {
            DisplayTransform.curve(s, curve);
            s.set(DisplayTransform.DITHER, false);
            settings.accept(s);
        }, rgb);
        return new float[] {all[0], all[1], all[2]};
    }

    /**
     * Every pixel's red, green, blue and alpha after transforming scene color {@code rgb} (with {@code ui} over it, or
     * none) for {@code encoding}, into an image of {@code format}: 16-bit floats, or 8 bits a channel (as 0 to 255).
     */
    private float[] pixels(DisplayEncoding encoding, Format format, Ui ui, Consumer<EngineSettings> settings,
        float... rgb) {
        boolean floats = format == Format.R16G16B16A16_SFLOAT;
        try (FrameGraph graph = new FrameGraph(device, 2);
             GpuImage into = floats ? null : new GpuImage(device, SIZE, SIZE, format.vk(),
                 VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT)) {
            GpuImage image = floats ? target : into;
            settings.accept(graph.settings());
            ExternalImage out = new ExternalImage("shown", SIZE, SIZE, format,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(image.handle(), image.view());
            ViewPort view = graph.addView("main").size(SIZE, SIZE).add(new Scene(rgb[0], rgb[1], rgb[2]));
            if (ui != null) {
                view.add(ui);
            }
            for (Technique each : extra) {
                view.add(each);
            }
            List<Technique> transforms = window == null ? transform.transforms(SCENE, SHOWN, encoding)
                : transform.transforms(SCENE, SHOWN, encoding, graph.settings().inheriting(window));
            for (Technique each : transforms) {
                view.add(each);
            }
            view.output(SHOWN, out);
            device.submitAndWait(device.graphics(), commands -> {
                graph.record(commands);
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.imageExtent().set(SIZE, SIZE, 1);
                    vkCmdCopyImageToBuffer(commands, image.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        pixels.handle(), region);
                }
            });
            float[] color = new float[SIZE * SIZE * 4];
            for (int c = 0; c < color.length; c++) {
                color[c] = floats ? Float.float16ToFloat(pixels.memory().get(ValueLayout.JAVA_SHORT_UNALIGNED, 2L * c))
                    : pixels.memory().get(ValueLayout.JAVA_BYTE, c) & 0xff;
            }
            return color;
        }
    }

    /** SMPTE ST 2084 of {@code nits}, as the shader should compute it. */
    private static double pq(double nits) {
        double y = Math.pow(nits / 10000, 0.1593017578125);
        return Math.pow((0.8359375 + 18.8515625 * y) / (1 + 18.6875 * y), 78.84375);
    }

    @Test
    void sdrToneMapsIntoTheDisplaysRange() {
        assertThat(shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.NONE, 0.5f, 2, 0.25f))
            .as("clipped at white").containsExactly(new float[] {0.5f, 1, 0.25f}, within(2e-3f));
        assertThat(shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.REINHARD, 1, 3, 0))
            .containsExactly(new float[] {0.5f, 0.75f, 0}, within(2e-3f));
        float[] aces = shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.ACES, 0.18f, 1, 16);
        assertThat(aces[0]).as("middle gray stays darker than white").isBetween(0.1f, 0.3f);
        assertThat(aces[1]).as("white: below 1, the shoulder").isBetween(0.6f, 0.75f);
        assertThat(aces[2]).as("bright: near white, not beyond").isBetween(0.98f, 1f);
    }

    @Test
    void hdrShowsWhiteAtPaperWhiteAndEncodesItForTheDisplay() {
        // Paper white 200 nits, peak 1000 (the defaults); gray stays gray in Rec. 2020.
        float[] hdr10 = shown(DisplayEncoding.HDR10_PQ, DisplayTransform.Curve.NONE, 1, 1, 1);
        assertThat(hdr10[0]).as("SDR white at 200 nits, PQ-encoded").isCloseTo((float) pq(200), within(2e-3f));
        assertThat(hdr10[1]).isCloseTo(hdr10[0], within(2e-3f));
        float[] bright = shown(DisplayEncoding.HDR10_PQ, DisplayTransform.Curve.NONE, 10, 10, 10);
        assertThat(bright[0]).as("clipped at the peak").isCloseTo((float) pq(1000), within(2e-3f));
        float[] scrgb = shown(DisplayEncoding.SCRGB_LINEAR, DisplayTransform.Curve.NONE, 1, 0.5f, 0);
        assertThat(scrgb).as("200 nits over 80").containsExactly(new float[] {2.5f, 1.25f, 0}, within(5e-3f));
        float[] rolled = shown(DisplayEncoding.SCRGB_LINEAR, DisplayTransform.Curve.REINHARD, 5, 5, 5);
        assertThat(rolled[0]).as("five times white: rolled off below the peak").isCloseTo(1000 * 0.5f / 80,
            within(2e-2f));
        assertThat(DisplayTransform.ALL).containsExactly(DisplayTransform.CURVE, DisplayTransform.EXPOSURE,
            DisplayTransform.PAPER_WHITE, DisplayTransform.PEAK, DisplayTransform.UI_BRIGHTNESS,
            DisplayTransform.DITHER);
    }

    /** Nits of a PQ-encoded value: SMPTE ST 2084's inverse. */
    private static double nits(double encoded) {
        double p = Math.pow(encoded, 1 / 78.84375);
        return 10000 * Math.pow(Math.max(p - 0.8359375, 0) / (18.8515625 - 18.6875 * p), 1 / 0.1593017578125);
    }

    @Test
    void hdr10ShowsTheUisWhiteAtPaperWhite() {
        for (float paperWhite : new float[] {100, 203, 400}) {
            // Over a scene five times white: the UI covers it, whatever the curve made of it.
            float[] white = shown(DisplayEncoding.HDR10_PQ, DisplayTransform.Curve.ACES, new Ui(1, 1, 1, 1),
                s -> s.set(DisplayTransform.PAPER_WHITE, paperWhite), 5, 5, 5);
            for (float c : white) {
                assertThat(nits(c)).as("white UI at paper white " + paperWhite).isCloseTo(paperWhite,
                    within(paperWhite * 0.01));
            }
        }
        float[] brighter = shown(DisplayEncoding.HDR10_PQ, DisplayTransform.Curve.NONE, new Ui(1, 1, 1, 1),
            s -> s.set(DisplayTransform.UI_BRIGHTNESS, 1.5f), 0, 0, 0);
        assertThat(nits(brighter[0])).as("ui.brightness, relative to paper white").isCloseTo(300, within(3.0));
        float[] glow = shown(DisplayEncoding.HDR10_PQ, DisplayTransform.Curve.NONE, new Ui(20, 20, 20, 1),
            s -> { }, 0, 0, 0);
        assertThat(nits(glow[0])).as("HDR-intense UI clipped at the peak").isCloseTo(1000, within(10.0));
    }

    @Test
    void aWindowsOwnSettingsViewOverridesThePaperWhiteItOtherwiseFollows() {
        window = "window.second";
        try {
            float[] follows = shown(DisplayEncoding.HDR10_PQ, DisplayTransform.Curve.NONE, new Ui(1, 1, 1, 1),
                s -> s.set(DisplayTransform.PAPER_WHITE, 150f), 0, 0, 0);
            assertThat(nits(follows[0])).as("nothing of its own: the graph's").isCloseTo(150, within(1.5));
            float[] own = shown(DisplayEncoding.HDR10_PQ, DisplayTransform.Curve.NONE, new Ui(1, 1, 1, 1),
                s -> s.set(DisplayTransform.PAPER_WHITE, 150f).inheriting(window)
                    .set(DisplayTransform.PAPER_WHITE, 450f), 0, 0, 0);
            assertThat(nits(own[0])).as("its own").isCloseTo(450, within(4.5));
        } finally {
            window = null;
        }
    }

    @Test
    void sdrShowsTheUisColorsExactlyOverABrightScene() {
        float[] shown = shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.ACES, new Ui(0.25f, 0.5f, 0.75f, 1),
            s -> s.set(DisplayTransform.EXPOSURE, 4f), 16, 16, 16);
        assertThat(shown).as("not exposed, not tone mapped").containsExactly(new float[] {0.25f, 0.5f, 0.75f},
            within(1e-3f));
        float[] none = shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.NONE, new Ui(0, 0, 0, 0), s -> { },
            0.5f, 0.25f, 1);
        assertThat(none).as("a transparent UI leaves the scene").containsExactly(new float[] {0.5f, 0.25f, 1},
            within(1e-3f));
    }

    @Test
    void theUiBlendsByItsAlpha() {
        // Premultiplied red at half coverage over gray: 0.5 * (1 - 0.5) + 0.25.
        float[] sdr = shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.NONE, new Ui(0.25f, 0, 0, 0.5f),
            s -> { }, 0.5f, 0.5f, 0.5f);
        assertThat(sdr).containsExactly(new float[] {0.5f, 0.25f, 0.25f}, within(1e-3f));
        // scRGB, paper white 200 (2.5 x 80 nits): the scene's 200 nits halved, the UI's half white added.
        float[] scrgb = shown(DisplayEncoding.SCRGB_LINEAR, DisplayTransform.Curve.NONE, new Ui(0.5f, 0.5f, 0, 0.5f),
            s -> { }, 1, 1, 1);
        assertThat(scrgb).containsExactly(new float[] {2.5f, 2.5f, 1.25f}, within(5e-3f));
        float[] scrgbWhite = shown(DisplayEncoding.SCRGB_LINEAR, DisplayTransform.Curve.ACES, new Ui(1, 1, 1, 1),
            s -> s.set(DisplayTransform.PAPER_WHITE, 400f), 3, 0, 0);
        assertThat(scrgbWhite).as("400 nits over 80").containsExactly(new float[] {5, 5, 5}, within(5e-3f));
    }

    /** Colors each curve is checked at: grays from dark to far above white, and saturated bright colors. */
    private static final float[][] INPUTS = {
        {0.01f, 0.01f, 0.01f}, {0.18f, 0.18f, 0.18f}, {1, 1, 1}, {4, 4, 4}, {16, 16, 16},
        {0.18f, 1, 4}, {8, 0.5f, 0.05f}, {0.02f, 0.3f, 12}};

    private static double[] doubles(float[] rgb) {
        return new double[] {rgb[0], rgb[1], rgb[2]};
    }

    /** {@code curve} in SDR as its reference code computes it; look 0 base, 1 punchy, 2 golden. */
    private static double[] reference(DisplayTransform.Curve curve, double[] c, int look) {
        return switch (curve) {
            case NONE -> new double[] {Math.min(1, c[0]), Math.min(1, c[1]), Math.min(1, c[2])};
            case REINHARD -> ToneReference.reinhard(c);
            case ACES -> ToneReference.acesNarkowicz(c);
            case REINHARD_EXTENDED -> ToneReference.reinhardExtended(c, 4);
            case ACES_FITTED -> ToneReference.acesHill(c);
            case AGX -> ToneReference.agx(c, look);
            case PBR_NEUTRAL -> ToneReference.pbrNeutral(c, 1);
            case GT -> ToneReference.gt(c);
            case HABLE -> ToneReference.hable(c);
        };
    }

    @Test
    void everyCurveMatchesItsReferenceInSdr() {
        for (DisplayTransform.Curve curve : DisplayTransform.Curve.values()) {
            for (float[] input : INPUTS) {
                double[] expected = reference(curve, doubles(input), 0);
                float[] shown = shown(DisplayEncoding.SDR_SRGB, curve, input);
                for (int c = 0; c < 3; c++) {
                    assertThat((double) shown[c]).as(curve + " of " + Arrays.toString(input) + ", channel " + c)
                        .isCloseTo(expected[c], within(2e-3 + expected[c] * 2e-3));
                }
            }
        }
        for (DisplayTransform.Look look : DisplayTransform.Look.values()) {
            float[] input = {8, 0.5f, 0.05f};
            double[] expected = ToneReference.agx(doubles(input), look.ordinal());
            float[] shown = shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.AGX, null,
                s -> s.set(DisplayTransform.AGX.<DisplayTransform.Look>setting("look"), look), input);
            assertThat(shown).as("AgX, " + look).containsExactly(new float[] {(float) expected[0],
                (float) expected[1], (float) expected[2]}, within(3e-3f));
        }
        // Values the references fix by construction: Reinhard's white point, Hable's white (11.2 over the bias of 2),
        // GT's linear middle, PBR Neutral's unchanged base colors (minus its 0.04 offset).
        assertThat(shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.REINHARD_EXTENDED, null,
            s -> s.set(DisplayTransform.REINHARD_EXTENDED.<Float>setting("whitePoint"), 6f), 6, 3, 0))
            .containsExactly(new float[] {1, (float) (3 * (1 + 3 / 36.0) / 4), 0}, within(2e-3f));
        assertThat(shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.HABLE, 5.6f, 5.6f, 5.6f))
            .containsExactly(new float[] {1, 1, 1}, within(2e-3f));
        assertThat(shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.GT, 0.4f, 0.3f, 0.25f))
            .containsExactly(new float[] {0.4f, 0.3f, 0.25f}, within(1e-3f));
        assertThat(shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.PBR_NEUTRAL, 0.5f, 0.3f, 0.6f))
            .containsExactly(new float[] {0.46f, 0.26f, 0.56f}, within(1e-3f));
    }

    @Test
    void hdr10MapsEveryCurveRelativeToThePeakOrByItsOwnHdrForm() {
        // Paper white 200 nits, peak 1000: the range is 5.
        double range = 5;
        for (DisplayTransform.Curve curve : DisplayTransform.Curve.values()) {
            for (float[] input : INPUTS) {
                double[] c = doubles(input);
                double[] nits = new double[3];
                switch (curve) {
                    case PBR_NEUTRAL -> {
                        double[] mapped = ToneReference.pbrNeutral(c, range);
                        for (int i = 0; i < 3; i++) {
                            nits[i] = Math.min(mapped[i], range) * 200;
                        }
                    }
                    case GT -> {
                        for (int i = 0; i < 3; i++) {
                            nits[i] = Math.min(ToneReference.gt(c[i], range), range) * 200;
                        }
                    }
                    default -> {
                        double[] mapped = reference(curve, new double[] {c[0] / range, c[1] / range, c[2] / range}, 0);
                        for (int i = 0; i < 3; i++) {
                            nits[i] = mapped[i] * 1000;
                        }
                    }
                }
                double[] wide = ToneReference.rec2020(nits);
                float[] shown = shown(DisplayEncoding.HDR10_PQ, curve, input);
                for (int i = 0; i < 3; i++) {
                    assertThat((double) shown[i]).as(curve + " of " + Arrays.toString(input) + " in HDR10")
                        .isCloseTo(ToneReference.pq(wide[i]), within(2e-3));
                }
            }
        }
        // PBR Neutral's and GT's HDR forms keep what SDR shows of the middle: at paper white, as in SDR at white.
        float[] neutral = shown(DisplayEncoding.HDR10_PQ, DisplayTransform.Curve.PBR_NEUTRAL, 0.5f, 0.5f, 0.5f);
        assertThat(neutral[0]).isCloseTo((float) ToneReference.pq(0.46 * 200), within(2e-3f));
        float[] gt = shown(DisplayEncoding.HDR10_PQ, DisplayTransform.Curve.GT, 0.4f, 0.4f, 0.4f);
        assertThat(gt[0]).isCloseTo((float) ToneReference.pq(0.4 * 200), within(2e-3f));
    }

    @Test
    void theCurvesAreKindsOfTheSlotWithTheirTunables() {
        EngineSettings settings = new EngineSettings();
        for (DisplayTransform.Curve curve : DisplayTransform.Curve.values()) {
            assertThat(DisplayTransform.curve(DisplayTransform.curve(settings, curve))).isEqualTo(curve);
        }
        assertThat(DisplayTransform.curve(new EngineSettings().load(java.util.Map.of("display.toneCurve", "AGX"))))
            .as("saved by the enum's name").isEqualTo(DisplayTransform.Curve.AGX);
        assertThat(DisplayTransform.AGX.<DisplayTransform.Look>setting("look").name())
            .isEqualTo("display.toneCurve.agx.look");
        assertThat(DisplayTransform.REINHARD_EXTENDED.config(settings).whitePoint()).isEqualTo(4);
    }

    @Test
    void sdrIntoAUnormImageIsEncodedByTheExactSrgbCurve() {
        for (float v : new float[] {0, 0.001f, 0.0031308f, 0.01f, 0.18f, 0.5f, 0.9f, 1}) {
            float[] shown = shown(DisplayEncoding.SDR_UNORM, DisplayTransform.Curve.NONE, v, v, v);
            assertThat((double) shown[0]).as("sRGB of " + v).isCloseTo(ToneReference.srgb(v), within(1e-3));
        }
        // Into an sRGB image the copy encodes: the bytes are the curve's, rounded.
        for (float v : new float[] {0.002f, 0.05f, 0.18f, 0.6f}) {
            float[] bytes = pixels(DisplayEncoding.SDR_SRGB, Format.R8G8B8A8_SRGB, null, s -> DisplayTransform.curve(s,
                DisplayTransform.Curve.NONE).set(DisplayTransform.DITHER, false), v, v, v);
            assertThat((double) bytes[0]).as("sRGB byte of " + v).isCloseTo(ToneReference.srgb(v) * 255, within(0.6));
        }
    }

    @Test
    void ditherSpreadsAValueBetweenStepsOverBothAndKeepsItsMean() {
        // Between two 8-bit steps: 100.4 / 255 encoded.
        double encoded = 100.4 / 255;
        float v = (float) Math.pow((encoded + 0.055) / 1.055, 2.4);
        for (boolean dither : new boolean[] {false, true}) {
            float[] bytes = pixels(DisplayEncoding.SDR_SRGB, Format.R8G8B8A8_SRGB, null, s -> DisplayTransform.curve(s,
                DisplayTransform.Curve.NONE).set(DisplayTransform.DITHER, dither), v, v, v);
            Set<Float> seen = new TreeSet<>();
            double mean = 0;
            for (int i = 0; i < bytes.length; i += 4) {
                seen.add(bytes[i]);
                mean += bytes[i];
            }
            mean /= SIZE * SIZE;
            if (dither) {
                assertThat(seen).as("dithered: neighbouring steps").hasSizeGreaterThan(1)
                    .allMatch(b -> b >= 99 && b <= 102);
                assertThat(mean).as("the mean kept").isCloseTo(100.4, within(0.3));
            } else {
                assertThat(seen).as("not dithered: one step").containsExactly(100f);
            }
        }
        // Black and white stay exact.
        float[] black = pixels(DisplayEncoding.SDR_SRGB, Format.R8G8B8A8_SRGB, null, s -> DisplayTransform.curve(s,
            DisplayTransform.Curve.NONE), 0, 1, 0);
        for (int i = 0; i < black.length; i += 4) {
            assertThat(black[i]).isZero();
            assertThat(black[i + 1]).isEqualTo(255);
        }
        // HDR10 dithers in its 10-bit steps, scRGB's floats not at all.
        float[] hdr = pixels(DisplayEncoding.HDR10_PQ, Format.R16G16B16A16_SFLOAT, null, s -> DisplayTransform.curve(s,
            DisplayTransform.Curve.NONE), 0.3f, 0.3f, 0.3f);
        float[] scrgb = pixels(DisplayEncoding.SCRGB_LINEAR, Format.R16G16B16A16_SFLOAT, null,
            s -> DisplayTransform.curve(s, DisplayTransform.Curve.NONE), 0.3f, 0.3f, 0.3f);
        float hdrLeast = Float.MAX_VALUE;
        float hdrMost = 0;
        for (int i = 0; i < hdr.length; i += 4) {
            hdrLeast = Math.min(hdrLeast, hdr[i]);
            hdrMost = Math.max(hdrMost, hdr[i]);
            assertThat(scrgb[i]).isEqualTo(scrgb[0]);
        }
        assertThat(hdrMost - hdrLeast).as("about two 10-bit steps").isBetween(0.5f / 1023, 2.2f / 1023);
    }

    /** A display effect: multiplies the view's display color by {@code factor}, in place, after tone mapping. */
    private record Dim(float factor) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(DisplayTransform.DISPLAY_COLOR);
        }

        @Override
        public com.truesim.engine.graph.Order order() {
            return com.truesim.engine.graph.Order.at(com.truesim.engine.graph.Anchor.TONE_MAP);
        }

        @Override
        public void build(GraphBuilder g) {
            Image display = g.modify(DisplayTransform.DISPLAY_COLOR);
            Image dimmed = g.create("dimmed", ImageDesc.like(display, com.truesim.engine.graph.Ratio.ONE));
            // A new version of the key: what the encoding reads. (Cleared to a constant: the factor of white.)
            g.pass("dim").read(display, Access.SAMPLED_COMPUTE).write(dimmed, Access.TRANSFER_DST)
                .record(ctx -> ctx.clear(dimmed.whole(), factor, factor, factor, 1));
            g.provide(DisplayTransform.DISPLAY_COLOR, dimmed);
        }
    }

    @Test
    void displayEffectsSplitTheTransformWithoutChangingIt() {
        for (DisplayEncoding encoding : DisplayEncoding.values()) {
            for (DisplayTransform.Curve curve : new DisplayTransform.Curve[] {DisplayTransform.Curve.ACES,
                DisplayTransform.Curve.AGX, DisplayTransform.Curve.GT}) {
                transform = new DisplayTransform();
                float[] fused = shown(encoding, curve, new Ui(0.1f, 0, 0, 0.25f), s -> { }, 3, 0.4f, 0.05f);
                transform = new DisplayTransform().displayEffects();
                float[] split = shown(encoding, curve, new Ui(0.1f, 0, 0, 0.25f), s -> { }, 3, 0.4f, 0.05f);
                for (int c = 0; c < 3; c++) {
                    // (the display color between the passes is in 16-bit floats, as the result: twice their step)
                    assertThat(split[c]).as(encoding + ", " + curve + ", channel " + c).isCloseTo(fused[c],
                        within(Math.max(2e-3f, fused[c] * 2e-3f)));
                }
            }
        }
        transform = new DisplayTransform();
        assertThat(new DisplayTransform().displayEffects().transforms(SCENE, SHOWN, DisplayEncoding.SDR_SRGB))
            .hasSize(2);
        assertThat(new DisplayTransform().transforms(SCENE, SHOWN, DisplayEncoding.SDR_SRGB)).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DisplayTransform().displayEffects()
            .transform(SCENE, SHOWN, DisplayEncoding.SDR_SRGB)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void techniquesModifyTheDisplayColorBetweenToneMappingAndTheUi() {
        transform = new DisplayTransform().displayEffects();
        extra = List.of(new Dim(0.25f));
        try {
            // SDR: the effect's display color shown as it is, not tone mapped again; the UI composited over it.
            assertThat(shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.ACES, new Ui(0.5f, 0, 0, 0.5f),
                s -> { }, 16, 16, 16)).containsExactly(new float[] {0.25f * 0.5f + 0.5f, 0.125f, 0.125f},
                within(2e-3f));
            // HDR: display units, 1 at paper white.
            float[] hdr = shown(DisplayEncoding.SCRGB_LINEAR, DisplayTransform.Curve.ACES, null, s -> { }, 16, 16, 16);
            assertThat(hdr[0]).as("a quarter of paper white, over 80 nits").isCloseTo(0.25f * 200 / 80, within(5e-3f));
        } finally {
            extra = List.of();
            transform = new DisplayTransform();
        }
    }

    /** Provides {@link DisplayTransform#EXPOSURE_INPUT}: a buffer holding {@code exposure}, written each frame. */
    private record Exposure(float exposure) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(DisplayTransform.EXPOSURE_INPUT);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer buffer = g.createBuffer("exposure", 16);
            g.pass("exposure").write(buffer, BufferAccess.TRANSFER_DST)
                .record(ctx -> ctx.fill(buffer, 0, 4, Float.floatToRawIntBits(exposure)));
            g.provide(DisplayTransform.EXPOSURE_INPUT, buffer);
        }
    }

    @Test
    void anExposureATechniqueProvidesTakesTheSettingsPlace() {
        assertThat(shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.NONE, null,
            s -> s.set(DisplayTransform.EXPOSURE, 2f), 0.25f, 0.1f, 0)).as("the setting")
            .containsExactly(new float[] {0.5f, 0.2f, 0}, within(2e-3f));
        for (DisplayTransform each : new DisplayTransform[] {new DisplayTransform(),
            new DisplayTransform().displayEffects()}) {
            transform = each;
            extra = List.of(new Exposure(3));
            try {
                assertThat(shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.NONE, null,
                    s -> s.set(DisplayTransform.EXPOSURE, 2f), 0.25f, 0.1f, 0)).as("the technique's, in its place")
                    .containsExactly(new float[] {0.75f, 0.3f, 0}, within(2e-3f));
            } finally {
                extra = List.of();
                transform = new DisplayTransform();
            }
        }
    }

    @Test
    void hdrDisplaysAreToldThePeakAndPaperWhite() {
        com.truesim.engine.settings.EngineSettings settings = new com.truesim.engine.settings.EngineSettings();
        assertThat(new DisplayTransform().metadata(settings))
            .isEqualTo(new com.truesim.engine.vulkan.HdrMetadata(1000, 0.0001f, 1000, 200));
        settings.set(DisplayTransform.PEAK, 400f).set(DisplayTransform.PAPER_WHITE, 600f);
        assertThat(new DisplayTransform().metadata(settings).maxFrameAverageLight()).as("never above the peak")
            .isEqualTo(400);
    }
}
