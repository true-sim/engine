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
import com.truesim.engine.render.layout.ParticleColliderLayout;
import com.truesim.engine.render.layout.ParticleFrameLayout;
import com.truesim.scene.effects.CpuSimulation;
import com.truesim.scene.effects.EffectColliders;
import com.truesim.scene.effects.ParticleWorld;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectRuntime;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.EmitterData;
import com.truesim.scene.effects.Space;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.SpawnRecords;
import com.truesim.scene.effects.Target;
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

    /**
     * The most particles an emitter with trails holds: each copies its past positions with the survivors every frame
     * (16 bytes a point).
     */
    public static final int MAX_TRAIL_PARTICLES = 65_536;
    /** Emitter instances at most, unless given. */
    public static final int DEFAULT_EMITTERS = 4096;
    /**
     * How far, in metres, an effect moves from the origin of its world-space particles along an axis before the origin
     * moves to it, unless set: {@link CpuSimulation#REBASE_DISTANCE}, where float offsets are still precise to a
     * millimetre.
     */
    public static final double REBASE_DISTANCE = CpuSimulation.REBASE_DISTANCE;
    /**
     * Frames an ended effect's emitter instances stay warm, unless set: their ranges and their effect's programs kept
     * for the next instance of the same effect (a muzzle flash played again), about five seconds at 60 Hz.
     */
    public static final int WARM_FRAMES = 300;
    /** Ended effects kept warm at most, and per effect data. */
    private static final int WARM = 32;
    private static final int WARM_PER_EFFECT = 4;

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
    // The frame's tables as written, each copied to its frame buffer once the update is done
    private final Staging emitterStaged;
    private final Staging chunkStaged;
    private final Staging spawnStaged = new Staging(256L * ParticleSpawnLayout.BYTES);
    private final Staging parameterStaged = new Staging(4L * 1024);
    private final Staging ribbonStaged;
    private int parameterFloats;
    // The frame's sorted emitters (each one's place in the list) and the chunks of their ranges, for the sorts
    private final int[] sortOf;
    private final FrameBuffer sortList;
    private final FrameBuffer sortChunks;
    private final Staging sortStaged;
    private final Staging sortChunkStaged;
    private int sortedCount;
    private int sortChunkCount;
    // What the simulation collides with (effects.md §2.7): the frame record, colliders, the height field, the depth
    private final FrameBuffer frameTable;
    private final Staging frameStaged = new Staging(ParticleFrameLayout.BYTES);
    private final FrameBuffer colliderTable;
    private final Staging colliderStaged = new Staging((long) ParticleColliderLayout.BYTES
        * ParticleConstants.MAX_COLLIDERS);
    private int colliderCount;
    private ParticleWorld heightWorld;
    private FrameBuffer heights;
    private Staging heightStaged;
    private int heightResolution;
    private float heightSize;
    private double heightX = Double.NaN;
    private double heightZ = Double.NaN;
    private double focusX;
    private double focusZ;
    private long heightRefreshes;
    private final GpuBuffer depth;
    private final ExternalBuffer depthImport;
    private boolean depthReady;
    private long frameRecorded = -1;

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
    private int[] restarted = new int[16];
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
    private final boolean[] cpu;
    private final ParticleWriter[] writers;
    private final CpuEmitters cpuEmitters;
    private CpuSimulation simulation;
    private final int[] freeInstances;
    private int freeCount;
    private int unplaced;
    private double rebaseDistance = REBASE_DISTANCE;
    private long rebases;
    private int warmFrames = WARM_FRAMES;

    // Ended effects kept warm: their programs and instances (with their ranges), until a frame.
    private final ModulePrograms.Block[] warmBlocks = new ModulePrograms.Block[WARM];
    private final int[][] warmInstances = new int[WARM][];
    private final long[] warmUntil = new long[WARM];
    private int warmCount;

    // The frame's list.
    private final int[] listInstance;
    private final int[] listSlot;
    private final int[] listEmitter;
    private final double[] listPosition;
    private final double[] listRotation;
    private final float[] listTime;
    private final double[] beamEnds;
    private final boolean[] beamEnded;
    private final GpuBuffer order;
    private final ExternalBuffer orderImport;
    private final FrameBuffer ribbonList;
    private int ribbonCount;
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
    private final Map<Integer, Pipeline> pipelines = new HashMap<>();

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
        cpu = new boolean[maxEmitters];
        writers = new ParticleWriter[maxEmitters];
        freeInstances = new int[maxEmitters];
        for (int i = 0; i < maxEmitters; i++) {
            freeInstances[i] = maxEmitters - 1 - i; // the lowest first
        }
        freeCount = maxEmitters;
        listInstance = new int[maxEmitters];
        listSlot = new int[maxEmitters];
        listEmitter = new int[maxEmitters];
        listPosition = new double[3 * maxEmitters];
        listRotation = new double[4 * maxEmitters];
        listTime = new float[maxEmitters];
        beamEnds = new double[3 * maxEmitters];
        beamEnded = new boolean[maxEmitters];
        demand = new int[maxEmitters];
        recordStart = new int[maxEmitters];
        recordFill = new int[maxEmitters];
        aliveOf = new int[maxEmitters];
        overflowOf = new int[maxEmitters];
        emitterStaged = new Staging((long) ParticleEmitterLayout.BYTES * maxEmitters);
        ribbonStaged = new Staging(8L * maxEmitters);
        sortOf = new int[maxEmitters];
        sortStaged = new Staging(4L * Math.min(maxEmitters, ParticleConstants.SORT_EMITTERS));
        ParticlePool p = null;
        ModulePrograms m = null;
        CpuEmitters c = null;
        GpuBuffer[] made = new GpuBuffer[6];
        FrameBuffer[] tables = new FrameBuffer[9];
        try {
            p = new ParticlePool(device, budget);
            m = new ModulePrograms(device, slots);
            m.assetSlots(this::textureSlot);
            c = new CpuEmitters(device, slots, maxEmitters);
            made[0] = GpuBuffer.deviceLocal(device, 4L * ParticleConstants.STATE_WORDS * maxEmitters, GPU_USAGE);
            made[1] = GpuBuffer.deviceLocal(device, 8L * p.slots() / ParticleConstants.CHUNK, GPU_USAGE);
            made[2] = GpuBuffer.deviceLocal(device, statisticsBytes(maxEmitters), GPU_USAGE);
            made[3] = GpuBuffer.deviceLocal(device, 16, GPU_USAGE | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
            made[4] = GpuBuffer.deviceLocal(device, 4L * p.slots(), GPU_USAGE);
            made[5] = GpuBuffer.deviceLocal(device, 4L * (ParticleConstants.DEPTH_HEADER
                + (long) ParticleConstants.DEPTH_GRID * ParticleConstants.DEPTH_GRID), GPU_USAGE);
            tables[7] = FrameBuffer.create(device, ParticleFrameLayout.BYTES, STORAGE, slots);
            tables[8] = FrameBuffer.create(device, (long) ParticleColliderLayout.BYTES
                * ParticleConstants.MAX_COLLIDERS, STORAGE, slots);
            tables[4] = FrameBuffer.create(device, 8L * maxEmitters, STORAGE, slots);
            tables[0] = FrameBuffer.create(device, (long) ParticleEmitterLayout.BYTES * maxEmitters, STORAGE, slots);
            tables[1] = FrameBuffer.create(device, 4L * p.slots() / ParticleConstants.CHUNK, STORAGE, slots);
            tables[2] = FrameBuffer.create(device, 256L * ParticleSpawnLayout.BYTES, STORAGE, slots);
            tables[3] = FrameBuffer.create(device, 4L * 1024, STORAGE, slots);
            tables[5] = FrameBuffer.create(device, 4L * Math.min(maxEmitters, ParticleConstants.SORT_EMITTERS),
                STORAGE, slots);
            tables[6] = FrameBuffer.create(device, 8L * p.slots() / ParticleConstants.CHUNK, STORAGE, slots);
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
            if (c != null) {
                c.close();
            }
            if (p != null) {
                p.close();
            }
            throw e;
        }
        pool = p;
        programs = m;
        cpuEmitters = c;
        state = made[0];
        chunkCounts = made[1];
        statistics = made[2];
        dispatch = made[3];
        order = made[4];
        depth = made[5];
        frameTable = tables[7];
        colliderTable = tables[8];
        ribbonList = tables[4];
        emitterTable = tables[0];
        chunkTable = tables[1];
        spawnRing = tables[2];
        parameterBlocks = tables[3];
        sortList = tables[5];
        sortChunks = tables[6];
        chunkStaged = new Staging(4L * p.slots() / ParticleConstants.CHUNK);
        sortChunkStaged = new Staging(8L * p.slots() / ParticleConstants.CHUNK);
        stateImport = new ExternalBuffer("particle state", state);
        chunkCountsImport = new ExternalBuffer("particle chunk counts", chunkCounts);
        statisticsImport = new ExternalBuffer("particle statistics", statistics);
        dispatchImport = new ExternalBuffer("particle spawn dispatch", dispatch);
        orderImport = new ExternalBuffer("particle ribbon order", order);
        depthImport = new ExternalBuffer("particle collision depth", depth);
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

    /**
     * Rebases an effect's world-space origin once it is {@code metres} away along an axis (default
     * {@link #REBASE_DISTANCE}): its particles shift by the difference on the GPU, in the update pass, their world
     * positions unchanged.
     */
    public Particles rebaseDistance(double metres) {
        if (!(metres > 0)) {
            throw new IllegalArgumentException("Not a distance: " + metres);
        }
        rebaseDistance = metres;
        return this;
    }

    /** How many times an effect's origin was rebased since this was made. */
    public long rebases() {
        return rebases;
    }

    /**
     * Keeps an ended effect's emitter instances (their ranges) and programs for {@code frames} frames (default
     * {@link #WARM_FRAMES}; 0: given back at once) for the next instance of the same effect; at most a few per effect,
     * and given back early when the pool lacks room.
     */
    public Particles warmFrames(int frames) {
        if (frames < 0) {
            throw new IllegalArgumentException("Not a frame count: " + frames);
        }
        warmFrames = frames;
        return this;
    }

    /** Ended effects kept warm now. */
    public int warmCount() {
        return warmCount;
    }

    // ---------------------------------------------------------------------------------------------- following effects

    /**
     * Follows {@code effects} as of their last advance and takes the frame's {@code spawns} (the runtime's): instances
     * of new effects take ranges, those of ended ones give them back, every instance moves on by its effect's time, and
     * the spawns go to the GPU. Once per frame, before the frame records (between frames, for the next one) or while it
     * records (after {@code beginFrame}); a frame without an update simulates nothing and draws what the frame before
     * left. Allocates nothing once its tables have grown.
     */
    public void update(Effects effects, SpawnRecords spawns) {
        update(effects, spawns, null);
    }

    /**
     * {@link #update(Effects, SpawnRecords)}, uploading CPU emitters' particles: as the game wrote them for emitters it
     * has a {@link #writer} of, else as {@code simulation} (null: none) left them, after its own update.
     */
    public void update(Effects effects, SpawnRecords spawns, CpuSimulation simulation) {
        Objects.requireNonNull(effects, "effects");
        this.simulation = simulation;
        // Between frames, the update is for the frame about to begin
        long frames = !slots.isSingle() && slots.current() < 0 ? slots.frames() + 1 : slots.frames();
        if (updatedAt != frames || slots.isSingle()) {
            source = written;
            target = 1 - written;
            written = target;
            updatedAt = frames;
        }
        retired.release();
        cool(false);
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
        colliders(effects.colliders());
        refreshHeights();
        spawn(effects, spawns);
        upload();
        ribbons();
        for (int i = 0; i < emitterCount; i++) {
            int at = ParticleEmitterLayout.BYTES * i;
            emitterStaged.putInt(at + ParticleEmitterLayout.RECORDS, recordStart[i]);
            emitterStaged.putInt(at + ParticleEmitterLayout.RECORD_COUNT, recordFill[i] - recordStart[i]);
            emitterStaged.putInt(at + ParticleEmitterLayout.DEMAND, demand[i]);
        }
        copyTables();
        following = null;
    }

    /** Hands the frame's tables to the GPU: a copy each, into frame buffers grown where they must. */
    private void copyTables() {
        emitterStaged.copyTo(emitterTable, (long) ParticleEmitterLayout.BYTES * emitterCount);
        chunkStaged.copyTo(chunkTable, 4L * chunkCount);
        long spawnBytes = (long) ParticleSpawnLayout.BYTES * recordCount;
        if (spawnRing.size() < spawnBytes) {
            spawnRing = grown(spawnRing, spawnBytes);
        }
        spawnStaged.copyTo(spawnRing, spawnBytes);
        long parameterBytes = 4L * parameterFloats;
        if (parameterBlocks.size() < parameterBytes) {
            parameterBlocks = grown(parameterBlocks, parameterBytes);
        }
        parameterStaged.copyTo(parameterBlocks, parameterBytes);
        ribbonStaged.copyTo(ribbonList, 8L * ribbonCount);
        sortStaged.copyTo(sortList, 4L * sortedCount);
        sortChunkStaged.copyTo(sortChunks, 8L * sortChunkCount);
        cpuEmitters.finish();
        colliderStaged.copyTo(colliderTable, (long) ParticleColliderLayout.BYTES * colliderCount);
    }

    /**
     * The frame record's address for the frame recording, written the first time a pass asks in the frame: the frame
     * buffers' addresses it holds name the copies of the slot recording (pitfalls: a frame buffer's address names the
     * copy of the slot current when it was taken), and updates may come between frames.
     */
    private synchronized long frameRecord() {
        long frame = slots.frames();
        if (frameRecorded == frame && !slots.isSingle()) {
            return frameTable.deviceAddress();
        }
        frameRecorded = frame;
        Staging r = frameStaged;
        r.putLong(ParticleFrameLayout.UPLOADS, cpuEmitters.recordsAddress());
        r.putLong(ParticleFrameLayout.CPU, cpuEmitters.listAddress());
        r.putLong(ParticleFrameLayout.COLLIDERS, colliderTable.deviceAddress());
        r.putLong(ParticleFrameLayout.HEIGHTS, heights == null ? 0 : heights.deviceAddress());
        r.putLong(ParticleFrameLayout.DEPTH, depth.deviceAddress());
        r.putInt(ParticleFrameLayout.COLLIDER_COUNT, colliderCount);
        r.putInt(ParticleFrameLayout.HEIGHT_RESOLUTION, heights == null ? 0 : heightResolution);
        r.putFloat(ParticleFrameLayout.HEIGHT_SIZE, heightSize);
        r.putInt(ParticleFrameLayout.DEPTH_READY, depthReady ? 1 : 0);
        float hx = (float) heightX;
        float hz = (float) heightZ;
        r.putVector(ParticleFrameLayout.HEIGHT_CENTER_HIGH, hx, 0, hz);
        r.putVector(ParticleFrameLayout.HEIGHT_CENTER_LOW, (float) (heightX - hx), 0, (float) (heightZ - hz));
        r.copyTo(frameTable, ParticleFrameLayout.BYTES);
        return frameTable.deviceAddress();
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
        } else if (following.restarts(effect) != restarted[slot]) {
            restart(slot, effect);
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
        restarted = Arrays.copyOf(restarted, length);
    }

    /** Starts following {@code effect} in {@code slot}: its GPU emitters take instances and ranges. */
    @AllowAllocation("an effect starting: its emitters' lists grow with the most emitters an effect had")
    private void start(int slot, long effect) {
        EffectData data = following.data(effect);
        List<EmitterData> emitters = data.emitters();
        int warm = warmOf(data);
        ModulePrograms.Block block;
        if (warm >= 0) { // a warm one's instances and programs, ranges and all
            block = warmBlocks[warm];
            int[] own = warmInstances[warm];
            warmInstances[warm] = instances[slot];
            instances[slot] = own;
            dropWarm(warm);
        } else {
            block = programs.acquire(data);
            if (instances[slot] == null || instances[slot].length < emitters.size()) {
                instances[slot] = new int[emitters.size()];
            }
            Arrays.fill(instances[slot], -1);
        }
        if (places[slot] == null || places[slot].length < instances[slot].length) {
            places[slot] = new int[instances[slot].length];
        }
        int[] own = instances[slot];
        for (int e = 0; e < emitters.size(); e++) {
            if (own[e] >= 0) {
                renew(own[e]);
            } else {
                own[e] = placeOrEvict(emitters.get(e), block.over[e], block.trails[e]); // CPU emitters too
            }
        }
        ids[slot] = effect;
        blocks[slot] = block;
        begin(slot, effect);
    }

    /** Starts {@code effect} in {@code slot} from its beginning: its time, origin and restarts. */
    private void begin(int slot, long effect) {
        times[slot] = 0;
        restarted[slot] = following.restarts(effect);
        for (int axis = 0; axis < 3; axis++) {
            origins[3 * slot + axis] = following.position(effect, axis);
            previous[3 * slot + axis] = origins[3 * slot + axis];
        }
    }

    /**
     * Restarts the effect in {@code slot} with its new data ({@link Effects#replace}, a hot reload): instances whose
     * emitter keeps its target, capacity and side units keep their ranges, starting empty; the others are given back
     * and placed again.
     */
    @AllowAllocation("a hot reload")
    private void restart(int slot, long effect) {
        EffectData data = following.data(effect);
        ModulePrograms.Block old = blocks[slot];
        ModulePrograms.Block block = programs.acquire(data);
        List<EmitterData> emitters = data.emitters();
        int[] own = instances[slot];
        int before = old.programs.length;
        if (own.length < emitters.size()) {
            own = Arrays.copyOf(own, emitters.size());
            Arrays.fill(own, before, own.length, -1);
            instances[slot] = own;
            places[slot] = new int[own.length];
        }
        for (int e = 0; e < Math.max(before, emitters.size()); e++) {
            int instance = e < before ? own[e] : -1;
            if (instance >= 0 && (e >= emitters.size() || !fits(instance, emitters.get(e), block.over[e],
                block.trails[e]))) {
                give(instance);
                instance = -1;
            }
            if (e < emitters.size()) {
                if (instance >= 0) {
                    renew(instance);
                    local[instance] = !cpu[instance] && emitters.get(e).space() == Space.LOCAL;
                } else {
                    instance = placeOrEvict(emitters.get(e), block.over[e], block.trails[e]);
                }
            }
            own[e] = instance;
        }
        programs.release(old);
        blocks[slot] = block;
        begin(slot, effect);
    }

    /** Whether {@code instance}'s ranges suit {@code emitter} as they are. */
    private boolean fits(int instance, EmitterData emitter, boolean over, int trail) {
        return capacities[instance] == emitter.capacity() && cpu[instance] == (emitter.target() == Target.CPU)
            && sideUnits[instance] == sideUnits(emitter.capacity(), over, trail);
    }

    /** {@code instance} as new: no particles, nothing written to it, no beam end. */
    private void renew(int instance) {
        fresh[instance] = true;
        beamEnded[instance] = false;
        writers[instance] = null;
    }

    private static int sideUnits(int capacity, boolean over, int trail) {
        return ((over ? 2 : 0) + (trail > 0 ? 1 + trail : 0)) * capacity;
    }

    /** {@link #place}, giving the warm instances back first if there is no room; counted when still none. */
    private int placeOrEvict(EmitterData emitter, boolean over, int trail) {
        int instance = place(emitter, over, trail);
        if (instance < 0 && warmCount > 0) {
            cool(true);
            instance = place(emitter, over, trail);
        }
        if (instance < 0) {
            unplaced++;
        }
        return instance;
    }

    /** The warm entry of {@code data}, or -1. */
    private int warmOf(EffectData data) {
        for (int k = 0; k < warmCount; k++) {
            if (warmBlocks[k].data.equals(data)) {
                return k;
            }
        }
        return -1;
    }

    /** Gives back the warm effects whose time is up, or all of them. */
    private void cool(boolean all) {
        for (int k = warmCount - 1; k >= 0; k--) {
            if (all || slots.frames() >= warmUntil[k]) {
                give(warmInstances[k], warmBlocks[k]);
                programs.release(warmBlocks[k]);
                dropWarm(k);
            }
        }
    }

    /** Forgets warm entry {@code k}, the last taking its place. */
    private void dropWarm(int k) {
        int last = --warmCount;
        int[] own = warmInstances[k];
        warmBlocks[k] = warmBlocks[last];
        warmInstances[k] = warmInstances[last];
        warmUntil[k] = warmUntil[last];
        warmInstances[last] = own; // kept for reuse
        warmBlocks[last] = null;
    }

    /** An emitter instance with a range for {@code emitter}, or -1 where there is no room. */
    private int place(EmitterData emitter, boolean over, int trail) {
        int capacity = emitter.capacity();
        boolean room = freeCount > 0 && (trail == 0 || capacity <= MAX_TRAIL_PARTICLES);
        int first = room ? pool.allocate(capacity) : -1;
        int units = sideUnits(capacity, over, trail);
        int side = ParticleConstants.NO_SIDE;
        if (first >= 0 && units > 0) {
            side = pool.allocateSide(units);
            if (side < 0) {
                pool.release(first, capacity);
                first = -1;
            }
        }
        if (first < 0) {
            return -1;
        }
        int instance = freeInstances[--freeCount];
        firsts[instance] = first;
        capacities[instance] = capacity;
        sideFirsts[instance] = side;
        sideUnits[instance] = units;
        cpu[instance] = emitter.target() == Target.CPU;
        local[instance] = !cpu[instance] && emitter.space() == Space.LOCAL; // CPU emitters upload world offsets
        renew(instance);
        return instance;
    }

    /**
     * Stops following the effect in {@code slot}: its instances are kept warm for the next instance of its effect, with
     * its programs, or give their ranges back.
     */
    private void end(int slot) {
        ModulePrograms.Block block = blocks[slot];
        int[] own = instances[slot];
        for (int e = 0; e < block.programs.length; e++) {
            if (own[e] >= 0 && writers[own[e]] != null) {
                writers[own[e]].ended = true;
                writers[own[e]] = null;
            }
        }
        if (warmFrames > 0 && warmCount < WARM && warmOf(block.data, WARM_PER_EFFECT)) {
            int k = warmCount++;
            warmBlocks[k] = block;
            instances[slot] = warmInstances[k];
            warmInstances[k] = own;
            warmUntil[k] = slots.frames() + warmFrames;
        } else {
            give(own, block);
            programs.release(block);
        }
        blocks[slot] = null;
        ids[slot] = 0;
    }

    /** Whether fewer than {@code most} warm entries are of {@code data}. */
    private boolean warmOf(EffectData data, int most) {
        int count = 0;
        for (int k = 0; k < warmCount; k++) {
            if (warmBlocks[k].data.equals(data)) {
                count++;
            }
        }
        return count < most;
    }

    /** Gives the ranges of the instances in {@code own} of {@code block}'s emitters back. */
    private void give(int[] own, ModulePrograms.Block block) {
        for (int e = 0; e < block.programs.length; e++) {
            if (own[e] >= 0) {
                give(own[e]);
                own[e] = -1;
            }
        }
    }

    private void give(int instance) {
        // Frames run in order: what frames in flight do with the range comes before any later frame's use of it
        pool.release(firsts[instance], capacities[instance]);
        if (sideFirsts[instance] != ParticleConstants.NO_SIDE) {
            pool.releaseSide(sideFirsts[instance], sideUnits[instance]);
        }
        freeInstances[freeCount++] = instance;
        if (writers[instance] != null) {
            writers[instance].ended = true;
            writers[instance] = null;
        }
    }

    /** Writes the frame's emitter list, chunk list and parameter blocks. */
    private void list(Effects effects) {
        emitterCount = 0;
        chunkCount = 0;
        parameterFloats = 0;
        sortedCount = 0;
        sortChunkCount = 0;
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
            // Far from its origin, the origin moves to it: the update shifts its world-space particles by the difference
            double ox = origins[3 * slot];
            double oy = origins[3 * slot + 1];
            double oz = origins[3 * slot + 2];
            float sx = 0;
            float sy = 0;
            float sz = 0;
            if (Math.abs(x - ox) > rebaseDistance || Math.abs(y - oy) > rebaseDistance
                || Math.abs(z - oz) > rebaseDistance) {
                sx = (float) (ox - x);
                sy = (float) (oy - y);
                sz = (float) (oz - z);
                ox = x;
                oy = y;
                oz = z;
                origins[3 * slot] = x;
                origins[3 * slot + 1] = y;
                origins[3 * slot + 2] = z;
                rebases++;
            }
            // The share of its velocity over the frame world-space newborn inherit
            double inherit = frame > 0 ? effects.inheritVelocity(effect) / frame : 0;
            float vx = (float) ((x - previous[3 * slot]) * inherit);
            float vy = (float) ((y - previous[3 * slot + 1]) * inherit);
            float vz = (float) ((z - previous[3 * slot + 2]) * inherit);
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
                listRotation[4 * index] = qx;
                listRotation[4 * index + 1] = qy;
                listRotation[4 * index + 2] = qz;
                listRotation[4 * index + 3] = qw;
                listTime[index] = (float) (time * 1e-6);
                places[slot][e] = index;
                demand[index] = 0;
                int at = ParticleEmitterLayout.BYTES * index;
                Staging t = emitterStaged;
                t.putInt(at + ParticleEmitterLayout.FIRST, firsts[instance]);
                t.putInt(at + ParticleEmitterLayout.CAPACITY, capacities[instance]);
                t.putInt(at + ParticleEmitterLayout.PROGRAM, block.programs[e]);
                t.putInt(at + ParticleEmitterLayout.CURVES, block.curves);
                t.putInt(at + ParticleEmitterLayout.CURVE_FLOATS, block.curveLength);
                t.putInt(at + ParticleEmitterLayout.FLAGS, (fresh[instance] ? ParticleConstants.EMITTER_NEW : 0)
                    | (local[instance] ? ParticleConstants.EMITTER_LOCAL : 0)
                    | (cpu[instance] ? ParticleConstants.EMITTER_CPU : 0));
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
                t.putFloat(at + ParticleEmitterLayout.TIME, listTime[index]);
                if (local[instance]) {
                    putOrigin(t, at, x, y, z);
                    putVector(t, at + ParticleEmitterLayout.POSITION, 0, 0, 0);
                    putVector(t, at + ParticleEmitterLayout.PREVIOUS, 0, 0, 0);
                    // world down turned into the emitter's frame: by the rotation's inverse
                    putVector(t, at + ParticleEmitterLayout.DOWN, -2 * (qw * qz + qx * qy),
                        -1 + 2 * (qx * qx + qz * qz), 2 * (qw * qx - qy * qz));
                    putVector(t, at + ParticleEmitterLayout.SHIFT, 0, 0, 0);
                    putVector(t, at + ParticleEmitterLayout.INHERITED, 0, 0, 0);
                    // the world's wind likewise, by the rotation's inverse
                    turnBack(effects.wind(0), effects.wind(1), effects.wind(2), qx, qy, qz, qw);
                    putVector(t, at + ParticleEmitterLayout.WIND, turned[0], turned[1], turned[2]);
                } else {
                    putOrigin(t, at, ox, oy, oz);
                    putVector(t, at + ParticleEmitterLayout.SHIFT, sx, sy, sz);
                    putVector(t, at + ParticleEmitterLayout.INHERITED, vx, vy, vz);
                    putVector(t, at + ParticleEmitterLayout.POSITION, (float) (x - ox), (float) (y - oy),
                        (float) (z - oz));
                    putVector(t, at + ParticleEmitterLayout.PREVIOUS, (float) (previous[3 * slot] - ox),
                        (float) (previous[3 * slot + 1] - oy), (float) (previous[3 * slot + 2] - oz));
                    putVector(t, at + ParticleEmitterLayout.DOWN, 0, -1, 0);
                    putVector(t, at + ParticleEmitterLayout.WIND, effects.wind(0), effects.wind(1), effects.wind(2));
                }
                fresh[instance] = false;
                int chunks = cpu[instance] ? 0 : ParticlePool.chunksOf(capacities[instance]);
                for (int c = 0; c < chunks; c++) {
                    chunkStaged.putInt(4L * chunkCount++, index);
                }
                sortOf[index] = ParticleConstants.NO_SORT;
                if (sortedCount < ParticleConstants.SORT_EMITTERS && sorted(block.sorted[e])) {
                    // CPU emitters too: their particles are in their ranges like any
                    int place = sortedCount++;
                    sortOf[index] = place;
                    sortStaged.putInt(4L * place, index);
                    for (int c = 0; c < ParticlePool.chunksOf(capacities[instance]); c++) {
                        sortChunkStaged.putInt(8L * sortChunkCount, place);
                        sortChunkStaged.putInt(8L * sortChunkCount + 4, c);
                        sortChunkCount++;
                    }
                }
            }
            times[slot] = time;
            previous[3 * slot] = x;
            previous[3 * slot + 1] = y;
            previous[3 * slot + 2] = z;
        }
    }

    /** Writes the scene's colliders (at most {@code MAX_COLLIDERS}) for the frame. */
    private void colliders(EffectColliders colliders) {
        colliderCount = Math.min(colliders.count(), ParticleConstants.MAX_COLLIDERS);
        for (int i = 0; i < colliderCount; i++) {
            long at = (long) ParticleColliderLayout.BYTES * i;
            double x = colliders.position(i, 0);
            double y = colliders.position(i, 1);
            double z = colliders.position(i, 2);
            float hx = (float) x;
            float hy = (float) y;
            float hz = (float) z;
            colliderStaged.putVector(at + ParticleColliderLayout.POSITION_HIGH, hx, hy, hz);
            colliderStaged.putVector(at + ParticleColliderLayout.POSITION_LOW, (float) (x - hx), (float) (y - hy),
                (float) (z - hz));
            colliderStaged.putInt(at + ParticleColliderLayout.KIND, colliders.kind(i).ordinal());
            colliderStaged.putInt(at + ParticleColliderLayout.LAYERS, colliders.layers(i));
            for (int c = 0; c < 4; c++) {
                colliderStaged.putFloat(at + ParticleColliderLayout.ROTATION + 4L * c,
                    (float) colliders.rotation(i, c));
            }
            colliderStaged.putVector(at + ParticleColliderLayout.SIZE, colliders.size(i, 0), colliders.size(i, 1),
                colliders.size(i, 2));
        }
    }

    /**
     * Lets {@code collide.height} modules collide with {@code world}'s heights on the GPU: a square of {@code size}
     * metres around the {@link #heightFocus focus}, sampled at {@code resolution} by {@code resolution} points, taken
     * again when the focus moves a quarter of it away. Null: none (height collision does nothing on the GPU).
     */
    @AllowAllocation("the height field set up")
    public Particles heights(ParticleWorld world, float size, int resolution) {
        if (world != null && (!(size > 0) || resolution < 2 || resolution > 4096)) {
            throw new IllegalArgumentException("Not a height field: " + size + " m, " + resolution + " points");
        }
        if (heights != null) {
            retired.add(heights);
            heights = null;
        }
        heightWorld = world;
        heightSize = size;
        heightResolution = resolution;
        heightX = Double.NaN;
        if (world != null) {
            long bytes = 4L * resolution * resolution;
            heights = FrameBuffer.create(device, bytes, STORAGE, slots);
            heightStaged = new Staging(bytes);
        }
        return this;
    }

    /** Where the height field centers: the camera, the player; it follows when it moves far enough. */
    public void heightFocus(double x, double z) {
        focusX = x;
        focusZ = z;
    }

    /** How many times the height field was taken from the world since this was made. */
    public long heightRefreshes() {
        return heightRefreshes;
    }

    /** Takes the height field again around the focus where it moved a quarter of its size from the last center. */
    private void refreshHeights() {
        if (heightWorld == null || Math.abs(focusX - heightX) < 0.25 * heightSize
            && Math.abs(focusZ - heightZ) < 0.25 * heightSize) {
            return;
        }
        heightX = focusX;
        heightZ = focusZ;
        int n = heightResolution;
        double spacing = heightSize / (n - 1.0);
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                double h = heightWorld.height(heightX - 0.5 * heightSize + x * spacing,
                    heightZ - 0.5 * heightSize + z * spacing);
                heightStaged.putFloat(4L * (z * n + x), Double.isNaN(h) ? -1e30f : (float) h);
            }
        }
        heightStaged.copyTo(heights, 4L * n * n);
        heightRefreshes++;
    }

    /** The collision depth's buffer, for the view's {@link ParticleCollisionDepth} to write. */
    ExternalBuffer depthImport() {
        return depthImport;
    }

    /** The collision view wrote its depth: depth collision has a frame to read from the next on. */
    void depthCaptured() {
        depthReady = true;
    }

    private final float[] turned = new float[3];

    /** ({@code x}, {@code y}, {@code z}) turned by the inverse of the rotation ({@code qx}, ..., {@code qw}). */
    private void turnBack(float x, float y, float z, float qx, float qy, float qz, float qw) {
        float cx = 2 * (-qy * z + qz * y);
        float cy = 2 * (-qz * x + qx * z);
        float cz = 2 * (-qx * y + qy * x);
        turned[0] = x + qw * cx - qy * cz + qz * cy;
        turned[1] = y + qw * cy - qz * cx + qx * cz;
        turned[2] = z + qw * cz - qx * cy + qy * cx;
    }

    private static boolean sorted(boolean[] renderers) {
        for (boolean sorted : renderers) {
            if (sorted) {
                return true;
            }
        }
        return false;
    }

    private void putParameters(Effects effects, long effect, int offset, int floats) {
        if (parameterScratch.length < floats) {
            parameterScratch = new float[Math.max(floats, 2 * parameterScratch.length)];
        }
        effects.parameters(effect, parameterScratch, 0);
        parameterStaged.room(4L * (offset + floats));
        for (int i = 0; i < floats; i++) {
            parameterStaged.putFloat(4L * (offset + i), parameterScratch[i]);
        }
    }

    private static void putOrigin(Staging t, int at, double x, double y, double z) {
        float hx = (float) x;
        float hy = (float) y;
        float hz = (float) z;
        putVector(t, at + ParticleEmitterLayout.ORIGIN_HIGH, hx, hy, hz);
        putVector(t, at + ParticleEmitterLayout.ORIGIN_LOW, (float) (x - hx), (float) (y - hy), (float) (z - hz));
    }

    private static void putVector(Staging t, long at, float x, float y, float z) {
        t.putVector(at, x, y, z);
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
        spawnStaged.room((long) ParticleSpawnLayout.BYTES * total);
        for (int i = 0; i < n; i++) {
            int index = placeOf(spawns, i);
            if (index < 0) {
                continue;
            }
            int slot = (int) spawns.effect(i);
            int at = ParticleSpawnLayout.BYTES * recordFill[index]++;
            int count = spawns.count(i);
            Staging r = spawnStaged;
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

    /** Writes the CPU emitters' particles for the upload; their boxes and counts into their records. */
    private void upload() {
        cpuEmitters.begin();
        for (int i = 0; i < emitterCount; i++) {
            int instance = listInstance[i];
            if (!cpu[instance]) {
                continue;
            }
            int slot = listSlot[i];
            int before = cpuEmitters.uploaded();
            int first = cpuEmitters.write(i, ids[slot], listEmitter[i], capacities[instance], writers[instance],
                simulation, origins[3 * slot], origins[3 * slot + 1], origins[3 * slot + 2]);
            recordStart[i] = first;
            recordFill[i] = first;
            demand[i] = cpuEmitters.uploaded() - before;
            long at = (long) ParticleEmitterLayout.BYTES * i;
            putVector(emitterStaged, at + ParticleEmitterLayout.POSITION, cpuEmitters.lowX, cpuEmitters.lowY,
                cpuEmitters.lowZ);
            putVector(emitterStaged, at + ParticleEmitterLayout.PREVIOUS, cpuEmitters.highX, cpuEmitters.highY,
                cpuEmitters.highZ);
        }
    }

    /**
     * The writer of CPU emitter {@code emitter} of playing effect {@code effect}: from now on the GPU draws the records
     * the game writes into it instead of the simulation's particles of that emitter. Made once; inert (and
     * {@link ParticleWriter#ended()}) once the effect ended. Call after an {@link #update} has seen the effect.
     */
    @AllowAllocation("a writer made once per emitter")
    public ParticleWriter writer(long effect, int emitter) {
        int instance = instance(effect, emitter);
        if (instance < 0 || !cpu[instance]) {
            throw new IllegalArgumentException("Not a CPU emitter with room of a playing effect: " + emitter
                + " of " + Long.toHexString(effect));
        }
        if (writers[instance] == null) {
            writers[instance] = new ParticleWriter(effect, emitter, capacities[instance]);
        }
        return writers[instance];
    }

    /** Lists the frame's GPU emitters with ribbons of several strands, for their order. */
    private void ribbons() {
        ribbonCount = 0;
        for (int i = 0; i < emitterCount; i++) {
            if (cpu[listInstance[i]]) {
                continue;
            }
            int strands = 1;
            List<Renderer> renderers = blocks[listSlot[i]].data.emitters().get(listEmitter[i]).renderers();
            for (int r = 0; r < renderers.size(); r++) {
                strands = Math.max(strands, ModulePrograms.ribbons(renderers.get(r)));
            }
            if (strands > 1) {
                ribbonStaged.putInt(8L * ribbonCount, i);
                ribbonStaged.putInt(8L * ribbonCount + 4, strands);
                ribbonCount++;
            }
        }
    }

    /** Emitters of the frame's list drawing sorted billboards (at most {@code SORT_EMITTERS}). */
    int sortedCount() {
        return sortedCount;
    }

    /** Chunks of their ranges. */
    int sortChunkCount() {
        return sortChunkCount;
    }

    /** The place among the frame's sorted emitters of the emitter at {@code index} of its list, or {@code NO_SORT}. */
    int sortOf(int index) {
        return sortOf[index];
    }

    /** Whether renderer {@code r} of the emitter at {@code index} of the frame's list draws sorted. */
    boolean rendererSorted(int index, int r) {
        return blocks[listSlot[index]].sorted[listEmitter[index]][r];
    }

    long sortListAddress() {
        return sortList.deviceAddress();
    }

    long sortChunksAddress() {
        return sortChunks.deviceAddress();
    }

    /** Emitters whose ribbons the frame orders. */
    int ribbonCount() {
        return ribbonCount;
    }

    long ribbonListAddress() {
        return ribbonList.deviceAddress();
    }

    long orderAddress() {
        return order.deviceAddress();
    }

    /** The side arrays' half the frame wrote, by address. */
    long sideAddress() {
        return pool.sideBuffer().deviceAddress() + 16L * written * pool.sideUnits();
    }

    /**
     * Ends the beams of emitter {@code emitter} of playing effect {@code effect} at world position ({@code x},
     * {@code y}, {@code z}) from now on: another node, a target. Without one a beam reaches its {@code length} along the
     * emitter's +Y. Allocates nothing.
     */
    public void beamEnd(long effect, int emitter, double x, double y, double z) {
        int instance = instance(effect, emitter);
        if (instance < 0) {
            throw new IllegalArgumentException("Not an emitter with room of a playing effect: " + emitter + " of "
                + Long.toHexString(effect));
        }
        beamEnds[3 * instance] = x;
        beamEnds[3 * instance + 1] = y;
        beamEnds[3 * instance + 2] = z;
        beamEnded[instance] = true;
    }

    /**
     * Along {@code axis}, where beam renderer {@code r} of the emitter at {@code index} of the frame's list ends: as
     * given, or its length along the emitter's +Y.
     */
    double beamEnd(int index, int r, int axis) {
        int instance = listInstance[index];
        if (beamEnded[instance]) {
            return beamEnds[3 * instance + axis];
        }
        Renderer renderer = blocks[listSlot[index]].data.emitters().get(listEmitter[index]).renderers().get(r);
        double length = ModulePrograms.setting(renderer, "length", 1);
        double qx = listRotation[4 * index];
        double qy = listRotation[4 * index + 1];
        double qz = listRotation[4 * index + 2];
        double qw = listRotation[4 * index + 3];
        // +Y turned by the rotation
        double up = switch (axis) {
            case 0 -> 2 * (qx * qy - qw * qz);
            case 1 -> 1 - 2 * (qx * qx + qz * qz);
            default -> 2 * (qy * qz + qw * qx);
        };
        return listPosition[3 * index + axis] + length * up;
    }

    /** The effect time of the emitter at {@code index} of the frame's list, in seconds. */
    float listTime(int index) {
        return listTime[index];
    }

    /** Whether the emitter at {@code index} of the frame's list is a CPU emitter. */
    boolean listCpu(int index) {
        return cpu[listInstance[index]];
    }

    /** CPU emitters uploaded in the last update. */
    int cpuEmitterCount() {
        return cpuEmitters.emitters();
    }

    /** The most particles one of them uploaded. */
    int largestUpload() {
        return cpuEmitters.largest();
    }

    /** The place in the frame's list of the emitter spawn record {@code i} spawns, or -1 for none on the GPU. */
    private int placeOf(SpawnRecords spawns, int i) {
        long effect = spawns.effect(i);
        int slot = (int) effect;
        if (slot < 0 || slot >= highest || ids[slot] != effect) {
            return -1;
        }
        int instance = instances[slot][spawns.emitter(i)];
        return instance < 0 || cpu[instance] ? -1 : places[slot][spawns.emitter(i)];
    }

    // ---------------------------------------------------------------------------------------------- the GPU side

    /** The simulation's buffers as buffers of a build. */
    record Buffers(Buffer particles, Buffer side, Buffer state, Buffer chunkCounts, Buffer statistics,
        Buffer dispatch, Buffer order, Buffer depth) {
    }

    @AllowAllocation("building the graph, when it changes")
    Buffers importInto(GraphBuilder g) {
        return new Buffers(g.importBuffer(pool.graphBuffer()), g.importBuffer(pool.sideGraphBuffer()),
            g.importBuffer(stateImport), g.importBuffer(chunkCountsImport), g.importBuffer(statisticsImport),
            g.importBuffer(dispatchImport), g.importBuffer(orderImport), g.importBuffer(depthImport));
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
        return push.source(particles + source * half).targetDelta((target - source) * pool.slots())
            .sideSource(side + source * sideHalf).sideDelta((target - source) * pool.sideUnits())
            .emitters(emitterTable.deviceAddress()).chunks(chunkTable.deviceAddress())
            .chunkCounts(chunkCounts.deviceAddress()).state(state.deviceAddress())
            .spawns(spawnRing.deviceAddress()).programs(programs.wordsAddress()).curves(programs.curvesAddress())
            .parameters(parameterBlocks.deviceAddress()).statistics(statistics.deviceAddress())
            .dispatch(dispatch.deviceAddress()).frame(frameRecord())
            .emitterCount(emitterCount).chunkCount(chunkCount);
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

    /**
     * The pipeline drawing renderers of {@code kind} ({@code RENDER_*}: billboards, ribbons, trails, beams) into color
     * attachments of {@code format} (a VkFormat), made once.
     */
    @AllowAllocation("a pipeline made once per kind and color format")
    Pipeline pipeline(int kind, int format) {
        int key = format * 8 + kind;
        Pipeline pipeline = pipelines.get(key);
        if (pipeline == null) {
            String entry = switch (kind) {
                case ParticleConstants.RENDER_RIBBON -> "ribbonMain";
                case ParticleConstants.RENDER_TRAIL -> "trailMain";
                case ParticleConstants.RENDER_BEAM -> "beamMain";
                default -> "billboardMain";
            };
            try (ShaderModule shaders = ShaderModule.load(device, "shaders/particles/draw.spv")) {
                pipeline = Pipeline.graphics(device).stage(VK_SHADER_STAGE_VERTEX_BIT, shaders, entry)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shaders, "fragmentMain").color(format)
                    .blend(0, Pipeline.Blend.PREMULTIPLIED).pushConstants(ParticleDrawPush.BYTES).doubleSided()
                    .build();
            }
            pipelines.put(key, pipeline);
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

    /**
     * Along {@code axis}, the world position {@code effect}'s world-space particles are offsets from as of the last
     * update: where it started, rebased when it went far; NaN for an effect not followed.
     */
    public double origin(long effect, int axis) {
        int slot = (int) effect;
        return slot >= 0 && slot < highest && ids[slot] == effect ? origins[3 * slot + Objects.checkIndex(axis, 3)]
            : Double.NaN;
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
        pipelines.values().forEach(Pipeline::close);
        emitterTable.close();
        chunkTable.close();
        spawnRing.close();
        parameterBlocks.close();
        state.close();
        chunkCounts.close();
        statistics.close();
        dispatch.close();
        order.close();
        ribbonList.close();
        sortList.close();
        sortChunks.close();
        frameTable.close();
        colliderTable.close();
        if (heights != null) {
            heights.close();
        }
        depth.close();
        programs.close();
        cpuEmitters.close();
        pool.close();
        retired.close();
    }
}
