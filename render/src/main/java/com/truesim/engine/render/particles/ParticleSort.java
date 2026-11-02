package com.truesim.engine.render.particles;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Request;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.layout.ParticleConstants;
import com.truesim.engine.render.layout.ParticleSortPush;
import com.truesim.engine.vulkan.Pipeline;
import java.util.Objects;

/**
 * The particles of the frame's sorted emitters, back to front as seen from {@code center} (effects.md §2.4, E7, E19):
 * each emitter's alive particles in a run of their own, farthest first, which {@link ParticleDraw} draws its sorted
 * billboards in. Keys are the emitter's place among the frame's sorted emitters (at most {@code SORT_EMITTERS}) above
 * the top 20 bits of the particle's squared distance from the center, sorted by an indirect radix sort of 4 bits a
 * pass (FidelityFX-style: count, scan, scatter), as many passes as the keys' bits need, even, at most 8. A request:
 * equal requests share one sort, so both eyes of a pair, drawn with the pair's center camera, sort once. Additive
 * renderers (and those {@code sorted(false)}) are not sorted; emitters draw as wholes back to front either way.
 *
 * <pre>{@code
 * ParticleSort.Order order = g.request(new ParticleSort(particles, center, pool, state));
 * }</pre>
 *
 * @param particles the particles
 * @param center the camera distances are from: the view's, or a pair of eyes' center
 * @param pool the simulated pool ({@link ParticleKeys#PARTICLES}) in the requesting build
 * @param state the emitter instances' state ({@link ParticleKeys#STATE})
 */
public record ParticleSort(Particles particles, Camera center, Buffer pool, Buffer state)
    implements Request<ParticleSort.Order> {

    private static final String SHADER = "shaders/particles/sort.spv";

    public ParticleSort {
        Objects.requireNonNull(particles, "particles");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(state, "state");
    }

    /**
     * A sort's result.
     *
     * @param sorted per key, the key and its particle's index in its emitter's range: each sorted emitter's run
     * @param args the dispatch, the key count, then from word 4 on each sorted emitter's first key in {@code sorted}
     */
    public record Order(Buffer sorted, Buffer args) {
    }

    /** Radix passes for keys of {@code sorted} emitters: 4 bits each, rounded up to even (the result in one buffer). */
    static int passes(int sorted) {
        int emitterBits = sorted <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(sorted - 1);
        int passes = (ParticleConstants.SORT_DEPTH_BITS + emitterBits + 3) / 4;
        return passes + (passes & 1);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Order build(GraphBuilder g) {
        int slots = particles.pool().slots();
        long groups = (slots + ParticleConstants.SORT_GROUP - 1) / ParticleConstants.SORT_GROUP;
        Buffer a = g.createBuffer("particle sort keys", 8L * slots);
        Buffer b = g.createBuffer("particle sort keys, other", 8L * slots);
        Buffer args = g.createBuffer("particle sort args", 4L * (4 + ParticleConstants.SORT_EMITTERS));
        Buffer histogram = g.createBuffer("particle sort counts", 4L * 16 * groups);
        Pipeline argsProgram = g.computeProgram(SHADER, "sortArgsMain", ParticleSortPush.BYTES);
        Pipeline keys = g.computeProgram(SHADER, "sortKeysMain", ParticleSortPush.BYTES);
        Pipeline count = g.computeProgram(SHADER, "sortCountMain", ParticleSortPush.BYTES);
        Pipeline scan = g.computeProgram(SHADER, "sortScanMain", ParticleSortPush.BYTES);
        Pipeline scatter = g.computeProgram(SHADER, "sortScatterMain", ParticleSortPush.BYTES);
        Particles p = particles;
        Camera camera = center;
        ParticleSortPush argsPush = new ParticleSortPush();
        g.pass("particle sort args").read(state, BufferAccess.STORAGE_READ).write(args, BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.sortedCount() > 0) {
                    ctx.dispatch(argsProgram, common(argsPush, p, camera).args(ctx.address(args)), 1);
                }
            });
        ParticleSortPush keysPush = new ParticleSortPush();
        g.pass("particle sort keys").read(pool, BufferAccess.STORAGE_READ).read(state, BufferAccess.STORAGE_READ)
            .read(args, BufferAccess.STORAGE_READ).write(a, BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.sortedCount() > 0) {
                    int chunks = p.sortChunkCount();
                    ctx.dispatch(keys, common(keysPush, p, camera).args(ctx.address(args)).target(ctx.address(a)),
                        Math.min(chunks, PassContext.MAX_GROUPS), (chunks + PassContext.MAX_GROUPS - 1)
                            / PassContext.MAX_GROUPS, 1);
                }
            });
        for (int pass = 0; pass < 8; pass++) {
            Buffer source = pass % 2 == 0 ? a : b;
            Buffer target = pass % 2 == 0 ? b : a;
            int index = pass;
            ParticleSortPush countPush = new ParticleSortPush().shift(4 * pass);
            g.pass("particle sort count " + pass).read(source, BufferAccess.STORAGE_READ)
                .read(args, BufferAccess.INDIRECT).read(args, BufferAccess.STORAGE_READ)
                .write(histogram, BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (index < passes(p.sortedCount()) && p.sortedCount() > 0) {
                        ctx.dispatchIndirect(count, common(countPush, p, camera).args(ctx.address(args))
                            .histogram(ctx.address(histogram)).source(ctx.address(source)), args, 0);
                    }
                });
            ParticleSortPush scanPush = new ParticleSortPush();
            g.pass("particle sort scan " + pass).read(args, BufferAccess.STORAGE_READ)
                .write(histogram, BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (index < passes(p.sortedCount()) && p.sortedCount() > 0) {
                        ctx.dispatch(scan, common(scanPush, p, camera).args(ctx.address(args))
                            .histogram(ctx.address(histogram)), 1);
                    }
                });
            ParticleSortPush scatterPush = new ParticleSortPush().shift(4 * pass);
            g.pass("particle sort scatter " + pass).read(source, BufferAccess.STORAGE_READ)
                .read(histogram, BufferAccess.STORAGE_READ).read(args, BufferAccess.INDIRECT)
                .read(args, BufferAccess.STORAGE_READ).write(target, BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (index < passes(p.sortedCount()) && p.sortedCount() > 0) {
                        ctx.dispatchIndirect(scatter, common(scatterPush, p, camera).args(ctx.address(args))
                            .histogram(ctx.address(histogram)).source(ctx.address(source)).target(ctx.address(target)),
                            args, 0);
                    }
                });
        }
        return new Order(a, args);
    }

    /** Sets what every pass shares. */
    private static ParticleSortPush common(ParticleSortPush push, Particles p, Camera center) {
        double x = center.position(0);
        double y = center.position(1);
        double z = center.position(2);
        float hx = (float) x;
        float hy = (float) y;
        float hz = (float) z;
        return push.particles(p.particlesAddress()).emitters(p.emitterTableAddress()).state(p.stateAddress())
            .list(p.sortListAddress()).chunks(p.sortChunksAddress()).centerHigh(hx, hy, hz)
            .centerLow((float) (x - hx), (float) (y - hy), (float) (z - hz)).sortedCount(p.sortedCount())
            .chunkCount(p.sortChunkCount());
    }
}
