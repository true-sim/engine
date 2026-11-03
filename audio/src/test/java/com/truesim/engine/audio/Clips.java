package com.truesim.engine.audio;

import com.truesim.scene.audio.AudioClip;

/** Clips made in code for the renderer's tests. */
final class Clips {

    private Clips() {
    }

    /** A mono float clip of {@code samples} at {@code rate}. */
    static AudioClip mono(int rate, float... samples) {
        return AudioClip.builder(rate, 1).floats(samples).build();
    }

    /** {@code frames} frames of a sine of {@code hz} at {@code amplitude}, mono at {@code rate}. */
    static AudioClip sine(int rate, double hz, float amplitude, int frames) {
        float[] s = new float[frames];
        for (int i = 0; i < frames; i++) {
            s[i] = (float) (amplitude * Math.sin(2 * Math.PI * hz * i / rate));
        }
        return mono(rate, s);
    }

    /** {@code frames} frames of {@code value}. */
    static AudioClip constant(int rate, float value, int frames) {
        float[] s = new float[frames];
        java.util.Arrays.fill(s, value);
        return mono(rate, s);
    }

    /** Sign changes from negative to positive in channel {@code channel} of interleaved {@code samples}. */
    static int upCrossings(float[] samples, int channels, int channel, int from, int to) {
        int n = 0;
        for (int f = from + 1; f < to; f++) {
            if (samples[(f - 1) * channels + channel] < 0 && samples[f * channels + channel] >= 0) {
                n++;
            }
        }
        return n;
    }

    /** The largest absolute sample of channel {@code channel} in frames [from, to). */
    static float peak(float[] samples, int channels, int channel, int from, int to) {
        float p = 0;
        for (int f = from; f < to; f++) {
            p = Math.max(p, Math.abs(samples[f * channels + channel]));
        }
        return p;
    }
}
