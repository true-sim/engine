package com.truesim.engine.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.scene.audio.Attenuation;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.Curve;
import com.truesim.scene.audio.HrtfSet;
import org.junit.jupiter.api.Test;

/**
 * HRTF (§6 step 17): the build-time conversion, the prepared set (resampling, triangulation, lookup), and binaural
 * rendering: ITD and ILD of rendered impulses against the set at measured directions, no click while a source circles
 * the head, the cap with its panning fallback, partitioned convolution, the near field, allocation-free.
 */
class HrtfTest {

    static final int RATE = 48_000;
    static final int SPATIAL_HRTF = Voices.SPATIAL | Voices.HRTF;
    /** No distance gain: the curve flat at 1. */
    static final Attenuation FLAT = Attenuation.builder().distance(1, 1_000).curve(Curve.points(0, 1)).build();

    private static Hrtf builtIn;

    static synchronized Hrtf builtIn() {
        if (builtIn == null) {
            builtIn = Hrtf.builtIn(RATE);
        }
        return builtIn;
    }

    // ---------------------------------------------------------------- conversion

    @Test
    void conversionFindsTheInterauralDelayAndKeepsTheMagnitude() {
        // two directions; each ear an impulse response of a known delay and colour
        double[][] positions = {{0, 0, 1.5}, {90, 0, 1.5}, {270, 0, 1.5}, {0, 90, 1.5}};
        float[][] left = new float[4][];
        float[][] right = new float[4][];
        double[] itds = {0, -12.5, 12.5, 0}; // right minus left: a source on the left reaches the right ear late
        for (int i = 0; i < 4; i++) {
            left[i] = response(40 - 0.5 * itds[i], 0.6, 256);
            right[i] = response(40 + 0.5 * itds[i], 0.6, 256);
            for (int t = 0; t < 256; t++) {
                right[i][t] *= 0.5f; // quieter, not coloured otherwise: colour would shift the low-passed lag
            }
        }
        HrtfSet set = HrtfConversion.convert(RATE, positions, left, right, 128, 0, "synthetic");
        assertThat(set.count()).isEqualTo(4);
        assertThat(set.credit()).isEqualTo("synthetic");
        for (int i = 0; i < 4; i++) {
            assertThat((double) (set.delay(i, 1) - set.delay(i, 0))).as("ITD %s", i).isCloseTo(itds[i], within(0.3));
        }
        assertThat(set.direction(1, 0)).as("90° counter-clockwise is to the left").isCloseTo(-1, within(1e-6f));
        assertThat(set.direction(3, 1)).isCloseTo(1, within(1e-6f));
        assertThat(set.distance(0)).isEqualTo(1.5f);
        // the least delay is 0; the ears' mean energy 1/2
        float least = Float.MAX_VALUE;
        double energy = 0;
        for (int i = 0; i < 4; i++) {
            for (int ear = 0; ear < 2; ear++) {
                least = Math.min(least, set.delay(i, ear));
                for (int t = 0; t < 128; t++) {
                    energy += set.tap(i, ear, t) * set.tap(i, ear, t);
                }
            }
        }
        assertThat(least).isZero();
        assertThat(energy / 8).isCloseTo(0.5, within(1e-4));
        // minimum phase: the same magnitude, the energy at the front (the input's started at sample 40)
        float[] minimum = HrtfConversion.minimumPhase(left[0], 128);
        assertThat(magnitude(minimum, 0.1)).isCloseTo(magnitude(left[0], 0.1), within(0.01));
        assertThat(magnitude(minimum, 0.3)).isCloseTo(magnitude(left[0], 0.3), within(0.01));
        double front = 0;
        double all = 0;
        for (int t = 0; t < 128; t++) {
            all += minimum[t] * minimum[t];
            front += t < 8 ? minimum[t] * minimum[t] : 0;
        }
        assertThat(front / all).isGreaterThan(0.9);
    }

