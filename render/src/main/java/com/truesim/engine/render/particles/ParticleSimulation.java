package com.truesim.engine.render.particles;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.SceneData;
import com.truesim.engine.render.layout.ParticlePush;
import com.truesim.engine.render.layout.ParticleRibbonPush;
import com.truesim.engine.render.layout.ParticleSortPush;
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
 * With mesh shapes ({@link Particles#meshes}) the spawn reads the renderer's geometry pool, so it runs after the scene
 * upload and the deformation pass; with mesh particles, "particle instances" writes their instances into the scene's
 * range the GPU writes, and the whole simulation runs before the scene is culled (effects.md §2.4's early group).
 *
 * <pre>{@code
 * graph.add(new ParticleSimulation(particles));
 * }</pre>
 */
public final class ParticleSimulation implements Technique {

    public static final Id<ParticleSimulation> ID = Id.of("truesim.particleSimulation", ParticleSimulation.class);
    private static final String SHADER = "shaders/particles/simulate.spv";
    private static final String RAYS_SHADER = "shaders/particles/simulate_rays.spv";
    private static final String SORT_SHADER = "shaders/particles/sort.spv";

    private final Particles particles;

    public ParticleSimulation(Particles particles) {
        this.particles = Objects.requireNonNull(particles, "particles");
    }

    @Override
    public Id<ParticleSimulation> id() {
        return ID;
    }

    /** What the simulation reads ({@link Particles#rays}, {@link Particles#meshes}): a change rebuilds the graph. */
    @Override
    public long buildVersion() {
        return particles.setup();
    }

    @Override
    public List<Key<?>> provides() {
        return List.of(ParticleKeys.PARTICLES, ParticleKeys.STATE, ParticleKeys.SIDE, ParticleKeys.RIBBON_ORDER);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Particles p0 = particles;
        Particles.Buffers b = particles.importInto(g);
        // With ray collision, the update and spawn take the rays variant, after the frame's top level
        boolean traced = particles.rayTracing() != null;
        Buffer topLevel = traced ? g.request(new TopLevel(particles.rayTracing(), particles.rayRenderer())) : null;
        String variant = traced ? RAYS_SHADER : SHADER;
        // With mesh shapes, the spawn reads the pool's meshes: after the scene upload and the deformation pass
        SceneData.Buffers scene = p0.meshRenderer() == null ? null : g.request(new SceneData(p0.meshRenderer()));
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
            .write(b.state(), BufferAccess.STORAGE_WRITE).write(b.eventArgs(), BufferAccess.STORAGE_WRITE)
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
            .write(b.state(), BufferAccess.STORAGE_WRITE).write(b.events(), BufferAccess.STORAGE_WRITE)
            .write(b.eventKeys(), BufferAccess.STORAGE_WRITE).write(b.eventArgs(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating()) {
                    dispatchChunks(ctx, update, p.push(updatePush), p.chunkCount());
                }
            });
        events(g, b);
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
        if (scene != null) {
            spawnPass.read(scene.positions(), BufferAccess.STORAGE_READ)
                .read(scene.attributes(), BufferAccess.STORAGE_READ).read(scene.meshlets(), BufferAccess.STORAGE_READ)
                .read(scene.meshletVertices(), BufferAccess.STORAGE_READ)
                .read(scene.meshletTriangles(), BufferAccess.STORAGE_READ);
        }
        spawnPass.read(b.dispatch(), BufferAccess.INDIRECT).read(b.dispatch(), BufferAccess.STORAGE_READ)
            .read(b.depth(), BufferAccess.STORAGE_READ).read(b.events(), BufferAccess.STORAGE_READ)
            .read(b.eventKeys(), BufferAccess.STORAGE_READ).read(b.eventArgs(), BufferAccess.STORAGE_READ)
            .read(b.childPrefix(), BufferAccess.STORAGE_READ)
            .write(b.particles(), BufferAccess.STORAGE_WRITE).write(b.side(), BufferAccess.STORAGE_WRITE)
            .write(b.state(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && (p.recordCount() > 0 || p.sendsEvents())) {
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
        if (scene != null && p.meshParticles()) {
            // Mesh particles' instances, before anything culls the scene (SceneData.written builds this first)
            Pipeline instances = g.computeProgram(SHADER, "instancesMain", ParticlePush.BYTES);
            ParticlePush instancesPush = new ParticlePush();
            g.pass("particle instances").read(b.particles(), BufferAccess.STORAGE_READ)
                .read(b.state(), BufferAccess.STORAGE_READ).write(scene.instances(), BufferAccess.TRANSFER_DST)
                .write(scene.instances(), BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    p.clearHoles(ctx.unsafe().commands(), ctx.unsafe().buffer(scene.instances()));
                    if (p.simulating() && p.meshEmitterCount() > 0) {
                        ctx.dispatch(instances, p.push(instancesPush), (p.largestMeshEmitter() + 63) / 64,
                            p.meshEmitterCount(), 1);
                    }
                });
        }
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

    /**
     * The event passes (E9, effects.md §2.4), when the frame's emitters send events: "particle events args" (the events
     * kept and the sort's dispatch), a radix sort by (parent, phase, event), "particle events rekey" (keys of target and
     * parent emitter), a second, stable sort, and "particle events ranges" (each sorted event's first child, each
     * target's first event and children), which the spawn args and the spawn read.
     */
    @AllowAllocation("building the graph, when it changes")
    private void events(GraphBuilder g, Particles.Buffers b) {
        Particles p = particles;
        Pipeline eventsArgs = g.computeProgram(SHADER, "eventsArgsMain", ParticlePush.BYTES);
        Pipeline rekey = g.computeProgram(SHADER, "eventsRekeyMain", ParticlePush.BYTES);
        Pipeline ranges = g.computeProgram(SHADER, "eventsRangesMain", ParticlePush.BYTES);
        ParticlePush argsPush = new ParticlePush();
        g.pass("particle events args").write(b.eventArgs(), BufferAccess.STORAGE_WRITE)
            .write(b.statistics(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.sendsEvents()) {
                    ctx.dispatch(eventsArgs, p.push(argsPush), 1);
                }
            });
        sort(g, b, "particle events by parent");
        ParticlePush rekeyPush = new ParticlePush();
        g.pass("particle events rekey").read(b.events(), BufferAccess.STORAGE_READ)
            .read(b.eventArgs(), BufferAccess.INDIRECT).read(b.eventArgs(), BufferAccess.STORAGE_READ)
            .write(b.eventKeys(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.sendsEvents()) {
                    ctx.dispatchIndirect(rekey, p.push(rekeyPush), b.eventArgs(), 0);
                }
            });
        sort(g, b, "particle events by target");
        ParticlePush rangesPush = new ParticlePush();
        g.pass("particle events ranges").read(b.events(), BufferAccess.STORAGE_READ)
            .read(b.eventKeys(), BufferAccess.STORAGE_READ).read(b.eventArgs(), BufferAccess.STORAGE_READ)
            .write(b.childPrefix(), BufferAccess.STORAGE_WRITE).write(b.state(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.sendsEvents()) {
                    ctx.dispatch(ranges, p.push(rangesPush), 1);
                }
            });
    }

    /** A radix sort of the frame's event keys in place: 8 passes of 4 bits, with the particle sort's programs. */
    @AllowAllocation("building the graph, when it changes")
    private void sort(GraphBuilder g, Particles.Buffers b, String name) {
        Particles p = particles;
        Pipeline count = g.computeProgram(SORT_SHADER, "sortCountMain", ParticleSortPush.BYTES);
        Pipeline scan = g.computeProgram(SORT_SHADER, "sortScanMain", ParticleSortPush.BYTES);
        Pipeline scatter = g.computeProgram(SORT_SHADER, "sortScatterMain", ParticleSortPush.BYTES);
        for (int pass = 0; pass < 8; pass++) {
            Buffer source = pass % 2 == 0 ? b.eventKeys() : b.eventKeysOther();
            Buffer target = pass % 2 == 0 ? b.eventKeysOther() : b.eventKeys();
            long from = pass % 2 == 0 ? p.eventKeysAddress() : p.eventKeysOtherAddress();
            long to = pass % 2 == 0 ? p.eventKeysOtherAddress() : p.eventKeysAddress();
            ParticleSortPush countPush = new ParticleSortPush().shift(4 * pass).args(p.eventArgsAddress())
                .histogram(p.eventCountsAddress()).source(from).target(to);
            g.pass(name + ", count " + pass).read(source, BufferAccess.STORAGE_READ)
                .read(b.eventArgs(), BufferAccess.INDIRECT).read(b.eventArgs(), BufferAccess.STORAGE_READ)
                .write(b.eventCounts(), BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (p.simulating() && p.sendsEvents()) {
                        ctx.dispatchIndirect(count, countPush, b.eventArgs(), 0);
                    }
                });
            g.pass(name + ", scan " + pass).read(b.eventArgs(), BufferAccess.STORAGE_READ)
                .write(b.eventCounts(), BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (p.simulating() && p.sendsEvents()) {
                        ctx.dispatch(scan, countPush, 1);
                    }
                });
            g.pass(name + ", scatter " + pass).read(source, BufferAccess.STORAGE_READ)
                .read(b.eventCounts(), BufferAccess.STORAGE_READ).read(b.eventArgs(), BufferAccess.INDIRECT)
                .read(b.eventArgs(), BufferAccess.STORAGE_READ).write(target, BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (p.simulating() && p.sendsEvents()) {
                        ctx.dispatchIndirect(scatter, countPush, b.eventArgs(), 0);
                    }
                });
        }
    }

    /** Dispatches a group per chunk (or emitter), in rows of up to 65,535 groups. */
    private static void dispatchChunks(PassContext ctx, Pipeline pipeline, ParticlePush push, int groups) {
        int x = Math.min(groups, PassContext.MAX_GROUPS);
        int y = (groups + PassContext.MAX_GROUPS - 1) / PassContext.MAX_GROUPS;
        ctx.dispatch(pipeline, push, x, y, 1);
    }
}
