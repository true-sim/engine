package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;

/**
 * Our own FFT (§2.13), allocation-free: an iterative radix-2 complex transform with its twiddles and bit reversal made
 * once from {@link Maths}, and a real transform of N points through a complex one of N / 2 (the split trick), as the
 * HRTF convolution and spectral checks need. Spectra are stored split, real and imaginary parts in their own arrays.
 * Not thread-safe: each thread its own.
 */
public final class Fft {

    private final int size;
    private final int half;
    private final float[] cos;
    private final float[] sin;
    private final int[] reverse;
    private final float[] splitCos;
    private final float[] splitSin;
    private final float[] workRe;
    private final float[] workIm;

    /** Transforms of {@code size} real points (a power of two, 4 or more). */
    @AllowAllocation("set up once")
    public Fft(int size) {
        if (size < 4 || Integer.bitCount(size) != 1) {
            throw new IllegalArgumentException("An FFT size is a power of two of 4 or more: " + size);
        }
        this.size = size;
        half = size / 2;
        cos = new float[half / 2 + 1];
        sin = new float[half / 2 + 1];
        for (int k = 0; k < cos.length; k++) {
            cos[k] = (float) Maths.cos(2 * Math.PI * k / half);
            sin[k] = (float) Maths.sin(2 * Math.PI * k / half);
        }
        reverse = new int[half];
        int bits = Integer.numberOfTrailingZeros(half);
        for (int i = 0; i < half; i++) {
            reverse[i] = bits == 0 ? 0 : Integer.reverse(i) >>> (32 - bits);
        }
        splitCos = new float[half + 1];
        splitSin = new float[half + 1];
        for (int k = 0; k <= half; k++) {
            splitCos[k] = (float) Maths.cos(2 * Math.PI * k / size);
            splitSin[k] = (float) Maths.sin(2 * Math.PI * k / size);
        }
        workRe = new float[half];
        workIm = new float[half];
    }

    /** Points of the real transform. */
    public int size() {
        return size;
    }

    /**
     * The spectrum of {@code size} real samples {@code in}: bins 0 to size / 2 into {@code re} and {@code im} (each
     * size / 2 + 1 long), unscaled: X[k] = sum of x[n] e^(-2 pi i k n / N).
     */
    public void forward(float[] in, float[] re, float[] im) {
        for (int n = 0; n < half; n++) {
            workRe[n] = in[2 * n];
            workIm[n] = in[2 * n + 1];
        }
        complex(workRe, workIm, false);
        // X[k] = (Z[k] + conj Z[N/2 - k]) / 2 - i e^(-2 pi i k / N) (Z[k] - conj Z[N/2 - k]) / 2
        for (int k = 0; k <= half; k++) {
            int a = k % half;
            int b = (half - k) % half;
            float zr = workRe[a];
            float zi = workIm[a];
            float cr = workRe[b];
            float ci = -workIm[b];
            float er = (zr + cr) * 0.5f;
            float ei = (zi + ci) * 0.5f;
            float or = (zr - cr) * 0.5f;
            float oi = (zi - ci) * 0.5f;
            float wr = splitCos[k];
            float wi = -splitSin[k];
            // -i w (or + i oi) = (wi or + wr oi) + i (wi oi - wr or)... written out:
            re[k] = er + (wi * or + wr * oi);
            im[k] = ei + (wi * oi - wr * or);
        }
    }

    /** The {@code size} real samples of spectrum {@code re}, {@code im} (bins 0 to size / 2) into {@code out}, scaled by 1/N. */
    public void inverse(float[] re, float[] im, float[] out) {
        // Z[k] = E[k] + i O[k] with E = (X[k] + conj X[N/2 - k]) / 2 and O = e^(2 pi i k / N) (X[k] - conj X[N/2 - k]) / 2
        for (int k = 0; k < half; k++) {
            float xr = re[k];
            float xi = im[k];
            float cr = re[half - k];
            float ci = -im[half - k];
            float er = (xr + cr) * 0.5f;
            float ei = (xi + ci) * 0.5f;
            float dr = (xr - cr) * 0.5f;
            float di = (xi - ci) * 0.5f;
            float wr = splitCos[k];
            float wi = splitSin[k];
            float or = dr * wr - di * wi;
            float oi = dr * wi + di * wr;
            workRe[k] = er - oi;
            workIm[k] = ei + or;
        }
        complex(workRe, workIm, true);
        float scale = 1f / half;
        for (int n = 0; n < half; n++) {
            out[2 * n] = workRe[n] * scale;
            out[2 * n + 1] = workIm[n] * scale;
        }
    }

    /** The complex transform of size / 2 points in place; {@code inverse} without scaling. */
    private void complex(float[] re, float[] im, boolean inverse) {
        int n = half;
        for (int i = 0; i < n; i++) {
            int j = reverse[i];
            if (j > i) {
                float t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        float sign = inverse ? 1 : -1;
        for (int length = 2; length <= n; length <<= 1) {
            int step = n / length;
            int halfLength = length / 2;
            for (int start = 0; start < n; start += length) {
                for (int k = 0; k < halfLength; k++) {
                    int t = k * step;
                    float wr = cos[t];
                    float wi = sin[t] * sign;
                    int a = start + k;
                    int b = a + halfLength;
                    float br = re[b] * wr - im[b] * wi;
                    float bi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - br;
                    im[b] = im[a] - bi;
                    re[a] += br;
                    im[a] += bi;
                }
            }
        }
    }
}
