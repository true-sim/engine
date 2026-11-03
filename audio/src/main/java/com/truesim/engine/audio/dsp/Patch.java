package com.truesim.engine.audio.dsp;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.audio.SoundParameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A DSP patch (AU3, §2.13): a graph of signals and nodes built once by a builder, registered by name
 * ({@link Patches}) so a {@code Sound} names it with {@code Patch.named}, and made into a {@link PatchVoice} per playing
 * voice (from a pool) on the game thread. Its inputs are the sound's parameters, sample-accurate with a 5 ms ramp;
 * its triggers fire at the sample a {@code trigger} is heard. Mono out.
 *
 * <pre>{@code
 * Patch hum = Patch.builder("hum").input(RPM).graph(g -> {
 *     Signal rpm = g.param(RPM).smooth(0.02);
 *     g.output(g.add(Oscillator::saw).set("frequency", rpm.times(1 / 60.0)).times(0.2));
 * }).build();
 * }</pre>
 *
 * Nodes are added as factories ({@code Oscillator::saw}, {@code () -> new Tube(1.6)}) or by registered type name, as
 * each voice makes its own; nodes run in the order their inputs and parameters need, not the order added.
 */
@AllowAllocation("built once on the game thread")
public final class Patch {

    private final String name;
    private final List<SoundParameter> inputs;
    private final boolean[] triggers;
    private final Spec[] specs;
    private final int[] order;
    private final int output;

    private Patch(Builder b, Graph g) {
        name = b.name;
        inputs = List.copyOf(b.inputs);
        triggers = new boolean[inputs.size()];
        for (int i = 0; i < triggers.length; i++) {
            triggers[i] = b.triggers.contains(inputs.get(i));
        }
        if (g.output < 0) {
            throw new IllegalArgumentException("Patch " + name + " has no output: call g.output(signal)");
        }
        specs = g.specs.toArray(new Spec[0]);
        for (Spec s : specs) {
            s.freeze();
        }
        output = g.output;
        order = order(specs, name);
    }

    /** A patch called {@code name}. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    /** The sound parameters it reads, inputs and triggers, in the order declared. */
    public List<SoundParameter> inputs() {
        return inputs;
    }

    /** Whether input {@code i} is a trigger. */
    public boolean trigger(int i) {
        return triggers[i];
    }

    /** A new voice of it at {@code rate} with blocks up to {@code maxBlock}, node type names resolved by {@code nodes}. */
    public PatchVoice voice(int rate, int maxBlock, Nodes nodes) {
        return new PatchVoice(this, rate, maxBlock, nodes);
    }

    Spec[] specs() {
        return specs;
    }

    int[] order() {
        return order;
    }

    int output() {
        return output;
    }

    /** Depth-first: each spec after what it reads; a signal reading itself through a node is an error. */
    private static int[] order(Spec[] specs, String name) {
        int[] order = new int[specs.length];
        byte[] mark = new byte[specs.length];
        int[] n = {0};
        for (int i = 0; i < specs.length; i++) {
            visit(i, specs, mark, order, n, name);
        }
        return order;
    }

    private static void visit(int i, Spec[] specs, byte[] mark, int[] order, int[] n, String name) {
        if (mark[i] == 2) {
            return;
        }
        if (mark[i] == 1) {
            throw new IllegalArgumentException("Patch " + name + " has a cycle through signal " + i
                + ": a node reads its own output (add a delay node for feedback)");
        }
        mark[i] = 1;
        for (int d : specs[i].reads()) {
            visit(d, specs, mark, order, n, name);
        }
        mark[i] = 2;
        order[n[0]++] = i;
    }

    /** Builds a patch: its inputs and triggers, then its graph. */
    public static final class Builder {
        private final String name;
        private final List<SoundParameter> inputs = new ArrayList<>();
        private final List<SoundParameter> triggers = new ArrayList<>();
        private Consumer<Graph> graph;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /** Reads {@code parameter}: sample-accurate, ramped over 5 ms or the instance's ramp. */
        public Builder input(SoundParameter parameter) {
            inputs.add(parameter);
            return this;
        }