    /** An impulse at fractional sample {@code delay} (a windowed sinc), coloured by a one-pole low-pass. */
    private static float[] response(double delay, double pole, int length) {
        float[] r = new float[length];
        for (int n = 0; n < length; n++) {
            double x = n - delay;
            double w = Math.abs(x) < 16 ? 0.5 * (1 + Math.cos(Math.PI * x / 16)) : 0;
            r[n] = (float) ((x == 0 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x)) * w);
        }
        double y = 0;
        for (int n = 0; n < length; n++) {
            y = r[n] + pole * y;
            r[n] = (float) (y * (1 - pole));
        }
        return r;
    }

    /** |H| at {@code cycles} per sample (a DFT bin). */
    private static double magnitude(float[] h, double cycles) {
        double re = 0;
        double im = 0;
        for (int n = 0; n < h.length; n++) {
            re += h[n] * Math.cos(2 * Math.PI * cycles * n);
            im -= h[n] * Math.sin(2 * Math.PI * cycles * n);
        }
        return Math.sqrt(re * re + im * im);
    }

    @Test
    void denseSetsAreThinnedEvenly() {
        double[][] positions = new double[360][];
        for (int i = 0; i < 360; i++) {
            positions[i] = new double[] {i, (i % 3 - 1) * 30, 1};
        }
        int[] kept = HrtfConversion.spread(positions, 12);
        assertThat(kept).hasSize(12).isSorted();
        assertThat(kept[0]).isEqualTo(1); // straight ahead first (number 1: azimuth 1°, elevation 0)
        float[][] ir = new float[360][];
        for (int i = 0; i < 360; i++) {
            ir[i] = response(20, 0.2, 64);
        }
        assertThat(HrtfConversion.convert(RATE, positions, ir, ir, 32, 12, "x").count()).isEqualTo(12);
        assertThat(HrtfConversion.convert(RATE, positions, ir, ir, 32, 400, "x").count()).isEqualTo(360);
    }

    @Test
    void anImpulseAtTheStartAndAnItdAtTheSearchsEdge() {
        float[] early = new float[64];
        early[0] = 1;
        float[] late = new float[64];
        late[60] = 1; // 60 samples later: past the 1 ms search (48)
        assertThat(HrtfConversion.itd(early, late, RATE)).isEqualTo(48);
        assertThat(HrtfConversion.itd(late, early, RATE)).isEqualTo(-48);
        assertThat(HrtfConversion.itd(early, early, RATE)).isZero();
    }

    // ---------------------------------------------------------------- the prepared set

    @Test
    void theBuiltInSetIsTriangulatedAndEveryDirectionFindsItsTriangle() {
        Hrtf h = builtIn();
        assertThat(h.count()).isEqualTo(2_000);
        assertThat(h.taps()).isEqualTo(128);
        assertThat(h.rate()).isEqualTo(RATE);
        assertThat(h.credit()).contains("SADIE II").contains("Apache License 2.0").contains("10.3390/app8112029");
        assertThat(h.triangles()).as("a closed triangulated sphere: T = 2V - 4").isEqualTo(2 * 2_000 - 4);
        assertThat(h.maxDelay()).isBetween(30f, 45f);
        int[] at = new int[3];
        float[] w = new float[3];
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 5_000; i++) {
            double x = random.nextGaussian();
            double y = random.nextGaussian();
            double z = random.nextGaussian();
            h.locate(x, y, z, at, w);
            assertThat(w[0] + w[1] + w[2]).isCloseTo(1, within(1e-5f));
            assertThat(Math.min(w[0], Math.min(w[1], w[2]))).isGreaterThanOrEqualTo(0);
            // the weighted vertices point where asked
            double px = 0;
            double py = 0;
            double pz = 0;
            for (int k = 0; k < 3; k++) {
                px += w[k] * h.direction(at[k], 0);
                py += w[k] * h.direction(at[k], 1);
                pz += w[k] * h.direction(at[k], 2);
            }
            double cos = (px * x + py * y + pz * z) / Math.sqrt((px * px + py * py + pz * pz) * (x * x + y * y + z * z));
            assertThat(cos).isGreaterThan(0.99999);
        }
        // a measured direction is its own neighbour
        h.locate(h.direction(17, 0), h.direction(17, 1), h.direction(17, 2), at, w);
        int self = at[0] == 17 ? 0 : at[1] == 17 ? 1 : 2;
        assertThat(at[self]).isEqualTo(17);
        assertThat(w[self]).isCloseTo(1, within(1e-4f));
    }

    @Test
    void preparedForAnotherRateTheFiltersAndDelaysAreResampled() {
        HrtfSet set = HrtfSetsForTests.ring(RATE);
        Hrtf at48 = Hrtf.prepare(set, RATE);
        Hrtf at96 = Hrtf.prepare(set, 96_000);
        Hrtf at24 = Hrtf.prepare(set, 24_000);
        assertThat(at96.taps()).isEqualTo(2 * set.taps() + 34); // with room for the sinc's ringing ahead
        assertThat(at24.taps()).isEqualTo(set.taps() / 2 + 9);
        for (int i = 0; i < set.count(); i++) {
            assertThat(at96.delay(i, 0)).isCloseTo(2 * at48.delay(i, 0), within(1e-4f));
            assertThat(at24.delay(i, 1)).isCloseTo(0.5f * at48.delay(i, 1), within(1e-4f));
            // the low-frequency gain stays: the sum of the taps
            assertThat(sum(at96, i, 0)).isCloseTo(sum(at48, i, 0), within(0.02 * Math.abs(sum(at48, i, 0)) + 1e-3));
            assertThat(sum(at24, i, 0)).isCloseTo(sum(at48, i, 0), within(0.05 * Math.abs(sum(at48, i, 0)) + 1e-3));
        }
        assertThatThrownBy(() -> Hrtf.prepare(HrtfSet.of(RATE, 1, "flat", new float[] {1, 0, 0, 0, 0, 1, -1, 0, 0,
            0, 0, -1}, new float[] {1, 1, 1, 1}, new float[8], new float[8]), RATE)).hasMessageContaining("plane");
    }

    private static double sum(Hrtf h, int i, int ear) {
        double s = 0;
        for (int t = 0; t < h.taps(); t++) {
            s += h.tap(i, ear, t);
        }
        return s;
    }

    // ---------------------------------------------------------------- rendering

    /** A renderer with the built-in set through a spatializer of {@code cap} voices (and room to fade). */
    static AudioRenderer renderer(int block, int cap) {
        AudioRenderer r = new AudioRenderer(AudioFormat.stereo(RATE).block(block), 64);
        r.commands().spatializer(new HrtfSpatializer(builtIn(), block, cap + 4), cap);
        return r;
    }

    @Test
    void renderedImpulsesHaveTheSetsItdAndIldAtMeasuredDirections() {
        Hrtf h = builtIn();
        int[] directions = {0, 17, 250, 999, 1_500, 1_999};
        for (int i : directions) {
            float x = (float) (2 * h.direction(i, 0));
            float y = (float) (2 * h.direction(i, 1));
            float z = (float) (2 * h.direction(i, 2));
            float[] out;
            try (AudioRenderer r = renderer(256, 16)) {
                r.commands().play(0, Clips.mono(RATE, 0.1f), 1_024, 0, 1, 1, Bus.SFX, SPATIAL_HRTF, 0, 1);
                r.commands().spatial(0, x, y, z, 1, FLAT, false);
                out = r.render(4_096);
            }
            float[] left = new float[h.taps()];
            float[] right = new float[h.taps()];
            for (int t = 0; t < h.taps(); t++) {
                left[t] = h.tap(i, 0, t);
                right[t] = h.tap(i, 1, t);
            }
            double lagLeft = lag(out, 0, left);
            double lagRight = lag(out, 1, right);
            double itd = h.delay(i, 1) - h.delay(i, 0);
            assertThat(lagRight - lagLeft).as("ITD at %s", i).isCloseTo(itd, within(0.25));
            assertThat(lagLeft - 1_024).as("the left ear's delay").isCloseTo(h.delay(i, 0) + HrtfSpatializer.BASE_DELAY,
                within(0.25));
            double ild = 10 * Math.log10(energy(out, 0) / energy(out, 1));
            double expected = 10 * Math.log10(energy(left) / energy(right));
            assertThat(ild).as("ILD at %s", i).isCloseTo(expected, within(0.1));
        }
    }

    /** Where {@code filter} best matches ear {@code ear} of {@code out}: cross-correlation, parabola at the peak. */
    private static double lag(float[] out, int ear, float[] filter) {
        int frames = out.length / 2;
        double[] c = new double[frames];
        int best = 0;
        for (int lag = 0; lag < frames - filter.length; lag++) {
            double s = 0;
            for (int t = 0; t < filter.length; t++) {
                s += out[2 * (lag + t) + ear] * filter[t];
            }
            c[lag] = s;
            if (s > c[best]) {
                best = lag;
            }
        }
        double a = c[best - 1];
        double b = c[best];
        double d = c[best + 1];
        return best + 0.5 * (a - d) / (a - 2 * b + d);
    }

    private static double energy(float[] out, int ear) {
        double e = 0;
        for (int f = 0; f < out.length / 2; f++) {
            e += out[2 * f + ear] * out[2 * f + ear];
        }
        return e;
    }

    private static double energy(float[] filter) {
        double e = 0;
        for (float f : filter) {
            e += f * f;
        }
        return e;
    }

    @Test
    void aSourceCirclingTheHeadNeverClicks() {
        // a 300 Hz tone circling at 2 m, once a second; a click is a burst in the second difference
        int seconds = 2;
        float[] out;
        try (AudioRenderer r = renderer(256, 16)) {
            AudioClip tone = Clips.sine(RATE, 300, 0.5f, RATE / 300 * 300); // whole periods: a seamless loop
            r.commands().play(0, tone, 0, 0, 1, 1, Bus.SFX, SPATIAL_HRTF | Voices.LOOP, 0, 1);
            out = new float[2 * RATE * seconds];
            for (int b = 0; b < RATE * seconds / 256; b++) {
                double angle = 2 * Math.PI * b * 256.0 / RATE;
                r.commands().spatial(0, (float) (2 * Math.sin(angle)), 0.3f, (float) (-2 * Math.cos(angle)), 1, FLAT,
                    false);
                r.render(out, b * 256, 256);
            }
        }
        for (int ear = 0; ear < 2; ear++) {
            double worst = 0;
            double level = 0;
            for (int f = RATE / 10; f < out.length / 2 - 1; f++) {
                double second = out[2 * (f + 1) + ear] - 2 * out[2 * f + ear] + out[2 * (f - 1) + ear];
                worst = Math.max(worst, Math.abs(second));
                level = Math.max(level, Math.abs(out[2 * f + ear]));
            }
            // a 300 Hz tone's second difference is at most (2 pi 300 / 48,000)^2 = 0.0015 of its peak; the moving
            // filter's colour adds some, a click (a jump) makes it a sizeable part of the peak
            assertThat(worst / level).as("ear %s", ear).isLessThan(0.02);
        }
    }

    @Test
    void pastTheCapVoicesPanAndTheLoudestAreBinaural() {
        try (AudioRenderer r = renderer(256, 4)) {
            AudioClip tone = Clips.sine(RATE, 200, 0.1f, 240 * 10);
            for (int v = 0; v < 8; v++) {
                r.commands().play(v, tone, 0, 0, 0.1f + v * 0.1f, 1, Bus.SFX, SPATIAL_HRTF | Voices.LOOP, 0, v);
                r.commands().spatial(v, v - 4, 0, -2, 1, FLAT, false);
            }
            r.render(256 * 8);
            assertThat(r.binauralVoices()).isEqualTo(4);
            for (int v = 0; v < 8; v++) {
                assertThat(r.binaural(v)).as("voice %s", v).isEqualTo(v >= 4); // the loudest four
            }
            // the quietest binaural one gets quieter than a panned one: they swap, cross-fading
            r.commands().gain(4, 0.01f, 0);
            r.render(256 * 8);
            assertThat(r.binaural(4)).isFalse();
            assertThat(r.binauralVoices()).isEqualTo(4);
            // a plain 3D voice never is
            r.commands().play(9, tone, 0, 0, 5, 1, Bus.SFX, Voices.SPATIAL | Voices.LOOP, 0, 9);
            r.commands().spatial(9, 1, 0, -1, 1, FLAT, false);
            r.render(256 * 8);
            assertThat(r.binaural(9)).isFalse();
            // a voice ending gives its channel back
            r.commands().kill(7);
            r.render(256 * 8);
            assertThat(r.binaural(7)).isFalse();
            // without a spatializer everything pans
            r.commands().spatializer(null, 16);
            r.render(256);
            assertThat(r.binauralVoices()).isZero();
        }
    }

    @Test
    void partitionedConvolutionMatchesOnePartition() {
        float[][] outs = new float[2][];
        int[] blocks = {256, 64};
        for (int k = 0; k < 2; k++) {
            try (AudioRenderer r = renderer(blocks[k], 16)) {
                r.commands().play(0, Clips.sine(RATE, 700, 0.1f, 4_000), 512, 0, 1, 1, Bus.SFX, SPATIAL_HRTF, 0, 1);
                r.commands().spatial(0, 1.5f, 0.4f, -1, 1, FLAT, false);
                outs[k] = r.render(8_192);
            }
        }
        assertThat(new HrtfSpatializer(builtIn(), 64, 1).parts()).isEqualTo(3);
        assertThat(new HrtfSpatializer(builtIn(), 256, 1).parts()).isEqualTo(1);
        for (int i = 0; i < outs[0].length; i++) {
            assertThat((double) outs[1][i]).as("sample %s", i).isCloseTo(outs[0][i], within(2e-6));
        }
    }

    @Test
    void nearTheHeadTheNearEarIsLouder() {
        double[] ild = new double[2];
        float[] distances = {2, 0.2f};
        for (int k = 0; k < 2; k++) {
            try (AudioRenderer r = renderer(256, 16)) {
                r.commands().play(0, Clips.sine(RATE, 400, 0.02f, 4_800), 0, 0, 1, 1, Bus.SFX,
                    SPATIAL_HRTF | Voices.LOOP, 0, 1);
                r.commands().spatial(0, distances[k] * 0.7f, 0, -distances[k] * 0.7f, 1, FLAT, false);
                float[] out = r.render(9_600);
                ild[k] = 10 * Math.log10(energy(out, 1) / energy(out, 0));
            }
        }
        assertThat(ild[1]).isGreaterThan(ild[0] + 2); // 3.7 dB at 2 m, 6.6 dB at 20 cm (measured)
        // at the head's centre it still renders (straight ahead)
        try (AudioRenderer r = renderer(256, 16)) {
            r.commands().play(0, Clips.sine(RATE, 400, 0.2f, 4_800), 0, 0, 1, 1, Bus.SFX, SPATIAL_HRTF, 0, 1);
            r.commands().spatial(0, HrtfSpatializer.EAR, 0, 0, 1, FLAT, false);
            assertThat(Clips.peak(r.render(4_800), 2, 0, 0, 4_800)).isGreaterThan(0);
        }
    }

    @Test
    void renderingSixteenMovingBinauralVoicesAllocatesNothing() {
        try (AudioRenderer r = renderer(256, 16)) {
            AudioClip tone = Clips.sine(RATE, 200, 0.05f, 240 * 10);
            for (int v = 0; v < 16; v++) {
                r.commands().play(v, tone, 0, 0, 1, 1, Bus.SFX, SPATIAL_HRTF | Voices.LOOP, 0, v);
            }
            float[] out = new float[512];
            int[] block = {0};
            assertAllocationFree(() -> {
                block[0]++;
                for (int v = 0; v < 16; v++) {
                    double angle = 0.05 * block[0] + v;
                    r.commands().spatial(v, (float) Math.sin(angle) * 3, 0, (float) Math.cos(angle) * 3, 1, FLAT,
                        false);
                }
                r.render(out, 0, 256);
                r.poll((k, s, t, f) -> { });
            }, 10_000, 1_000, 10);
            assertThat(r.binauralVoices()).isEqualTo(16);
        }
    }
}
