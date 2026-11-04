package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.annotations.AllowStrictMath;
import com.truesim.common.math.Maths;
import java.util.Arrays;

/**
 * Our binaural renderer (§2.9): uniformly partitioned overlap-save convolution of each voice with the minimum-phase
 * filters of an {@link Hrtf}, interpolated between the three measured directions around the source, each ear's
 * filter delayed by its interaural delay. Per channel per block (block B, FFTs of 2B points, P partitions of the
 * delayed filter): one forward FFT of the input, shared by both ears; per ear the sum of P spectrum products and one
 * inverse FFT. When the source moved by more than 0.5° the filters are interpolated again (2P forward FFTs) and the
 * block is rendered with the old and the new filters, cross-faded over the block, so a source circling the head never
 * clicks; at most {@value #UPDATES} channels change filter a block (a new voice always does), the others on the blocks
 * after.
 *
 * <p>The delay is put into the filter, not applied after it: the interpolated filter is shifted by its ear's delay
 * plus {@value #BASE_DELAY} samples with a windowed sinc (Blackman, {@value #BASE_DELAY} taps either side), so the
 * cross-fade moves the delay with the filter. (The design's per-sample Lagrange delay after the convolution lost
 * highs by its fraction, which differs between the ears: rendered level differences were 0.4 dB off the set's.)
 *
 * <p>Near the head (under 1 m) each ear looks up its own direction, from an ear 8.75 cm either side of the head's
 * centre (acoustic parallax), and gains its distance's ratio to the centre's (the interaural level growing as a
 * source comes close); both fade in from 1 m to 25 cm, so nothing jumps; the gains ramp over a block. Allocation-free
 * once made.
 */
public final class HrtfSpatializer implements Spatializer {

    /** Channels that may change filter in one block, at most, by default. */
    static final int UPDATES = 4;
    /** Half the distance between the ears, metres, by default. */
    static final float EAR = 0.0875f;
    /** Samples every ear's filter is delayed by on top of its own delay: the shifting sinc's half width. */
    static final int BASE_DELAY = 12;
    /** How far a source's direction turns before its filter changes, degrees, by default. */
    static final float MOVED_DEGREES = 0.5f;

    private final Hrtf hrtf;
    private final int capacity;
    private final int updatesPerBlock; // channels that may change filter in one block
    private final float ear; // half the distance between the ears, metres
    private final double moved; // the cosine of the turn that changes a filter
    private final int block;
    private final int length;
    private final int parts;
    private final int bins;
    private final int size;
    private final Fft fft;

    private final float[][] history;
    private final float[][] fdlRe;
    private final float[][] fdlIm;
    private final int[] head;
    private final float[][] filterRe;
    private final float[][] filterIm;
    private final float[][] oldRe;
    private final float[][] oldIm;
    private final boolean[] changed;
    private final boolean[] fresh;
    private final boolean[] pending;
    private final float[] targetDirections;
    private final float[] filterDirections;
    private final float[] gainNow;
    private final float[] gainTarget;
    private int updates;
    private long filterUpdates;

    private final float[] frame;
    private final float[] specRe;
    private final float[] specIm;
    private final float[] accRe;
    private final float[] accIm;
    private final float[] timeNew;
    private final float[] timeOld;
    private final float[] taps;
    private final float[] delayed;
    private final float[] part;
    private final float[] kernel;
    private final int[] at = new int[3];
    private final float[] weights = new float[3];
    private final Ambisonics ambisonics;

    /** A spatializer of {@code capacity} channels for blocks of {@code block} frames at {@code hrtf}'s rate. */
    public HrtfSpatializer(Hrtf hrtf, int block, int capacity) {
        this(hrtf, block, capacity, UPDATES, EAR, FIELDS, MOVED_DEGREES);
    }

    /**
     * A spatializer of {@code capacity} channels for blocks of {@code block} frames at {@code hrtf}'s rate, changing at
     * most {@code updates} channels' filters a block, with ears {@code ear} metres either side of the head's centre,
     * decoding {@code fields} ambisonic fields at most, and changing a filter once its direction turned by
     * {@code movedDegrees}.
     */
    @AllowAllocation("made on the game thread")
    public HrtfSpatializer(Hrtf hrtf, int block, int capacity, int updates, float ear, int fields,
        float movedDegrees) {
        this.hrtf = hrtf;
        this.capacity = capacity;
        this.updatesPerBlock = updates;
        this.ear = ear;
        this.moved = cosine(movedDegrees);
        this.block = block;
        length = hrtf.taps() + (int) Math.ceil(hrtf.maxDelay()) + 2 * BASE_DELAY + 1;
        parts = (length + block - 1) / block;
        size = 2 * block;
        bins = block + 1;
        fft = new Fft(size);
        history = new float[capacity][block];
        fdlRe = new float[capacity][parts * bins];
        fdlIm = new float[capacity][parts * bins];
        head = new int[capacity];
        filterRe = new float[capacity][2 * parts * bins];
        filterIm = new float[capacity][2 * parts * bins];
        oldRe = new float[capacity][2 * parts * bins];
        oldIm = new float[capacity][2 * parts * bins];
        changed = new boolean[capacity];
        fresh = new boolean[capacity];
        pending = new boolean[capacity];
        targetDirections = new float[6 * capacity];
        filterDirections = new float[6 * capacity];
        gainNow = new float[2 * capacity];
        gainTarget = new float[2 * capacity];
        frame = new float[size];
        specRe = new float[bins];
        specIm = new float[bins];
        accRe = new float[bins];
        accIm = new float[bins];
        timeNew = new float[size];
        timeOld = new float[size];
        taps = new float[hrtf.taps()];
        delayed = new float[parts * block];
        part = new float[size];
        kernel = new float[2 * BASE_DELAY + 2];
        for (int c = 0; c < capacity; c++) {
            reset(c);
        }
        ambisonics = new Ambisonics(hrtf, block, fields);
    }

