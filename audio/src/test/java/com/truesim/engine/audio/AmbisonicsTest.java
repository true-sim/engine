package com.truesim.engine.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.math.random.Hash;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.BusLayout;
import org.junit.jupiter.api.Test;

/**
 * The ambisonics bus (§6 step 18): the encoding is orthonormal, sources past the cap are heard from their direction
 * (ITD, ILD broadband and per band), the decode runs once a block per field however many voices it carries, crossing
 * the cap does not click.
 */
class AmbisonicsTest {

    static final int RATE = 48_000;

    @Test
    void theHarmonicsAreOrthonormal() {
        int n = 20_000;
        double[][] gram = new double[16][16];
        float[] y = new float[16];
        for (int i = 0; i < n; i++) { // a Fibonacci sphere: nearly even points
            double z = 1 - (2.0 * i + 1) / n;
            double r = Math.sqrt(1 - z * z);
            double phi = i * Math.PI * (3 - Math.sqrt(5));
            Ambisonics.encode((float) (r * Math.cos(phi)), (float) (r * Math.sin(phi)), (float) z, y, 0);
            for (int a = 0; a < 16; a++) {
                for (int b = 0; b < 16; b++) {
                    gram[a][b] += y[a] * y[b] * 4 * Math.PI / n;
                }
            }
        }
        for (int a = 0; a < 16; a++) {
            for (int b = 0; b < 16; b++) {
                assertThat(gram[a][b]).as("%s, %s", a, b).isCloseTo(a == b ? 1 : 0, within(0.01));
            }
        }
        Ambisonics.encode(0, 0, 0, y, 0); // no direction: ahead
        float[] ahead = new float[16];
        Ambisonics.encode(0, 0, -5, ahead, 0);
        assertThat(y).containsExactly(ahead);
        assertThat(ahead[3]).as("first order: x").isZero();
        assertThat(ahead[2]).as("first order: z, ahead is -z").isNegative();
    }

    /** A renderer whose spatializer renders {@code cap} voices binaurally and the rest into the ambisonics bus. */
    private static AudioRenderer renderer(int cap) {
        AudioRenderer r = new AudioRenderer(AudioFormat.stereo(RATE), 256);
        r.commands().spatializer(new HrtfSpatializer(HrtfTest.builtIn(), 256, cap + 4), cap);
        return r;
    }

    /** Pink-ish noise, a second. */
    private static AudioClip noise() {
        float[] s = new float[RATE];
        double low = 0;
        for (int i = 0; i < s.length; i++) {
            double white = Hash.random(5, i) * 2 - 1;
            low += 0.2 * (white - low);
            s[i] = (float) (0.05 * (white + 2 * low));
        }
        return Clips.mono(RATE, s);
    }

    @Test
    void sourcesPastTheCapAreHeardFromTheirDirection() {
        AudioClip noise = noise();
        for (int degrees : new int[] {-90, -45, 0, 30, 90, 135}) {
            double a = Math.toRadians(degrees);
            float x = (float) (2 * Math.sin(a));
            float z = (float) (-2 * Math.cos(a));
            float[][] outs = new float[2][];
            for (int cap : new int[] {16, 0}) { // direct HRTF, then the ambisonic field
                try (AudioRenderer r = renderer(cap)) {
                    r.commands().play(0, noise, 0, 0, 1, 1, Bus.SFX, HrtfTest.SPATIAL_HRTF, 0, 1);
                    r.commands().spatial(0, x, 0, z, 1, HrtfTest.FLAT, false);
                    float[] out = new float[2 * RATE];
                    r.render(out, 0, RATE / 2);
                    assertThat(r.binauralVoices()).isEqualTo(cap == 16 ? 1 : 0);
                    r.render(out, RATE / 2, RATE / 2);
                    outs[cap == 16 ? 0 : 1] = out;
                }
            }
            double directItd = itd(outs[0]);
            double fieldItd = itd(outs[1]);
            double directIld = ild(outs[0]);
            double fieldIld = ild(outs[1]);
            System.out.printf("%d°: ITD %.0f / %.0f samples, ILD %.1f / %.1f dB%n", degrees, directItd, fieldItd, directIld,
                fieldIld);
            assertThat(fieldItd).as("ITD at %s°", degrees).isCloseTo(directItd, within(5.0)); // 0.1 ms
            // broadband, so it also holds the decode's balance of lows and highs: before MagLS about half in dB
            assertThat(fieldIld).as("ILD at %s°", degrees).isCloseTo(directIld, within(1.5));
            if (Math.abs(degrees) >= 30) {
                assertThat(Math.signum(fieldIld)).isEqualTo(Math.signum(directIld));
                assertThat(Math.signum(fieldItd)).isEqualTo(Math.signum(directItd));
            }
        }
    }

