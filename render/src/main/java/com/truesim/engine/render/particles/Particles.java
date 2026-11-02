package com.truesim.engine.render.particles;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.ExternalBuffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Readback;
import com.truesim.engine.render.layout.ParticleConstants;
import com.truesim.engine.render.layout.ParticleEmitterLayout;
import com.truesim.engine.render.layout.ParticleLayout;
import com.truesim.engine.render.layout.ParticlePush;
import com.truesim.engine.render.layout.ParticleSpawnLayout;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.render.layout.ParticleDrawPush;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.scene.assets.AssetRef;
import java.util.HashMap;
import java.util.Map;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.effects.CpuSimulation;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectRuntime;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.EmitterData;
import com.truesim.scene.effects.Space;
import com.truesim.scene.effects.SpawnRecords;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The GPU side of the effects playing in a scene (effects.md §2.2 to §2.4): the pool, an emitter instance per GPU
 * emitter of each playing effect with a range of the pool, the module programs and curve keys, and each frame's emitter
 * table, chunk list, spawn ring and user parameters, which {@link ParticleSimulation} simulates. Each frame, after
 * {@link EffectRuntime#update}, {@link #update} follows the effects (instances of new ones take ranges, those of ended
 * ones give them back), moves each instance on by its effect's time (at most {@link CpuSimulation#MAX_STEP} s) and
 * hands the runtime's spawn records to the GPU:
 *
 * <pre>{@code
 * Particles particles = new Particles(device, graph.frameSlots(), 64 << 20);   // a budget in bytes
 * graph.add(new ParticleSimulation(particles));
 * // each frame, while it records (after beginFrame):
 * runtime.update(seconds);
 * particles.update(effects, runtime.spawns());
 * }</pre>
 *
 * <p>World-space emitters' particles are float offsets from their effect's origin (its position when it started, two
 * floats per axis on the GPU), local-space ones live in the emitter's frame. Statistics (particles alive, spawned and
 * dropped for want of room, per instance and in all) come back a few frames late, for budgets and debugging only.
 * Allocates nothing per frame once its tables have grown to the effects playing; used from the render thread.
 */
public final class Particles implements AutoCloseable {

    /** Emitter instances at most, unless given. */
    public static final int DEFAULT_EMITTERS = 4096;

    private static final int STORAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    private static final int GPU_USAGE = STORAGE | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    private final VulkanDevice device;
    private final FrameSlots slots;
    private final ParticlePool pool;
    private final ModulePrograms programs;
    private final Retiring retired;
    private final int maxEmitters;
    private final GpuBuffer state;
    private final GpuBuffer chunkCounts;
    private final GpuBuffer statistics;
    private final GpuBuffer dispatch;
    private final ExternalBuffer stateImport;
    private final ExternalBuffer chunkCountsImport;
    private final ExternalBuffer statisticsImport;
    private final ExternalBuffer dispatchImport;
    private final FrameBuffer emitterTable;
    private final FrameBuffer chunkTable;
    private FrameBuffer spawnRing;
    private FrameBuffer parameterBlocks;

    // Per effect slot: the effect followed, its programs, its emitters' instances (-1: none) and places in the frame's
    // list, its origin, where it was and its time at the last update.
    private long[] ids = new long[16];
    private ModulePrograms.Block[] blocks = new ModulePrograms.Block[16];
    private int[][] instances = new int[16][];
    private int[][] places = new int[16][];
    private double[] origins = new double[3 * 16];
    private double[] previous = new double[3 * 16];
    private long[] times = new long[16];
    private long[] seen = new long[16];
    private int highest; // one past the highest slot followed
    private Effects following;
    private long stamp;
    private final Effects.EffectVisitor follow = this::follow;

    // Per emitter instance.
    private final int[] firsts;
    private final int[] capacities;
    private final int[] sideFirsts;
    private final int[] sideUnits;
    private final boolean[] fresh;
    private final boolean[] local;
    private final int[] freeInstances;
    private int freeCount;
    private int unplaced;

