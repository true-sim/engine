package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.OutputTransform;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.layout.EncodePush;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.HdrMetadata;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

/**
 * Tone mapping and encoding for each window's display, as a presenter's {@link OutputTransform}: the view's linear
 * scene color (a float format, e.g. {@code R16G16B16A16_SFLOAT}) is exposed, tone mapped and encoded for what the
 * window's display shows, SDR, HDR10 or scRGB, which the presenter tells per window and again when it changes (HDR
 * turned on or off, the window moved to another display). All of it reads the graph's settings when frames record, so
 * changing them takes effect at once, without a rebuild.
 *
 * <ul>
 *   <li>SDR: the {@link #SLOT tone curve} ({@code display.toneCurve}: {@code aces}, {@code reinhard} or
 *       {@code off}) maps scene color into 0 to 1, shown at the display's white.</li>
 *   <li>HDR: scene color 1.0 shows at {@link #PAPER_WHITE paper white}; the curve rolls brighter colors off towards
 *       the display's {@link #PEAK peak brightness}.</li>
 * </ul>
 *
 * <p>A view's {@link Keys#UI UI} (premultiplied, linear, 1.0 meaning paper white), where a technique provides one, is
 * composited after the tone curve, so it is neither exposed nor tone mapped: SDR shows
 * {@code toneMapped * (1 - a) + ui}, HDR {@code curveNits * (1 - a) + ui * paperWhite * uiBrightness} (UI brighter
 * than white, a glow, clipped at the peak), then encodes.
 *
 * <p>Each window reads the settings its presenter target gives it ({@code Presenter.Target#displaySettings}): the
 * graph's by default, or a view of them holding the window's own values, such as a paper white for a window on
 * another display ({@code EngineSettings#inheriting}: unset, it follows the graph's).
 *
 * <pre>{@code
 * presenter.outputTransform(new DisplayTransform());
 * graph.settings().set(DisplayTransform.EXPOSURE, 1.5f).set(PresentSettings.HDR, true);
 * }</pre>
 */
public final class DisplayTransform implements OutputTransform {

    /** Encodings, as encode.slang numbers them; its curves are {@link Curve}'s ordinals. */
    private static final int ENCODE_SDR = 0;
    private static final int ENCODE_HDR10 = 1;
    private static final int ENCODE_SCRGB = 2;
    /** The UI slot when the view has no UI (encode.slang's NO_UI). */
    private static final int NO_UI = -1;

    /** How scene color brighter than white is brought into what the display shows: encode.slang's curves. */
    public enum Curve {
        /** Clipped at white (SDR) or at the peak (HDR). */
        NONE,
        /** {@code c / (1 + c)}: soft, never reaching white. */
        REINHARD,
        /** A fit of the ACES film curve: contrast in the middle, a soft shoulder. */
        ACES
    }

    /**
     * The tone curve, a kind's id ({@code off}: {@link Curve#NONE}); read as frames record. The curves are the
     * shader's: a game's kind of this slot makes one of them.
     */
    public static final TechniqueSlot<Void, Curve> SLOT = TechniqueSlot.of("display.toneCurve", "aces", true,
        "how colors brighter than white are brought into what the display shows: off, reinhard, aces",
        techniques -> techniques.register(DisplayTransform.SLOT, DisplayTransform.REINHARD)
            .register(DisplayTransform.SLOT, DisplayTransform.ACES));

    /** A tone curve's tunables: none. */
    public record Shape() implements TechniqueConfig {
    }

    /** {@code c / (1 + c)}: soft, never reaching white. */
    public static final TechniqueKind<Shape, Void, Curve> REINHARD = TechniqueKind.of(SLOT, "reinhard", new Shape(),
        "c / (1 + c): soft, never reaching white", (context, config) -> Curve.REINHARD);
    /** A fit of the ACES film curve: contrast in the middle, a soft shoulder. The default. */
    public static final TechniqueKind<Shape, Void, Curve> ACES = TechniqueKind.of(SLOT, "aces", new Shape(),
        "a fit of the ACES film curve: contrast in the middle, a soft shoulder", (context, config) -> Curve.ACES);
    /** The tone curve's setting: {@link #SLOT}'s. */
    public static final Setting<String> CURVE = SLOT.setting();
    /** What scene color is multiplied by before the tone curve. */
    public static final Setting<Float> EXPOSURE = Setting.decimal("display.exposure", 1, 0.01f, 64,
        "what scene color is multiplied by before the tone curve");
    /** How bright scene color 1.0 (SDR white, UI) shows on an HDR display. */
    public static final Setting<Float> PAPER_WHITE = Setting.decimal("display.paperWhite", 200, 40, 1000,
        "how bright SDR white (scene color 1.0) shows on an HDR display, in nits");
    /** The HDR display's peak brightness, where the tone curve ends. */
    public static final Setting<Float> PEAK = Setting.decimal("display.peakBrightness", 1000, 80, 10000,
        "the HDR display's peak brightness, in nits");
    /** How bright the UI's white shows on an HDR display, relative to {@link #PAPER_WHITE paper white}. */
    public static final Setting<Float> UI_BRIGHTNESS = Setting.decimal("ui.brightness", 1, 0.25f, 4,
        "how bright the UI's white shows on an HDR display, relative to paper white");
    /** Every display setting. */
    public static final List<Setting<?>> ALL = List.of(CURVE, EXPOSURE, PAPER_WHITE, PEAK, UI_BRIGHTNESS);

