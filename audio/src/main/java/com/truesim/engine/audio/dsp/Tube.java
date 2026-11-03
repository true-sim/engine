package com.truesim.engine.audio.dsp;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.List;

/**
 * A tube as a waveguide (§2.13): the wave travels to the open end and back ({@code 2 L / 343} s) and returns inverted
 * through a one-pole low-pass (parameter {@code damping}, 0 bright to 1 dull) at {@code reflection}; out is the input
 * plus what returns. An exhaust pipe then resonates at odd multiples of {@code 343 / (4 L)} Hz.
 */
public final class Tube implements DspNode {

    private static final double SPEED_OF_SOUND = 343;
    private static final List<NodeParam> PARAMS = List.of(NodeParam.of("damping", 0.35, 0, 1),
        NodeParam.of("reflection", 0.6, 0, 0.98));

    private final double length;
    private float[] line;
    private int head;
    private float low;
    private Param damping;
    private Param reflection;

    /** A tube {@code length} metres long. */
    public Tube(double length) {
        this.length = length;
    }

    public static Tube of(double length) {
        return new Tube(length);
    }

    @Override
    public List<NodeParam> params() {
        return PARAMS;
    }

    @Override
    @AllowAllocation("prepared on the game thread")
    public void prepare(DspSetup setup) {
        line = new float[Math.max(2, (int) Math.round(2 * length / SPEED_OF_SOUND * setup.rate()))];
        damping = setup.param("damping");
        reflection = setup.param("reflection");
    }

    @Override
    public void process(DspBlock block) {
        float[] in = block.input(0);
        float[] out = block.output(0);
        float k = 1 - 0.95f * damping.value();
        float r = reflection.value();
        for (int i = 0; i < block.frames(); i++) {
            float back = line[head];
            low += k * (back - low);
            float y = in[i] - r * low;
            line[head] = y;
            head = head + 1 == line.length ? 0 : head + 1;
            out[i] = y;
        }
    }

    @Override
    public void reset() {
        Arrays.fill(line, 0);
        head = 0;
        low = 0;
    }
}
