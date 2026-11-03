package com.truesim.engine.audio.dsp;

import com.truesim.common.math.Maths;
import java.util.List;

/**
 * An RBJ biquad (§2.13): low-pass, high-pass, band-pass (0 dB peak) or notch, transposed direct form II. Parameters
 * {@code cutoff} (Hz) and {@code q}; coefficients from {@code Maths} when they change, every 16 samples while they move.
 */
public final class Filter implements DspNode {

    /** Its response. */
    public enum Kind {
        LOW_PASS, HIGH_PASS, BAND_PASS, NOTCH
    }

    private static final List<NodeParam> PARAMS = List.of(NodeParam.of("cutoff", 1_000, 10, 20_000),
        NodeParam.of("q", 0.7071, 0.1, 30));

    private final Kind kind;
    private final float startCutoff;
    private final float startQ;
    private Param cutoff;
    private Param q;
    private float rate;
    private float b0;
    private float b1;
    private float b2;
    private float a1;
    private float a2;
    private float z1;
    private float z2;
    private float lastCutoff = -1;
    private float lastQ = -1;

    public Filter(Kind kind, double cutoff, double q) {
        this.kind = kind;
        startCutoff = (float) cutoff;
        startQ = (float) q;
    }

    public static Filter lowPass() {
        return new Filter(Kind.LOW_PASS, 1_000, 0.7071);
    }

    public static Filter highPass() {
        return new Filter(Kind.HIGH_PASS, 1_000, 0.7071);
    }

    public static Filter bandPass(double cutoff, double q) {
        return new Filter(Kind.BAND_PASS, cutoff, q);
    }

    public static Filter notch(double cutoff, double q) {
        return new Filter(Kind.NOTCH, cutoff, q);
    }

    @Override
    public List<NodeParam> params() {
        return PARAMS;
    }

    @Override
    public void prepare(DspSetup setup) {
        rate = setup.rate();
        cutoff = setup.param("cutoff");
        q = setup.param("q");
        cutoff.set(startCutoff, 0);
        q.set(startQ, 0);
        cutoff.advance(0, 1);
        q.advance(0, 1);
    }

    private void coefficients(float f, float qq) {
        if (f == lastCutoff && qq == lastQ) {
            return;
        }
        lastCutoff = f;
        lastQ = qq;
        double w0 = Maths.TAU * Math.min(f, 0.49 * rate) / rate;
        double cs = Maths.cos(w0);
        double alpha = Maths.sin(w0) / (2 * qq);
        double a0 = 1 + alpha;
        double n0;
        double n1;
        double n2;
        switch (kind) {
            case LOW_PASS -> {
                n0 = (1 - cs) / 2;
                n1 = 1 - cs;
                n2 = n0;
            }
            case HIGH_PASS -> {
                n0 = (1 + cs) / 2;
                n1 = -(1 + cs);
                n2 = n0;
            }
            case BAND_PASS -> {
                n0 = alpha;
                n1 = 0;
                n2 = -alpha;
            }
            default -> {
                n0 = 1;
                n1 = -2 * cs;
                n2 = 1;
            }
        }
        b0 = (float) (n0 / a0);
        b1 = (float) (n1 / a0);
        b2 = (float) (n2 / a0);
        a1 = (float) (-2 * cs / a0);
        a2 = (float) ((1 - alpha) / a0);
    }

    @Override
    public void process(DspBlock block) {
        float[] in = block.input(0);
        float[] out = block.output(0);
        boolean moving = !cutoff.constant() || !q.constant();
        coefficients(cutoff.value(), q.value());
        for (int i = 0; i < block.frames(); i++) {
            if (moving && (i & 15) == 0) {
                coefficients(cutoff.at(i), q.at(i));
            }
            float x = in[i];
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            out[i] = y;
        }
    }

    @Override
    public void reset() {
        z1 = 0;
        z2 = 0;
    }
}
