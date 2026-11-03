package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * The ambisonics bus (§2.9): HRTF voices past the cap are encoded into a third-order ambisonic field (16 real
 * spherical harmonics, orthonormal, of the direction in the listener's frame: positions are listener-relative, so
 * the field needs no rotation) and the field is decoded to both ears once a block, by convolving each harmonic with
 * an SH-domain filter pair derived from the same {@link Hrtf}: for harmonic n and ear e, the sum over the measured
 * directions d of w_d g_l Y_n(d) h_e,d, where w_d is the direction's share of the sphere (a third of its triangles'
 * solid angles), g_l the max-rE weight of the harmonic's order (fewer side lobes) and h_e,d the ear's filter delayed
 * by its interaural delay; scaled so the decoded field is as loud, averaged over the directions, as the direct HRTF.
 * The decode costs 16 forward FFTs, 32 partition products and 2 inverse FFTs a block, however many voices the field
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
    /** Max-rE weights per order for order 3 (Zotter and Frank). */
    static final float[] MAX_RE = {1, 0.861136f, 0.611985f, 0.304746f};
    private static final int[] ORDERS = {0, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3};

    private final int block;
    private final int fields;
    private final int length;
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
        length = hrtf.taps() + (int) Math.ceil(hrtf.maxDelay()) + 2 * HrtfSpatializer.BASE_DELAY + 1;
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

    /** The SH-domain filter pairs, transformed by partition. */
    private void filters(Hrtf hrtf) {
        int n = hrtf.count();
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
        float[][] sh = new float[2][CHANNELS * length];
        float[] taps = new float[hrtf.taps()];
        float[] delayed = new float[parts * block];
        float[] kernel = new float[2 * HrtfSpatializer.BASE_DELAY + 2];
        float[] y = new float[CHANNELS];
        float[][] direct = new float[n * 2][];
        for (int i = 0; i < n; i++) {
            encode((float) hrtf.direction(i, 0), (float) hrtf.direction(i, 1), (float) hrtf.direction(i, 2), y, 0);
            for (int ear = 0; ear < 2; ear++) {
                for (int t = 0; t < taps.length; t++) {
                    taps[t] = hrtf.tap(i, ear, t);
                }
                HrtfSpatializer.shift(taps, hrtf.delay(i, ear) + HrtfSpatializer.BASE_DELAY, delayed, kernel);
                direct[2 * i + ear] = Arrays.copyOf(delayed, length);
                for (int ch = 0; ch < CHANNELS; ch++) {
                    float g = (float) (share[i] * MAX_RE[ORDERS[ch]] * y[ch]);
                    for (int t = 0; t < length; t++) {
                        sh[ear][ch * length + t] += g * delayed[t];
                    }
                }
            }
        }
        // as loud as the direct HRTF, averaged over the measured directions
        double decoded = 0;
        double wanted = 0;
        float[] out = new float[length];
        for (int i = 0; i < n; i++) {
            encode((float) hrtf.direction(i, 0), (float) hrtf.direction(i, 1), (float) hrtf.direction(i, 2), y, 0);
            for (int ear = 0; ear < 2; ear++) {
                Arrays.fill(out, 0);
                for (int ch = 0; ch < CHANNELS; ch++) {
                    for (int t = 0; t < length; t++) {
                        out[t] += y[ch] * sh[ear][ch * length + t];
                    }
                }
                for (int t = 0; t < length; t++) {
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
                    for (int t = 0; t < block && p * block + t < length; t++) {
                        part[t] = sh[ear][ch * length + p * block + t] * scale;
                    }
                    fft.forward(part, specRe, specIm);
                    int o = ((ear * CHANNELS + ch) * parts + p) * bins;
                    System.arraycopy(specRe, 0, filterRe, o, bins);
                    System.arraycopy(specIm, 0, filterIm, o, bins);
                }
            }
        }
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
