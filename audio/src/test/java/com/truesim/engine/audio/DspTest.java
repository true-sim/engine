package com.truesim.engine.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.audio.dsp.Crackle;
import com.truesim.engine.audio.dsp.Delay;
import com.truesim.engine.audio.dsp.Envelope;
import com.truesim.engine.audio.dsp.Filter;
import com.truesim.engine.audio.dsp.FiringPulses;
import com.truesim.engine.audio.dsp.NodeParam;
import com.truesim.engine.audio.dsp.Noise;
import com.truesim.engine.audio.dsp.Oscillator;
import com.truesim.engine.audio.dsp.Patch;
import com.truesim.engine.audio.dsp.Patch.Signal;
import com.truesim.engine.audio.dsp.PatchVoice;
import com.truesim.engine.audio.dsp.Patches;
import com.truesim.engine.audio.dsp.RpmBank;
import com.truesim.engine.audio.dsp.Shaper;
import com.truesim.engine.audio.dsp.Tube;
import com.truesim.engine.audio.dsp.Vehicles;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.SoundParameter;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The DSP layer (§6 step 12): oscillator spectra, sample-accurate events, the V8's firing peak, a plugin's node. */
class DspTest {

    static final int RATE = 48_000;
    static final SoundParameter X = SoundParameter.of("x", 0, -10, 10_000);
    static final SoundParameter GO = SoundParameter.trigger("go");

    static Patches patches() {
        return new Patches(RATE, 256);
    }

    static float[] render(PatchVoice voice, int frames) {
        float[] out = new float[frames];
        for (int f = 0; f < frames; f += 1_024) {
            int n = Math.min(1_024, frames - f);
            voice.fill(f + n);
            for (int i = 0; i < n; i++) {
                out[f + i] = voice.sample(f + i);
            }
        }
        return out;
    }

    static PatchVoice voice(Consumer<Patch.Graph> graph) {
        return Patch.builder("t").input(X).trigger(GO).graph(graph).build().voice(RATE, 256, patches().nodes());
    }

    static float[] render(Consumer<Patch.Graph> graph, int frames) {
        return render(voice(graph), frames);
    }

