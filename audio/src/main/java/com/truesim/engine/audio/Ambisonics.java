package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import java.util.Arrays;

/**
 * The ambisonics bus (§2.9): HRTF voices past the cap are encoded into a third-order ambisonic field (16 real
 * spherical harmonics, orthonormal, of the direction in the listener's frame: positions are listener-relative, so
 * the field needs no rotation) and the field is decoded to both ears once a block, by convolving each harmonic with
 * an SH-domain filter pair derived from the same {@link Hrtf}: per frequency, the 16 coefficients whose field best
 * matches each ear's filter h_e,d (delayed by its interaural delay) over the measured directions d, weighted by each
 * direction's share of the sphere (a third of its triangles' solid angles); below {@link #CUTOFF} the complex
 * response, above it the magnitude only (MagLS), its phase free; scaled so the decoded field is as loud, averaged
 * over the directions, as the direct HRTF. Fitting the complex response at all frequencies (with max-rE weights, as
 * before) lost the highs at third order (the phase of h_e,d needs higher orders there) and, once scaled, raised the
 * lows: about 11 dB too loud at 500 Hz and 7.5 dB too quiet at 6 kHz at the near ear, which halved the broadband ILD.
 * The filters are as long as before (the same partitions), so the decode costs 16 forward FFTs, 32 partition products and 2 inverse FFTs a block, however many voices the field
 * carries; each voice costs 16 gains a sample.
 *
 * <p>One field per bus that has such voices (their bus volumes and effects stay), up to {@link #fields()}; a field
 * decodes while it has input and for its filters' tail after. Above about 1.5 kHz a third-order field cannot carry
 * the interaural time difference: directions blur there, which is why the most audible voices take the direct
 * HRTF. Made with its spatializer, off the audio thread; allocation-free after.
 */
public final class Ambisonics {

    /** The order. */
    public static final int ORDER = 3;
    /** Harmonics: (order + 1)². */
    public static final int CHANNELS = (ORDER + 1) * (ORDER + 1);
    /**
     * Where the decode's fit turns from complex responses to magnitudes, Hz: near where a third-order field stops
     * carrying the phase of the head (N c / (2 pi r) is 1.9 kHz for a radius of 8.75 cm); of 1.5, 2 and 3 kHz, 1.5 kept
     * the ILDs and levels closest to the direct HRTF ({@code AmbisonicsIldProbe}).
     */
    static final double CUTOFF = 1_500;
    /** Taps past the middle interaural delay around which the magnitude fit centres the highs. */
    private static final int CENTRE = 8;
    /** Taps faded out at the end of each SH-domain filter. */
    private static final int FADE = 16;

    private final int block;
    private final int fields;
    private final int parts;
    private final int bins;
    private final Fft fft;
    private final float[] filterRe;
    private final float[] filterIm;
    private final float[][] input;
    private final float[][] history;
    private final float[][] fdlRe;
    private final float[][] fdlIm;
    private final int[] head;
    private final int[] active;
    private final float[] frame;
    private final float[] specRe;
    private final float[] specIm;
    private final float[] accRe;
    private final float[] accIm;
    private final float[] time;
    private long decodes;

    /** The bus for {@code hrtf} at blocks of {@code block} frames, with room for {@code fields} fields (buses). */
    @AllowAllocation("made with its spatializer")
    public Ambisonics(Hrtf hrtf, int block, int fields) {
        this.block = block;
        this.fields = fields;
        int length = hrtf.taps() + (int) Math.ceil(hrtf.maxDelay()) + 2 * HrtfSpatializer.BASE_DELAY + 1;
        parts = (length + block - 1) / block;
        bins = block + 1;
        fft = new Fft(2 * block);
        filterRe = new float[2 * CHANNELS * parts * bins];
        filterIm = new float[2 * CHANNELS * parts * bins];
        input = new float[fields][CHANNELS * block];
        history = new float[fields][CHANNELS * block];
        fdlRe = new float[fields][CHANNELS * parts * bins];
        fdlIm = new float[fields][CHANNELS * parts * bins];
        head = new int[fields];
        active = new int[fields];
        frame = new float[2 * block];
        specRe = new float[bins];
        specIm = new float[bins];
        accRe = new float[bins];
        accIm = new float[bins];
        time = new float[2 * block];
        filters(hrtf);
    }