    /** The tone curve {@code settings} choose. Allocates nothing. */
    public static Curve curve(EngineSettings settings) {
        TechniqueKind<?, Void, Curve> kind = SLOT.chosen(settings);
        return kind == null ? Curve.NONE : kind.create(null, null);
    }

    /** Chooses {@code curve} as the player's tone curve in {@code settings}. */
    public static EngineSettings curve(EngineSettings settings, Curve curve) {
        return SLOT.choose(settings, switch (curve) {
            case NONE -> null;
            case REINHARD -> REINHARD;
            case ACES -> ACES;
        });
    }

    @Override
    public Technique transform(Key<Image> source, Key<Image> target, DisplayEncoding encoding) {
        return new Encode(source, target, encoding, null);
    }

    @Override
    public Technique transform(Key<Image> source, Key<Image> target, DisplayEncoding encoding,
        EngineSettings settings) {
        return new Encode(source, target, encoding, settings);
    }

    /**
     * The content as the settings shape it: graded for the {@link #PEAK peak brightness}, which the tone curve rolls off
     * to (the brightest pixel too), frames averaging around {@link #PAPER_WHITE paper white}.
     */
    @Override
    @AllowAllocation("when settings change")
    public HdrMetadata metadata(EngineSettings settings) {
        float peak = settings.get(PEAK);
        return new HdrMetadata(peak, 0.0001f, peak, Math.min(peak, settings.get(PAPER_WHITE)));
    }

    /**
     * One window's transform: {@code source} encoded for {@code encoding} as {@code target}, reading {@code settings}
     * (null: the graph's).
     */
    record Encode(Key<Image> source, Key<Image> target, DisplayEncoding encoding, EngineSettings settings)
        implements Technique {

        Encode {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(encoding, "encoding");
        }

        @Override
        public List<Key<?>> provides() {
            return List.of(target);
        }

        @Override
        @AllowAllocation("building the graph, when it changes")
        public void build(GraphBuilder g) {
            Pipeline pipeline = g.computeProgram("shaders/present/encode.spv", "encodeMain", EncodePush.BYTES);
            Image scene = g.require(source);
            Image ui = g.optional(Keys.UI); // composited after the tone curve, where the view has one
            // At the size the view shows at, its scene color scaled up from what it rendered.
            ViewPort view = g.view();
            Image encoded = g.unscaled(g.create("encoded for " + encoding, ImageDesc.of(Format.R16G16B16A16_SFLOAT,
                view == null ? scene.width() : view.outputWidth(), view == null ? scene.height() : view.outputHeight())));
            EncodePush push = new EncodePush().width(encoded.width()).height(encoded.height())
                .encoding(switch (encoding) {
                    case SDR_SRGB -> ENCODE_SDR;
                    case HDR10_PQ -> ENCODE_HDR10;
                    case SCRGB_LINEAR -> ENCODE_SCRGB;
                });
            EngineSettings settings = this.settings == null ? g.settings() : this.settings;
            GraphBuilder.PassBuilder pass = g.pass("display transform, " + encoding).read(scene, Access.SAMPLED_COMPUTE)
                .write(encoded, Access.STORAGE_WRITE);
            if (ui != null) {
                pass.read(ui, Access.SAMPLED_COMPUTE);
            }
            pass.record(ctx -> encode(ctx, pipeline, push, settings, scene, ui, encoded));
            g.provide(target, encoded);
        }

        private static void encode(PassContext ctx, Pipeline pipeline, EncodePush push, EngineSettings settings,
            Image scene, Image ui, Image encoded) {
            push.source(ctx.sampled(scene)).target(ctx.storage(encoded)).sourceWidth(ctx.regionWidth(scene))
                .sourceHeight(ctx.regionHeight(scene)).curve(curve(settings).ordinal())
                .exposure(settings.get(EXPOSURE)).paperWhite(settings.get(PAPER_WHITE)).peak(settings.get(PEAK))
                .ui(ui == null ? NO_UI : ctx.sampled(ui)).uiBrightness(settings.get(UI_BRIGHTNESS));
            ctx.dispatch(pipeline, push, (encoded.width() + 7) / 8, (encoded.height() + 7) / 8, 1);
        }
    }
}