    /** The cosine of {@code degrees}, the same on every platform. */
    @AllowStrictMath
    private static double cosine(float degrees) {
        return StrictMath.cos(StrictMath.toRadians(degrees));
    }

    /** Ambisonic fields (buses with voices past the cap) it decodes at most, by default. */
    static final int FIELDS = 16;

    @Override
    public Ambisonics ambisonics() {
        return ambisonics;
    }

    @Override
    public int capacity() {
        return capacity;
    }

    /** The set it renders with. */
    public Hrtf hrtf() {
        return hrtf;
    }

    /** Partitions per filter. */
    int parts() {
        return parts;
    }

    /** Filter updates made since made (a new voice's first included). */
    public long filterUpdates() {
        return filterUpdates;
    }

    @Override
    public void reset(int channel) {
        Arrays.fill(history[channel], 0);
        Arrays.fill(fdlRe[channel], 0);
        Arrays.fill(fdlIm[channel], 0);
        fresh[channel] = true;
        pending[channel] = true;
        changed[channel] = false;
        for (int i = 0; i < 6; i++) {
            targetDirections[6 * channel + i] = i == 2 || i == 5 ? -1 : 0; // ahead until placed
        }
        for (int ear = 0; ear < 2; ear++) {
            gainTarget[2 * channel + ear] = 1;
            gainNow[2 * channel + ear] = 1;
        }
    }

    @Override
    public void place(int channel, float x, float y, float z) {
        float r = Math.max(0.05f, (float) Math.sqrt(x * x + y * y + z * z));
        // near the head: each ear its own direction and its distance's gain, faded in from 1 m to 25 cm
        float s = Math.max(0, Math.min(1, (1 - r) / 0.75f));
        s = s * s * (3 - 2 * s);
        float e = ear * s;
        int o = 6 * channel;
        boolean moved = fresh[channel];
        for (int ear = 0; ear < 2; ear++) {
            float ex = x + (ear == 0 ? e : -e); // the source from the left ear (at -e) and the right (at +e)
            float distance = (float) Math.sqrt(ex * ex + y * y + z * z);
            boolean inside = distance < 1e-5f; // at the ear itself: ahead
            float dx = inside ? 0 : ex / distance;
            float dy = inside ? 0 : y / distance;
            float dz = inside ? -1 : z / distance;
            targetDirections[o + 3 * ear] = dx;
            targetDirections[o + 3 * ear + 1] = dy;
            targetDirections[o + 3 * ear + 2] = dz;
            double dot = dx * filterDirections[o + 3 * ear] + dy * filterDirections[o + 3 * ear + 1]
                + dz * filterDirections[o + 3 * ear + 2];
            moved |= dot < this.moved;
            gainTarget[2 * channel + ear] = Math.max(0.25f, Math.min(4, r / Math.max(1e-5f, distance)));
        }
        pending[channel] |= moved;
    }

    @Override
    public int tailBlocks() {
        return parts;
    }

    @Override
    public void begin() {
        updates = 0;
    }

