package com.truesim.engine.audio;

import com.truesim.common.math.random.Hash;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Bus;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Measures the interaural level difference per octave band (500 Hz, 2 kHz, 6 kHz) of a source straight to the right,
 * direct HRTF against the ambisonic field: end to end through the renderer, and offline for decodes of other kinds
 * and orders (least squares with and without max-rE, magnitude least squares), so a change of decode is chosen on
 * numbers. Behind {@code TRUESIM_AUDIO=true}; no timing.
 *
 * <pre>TRUESIM_AUDIO=true ./gradlew :engine-audio:test --tests '*AmbisonicsIldProbe*'</pre>
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_AUDIO", matches = "true")
class AmbisonicsIldProbe {

    static final int RATE = 48_000;
    static double[][] BANDS = {{354, 707}, {1414, 2828}, {4243, 8485}};

    @Test
    void endToEnd() {
        for (int degrees : new int[] {90, 45}) {
            double[][] ild = new double[2][];
            for (int cap : new int[] {16, 0}) {
                ild[cap == 16 ? 0 : 1] = bandIld(render(cap, degrees));
            }
            System.out.printf("end to end %d°: direct %s | field %s%n", degrees, Arrays.toString(round(ild[0])),
                Arrays.toString(round(ild[1])));
        }
        for (boolean pink : new boolean[] {false, true}) {
            for (int degrees : new int[] {90, 45}) {
                float[] direct = render(16, degrees, pink);
                float[] field = render(0, degrees, pink);
                double[] d = thirds(direct);
                double[] f = thirds(field);
                System.out.printf("%s %d°: broadband direct %.1f field %.1f%n", pink ? "pink" : "white", degrees,
                    broadband(direct), broadband(field));
                if (!pink) {
                    for (int b = 0; b < d.length; b++) {
                        System.out.printf("  %6.0f Hz: direct %6.1f field %6.1f%n", 125 * Math.pow(2, b / 3.0), d[b],
                            f[b]);
                    }
                }
            }
        }
    }

    /**
     * With {@code TRUESIM_AUDIO_WAV} set to a directory and {@code TRUESIM_AUDIO_WAV_NAME} to a file name: pink-ish
     * noise circling the head once in 6 s at 2 m, through the direct HRTF, a second of silence, then through the field.
     */
    @Test
    void circlingWav() throws java.io.IOException {
        String dir = System.getenv("TRUESIM_AUDIO_WAV");
        if (dir == null) {
            return;
        }
        int seconds = 6;
        float[] s = new float[seconds * RATE];
        double low = 0;
        for (int i = 0; i < s.length; i++) {
            double white = Hash.random(5, i) * 2 - 1;
            low += 0.2 * (white - low);
            s[i] = (float) (0.15 * (white + 2 * low));
        }
        AudioClip noise = Clips.mono(RATE, s);
        float[] all = new float[2 * (2 * seconds + 1) * RATE];
        int at = 0;
        for (int cap : new int[] {16, 0}) {
            try (AudioRenderer r = new AudioRenderer(AudioFormat.stereo(RATE), 256)) {
                r.commands().spatializer(new HrtfSpatializer(HrtfTest.builtIn(), 256, cap + 4), cap);
                r.commands().play(0, noise, 0, 0, 1, 1, Bus.SFX, HrtfTest.SPATIAL_HRTF, 0, 1);
                for (int b = 0; b < seconds * RATE / 256; b++) {
                    double a = 2 * Math.PI * b * 256.0 / (seconds * RATE);
                    r.commands().spatial(0, (float) (2 * Math.sin(a)), 0, (float) (-2 * Math.cos(a)), 1,
                        HrtfTest.FLAT, false);
                    r.render(all, at, 256);
                    at += 256;
                }
            }
            at += RATE;
        }
        WavWriter.write(java.nio.file.Path.of(dir).resolve(System.getenv("TRUESIM_AUDIO_WAV_NAME")), all, 2, RATE, 16);
    }

    static double broadband(float[] out) {
        double l = 0;
        double r = 0;
        for (int i = 0; i < out.length / 2; i++) {
            l += out[2 * i] * out[2 * i];
            r += out[2 * i + 1] * out[2 * i + 1];
        }
        return 10 * Math.log10(l / r);
    }

    /** Third-octave ILDs from 125 Hz to 16 kHz. */
    static double[] thirds(float[] out) {
        double[][] saved = BANDS;
        double[][] bands = new double[22][];
        for (int b = 0; b < bands.length; b++) {
            double c = 125 * Math.pow(2, b / 3.0);
            bands[b] = new double[] {c * Math.pow(2, -1 / 6.0), c * Math.pow(2, 1 / 6.0)};
        }
        BANDS = bands;
        try {
            return bandIld(out);
        } finally {
            BANDS = saved;
        }
    }