    /**
     * The SH-domain filter pairs, transformed by partition: designed per frequency bin as a weighted least-squares fit
     * over the measured directions, of the complex responses below {@link #CUTOFF} and of their magnitudes above, with
     * each direction's phase taken from the fit of the bin below (MagLS, Schoerkhuber, Zaunschirm and Hoeldrich 2018).
     */
    private void filters(Hrtf hrtf) {
        int n = hrtf.count();
        int span = parts * block;
        double[] share = new double[n];
        for (int t = 0; t < hrtf.triangles(); t++) {
            int a = hrtf.corner(t, 0);
            int b = hrtf.corner(t, 1);
            int c = hrtf.corner(t, 2);
            double angle = solidAngle(hrtf, a, b, c);
            share[a] += angle / 3;
            share[b] += angle / 3;
            share[c] += angle / 3;
        }
        // the fit: c = (Y^T W Y)^-1 Y^T W h, Y the harmonics of the directions, W their shares
        float[] y = new float[n * CHANNELS];
        double[][] gram = new double[CHANNELS][CHANNELS];
        for (int i = 0; i < n; i++) {
            encode((float) hrtf.direction(i, 0), (float) hrtf.direction(i, 1), (float) hrtf.direction(i, 2), y,
                i * CHANNELS);
            for (int a = 0; a < CHANNELS; a++) {
                for (int b = 0; b < CHANNELS; b++) {
                    gram[a][b] += share[i] * y[i * CHANNELS + a] * y[i * CHANNELS + b];
                }
            }
        }
        double[][] inverse = invert(gram);
        float[] solve = new float[CHANNELS * n];
        for (int a = 0; a < CHANNELS; a++) {
            for (int i = 0; i < n; i++) {
                double s = 0;
                for (int b = 0; b < CHANNELS; b++) {
                    s += inverse[a][b] * y[i * CHANNELS + b];
                }
                solve[a * n + i] = (float) (s * share[i]);
            }
        }
        // each ear's filters (delayed by their interaural delays) as spectra, then the fit bin by bin
        int size = Integer.highestOneBit(4 * span - 1) << 1;
        int spectrum = size / 2 + 1;
        int above = (int) Math.ceil(CUTOFF * size / hrtf.rate());
        Fft design = new Fft(size);
        // above the cutoff each bin takes the phase of the fit below, turned by the phase a delay of `centre` adds:
        // the magnitude fit's highs then come around that delay, inside the filters, not around time 0 (wrapped)
        double centre = HrtfSpatializer.BASE_DELAY + hrtf.maxDelay() / 2 + CENTRE;
        double stepRe = Maths.cos(2 * Maths.PI * centre / size);
        double stepIm = -Maths.sin(2 * Maths.PI * centre / size);
        float[] taps = new float[hrtf.taps()];
        float[] delayed = new float[span];
        float[] kernel = new float[2 * HrtfSpatializer.BASE_DELAY + 2];
        float[] padded = new float[size];
        float[] re = new float[spectrum];
        float[] im = new float[spectrum];
        float[] hRe = new float[n * spectrum];
        float[] hIm = new float[n * spectrum];
        double[] cRe = new double[CHANNELS * spectrum];
        double[] cIm = new double[CHANNELS * spectrum];
        double[] tRe = new double[n];
        double[] tIm = new double[n];
        float[][] sh = new float[2][CHANNELS * span];
        float[][] direct = new float[n * 2][];
        for (int ear = 0; ear < 2; ear++) {
            for (int i = 0; i < n; i++) {
                for (int t = 0; t < taps.length; t++) {
                    taps[t] = hrtf.tap(i, ear, t);
                }
                HrtfSpatializer.shift(taps, hrtf.delay(i, ear) + HrtfSpatializer.BASE_DELAY, delayed, kernel);
                direct[2 * i + ear] = delayed.clone();
                System.arraycopy(delayed, 0, padded, 0, span);
                design.forward(padded, re, im);
                System.arraycopy(re, 0, hRe, i * spectrum, spectrum);
                System.arraycopy(im, 0, hIm, i * spectrum, spectrum);
            }
            for (int k = 0; k < spectrum; k++) {
                for (int i = 0; i < n; i++) {
                    double hr = hRe[i * spectrum + k];
                    double hi = hIm[i * spectrum + k];
                    if (k < above) {
                        tRe[i] = hr;
                        tIm[i] = hi;
                        continue;
                    }
                    double pr = 0;
                    double pi = 0;
                    for (int a = 0; a < CHANNELS; a++) {
                        pr += y[i * CHANNELS + a] * cRe[a * spectrum + k - 1];
                        pi += y[i * CHANNELS + a] * cIm[a * spectrum + k - 1];
                    }
                    double advanced = pr * stepRe - pi * stepIm; // the phase a delay of `centre` adds a bin
                    pi = pr * stepIm + pi * stepRe;
                    pr = advanced;
                    double magnitude = Math.sqrt(hr * hr + hi * hi);
                    double fitted = Math.sqrt(pr * pr + pi * pi);
                    tRe[i] = fitted > 0 ? magnitude * pr / fitted : magnitude;
                    tIm[i] = fitted > 0 ? magnitude * pi / fitted : 0;
                }
                for (int a = 0; a < CHANNELS; a++) {
                    double sr = 0;
                    double si = 0;
                    for (int i = 0; i < n; i++) {
                        sr += solve[a * n + i] * tRe[i];
                        si += solve[a * n + i] * tIm[i];
                    }
                    cRe[a * spectrum + k] = sr;
                    cIm[a * spectrum + k] = k == 0 || k == spectrum - 1 ? 0 : si;
                }
            }
            for (int a = 0; a < CHANNELS; a++) {
                for (int k = 0; k < spectrum; k++) {
                    re[k] = (float) cRe[a * spectrum + k];
                    im[k] = (float) cIm[a * spectrum + k];
                }
                design.inverse(re, im, padded);
                for (int t = 0; t < span; t++) { // a short fade at the end: the magnitude fit rings a little longer
                    float fade = t < span - FADE ? 1 : (float) (span - t) / (FADE + 1);
                    sh[ear][a * span + t] = padded[t] * fade;
                }
            }
        }
        // as loud as the direct HRTF, averaged over the measured directions
        double decoded = 0;
        double wanted = 0;
        float[] out = new float[span];
        for (int i = 0; i < n; i++) {
            for (int ear = 0; ear < 2; ear++) {
                Arrays.fill(out, 0);
                for (int ch = 0; ch < CHANNELS; ch++) {
                    float g = y[i * CHANNELS + ch];
                    for (int t = 0; t < span; t++) {
                        out[t] += g * sh[ear][ch * span + t];
                    }
                }
                for (int t = 0; t < span; t++) {
                    decoded += out[t] * out[t];
                    wanted += direct[2 * i + ear][t] * direct[2 * i + ear][t];
                }
            }
        }
        float scale = (float) Math.sqrt(wanted / decoded);
        float[] part = new float[2 * block];
        for (int ear = 0; ear < 2; ear++) {
            for (int ch = 0; ch < CHANNELS; ch++) {
                for (int p = 0; p < parts; p++) {
                    Arrays.fill(part, 0);
                    for (int t = 0; t < block; t++) {
                        part[t] = sh[ear][ch * span + p * block + t] * scale;
                    }
                    fft.forward(part, specRe, specIm);
                    int o = ((ear * CHANNELS + ch) * parts + p) * bins;
                    System.arraycopy(specRe, 0, filterRe, o, bins);
                    System.arraycopy(specIm, 0, filterIm, o, bins);
                }
            }
        }
    }

