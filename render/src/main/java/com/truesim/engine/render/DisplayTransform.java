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
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
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
 *   <li>SDR: the {@link #CURVE tone curve} maps scene color into 0 to 1, shown at the display's white.</li>
 *   <li>HDR: scene color 1.0 shows at {@link #PAPER_WHITE paper white}; the curve rolls brighter colors off towards
 *       the display's {@link #PEAK peak brightness}.</li>
 * </ul>
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

    /** How scene color brighter than white is brought into what the display shows. */
    public enum Curve {
        /** Clipped at white (SDR) or at the peak (HDR). */
        NONE,
        /** {@code c / (1 + c)}: soft, never reaching white. */
        REINHARD,
        /** A fit of the ACES film curve: contrast in the middle, a soft shoulder. */
        ACES
    }

    /** The tone curve. */
    public static final Setting<Curve> CURVE = Setting.choice("display.toneCurve", Curve.ACES,
        "how colors brighter than white are brought into what the display shows: NONE, REINHARD, ACES");
    /** What scene color is multiplied by before the tone curve. */
    public static final Setting<Float> EXPOSURE = Setting.decimal("display.exposure", 1, 0.01f, 64,
        "what scene color is multiplied by before the tone curve");
    /** How bright scene color 1.0 (SDR white, UI) shows on an HDR display. */
    public static final Setting<Float> PAPER_WHITE = Setting.decimal("display.paperWhite", 200, 40, 1000,
        "how bright SDR white (scene color 1.0) shows on an HDR display, in nits");
    /** The HDR display's peak brightness, where the tone curve ends. */
    public static final Setting<Float> PEAK = Setting.decimal("display.peakBrightness", 1000, 80, 10000,
        "the HDR display's peak brightness, in nits");
    /** Every display setting. */
    public static final List<Setting<?>> ALL = List.of(CURVE, EXPOSURE, PAPER_WHITE, PEAK);

    @Override
    public Technique transform(Key<Image> source, Key<Image> target, DisplayEncoding encoding) {
        return new Encode(source, target, encoding);
    }

    /** One window's transform: {@code source} encoded for {@code encoding} as {@code target}. */
    record Encode(Key<Image> source, Key<Image> target, DisplayEncoding encoding) implements Technique {

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
            EngineSettings settings = g.settings();
            g.pass("display transform, " + encoding).read(scene, Access.SAMPLED_COMPUTE)
                .write(encoded, Access.STORAGE_WRITE)
                .record(ctx -> encode(ctx, pipeline, push, settings, scene, encoded));
            g.provide(target, encoded);
        }

        private static void encode(PassContext ctx, Pipeline pipeline, EncodePush push, EngineSettings settings,
            Image scene, Image encoded) {
            push.source(ctx.sampled(scene)).target(ctx.storage(encoded)).sourceWidth(ctx.regionWidth(scene))
                .sourceHeight(ctx.regionHeight(scene)).curve(settings.get(CURVE).ordinal())
                .exposure(settings.get(EXPOSURE)).paperWhite(settings.get(PAPER_WHITE)).peak(settings.get(PEAK));
            ctx.dispatch(pipeline, push, (encoded.width() + 7) / 8, (encoded.height() + 7) / 8, 1);
        }
    }
}