        /** Reads trigger {@code parameter}: a one-sample 1 each time it fires. */
        public Builder trigger(SoundParameter parameter) {
            inputs.add(parameter);
            triggers.add(parameter);
            return this;
        }

        /** Its graph. */
        public Builder graph(Consumer<Graph> graph) {
            this.graph = graph;
            return this;
        }

        public Patch build() {
            Graph g = new Graph(inputs, triggers);
            Objects.requireNonNull(graph, "graph").accept(g);
            return new Patch(this, g);
        }
    }

    /** What a patch's graph is built with: its inputs, constants, nodes and mixes. */
    public static final class Graph {
        private final List<SoundParameter> inputs;
        private final List<SoundParameter> triggers;
        private final List<Spec> specs = new ArrayList<>();
        private int output = -1;

        private Graph(List<SoundParameter> inputs, List<SoundParameter> triggers) {
            this.inputs = inputs;
            this.triggers = triggers;
        }

        Signal signal(Spec spec) {
            specs.add(spec);
            return new Signal(this, specs.size() - 1);
        }

        /** Input {@code parameter}'s value per sample. */
        public Signal param(SoundParameter parameter) {
            int i = inputs.indexOf(parameter);
            if (i < 0 || triggers.contains(parameter)) {
                throw new IllegalArgumentException("Not an input of this patch: " + parameter);
            }
            return signal(new Spec(Spec.INPUT, new int[0], i, 0));
        }

        /** Trigger {@code parameter}: 1 at the sample it fires, else 0. */
        public Signal trigger(SoundParameter parameter) {
            int i = inputs.indexOf(parameter);
            if (i < 0 || !triggers.contains(parameter)) {
                throw new IllegalArgumentException("Not a trigger of this patch: " + parameter);
            }
            return signal(new Spec(Spec.TRIGGER, new int[0], i, 0));
        }

        /** A constant. */
        public Signal constant(double value) {
            return signal(new Spec(Spec.CONSTANT, new int[0], value, 0));
        }

        /** A node, each voice making its own with {@code factory}. */
        public Node add(Supplier<? extends DspNode> factory) {
            Spec spec = new Spec(Spec.NODE, new int[0], 0, 0);
            spec.factory = factory;
            specs.add(spec);
            return new Node(this, specs.size() - 1, spec);
        }

        /** A node of the type registered as {@code type} ({@link Nodes}), resolved when a voice is made. */
        public Node add(String type) {
            Spec spec = new Spec(Spec.NODE, new int[0], 0, 0);
            spec.type = type;
            specs.add(spec);
            return new Node(this, specs.size() - 1, spec);
        }

        /** The sum of {@code signals}. */
        public Signal mix(Signal... signals) {
            int[] reads = new int[signals.length];
            for (int i = 0; i < reads.length; i++) {
                reads[i] = signals[i].index;
            }
            return signal(new Spec(Spec.MIX, reads, 0, 0));
        }

        /** The patch's output. */
        public void output(Signal signal) {
            output = signal.index;
        }
    }

    /** A signal in a patch: a block of samples per voice, shaped by the operations here into new signals. */
    public static class Signal {
        final Graph graph;
        final int index;

        Signal(Graph graph, int index) {
            this.graph = graph;
            this.index = index;
        }

        private Signal op(int kind, double a, double b) {
            return graph.signal(new Spec(kind, new int[] {index}, a, b));
        }

        /** Smoothed by a one-pole of time constant {@code seconds}. */
        public Signal smooth(double seconds) {
            return op(Spec.SMOOTH, seconds, 0);
        }

        /** Mapped linearly from [{@code inMin}, {@code inMax}] to [{@code outMin}, {@code outMax}] (not clamped). */
        public Signal remap(double inMin, double inMax, double outMin, double outMax) {
            double scale = (outMax - outMin) / (inMax - inMin);
            return op(Spec.AFFINE, scale, outMin - inMin * scale);
        }