    // The frame's list.
    private final int[] listInstance;
    private final int[] listSlot;
    private final int[] listEmitter;
    private final double[] listPosition;
    private final int[] demand;
    private final int[] recordStart;
    private final int[] recordFill;
    private int emitterCount;
    private int chunkCount;
    private int recordCount;
    private float[] parameterScratch = new float[16];
    private long updatedAt = -1;
    private int written;
    private int source;
    private int target = 1;

    // Statistics, as last read back.
    private final int[] aliveOf;
    private final int[] overflowOf;
    private long aliveTotal;
    private long spawnedTotal;
    private long overflowTotal;
    private long statisticsFrame = -1;
    private final Readback receiver = this::receive;
    private final Map<AssetRef<?>, Integer> textureSlots = new HashMap<>();
    private final Map<Integer, Pipeline> billboards = new HashMap<>();

    /** Particles in a pool of {@code budget} bytes, with up to {@value #DEFAULT_EMITTERS} emitter instances. */
    public Particles(VulkanDevice device, FrameSlots slots, long budget) {
        this(device, slots, budget, DEFAULT_EMITTERS);
    }

    /** Particles in a pool of {@code budget} bytes, with up to {@code maxEmitters} emitter instances at once. */
    @AllowAllocation("made once")
    public Particles(VulkanDevice device, FrameSlots slots, long budget, int maxEmitters) {
        if (maxEmitters < 1) {
            throw new IllegalArgumentException("Not an emitter count: " + maxEmitters);
        }
        this.device = Objects.requireNonNull(device, "device");
        this.slots = Objects.requireNonNull(slots, "slots");
        this.maxEmitters = maxEmitters;
        this.retired = new Retiring(slots);
        firsts = new int[maxEmitters];
        capacities = new int[maxEmitters];
        sideFirsts = new int[maxEmitters];
        sideUnits = new int[maxEmitters];
        fresh = new boolean[maxEmitters];
        local = new boolean[maxEmitters];
        freeInstances = new int[maxEmitters];
        for (int i = 0; i < maxEmitters; i++) {
            freeInstances[i] = maxEmitters - 1 - i; // the lowest first
        }
        freeCount = maxEmitters;
        listInstance = new int[maxEmitters];
        listSlot = new int[maxEmitters];
        listEmitter = new int[maxEmitters];
        listPosition = new double[3 * maxEmitters];
        demand = new int[maxEmitters];
        recordStart = new int[maxEmitters];
        recordFill = new int[maxEmitters];
        aliveOf = new int[maxEmitters];
        overflowOf = new int[maxEmitters];
        ParticlePool p = null;
        ModulePrograms m = null;
        GpuBuffer[] made = new GpuBuffer[4];
        FrameBuffer[] tables = new FrameBuffer[4];
        try {
            p = new ParticlePool(device, budget);
            m = new ModulePrograms(device, slots);
            made[0] = GpuBuffer.deviceLocal(device, 4L * ParticleConstants.STATE_WORDS * maxEmitters, GPU_USAGE);
            made[1] = GpuBuffer.deviceLocal(device, 8L * p.slots() / ParticleConstants.CHUNK, GPU_USAGE);
            made[2] = GpuBuffer.deviceLocal(device, statisticsBytes(maxEmitters), GPU_USAGE);
            made[3] = GpuBuffer.deviceLocal(device, 16, GPU_USAGE | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
            tables[0] = FrameBuffer.create(device, (long) ParticleEmitterLayout.BYTES * maxEmitters, STORAGE, slots);
            tables[1] = FrameBuffer.create(device, 4L * p.slots() / ParticleConstants.CHUNK, STORAGE, slots);
            tables[2] = FrameBuffer.create(device, 256L * ParticleSpawnLayout.BYTES, STORAGE, slots);
            tables[3] = FrameBuffer.create(device, 4L * 1024, STORAGE, slots);
        } catch (RuntimeException e) {
            for (FrameBuffer table : tables) {
                if (table != null) {
                    table.close();
                }
            }
            for (GpuBuffer buffer : made) {
                if (buffer != null) {
                    buffer.close();
                }
            }
            if (m != null) {
                m.close();
            }
            if (p != null) {
                p.close();
            }
            throw e;
        }
        pool = p;
        programs = m;
        state = made[0];
        chunkCounts = made[1];
        statistics = made[2];
        dispatch = made[3];
        emitterTable = tables[0];
        chunkTable = tables[1];
        spawnRing = tables[2];
        parameterBlocks = tables[3];
        stateImport = new ExternalBuffer("particle state", state);
        chunkCountsImport = new ExternalBuffer("particle chunk counts", chunkCounts);
        statisticsImport = new ExternalBuffer("particle statistics", statistics);
        dispatchImport = new ExternalBuffer("particle spawn dispatch", dispatch);
    }

    private static long statisticsBytes(int maxEmitters) {
        return 4L * (ParticleConstants.STATISTICS_HEADER + 2L * maxEmitters);
    }

    VulkanDevice device() {
        return device;
    }

    FrameSlots slots() {
        return slots;
    }

    /** Whether there is anything to draw: an update listed emitter instances. */
    boolean drawable() {
        return updatedAt >= 0 && emitterCount > 0;
    }

    /** The pool. */
    public ParticlePool pool() {
        return pool;
    }

    /** The most emitter instances at once. */
    public int maxEmitters() {
        return maxEmitters;
    }

    /** Emitter instances in the last update's list. */
    public int emitterCount() {
        return emitterCount;
    }

    /** Emitter instances that found no room (in the pool or the instance table) since this was made: not simulated. */
    public int unplaced() {
        return unplaced;
    }

    /** Effects encoded for the GPU. */
    public int programCount() {
        return programs.size();
    }

    // ---------------------------------------------------------------------------------------------- following effects

    /**
     * Follows {@code effects} as of their last advance and takes the frame's {@code spawns} (the runtime's): instances
     * of new effects take ranges, those of ended ones give them back, every instance moves on by its effect's time, and
     * the spawns go to the GPU. Once per frame, while it records (after {@code beginFrame}); a frame without an update
     * simulates nothing and draws what the frame before left. Allocates nothing once its tables have grown.
     */
    public void update(Effects effects, SpawnRecords spawns) {
        Objects.requireNonNull(effects, "effects");
        long frames = slots.frames();
        if (updatedAt != frames || slots.isSingle()) {
            source = written;
            target = 1 - written;
            written = target;
            updatedAt = frames;
        }
        retired.release();
        programs.collect();
        following = effects;
        stamp++;
        effects.forEach(follow);
        for (int slot = 0; slot < highest; slot++) {
            if (ids[slot] != 0 && seen[slot] != stamp) {
                end(slot);
            }
        }
        list(effects);
        spawn(effects, spawns);
        for (int i = 0; i < emitterCount; i++) {
            int at = ParticleEmitterLayout.BYTES * i;
            emitterTable.putInt(at + ParticleEmitterLayout.RECORDS, recordStart[i]);
            emitterTable.putInt(at + ParticleEmitterLayout.RECORD_COUNT, recordFill[i] - recordStart[i]);
            emitterTable.putInt(at + ParticleEmitterLayout.DEMAND, demand[i]);
        }
        following = null;
    }

    private void follow(long effect) {
        int slot = (int) effect;
        if (slot >= ids.length) {
            grow(slot + 1);
        }
        highest = Math.max(highest, slot + 1);
        if (ids[slot] != effect) {
            if (ids[slot] != 0) {
                end(slot);
            }
            start(slot, effect);
        }
        seen[slot] = stamp;
    }

    @AllowAllocation("growing with the effects playing")
    private void grow(int n) {
        int length = Math.max(n, 2 * ids.length);
        ids = Arrays.copyOf(ids, length);
        blocks = Arrays.copyOf(blocks, length);
        instances = Arrays.copyOf(instances, length);
        places = Arrays.copyOf(places, length);
        origins = Arrays.copyOf(origins, 3 * length);
        previous = Arrays.copyOf(previous, 3 * length);
        times = Arrays.copyOf(times, length);
        seen = Arrays.copyOf(seen, length);
    }

    /** Starts following {@code effect} in {@code slot}: its GPU emitters take instances and ranges. */
    @AllowAllocation("an effect starting: its emitters' lists grow with the most emitters an effect had")
    private void start(int slot, long effect) {
        EffectData data = following.data(effect);
        ModulePrograms.Block block = programs.acquire(data);
        List<EmitterData> emitters = data.emitters();
        if (instances[slot] == null || instances[slot].length < emitters.size()) {
            instances[slot] = new int[emitters.size()];
            places[slot] = new int[emitters.size()];
        }
        Arrays.fill(instances[slot], -1);
        for (int e = 0; e < emitters.size(); e++) {
            if (block.programs[e] >= 0) {
                instances[slot][e] = place(emitters.get(e), block.over[e]);
            }
        }
        ids[slot] = effect;
        blocks[slot] = block;
        times[slot] = 0;
        for (int axis = 0; axis < 3; axis++) {
            origins[3 * slot + axis] = following.position(effect, axis);
            previous[3 * slot + axis] = origins[3 * slot + axis];
        }
    }

    /** An emitter instance with a range for {@code emitter}, or -1 where there is no room. */
    private int place(EmitterData emitter, boolean over) {
        int capacity = emitter.capacity();
        int first = freeCount == 0 ? -1 : pool.allocate(capacity);
        int units = over ? 2 * capacity : 0;
        int side = ParticleConstants.NO_SIDE;
        if (first >= 0 && over) {
            side = pool.allocateSide(units);
            if (side < 0) {
                pool.release(first, capacity);
                first = -1;
            }
        }
        if (first < 0) {
            unplaced++;
            return -1;
        }
        int instance = freeInstances[--freeCount];
        firsts[instance] = first;
        capacities[instance] = capacity;
        sideFirsts[instance] = side;
        sideUnits[instance] = units;
        fresh[instance] = true;
        local[instance] = emitter.space() == Space.LOCAL;
        return instance;
    }

    /** Stops following the effect in {@code slot}: its instances give their ranges back. */
    private void end(int slot) {
        int[] own = instances[slot];
        for (int e = 0; e < blocks[slot].programs.length; e++) {
            int instance = own[e];
            if (instance < 0) {
                continue;
            }
            // Frames run in order: what frames in flight do with the range comes before any later frame's use of it
            pool.release(firsts[instance], capacities[instance]);
            if (sideFirsts[instance] != ParticleConstants.NO_SIDE) {
                pool.releaseSide(sideFirsts[instance], sideUnits[instance]);
            }
            freeInstances[freeCount++] = instance;
            own[e] = -1;
        }
        programs.release(blocks[slot]);
        blocks[slot] = null;
        ids[slot] = 0;
    }

    /** Writes the frame's emitter list, chunk list and parameter blocks. */
    private void list(Effects effects) {
        emitterCount = 0;
        chunkCount = 0;
        int parameterFloats = 0;
        for (int slot = 0; slot < highest; slot++) {
            long effect = ids[slot];
            if (effect == 0) {
                continue;
            }
            ModulePrograms.Block block = blocks[slot];
            EffectData data = block.data;
            int floats = data.parameterFloats();
            int parameterOffset = parameterFloats;
            if (floats > 0) {
                parameterFloats += floats;
                putParameters(effects, effect, parameterOffset, floats);
            }
            long time = effects.time(effect);
            double frame = (time - times[slot]) * 1e-6;
            float dt = (float) Math.min(CpuSimulation.MAX_STEP, frame);
            double x = effects.position(effect, 0);
            double y = effects.position(effect, 1);
            double z = effects.position(effect, 2);
            float qx = (float) effects.rotation(effect, 0);
            float qy = (float) effects.rotation(effect, 1);
            float qz = (float) effects.rotation(effect, 2);
            float qw = (float) effects.rotation(effect, 3);
            int seed = effects.seed(effect);
            int[] own = instances[slot];
            for (int e = 0; e < block.programs.length; e++) {
                int instance = own[e];
                if (instance < 0) {
                    continue;
                }
                int index = emitterCount++;
                listInstance[index] = instance;
                listSlot[index] = slot;
                listEmitter[index] = e;
                listPosition[3 * index] = x;
                listPosition[3 * index + 1] = y;
                listPosition[3 * index + 2] = z;
                places[slot][e] = index;
                demand[index] = 0;
                int at = ParticleEmitterLayout.BYTES * index;
                FrameBuffer t = emitterTable;
                t.putInt(at + ParticleEmitterLayout.FIRST, firsts[instance]);
                t.putInt(at + ParticleEmitterLayout.CAPACITY, capacities[instance]);
                t.putInt(at + ParticleEmitterLayout.PROGRAM, block.programs[e]);
                t.putInt(at + ParticleEmitterLayout.CURVES, block.curves);
                t.putInt(at + ParticleEmitterLayout.CURVE_FLOATS, block.curveLength);
                t.putInt(at + ParticleEmitterLayout.FLAGS, (fresh[instance] ? ParticleConstants.EMITTER_NEW : 0)
                    | (local[instance] ? ParticleConstants.EMITTER_LOCAL : 0));
                t.putInt(at + ParticleEmitterLayout.SIDE, sideFirsts[instance]);
                t.putInt(at + ParticleEmitterLayout.SEED, EffectRuntime.emitterSeed(seed, e));
                t.putFloat(at + ParticleEmitterLayout.DT, dt);
                t.putFloat(at + ParticleEmitterLayout.FRAME, (float) frame);
                putVector(t, at + ParticleEmitterLayout.ROTATION, qx, qy, qz);
                t.putFloat(at + ParticleEmitterLayout.ROTATION + 12, qw);
                t.putInt(at + ParticleEmitterLayout.PARAMETERS, parameterOffset);
                t.putInt(at + ParticleEmitterLayout.CHUNKS, chunkCount);
                t.putInt(at + ParticleEmitterLayout.INSTANCE, instance);
                t.putInt(at + ParticleEmitterLayout.SIDE_UNITS, sideUnits[instance] == 0 ? 0
                    : sideUnits[instance] / capacities[instance]);
                if (local[instance]) {
                    putOrigin(t, at, x, y, z);
                    putVector(t, at + ParticleEmitterLayout.POSITION, 0, 0, 0);
                    putVector(t, at + ParticleEmitterLayout.PREVIOUS, 0, 0, 0);
                    // world down turned into the emitter's frame: by the rotation's inverse
                    putVector(t, at + ParticleEmitterLayout.DOWN, -2 * (qw * qz + qx * qy),
                        -1 + 2 * (qx * qx + qz * qz), 2 * (qw * qx - qy * qz));
                } else {
                    double ox = origins[3 * slot];
                    double oy = origins[3 * slot + 1];
                    double oz = origins[3 * slot + 2];
                    putOrigin(t, at, ox, oy, oz);
                    putVector(t, at + ParticleEmitterLayout.POSITION, (float) (x - ox), (float) (y - oy),
                        (float) (z - oz));
                    putVector(t, at + ParticleEmitterLayout.PREVIOUS, (float) (previous[3 * slot] - ox),
                        (float) (previous[3 * slot + 1] - oy), (float) (previous[3 * slot + 2] - oz));
                    putVector(t, at + ParticleEmitterLayout.DOWN, 0, -1, 0);
                }
                fresh[instance] = false;
                int chunks = ParticlePool.chunksOf(capacities[instance]);
                for (int c = 0; c < chunks; c++) {
                    chunkTable.putInt(4L * chunkCount++, index);
                }
            }
            times[slot] = time;
            previous[3 * slot] = x;
            previous[3 * slot + 1] = y;
            previous[3 * slot + 2] = z;
        }
    }

    private void putParameters(Effects effects, long effect, int offset, int floats) {
        if (parameterScratch.length < floats) {
            parameterScratch = new float[Math.max(floats, 2 * parameterScratch.length)];
        }
        effects.parameters(effect, parameterScratch, 0);
        long bytes = 4L * (offset + floats);
        if (parameterBlocks.size() < bytes) {
            parameterBlocks = grown(parameterBlocks, bytes);
        }
        for (int i = 0; i < floats; i++) {
            parameterBlocks.putFloat(4L * (offset + i), parameterScratch[i]);
        }
    }

    private static void putOrigin(FrameBuffer t, int at, double x, double y, double z) {
        float hx = (float) x;
        float hy = (float) y;
        float hz = (float) z;
        putVector(t, at + ParticleEmitterLayout.ORIGIN_HIGH, hx, hy, hz);
        putVector(t, at + ParticleEmitterLayout.ORIGIN_LOW, (float) (x - hx), (float) (y - hy), (float) (z - hz));
    }

    private static void putVector(FrameBuffer t, long at, float x, float y, float z) {
        t.putFloat(at, x);
        t.putFloat(at + 4, y);
        t.putFloat(at + 8, z);
    }

    /** A frame buffer of at least {@code bytes} for {@code old}, which frames in flight may still read. */
    @AllowAllocation("a table growing")
    private FrameBuffer grown(FrameBuffer old, long bytes) {
        retired.add(old);
        return FrameBuffer.create(device, Math.max(bytes, 2 * old.size()), STORAGE, slots);
    }

    /** Writes the frame's spawn records into the ring, grouped by emitter in the list's order. */
    private void spawn(Effects effects, SpawnRecords spawns) {
        int n = spawns == null ? 0 : spawns.size();
        Arrays.fill(recordFill, 0, emitterCount, 0);
        for (int i = 0; i < n; i++) { // count per emitter
            int index = placeOf(spawns, i);
            if (index >= 0) {
                recordFill[index]++;
            }
        }
        int total = 0;
        for (int i = 0; i < emitterCount; i++) {
            recordStart[i] = total;
            total += recordFill[i];
            recordFill[i] = recordStart[i];
        }
        recordCount = total;
        long bytes = (long) ParticleSpawnLayout.BYTES * Math.max(1, total);
        if (spawnRing.size() < bytes) {
            spawnRing = grown(spawnRing, bytes);
        }
        for (int i = 0; i < n; i++) {
            int index = placeOf(spawns, i);
            if (index < 0) {
                continue;
            }
            int slot = (int) spawns.effect(i);
            int at = ParticleSpawnLayout.BYTES * recordFill[index]++;
            int count = spawns.count(i);
            FrameBuffer r = spawnRing;
            r.putInt(at + ParticleSpawnLayout.EMITTER, index);
            r.putInt(at + ParticleSpawnLayout.COUNT, count);
            r.putInt(at + ParticleSpawnLayout.BEFORE, demand[index]);
            r.putInt(at + ParticleSpawnLayout.FIRST_INDEX, (int) spawns.firstIndex(i));
            r.putFloat(at + ParticleSpawnLayout.AGE, spawns.age(i));
            r.putFloat(at + ParticleSpawnLayout.INTERVAL, spawns.interval(i));
            int call = spawns.burst(i);
            int flags = (spawns.flags(i) & SpawnRecords.PREWARM) != 0 ? ParticleConstants.SPAWN_PREWARM : 0;
            if (call >= 0) {
                flags |= ParticleConstants.SPAWN_BURST;
                putVector(r, at + ParticleSpawnLayout.BURST_POSITION,
                    (float) (effects.burstPosition(call, 0) - origins[3 * slot]),
                    (float) (effects.burstPosition(call, 1) - origins[3 * slot + 1]),
                    (float) (effects.burstPosition(call, 2) - origins[3 * slot + 2]));
                putVector(r, at + ParticleSpawnLayout.BURST_NORMAL, effects.burstNormal(call, 0),
                    effects.burstNormal(call, 1), effects.burstNormal(call, 2));
            }
            r.putInt(at + ParticleSpawnLayout.FLAGS, flags);
            demand[index] = (int) Math.min(Integer.MAX_VALUE, (long) demand[index] + count);
        }
    }

    /** The place in the frame's list of the emitter spawn record {@code i} spawns, or -1 for none on the GPU. */
    private int placeOf(SpawnRecords spawns, int i) {
        long effect = spawns.effect(i);
        int slot = (int) effect;
        if (slot < 0 || slot >= highest || ids[slot] != effect) {
            return -1;
        }
        int instance = instances[slot][spawns.emitter(i)];
        return instance < 0 ? -1 : places[slot][spawns.emitter(i)];
    }

    // ---------------------------------------------------------------------------------------------- the GPU side

    /** The simulation's buffers as buffers of a build. */
    record Buffers(Buffer particles, Buffer side, Buffer state, Buffer chunkCounts, Buffer statistics,
        Buffer dispatch) {
    }

    @AllowAllocation("building the graph, when it changes")
    Buffers importInto(GraphBuilder g) {
        return new Buffers(g.importBuffer(pool.graphBuffer()), g.importBuffer(pool.sideGraphBuffer()),
            g.importBuffer(stateImport), g.importBuffer(chunkCountsImport), g.importBuffer(statisticsImport),
            g.importBuffer(dispatchImport));
    }

    /** Whether this frame simulates: it was updated while it records, with emitters to move. */
    boolean simulating() {
        return (updatedAt == slots.frames() || slots.isSingle()) && emitterCount > 0;
    }

    int chunkCount() {
        return chunkCount;
    }

    int recordCount() {
        return recordCount;
    }

    /**
     * Sets every pointer and count of {@code push} for the frame recording. The buffers are this object's own, by
     * their addresses: each pass declares those its shader reads and writes.
     */
    ParticlePush push(ParticlePush push) {
        long half = (long) pool.slots() * ParticleLayout.BYTES;
        long sideHalf = 16L * pool.sideUnits();
        long particles = pool.buffer().deviceAddress();
        long side = pool.sideBuffer().deviceAddress();
        return push.source(particles + source * half).target(particles + target * half)
            .sideSource(side + source * sideHalf).sideTarget(side + target * sideHalf)
            .emitters(emitterTable.deviceAddress()).chunks(chunkTable.deviceAddress())
            .chunkCounts(chunkCounts.deviceAddress()).state(state.deviceAddress())
            .spawns(spawnRing.deviceAddress()).programs(programs.wordsAddress()).curves(programs.curvesAddress())
            .parameters(parameterBlocks.deviceAddress()).statistics(statistics.deviceAddress())
            .dispatch(dispatch.deviceAddress()).emitterCount(emitterCount).chunkCount(chunkCount);
    }

    /** The half of the pool the frame's simulation writes and every draw after reads. */
    public int currentHalf() {
        return written;
    }

    /** The emitter table's address for the frame recording: its list as the simulation saw it. */
    public long emitterTableAddress() {
        return emitterTable.deviceAddress();
    }

    /** The receiver of the statistics' readback. */
    Readback receiver() {
        return receiver;
    }

    long statisticsBytes() {
        return statisticsBytes(maxEmitters);
    }

    private void receive(MemorySegment data, long frame) {
        aliveTotal = Integer.toUnsignedLong(data.getAtIndex(ValueLayout.JAVA_INT, ParticleConstants.STATISTICS_ALIVE));
        spawnedTotal = Integer.toUnsignedLong(data.getAtIndex(ValueLayout.JAVA_INT,
            ParticleConstants.STATISTICS_SPAWNED));
        overflowTotal = Integer.toUnsignedLong(data.getAtIndex(ValueLayout.JAVA_INT,
            ParticleConstants.STATISTICS_OVERFLOW));
        for (int i = 0; i < maxEmitters; i++) {
            aliveOf[i] = data.getAtIndex(ValueLayout.JAVA_INT, ParticleConstants.STATISTICS_HEADER + 2L * i);
            overflowOf[i] = data.getAtIndex(ValueLayout.JAVA_INT, ParticleConstants.STATISTICS_HEADER + 2L * i + 1);
        }
        statisticsFrame = frame;
    }

    // ---------------------------------------------------------------------------------------------- drawing

    /**
     * Draws {@code texture} (a texture asset renderers name in their {@code texture} setting) from bindless sampled
     * slot {@code slot}: the scene's resources say where they put it. A renderer whose texture has no slot draws a
     * round sprite.
     */
    @AllowAllocation("a texture registered")
    public void texture(AssetRef<?> texture, int slot) {
        textureSlots.put(Objects.requireNonNull(texture, "texture"), slot);
    }

    /** Forgets {@code texture}'s slot. */
    public void forgetTexture(AssetRef<?> texture) {
        textureSlots.remove(texture);
    }

    /** {@code texture}'s sampled slot, or {@code NO_TEXTURE}. */
    int textureSlot(AssetRef<?> texture) {
        if (texture == null) {
            return ParticleConstants.NO_TEXTURE;
        }
        Integer slot = textureSlots.get(texture);
        return slot == null ? ParticleConstants.NO_TEXTURE : slot;
    }

    /** How many renderers the emitter at {@code index} of the frame's list has. */
    int renderers(int index) {
        return blocks[listSlot[index]].renderers[listEmitter[index]].length;
    }

    /** Its renderer {@code r}'s record, as a word of the program buffer. */
    int rendererWord(int index, int r) {
        return blocks[listSlot[index]].renderers[listEmitter[index]][r];
    }

    /** Its renderer {@code r}'s kind ({@code RENDER_*}). */
    int rendererKind(int index, int r) {
        return blocks[listSlot[index]].data.emitters().get(listEmitter[index]).renderers().get(r).kind().ordinal();
    }

    /** Its renderer {@code r}'s texture slot. */
    int rendererTexture(int index, int r) {
        return textureSlot(blocks[listSlot[index]].textures[listEmitter[index]][r]);
    }

    /** Where the emitter at {@code index} of the frame's list is, along {@code axis}: its effect's position. */
    double listPosition(int index, int axis) {
        return listPosition[3 * index + axis];
    }

    /** The program buffer's address, for renderers' records. */
    long wordsAddress() {
        return programs.wordsAddress();
    }

    /** The pool's half the frame wrote, by address. */
    long particlesAddress() {
        return pool.buffer().deviceAddress() + (long) written * pool.slots() * ParticleLayout.BYTES;
    }

    long stateAddress() {
        return state.deviceAddress();
    }

    /** The billboard pipeline drawing into color attachments of {@code format} (a VkFormat), made once. */
    @AllowAllocation("a pipeline made once per color format")
    Pipeline billboards(int format) {
        Pipeline pipeline = billboards.get(format);
        if (pipeline == null) {
            try (ShaderModule shaders = ShaderModule.load(device, "shaders/particles/draw.spv")) {
                pipeline = Pipeline.graphics(device).stage(VK_SHADER_STAGE_VERTEX_BIT, shaders, "vertexMain")
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shaders, "fragmentMain").color(format)
                    .blend(0, Pipeline.Blend.PREMULTIPLIED).pushConstants(ParticleDrawPush.BYTES).doubleSided()
                    .build();
            }
            billboards.put(format, pipeline);
        }
        return pipeline;
    }

