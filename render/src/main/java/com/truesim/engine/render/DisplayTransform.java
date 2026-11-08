package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
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
import com.truesim.engine.settings.TechniqueConfig.Tunable;
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
 *   <li>SDR: the {@link #SLOT tone curve} ({@code display.toneCurve}, one of {@link Curve}'s, each its published
 *       reference implementation) maps scene color into 0 to 1, shown at the display's white. Into an sRGB image the
 *       copy encodes it; into a UNORM image ({@link DisplayEncoding#SDR_UNORM}, where the surface offers no sRGB
 *       format) this pass encodes it by the exact sRGB curve.</li>
 *   <li>HDR: scene color 1.0 shows at {@link #PAPER_WHITE paper white}; the curve rolls brighter colors off towards
 *       the display's {@link #PEAK peak brightness} (PBR Neutral and GT by HDR forms of their own).</li>
 *   <li>Before the copy into 8 bits (SDR) or 10 (HDR10), a triangular dither of one step ({@link #DITHER}) against
 *       banding in smooth gradients; black and white stay exact.</li>
 * </ul>
 *
 * <p>Scene color is multiplied by the {@link #EXPOSURE exposure} first: {@code display.exposure}, or, where a technique
 * of the view provides {@link #EXPOSURE_INPUT} (auto exposure), the float that buffer holds, in its place.
 *
 * <p>{@link #displayEffects()} splits the transform in two techniques: tone mapping into {@link #DISPLAY_COLOR}, then
 * encoding it, so techniques of the view modifying {@code DISPLAY_COLOR} (at {@code Anchor.TONE_MAP}: film grain or a
 * color vision filter, say) run in display light between them.
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

    /**
     * A view's tone-mapped color, with {@link #displayEffects()}: display light in linear Rec. 709, 1 at SDR white
     * (paper white on HDR displays, brighter colors up to the peak's share of it), at the size the view shows at, in
     * 16-bit floats, without the UI. Techniques modifying it run after tone mapping, before the UI is composited and
     * the result encoded.
     */
    public static final Key<Image> DISPLAY_COLOR = Key.image("truesim.displayColor");

    /**
     * What scene color is multiplied by before the tone curve, where a technique of the view provides it (auto
     * exposure): a buffer whose first 4 bytes are the float, read by the GPU as the frame runs, in place of
     * {@link #EXPOSURE display.exposure}. Without one, the setting.
     */
    public static final Key<Buffer> EXPOSURE_INPUT = Key.buffer("truesim.exposure");

    /** Encodings, as encode.slang numbers them; its curves are {@link Curve}'s ordinals, its looks {@link Look}'s. */
    private static final int ENCODE_SDR = 0;
    private static final int ENCODE_HDR10 = 1;
    private static final int ENCODE_SCRGB = 2;
    private static final int ENCODE_SDR_UNORM = 3;
    /** encode.slang's stages: tone map and encode in one pass, only tone map, only encode. */
    private static final int STAGE_FUSED = 0;
    private static final int STAGE_TONE_MAP = 1;
    private static final int STAGE_ENCODE = 2;
    /** encode.slang's flag: the exposure is read through the push's pointer, not its value. */
    private static final int EXPOSURE_FROM_BUFFER = 1 << 16;
    /** The UI slot when the view has no UI (encode.slang's NO_UI). */
    private static final int NO_UI = -1;

    private final boolean split; // tone mapped into DISPLAY_COLOR, encoded apart

    /** The transform in one pass. */
    public DisplayTransform() {
        this(false);
    }

    private DisplayTransform(boolean split) {
        this.split = split;
    }

    /**
     * This transform in two passes, tone mapping into {@link #DISPLAY_COLOR} and encoding it, so techniques of the
     * view after tone mapping (modifying {@code DISPLAY_COLOR}) can run between them. Costs a pass and an image of the
     * view's output size; without such techniques it shows what one pass does (to 16-bit float precision).
     */
    public DisplayTransform displayEffects() {
        return new DisplayTransform(true);
    }

    /** Whether it runs in two passes, with {@link #DISPLAY_COLOR} between them ({@link #displayEffects()}). */
    public boolean hasDisplayEffects() {
        return split;
    }

    /**
     * How scene color brighter than white is brought into what the display shows: encode.slang's curves, each its
     * published reference implementation (tested against a port of it). In HDR each maps relative to the display's
     * peak, scene color 1.0 near paper white, except PBR Neutral and GT, which have HDR forms of their own.
     */
    public enum Curve {
        /** Clipped at white (SDR) or at the peak (HDR). */
        NONE,
        /** {@code c / (1 + c)} per channel: soft, never reaching white. */
        REINHARD,
        /** Narkowicz's fit of the ACES film curve: contrast in the middle, a soft shoulder; skews bright hues. */
        ACES,
        /** Reinhard's extended curve, {@code c (1 + c / w^2) / (1 + c)}: reaching white at its white point. */
        REINHARD_EXTENDED,
        /** Hill's fit of the ACES reference rendering and sRGB output transforms, through ACES' primaries. */
        ACES_FITTED,
        /**
         * Sobotka's AgX (Wrensch's minimal reference, with Blender's {@link Look looks}): bright saturated colors go to
         * white smoothly, without the hue skews of per-channel curves; a little desaturated.
         */
        AGX,
        /**
         * Khronos PBR Neutral: base colors below 0.76 shown as they are (a small offset in the blacks), highlights
         * compressed and desaturated. In HDR its shoulder rises to the peak, so what SDR shows unchanged HDR does too.
         */
        PBR_NEUTRAL,
        /**
         * Uchimura's Gran Turismo curve: a toe, a linear middle and an exponential shoulder; in HDR its maximum is the
         * peak over paper white (its own HDR form), the toe and middle as in SDR.
         */
        GT,
        /** Hable's Uncharted 2 filmic curve, his reference parameters (white 11.2, exposure bias 2). */
        HABLE
    }

    /** AgX's looks, as Blender's: none, punchy (more contrast and saturation) or golden (warm, less saturated). */
    public enum Look {
        BASE,
        PUNCHY,
        GOLDEN
    }

    /**
     * The tone curve, a kind's id ({@code off}: {@link Curve#NONE}); read as frames record. The curves are the
     * shader's: a game's kind of this slot makes one of them.
     */
    public static final TechniqueSlot<Void, Curve> SLOT = TechniqueSlot.of("display.toneCurve", "aces", true,
        "how colors brighter than white are brought into what the display shows: off, reinhard, aces, "
            + "reinhard_extended, aces_fitted, agx, pbr_neutral, gt, hable",
        techniques -> techniques.register(DisplayTransform.SLOT, DisplayTransform.REINHARD)
            .register(DisplayTransform.SLOT, DisplayTransform.ACES)
            .register(DisplayTransform.SLOT, DisplayTransform.REINHARD_EXTENDED)
            .register(DisplayTransform.SLOT, DisplayTransform.ACES_FITTED)
            .register(DisplayTransform.SLOT, DisplayTransform.AGX)
            .register(DisplayTransform.SLOT, DisplayTransform.PBR_NEUTRAL)
            .register(DisplayTransform.SLOT, DisplayTransform.GT)
            .register(DisplayTransform.SLOT, DisplayTransform.HABLE));

    /** A tone curve's tunables: none. */
    public record Shape() implements TechniqueConfig {
    }

    /**
     * The extended Reinhard curve's tunables.
     *
     * @param whitePoint the scene color it shows as white
     */
    public record Extended(
        @Tunable(value = "the scene color the extended Reinhard curve shows as white", min = 1, max = 100)
        float whitePoint) implements TechniqueConfig {
    }

    /**
     * AgX's tunables.
     *
     * @param look Blender's look, applied in AgX's log space: none, punchy or golden
     */
    public record Agx(@Tunable("AgX's look: base, punchy or golden") Look look) implements TechniqueConfig {
    }

    /** {@code c / (1 + c)}: soft, never reaching white. */
    public static final TechniqueKind<Shape, Void, Curve> REINHARD = TechniqueKind.of(SLOT, "reinhard", new Shape(),
        "c / (1 + c): soft, never reaching white", (context, config) -> Curve.REINHARD);
    /** A fit of the ACES film curve: contrast in the middle, a soft shoulder. The default. */
    public static final TechniqueKind<Shape, Void, Curve> ACES = TechniqueKind.of(SLOT, "aces", new Shape(),
        "a fit of the ACES film curve: contrast in the middle, a soft shoulder", (context, config) -> Curve.ACES);
    /** Reinhard's extended curve: reaching white at its white point. */
    public static final TechniqueKind<Extended, Void, Curve> REINHARD_EXTENDED = TechniqueKind.of(SLOT,
        "reinhard_extended", new Extended(4), "Reinhard's extended curve, reaching white at its white point",
        (context, config) -> Curve.REINHARD_EXTENDED);
    /** Hill's fit of the ACES reference rendering and output transforms. */
    public static final TechniqueKind<Shape, Void, Curve> ACES_FITTED = TechniqueKind.of(SLOT, "aces_fitted",
        new Shape(), "Hill's fit of the ACES reference rendering and sRGB output transforms",
        (context, config) -> Curve.ACES_FITTED);
    /** Sobotka's AgX: bright saturated colors go to white without hue skews. */
    public static final TechniqueKind<Agx, Void, Curve> AGX = TechniqueKind.of(SLOT, "agx", new Agx(Look.BASE),
        "Sobotka's AgX: bright saturated colors go to white smoothly, without hue skews",
        (context, config) -> Curve.AGX);
    /** Khronos PBR Neutral: base colors shown as authored, highlights compressed. */
    public static final TechniqueKind<Shape, Void, Curve> PBR_NEUTRAL = TechniqueKind.of(SLOT, "pbr_neutral",
        new Shape(), "Khronos PBR Neutral: base colors shown as authored, highlights compressed and desaturated",
        (context, config) -> Curve.PBR_NEUTRAL);
    /** Uchimura's Gran Turismo curve. */
    public static final TechniqueKind<Shape, Void, Curve> GT = TechniqueKind.of(SLOT, "gt", new Shape(),
        "Uchimura's Gran Turismo curve: a toe, a linear middle and an exponential shoulder",
        (context, config) -> Curve.GT);
    /** Hable's Uncharted 2 filmic curve. */
    public static final TechniqueKind<Shape, Void, Curve> HABLE = TechniqueKind.of(SLOT, "hable", new Shape(),
        "Hable's Uncharted 2 filmic curve", (context, config) -> Curve.HABLE);
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
    /**
     * Triangular dither of one step before the copy into 8 bits (SDR) or 10 (HDR10), against banding in smooth
     * gradients (skies, fog, bloom); black and white stay exact. scRGB's floats need none.
     */
    public static final Setting<Boolean> DITHER = Setting.bool("display.dither", true,
        "dither before quantizing to the display's 8 or 10 bits, against banding");
    /** Every display setting (the curves' tunables are their kinds', under {@code display.toneCurve.<kind>}). */
    public static final List<Setting<?>> ALL = List.of(CURVE, EXPOSURE, PAPER_WHITE, PEAK, UI_BRIGHTNESS, DITHER);

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
            case REINHARD_EXTENDED -> REINHARD_EXTENDED;
            case ACES_FITTED -> ACES_FITTED;
            case AGX -> AGX;
            case PBR_NEUTRAL -> PBR_NEUTRAL;
            case GT -> GT;
            case HABLE -> HABLE;
        });
    }

    /**
     * The transform in one technique.
     *
     * @throws IllegalStateException with {@link #displayEffects()}: that is two, {@link #transforms}
     */
    @Override
    public Technique transform(Key<Image> source, Key<Image> target, DisplayEncoding encoding) {
        return transform(source, target, encoding, null);
    }

    @Override
    public Technique transform(Key<Image> source, Key<Image> target, DisplayEncoding encoding,
        EngineSettings settings) {
        if (split) {
            throw new IllegalStateException("A transform with display effects is two techniques: transforms(...)");
        }
        return new Encode(source, target, encoding, settings, STAGE_FUSED);
    }

    /** The transform's techniques: one, or with {@link #displayEffects()} tone mapping and encoding. */
    @Override
    @AllowAllocation("when a window's display changes")
    public List<Technique> transforms(Key<Image> source, Key<Image> target, DisplayEncoding encoding,
        EngineSettings settings) {
        return split ? List.of(new Encode(source, DISPLAY_COLOR, encoding, settings, STAGE_TONE_MAP),
            new Encode(DISPLAY_COLOR, target, encoding, settings, STAGE_ENCODE))
            : List.of(transform(source, target, encoding, settings));
    }

    /** {@link #transforms(Key, Key, DisplayEncoding, EngineSettings)} reading the graph's settings. */
    @AllowAllocation("when a window's display changes")
    public List<Technique> transforms(Key<Image> source, Key<Image> target, DisplayEncoding encoding) {
        return transforms(source, target, encoding, null);
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
     * One window's transform, or a stage of it: {@code source} tone mapped and encoded for {@code encoding} as
     * {@code target} (fused), only tone mapped into display light, or only encoded from it, reading {@code settings}
     * (null: the graph's).
     */
    record Encode(Key<Image> source, Key<Image> target, DisplayEncoding encoding, EngineSettings settings, int stage)
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
            // The UI is composited after the tone curve, when encoding; the exposure is read when tone mapping
            Image ui = stage == STAGE_TONE_MAP ? null : g.optional(Keys.UI);
            Buffer exposure = stage == STAGE_ENCODE ? null : g.optional(EXPOSURE_INPUT);
            // At the size the view shows at, its scene color scaled up from what it rendered.
            ViewPort view = g.view();
            String what = stage == STAGE_TONE_MAP ? "tone mapped for " : "encoded for ";
            Image encoded = g.unscaled(g.create(what + encoding, ImageDesc.of(Format.R16G16B16A16_SFLOAT,
                view == null ? scene.width() : view.outputWidth(), view == null ? scene.height() : view.outputHeight())));
            int code = switch (encoding) {
                case SDR_SRGB -> ENCODE_SDR;
                case HDR10_PQ -> ENCODE_HDR10;
                case SCRGB_LINEAR -> ENCODE_SCRGB;
                case SDR_UNORM -> ENCODE_SDR_UNORM;
            };
            int steps = stage == STAGE_TONE_MAP ? 0 : switch (encoding) {
                case SDR_SRGB, SDR_UNORM -> 255; // 8-bit images
                case HDR10_PQ -> 1023;
                case SCRGB_LINEAR -> 0; // floats
            };
            EncodePush push = new EncodePush().width(encoded.width()).height(encoded.height());
            EngineSettings settings = this.settings == null ? g.settings() : this.settings;
            String name = switch (stage) {
                case STAGE_TONE_MAP -> "display transform, tone mapping for ";
                case STAGE_ENCODE -> "display transform, encoding for ";
                default -> "display transform, ";
            };
            int modes = code | stage << 12 | (exposure != null ? EXPOSURE_FROM_BUFFER : 0);
            GraphBuilder.PassBuilder pass = g.pass(name + encoding).read(scene, Access.SAMPLED_COMPUTE)
                .write(encoded, Access.STORAGE_WRITE);
            if (ui != null) {
                pass.read(ui, Access.SAMPLED_COMPUTE);
            }
            // Never a null address, even unread: lavapipe runs a load through a push constant's pointer in a branch
            // the dispatch skips, after an early return, and crashed on 0 here (docs/pitfalls.md, #456). Without a
            // technique's exposure, the shader gets a buffer of its own (zeroed, never read).
            Buffer exposureAt = exposure != null ? exposure
                : g.persistentBuffer("display exposure stand-in, " + name + encoding, 16);
            pass.read(exposureAt, BufferAccess.STORAGE_READ);
            pass.record(ctx -> encode(ctx, pipeline, push, settings, scene, ui, exposureAt, encoded, modes, steps));
            g.provide(target, encoded);
        }

        private static void encode(PassContext ctx, Pipeline pipeline, EncodePush push, EngineSettings settings,
            Image scene, Image ui, Buffer exposure, Image encoded, int modes, int steps) {
            Curve curve = curve(settings);
            int look = curve == Curve.AGX ? AGX.config(settings).look().ordinal() : 0;
            float whitePoint = curve == Curve.REINHARD_EXTENDED ? REINHARD_EXTENDED.config(settings).whitePoint() : 1;
            push.source(ctx.sampled(scene)).target(ctx.storage(encoded)).sourceWidth(ctx.regionWidth(scene))
                .sourceHeight(ctx.regionHeight(scene)).modes(modes | curve.ordinal() << 4 | look << 8)
                .exposure(settings.get(EXPOSURE)).exposureAt(ctx.address(exposure))
                .paperWhite(settings.get(PAPER_WHITE)).peak(settings.get(PEAK)).whitePoint(whitePoint)
                .ditherSteps(settings.get(DITHER) ? steps : 0).frame((int) ctx.frame())
                .ui(ui == null ? NO_UI : ctx.sampled(ui)).uiBrightness(settings.get(UI_BRIGHTNESS));
            ctx.dispatch(pipeline, push, (encoded.width() + 7) / 8, (encoded.height() + 7) / 8, 1);
        }
    }
}
