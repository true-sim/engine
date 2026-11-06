package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.render.layout.RadixConstants;
import com.truesim.engine.render.layout.SortPush;
import com.truesim.engine.vulkan.Pipeline;

/**
 * The core's GPU radix sort (blocks/sort.slang: count, scan, scatter, 4 bits a pass, stable) as passes of a frame graph:
 * pairs of a 32-bit key and a 32-bit value ({@code uint2}), as many as word 3 of {@code args} says, sorted by key from
 * the lowest digit up. Word 0 to 2 of {@code args} are the dispatch over the keys' groups of {@link #GROUP} keys (rows
 * of up to 65,535 groups), which a pass before writes with the count: the sort's size is the GPU's. Any technique sorts
 * this way (blended draws by distance, particles back to front, events by target); shaders of its own ranking keys in a
 * group import {@code blocks.radix} for the same sizes and wave helpers.
 *
 * <pre>{@code
 * Buffer histogram = g.createBuffer("my sort counts", RadixSort.histogramBytes(maxKeys));
 * RadixSort.add(g, "my sort", args, keys, other, histogram, RadixSort.passes(24));
 * }</pre>
 */
public final class RadixSort {

    /** The compiled shader: entry points {@code sortCountMain}, {@code sortScanMain} and {@code sortScatterMain}. */
    public static final String SHADER = "shaders/blocks/sort.spv";
    /** Keys a group of the sort takes ({@code SORT_GROUP}). */
    public static final int GROUP = RadixConstants.SORT_GROUP;
    /** The most passes: 32-bit keys, 4 bits each. */
    public static final int MAX_PASSES = 8;

    private RadixSort() {
    }

    /** Whether a sort's pass {@code pass} runs this frame: read as each pass records, so a sort can be shorter. */
    @FunctionalInterface
    public interface Gate {

        /** Every pass runs. */
        Gate ALWAYS = pass -> true;

        /** Whether pass {@code pass} (from 0) runs. */
        boolean runs(int pass);
    }

    /** Bytes of the digit counts for up to {@code keys} keys: 16 words per group. */
    public static long histogramBytes(long keys) {
        return 64 * Math.max(1, (keys + GROUP - 1) / GROUP);
    }

    /** Passes a key of {@code bits} bits takes, rounded up to even (so the result is back in the first buffer). */
    public static int passes(int bits) {
        if (bits < 0 || bits > 32) {
            throw new IllegalArgumentException("Keys of 0 to 32 bits, not " + bits);
        }
        int passes = (bits + 3) / 4;
        return passes + (passes & 1);
    }

    /**
     * Adds {@code passes} passes (even: the result back in {@code keys}) from the lowest digit up, ping-ponging
     * between {@code keys} and {@code other}, their digit counts in {@code histogram} ({@link #histogramBytes}).
     */
    @AllowAllocation("building the graph, when it changes")
    public static void add(GraphBuilder g, String name, Buffer args, Buffer keys, Buffer other, Buffer histogram,
        int passes) {
        add(g, name, args, keys, other, histogram, passes, false, Gate.ALWAYS);
    }

    /**
     * Adds the sort as {@link #add(GraphBuilder, String, Buffer, Buffer, Buffer, Buffer, int)} does: its passes on the
     * async compute queue when {@code async}, each pass recorded only while {@code gate} lets it run (a pass left out
     * leaves its keys where they are, so a gate shortens the sort by an even number of passes, or stops it whole).
     */
    @AllowAllocation("building the graph, when it changes")
    public static void add(GraphBuilder g, String name, Buffer args, Buffer keys, Buffer other, Buffer histogram,
        int passes, boolean async, Gate gate) {
        if (passes % 2 != 0 || passes < 0 || passes > MAX_PASSES) {
            throw new IllegalArgumentException("An even number of passes up to " + MAX_PASSES + ": " + passes);
        }
        Pipeline count = g.computeProgram(SHADER, "sortCountMain", SortPush.BYTES);
        Pipeline scan = g.computeProgram(SHADER, "sortScanMain", SortPush.BYTES);
        Pipeline scatter = g.computeProgram(SHADER, "sortScatterMain", SortPush.BYTES);
        for (int pass = 0; pass < passes; pass++) {
            Buffer source = pass % 2 == 0 ? keys : other;
            Buffer target = pass % 2 == 0 ? other : keys;
            int index = pass;
            SortPush countPush = new SortPush().shift(4 * pass);
            queue(g.pass(name + " count " + pass), async).read(source, BufferAccess.STORAGE_READ)
                .read(args, BufferAccess.INDIRECT).read(args, BufferAccess.STORAGE_READ)
                .write(histogram, BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (gate.runs(index)) {
                        ctx.dispatchIndirect(count, countPush.args(ctx.address(args))
                            .histogram(ctx.address(histogram)).source(ctx.address(source)), args, 0);
                    }
                });
            SortPush scanPush = new SortPush();
            queue(g.pass(name + " scan " + pass), async).read(args, BufferAccess.STORAGE_READ)
                .write(histogram, BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (gate.runs(index)) {
                        ctx.dispatch(scan, scanPush.args(ctx.address(args)).histogram(ctx.address(histogram)), 1);
                    }
                });
            SortPush scatterPush = new SortPush().shift(4 * pass);
            queue(g.pass(name + " scatter " + pass), async).read(source, BufferAccess.STORAGE_READ)
                .read(histogram, BufferAccess.STORAGE_READ).read(args, BufferAccess.INDIRECT)
                .read(args, BufferAccess.STORAGE_READ).write(target, BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (gate.runs(index)) {
                        ctx.dispatchIndirect(scatter, scatterPush.args(ctx.address(args))
                            .histogram(ctx.address(histogram)).source(ctx.address(source)).target(ctx.address(target)),
                            args, 0);
                    }
                });
        }
    }

    private static GraphBuilder.PassBuilder queue(GraphBuilder.PassBuilder pass, boolean async) {
        return async ? pass.async() : pass;
    }
}