    // ---------------------------------------------------------------------------------------------- statistics

    /** The graph frame the statistics are of, -1 before any came back. */
    public long statisticsFrame() {
        return statisticsFrame;
    }

    /** Particles alive after the frame of the statistics. */
    public long alive() {
        return aliveTotal;
    }

    /** Particles born in that frame. */
    public long spawned() {
        return spawnedTotal;
    }

    /** Particles dropped in that frame for want of room in their ranges. */
    public long overflow() {
        return overflowTotal;
    }

    /** The instance of GPU emitter {@code emitter} of {@code effect}, or -1 (not followed, not on the GPU, no room). */
    public int instance(long effect, int emitter) {
        int slot = (int) effect;
        if (slot < 0 || slot >= highest || ids[slot] != effect) {
            return -1;
        }
        int[] own = instances[slot];
        return emitter >= 0 && emitter < blocks[slot].programs.length ? own[emitter] : -1;
    }

    /** Particles of GPU emitter {@code emitter} of {@code effect} as of the statistics, 0 without an instance. */
    public int alive(long effect, int emitter) {
        int instance = instance(effect, emitter);
        return instance < 0 ? 0 : aliveOf[instance];
    }

    /** Particles it dropped for want of room since it started, as of the statistics. */
    public int overflow(long effect, int emitter) {
        int instance = instance(effect, emitter);
        return instance < 0 ? 0 : overflowOf[instance];
    }

    /** The first slot of its range in each half of the pool, or -1 without an instance. */
    public int first(long effect, int emitter) {
        int instance = instance(effect, emitter);
        return instance < 0 ? -1 : firsts[instance];
    }

    @Override
    public void close() {
        billboards.values().forEach(Pipeline::close);
        emitterTable.close();
        chunkTable.close();
        spawnRing.close();
        parameterBlocks.close();
        state.close();
        chunkCounts.close();
        statistics.close();
        dispatch.close();
        programs.close();
        pool.close();
        retired.close();
    }
}
