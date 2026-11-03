package com.truesim.engine.audio.dsp;

import com.truesim.common.math.random.Hash;

/**
 * Noise from the hashed PCG generator (§2.13), the same for the same seed: white, or pink (Kellet's three-pole
 * approximation, about -3 dB an octave). From -1 to 1 (pink about the same loudness).
 */
public final class Noise implements DspNode {

    private final boolean pink;
    private int seed;
    private int counter;
    private float b0;
    private float b1;
    private float b2;

    private Noise(boolean pink) {
        this.pink = pink;
    }

    public static Noise white() {
        return new Noise(false);
    }

    public static Noise pink() {
        return new Noise(true);
    }

    /** Its seed. */
    public Noise seed(int seed) {
        this.seed = seed;
        return this;
    }

    @Override
    public void prepare(DspSetup setup) {
    }

    @Override
    public void process(DspBlock block) {
        float[] out = block.output(0);
        for (int i = 0; i < block.frames(); i++) {
            float w = Hash.random(seed, counter++) * 2 - 1;
            if (pink) {
                b0 = 0.99765f * b0 + w * 0.0990460f;
                b1 = 0.96300f * b1 + w * 0.2965164f;
                b2 = 0.57000f * b2 + w * 1.0526913f;
                w = (b0 + b1 + b2 + w * 0.1848f) * 0.25f;
            }
            out[i] = w;
        }
    }

    @Override
    public void reset() {
        counter = 0;
        b0 = 0;
        b1 = 0;
        b2 = 0;
    }
}
