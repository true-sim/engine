package com.truesim.engine.audio.dsp;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.random.Hash;
import java.util.List;

/**
 * An engine's firing pulses (§2.13): a four-stroke engine of {@code cylinders} fires {@code rpm / 60 × cylinders / 2}
 * times a second (a V8 at 3,000 rpm: 200). Each firing is a short pressure pulse (narrower and louder with
 * {@code hardness}, the load), scaled by its cylinder's bank (the firing order: a cross-plane V8's uneven bank pattern
 * burbles) and a small per-cylinder and per-firing variation from the hashed random. DC removed.
 */
public final class FiringPulses implements DspNode {

    /** The order cylinders fire in, as gains per firing (a bank's exhaust louder than the other's). */
    public enum FiringOrder {
        /** Every firing alike. */
        EVEN(new float[] {1}),
        /** GM's 1-8-4-3-6-5-7-2 with odd cylinders on the left bank: L R R L R L L R. */
        CROSS_PLANE_V8(new float[] {1, 0.8f, 0.8f, 1, 0.8f, 1, 1, 0.8f}),
        /** Banks alternating: L R L R. */
        FLAT_PLANE_V8(new float[] {1, 0.85f});

        private final float[] banks;

        FiringOrder(float[] banks) {
            this.banks = banks;
        }

        float bank(int firing) {
            return banks[firing % banks.length];
        }
    }

    private static final List<NodeParam> PARAMS = List.of(NodeParam.of("rpm", 800, 0, 12_000),
        NodeParam.of("hardness", 0.7, 0, 1));

    private final int cylinders;
    private final FiringOrder order;
    private float jitter = 0.03f;
    private int seed;
    private float[] cylinderGains;
    private Param rpm;
    private Param hardness;
    private float rate;
    private double phase;
    private int firing;
    private int count;
    private float amplitude;
    private float since = Float.MAX_VALUE;
    private float dc;

    private FiringPulses(int cylinders, FiringOrder order) {
        this.cylinders = cylinders;
        this.order = order;
    }

    /** A four-stroke engine of {@code cylinders} firing in {@code order}. */
    public static FiringPulses fourStroke(int cylinders, FiringOrder order) {
        return new FiringPulses(cylinders, order);
    }

    /** Each firing's amplitude varies by up to {@code jitter} (a fraction). */
    public FiringPulses jitter(float jitter) {
        this.jitter = jitter;
        return this;
    }

    public FiringPulses seed(int seed) {
        this.seed = seed;
        return this;
    }

    @Override
    public List<NodeParam> params() {
        return PARAMS;
    }

    @Override
    @AllowAllocation("prepared on the game thread")
    public void prepare(DspSetup setup) {
        rate = setup.rate();
        rpm = setup.param("rpm");
        hardness = setup.param("hardness");
        cylinderGains = new float[cylinders];
        for (int c = 0; c < cylinders; c++) {
            cylinderGains[c] = 1 + 0.06f * (Hash.random(seed, c) * 2 - 1);
        }
    }

    @Override
    public void process(DspBlock block) {
        float[] out = block.output(0);
        float dcCoef = 2 * 3.1415927f * 15 / rate; // a 15 Hz high-pass
        for (int i = 0; i < block.frames(); i++) {
            float h = hardness.at(i);
            phase += rpm.at(i) / 60.0 * cylinders / 2 / rate;
            if (phase >= 1) {
                phase -= (long) phase;
                firing = (firing + 1) % cylinders;
                float variation = 1 + jitter * (Hash.random(seed + 1, count++) * 2 - 1);
                amplitude = order.bank(firing) * cylinderGains[firing] * variation * (0.4f + 0.6f * h);
                since = 0;
            }
            float width = rate * (0.0028f - 0.0018f * h);
            float v = 0;
            if (since < width) {
                float x = since / width;
                v = amplitude * 4 * x * (1 - x);
            }
            since++;
            dc += dcCoef * (v - dc);
            out[i] = v - dc;
        }
    }

    @Override
    public void reset() {
        phase = 0;
        firing = 0;
        count = 0;
        since = Float.MAX_VALUE;
        dc = 0;
    }
}