    /** Two seconds of white noise from {@code degrees} (clockwise from ahead) at 2 m, the last 1.5 s. */
    static float[] render(int cap, int degrees) {
        return render(cap, degrees, false);
    }

    /** As above, pink-ish (the noise of {@code AmbisonicsTest}) when {@code pink}. */
    static float[] render(int cap, int degrees, boolean pink) {
        float[] s = new float[2 * RATE];
        double low = 0;
        for (int i = 0; i < s.length; i++) {
            double white = Hash.random(pink ? 5 : 7, i) * 2 - 1;
            low += 0.2 * (white - low);
            s[i] = (float) (pink ? 0.05 * (white + 2 * low) : 0.1 * white);
        }
        AudioClip noise = Clips.mono(RATE, s);
        double a = Math.toRadians(degrees);
        try (AudioRenderer r = new AudioRenderer(AudioFormat.stereo(RATE), 256)) {
            r.commands().spatializer(new HrtfSpatializer(HrtfTest.builtIn(), 256, cap + 4), cap);
            r.commands().play(0, noise, 0, 0, 1, 1, Bus.SFX, HrtfTest.SPATIAL_HRTF, 0, 1);
            r.commands().spatial(0, (float) (2 * Math.sin(a)), 0, (float) (-2 * Math.cos(a)), 1, HrtfTest.FLAT,
                false);
            float[] out = new float[2 * 2 * RATE];
            r.render(out, 0, RATE / 2);
            r.render(out, 0, 3 * RATE / 2);
            return Arrays.copyOf(out, 2 * 3 * RATE / 2);
        }
    }

