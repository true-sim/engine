package com.truesim.engine.audio.dsp;

import com.truesim.annotations.AllowAllocation;
import java.util.List;

/** What a node is prepared for: the rate, the largest block, its channels, and its parameters by name. */
public final class DspSetup {

    private final int rate;
    private final int maxBlock;
    private final int channels;
    private final List<NodeParam> declared;
    private final Param[] params;

    @AllowAllocation("set up once")
    DspSetup(int rate, int maxBlock, int channels, List<NodeParam> declared) {
        this.rate = rate;
        this.maxBlock = maxBlock;
        this.channels = channels;
        this.declared = declared;
        params = new Param[declared.size()];
        for (int i = 0; i < params.length; i++) {
            params[i] = new Param(declared.get(i), maxBlock);
        }
    }

    /** A setup outside a patch (a test, a bus effect): parameters at their defaults, driven by events. */
    @AllowAllocation("set up once")
    public static DspSetup of(int rate, int maxBlock, int channels, NodeParam... params) {
        return new DspSetup(rate, maxBlock, channels, List.of(params));
    }

    public int rate() {
        return rate;
    }

    public int maxBlock() {
        return maxBlock;
    }

    public int channels() {
        return channels;
    }

    /** Parameter {@code name}; it must be declared by the node or its type. */
    public Param param(String name) {
        for (int i = 0; i < params.length; i++) {
            if (declared.get(i).name().equals(name)) {
                return params[i];
            }
        }
        throw new IllegalArgumentException(unknown(name));
    }

    @AllowAllocation("diagnostics")
    private String unknown(String name) {
        return "No parameter " + name + " is declared; declared: " + declared;
    }

    /** Index of parameter {@code name}, or -1. */
    int index(String name) {
        for (int i = 0; i < params.length; i++) {
            if (declared.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    Param param(int i) {
        return params[i];
    }

    int params() {
        return params.length;
    }

    /** Each parameter's events up to the block of {@code frames} starting at {@code frame}. */
    public void advance(long frame, int frames) {
        for (Param p : params) {
            p.advance(frame, frames);
        }
    }

    void reset() {
        for (Param p : params) {
            p.reset();
        }
    }
}
