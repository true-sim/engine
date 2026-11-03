package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.render.layout.ParticleSortPush;
import com.truesim.engine.vulkan.Pipeline;

/**
 * The particles' radix sort (particles/sort.slang: count, scan, scatter, 4 bits a pass, stable) as passes of a graph,
 * over keys other than particles': pairs of a 32-bit key and a value, as many as word 3 of {@code args} says, the
 * dispatch over their groups (particle.slang's {@code SORT_GROUP} keys each) at its word 0.
 */
final class RadixPasses {

    private static final String SHADER = "shaders/particles/sort.spv";

    private RadixPasses() {
    }

    /**
     * Adds {@code passes} passes (even: the result back in {@code keys}) from the lowest digit up, ping-ponging
     * between {@code keys} and {@code other}, their digit counts in {@code histogram} (16 words per group).
     */
    @AllowAllocation("building the graph, when it changes")
    static void add(GraphBuilder g, String name, Buffer args, Buffer keys, Buffer other, Buffer histogram,
        int passes) {
        if (passes % 2 != 0) {
            throw new IllegalArgumentException("An even number of passes: " + passes);
        }
        Pipeline count = g.computeProgram(SHADER, "sortCountMain", ParticleSortPush.BYTES);
        Pipeline scan = g.computeProgram(SHADER, "sortScanMain", ParticleSortPush.BYTES);
        Pipeline scatter = g.computeProgram(SHADER, "sortScatterMain", ParticleSortPush.BYTES);
        for (int pass = 0; pass < passes; pass++) {
            Buffer source = pass % 2 == 0 ? keys : other;
            Buffer target = pass % 2 == 0 ? other : keys;
            ParticleSortPush countPush = new ParticleSortPush().shift(4 * pass);
            g.pass(name + " count " + pass).read(source, BufferAccess.STORAGE_READ)
                .read(args, BufferAccess.INDIRECT).read(args, BufferAccess.STORAGE_READ)
                .write(histogram, BufferAccess.STORAGE_WRITE)
                .record(ctx -> ctx.dispatchIndirect(count, countPush.args(ctx.address(args))
                    .histogram(ctx.address(histogram)).source(ctx.address(source)), args, 0));
            ParticleSortPush scanPush = new ParticleSortPush();
            g.pass(name + " scan " + pass).read(args, BufferAccess.STORAGE_READ)
                .write(histogram, BufferAccess.STORAGE_WRITE)
                .record(ctx -> ctx.dispatch(scan, scanPush.args(ctx.address(args))
                    .histogram(ctx.address(histogram)), 1));
            ParticleSortPush scatterPush = new ParticleSortPush().shift(4 * pass);
            g.pass(name + " scatter " + pass).read(source, BufferAccess.STORAGE_READ)
                .read(histogram, BufferAccess.STORAGE_READ).read(args, BufferAccess.INDIRECT)
                .read(args, BufferAccess.STORAGE_READ).write(target, BufferAccess.STORAGE_WRITE)
                .record(ctx -> ctx.dispatchIndirect(scatter, scatterPush.args(ctx.address(args))
                    .histogram(ctx.address(histogram)).source(ctx.address(source)).target(ctx.address(target)),
                    args, 0));
        }
    }
}