    /**
     * Per octave band (500 Hz, 2 kHz, 6 kHz), white noise straight to the right and at 45°: the field's ILD within 1.5,
     * 1.5 and 5 dB of the direct HRTF's, and the near ear's level within 3 dB. Fitting the complex responses at every
     * frequency (with max-rE weights) had the near ear 11 dB too loud at 500 Hz and 7.5 dB too quiet at 6 kHz, the
     * ILDs off by 4.4 dB at 2 kHz and 6.7 dB at 6 kHz straight to the side; with MagLS 1.3, 0.6 and 4.1 dB there.
     */
    @Test
    void theFieldKeepsEachBandsLevelDifference() {
        double[] tolerance = {1.5, 1.5, 5};
        for (int degrees : new int[] {90, 45}) {
            double[][] direct = bandLevels(whiteNoiseFrom(16, degrees));
            double[][] field = bandLevels(whiteNoiseFrom(0, degrees));
            for (int b = 0; b < BANDS.length; b++) {
                double directIld = direct[0][b] - direct[1][b];
                double fieldIld = field[0][b] - field[1][b];
                System.out.printf("%d°, %.0f Hz: ILD %.1f / %.1f dB, near ear %+.1f dB%n", degrees, BANDS[b][2],
                    directIld, fieldIld, field[1][b] - direct[1][b]);
                assertThat(fieldIld).as("ILD at %s°, %s Hz", degrees, BANDS[b][2])
                    .isCloseTo(directIld, within(tolerance[b]));
                assertThat(field[1][b]).as("near (right) ear at %s°, %s Hz", degrees, BANDS[b][2])
                    .isCloseTo(direct[1][b], within(3.0));
            }
        }
    }

    /** Octave bands: from, to, centre (Hz). */
    private static final double[][] BANDS = {{354, 707, 500}, {1414, 2828, 2000}, {4243, 8485, 6000}};

    /** A second of white noise at 2 m, {@code degrees} clockwise from ahead, with {@code cap} binaural voices. */
    private static float[] whiteNoiseFrom(int cap, int degrees) {
        float[] s = new float[RATE];
        for (int i = 0; i < s.length; i++) {
            s[i] = (float) (0.1 * (Hash.random(7, i) * 2 - 1));
        }
        double a = Math.toRadians(degrees);
        try (AudioRenderer r = renderer(cap)) {
            r.commands().play(0, Clips.mono(RATE, s), 0, 0, 1, 1, Bus.SFX, HrtfTest.SPATIAL_HRTF, 0, 1);
            r.commands().spatial(0, (float) (2 * Math.sin(a)), 0, (float) (-2 * Math.cos(a)), 1, HrtfTest.FLAT,
                false);
            float[] out = new float[RATE];
            r.render(out, 0, RATE / 4); // past the filters' start
            r.render(out, 0, RATE / 2);
            return out;
        }
    }

