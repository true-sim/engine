package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.scene.audio.HrtfSet;

/**
 * Prepares measured head-related impulse responses into an {@link HrtfSet} (§2.9, steps 2 and 3 of the preparation),
 * at build time: the SOFA converter ({@code SofaConverter}, in the build's tools) reads the file and hands its
 * responses here. Per direction and ear:
 * <ul>
 *   <li>the interaural time difference: the lag of the largest cross-correlation of the two ears' responses below
 *       about 1.5 kHz, within a millisecond, between samples by a parabola through the peak; each ear's delay is half
 *       of it either side of a common delay, the least delay of the set being 0 (the responses' onsets, which the
 *       design meant to refine, were not reliable: SADIE II's KU100 straight above had onsets 14.6 samples apart);</li>
 *   <li>the minimum-phase filter with the response's magnitude (real cepstrum, folded), cut to {@code taps} with a
 *       half-cosine fade over its last eighth.</li>
 * </ul>
 * The filters are scaled so an ear's energy averaged over the directions is 1/2, an equal-power pan's in the middle,
 * and dense sets are thinned to {@code keep} directions spread evenly (farthest points first, from straight ahead).
 * Not for the audio thread: it allocates freely.
 */
@AllowAllocation("build-time conversion")
public final class HrtfConversion {

    /** Size of the transforms the cepstrum is taken with: long, so its aliasing stays small. */
    private static final int CEPSTRUM = 4_096;

    private HrtfConversion() {
    }

    /**
     * Converts {@code count} measured directions at {@code rate}: {@code positions} holds per direction its azimuth
     * (degrees, counter-clockwise from ahead, as SOFA's spherical positions), elevation (degrees up) and distance
     * (metres); {@code left} and {@code right} the responses. Keeps {@code keep} directions (0: all) with filters
     * {@code taps} long, credited {@code credit}.
     */
    public static HrtfSet convert(int rate, double[][] positions, float[][] left, float[][] right, int taps, int keep,
            String credit) {
        int count = positions.length;
        int[] chosen = keep <= 0 || keep >= count ? all(count) : spread(positions, keep);
        int n = chosen.length;
        float[] directions = new float[3 * n];
        float[] distances = new float[n];
        float[] delays = new float[2 * n];
        float[] filters = new float[2 * n * taps];
        double energy = 0;
        for (int k = 0; k < n; k++) {
            int i = chosen[k];
            double azimuth = Maths.toRadians(positions[i][0]);
            double elevation = Maths.toRadians(positions[i][1]);
            directions[3 * k] = (float) (-Maths.cos(elevation) * Maths.sin(azimuth));
            directions[3 * k + 1] = (float) Maths.sin(elevation);
            directions[3 * k + 2] = (float) (-Maths.cos(elevation) * Maths.cos(azimuth));
            distances[k] = (float) positions[i][2];
            double itd = itd(left[i], right[i], rate);
            delays[2 * k] = (float) (-0.5 * itd);
            delays[2 * k + 1] = (float) (0.5 * itd);
            float[][] ears = {left[i], right[i]};
            for (int ear = 0; ear < 2; ear++) {
                float[] minimum = minimumPhase(ears[ear], taps);
                System.arraycopy(minimum, 0, filters, (2 * k + ear) * taps, taps);
                for (float f : minimum) {
                    energy += f * f;
                }
            }
        }
        float least = Float.POSITIVE_INFINITY;
        for (float d : delays) {
            least = Math.min(least, d);
        }
        for (int i = 0; i < delays.length; i++) {
            delays[i] -= least;
        }
        float scale = (float) Math.sqrt(0.5 / (energy / (2.0 * n)));
        for (int i = 0; i < filters.length; i++) {
            filters[i] *= scale;
        }
        return HrtfSet.of(rate, taps, credit, directions, distances, delays, filters);
    }

    private static int[] all(int count) {
        int[] all = new int[count];
        for (int i = 0; i < count; i++) {
            all[i] = i;
        }
        return all;
    }

    /** {@code keep} of the directions, spread evenly: each next the one farthest from those chosen. */
    static int[] spread(double[][] positions, int keep) {
        int count = positions.length;
        double[] unit = new double[3 * count];
        for (int i = 0; i < count; i++) {
            double azimuth = Maths.toRadians(positions[i][0]);
            double elevation = Maths.toRadians(positions[i][1]);
            unit[3 * i] = -Maths.cos(elevation) * Maths.sin(azimuth);
            unit[3 * i + 1] = Maths.sin(elevation);
            unit[3 * i + 2] = -Maths.cos(elevation) * Maths.cos(azimuth);
        }
        double[] nearest = new double[count]; // the largest dot product with any chosen: nearer is larger
        java.util.Arrays.fill(nearest, -2);
        int[] chosen = new int[keep];
        int next = 0;
        double best = -2;
        for (int i = 0; i < count; i++) { // the first: the one nearest ahead
            if (-unit[3 * i + 2] > best) {
                best = -unit[3 * i + 2];
                next = i;
            }
        }
        for (int k = 0; k < keep; k++) {
            chosen[k] = next;
            double far = 2;
            int farthest = next;
            for (int i = 0; i < count; i++) {
                double dot = unit[3 * i] * unit[3 * next] + unit[3 * i + 1] * unit[3 * next + 1]
                    + unit[3 * i + 2] * unit[3 * next + 2];
                nearest[i] = Math.max(nearest[i], dot);
                if (nearest[i] < far) {
                    far = nearest[i];
                    farthest = i;
                }
            }
            next = farthest;
        }
        java.util.Arrays.sort(chosen);
        return chosen;
    }