    /** The inverse of a small symmetric positive definite matrix (Gauss-Jordan with partial pivoting). */
    private static double[][] invert(double[][] a) {
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
            double[] swap = m[col];
            m[col] = m[pivot];
            m[pivot] = swap;
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
        double[][] inverse = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(m[i], n, inverse[i], 0, n);
        }
        return inverse;
    }

    /** The solid angle of the triangle of unit vectors a, b, c (Van Oosterom and Strackee). */
    private static double solidAngle(Hrtf h, int a, int b, int c) {
        double ax = h.direction(a, 0);
        double ay = h.direction(a, 1);
        double az = h.direction(a, 2);
        double bx = h.direction(b, 0);
        double by = h.direction(b, 1);
        double bz = h.direction(b, 2);
        double cx = h.direction(c, 0);
        double cy = h.direction(c, 1);
        double cz = h.direction(c, 2);
        double triple = ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx);
        double ab = ax * bx + ay * by + az * bz;
        double bc = bx * cx + by * cy + bz * cz;
        double ca = cx * ax + cy * ay + cz * az;
        return 2 * Math.abs(com.truesim.common.math.Maths.atan2(triple, 1 + ab + bc + ca));
    }

    /**
     * The 16 real spherical harmonics up to order 3 (orthonormal over the sphere, ACN order) of the direction of
     * ({@code x}, {@code y}, {@code z}) into {@code out} from {@code offset}; straight ahead for the zero vector.
     */
    public static void encode(float x, float y, float z, float[] out, int offset) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length < 1e-9f) {
            x = 0;
            y = 0;
            z = -1;
        } else {
            x /= length;
            y /= length;
            z /= length;
        }
        // the polynomial forms, with the listener's frame as is: any frame works, encoding and filters share it
        out[offset] = 0.282_094_8f;
        out[offset + 1] = 0.488_602_5f * y;
        out[offset + 2] = 0.488_602_5f * z;
        out[offset + 3] = 0.488_602_5f * x;
        out[offset + 4] = 1.092_548_4f * x * y;
        out[offset + 5] = 1.092_548_4f * y * z;
        out[offset + 6] = 0.315_391_6f * (3 * z * z - 1);
        out[offset + 7] = 1.092_548_4f * x * z;
        out[offset + 8] = 0.546_274_2f * (x * x - y * y);
        out[offset + 9] = 0.590_043_6f * y * (3 * x * x - y * y);
        out[offset + 10] = 2.890_611_4f * x * y * z;
        out[offset + 11] = 0.457_045_8f * y * (5 * z * z - 1);
        out[offset + 12] = 0.373_176_3f * z * (5 * z * z - 3);
        out[offset + 13] = 0.457_045_8f * x * (5 * z * z - 1);
        out[offset + 14] = 1.445_305_7f * z * (x * x - y * y);
        out[offset + 15] = 0.590_043_6f * x * (x * x - 3 * y * y);
    }

    /** Fields (buses) it has room for. */
    public int fields() {
        return fields;
    }

    /** Fields decoded since made, block by block: one per active field a block, whatever the voices. */
    public long decodes() {
        return decodes;
    }

    /**
     * Adds a voice's mono block {@code mono} into field {@code field}, its 16 gains moving from {@code from} to
     * {@code to} (each at {@code offset}) over the block.
     */
    void add(int field, float[] mono, float[] from, float[] to, int offset, int frames) {
        float[] in = input[field];
        for (int ch = 0; ch < CHANNELS; ch++) {
            float g = from[offset + ch];
            float step = (to[offset + ch] - g) / frames;
            int o = ch * block;
            for (int f = 0; f < frames; f++) {
                g += step;
                in[o + f] += mono[f] * g;
            }
        }
        active[field] = parts + 1;
    }

    /** Decodes every field with input this block, or with a tail still to play, into its bus of {@code graph}. */
    void decode(BusGraph graph, int frames) {
        for (int field = 0; field < fields; field++) {
            if (active[field] > 0) {
                decode(field, graph.buffer(field), frames);
                active[field]--;
            }
        }
    }

    private void decode(int field, float[] out, int frames) {
        float[] in = input[field];
        float[] past = history[field];
        int h = (head[field] + 1) % parts;
        head[field] = h;
        for (int ch = 0; ch < CHANNELS; ch++) {
            System.arraycopy(past, ch * block, frame, 0, block);
            System.arraycopy(in, ch * block, frame, block, block);
            System.arraycopy(in, ch * block, past, ch * block, block);
            fft.forward(frame, specRe, specIm);
            int o = (ch * parts + h) * bins;
            System.arraycopy(specRe, 0, fdlRe[field], o, bins);
            System.arraycopy(specIm, 0, fdlIm[field], o, bins);
        }
        Arrays.fill(in, 0);
        float[] xr = fdlRe[field];
        float[] xi = fdlIm[field];
        for (int ear = 0; ear < 2; ear++) {
            Arrays.fill(accRe, 0);
            Arrays.fill(accIm, 0);
            for (int ch = 0; ch < CHANNELS; ch++) {
                for (int p = 0; p < parts; p++) {
                    int x = (ch * parts + (h - p + parts) % parts) * bins;
                    int f = ((ear * CHANNELS + ch) * parts + p) * bins;
                    for (int k = 0; k < bins; k++) {
                        float ar = xr[x + k];
                        float ai = xi[x + k];
                        float br = filterRe[f + k];
                        float bi = filterIm[f + k];
                        accRe[k] += ar * br - ai * bi;
                        accIm[k] += ar * bi + ai * br;
                    }
                }
            }
            fft.inverse(accRe, accIm, time);
            for (int f = 0; f < frames; f++) {
                out[2 * f + ear] += time[block + f];
            }
        }
        decodes++;
    }

    /** A layout swap renumbers the buses: the fields start again silent. */
    void clear() {
        for (int field = 0; field < fields; field++) {
            Arrays.fill(input[field], 0);
            Arrays.fill(history[field], 0);
            Arrays.fill(fdlRe[field], 0);
            Arrays.fill(fdlIm[field], 0);
            active[field] = 0;
        }
    }
}
