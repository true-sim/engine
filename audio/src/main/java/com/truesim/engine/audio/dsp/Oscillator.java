package com.truesim.engine.audio.dsp;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import java.util.List;

/**
 * An oscillator by phase accumulator (§2.13): a sine from a 4,096-point table with linear interpolation, and saw,
 * square and triangle band-limited by polyBLEP (the triangle a leaky integral of the band-limited square). Parameter
 * {@code frequency} in Hz, sample-accurate; output from -1 to 1.
 */
public final class Oscillator implements DspNode {

    /** Its waveform. */
    public enum Shape {
        SINE, SAW, SQUARE, TRIANGLE
    }

    private static final int TABLE = 4_096;
    private static final float[] SINE = sineTable();
    private static final List<NodeParam> PARAMS = List.of(NodeParam.of("frequency", 440, 0, 20_000));

    private final Shape shape;
    private Param frequency;
    private float rate;
    private double phase;
    private float integral;

    public Oscillator(Shape shape) {
        this.shape = shape;
    }

    public static Oscillator sine() {
        return new Oscillator(Shape.SINE);
    }

    public static Oscillator saw() {
        return new Oscillator(Shape.SAW);
    }

    public static Oscillator square() {
        return new Oscillator(Shape.SQUARE);
    }

    public static Oscillator triangle() {
        return new Oscillator(Shape.TRIANGLE);
    }

    @AllowAllocation("a table made once")
    private static float[] sineTable() {
        float[] t = new float[TABLE + 1];
        for (int i = 0; i <= TABLE; i++) {
            t[i] = (float) Maths.sin(Maths.TAU * i / TABLE);
        }
        return t;
    }

    @Override
    public List<NodeParam> params() {
        return PARAMS;
    }

    @Override
    public void prepare(DspSetup setup) {
        frequency = setup.param("frequency");
        rate = setup.rate();
    }

    /** polyBLEP: the residual of a band-limited step at phase {@code t}, increment {@code dt}. */
    static float blep(double t, double dt) {
        if (t < dt) {
            double x = t / dt;
            return (float) (x + x - x * x - 1);
        }
        if (t > 1 - dt) {
            double x = (t - 1) / dt;
            return (float) (x * x + x + x + 1);
        }
        return 0;
    }

    @Override
    public void process(DspBlock block) {
        float[] out = block.output(0);
        double p = phase;
        for (int i = 0; i < block.frames(); i++) {
            double dt = Math.min(0.5, frequency.at(i) / rate);
            float v;
            switch (shape) {
                case SINE -> {
                    double x = p * TABLE;
                    int k = (int) x;
                    float f = (float) (x - k);
                    v = SINE[k] + (SINE[k + 1] - SINE[k]) * f;
                }
                case SAW -> v = (float) (2 * p - 1) - blep(p, dt);
                default -> {
                    double q = p + 0.5;
                    v = (p < 0.5 ? 1 : -1) + blep(p, dt) - blep(q >= 1 ? q - 1 : q, dt);
                    if (shape == Shape.TRIANGLE) {
                        integral = 0.9995f * integral + (float) (4 * dt) * v;
                        v = integral;
                    }
                }
            }
            out[i] = v;
            p += dt;
            if (p >= 1) {
                p -= 1;
            }
        }
        phase = p;
    }

    @Override
    public void reset() {
        phase = 0;
        integral = 0;
    }
}