    /** Left over right per band, dB: Welch (Hann, 2,048 points, half overlap) of an interleaved stereo signal. */
    static double[] bandIld(float[] out) {
        int n = 2048;
        Fft fft = new Fft(n);
        float[] frame = new float[n];
        float[] re = new float[n / 2 + 1];
        float[] im = new float[n / 2 + 1];
        double[][] power = new double[2][n / 2 + 1];
        int frames = out.length / 2;
        for (int start = 0; start + n <= frames; start += n / 2) {
            for (int ear = 0; ear < 2; ear++) {
                for (int i = 0; i < n; i++) {
                    double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / n);
                    frame[i] = (float) (w * out[2 * (start + i) + ear]);
                }
                fft.forward(frame, re, im);
                for (int k = 0; k <= n / 2; k++) {
                    power[ear][k] += re[k] * re[k] + im[k] * im[k];
                }
            }
        }
        return ild(power[0], power[1], n);
    }

    static double[] ild(double[] left, double[] right, int n) {
        double[] ild = new double[BANDS.length];
        for (int b = 0; b < BANDS.length; b++) {
            double l = 0;
            double r = 0;
            for (int k = 0; k <= n / 2; k++) {
                double f = (double) k * RATE / n;
                if (f >= BANDS[b][0] && f < BANDS[b][1]) {
                    l += left[k];
                    r += right[k];
                }
            }
            ild[b] = 10 * Math.log10(l / r);
        }
        return ild;
    }

    static double[] round(double[] v) {
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = Math.round(v[i] * 10) / 10.0;
        }
        return r;
    }

    // ------------------------------------------------------------------------------------------- offline decodes

    @Test
    void offline() {
        Hrtf h = HrtfTest.builtIn();
        int n = h.count();
        int m = 1024;
        int length = h.taps() + (int) Math.ceil(h.maxDelay()) + 2 * HrtfSpatializer.BASE_DELAY + 1;
        double[] share = shares(h);
        // spectra of the delayed filters
        Fft fft = new Fft(m);
        int bins = m / 2 + 1;
        double[][] re = new double[2 * n][bins];
        double[][] im = new double[2 * n][bins];
        float[] taps = new float[h.taps()];
        float[] delayed = new float[m];
        float[] kernel = new float[2 * HrtfSpatializer.BASE_DELAY + 2];
        float[] sr = new float[bins];
        float[] si = new float[bins];
        for (int i = 0; i < n; i++) {
            for (int ear = 0; ear < 2; ear++) {
                for (int t = 0; t < taps.length; t++) {
                    taps[t] = h.tap(i, ear, t);
                }
                HrtfSpatializer.shift(taps, h.delay(i, ear) + HrtfSpatializer.BASE_DELAY, delayed, kernel);
                Arrays.fill(delayed, length, m, 0);
                fft.forward(delayed, sr, si);
                for (int k = 0; k < bins; k++) {
                    re[2 * i + ear][k] = sr[k];
                    im[2 * i + ear][k] = si[k];
                }
            }
        }
        int right = nearest(h, 1, 0, 0);
        int half = nearest(h, Math.sqrt(0.5), 0, -Math.sqrt(0.5));
        System.out.printf("%d directions, taps %d, 90° measured at (%.3f %.3f %.3f)%n", n, h.taps(),
            h.direction(right, 0), h.direction(right, 1), h.direction(right, 2));
        for (int at : new int[] {right, half}) {
            double[][] p = new double[2][bins];
            for (int ear = 0; ear < 2; ear++) {
                for (int k = 0; k < bins; k++) {
                    p[ear][k] = re[2 * at + ear][k] * re[2 * at + ear][k] + im[2 * at + ear][k] * im[2 * at + ear][k];
                }
            }
            System.out.printf("direct at %d: %s bb %.1f%n", at, Arrays.toString(round(ild(p[0], p[1], m))), broad(p));
        }
        for (int order : new int[] {3, 4, 5}) {
            for (String kind : new String[] {"LS", "LS+maxrE", "MagLS", "MagLS+maxrE"}) {
                boolean mag = kind.startsWith("MagLS");
                boolean maxRe = kind.endsWith("maxrE");
                for (double cutoff : mag ? new double[] {1500, 2000, 3000} : new double[] {0}) {
                    double[][][] c = decode(h, share, re, im, order, mag ? cutoff : Double.MAX_VALUE, maxRe, m);
                    System.out.printf("order %d %-12s fc %5.0f: %s%n", order, kind, cutoff,
                        metrics(h, re, im, c, order, m, right, half));
                }
            }
        }
    }

    /**
     * Over the horizontal plane every 15°: mean |ILD error| per octave band and broadband (white), mean ipsilateral
     * and contralateral level error per band (after the engine's one loudness scale); and the 90° and 45° ILDs.
     */
    static String metrics(Hrtf h, double[][] re, double[][] im, double[][][] c, int order, int m, int right,
            int half) {
        int n = h.count();
        int bins = m / 2 + 1;
        double decoded = 0;
        double wanted = 0;
        double[][] dec = new double[2 * n][bins];
        for (int i = 0; i < n; i++) {
            double[] y = sh(order, h.direction(i, 0), h.direction(i, 1), h.direction(i, 2));
            for (int ear = 0; ear < 2; ear++) {
                for (int k = 0; k < bins; k++) {
                    double a = 0;
                    double b = 0;
                    for (int ch = 0; ch < y.length; ch++) {
                        a += y[ch] * c[ear][ch][2 * k];
                        b += y[ch] * c[ear][ch][2 * k + 1];
                    }
                    dec[2 * i + ear][k] = a * a + b * b;
                    decoded += dec[2 * i + ear][k];
                    double d = re[2 * i + ear][k] * re[2 * i + ear][k] + im[2 * i + ear][k] * im[2 * i + ear][k];
                    wanted += d;
                }
            }
        }
        double scale = wanted / decoded;
        double[] ildErr = new double[BANDS.length + 1];
        double[] ipsi = new double[BANDS.length];
        double[] contra = new double[BANDS.length];
        int count = 0;
        for (int deg = 0; deg < 360; deg += 15) {
            double a = Math.toRadians(deg);
            int at = nearest(h, Math.sin(a), 0, -Math.cos(a));
            double[][] pd = new double[2][bins];
            double[][] pf = new double[2][bins];
            for (int ear = 0; ear < 2; ear++) {
                for (int k = 0; k < bins; k++) {
                    pd[ear][k] = re[2 * at + ear][k] * re[2 * at + ear][k] + im[2 * at + ear][k] * im[2 * at + ear][k];
                    pf[ear][k] = dec[2 * at + ear][k] * scale;
                }
            }
            double[] id = ild(pd[0], pd[1], m);
            double[] iF = ild(pf[0], pf[1], m);
            for (int b = 0; b < BANDS.length; b++) {
                ildErr[b] += Math.abs(iF[b] - id[b]);
                int ip = id[b] < 0 ? 1 : 0;
                ipsi[b] += level(pf[ip], m, b) - level(pd[ip], m, b);
                contra[b] += level(pf[1 - ip], m, b) - level(pd[1 - ip], m, b);
            }
            ildErr[BANDS.length] += Math.abs(broad(pf) - broad(pd));
            count++;
        }
        StringBuilder out = new StringBuilder("|dILD| ");
        for (double e : ildErr) {
            out.append(String.format("%5.1f", e / count));
        }
        out.append("  ipsi ");
        for (double e : ipsi) {
            out.append(String.format("%5.1f", e / count));
        }
        out.append("  contra ");
        for (double e : contra) {
            out.append(String.format("%5.1f", e / count));
        }
        for (int at : new int[] {right, half}) {
            double[] y = sh(order, h.direction(at, 0), h.direction(at, 1), h.direction(at, 2));
            double[][] p = {dec[2 * at], dec[2 * at + 1]};
            out.append("  ").append(Arrays.toString(round(ild(p[0], p[1], m))));
            out.append(String.format(" bb %.1f", broad(p)));
        }
        return out.toString();
    }

    static double level(double[] p, int m, int b) {
        double s = 0;
        for (int k = 0; k <= m / 2; k++) {
            double f = (double) k * RATE / m;
            if (f >= BANDS[b][0] && f < BANDS[b][1]) {
                s += p[k];
            }
        }
        return 10 * Math.log10(s);
    }

    static double broad(double[][] p) {
        double l = 0;
        double r = 0;
        for (int k = 0; k < p[0].length; k++) {
            l += p[0][k];
            r += p[1][k];
        }
        return 10 * Math.log10(l / r);
    }

    static int nearest(Hrtf h, double x, double y, double z) {
        int best = 0;
        double most = -2;
        for (int i = 0; i < h.count(); i++) {
            double d = x * h.direction(i, 0) + y * h.direction(i, 1) + z * h.direction(i, 2);
            if (d > most) {
                most = d;
                best = i;
            }
        }
        return best;
    }

    static double[] shares(Hrtf h) {
        double[] share = new double[h.count()];
        for (int t = 0; t < h.triangles(); t++) {
            int[] k = {h.corner(t, 0), h.corner(t, 1), h.corner(t, 2)};
            double[][] v = new double[3][3];
            for (int j = 0; j < 3; j++) {
                for (int axis = 0; axis < 3; axis++) {
                    v[j][axis] = h.direction(k[j], axis);
                }
            }
            double triple = v[0][0] * (v[1][1] * v[2][2] - v[1][2] * v[2][1])
                - v[0][1] * (v[1][0] * v[2][2] - v[1][2] * v[2][0]) + v[0][2] * (v[1][0] * v[2][1] - v[1][1] * v[2][0]);
            double ab = dot(v[0], v[1]);
            double bc = dot(v[1], v[2]);
            double ca = dot(v[2], v[0]);
            double angle = 2 * Math.abs(Math.atan2(triple, 1 + ab + bc + ca));
            for (int j = 0; j < 3; j++) {
                share[k[j]] += angle / 3;
            }
        }
        return share;
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /**
     * SH coefficients [ear][channel][2 * bin (+1 imaginary)]: weighted least squares over the measured directions,
     * of the complex responses below {@code cutoff} and of the magnitudes above (phase from the bin below's decode,
     * turned by a delay of the middle interaural delay, as {@link Ambisonics} does).
     */
    static double[][][] decode(Hrtf h, double[] w, double[][] re, double[][] im, int order, double cutoff,
            boolean maxRe, int m) {
        int n = h.count();
        int ch = (order + 1) * (order + 1);
        int bins = m / 2 + 1;
        double[][] y = new double[n][];
        for (int i = 0; i < n; i++) {
            y[i] = sh(order, h.direction(i, 0), h.direction(i, 1), h.direction(i, 2));
        }
        // (Y^T W Y)^-1 Y^T W
        double[][] gram = new double[ch][ch];
        for (int i = 0; i < n; i++) {
            for (int a = 0; a < ch; a++) {
                for (int b = 0; b < ch; b++) {
                    gram[a][b] += w[i] * y[i][a] * y[i][b];
                }
            }
        }
        double[][] inv = invert(gram);
        double[][] solve = new double[ch][n];
        for (int a = 0; a < ch; a++) {
            for (int i = 0; i < n; i++) {
                double s = 0;
                for (int b = 0; b < ch; b++) {
                    s += inv[a][b] * y[i][b];
                }
                solve[a][i] = s * w[i];
            }
        }
        double[] g = maxRe ? maxRe(order) : null;
        double[][][] c = new double[2][ch][2 * bins];
        double[] tr = new double[n];
        double[] ti = new double[n];
        for (int ear = 0; ear < 2; ear++) {
            for (int k = 0; k < bins; k++) {
                double f = (double) k * RATE / m;
                for (int i = 0; i < n; i++) {
                    double hr = re[2 * i + ear][k];
                    double hi = im[2 * i + ear][k];
                    if (f < cutoff || k == 0) {
                        tr[i] = hr;
                        ti[i] = hi;
                    } else {
                        double pr = 0;
                        double pi = 0;
                        for (int a = 0; a < ch; a++) {
                            pr += y[i][a] * c[ear][a][2 * (k - 1)];
                            pi += y[i][a] * c[ear][a][2 * (k - 1) + 1];
                        }
                        double turn = -2 * Math.PI * (HrtfSpatializer.BASE_DELAY + h.maxDelay() / 2 + 8) / m;
                        double qr = pr * Math.cos(turn) - pi * Math.sin(turn);
                        pi = pr * Math.sin(turn) + pi * Math.cos(turn);
                        pr = qr;
                        double mag = Math.hypot(hr, hi);
                        double phase = Math.atan2(pi, pr);
                        tr[i] = mag * Math.cos(phase);
                        ti[i] = mag * Math.sin(phase);
                    }
                }
                for (int a = 0; a < ch; a++) {
                    double sr = 0;
                    double si = 0;
                    for (int i = 0; i < n; i++) {
                        sr += solve[a][i] * tr[i];
                        si += solve[a][i] * ti[i];
                    }
                    c[ear][a][2 * k] = sr;
                    c[ear][a][2 * k + 1] = si;
                }
            }
            if (g != null) {
                for (int a = 0; a < ch; a++) {
                    int l = (int) Math.floor(Math.sqrt(a));
                    for (int k = 0; k < 2 * bins; k++) {
                        c[ear][a][k] *= g[l];
                    }
                }
            }
        }
        return c;
    }

    /** Max-rE weights per order: P_l(cos(137.9° / (N + 1.51))) (Zotter and Frank's approximation). */
    static double[] maxRe(int order) {
        double x = Math.cos(Math.toRadians(137.9 / (order + 1.51)));
        double[] p = new double[order + 1];
        p[0] = 1;
        if (order > 0) {
            p[1] = x;
        }
        for (int l = 2; l <= order; l++) {
            p[l] = ((2 * l - 1) * x * p[l - 1] - (l - 1) * p[l - 2]) / l;
        }
        return p;
    }

    /** Orthonormal real spherical harmonics up to {@code order}, ACN, polar axis y (up). */
    static double[] sh(int order, double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        double ct = y / len;
        double phi = Math.atan2(x, -z);
        double st = Math.sqrt(Math.max(0, 1 - ct * ct));
        double[] out = new double[(order + 1) * (order + 1)];
        for (int l = 0; l <= order; l++) {
            for (int mm = -l; mm <= l; mm++) {
                int am = Math.abs(mm);
                double p = legendre(l, am, ct, st);
                double norm = Math.sqrt((2 * l + 1) / (4 * Math.PI) * factorialRatio(l - am, l + am));
                double v = norm * p;
                if (mm > 0) {
                    v *= Math.sqrt(2) * Math.cos(am * phi);
                } else if (mm < 0) {
                    v *= Math.sqrt(2) * Math.sin(am * phi);
                }
                out[l * l + l + mm] = v;
            }
        }
        return out;
    }

    static double factorialRatio(int a, int b) {
        double r = 1;
        for (int i = a + 1; i <= b; i++) {
            r /= i;
        }
        return r;
    }

    static double legendre(int l, int m, double x, double s) {
        double pmm = 1;
        for (int i = 1; i <= m; i++) {
            pmm *= (2 * i - 1) * s;
        }
        if (l == m) {
            return pmm;
        }
        double pmm1 = x * (2 * m + 1) * pmm;
        if (l == m + 1) {
            return pmm1;
        }
        double pll = 0;
        for (int ll = m + 2; ll <= l; ll++) {
            pll = ((2 * ll - 1) * x * pmm1 - (ll + m - 1) * pmm) / (ll - m);
            pmm = pmm1;
            pmm1 = pll;
        }
        return pll;
    }

    static double[][] invert(double[][] a) {
        int n = a.length;
        double[][] m = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n + i] = 1;
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
                    pivot = r;
                }
            }
            double[] t = m[col];
            m[col] = m[pivot];
            m[pivot] = t;
            double d = m[col][col];
            for (int j = 0; j < 2 * n; j++) {
                m[col][j] /= d;
            }
            for (int r = 0; r < n; r++) {
                if (r != col) {
                    double f = m[r][col];
                    for (int j = 0; j < 2 * n; j++) {
                        m[r][j] -= f * m[col][j];
                    }
                }
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(m[i], n, inv[i], 0, n);
        }
        return inv;
    }
}
