package com.truesim.engine.audio.dsp;

import java.util.List;

/**
 * An attack-decay envelope (§2.13) fired by its input going above 0.5 (a patch's trigger): rises linearly to 1 over
 * {@code attack} seconds, then falls exponentially with time constant {@code decay}. Out from 0 to 1.
 */
public final class Envelope implements DspNode {

    private static final List<NodeParam> PARAMS = List.of(NodeParam.of("attack", 0.005, 0, 10),
        NodeParam.of("decay", 0.2, 0.001, 30));

    private Param attack;
    private Param decay;
    private float rate;
    private float value;
    private boolean rising;
    private float last;

    public static Envelope of() {
        return new Envelope();
    }

    @Override
    public List<NodeParam> params() {
        return PARAMS;
    }

    @Override
    public void prepare(DspSetup setup) {
        attack = setup.param("attack");
        decay = setup.param("decay");
        rate = setup.rate();
    }

    @Override
    public void process(DspBlock block) {
        float[] in = block.input(0);
        float[] out = block.output(0);
        float fall = 1 / (decay.value() * rate); // a first-order step: e^-x ~ 1 - x per sample
        float rise = 1 / Math.max(1, attack.value() * rate);
        for (int i = 0; i < block.frames(); i++) {
            if (in[i] > 0.5f && last <= 0.5f) {
                rising = true;
            }
            last = in[i];
            if (rising) {
                value += rise;
                if (value >= 1) {
                    value = 1;
                    rising = false;
                }
            } else {
                value -= value * fall;
            }
            out[i] = value;
        }
    }

    @Override
    public void reset() {
        value = 0;
        rising = false;
        last = 0;
    }
}
