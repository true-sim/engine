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
import com.truesim.engine.render.layout.ParticleConstants;
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
    private boolean async;
    private boolean queue; // the build's: its passes on the async compute queue

    public ParticleSimulation(Particles particles) {
        this.particles = Objects.requireNonNull(particles, "particles");
    }

    /**
     * Runs the simulation's passes on the async compute queue where the device has one (effects.md §2.4's main group),
     * beside the shadow maps and opaque drawing, the first draw waiting for it; off by default. Not with mesh particles
     * or mesh shapes (their reads and writes of the scene order them about culling, on the graphics queue) or ray
     * collision: those frames simulate on the graphics queue.
     */
    public ParticleSimulation async(boolean on) {
        async = on;
        return this;
    }

    /** Whether the simulation is asked to run on the async compute queue. */
    public boolean async() {
        return async;
    }

    @Override
    public Id<ParticleSimulation> id() {
        return ID;
    }

    /** What the simulation reads ({@link Particles#rays}, {@link Particles#meshes}): a change rebuilds the graph. */
    @Override
    public long buildVersion() {
        return particles.setup() * 4 + (particles.parentRibbons() ? 2 : 0) + (async ? 1 : 0);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public List<Key<?>> provides() {
        return particles.previousInstances() == null
            ? List.of(ParticleKeys.PARTICLES, ParticleKeys.STATE, ParticleKeys.SIDE, ParticleKeys.RIBBON_ORDER)
            : List.of(ParticleKeys.PARTICLES, ParticleKeys.STATE, ParticleKeys.SIDE, ParticleKeys.RIBBON_ORDER,
                ParticleKeys.PREVIOUS_INSTANCES);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Particles p0 = particles;
        Particles.Buffers b = particles.importInto(g);
        // With ray collision, the update and spawn take the rays variant, after the frame's top level
        boolean traced = particles.rayTracing() != null;
        Buffer topLevel = traced ? g.request(new TopLevel(particles.rayTracing(), particles.rayRenderer())) : null;
        String variant = particles.modules() != null ? particles.modules().shader() : traced ? RAYS_SHADER : SHADER;
        queue = async && particles.asyncAllowed();
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
        q(g.pass("particle count")).read(b.particles(), BufferAccess.STORAGE_READ).read(b.state(),
                BufferAccess.STORAGE_READ).write(b.chunkCounts(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating()) {
                    dispatchChunks(ctx, count, p.push(countPush), p.chunkCount());
                }
            });
        ParticlePush scanPush = new ParticlePush();
        q(g.pass("particle scan")).write(b.chunkCounts(), BufferAccess.STORAGE_WRITE)
            .write(b.state(), BufferAccess.STORAGE_WRITE).write(b.eventArgs(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating()) {
                    dispatchChunks(ctx, scan, p.push(scanPush), p.emitterCount());
                }
            });
        ParticlePush updatePush = new ParticlePush();
        GraphBuilder.PassBuilder updatePass = q(g.pass("particle update"));
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
        q(g.pass("particle spawn args")).write(b.state(), BufferAccess.STORAGE_WRITE)
            .write(b.statistics(), BufferAccess.STORAGE_WRITE).write(b.dispatch(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating()) {
                    ctx.dispatch(spawnArgs, p.push(spawnArgsPush), 1);
                }
            });
        ParticlePush spawnPush = new ParticlePush();
        GraphBuilder.PassBuilder spawnPass = q(g.pass("particle spawn")); // after the spawn args, as declared
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
        q(g.pass("particle upload")).write(b.particles(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.cpuEmitterCount() > 0 && p.largestUpload() > 0) {
                    ctx.dispatch(upload, p.push(uploadPush), (p.largestUpload() + 63) / 64, p.cpuEmitterCount(), 1);
                }
            });
        ParticlePush argsPush = new ParticlePush();
        q(g.pass("particle args")).write(b.state(), BufferAccess.STORAGE_WRITE)
            .write(b.statistics(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating()) {
                    ctx.dispatch(args, p.push(argsPush), (p.emitterCount() + 63) / 64);
                }
            });
        Buffer previous = p.previousInstances() == null ? null : g.importBuffer(p.previousInstances());
        if (scene != null && p.meshParticles()) {
            // Mesh particles' instances, before anything culls the scene (SceneData.written builds this first), and
            // their transforms a frame before (motion vectors, step 21)
            Pipeline instances = g.computeProgram(SHADER, "instancesMain", ParticlePush.BYTES);
            ParticlePush instancesPush = new ParticlePush();
            GraphBuilder.PassBuilder instancesPass = q(g.pass("particle instances"));
            if (previous != null) {
                instancesPass.write(previous, BufferAccess.STORAGE_WRITE);
            }
            instancesPass.read(b.particles(), BufferAccess.STORAGE_READ)
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
        if (p.lights() != null && p.lights().gpuLightsBuffer() != null) {
            lights(g, b);
        }
        Pipeline ribbons = g.computeProgram("shaders/particles/ribbons.spv", "orderMain", ParticleRibbonPush.BYTES);
        ParticleRibbonPush ribbonPush = new ParticleRibbonPush();
        q(g.pass("particle ribbons")).read(b.particles(), BufferAccess.STORAGE_READ)
            .read(b.state(), BufferAccess.STORAGE_READ).write(b.order(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.ribbonCount() > 0) {
                    ctx.dispatch(ribbons, ribbonPush.particles(p.particlesAddress())
                        .emitters(p.emitterTableAddress()).state(ctx.address(b.state()))
                        .order(ctx.address(b.order())).list(p.ribbonListAddress()).count(p.ribbonCount()),
                        p.ribbonCount());
                }
            });
        if (p.parentRibbons()) {
            parentRibbons(g, b);
        }
        if (previous != null) {
            g.provide(ParticleKeys.PREVIOUS_INSTANCES, previous);
        }
        g.readback("particle statistics", b.statistics(), 0, p.statisticsBytes(), p.receiver());
        g.provide(ParticleKeys.PARTICLES, b.particles());
        g.provide(ParticleKeys.STATE, b.state());
        g.provide(ParticleKeys.SIDE, b.side());
        g.provide(ParticleKeys.RIBBON_ORDER, b.order());
    }

    /**
     * The event passes (E9, effects.md §2.4), when the frame's emitters send events: "particle events args" (the events
     * kept and the sorts' dispatches), "particle events small" (when they are at most {@code EVENT_SMALL}: both sorts
     * and the rekey in one group), else a radix sort by (parent, phase, event), "particle events rekey" (keys of target
     * and parent emitter) and a second, stable sort; then "particle events ranges" (each sorted event's first child,
     * each target's first event and children), which the spawn args and the spawn read. The GPU picks the sort by the
     * events kept (the other's dispatches get no groups); the radix sort's 48 passes are recorded only while recent
     * frames sent many events ({@link Particles#eventsDropped}), else the small sort takes at most its capacity.
     */
    @AllowAllocation("building the graph, when it changes")
    private void events(GraphBuilder g, Particles.Buffers b) {
        Particles p = particles;
        Pipeline eventsArgs = g.computeProgram(SHADER, "eventsArgsMain", ParticlePush.BYTES);
        Pipeline rekey = g.computeProgram(SHADER, "eventsRekeyMain", ParticlePush.BYTES);
        Pipeline ranges = g.computeProgram(SHADER, "eventsRangesMain", ParticlePush.BYTES);
        ParticlePush argsPush = new ParticlePush();
        q(g.pass("particle events args")).write(b.eventArgs(), BufferAccess.STORAGE_WRITE)
            .write(b.statistics(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.sendsEvents()) {
                    ctx.dispatch(eventsArgs, p.push(argsPush), 1);
                }
            });
        Pipeline small = g.computeProgram(SHADER, "eventsSmallMain", ParticlePush.BYTES);
        ParticlePush smallPush = new ParticlePush();
        q(g.pass("particle events small")).read(b.events(), BufferAccess.STORAGE_READ)
            .read(b.eventArgs(), BufferAccess.INDIRECT).read(b.eventArgs(), BufferAccess.STORAGE_READ)
            .write(b.eventKeys(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.sendsEvents()) {
                    ctx.dispatchIndirect(small, p.push(smallPush), b.eventArgs(), 20); // its dispatch: words 5 to 7
                }
            });
        sort(g, b, "particle events by parent");
        ParticlePush rekeyPush = new ParticlePush();
        q(g.pass("particle events rekey")).read(b.events(), BufferAccess.STORAGE_READ)
            .read(b.eventArgs(), BufferAccess.INDIRECT).read(b.eventArgs(), BufferAccess.STORAGE_READ)
            .write(b.eventKeys(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.sendsEvents() && p.radixEvents()) {
                    ctx.dispatchIndirect(rekey, p.push(rekeyPush), b.eventArgs(), 0);
                }
            });
        sort(g, b, "particle events by target");
        ParticlePush rangesPush = new ParticlePush();
        q(g.pass("particle events ranges")).read(b.events(), BufferAccess.STORAGE_READ)
            .read(b.eventKeys(), BufferAccess.STORAGE_READ).read(b.eventArgs(), BufferAccess.STORAGE_READ)
            .write(b.childPrefix(), BufferAccess.STORAGE_WRITE).write(b.state(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.sendsEvents()) {
                    ctx.dispatch(ranges, p.push(rangesPush), 1);
                }
            });
    }

    /**
     * A radix sort of the frame's event keys in place: 8 passes of 4 bits, with the particle sort's programs; recorded
     * while recent frames sent many events.
     */
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
            q(g.pass(name + ", count " + pass)).read(source, BufferAccess.STORAGE_READ)
                .read(b.eventArgs(), BufferAccess.INDIRECT).read(b.eventArgs(), BufferAccess.STORAGE_READ)
                .write(b.eventCounts(), BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (p.simulating() && p.sendsEvents() && p.radixEvents()) {
                        ctx.dispatchIndirect(count, countPush, b.eventArgs(), 0);
                    }
                });
            q(g.pass(name + ", scan " + pass)).read(b.eventArgs(), BufferAccess.STORAGE_READ)
                .write(b.eventCounts(), BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (p.simulating() && p.sendsEvents() && p.radixEvents()) {
                        ctx.dispatch(scan, countPush, 1);
                    }
                });
            q(g.pass(name + ", scatter " + pass)).read(source, BufferAccess.STORAGE_READ)
                .read(b.eventCounts(), BufferAccess.STORAGE_READ).read(b.eventArgs(), BufferAccess.INDIRECT)
                .read(b.eventArgs(), BufferAccess.STORAGE_READ).write(target, BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (p.simulating() && p.sendsEvents() && p.radixEvents()) {
                        ctx.dispatchIndirect(scatter, countPush, b.eventArgs(), 0);
                    }
                });
        }
    }

    /**
     * The particle lights (step 17b): "particle lights count" clears the selection and counts each light emitter's
     * candidates by reach (brightness times radius), "particle lights threshold" finds where each emitter's budget
     * (a thread each) and then the frame's start, "particle lights write" writes the kept as point lights into the
     * lights the GPU writes, which the light binning reads after (it builds this first: Lights.writtenBy).
     */
    @AllowAllocation("building the graph, when it changes")
    private void lights(GraphBuilder g, Particles.Buffers b) {
        Particles p = particles;
        Buffer written = g.importBuffer(p.lights().gpuLightsBuffer());
        Buffer state = g.importBuffer(p.lightStateImport());
        Pipeline count = g.computeProgram(SHADER, "lightsCountMain", ParticlePush.BYTES);
        Pipeline threshold = g.computeProgram(SHADER, "lightsThresholdMain", ParticlePush.BYTES);
        Pipeline write = g.computeProgram(SHADER, "lightsWriteMain", ParticlePush.BYTES);
        ParticlePush countPush = new ParticlePush();
        q(g.pass("particle lights count")).read(b.particles(), BufferAccess.STORAGE_READ)
            .read(b.state(), BufferAccess.STORAGE_READ).write(state, BufferAccess.TRANSFER_DST)
            .write(state, BufferAccess.STORAGE_WRITE).write(written, BufferAccess.TRANSFER_DST)
            .record(ctx -> {
                if (p.simulating()) { // else the lights of the frame before stay, as the particles do
                    p.clearLights(ctx.unsafe().commands(), ctx.unsafe().buffer(state), ctx.unsafe().buffer(written));
                    if (p.lightEmitterCount() > 0) {
                        ctx.dispatch(count, p.push(countPush), (p.largestLightEmitter() + 63) / 64,
                            p.lightEmitterCount(), 1);
                    }
                }
            });
        ParticlePush thresholdPush = new ParticlePush();
        q(g.pass("particle lights threshold")).write(state, BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.lightEmitterCount() > 0) {
                    ctx.dispatch(threshold, p.push(thresholdPush), 1);
                }
            });
        ParticlePush writePush = new ParticlePush();
        q(g.pass("particle lights write")).read(b.particles(), BufferAccess.STORAGE_READ)
            .read(b.state(), BufferAccess.STORAGE_READ).write(state, BufferAccess.STORAGE_WRITE)
            .write(written, BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.lightEmitterCount() > 0) {
                    ctx.dispatch(write, p.push(writePush), (p.largestLightEmitter() + 63) / 64,
                        p.lightEmitterCount(), 1);
                }
            });
    }

    /**
     * Ribbons per parent (particles as emitters, step 19): "particle ribbon keys" keys each particle of those emitters
     * by its emitter's place and its ribbon (its parent's spawn index), the view sort's radix passes sort them stably,
     * and "particle ribbon order" writes each emitter's range of the ribbon order in that order: each ribbon's particles
     * together, in spawn order, which the ribbon draws follow.
     */
    @AllowAllocation("building the graph, when it changes")
    private void parentRibbons(GraphBuilder g, Particles.Buffers b) {
        Particles p = particles;
        int slots = p.pool().slots();
        long groups = (slots + ParticleConstants.SORT_GROUP - 1) / ParticleConstants.SORT_GROUP;
        Buffer keys = g.createBuffer("particle ribbon keys", 8L * slots);
        Buffer other = g.createBuffer("particle ribbon keys, other", 8L * slots);
        Buffer args = g.createBuffer("particle ribbon args", 4L * (4 + 64));
        Buffer histogram = g.createBuffer("particle ribbon counts", 4L * 16 * groups);
        Pipeline argsProgram = g.computeProgram(SORT_SHADER, "sortArgsMain", ParticleSortPush.BYTES);
        Pipeline keysProgram = g.computeProgram(SORT_SHADER, "ribbonKeysMain", ParticleSortPush.BYTES);
        Pipeline count = g.computeProgram(SORT_SHADER, "sortCountMain", ParticleSortPush.BYTES);
        Pipeline scan = g.computeProgram(SORT_SHADER, "sortScanMain", ParticleSortPush.BYTES);
        Pipeline scatter = g.computeProgram(SORT_SHADER, "sortScatterMain", ParticleSortPush.BYTES);
        Pipeline orderProgram = g.computeProgram(SORT_SHADER, "ribbonOrderMain", ParticleSortPush.BYTES);
        ParticleSortPush argsPush = new ParticleSortPush();
        q(g.pass("particle ribbon args")).read(b.state(), BufferAccess.STORAGE_READ)
            .write(args, BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.parentRibbonCount() > 0) {
                    ctx.dispatch(argsProgram, ribbonPush(argsPush, ctx.address(b.state()), slots)
                        .args(ctx.address(args)), 1);
                }
            });
        ParticleSortPush keysPush = new ParticleSortPush();
        q(g.pass("particle ribbon keys")).read(b.particles(), BufferAccess.STORAGE_READ)
            .read(b.state(), BufferAccess.STORAGE_READ).read(args, BufferAccess.STORAGE_READ)
            .write(keys, BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.parentRibbonCount() > 0) {
                    dispatchChunks(ctx, keysProgram, ribbonPush(keysPush, ctx.address(b.state()), slots)
                        .args(ctx.address(args)).target(ctx.address(keys)), p.parentRibbonChunks());
                }
            });
        for (int pass = 0; pass < 8; pass++) {
            Buffer source = pass % 2 == 0 ? keys : other;
            Buffer target = pass % 2 == 0 ? other : keys;
            int index = pass;
            ParticleSortPush countPush = new ParticleSortPush().shift(4 * pass);
            q(g.pass("particle ribbon sort, count " + pass)).read(source, BufferAccess.STORAGE_READ)
                .read(args, BufferAccess.INDIRECT).read(args, BufferAccess.STORAGE_READ)
                .write(histogram, BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (p.simulating() && index < ParticleSort.passes(p.parentRibbonCount())) {
                        ctx.dispatchIndirect(count, ribbonPush(countPush, ctx.address(b.state()), slots)
                            .args(ctx.address(args)).histogram(ctx.address(histogram)).source(ctx.address(source)),
                            args, 0);
                    }
                });
            ParticleSortPush scanPush = new ParticleSortPush();
            q(g.pass("particle ribbon sort, scan " + pass)).read(args, BufferAccess.STORAGE_READ)
                .write(histogram, BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (p.simulating() && index < ParticleSort.passes(p.parentRibbonCount())) {
                        ctx.dispatch(scan, ribbonPush(scanPush, ctx.address(b.state()), slots)
                            .args(ctx.address(args)).histogram(ctx.address(histogram)), 1);
                    }
                });
            ParticleSortPush scatterPush = new ParticleSortPush().shift(4 * pass);
            q(g.pass("particle ribbon sort, scatter " + pass)).read(source, BufferAccess.STORAGE_READ)
                .read(histogram, BufferAccess.STORAGE_READ).read(args, BufferAccess.INDIRECT)
                .read(args, BufferAccess.STORAGE_READ).write(target, BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    if (p.simulating() && index < ParticleSort.passes(p.parentRibbonCount())) {
                        ctx.dispatchIndirect(scatter, ribbonPush(scatterPush, ctx.address(b.state()), slots)
                            .args(ctx.address(args)).histogram(ctx.address(histogram)).source(ctx.address(source))
                            .target(ctx.address(target)), args, 0);
                    }
                });
        }
        ParticleSortPush orderPush = new ParticleSortPush();
        q(g.pass("particle ribbon order")).read(keys, BufferAccess.STORAGE_READ).read(args, BufferAccess.INDIRECT)
            .read(args, BufferAccess.STORAGE_READ).write(b.order(), BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (p.simulating() && p.parentRibbonCount() > 0) {
                    ctx.dispatchIndirect(orderProgram, ribbonPush(orderPush, ctx.address(b.state()), slots)
                        .args(ctx.address(args)).source(ctx.address(keys)).target(ctx.address(b.order())), args, 0);
                }
            });
    }

    /** What every pass of the ribbons' sort shares. */
    private ParticleSortPush ribbonPush(ParticleSortPush push, long state, int capacity) {
        Particles p = particles;
        return push.particles(p.particlesAddress()).emitters(p.emitterTableAddress()).state(state)
            .list(p.parentRibbonListAddress()).chunks(p.parentRibbonChunksAddress()).sortedCount(p.parentRibbonCount())
            .chunkCount(p.parentRibbonChunks()).capacity(capacity);
    }

    /** {@code pass}, on the async compute queue if the build's passes are. */
    private GraphBuilder.PassBuilder q(GraphBuilder.PassBuilder pass) {
        return queue ? pass.async() : pass;
    }

    /** Dispatches a group per chunk (or emitter), in rows of up to 65,535 groups. */
    private static void dispatchChunks(PassContext ctx, Pipeline pipeline, com.truesim.engine.vulkan.PushConstants push,
        int groups) {
        int x = Math.min(groups, PassContext.MAX_GROUPS);
        int y = (groups + PassContext.MAX_GROUPS - 1) / PassContext.MAX_GROUPS;
        ctx.dispatch(pipeline, push, x, y, 1);
    }
}