    /** Power per bin of {@code x}'s last 65,536 samples, windowed by a 4-term Blackman-Harris (sidelobes -92 dB). */
    static double[] spectrum(float[] x) {
        int n = 65_536;
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            w[i] = x[x.length - n + i] * (float) (0.35875 - 0.48829 * Math.cos(t) + 0.14128 * Math.cos(2 * t)
                - 0.01168 * Math.cos(3 * t));
        }
        float[] re = new float[n / 2 + 1];
        float[] im = new float[n / 2 + 1];
        new Fft(n).forward(w, re, im);
        double[] p = new double[n / 2 + 1];
        for (int k = 0; k < p.length; k++) {
            p[k] = (double) re[k] * re[k] + (double) im[k] * im[k];
        }
        return p;
    }

    /** dB of the power away from the harmonics of {@code f} (more than 6 bins off), against all of it. */
    static double inharmonicDb(double[] p, double f) {
        return inharmonicDb(p, f, RATE / 2.0);
    }

    /** As {@link #inharmonicDb(double[], double)}, counting what is off the harmonics only below {@code upTo} Hz. */
    static double inharmonicDb(double[] p, double f, double upTo) {
        double binHz = (double) RATE / 65_536;
        double total = 0;
        double off = 0;
        for (int k = 1; k < p.length; k++) {
            double h = k * binHz / f;
            boolean near = Math.abs(h - Math.round(h)) * f / binHz <= 6;
            total += p[k];
            off += near || k * binHz > upTo ? 0 : p[k];
        }
        return 10 * Math.log10(off / total);
    }

    static double peakHz(double[] p, double fromHz, double toHz) {
        double binHz = (double) RATE / 65_536;
        int best = (int) (fromHz / binHz);
        for (int k = best; k <= toHz / binHz; k++) {
            if (p[k] > p[best]) {
                best = k;
            }
        }
        return best * binHz;
    }

    static double rms(float[] x, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) {
            s += x[i] * x[i];
        }
        return Math.sqrt(s / (to - from));
    }

    @Test
    void polyBlepKeepsTheSawsAliasingDown() {
        double f = 2_489.3;
        float[] blep = render(g -> g.output(g.add(Oscillator::saw).set("frequency", f)), 70_000);
        float[] naive = new float[70_000];
        for (int i = 0; i < naive.length; i++) {
            double p = i * f / RATE;
            naive[i] = (float) (2 * (p - Math.floor(p)) - 1);
        }
        // aliases below 10 kHz (where they are heard over the harmonics); the 2-point polyBLEP leaves more near Nyquist
        double withBlep = inharmonicDb(spectrum(blep), f, 10_000);
        double without = inharmonicDb(spectrum(naive), f, 10_000);
        assertThat(withBlep).as("aliasing of the polyBLEP saw below 10 kHz, dB").isLessThan(-48);
        assertThat(withBlep).as("better than the naive saw").isLessThan(without - 30);
        assertThat(inharmonicDb(spectrum(blep), f)).as("over the whole band").isLessThan(-25);
        float[] square = render(g -> g.output(g.add(Oscillator::square).set("frequency", f)), 70_000);
        double sq = inharmonicDb(spectrum(square), f, 10_000);
        float[] triangle = render(g -> g.output(g.add(Oscillator::triangle).set("frequency", f)), 70_000);
        double tri = inharmonicDb(spectrum(triangle), f, 10_000);
        assertThat(sq).isLessThan(-52);
        assertThat(tri).isLessThan(-60);
        assertThat(rms(triangle, 60_000, 70_000)).as("about a triangle's 1/sqrt(3)").isCloseTo(0.577, within(0.06));
    }

    @Test
    void theSineIsClean() {
        double f = 1_365.0 * RATE / 65_536; // on a bin: the window leaks nothing past its main lobe
        float[] sine = render(g -> g.output(g.add(Oscillator::sine).set("frequency", f)), 70_000);
        double[] p = spectrum(sine);
        assertThat(peakHz(p, 20, 20_000)).isCloseTo(f, within(1.0));
        assertThat(inharmonicDb(p, f)).isLessThan(-100);
        assertThat(rms(sine, 0, 48_000)).isCloseTo(Math.sqrt(0.5), within(1e-3));
    }

    @Test
    void eventsLandOnTheirSample() {
        PatchVoice v = voice(g -> g.output(g.param(X)));
        v.set(0, 1, 100, 0);
        v.set(0, 3, 300, 100); // linear: 1 to 3 over 100 frames from 300
        v.set(0, 2, 1_000, 0);
        float[] out = render(v, 2_048);
        assertThat(out[99]).isZero();
        assertThat(out[100]).isEqualTo(1);
        assertThat(out[300]).isCloseTo(1.02f, within(1e-5f));
        assertThat(out[349]).isCloseTo(2f, within(1e-4f));
        assertThat(out[399]).isEqualTo(3);
        assertThat(out[999]).isEqualTo(3);
        assertThat(out[1_000]).isEqualTo(2);
        assertThat(v.written()).isEqualTo(2_048);
        assertThat(v.sample(5_000)).as("not rendered yet").isZero();
        assertThat(v.defaultRamp()).isEqualTo(240);
    }

    @Test
    void triggersFireOnTheirSampleAndEnvelopesFollow() {
        PatchVoice v = voice(g -> g.output(g.add(Envelope::of).in(g.trigger(GO)).set("attack", 0).set("decay", 0.1)));
        v.trigger(1, 500);
        float[] out = render(v, 10_000);
        assertThat(out[499]).isZero();
        assertThat(out[500]).isEqualTo(1);
        assertThat(out[500 + 4_800]).as("e^-1 after the decay time").isCloseTo(0.368f, within(0.01f));
        v.trigger(1, 0); // passed: fires at the next frame rendered
        v.trigger(0, 0); // not a trigger: ignored
        v.fill(10_100);
        assertThat(v.sample(10_000)).isEqualTo(1);
    }

    @Test
    void theV8FiresAt200HzAt3000Rpm() {
        Patch v8 = Vehicles.v8();
        PatchVoice v = v8.voice(RATE, 256, patches().nodes());
        v.set(0, 3_000, 0, 0);
        v.set(1, 0.5f, 0, 0);
        float[] out = render(v, 2 * RATE);
        double[] p = spectrum(out);
        assertThat(peakHz(p, 60, 1_000)).as("the firing peak").isCloseTo(200, within(1.5));
        assertThat(rms(out, RATE, 2 * RATE)).isBetween(0.02, 0.9);
        v.set(0, 6_000, 0, 0);
        float[] high = render(v, 2 * RATE);
        assertThat(peakHz(spectrum(high), 300, 500)).isCloseTo(400, within(3.0));
        assertThat(v8.inputs()).containsExactly(Vehicles.RPM, Vehicles.LOAD, Vehicles.SHIFT);
        assertThat(v8.trigger(2)).isTrue();
        assertThat(v8.trigger(0)).isFalse();
    }

    @Test
    void theV8CracklesOnOverrunAndDipsOnAShift() {
        PatchVoice v = Vehicles.v8().voice(RATE, 256, patches().nodes());
        v.set(0, 5_000, 0, 0);
        v.set(1, -1, 0, 0);
        float[] overrun = render(v, RATE);
        v.reset();
        v.set(0, 5_000, 0, 0);
        v.set(1, -0.1f, 0, 0);
        float[] coasting = render(v, RATE);
        assertThat(peakOf(overrun)).as("pops").isGreaterThan(peakOf(coasting));
        v.reset();
        v.set(0, 3_000, 0, 0);
        v.set(1, 1, 0, 0);
        v.trigger(2, RATE / 2);
        float[] shift = render(v, RATE);
        assertThat(rms(shift, RATE / 2 + 480, RATE / 2 + 4_800)).isLessThan(rms(shift, RATE / 4, RATE / 2));
    }

    private static double peakOf(float[] x) {
        double p = 0;
        for (float s : x) {
            p = Math.max(p, Math.abs(s));
        }
        return p;
    }

    @Test
    void aPluginsNodeIsUsedByItsTypeName() {
        Patches patches = patches();
        patches.nodes().register(BitCrusher.TYPE);
        Patch crushed = Patch.builder("crushed").graph(g -> g.output(g.add("bitCrusher").set("bits", 2)
            .set("downsample", 4).in(g.add(Oscillator::sine).set("frequency", 100)))).build();
        patches.register(crushed);
        PatchVoice v = patches.take("crushed");
        float[] out = render(v, 4_800);
        for (int i = 0; i < out.length; i++) {
            assertThat(out[i] * 4).isCloseTo(Math.round(out[i] * 4), within(1e-6f));
            if (i % 4 != 0) {
                assertThat(out[i]).as("held for 4").isEqualTo(out[i - 1]);
            }
        }
        patches.give(v);
        assertThat(patches.take("crushed")).as("pooled, reset").isSameAs(v);
        assertThat(patches.take("crushed")).as("a new one when the pool is empty").isNotSameAs(v);
        assertThat(patches.take("nothing")).isNull();
        assertThat(patches.patch("crushed")).isSameAs(crushed);
        patches.register(Patch.builder("crushed").graph(g -> g.output(g.constant(0))).build());
        patches.give(v); // its patch was replaced: dropped
        assertThat(patches.take("crushed")).isNotSameAs(v);
        assertThatThrownBy(() -> patches.nodes().type("nothing")).hasMessageContaining("bitCrusher");
    }

    @Test
    void patchesAreAllocationFree() {
        PatchVoice v = Vehicles.v8().voice(RATE, 256, patches().nodes());
        v.set(0, 3_000, 0, 0);
        long[] at = {0};
        assertAllocationFree(() -> {
            at[0] += 256;
            v.set(0, 3_000 + (at[0] % 1_000), at[0], 240);
            v.trigger(2, at[0]);
            v.fill(at[0]);
        }, 20_000, 2_000, 10);
    }

    @Test
    void filtersMatchTheirAnalyticResponses() {
        // a low-pass at 1 kHz: -3 dB at the cutoff (Q 0.7071), about -12 dB an octave above double it
        assertThat(gainDb(Filter::lowPass, 1_000, 100)).isCloseTo(0, within(0.1));
        assertThat(gainDb(Filter::lowPass, 1_000, 1_000)).isCloseTo(-3.01, within(0.15));
        assertThat(gainDb(Filter::lowPass, 1_000, 8_000)).as("the bilinear transform's response")
            .isCloseTo(rbjLowPassDb(1_000, 0.7071, 8_000), within(0.2));
        assertThat(gainDb(Filter::highPass, 1_000, 1_000)).isCloseTo(-3.01, within(0.15));
        assertThat(gainDb(Filter::highPass, 1_000, 10_000)).isCloseTo(0, within(0.1));
        assertThat(gainDb(() -> Filter.bandPass(1_000, 2), 1_000, 1_000)).isCloseTo(0, within(0.1));
        assertThat(gainDb(() -> Filter.bandPass(1_000, 2), 1_000, 4_000)).isLessThan(-15);
        assertThat(gainDb(() -> Filter.notch(1_000, 2), 1_000, 1_000)).isLessThan(-40);
    }

    /** The RBJ low-pass's magnitude at {@code f}, dB: |H(e^jw)| from its coefficients. */
    private static double rbjLowPassDb(double f0, double q, double f) {
        double w0 = 2 * Math.PI * f0 / RATE;
        double cs = Math.cos(w0);
        double alpha = Math.sin(w0) / (2 * q);
        double[] b = {(1 - cs) / 2, 1 - cs, (1 - cs) / 2};
        double[] a = {1 + alpha, -2 * cs, 1 - alpha};
        double w = 2 * Math.PI * f / RATE;
        double nr = b[0] + b[1] * Math.cos(w) + b[2] * Math.cos(2 * w);
        double ni = -b[1] * Math.sin(w) - b[2] * Math.sin(2 * w);
        double dr = a[0] + a[1] * Math.cos(w) + a[2] * Math.cos(2 * w);
        double di = -a[1] * Math.sin(w) - a[2] * Math.sin(2 * w);
        return 10 * Math.log10((nr * nr + ni * ni) / (dr * dr + di * di));
    }

    /** The lag in [{@code from}, {@code to}] frames where {@code x} best matches itself: its period. */
    private static int period(float[] x, int from, int to) {
        int best = from;
        double bestSum = -Double.MAX_VALUE;
        for (int lag = from; lag <= to; lag++) {
            double sum = 0;
            for (int i = x.length / 2; i < x.length - lag; i++) {
                sum += x[i] * x[i + lag];
            }
            if (sum > bestSum) {
                bestSum = sum;
                best = lag;
            }
        }
        return best;
    }

    private static double gainDb(java.util.function.Supplier<Filter> filter, double cutoff, double hz) {
        float[] out = render(g -> g.output(g.add(filter).set("cutoff", cutoff).in(g.add(Oscillator::sine)
            .set("frequency", hz))), RATE);
        return 20 * Math.log10(rms(out, RATE / 2, RATE) / Math.sqrt(0.5));
    }

    @Test
    void aFilterFollowsAMovingCutoff() {
        PatchVoice v = voice(g -> g.output(g.add(Filter::lowPass).set("cutoff", g.param(X).plus(200))
            .in(g.add(Noise::white))));
        v.set(0, 9_000, 0, 0);
        float[] open = render(v, RATE / 2);
        v.reset();
        v.set(0, 0, 0, 0);
        float[] closed = render(v, RATE / 2);
        assertThat(rms(open, 1_000, RATE / 2)).isGreaterThan(3 * rms(closed, 1_000, RATE / 2));
    }

    @Test
    void delaysAndTubesDelayBySamples() {
        float[] out = render(g -> g.output(g.add(() -> new Delay(0.1, 0.01, 0)).in(g.trigger(GO))), 2_000);
        assertThat(out[0]).isZero(); // nothing fired: silence
        PatchVoice v = voice(g -> g.output(g.add(() -> Delay.comb(0.01, 0.5)).in(g.trigger(GO))));
        v.trigger(1, 0);
        float[] comb = render(v, 2_000);
        assertThat(comb[480]).isCloseTo(1, within(1e-3f));
        assertThat(comb[960]).isCloseTo(0.5f, within(1e-3f));
        assertThat(comb[481]).isCloseTo(0, within(1e-3f));
        PatchVoice t = voice(g -> g.output(g.add(() -> Tube.of(1.715)).set("damping", 0).set("reflection", 0.5)
            .in(g.trigger(GO))));
        t.trigger(1, 0);
        float[] tube = render(t, 2_000);
        assertThat(tube[0]).isEqualTo(1);
        assertThat(tube[480]).as("back after 2 L / c, inverted").isCloseTo(-0.5f, within(0.03f));
        Delay d = new Delay(0.1, 0.01, 0);
        assertThat(d.params()).extracting(NodeParam::name).containsExactly("time", "feedback", "mix");
    }

    @Test
    void noiseIsSeededAndPinkFallsWithFrequency() {
        float[] a = render(g -> g.output(g.add(() -> Noise.white().seed(3))), 70_000);
        float[] b = render(g -> g.output(g.add(() -> Noise.white().seed(3))), 70_000);
        assertThat(a).isEqualTo(b);
        assertThat(rms(a, 0, 70_000)).isCloseTo(1 / Math.sqrt(3), within(0.01));
        double[] pink = spectrum(render(g -> g.output(g.add(Noise::pink)), 70_000));
        double low = band(pink, 200, 400);
        double high = band(pink, 3_200, 6_400);
        assertThat(10 * Math.log10(high / low)).as("4 octaves at -3 dB each, per octave band")
            .isCloseTo(0, within(2.0));
        double[] white = spectrum(a);
        assertThat(10 * Math.log10(band(white, 3_200, 6_400) / band(white, 200, 400))).isCloseTo(12, within(1.0));
    }

    /** Power in [{@code from}, {@code to}) Hz. */
    private static double band(double[] p, double from, double to) {
        double binHz = (double) RATE / 65_536;
        double s = 0;
        for (int k = (int) (from / binHz); k < to / binHz; k++) {
            s += p[k];
        }
        return s;
    }

    @Test
    void crackleShaperAndFiringPulses() {
        float[] none = render(g -> g.output(g.add(Crackle::of)), RATE);
        assertThat(peakOf(none)).isZero();
        float[] pops = render(g -> g.output(g.add(() -> Crackle.of().seed(2)).set("rate", 50)), RATE);
        assertThat(peakOf(pops)).isGreaterThan(0.1);
        float[] loud = render(g -> g.output(g.add(Shaper::of).set("drive", 20).in(g.add(Oscillator::sine))), RATE);
        assertThat(peakOf(loud)).isLessThanOrEqualTo(1.0).isGreaterThan(0.95);
        float[] quiet = render(g -> g.output(g.add(Shaper::of).set("drive", 0.1).in(g.add(Oscillator::sine))),
            RATE);
        assertThat(peakOf(quiet)).isCloseTo(0.1, within(0.005));
        float[] four = render(g -> g.output(g.add(() -> FiringPulses.fourStroke(4, FiringPulses.FiringOrder.EVEN)
            .jitter(0)).set("rpm", 1_200)), 2 * RATE);
        assertThat(period(four, 240, 4_800)).as("a four-cylinder fires 2 × rpm / 60: every 25 ms").isEqualTo(1_200);
        float[] flat = render(g -> g.output(g.add(() -> FiringPulses.fourStroke(8,
            FiringPulses.FiringOrder.FLAT_PLANE_V8)).set("rpm", 3_000)), 2 * RATE);
        assertThat(peakHz(spectrum(flat), 60, 1_000)).isCloseTo(200, within(1.5));
    }

    @Test
    void signalOperations() {
        PatchVoice v = voice(g -> {
            Signal x = g.param(X);
            g.output(g.mix(x.clamp(0, 2), x.below(1).times(10), Signal.when(x.below(0), g.constant(100),
                x.plus(x).times(x.gain(1)).remap(0, 1, 0, 1000)), x.envelope(0, 0).times(0), x.smooth(0)));
        });
        v.set(0, 3, 0, 0);
        assertThat(render(v, 256)[10]).isEqualTo(2 + 0 + 18_000 + 3);
        v.reset();
        v.set(0, -1, 0, 0);
        assertThat(render(v, 256)[10]).isEqualTo(0 + 10 + 100 - 1);
    }

    @Test
    void patchesAreCheckedWhenBuilt() {
        assertThatThrownBy(() -> Patch.builder("silent").graph(g -> g.constant(1)).build())
            .hasMessageContaining("no output");
        assertThatThrownBy(() -> Patch.builder("p").input(X).graph(g -> g.trigger(X)).build())
            .hasMessageContaining("Not a trigger");
        assertThatThrownBy(() -> Patch.builder("p").trigger(GO).graph(g -> g.param(GO)).build())
            .hasMessageContaining("Not an input");
        assertThatThrownBy(() -> Patch.builder("p").graph(g -> g.param(X)).build())
            .hasMessageContaining("Not an input");
        assertThatThrownBy(() -> Patch.builder("p").graph(g -> g.trigger(GO)).build())
            .hasMessageContaining("Not a trigger");
        assertThatThrownBy(() -> Patch.builder("loop").graph(g -> {
            Patch.Node f = g.add(Filter::lowPass);
            f.set("cutoff", f.plus(1000));
            g.output(f);
        }).build()).hasMessageContaining("cycle");
        assertThatThrownBy(() -> render(g -> g.output(g.add(Filter::lowPass).set("nothing", 1)), 1))
            .hasMessageContaining("Filter has no parameter nothing");
        PatchVoice later = voice(g -> {
            Patch.Node f = g.add(Filter::lowPass).set("q", 1).set("q", g.constant(1)).set("cutoff", g.constant(500))
                .set("cutoff", 20_000);
            g.output(f.in(g.add(Oscillator::sine).set("frequency", 1_000)));
        });
        assertThat(rms(render(later, RATE / 4), 2_000, RATE / 4)).as("the last setting wins").isCloseTo(
            Math.sqrt(0.5), within(0.02));
    }

    @Test
    void anRpmBankPlaysItsLoopsAtTheRpm() {
        AudioClip low = Clips.sine(RATE, 100, 0.5f, RATE); // recorded at 2,000 rpm
        AudioClip high = Clips.sine(RATE, 200, 0.5f, RATE); // at 4,000 rpm
        AudioClip off = Clips.sine(RATE, 300, 0.5f, RATE); // overrun, at 3,000
        PatchVoice v = voice(g -> g.output(g.add(() -> RpmBank.builder().on(4_000, high).on(2_000, low)
            .off(3_000, off).build()).set("rpm", 3_000).set("load", g.param(X).plus(-1))));
        v.set(0, 2, 0, 0); // load 1: on load only
        double[] on = spectrum(render(v, 70_000));
        assertThat(peakHz(on, 50, 1_000)).as("both loops at 150 Hz").isCloseTo(150, within(1.0));
        v.reset();
        float[] overrun = render(v, 70_000); // load -1: the off-load loop at its rpm
        assertThat(peakHz(spectrum(overrun), 50, 1_000)).isCloseTo(300, within(1.0));
        PatchVoice one = voice(g -> g.output(g.add(() -> RpmBank.builder().on(2_000, low).build())
            .set("rpm", 1_000)));
        assertThat(peakHz(spectrum(render(one, 70_000)), 20, 1_000)).isCloseTo(50, within(1.0));
        assertThatThrownBy(() -> RpmBank.builder().off(1_000, low).build()).hasMessageContaining("on-load");
    }
}