        public Signal times(double k) {
            return op(Spec.AFFINE, k, 0);
        }

        /** The same as {@link #times(double)}: a gain. */
        public Signal gain(double k) {
            return times(k);
        }

        public Signal plus(double k) {
            return op(Spec.AFFINE, 1, k);
        }

        public Signal times(Signal other) {
            return graph.signal(new Spec(Spec.MULTIPLY, new int[] {index, other.index}, 0, 0));
        }

        public Signal plus(Signal other) {
            return graph.signal(new Spec(Spec.ADD, new int[] {index, other.index}, 0, 0));
        }

        public Signal clamp(double min, double max) {
            return op(Spec.CLAMP, min, max);
        }

        /** 1 where it is below {@code x}, else 0. */
        public Signal below(double x) {
            return op(Spec.BELOW, x, 0);
        }

        /** Its envelope: a follower of its magnitude rising over {@code attack} and falling over {@code release} s. */
        public Signal envelope(double attack, double release) {
            return op(Spec.ENVELOPE, attack, release);
        }

        /** {@code a} where {@code condition} is above 0.5, else {@code b}. */
        public static Signal when(Signal condition, Signal a, Signal b) {
            return condition.graph.signal(new Spec(Spec.WHEN, new int[] {condition.index, a.index, b.index}, 0, 0));
        }

        /** {@code a} where {@code condition} is above 0.5, else {@code b}. */
        public static Signal when(Signal condition, Signal a, double b) {
            return when(condition, a, condition.graph.constant(b));
        }
    }

    /** A node in a patch: its inputs and parameters wired to signals or constants. */
    public static final class Node extends Signal {
        private final Spec spec;

        Node(Graph graph, int index, Spec spec) {
            super(graph, index);
            this.spec = spec;
        }

        /** Its inputs, in order (a trigger or side chain after the audio input). */
        public Node in(Signal... signals) {
            for (Signal s : signals) {
                spec.inputs.add(s.index);
            }
            return this;
        }

        /** Parameter {@code param} follows {@code signal}, sample by sample. */
        public Node set(String param, Signal signal) {
            spec.signals.put(param, signal.index);
            spec.values.remove(param);
            return this;
        }

        /** Parameter {@code param} is {@code value}. */
        public Node set(String param, double value) {
            spec.values.put(param, (float) value);
            spec.signals.remove(param);
            return this;
        }
    }

    /** One signal's description: what kind, what it reads, its constants; a node's factory and wiring. */
    static final class Spec {
        static final int INPUT = 0;
        static final int TRIGGER = 1;
        static final int CONSTANT = 2;
        static final int NODE = 3;
        static final int MIX = 4;
        static final int SMOOTH = 5;
        static final int AFFINE = 6;
        static final int MULTIPLY = 7;
        static final int ADD = 8;
        static final int CLAMP = 9;
        static final int BELOW = 10;
        static final int ENVELOPE = 11;
        static final int WHEN = 12;

        final int kind;
        private int[] reads;
        final double a;
        final double b;
        Supplier<? extends DspNode> factory;
        String type;
        final List<Integer> inputs = new ArrayList<>();
        final Map<String, Integer> signals = new LinkedHashMap<>();
        final Map<String, Float> values = new LinkedHashMap<>();

        Spec(int kind, int[] reads, double a, double b) {
            this.kind = kind;
            this.reads = reads;
            this.a = a;
            this.b = b;
        }

        int[] reads() {
            return reads;
        }

        /** A node reads its inputs and the signals its parameters follow. */
        void freeze() {
            if (kind == NODE) {
                int[] r = new int[inputs.size() + signals.size()];
                int n = 0;
                for (int i : inputs) {
                    r[n++] = i;
                }
                for (int i : signals.values()) {
                    r[n++] = i;
                }
                reads = r;
            }
        }
    }
}
