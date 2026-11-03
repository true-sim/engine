package com.truesim.engine.audio;

import com.truesim.common.math.random.Hash;
import com.truesim.scene.audio.AudioClip;

/**
 * A tiny offline synthesizer for the listening proofs: notes of additive tones with envelopes, drums from sweeps and
 * noise, all written into stereo float buffers at 48 kHz. Test code only; nothing here is meant to be fast.
 */
final class Synth {

    static final int RATE = 48_000;
    private final float[] out;
    private final int frames;

    Synth(double seconds) {
        frames = (int) (seconds * RATE);
        out = new float[2 * frames];
    }

    static double hz(int midi) {
        return 440 * Math.pow(2, (midi - 69) / 12.0);
    }

    /** A tone of {@code harmonics} (amplitude 1/n^tilt), attack and release in seconds, panned (-1 to 1). */
    Synth note(double at, double length, int midi, double level, int harmonics, double tilt, double attack,
            double release, double pan) {
        double f0 = hz(midi);
        int start = (int) (at * RATE);
        int end = Math.min(frames, (int) ((at + length + release) * RATE));
        double l = Math.cos((pan + 1) * Math.PI / 4);
        double r = Math.sin((pan + 1) * Math.PI / 4);
        for (int i = Math.max(0, start); i < end; i++) {
            double t = (i - start) / (double) RATE;
            double env = Math.min(1, t / Math.max(1e-3, attack)) * (t > length ? Math.exp(-(t - length) / release * 4)
                : 1);
            double v = 0;
            for (int n = 1; n <= harmonics; n++) {
                double fn = f0 * n * (1 + 0.0015 * (n - 1)); // a little inharmonic: less like a test tone
                if (fn > 18_000) {
                    break;
                }
                v += Math.sin(2 * Math.PI * fn * t + n) / Math.pow(n, tilt);
            }
            v *= env * level * 0.3;
            out[2 * i] += (float) (v * l);
            out[2 * i + 1] += (float) (v * r);
        }
        return this;
    }

    /** A plucked or struck note: harmonics decaying, the high ones faster. */
    Synth pluck(double at, int midi, double level, double decay, double pan) {
        double f0 = hz(midi);
        int start = (int) (at * RATE);
        int end = Math.min(frames, start + (int) (decay * 5 * RATE));
        double l = Math.cos((pan + 1) * Math.PI / 4);
        double r = Math.sin((pan + 1) * Math.PI / 4);
        for (int i = Math.max(0, start); i < end; i++) {
            double t = (i - start) / (double) RATE;
            double v = 0;
            for (int n = 1; n <= 8; n++) {
                v += Math.sin(2 * Math.PI * f0 * n * t) * Math.exp(-t * n / decay) / n;
            }
            v *= level * 0.4 * Math.min(1, t * 400);
            out[2 * i] += (float) (v * l);
            out[2 * i + 1] += (float) (v * r);
        }
        return this;
    }

    Synth kick(double at, double level) {
        int start = (int) (at * RATE);
        double phase = 0;
        for (int i = start; i < Math.min(frames, start + RATE / 2); i++) {
            double t = (i - start) / (double) RATE;
            phase += 2 * Math.PI * (45 + 100 * Math.exp(-t * 30)) / RATE;
            double v = Math.sin(phase) * Math.exp(-t * 7) * level;
            out[2 * i] += (float) v;
            out[2 * i + 1] += (float) v;
        }
        return this;
    }

    Synth snare(double at, double level, int seed) {
        int start = (int) (at * RATE);
        double low = 0;
        for (int i = start; i < Math.min(frames, start + RATE / 3); i++) {
            double t = (i - start) / (double) RATE;
            double noise = Hash.random(seed, i) * 2 - 1;
            low += 0.5 * (noise - low);
            double v = ((noise - low) * 0.7 * Math.exp(-t * 18) + Math.sin(2 * Math.PI * 190 * t) * Math.exp(-t * 30)
                * 0.5) * level;
            out[2 * i] += (float) (v * 0.9);
            out[2 * i + 1] += (float) v;
        }
        return this;
    }

    Synth hat(double at, double level, int seed) {
        int start = (int) (at * RATE);
        double prev = 0;
        for (int i = start; i < Math.min(frames, start + RATE / 12); i++) {
            double t = (i - start) / (double) RATE;
            double noise = Hash.random(seed, i) * 2 - 1;
            double v = (noise - prev) * Math.exp(-t * 60) * level * 0.4;
            prev = noise;
            out[2 * i] += (float) (v * 0.7);
            out[2 * i + 1] += (float) v;
        }
        return this;
    }

    /** A crash: long bright noise, and a boom under it. */
    Synth crash(double at, double level, int seed) {
        int start = (int) (at * RATE);
        double prev = 0;
        for (int i = start; i < Math.min(frames, start + 3 * RATE); i++) {
            double t = (i - start) / (double) RATE;
            double noise = Hash.random(seed, i) * 2 - 1;
            double v = ((noise - prev) * 0.5 * Math.exp(-t * 1.5) + Math.sin(2 * Math.PI * 55 * t) * Math.exp(-t * 3))
                * level;
            prev = noise;
            out[2 * i] += (float) v;
            out[2 * i + 1] += (float) (v * 0.95);
        }
        return this;
    }

    AudioClip clip() {
        return AudioClip.builder(RATE, 2).floats(out).build();
    }
}
