package com.truesim.engine.audio.dsp;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * One playing voice of a {@link Patch}: its nodes prepared, its signals' buffers, its inputs as {@link Param}s and its
 * triggers. Made on the game thread (or taken from a pool and {@link #reset}); rendered on the audio thread in blocks
 * up to the maximum into a ring of 8,192 frames that the voice reads (with pitch and Doppler) a little ahead of. Input
 * events and triggers are stamped in the patch's own frames.
 */
public final class PatchVoice {

    /** Frames the output ring holds. */
    public static final int RING = 8_192;
    /** The default ramp of an input set without one, seconds. */
    public static final double DEFAULT_RAMP = 0.005;
    private static final int TRIGGERS = 16;

    private final Patch patch;
    private final int rate;
    private final int block;
    private final Unit[] units;
    private final Unit out;
    private final Param[] inputs;
    private final float[][] triggerBuffers;
    private final long[][] triggerAt;
    private final int[] triggerCount;
    private final float[] ring = new float[RING];
    private long written;
    private final float[] sent;

    @AllowAllocation("made on the game thread, then pooled")
    PatchVoice(Patch patch, int rate, int block, Nodes nodes) {
        this.patch = patch;
        this.rate = rate;
        this.block = block;
        int n = patch.inputs().size();
        inputs = new Param[n];
        triggerBuffers = new float[n][];
        triggerAt = new long[n][];
        triggerCount = new int[n];
        sent = new float[n];
        for (int i = 0; i < n; i++) {
            var p = patch.inputs().get(i);
            inputs[i] = new Param(new NodeParam(p.name(), p.fallback(), p.min(), p.max()), block);
            if (patch.trigger(i)) {
                triggerBuffers[i] = new float[block];
                triggerAt[i] = new long[TRIGGERS];
            }
            sent[i] = p.fallback();
        }
        Patch.Spec[] specs = patch.specs();
        Unit[] byIndex = new Unit[specs.length];
        units = new Unit[specs.length];
        int[] order = patch.order();
        for (int k = 0; k < order.length; k++) {
            int i = order[k];
            byIndex[i] = make(specs[i], byIndex, nodes);
            units[k] = byIndex[i];
        }
        out = byIndex[patch.output()];
    }

    private Unit make(Patch.Spec s, Unit[] built, Nodes nodes) {
        int[] r = s.reads();
        return switch (s.kind) {
            case Patch.Spec.INPUT -> new InputUnit(block, inputs[(int) s.a]);
            case Patch.Spec.TRIGGER -> new TriggerUnit(triggerBuffers[(int) s.a]);
            case Patch.Spec.CONSTANT -> new ConstantUnit(block, (float) s.a);
            case Patch.Spec.NODE -> new NodeUnit(s, built, nodes, rate, block);
            case Patch.Spec.MIX -> new MixUnit(block, units(r, built));
            default -> new OpUnit(block, s.kind, units(r, built), s.a, s.b, rate);
        };
    }

    private static Unit[] units(int[] reads, Unit[] built) {
        Unit[] u = new Unit[reads.length];
        for (int i = 0; i < u.length; i++) {
            u[i] = built[reads[i]];
        }
        return u;
    }

    public Patch patch() {
        return patch;
    }

    /** Sets input {@code input} to {@code value}, ramped linearly over {@code rampFrames} from frame {@code frame}. */
    public void set(int input, float value, long frame, int rampFrames) {
        inputs[input].linear(value, frame, rampFrames);
    }

    /** Fires trigger {@code input} at frame {@code frame} (at the next frame rendered if that has passed). */
    public void trigger(int input, long frame) {
        long[] at = triggerAt[input];
        if (at != null && triggerCount[input] < TRIGGERS) {
            at[triggerCount[input]++] = Math.max(frame, written);
        }
    }

    /** The default ramp in frames at its rate. */
    public int defaultRamp() {
        return (int) Math.round(DEFAULT_RAMP * rate);
    }

    /** Renders until frame {@code upTo} (exclusive) is in the ring; at most {@link #RING} frames ahead of reading. */
    public void fill(long upTo) {
        if (upTo - written > RING / 2) { // out of earshot meanwhile (virtual): skip to just before what is read
            written = upTo - block;
        }
        while (written < upTo) {
            int n = (int) Math.min(block, upTo - written);
            renderBlock(n);
        }
    }

    private void renderBlock(int n) {
        long frame = written;
        for (int i = 0; i < inputs.length; i++) {
            inputs[i].advance(frame, n);
            float[] t = triggerBuffers[i];
            if (t != null) {
                Arrays.fill(t, 0, n, 0);
                long[] at = triggerAt[i];
                for (int k = 0; k < triggerCount[i]; ) {
                    if (at[k] < frame + n) {
                        t[(int) Math.max(0, at[k] - frame)] = 1;
                        at[k] = at[--triggerCount[i]];
                    } else {
                        k++;
                    }
                }
            }
        }
        for (Unit u : units) {
            u.process(n, frame);
        }
        float[] o = out.out;
        for (int i = 0; i < n; i++) {
            ring[(int) ((frame + i) & (RING - 1))] = o[i];
        }
        written += n;
    }

    /** Its output at frame {@code frame}; 0 outside what the ring holds. */
    public float sample(long frame) {
        return frame >= written || frame < written - RING || frame < 0 ? 0 : ring[(int) (frame & (RING - 1))];
    }

    /** Frames rendered so far. */
    public long written() {
        return written;
    }

    /** Game thread, before reuse: its nodes reset, its inputs at their defaults, nothing rendered. */
    public void reset() {
        written = 0;
        Arrays.fill(ring, 0);
        Arrays.fill(triggerCount, 0);
        for (int i = 0; i < inputs.length; i++) {
            inputs[i].reset();
            sent[i] = patch.inputs().get(i).fallback();
        }
        for (Unit u : units) {
            u.reset();
        }
    }

    /** The value last sent for input {@code i} (the game thread's bookkeeping). */
    public float sent(int i) {
        return sent[i];
    }

    /** Records {@code value} as sent for input {@code i}. */
    public void sent(int i, float value) {
        sent[i] = value;
    }

    // ---------------------------------------------------------------- units: a signal's block each

    private abstract static class Unit {
        final float[] out;

        Unit(float[] out) {
            this.out = out;
        }

        abstract void process(int frames, long frame);

        void reset() {
        }
    }

    private static final class InputUnit extends Unit {
        private final Param param;

        @AllowAllocation("made with its voice")
        InputUnit(int block, Param param) {
            super(new float[block]);
            this.param = param;
        }

        @Override
        void process(int frames, long frame) {
            for (int i = 0; i < frames; i++) {
                out[i] = param.at(i);
            }
        }
    }

    private static final class TriggerUnit extends Unit {
        TriggerUnit(float[] buffer) {
            super(buffer);
        }

        @Override
        void process(int frames, long frame) {
        }
    }

    private static final class ConstantUnit extends Unit {
        @AllowAllocation("made with its voice")
        ConstantUnit(int block, float value) {
            super(new float[block]);
            Arrays.fill(out, value);
        }

        @Override
        void process(int frames, long frame) {
        }
    }

    private static final class MixUnit extends Unit {
        private final Unit[] in;

        @AllowAllocation("made with its voice")
        MixUnit(int block, Unit[] in) {
            super(new float[block]);
            this.in = in;
        }

        @Override
        void process(int frames, long frame) {
            Arrays.fill(out, 0, frames, 0);
            for (Unit u : in) {
                for (int i = 0; i < frames; i++) {
                    out[i] += u.out[i];
                }
            }
        }
    }

    private static final class OpUnit extends Unit {
        private final int kind;
        private final float[] x;
        private final float[] y;
        private final float[] z;
        private final float a;
        private final float b;
        private float state;

        @AllowAllocation("made with its voice")
        OpUnit(int block, int kind, Unit[] in, double a, double b, int rate) {
            super(new float[block]);
            this.kind = kind;
            x = in[0].out;
            y = in.length > 1 ? in[1].out : null;
            z = in.length > 2 ? in[2].out : null;
            switch (kind) {
                case Patch.Spec.SMOOTH -> {
                    this.a = coefficient(a, rate);
                    this.b = 0;
                }
                case Patch.Spec.ENVELOPE -> {
                    this.a = coefficient(a, rate);
                    this.b = coefficient(b, rate);
                }
                default -> {
                    this.a = (float) a;
                    this.b = (float) b;
                }
            }
        }

        /** A one-pole's coefficient for time constant {@code seconds}: 1 (no smoothing) at 0. */
        private static float coefficient(double seconds, int rate) {
            return seconds <= 0 ? 1 : (float) (1 - Maths.exp(-1 / (seconds * rate)));
        }

        @Override
        void process(int frames, long frame) {
            switch (kind) {
                case Patch.Spec.SMOOTH -> {
                    float s = frame == 0 ? x[0] : state; // starts at its input: no glide from 0
                    for (int i = 0; i < frames; i++) {
                        s += a * (x[i] - s);
                        out[i] = s;
                    }
                    state = s;
                }
                case Patch.Spec.AFFINE -> {
                    for (int i = 0; i < frames; i++) {
                        out[i] = x[i] * a + b;
                    }
                }
                case Patch.Spec.MULTIPLY -> {
                    for (int i = 0; i < frames; i++) {
                        out[i] = x[i] * y[i];
                    }
                }
                case Patch.Spec.ADD -> {
                    for (int i = 0; i < frames; i++) {
                        out[i] = x[i] + y[i];
                    }
                }
                case Patch.Spec.CLAMP -> {
                    for (int i = 0; i < frames; i++) {
                        out[i] = Math.max(a, Math.min(b, x[i]));
                    }
                }
                case Patch.Spec.BELOW -> {
                    for (int i = 0; i < frames; i++) {
                        out[i] = x[i] < a ? 1 : 0;
                    }
                }
                case Patch.Spec.ENVELOPE -> {
                    float s = state;
                    for (int i = 0; i < frames; i++) {
                        float m = Math.abs(x[i]);
                        s += (m > s ? a : b) * (m - s);
                        out[i] = s;
                    }
                    state = s;
                }
                default -> { // WHEN
                    for (int i = 0; i < frames; i++) {
                        out[i] = x[i] > 0.5f ? y[i] : z[i];
                    }
                }
            }
        }

        @Override
        void reset() {
            state = 0;
        }
    }

    private static final class NodeUnit extends Unit {
        private final DspNode node;
        private final DspSetup setup;
        private final DspBlock blockOf;

        @AllowAllocation("made with its voice")
        NodeUnit(Patch.Spec s, Unit[] built, Nodes nodes, int rate, int block) {
            super(new float[block]);
            List<NodeParam> params;
            if (s.type != null) {
                NodeType<?> type = nodes.type(s.type);
                node = type.factory().get();
                params = type.params();
            } else {
                node = s.factory.get();
                params = node.params();
            }
            setup = new DspSetup(rate, block, 1, params);
            node.prepare(setup); // first: what it sets as its own defaults, the patch's constants then replace
            for (Map.Entry<String, Float> e : s.values.entrySet()) {
                Param p = param(e.getKey());
                p.set(e.getValue(), 0);
                p.advance(0, 1); // a constant from the first frame
            }
            for (Map.Entry<String, Integer> e : s.signals.entrySet()) {
                param(e.getKey()).drive(built[e.getValue()].out);
            }
            float[][] in = new float[Math.max(1, s.inputs.size())][];
            for (int i = 0; i < in.length; i++) {
                in[i] = i < s.inputs.size() ? built[s.inputs.get(i)].out : new float[block];
            }
            blockOf = new DspBlock(in, new float[][] {out}, 1);
        }

        private Param param(String name) {
            if (setup.index(name) < 0) {
                throw new IllegalArgumentException(unknown(name));
            }
            return setup.param(name);
        }

        @AllowAllocation("diagnostics")
        private String unknown(String name) {
            return node.getClass().getSimpleName() + " has no parameter " + name;
        }

        @Override
        void process(int frames, long frame) {
            setup.advance(frame, frames);
            blockOf.frames(frames);
            node.process(blockOf);
        }

        @Override
        void reset() {
            node.reset();
        }
    }
}
