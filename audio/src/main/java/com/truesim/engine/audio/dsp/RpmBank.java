package com.truesim.engine.audio.dsp;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.audio.AudioClip;
import java.util.ArrayList;
import java.util.List;

/**
 * The sample-based engine (§2.13): loops recorded at fixed RPMs, on load and off load, each played at {@code rpm /
 * its rpm} and cross-faded with equal power to its neighbours in RPM, the on-load and off-load layers cross-faded by
 * {@code load} (from -0.2 to 0.2). Parameters {@code rpm} and {@code load} (-1 overrun to 1 full throttle). Clips in
 * memory, read with linear interpolation, mixed to mono.
 *
 * <pre>{@code
 * g.add(() -> RpmBank.builder().on(1_000, idle).on(3_000, mid).on(6_000, high).off(1_000, idleOff).off(4_000, offHigh)
 *     .build()).set("rpm", rpm).set("load", load);
 * }</pre>
 */
public final class RpmBank implements DspNode {

    private static final List<NodeParam> PARAMS = List.of(NodeParam.of("rpm", 1_000, 0, 12_000),
        NodeParam.of("load", 1, -1, 1));

    private final float[][] layers = new float[2][];
    private final AudioClip[][] clips = new AudioClip[2][];
    private final double[][] positions = new double[2][];
    private Param rpm;
    private Param load;
    private float rate;

    @AllowAllocation("built on the game thread")
    private RpmBank(Builder b) {
        for (int layer = 0; layer < 2; layer++) {
            List<Object[]> loops = b.loops.get(layer);
            loops.sort((x, y) -> Float.compare((Float) x[0], (Float) y[0]));
            layers[layer] = new float[loops.size()];
            clips[layer] = new AudioClip[loops.size()];
            positions[layer] = new double[loops.size()];
            for (int i = 0; i < loops.size(); i++) {
                layers[layer][i] = (Float) loops.get(i)[0];
                clips[layer][i] = (AudioClip) loops.get(i)[1];
            }
        }
        if (layers[0].length == 0) {
            throw new IllegalArgumentException("An RPM bank needs at least one on-load loop");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<NodeParam> params() {
        return PARAMS;
    }

    @Override
    public void prepare(DspSetup setup) {
        rpm = setup.param("rpm");
        load = setup.param("load");
        rate = setup.rate();
    }

    @Override
    public void process(DspBlock block) {
        float[] out = block.output(0);
        for (int i = 0; i < block.frames(); i++) {
            float r = rpm.at(i);
            float on = layers[1].length == 0 ? 1 : Math.max(0, Math.min(1, (load.at(i) + 0.2f) / 0.4f));
            out[i] = (float) (Math.sqrt(on) * layer(0, r) + (on < 1 ? Math.sqrt(1 - on) * layer(1, r) : 0));
        }
    }

    /** Layer {@code layer} at {@code r} rpm: its two nearest loops cross-faded; every loop's position advances. */
    private float layer(int layer, float r) {
        float[] at = layers[layer];
        int n = at.length;
        int hi = 0;
        while (hi < n && at[hi] < r) {
            hi++;
        }
        int lo = Math.max(0, hi - 1);
        hi = Math.min(n - 1, hi);
        float t = hi == lo ? 1 : (r - at[lo]) / (at[hi] - at[lo]);
        float v = 0;
        for (int i = 0; i < n; i++) {
            float x = read(layer, i, r);
            if (i == hi) {
                v += (float) Math.sqrt(t) * x;
            } else if (i == lo) {
                v += (float) Math.sqrt(1 - t) * x;
            }
        }
        return v;
    }

    private float read(int layer, int i, float r) {
        AudioClip clip = clips[layer][i];
        double p = positions[layer][i];
        long frames = clip.frames();
        long k = (long) p;
        float f = (float) (p - k);
        long k1 = k + 1 == frames ? 0 : k + 1;
        float x = 0;
        for (int c = 0; c < clip.channels(); c++) {
            x += clip.sample(k, c) * (1 - f) + clip.sample(k1, c) * f;
        }
        p += r / layers[layer][i] * clip.rate() / rate;
        positions[layer][i] = p >= frames ? p - frames * (long) (p / frames) : p;
        return x / clip.channels();
    }

    @Override
    public void reset() {
        for (double[] p : positions) {
            java.util.Arrays.fill(p, 0);
        }
    }

    /** Builds a bank of loops. */
    @AllowAllocation("built on the game thread")
    public static final class Builder {
        private final List<List<Object[]>> loops = List.of(new ArrayList<>(), new ArrayList<>());

        /** An on-load loop recorded at {@code rpm}. */
        public Builder on(double rpm, AudioClip clip) {
            loops.get(0).add(new Object[] {(float) rpm, clip});
            return this;
        }

        /** An off-load (overrun) loop recorded at {@code rpm}. */
        public Builder off(double rpm, AudioClip clip) {
            loops.get(1).add(new Object[] {(float) rpm, clip});
            return this;
        }

        public RpmBank build() {
            return new RpmBank(this);
        }
    }
}
