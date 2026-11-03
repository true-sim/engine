package com.truesim.engine.audio.dsp;

import com.truesim.common.math.random.Hash;
import java.util.List;

/**
 * Pops at random (§2.13): an exhaust's overrun crackle, a fire. Parameter {@code rate} in pops a second; each pop a
 * burst of noise decaying over 1 to 5 ms at a random level, from the hashed random.
 */
public final class Crackle implements DspNode {

    private static final List<NodeParam> PARAMS = List.of(NodeParam.of("rate", 0, 0, 200));

    private int seed;
    private Param rate;
    private float sampleRate;
    private int counter;
    private float envelope;
    private float decay;

    public static Crackle of() {
        return new Crackle();
    }

    public Crackle seed(int seed) {
        this.seed = seed;
        return this;
    }

    @Override
    public List<NodeParam> params() {
        return PARAMS;
    }

    @Override
    public void prepare(DspSetup setup) {
        rate = setup.param("rate");
        sampleRate = setup.rate();
    }

    @Override
    public void process(DspBlock block) {
        float[] out = block.output(0);
        for (int i = 0; i < block.frames(); i++) {
            int n = counter++;
            if (Hash.random(seed, 3 * n) < rate.at(i) / sampleRate) {
                envelope = 0.3f + 0.7f * Hash.random(seed, 3 * n + 1);
                float ms = 1 + 4 * Hash.random(seed, 3 * n + 2);
                decay = 1 - 1 / (ms * 0.001f * sampleRate);
            }
            out[i] = envelope * (Hash.random(seed + 7, n) * 2 - 1);
            envelope *= decay;
        }
    }

    @Override
    public void reset() {
        counter = 0;
        envelope = 0;
    }
}
