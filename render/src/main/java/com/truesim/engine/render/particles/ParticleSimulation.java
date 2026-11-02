package com.truesim.engine.render.particles;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.layout.ParticlePush;
import com.truesim.engine.render.layout.ParticleRibbonPush;
import com.truesim.engine.render.shadows.TopLevel;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

/**
 * The frame's particle simulation (effects.md §2.4): a frame technique, once a frame for every view, providing the pool
 * ({@link ParticleKeys#PARTICLES}) and the emitter instances' state ({@link ParticleKeys#STATE}). Its passes, on the
 * graphics queue: "particle count" (survivors per chunk), "particle scan" (their slots, per emitter), "particle update"
 * (the updaters, survivors copied in order into the other half), "particle spawn args" (spawns within room, overflow
 * counted), "particle spawn" (the newborn, indirect), "particle upload" (CPU emitters' particles into their ranges),
 * "particle args" (counts for the next frame), "particle ribbons" (ribbons of several strands ordered by strand and
 * spawn index), and the statistics' readback. A frame {@link Particles#update} was not called in simulates nothing.
 *
 * <pre>{@code
 * graph.add(new ParticleSimulation(particles));
 * }</pre>
 */
public final class ParticleSimulation implements Technique {

    public static final Id<ParticleSimulation> ID = Id.of("truesim.particleSimulation", ParticleSimulation.class);
    private static final String SHADER = "shaders/particles/simulate.spv";
    private static final String RAYS_SHADER = "shaders/particles/simulate_rays.spv";

    private final Particles particles;

    public ParticleSimulation(Particles particles) {
        this.particles = Objects.requireNonNull(particles, "particles");
    }

    @Override
    public Id<ParticleSimulation> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of(ParticleKeys.PARTICLES, ParticleKeys.STATE, ParticleKeys.SIDE, ParticleKeys.RIBBON_ORDER);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Particles.Buffers b = particles.importInto(g);
        // With ray collision, the update and spawn take the rays variant, after the frame's top level
        boolean traced = particles.rayTracing() != null;
        Buffer topLevel = traced ? g.request(new TopLevel(particles.rayTracing(), particles.rayRenderer())) : null;
        String variant = traced ? RAYS_SHADER : SHADER;
        Pipeline count = g.computeProgram(SHADER, "countMain", ParticlePush.BYTES);
        Pipeline scan = g.computeProgram(SHADER, "scanMain", ParticlePush.BYTES);
        Pipeline update = g.computeProgram(variant, "updateMain", ParticlePush.BYTES);
        Pipeline spawnArgs = g.computeProgram(SHADER, "spawnArgsMain", ParticlePush.BYTES);
        Pipeline spawn = g.computeProgram(variant, "spawnMain", ParticlePush.BYTES);
        Pipeline args = g.computeProgram(SHADER, "argsMain", ParticlePush.BYTES);
        Particles p = particles;
        ParticlePush countPush = new ParticlePush();
        g.pass("particle count").read(b.particles(), BufferAccess.STORAGE_READ).read(b.state(),
                BufferAccess.STORAGE_READ).write(b.chunkCounts(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating()) {
                    dispatchChunks(ctx, count, p.push(countPush), p.chunkCount());
                }
            });
        ParticlePush scanPush = new ParticlePush();
        g.pass("particle scan").write(b.chunkCounts(), BufferAccess.STORAGE_WRITE)
            .write(b.state(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating()) {
                    dispatchChunks(ctx, scan, p.push(scanPush), p.emitterCount());
                }
            });
        ParticlePush updatePush = new ParticlePush();
        GraphBuilder.PassBuilder updatePass = g.pass("particle update");
        if (traced) {
            updatePass.read(topLevel, BufferAccess.ACCELERATION_STRUCTURE_READ);
        }
        updatePass.read(b.chunkCounts(), BufferAccess.STORAGE_READ)
            .read(b.depth(), BufferAccess.STORAGE_READ)
            .write(b.particles(), BufferAccess.STORAGE_WRITE).write(b.side(), BufferAccess.STORAGE_WRITE)
            .write(b.state(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating()) {
                    dispatchChunks(ctx, update, p.push(updatePush), p.chunkCount());
                }
            });
        ParticlePush spawnArgsPush = new ParticlePush();
        g.pass("particle spawn args").write(b.state(), BufferAccess.STORAGE_WRITE)
            .write(b.statistics(), BufferAccess.STORAGE_WRITE).write(b.dispatch(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating()) {
                    ctx.dispatch(spawnArgs, p.push(spawnArgsPush), 1);
                }
            });
        ParticlePush spawnPush = new ParticlePush();
        GraphBuilder.PassBuilder spawnPass = g.pass("particle spawn"); // after the spawn args, as declared
        if (traced) {
            spawnPass.read(topLevel, BufferAccess.ACCELERATION_STRUCTURE_READ);
        }
        spawnPass.read(b.dispatch(), BufferAccess.INDIRECT).read(b.dispatch(), BufferAccess.STORAGE_READ)
            .read(b.depth(), BufferAccess.STORAGE_READ)
            .write(b.particles(), BufferAccess.STORAGE_WRITE).write(b.side(), BufferAccess.STORAGE_WRITE)
            .write(b.state(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.recordCount() > 0) {
                    ctx.dispatchIndirect(spawn, p.push(spawnPush), b.dispatch(), 0);
                }
            });
        Pipeline upload = g.computeProgram(SHADER, "uploadMain", ParticlePush.BYTES);
        ParticlePush uploadPush = new ParticlePush();
        g.pass("particle upload").write(b.particles(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.cpuEmitterCount() > 0 && p.largestUpload() > 0) {
                    ctx.dispatch(upload, p.push(uploadPush), (p.largestUpload() + 63) / 64, p.cpuEmitterCount(), 1);
                }
            });
        ParticlePush argsPush = new ParticlePush();
        g.pass("particle args").write(b.state(), BufferAccess.STORAGE_WRITE)
            .write(b.statistics(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating()) {
                    ctx.dispatch(args, p.push(argsPush), (p.emitterCount() + 63) / 64);
                }
            });
        Pipeline ribbons = g.computeProgram("shaders/particles/ribbons.spv", "orderMain", ParticleRibbonPush.BYTES);
        ParticleRibbonPush ribbonPush = new ParticleRibbonPush();
        g.pass("particle ribbons").read(b.particles(), BufferAccess.STORAGE_READ)
            .read(b.state(), BufferAccess.STORAGE_READ).write(b.order(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.ribbonCount() > 0) {
                    ctx.dispatch(ribbons, ribbonPush.particles(p.particlesAddress())
                        .emitters(p.emitterTableAddress()).state(ctx.address(b.state()))
                        .order(ctx.address(b.order())).list(p.ribbonListAddress()).count(p.ribbonCount()),
                        p.ribbonCount());
                }
            });
        g.readback("particle statistics", b.statistics(), 0, p.statisticsBytes(), p.receiver());
        g.provide(ParticleKeys.PARTICLES, b.particles());
        g.provide(ParticleKeys.STATE, b.state());
        g.provide(ParticleKeys.SIDE, b.side());
        g.provide(ParticleKeys.RIBBON_ORDER, b.order());
    }

    /** Dispatches a group per chunk (or emitter), in rows of up to 65,535 groups. */
    private static void dispatchChunks(PassContext ctx, Pipeline pipeline, ParticlePush push, int groups) {
        int x = Math.min(groups, PassContext.MAX_GROUPS);
        int y = (groups + PassContext.MAX_GROUPS - 1) / PassContext.MAX_GROUPS;
        ctx.dispatch(pipeline, push, x, y, 1);
    }
}
