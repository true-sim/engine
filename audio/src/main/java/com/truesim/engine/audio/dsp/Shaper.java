package com.truesim.engine.audio.dsp;

import java.util.List;

/**
 * A waveshaper (§2.13), 2× oversampled: the input times {@code drive} through a soft clip (a rational tanh, exactly
 * ±1 past ±3), at twice the rate by linear interpolation and back by averaging the pair, which keeps most of the
 * aliasing the clip makes out of the band.
 */
public final class Shaper implements DspNode {

    private static final List<NodeParam> PARAMS = List.of(NodeParam.of("drive", 1, 0.1, 50));

    private Param drive;
    private float last;

    public static Shaper of() {
        return new Shaper();
    }

    @Override
    public List<NodeParam> params() {
        return PARAMS;
    }

    @Override
    public void prepare(DspSetup setup) {
        drive = setup.param("drive");
    }

    /** A rational approximation of tanh, exactly ±1 from ±3. */
    static float clip(float x) {
        if (x >= 3) {
            return 1;
        }
        if (x <= -3) {
            return -1;
        }
        float x2 = x * x;
        return x * (27 + x2) / (27 + 9 * x2);
    }

    @Override
    public void process(DspBlock block) {
        float[] in = block.input(0);
        float[] out = block.output(0);
        for (int i = 0; i < block.frames(); i++) {
            float x = in[i] * drive.at(i);
            out[i] = 0.5f * (clip(0.5f * (last + x)) + clip(x));
            last = x;
        }
    }

    @Override
    public void reset() {
        last = 0;
    }
}
