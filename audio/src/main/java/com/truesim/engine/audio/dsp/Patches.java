package com.truesim.engine.audio.dsp;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Patches by name (§2.13), with a pool of voices each. Registering warms the patch: a voice is made and rendered for
 * half a second on the calling (game) thread, then pooled. {@link #take} hands a pooled voice (reset) or makes one;
 * {@link #give} returns it once the audio thread has ended it. Game thread only.
 */
@AllowAllocation("registered at set-up; voices pooled")
public final class Patches {

    private final Map<String, Patch> patches = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<PatchVoice>> pools = new ConcurrentHashMap<>();
    private final Nodes nodes;
    private final int rate;
    private final int block;

    /** Patches at {@code rate} in blocks up to {@code block}. */
    public Patches(int rate, int block) {
        this.rate = rate;
        this.block = block;
        nodes = new Nodes(rate, block);
    }

    /** The node types patches name. */
    public Nodes nodes() {
        return nodes;
    }

    /** Registers {@code patch}, replacing one of its name (and its pool), and warms it. */
    public Patches register(Patch patch) {
        PatchVoice voice = patch.voice(rate, block, nodes);
        voice.fill(rate / 2);
        voice.reset();
        ArrayDeque<PatchVoice> pool = new ArrayDeque<>();
        pool.push(voice);
        patches.put(patch.name(), patch);
        pools.put(patch.name(), pool);
        return this;
    }

    /** The patch registered as {@code name}, or {@code null}. */
    public Patch patch(String name) {
        return patches.get(name);
    }

    /** A voice of the patch registered as {@code name}, ready to play; {@code null} if none is registered. */
    public PatchVoice take(String name) {
        ArrayDeque<PatchVoice> pool = pools.get(name);
        if (pool == null) {
            return null;
        }
        PatchVoice voice = pool.poll();
        return voice != null ? voice : patches.get(name).voice(rate, block, nodes);
    }

    /** Returns {@code voice} to its pool, reset; dropped if its patch was replaced meanwhile. */
    public void give(PatchVoice voice) {
        if (patches.get(voice.patch().name()) == voice.patch()) {
            voice.reset();
            pools.get(voice.patch().name()).push(voice);
        }
    }
}