    /** The level (dB) of each ear in each band: averaged spectra of half-overlapping Hann windows of 2,048 frames. */
    private static double[][] bandLevels(float[] out) {
        int n = 2048;
        Fft fft = new Fft(n);
        float[] frame = new float[n];
        float[] re = new float[n / 2 + 1];
        float[] im = new float[n / 2 + 1];
        double[][] level = new double[2][BANDS.length];
        for (int start = 0; start + n <= out.length / 2; start += n / 2) {
            for (int ear = 0; ear < 2; ear++) {
                for (int i = 0; i < n; i++) {
                    frame[i] = (float) ((0.5 - 0.5 * Math.cos(2 * Math.PI * i / n)) * out[2 * (start + i) + ear]);
                }
                fft.forward(frame, re, im);
                for (int k = 0; k <= n / 2; k++) {
                    double f = (double) k * RATE / n;
                    for (int b = 0; b < BANDS.length; b++) {
                        if (f >= BANDS[b][0] && f < BANDS[b][1]) {
                            level[ear][b] += re[k] * re[k] + im[k] * im[k];
                        }
                    }
                }
            }
        }
        for (int ear = 0; ear < 2; ear++) {
            for (int b = 0; b < BANDS.length; b++) {
                level[ear][b] = 10 * Math.log10(level[ear][b]);
            }
        }
        return level;
    }

    /** The lag (samples) of the right ear behind the left: the largest cross-correlation of the two within 1 ms. */
    private static double itd(float[] out) {
        int frames = out.length / 2;
        double best = -Double.MAX_VALUE;
        int at = 0;
        for (int lag = -48; lag <= 48; lag++) {
            double s = 0;
            for (int f = 2_000; f < frames - 2_000; f++) {
                s += lowPassed(out, f, 0) * lowPassed(out, f + lag, 1);
            }
            if (s > best) {
                best = s;
                at = lag;
            }
        }
        return at;
    }

    /** A 16-sample moving average: the cross-correlation looks at the lows, where the ITD is. */
    private static double lowPassed(float[] out, int f, int ear) {
        double s = 0;
        for (int i = 0; i < 16; i++) {
            s += out[2 * (f - i) + ear];
        }
        return s / 16;
    }

    /** Left over right, dB. */
    private static double ild(float[] out) {
        double l = 0;
        double r = 0;
        for (int f = 0; f < out.length / 2; f++) {
            l += out[2 * f] * out[2 * f];
            r += out[2 * f + 1] * out[2 * f + 1];
        }
        return 10 * Math.log10(l / r);
    }

    @Test
    void theFieldDecodesOnceABlockWhateverItCarries() {
        for (int voices : new int[] {10, 100}) {
            try (AudioRenderer r = renderer(0)) {
                AudioClip tone = Clips.sine(RATE, 300, 0.01f, 160 * 30);
                for (int v = 0; v < voices; v++) {
                    r.commands().play(v, tone, 0, 0, 1, 1, Bus.SFX, HrtfTest.SPATIAL_HRTF | Voices.LOOP, 0, v);
                    r.commands().spatial(v, (float) Math.sin(v), 0, (float) Math.cos(v), 1, HrtfTest.FLAT, false);
                }
                r.render(256);
                Ambisonics field = ambisonics(r);
                long before = field.decodes();
                r.render(256 * 20);
                assertThat(field.decodes() - before).as("%s voices", voices).isEqualTo(20);
            }
        }
        try (AudioRenderer r = renderer(0)) { // voices on two buses: two fields
            AudioClip tone = Clips.sine(RATE, 300, 0.01f, 160 * 30);
            r.commands().play(0, tone, 0, 0, 1, 1, Bus.SFX, HrtfTest.SPATIAL_HRTF | Voices.LOOP, 0, 0);
            r.commands().play(1, tone, 0, 0, 1, 1, Bus.AMBIENCE, HrtfTest.SPATIAL_HRTF | Voices.LOOP, 0, 1);
            r.commands().spatial(0, 1, 0, 0, 1, HrtfTest.FLAT, false);
            r.commands().spatial(1, -1, 0, 0, 1, HrtfTest.FLAT, false);
            r.render(256);
            long before = ambisonics(r).decodes();
            r.render(256 * 10);
            assertThat(ambisonics(r).decodes() - before).isEqualTo(20);
            // stopped, a field decodes its tail and then rests
            r.commands().kill(0);
            r.commands().kill(1);
            r.render(256 * 20);
            before = ambisonics(r).decodes();
            r.render(256 * 10);
            assertThat(ambisonics(r).decodes() - before).isZero();
            // a new layout starts the fields again
            r.commands().layout(BusLayout.DEFAULT);
            r.render(256);
            assertThat(Clips.peak(r.render(256), 2, 0, 0, 256)).isZero();
        }
    }

