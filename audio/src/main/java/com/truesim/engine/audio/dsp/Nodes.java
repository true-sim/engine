package com.truesim.engine.audio.dsp;

import com.truesim.annotations.AllowAllocation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Node types by name (§2.13): plugins register theirs so patches (and later data) can name them. Registering warms
 * the type on the calling (game) thread: one node is made, prepared and run for half a second of blocks, so the JIT
 * has compiled its {@code process} before an audio thread first runs it.
 */
@AllowAllocation("registered at set-up")
public final class Nodes {

    private final Map<String, NodeType<?>> types = new ConcurrentHashMap<>();
    private final int rate;
    private final int block;

    /** Types warmed at {@code rate} in blocks of {@code block}. */
    public Nodes(int rate, int block) {
        this.rate = rate;
        this.block = block;
    }

    /** Registers {@code type}, replacing one of its name, and warms it. */
    public Nodes register(NodeType<?> type) {
        DspNode node = type.factory().get();
        DspSetup setup = new DspSetup(rate, block, 1, type.params());
        float[] in = new float[block];
        float[] out = new float[block];
        DspBlock b = new DspBlock(new float[][] {in}, new float[][] {out}, 1);
        node.prepare(setup);
        b.frames(block);
        for (int k = 0, n = rate / 2 / block; k < n; k++) {
            for (int i = 0; i < block; i++) {
                in[i] = ((k * block + i) * 0x9E3779B9 >>> 8) * 0x1p-24f - 0.5f; // a scrambled ramp: not silence
            }
            setup.advance((long) k * block, block);
            node.process(b);
        }
        types.put(type.name(), type);
        return this;
    }

    /** The type registered as {@code name}. */
    public NodeType<?> type(String name) {
        NodeType<?> type = types.get(name);
        if (type == null) {
            throw new IllegalArgumentException("No node type " + name + " is registered; registered: "
                + types.keySet());
        }
        return type;
    }
}