    /**
     * The right ear's delay minus the left's, samples: the lag of the largest cross-correlation of the responses
     * low-passed near 1.5 kHz, within a millisecond, between samples by a parabola through the peak (the edge's lag
     * when the peak is there).
     */
    static double itd(float[] left, float[] right, int rate) {
        double[] l = lowPass(left, rate);
        double[] r = lowPass(right, rate);
        int reach = Math.max(1, (int) Math.ceil(0.001 * rate));
        int from = -reach;
        int to = reach;
        double[] c = new double[to - from + 1];
        int best = -1;
        for (int lag = from; lag <= to; lag++) {
            double sum = 0;
            for (int i = 0; i < l.length; i++) {
                int j = i + lag;
                if (j >= 0 && j < r.length) {
                    sum += l[i] * r[j];
                }
            }
            c[lag - from] = sum;
            if (best < 0 || sum > c[best]) {
                best = lag - from;
            }
        }
        if (best == 0 || best == c.length - 1) {
            return from + best;
        }
        double a = c[best - 1];
        double b = c[best];
        double d = c[best + 1];
        double curve = a - 2 * b + d;
        double shift = curve == 0 ? 0 : 0.5 * (a - d) / curve;
        return from + best + shift;
    }

    /** {@code x} through a second-order Butterworth low-pass at 1.5 kHz (RBJ's biquad), with room after for its tail. */
    private static double[] lowPass(float[] x, int rate) {
        double w = 2 * Maths.PI * 1_500 / rate;
        double alpha = Maths.sin(w) / (2 * Math.sqrt(0.5));
        double cos = Maths.cos(w);
        double a0 = 1 + alpha;
        double b0 = (1 - cos) / 2 / a0;
        double b1 = (1 - cos) / a0;
        double a1 = -2 * cos / a0;
        double a2 = (1 - alpha) / a0;
        double[] y = new double[x.length + 64];
        double x1 = 0;
        double x2 = 0;
        double y1 = 0;
        double y2 = 0;
        for (int i = 0; i < y.length; i++) {
            double in = i < x.length ? x[i] : 0;
            double out = b0 * in + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = in;
            y2 = y1;
            y1 = out;
            y[i] = out;
        }
        return y;
    }

    /**
     * The minimum-phase filter with {@code response}'s magnitude, {@code taps} long: the real cepstrum of the
     * magnitude, folded onto positive quefrencies, exponentiated back; the last eighth faded by a half cosine.
     */
    static float[] minimumPhase(float[] response, int taps) {
        int n = CEPSTRUM;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < Math.min(n, response.length); i++) {
            re[i] = response[i];
        }
        fft(re, im, false);
        double floor = 0;
        for (int k = 0; k < n; k++) {
            floor = Math.max(floor, Math.sqrt(re[k] * re[k] + im[k] * im[k]));
        }
        floor = Math.max(floor * 1e-5, 1e-30); // -100 dB under the peak: a notch's log stays finite
        for (int k = 0; k < n; k++) {
            re[k] = Maths.log(Math.max(floor, Math.sqrt(re[k] * re[k] + im[k] * im[k])));
            im[k] = 0;
        }
        fft(re, im, true); // the real cepstrum
        for (int i = 1; i < n / 2; i++) {
            re[i] *= 2;
            im[i] *= 2;
        }
        for (int i = n / 2 + 1; i < n; i++) {
            re[i] = 0;
            im[i] = 0;
        }
        fft(re, im, false);
        for (int k = 0; k < n; k++) { // exp of the complex log spectrum
            double magnitude = Maths.exp(re[k]);
            double phase = im[k];
            re[k] = magnitude * Maths.cos(phase);
            im[k] = magnitude * Maths.sin(phase);
        }
        fft(re, im, true);
        float[] out = new float[taps];
        int fade = Math.max(1, taps / 8);
        for (int i = 0; i < taps; i++) {
            double g = i < taps - fade ? 1 : 0.5 * (1 + Maths.cos(Maths.PI * (i - (taps - fade) + 1) / (fade + 1)));
            out[i] = (float) (re[i] * g);
        }
        return out;
    }

    /** An in-place radix-2 complex transform; the inverse scaled by 1/n. */
    static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            double angle = 2 * Maths.PI / length * (inverse ? 1 : -1);
            for (int k = 0; k < length / 2; k++) {
                double wr = Maths.cos(angle * k);
                double wi = Maths.sin(angle * k);
                for (int start = 0; start < n; start += length) {
                    int a = start + k;
                    int b = a + length / 2;
                    double br = re[b] * wr - im[b] * wi;
                    double bi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - br;
                    im[b] = im[a] - bi;
                    re[a] += br;
                    im[a] += bi;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }
}