    @Override
    public void process(int channel, float[] mono, float[] out, int frames) {
        float[] past = history[channel];
        System.arraycopy(past, 0, frame, 0, block);
        System.arraycopy(mono, 0, frame, block, block);
        System.arraycopy(mono, 0, past, 0, block);
        fft.forward(frame, specRe, specIm);
        int h = (head[channel] + 1) % parts;
        head[channel] = h;
        System.arraycopy(specRe, 0, fdlRe[channel], h * bins, bins);
        System.arraycopy(specIm, 0, fdlIm[channel], h * bins, bins);
        if (pending[channel] && (fresh[channel] || updates < updatesPerBlock)) {
            if (!fresh[channel]) {
                updates++;
            }
            update(channel);
        }
        for (int ear = 0; ear < 2; ear++) {
            convolve(channel, ear, filterRe[channel], filterIm[channel], timeNew);
            if (changed[channel]) {
                convolve(channel, ear, oldRe[channel], oldIm[channel], timeOld);
                for (int f = 0; f < block; f++) {
                    float t = (float) (f + 1) / block;
                    timeNew[block + f] = timeOld[block + f] + (timeNew[block + f] - timeOld[block + f]) * t;
                }
            }
            float gain = gainNow[2 * channel + ear];
            float gainStep = (gainTarget[2 * channel + ear] - gain) / block;
            for (int f = 0; f < block; f++) {
                gain += gainStep;
                out[2 * f + ear] += timeNew[block + f] * gain;
            }
            gainNow[2 * channel + ear] = gainTarget[2 * channel + ear];
        }
        changed[channel] = false;
    }

    /** The sum over partitions of the input spectra times the filter's, inverse transformed into {@code time}. */
    private void convolve(int channel, int ear, float[] re, float[] im, float[] time) {
        Arrays.fill(accRe, 0);
        Arrays.fill(accIm, 0);
        float[] xr = fdlRe[channel];
        float[] xi = fdlIm[channel];
        int h = head[channel];
        for (int p = 0; p < parts; p++) {
            int x = ((h - p + parts) % parts) * bins;
            int f = (ear * parts + p) * bins;
            for (int k = 0; k < bins; k++) {
                float ar = xr[x + k];
                float ai = xi[x + k];
                float br = re[f + k];
                float bi = im[f + k];
                accRe[k] += ar * br - ai * bi;
                accIm[k] += ar * bi + ai * br;
            }
        }
        fft.inverse(accRe, accIm, time);
    }

    /** Interpolates the filters and delays for where the channel's source is now; the old filters kept to fade from. */
    private void update(int channel) {
        boolean first = fresh[channel];
        if (!first) {
            System.arraycopy(filterRe[channel], 0, oldRe[channel], 0, filterRe[channel].length);
            System.arraycopy(filterIm[channel], 0, oldIm[channel], 0, filterIm[channel].length);
        }
        int o = 6 * channel;
        for (int ear = 0; ear < 2; ear++) {
            hrtf.locate(targetDirections[o + 3 * ear], targetDirections[o + 3 * ear + 1],
                targetDirections[o + 3 * ear + 2], at, weights);
            hrtf.interpolate(at, weights, ear, taps);
            shift(taps, hrtf.delay(at, weights, ear) + BASE_DELAY, delayed, kernel);
            for (int p = 0; p < parts; p++) {
                Arrays.fill(part, 0);
                System.arraycopy(delayed, p * block, part, 0, block);
                fft.forward(part, specRe, specIm);
                int f = (ear * parts + p) * bins;
                System.arraycopy(specRe, 0, filterRe[channel], f, bins);
                System.arraycopy(specIm, 0, filterIm[channel], f, bins);
            }
            System.arraycopy(targetDirections, o + 3 * ear, filterDirections, o + 3 * ear, 3);
        }
        if (first) {
            for (int ear = 0; ear < 2; ear++) {
                gainNow[2 * channel + ear] = gainTarget[2 * channel + ear];
            }
        }
        changed[channel] = !first;
        fresh[channel] = false;
        pending[channel] = false;
        filterUpdates++;
    }

    /**
     * {@code taps} delayed by {@code delay} samples (at least {@value #BASE_DELAY}) into {@code delayed}: the integer
     * part as an offset, the fraction by a Blackman-windowed sinc of {@value #BASE_DELAY} taps either side, made once
     * into {@code kernel} ({@code 2 × BASE_DELAY + 2} long), not per tap.
     */
    static void shift(float[] taps, float delay, float[] delayed, float[] kernel) {
        Arrays.fill(delayed, 0);
        int whole = (int) Math.floor(delay);
        float fraction = delay - whole;
        int half = BASE_DELAY;
        // kernel[j] weighs input sample n at output n + whole - half + j: sinc(j - half - fraction), windowed
        for (int j = 0; j < kernel.length; j++) {
            double x = j - half - fraction;
            double w = x / (half + 1);
            double window = Math.abs(w) >= 1 ? 0
                : 0.42 + 0.5 * Maths.cos(Maths.PI * w) + 0.08 * Maths.cos(2 * Maths.PI * w);
            kernel[j] = (float) ((Math.abs(x) < 1e-9 ? 1 : Maths.sin(Maths.PI * x) / (Maths.PI * x)) * window);
        }
        int n = taps.length;
        int limit = delayed.length;
        for (int i = 0; i < n; i++) {
            float v = taps[i];
            int start = i + whole - half;
            for (int j = 0; j < kernel.length; j++) {
                int k = start + j;
                if (k >= 0 && k < limit) {
                    delayed[k] += v * kernel[j];
                }
            }
        }
    }
}
