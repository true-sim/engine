package com.truesim.engine.audio.dsp;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.List;

/**
 * A delay line (§2.13) with linear interpolation: parameters {@code time} (s, up to the maximum it was made with),
 * {@code feedback} (a comb when set) and {@code mix} (0 dry, 1 only the delayed signal).
 */
public final class Delay implements DspNode {

    private final double maxSeconds;
    private final List<NodeParam> params;
    private float[] line;
    private int head;
    private float rate;
    private Param time;
    private Param feedback;
    private Param mix;

    /** A delay of up to {@code maxSeconds}, starting at {@code time} seconds and {@code feedback}. */
    @AllowAllocation("made on the game thread")
    public Delay(double maxSeconds, double time, double feedback) {
        this.maxSeconds = maxSeconds;
        params = List.of(NodeParam.of("time", time, 0, maxSeconds), NodeParam.of("feedback", feedback, -0.99, 0.99),
            NodeParam.of("mix", 1, 0, 1));
    }

    /** A comb: {@code time} seconds fed back at {@code feedback}, only the delayed signal out. */
    public static Delay comb(double time, double feedback) {
        return new Delay(time, time, feedback);
    }

    @Override
    public List<NodeParam> params() {
        return params;
    }

    @Override
    @AllowAllocation("prepared on the game thread")
    public void prepare(DspSetup setup) {
        rate = setup.rate();
        line = new float[(int) Math.ceil(maxSeconds * rate) + 2];
        time = setup.param("time");
        feedback = setup.param("feedback");
        mix = setup.param("mix");
    }

    @Override
    public void process(DspBlock block) {
        float[] in = block.input(0);
        float[] out = block.output(0);
        int n = line.length;
        for (int i = 0; i < block.frames(); i++) {
            float d = Math.max(1, Math.min(n - 2, time.at(i) * rate));
            int whole = (int) d;
            float frac = d - whole;
            int a = head - whole;
            int b = a - 1;
            float delayed = line[a < 0 ? a + n : a] * (1 - frac) + line[b < 0 ? b + n : b] * frac;
            line[head] = in[i] + feedback.at(i) * delayed;
            head = head + 1 == n ? 0 : head + 1;
            float m = mix.at(i);
            out[i] = in[i] * (1 - m) + delayed * m;
        }
    }

    @Override
    public void reset() {
        Arrays.fill(line, 0);
        head = 0;
    }
}