    private static Ambisonics ambisonics(AudioRenderer r) {
        return ((HrtfSpatializer) spatializerOf(r)).ambisonics();
    }

    private static Spatializer spatializerOf(AudioRenderer r) {
        try {
            var voices = AudioRenderer.class.getDeclaredField("voices");
            voices.setAccessible(true);
            var spatializer = Voices.class.getDeclaredField("spatializer");
            spatializer.setAccessible(true);
            return (Spatializer) spatializer.get(voices.get(r));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aVoiceCrossingTheCapDoesNotClick() {
        // two tones: the louder one is binaural; it drops below the other and moves into the field, cross-fading
        try (AudioRenderer r = renderer(1)) {
            AudioClip tone = Clips.sine(RATE, 250, 0.2f, 192 * 25); // whole periods: a seamless loop
            r.commands().play(0, tone, 0, 0, 1, 1, Bus.SFX, HrtfTest.SPATIAL_HRTF | Voices.LOOP, 0, 0);
            r.commands().play(1, tone, 0, 0, 0.5f, 1, Bus.SFX, HrtfTest.SPATIAL_HRTF | Voices.LOOP, 0, 1);
            r.commands().spatial(0, 1.5f, 0, -1.5f, 1, HrtfTest.FLAT, false);
            r.commands().spatial(1, -1.5f, 0, -1.5f, 1, HrtfTest.FLAT, false);
            float[] out = new float[2 * RATE];
            r.render(out, 0, RATE / 2);
            assertThat(r.binaural(0)).isTrue();
            r.commands().gain(0, 0.25f, 0.2);
            r.render(out, RATE / 2, RATE / 2);
            assertThat(r.binaural(0)).isFalse();
            assertThat(r.binaural(1)).isTrue();
            for (int ear = 0; ear < 2; ear++) {
                double worst = 0;
                double level = 0;
                for (int f = RATE / 4; f < RATE - 1; f++) {
                    double second = out[2 * (f + 1) + ear] - 2 * out[2 * f + ear] + out[2 * (f - 1) + ear];
                    worst = Math.max(worst, Math.abs(second));
                    level = Math.max(level, Math.abs(out[2 * f + ear]));
                }
                assertThat(worst / level).as("ear %s", ear).isLessThan(0.02);
            }
        }
    }

    @Test
    void renderingIntoTheFieldAllocatesNothing() {
        try (AudioRenderer r = renderer(4)) {
            AudioClip tone = Clips.sine(RATE, 200, 0.02f, 240 * 10);
            for (int v = 0; v < 40; v++) {
                r.commands().play(v, tone, 0, 0, 1, 1, Bus.SFX, HrtfTest.SPATIAL_HRTF | Voices.LOOP, 0, v);
            }
            float[] out = new float[512];
            int[] block = {0};
            assertAllocationFree(() -> {
                block[0]++;
                for (int v = 0; v < 40; v++) {
                    double angle = 0.05 * block[0] + v;
                    r.commands().spatial(v, (float) Math.sin(angle) * 3, 0, (float) Math.cos(angle) * 3, 1,
                        HrtfTest.FLAT, false);
                }
                r.render(out, 0, 256);
                r.poll((k, s, t, f) -> { });
            }, 5_000, 500, 10);
        }
    }
}
