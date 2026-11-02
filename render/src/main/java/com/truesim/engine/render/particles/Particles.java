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
import com.truesim.engine.render.layout.ParticleSourceLayout;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
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
import com.truesim.engine.render.layout.ParticleEventRecordLayout;
import com.truesim.engine.render.layout.ParticleFrameLayout;
import com.truesim.scene.effects.CpuSimulation;
import com.truesim.scene.effects.EffectColliders;
import com.truesim.scene.effects.ParticleWorld;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectRuntime;
import com.truesim.scene.effects.EffectType;
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
 * particles.update(runtime, null);             // or (effects, runtime.spawns()): without levels of detail, placements
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
    /** Records of a frame's list, per emitter instance it holds: placements of shared effects add records. */
    private static final int PLACEMENT_RECORDS = 4;
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
    /**
     * Frames after one that sent over half of {@code EVENT_SMALL} events in which the event radix sort's passes are
     * recorded; past them the small sort alone runs, which keeps at most {@code EVENT_SMALL} a frame.
     */
    static final int EVENT_WINDOW = 60;
    /** Event sorts {@link #eventSort} can force: both as the counts say, the small sort's alone, the radix sort's. */
    static final int EVENTS_EITHER = 0;
    static final int EVENTS_SMALL = 1;
    static final int EVENTS_RADIX = 2;

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
    private FrameBuffer emitterTable;
    private final FrameBuffer chunkTable;
    private FrameBuffer spawnRing;
    private FrameBuffer parameterBlocks;
    // The frame's tables as written, each copied to its frame buffer once the update is done
    private final Staging emitterStaged;
    private final Staging chunkStaged;
    private final Staging spawnStaged = new Staging(256L * ParticleSpawnLayout.BYTES);
    private final Staging parameterStaged = new Staging(4L * 1024);
    private final Staging ribbonStaged;
    // Ribbons per parent (particles as emitters): the emitters' places in the list, their chunks (place, chunk)
    private static final int MAX_PARENT_RIBBONS = 64;
    private final Staging parentRibbonStaged = new Staging(4L * MAX_PARENT_RIBBONS);
    private final Staging parentChunkStaged = new Staging(8L * 64);
    private final FrameBuffer parentRibbonList;
    private FrameBuffer parentRibbonChunkTable;
    private int parentRibbonCount;
    private int parentRibbonChunks;
    private boolean parentRibbonsSeen;
    private int parameterFloats;
    // The frame's sorted emitters (each one's place in the list) and the chunks of their ranges, for the sorts
    private final int[] sortOf;
    private FrameBuffer sortList;
    private FrameBuffer sortChunks;
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
    private RayTracing rayTracing;
    private MeshletRenderer rayRenderer;
    // What mesh and bone shapes emit from (effects.md §2.7): the renderer whose pool's meshes they read, the meshes'
    // alias tables, who names each emitter's mesh and joints, and the frame's sources
    private MeshletRenderer meshRenderer;
    private final MeshTables meshTables;
    private Sources sources;
    private FrameBuffer sourceTable;
    private final Staging sourceStaged = new Staging(16L * ParticleSourceLayout.BYTES);
    private int sourceCount;
    private long setup; // changes with what the simulation reads: ray tracing, meshes
    // Mesh particles (E15): the scene whose range the GPU writes holds their instances, that range's ids handed to
    // emitter instances, the frame's emitters with mesh renderers, and the meshes registered for mesh renderers
    private GpuScene meshScene;
    private com.truesim.engine.render.RangeAllocator meshIds;
    private int meshBase;
    private final FrameBuffer meshList;
    private final Staging meshStaged;
    private int meshCount;
    private int largestMesh;
    private int meshUnplaced;
    private int[] holes = new int[16]; // ranges given back whose slots the next frame clears: first id, count
    // Particle lights (step 17b): the lights they write into, their budget, the frame's light emitters, the selection
    private com.truesim.engine.render.Lights lights;
    private int lightBudget;
    private final FrameBuffer lightList;
    private final Staging lightStaged = new Staging(4L * ParticleConstants.LIGHT_ENTRY_WORDS
        * ParticleConstants.MAX_LIGHT_EMITTERS);
    private int lightCount;
    private int largestLight;
    private final GpuBuffer lightState;
    private final ExternalBuffer lightStateImport;
    private int holeCount;
    private final Map<AssetRef<?>, long[]> meshAssets = new HashMap<>();
    // Events (E9): the frame's records, their sort keys (two buffers), the sort's counts and dispatch, each sorted
    // event's first child; and each effect's emitters' places, for their targets
    private final GpuBuffer events;
    private final GpuBuffer eventKeys;
    private final GpuBuffer eventKeysOther;
    private final GpuBuffer eventArgs;
    private final GpuBuffer eventCounts;
    private final GpuBuffer childPrefix;
    private final ExternalBuffer eventsImport;
    private final ExternalBuffer eventKeysImport;
    private final ExternalBuffer eventKeysOtherImport;
    private final ExternalBuffer eventArgsImport;
    private final ExternalBuffer eventCountsImport;
    private final ExternalBuffer childPrefixImport;
    private final FrameBuffer targetTable;
    private final Staging targetStaged;
    private int targetCount;

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
    private final int[] sourceMesh; // the pool mesh its mesh shapes emit from, -1: none
    private final MeshData[] sourceData;
    private final double[][] sourceWorld; // that mesh's world transform, rows of 3 x 4; made when first set
    private final int[] meshFirsts; // its first id of the mesh particles' range, -1: none
    private final int[] jointCounts;
    private final double[][] jointWorld; // the joints its bone shapes run along, x, y, z each; made when first set
    private final ParticleWriter[] writers;
    private final CpuEmitters cpuEmitters;
    private CpuSimulation simulation;
    private ParticleModules modules = BuiltInModules.MODULES;
    private GpuBuffer previousInstances;
    private ExternalBuffer previousImport;
    private EffectRuntime runtime; // the update's: levels of detail, drawn fractions, placements' shares
    private double[] owed = new double[16]; // per effect slot: seconds its held emitters have not moved
    private long updates; // updates so far: emitters of reduced rates take turns by it
    private int sortSlots;
    private boolean halfSeen;
    private long sortedSlots; // the frame's sorted records' capacities
    private int drawCount; // records of the frame's list: the simulated emitters, then placements' records
    private final boolean[] listHidden;
    private final Effects.EffectVisitor placementVisitor = this::placement;
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
    private long eventsTotal;
    private long eventsDroppedTotal;
    private boolean sendsEvents;
    // The last statistics' frame that sent over half of EVENT_SMALL events; whether the frame records the radix sort
    private long eventsBusy = Long.MIN_VALUE;
    private boolean radixEvents;
    private int eventSort = EVENTS_EITHER;
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
        sourceMesh = new int[maxEmitters];
        sourceData = new MeshData[maxEmitters];
        sourceWorld = new double[maxEmitters][];
        jointCounts = new int[maxEmitters];
        meshFirsts = new int[maxEmitters];
        Arrays.fill(meshFirsts, -1);
        jointWorld = new double[maxEmitters][];
        writers = new ParticleWriter[maxEmitters];
        freeInstances = new int[maxEmitters];
        for (int i = 0; i < maxEmitters; i++) {
            freeInstances[i] = maxEmitters - 1 - i; // the lowest first
        }
        freeCount = maxEmitters;
        int records = PLACEMENT_RECORDS * maxEmitters; // placements draw records of their own
        listInstance = new int[records];
        listSlot = new int[records];
        listEmitter = new int[records];
        listPosition = new double[3 * records];
        listRotation = new double[4 * records];
        listTime = new float[records];
        listHidden = new boolean[records];
        beamEnds = new double[3 * maxEmitters];
        beamEnded = new boolean[maxEmitters];
        demand = new int[maxEmitters];
        recordStart = new int[maxEmitters];
        recordFill = new int[maxEmitters];
        aliveOf = new int[maxEmitters];
        overflowOf = new int[maxEmitters];
        emitterStaged = new Staging((long) ParticleEmitterLayout.BYTES * maxEmitters);
        ribbonStaged = new Staging(8L * maxEmitters);
        sortOf = new int[records];
        sortStaged = new Staging(4L * Math.min(maxEmitters, ParticleConstants.SORT_EMITTERS));
        ParticlePool p = null;
        ModulePrograms m = null;
        CpuEmitters c = null;
        GpuBuffer[] made = new GpuBuffer[13];
        FrameBuffer[] tables = new FrameBuffer[13];
        try {
            p = new ParticlePool(device, budget);
            m = new ModulePrograms(device, slots);
            m.assetSlots(this::textureSlot);
            c = new CpuEmitters(device, slots, maxEmitters);
            made[0] = GpuBuffer.deviceLocal(device, 4L * ParticleConstants.STATE_WORDS * maxEmitters, GPU_USAGE, Particles.queues(device));
            made[1] = GpuBuffer.deviceLocal(device, 8L * p.slots() / ParticleConstants.CHUNK, GPU_USAGE, Particles.queues(device));
            made[2] = GpuBuffer.deviceLocal(device, statisticsBytes(maxEmitters), GPU_USAGE, Particles.queues(device));
            made[3] = GpuBuffer.deviceLocal(device, 16, GPU_USAGE | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, Particles.queues(device));
            made[4] = GpuBuffer.deviceLocal(device, 4L * p.slots(), GPU_USAGE, Particles.queues(device));
            made[5] = GpuBuffer.deviceLocal(device, 4L * (ParticleConstants.DEPTH_HEADER
                + (long) ParticleConstants.DEPTH_GRID * ParticleConstants.DEPTH_GRID), GPU_USAGE, Particles.queues(device));
            long events = ParticleConstants.EVENT_CAPACITY;
            made[6] = GpuBuffer.deviceLocal(device, ParticleEventRecordLayout.BYTES * events, GPU_USAGE, Particles.queues(device));
            made[7] = GpuBuffer.deviceLocal(device, 8 * events, GPU_USAGE, Particles.queues(device));
            made[8] = GpuBuffer.deviceLocal(device, 8 * events, GPU_USAGE, Particles.queues(device));
            made[9] = GpuBuffer.deviceLocal(device, 32, GPU_USAGE | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, Particles.queues(device));
            made[10] = GpuBuffer.deviceLocal(device, 4L * 16 * (events / ParticleConstants.SORT_GROUP), GPU_USAGE, Particles.queues(device));
            made[11] = GpuBuffer.deviceLocal(device, 4 * events, GPU_USAGE, Particles.queues(device));
            made[12] = GpuBuffer.deviceLocal(device, 4L * ParticleConstants.LIGHT_STATE_WORDS
                * (ParticleConstants.MAX_LIGHT_EMITTERS + 1), GPU_USAGE, Particles.queues(device));
            tables[9] = FrameBuffer.create(device, 4L * maxEmitters, STORAGE, slots, Particles.queues(device));
            tables[10] = FrameBuffer.create(device, 16L * ParticleSourceLayout.BYTES, STORAGE, slots, Particles.queues(device));
            tables[11] = FrameBuffer.create(device, 4L * maxEmitters, STORAGE, slots, Particles.queues(device));
            tables[12] = FrameBuffer.create(device, 4L * ParticleConstants.LIGHT_ENTRY_WORDS
                * ParticleConstants.MAX_LIGHT_EMITTERS, STORAGE, slots, Particles.queues(device));
            tables[7] = FrameBuffer.create(device, ParticleFrameLayout.BYTES, STORAGE, slots, Particles.queues(device));
            tables[8] = FrameBuffer.create(device, (long) ParticleColliderLayout.BYTES
                * ParticleConstants.MAX_COLLIDERS, STORAGE, slots, Particles.queues(device));
            tables[4] = FrameBuffer.create(device, 8L * maxEmitters, STORAGE, slots, Particles.queues(device));
            tables[0] = FrameBuffer.create(device, (long) ParticleEmitterLayout.BYTES * maxEmitters, STORAGE, slots, Particles.queues(device));
            tables[1] = FrameBuffer.create(device, 4L * p.slots() / ParticleConstants.CHUNK, STORAGE, slots, Particles.queues(device));
            tables[2] = FrameBuffer.create(device, 256L * ParticleSpawnLayout.BYTES, STORAGE, slots, Particles.queues(device));
            tables[3] = FrameBuffer.create(device, 4L * 1024, STORAGE, slots, Particles.queues(device));
            tables[5] = FrameBuffer.create(device, 4L * Math.min(maxEmitters, ParticleConstants.SORT_EMITTERS),
                STORAGE, slots, Particles.queues(device));
            tables[6] = FrameBuffer.create(device, 8L * p.slots() / ParticleConstants.CHUNK, STORAGE, slots, Particles.queues(device));
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
        sortSlots = p.slots();
        programs = m;
        cpuEmitters = c;
        state = made[0];
        chunkCounts = made[1];
        statistics = made[2];
        dispatch = made[3];
        order = made[4];
        depth = made[5];
        this.events = made[6];
        eventKeys = made[7];
        eventKeysOther = made[8];
        eventArgs = made[9];
        eventCounts = made[10];
        childPrefix = made[11];
        targetTable = tables[9];
        sourceTable = tables[10];
        meshList = tables[11];
        lightList = tables[12];
        lightState = made[12];
        lightStateImport = new ExternalBuffer("particle light selection", lightState);
        meshStaged = new Staging(4L * maxEmitters);
        meshTables = new MeshTables(device, slots);
        targetStaged = new Staging(4L * maxEmitters);
        frameTable = tables[7];
        colliderTable = tables[8];
        ribbonList = tables[4];
        parentRibbonList = FrameBuffer.create(device, 4L * MAX_PARENT_RIBBONS, STORAGE, slots, queues(device));
        parentRibbonChunkTable = FrameBuffer.create(device, 8L * 64, STORAGE, slots, queues(device));
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
        eventsImport = new ExternalBuffer("particle events", this.events);
        eventKeysImport = new ExternalBuffer("particle event keys", eventKeys);
        eventKeysOtherImport = new ExternalBuffer("particle event keys, other", eventKeysOther);
        eventArgsImport = new ExternalBuffer("particle event args", eventArgs);
        eventCountsImport = new ExternalBuffer("particle event counts", eventCounts);
        childPrefixImport = new ExternalBuffer("particle event children", childPrefix);
    }

    private static long statisticsBytes(int maxEmitters) {
        return 4L * (ParticleConstants.STATISTICS_HEADER + 2L * maxEmitters);
    }

    /**
     * The queue families buffers the simulation and draws use are shared by: graphics and async compute, so the
     * simulation may run on either queue ({@link ParticleSimulation#async}).
     */
    static int[] queues(VulkanDevice device) {
        return new int[] {device.graphics().family(), device.compute().family()};
    }

    /**
     * What {@code effect} costs the pool, in bytes: each GPU and CPU emitter's range at its capacity as placed (two
     * particles a slot) and its side units (two halves), for budgets ({@code EffectRuntime.budget}).
     */
    public long bytes(EffectData effect) {
        long bytes = 0;
        for (EmitterData e : effect.emitters()) {
            long capacity = ParticlePool.chunksOf(placedCapacity(e, effect.type())) * (long) ParticleConstants.CHUNK;
            bytes += capacity * 2 * ParticleLayout.BYTES + 2L * 16 * sideUnits((int) capacity, true, false, 0);
        }
        return bytes;
    }

    /** The pool's bytes: what budgets keep effects within. */
    public long budget() {
        return (long) pool.slots() * ParticlePool.SLOT_BYTES;
    }

    /**
     * {@code emitter}'s capacity as its range is placed: a GPU emitter's scaled by its effect type's spawn scale at the
     * quality ({@code effects.quality}), at least a chunk; a CPU emitter's as authored (it uploads what it simulates).
     */
    int placedCapacity(EmitterData emitter) {
        return placedCapacity(emitter, EffectType.DEFAULT);
    }

    private int placedCapacity(EmitterData emitter, EffectType type) {
        float scale = type.spawnScale(quality);
        if (emitter.target() == Target.CPU || scale >= 1) {
            return emitter.capacity();
        }
        return Math.max(Math.min(emitter.capacity(), ParticleConstants.CHUNK),
            (int) Math.ceil(emitter.capacity() * (double) scale));
    }

    VulkanDevice device() {
        return device;
    }

    FrameSlots slots() {
        return slots;
    }

    /** Whether there is anything to draw: an update listed emitter instances. */
    boolean drawable() {
        return updatedAt >= 0 && drawCount > 0;
    }

    /**
     * Whether views draw particles at half resolution: at low quality, or since an effect with a renderer drawn so
     * ({@code half} 1) played; a change rebuilds the draws' graph.
     */
    boolean drawsHalf() {
        return halfSeen || quality == com.truesim.scene.effects.Quality.LOW;
    }

    /** Records of the frame's list the draws read: its emitter instances, then placements' records. */
    int drawCount() {
        return drawCount;
    }

    /**
     * Whether the record at {@code index} of the frame's list is not drawn as itself: a variant of a shared effect,
     * drawn at its placements only.
     */
    boolean listHidden(int index) {
        return listHidden[index];
    }

    /** Whether the record at {@code index} of the frame's list is a placement's: drawn, never simulated. */
    boolean listPlacement(int index) {
        return index >= emitterCount;
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
        update(effects, spawns, simulation, null);
    }

    /**
     * {@link #update(Effects, SpawnRecords, CpuSimulation)} of {@code runtime}'s effects and spawns, as it decided
     * them: emitters draw their effects' {@link EffectRuntime#drawn drawn fractions} (the others fading out by their
     * hash), update every {@link EffectRuntime#updateEvery n frames} (moved on by the time between, and drawn moved on
     * by their velocities in the frames between), and placements of shared effects draw their variants' particles
     * where they are, at their shares ({@link EffectRuntime#placementWeight}).
     */
    public void update(EffectRuntime runtime, CpuSimulation simulation) {
        update(runtime.effects(), runtime.spawns(), simulation, runtime);
    }

    private void update(Effects effects, SpawnRecords spawns, CpuSimulation simulation, EffectRuntime runtime) {
        Objects.requireNonNull(effects, "effects");
        this.simulation = simulation;
        this.runtime = runtime;
        updates++;
        // Between frames, the update is for the frame about to begin
        long frames = !slots.isSingle() && slots.current() < 0 ? slots.frames() + 1 : slots.frames();
        if (updatedAt != frames || slots.isSingle()) {
            source = written;
            target = 1 - written;
            written = target;
            updatedAt = frames;
        }
        radixEvents = eventSort == EVENTS_RADIX
            || eventSort == EVENTS_EITHER && eventsBusy >= frames - EVENT_WINDOW;
        retired.release();
        meshTables.release();
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
        this.runtime = null;
    }

    /** Hands the frame's tables to the GPU: a copy each, into frame buffers grown where they must. */
    private void copyTables() {
        long emitterBytes = (long) ParticleEmitterLayout.BYTES * drawCount;
        if (emitterTable.size() < emitterBytes) {
            emitterTable = grown(emitterTable, emitterBytes);
        }
        emitterStaged.copyTo(emitterTable, emitterBytes);
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
        parentRibbonStaged.copyTo(parentRibbonList, 4L * parentRibbonCount);
        if (parentRibbonChunkTable.size() < 8L * parentRibbonChunks) {
            parentRibbonChunkTable = grown(parentRibbonChunkTable, 8L * parentRibbonChunks);
        }
        parentChunkStaged.copyTo(parentRibbonChunkTable, 8L * parentRibbonChunks);
        long sourceBytes = (long) ParticleSourceLayout.BYTES * sourceCount;
        if (sourceTable.size() < sourceBytes) {
            sourceTable = grown(sourceTable, sourceBytes);
        }
        sourceStaged.copyTo(sourceTable, sourceBytes);
        meshStaged.copyTo(meshList, 4L * meshCount);
        lightStaged.copyTo(lightList, 4L * ParticleConstants.LIGHT_ENTRY_WORDS * lightCount);
        if (sortList.size() < 4L * sortedCount) {
            sortList = grown(sortList, 4L * sortedCount);
        }
        sortStaged.copyTo(sortList, 4L * sortedCount);
        if (sortChunks.size() < 8L * sortChunkCount) {
            sortChunks = grown(sortChunks, 8L * sortChunkCount);
        }
        sortChunkStaged.copyTo(sortChunks, 8L * sortChunkCount);
        cpuEmitters.finish();
        colliderStaged.copyTo(colliderTable, (long) ParticleColliderLayout.BYTES * colliderCount);
        targetStaged.copyTo(targetTable, 4L * targetCount);
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
        r.putLong(ParticleFrameLayout.EVENTS, events.deviceAddress());
        r.putLong(ParticleFrameLayout.EVENT_KEYS, eventKeys.deviceAddress());
        r.putLong(ParticleFrameLayout.EVENT_ARGS, eventArgs.deviceAddress());
        r.putLong(ParticleFrameLayout.CHILD_PREFIX, childPrefix.deviceAddress());
        r.putLong(ParticleFrameLayout.TARGETS, targetTable.deviceAddress());
        r.putLong(ParticleFrameLayout.SOURCES, sourceTable.deviceAddress());
        r.putLong(ParticleFrameLayout.INSTANCES, meshScene == null ? 0 : meshScene.instanceAddress());
        r.putLong(ParticleFrameLayout.MESH_EMITTERS, meshList.deviceAddress());
        r.putLong(ParticleFrameLayout.LIGHT_EMITTERS, lightList.deviceAddress());
        r.putLong(ParticleFrameLayout.LIGHT_STATE, lightState.deviceAddress());
        r.putLong(ParticleFrameLayout.GPU_LIGHTS, lights == null || lights.gpuLightsBuffer() == null ? 0
            : lights.gpuLightsBuffer().deviceAddress());
        r.putInt(ParticleFrameLayout.LIGHT_EMITTER_COUNT, lightCount);
        r.putInt(ParticleFrameLayout.MESH_BASE, meshBase);
        r.putLong(ParticleFrameLayout.PREVIOUS_INSTANCES, previousInstances == null ? depth.deviceAddress()
            : previousInstances.deviceAddress());
        r.putInt(ParticleFrameLayout.LIGHT_BUDGET, lightBudget);
        r.putInt(ParticleFrameLayout.EVENT_LIMIT, radixEvents ? ParticleConstants.EVENT_CAPACITY
            : ParticleConstants.EVENT_SMALL);
        r.putInt(ParticleFrameLayout.SMALL_EVENTS, eventSort == EVENTS_RADIX ? 0 : ParticleConstants.EVENT_SMALL);
        GeometryPool meshes = meshRenderer == null ? null : meshRenderer.pool();
        r.putLong(ParticleFrameLayout.MESHES, meshes == null ? 0 : meshes.meshTable().deviceAddress());
        r.putLong(ParticleFrameLayout.POSITIONS, meshes == null ? 0 : meshes.positions().deviceAddress());
        r.putLong(ParticleFrameLayout.ATTRIBUTES, meshes == null ? 0 : meshes.attributes().deviceAddress());
        r.putLong(ParticleFrameLayout.MESHLETS, meshes == null ? 0 : meshes.meshlets().deviceAddress());
        r.putLong(ParticleFrameLayout.MESHLET_VERTICES, meshes == null ? 0 : meshes.meshletVertices().deviceAddress());
        r.putLong(ParticleFrameLayout.MESHLET_TRIANGLES, meshes == null ? 0
            : meshes.meshletTriangles().deviceAddress());
        r.putInt(ParticleFrameLayout.COLLIDER_COUNT, colliderCount);
        r.putInt(ParticleFrameLayout.HEIGHT_RESOLUTION, heights == null ? 0 : heightResolution);
        r.putFloat(ParticleFrameLayout.HEIGHT_SIZE, heightSize);
        r.putInt(ParticleFrameLayout.DEPTH_READY, depthReady ? 1 : 0);
        float hx = (float) heightX;
        float hz = (float) heightZ;
        r.putVector(ParticleFrameLayout.HEIGHT_CENTER_HIGH, hx, 0, hz);
        r.putVector(ParticleFrameLayout.HEIGHT_CENTER_LOW, (float) (heightX - hx), 0, (float) (heightZ - hz));
        if (rayTracing != null) {
            r.putLong(ParticleFrameLayout.RAYS, rayTracing.sceneAddress(rayRenderer.scene()));
            double ox = rayTracing.origin(0);
            double oy = rayTracing.origin(1);
            double oz = rayTracing.origin(2);
            r.putVector(ParticleFrameLayout.RAYS_ORIGIN_HIGH, (float) ox, (float) oy, (float) oz);
            r.putVector(ParticleFrameLayout.RAYS_ORIGIN_LOW, (float) (ox - (float) ox), (float) (oy - (float) oy),
                (float) (oz - (float) oz));
        } else {
            r.putLong(ParticleFrameLayout.RAYS, 0);
        }
        r.copyTo(frameTable, ParticleFrameLayout.BYTES);
        return frameTable.deviceAddress();
    }

    private void follow(long effect) {
        if (following.placement(effect)) {
            return; // it draws its variant: nothing of its own to simulate
        }
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
        owed = Arrays.copyOf(owed, length);
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
            } else { // CPU emitters too
                own[e] = placeOrEvict(emitters.get(e), data.type(), block.over[e], block.emits[e], block.trails[e]);
            }
        }
        ids[slot] = effect;
        blocks[slot] = block;
        begin(slot, effect);
    }

    /** Starts {@code effect} in {@code slot} from its beginning: its time, origin and restarts. */
    private void begin(int slot, long effect) {
        times[slot] = 0;
        owed[slot] = 0;
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
            if (instance >= 0 && (e >= emitters.size() || !fits(instance, emitters.get(e), data.type(),
                block.over[e], block.emits[e], block.trails[e]))) {
                give(instance);
                instance = -1;
            }
            if (e < emitters.size()) {
                if (instance >= 0) {
                    renew(instance);
                    local[instance] = !cpu[instance] && emitters.get(e).space() == Space.LOCAL;
                } else {
                    instance = placeOrEvict(emitters.get(e), data.type(), block.over[e], block.emits[e],
                        block.trails[e]);
                }
            }
            own[e] = instance;
        }
        programs.release(old);
        blocks[slot] = block;
        begin(slot, effect);
    }

    /** Whether {@code instance}'s ranges suit {@code emitter} as they are. */
    private boolean fits(int instance, EmitterData emitter, EffectType type, boolean over, boolean emits, int trail) {
        int capacity = placedCapacity(emitter, type);
        return capacities[instance] == capacity && cpu[instance] == (emitter.target() == Target.CPU)
            && sideUnits[instance] == sideUnits(capacity, over, emits, trail)
            && (meshFirsts[instance] >= 0) == (meshIds != null && meshRendered(emitter));
    }

    /** {@code instance} as new: no particles, nothing written to it, no beam end. */
    private void renew(int instance) {
        fresh[instance] = true;
        beamEnded[instance] = false;
        writers[instance] = null;
        sourceMesh[instance] = -1;
        sourceData[instance] = null;
        jointCounts[instance] = 0;
    }

    /**
     * Side units of a range of {@code capacity}: attributes from birth (2 a particle), emit accumulators (1: four of
     * them), the trail's ring (a head and its points).
     */
    private static int sideUnits(int capacity, boolean over, boolean emits, int trail) {
        return ((over ? 2 : 0) + (emits ? 1 : 0) + (trail > 0 ? 1 + trail : 0)) * capacity;
    }

    /** {@link #place}, giving the warm instances back first if there is no room; counted when still none. */
    private int placeOrEvict(EmitterData emitter, EffectType type, boolean over, boolean emits, int trail) {
        int instance = place(emitter, type, over, emits, trail);
        if (instance < 0 && warmCount > 0) {
            cool(true);
            instance = place(emitter, type, over, emits, trail);
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
    private int place(EmitterData emitter, EffectType type, boolean over, boolean emits, int trail) {
        int capacity = placedCapacity(emitter, type);
        boolean room = freeCount > 0 && (trail == 0 || capacity <= MAX_TRAIL_PARTICLES);
        int first = room ? pool.allocate(capacity) : -1;
        int units = sideUnits(capacity, over, emits, trail);
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
        int meshFirst = -1;
        if (meshIds != null && meshRendered(emitter)) {
            meshFirst = (int) meshIds.allocate(capacity, 1);
            meshUnplaced += meshFirst < 0 ? 1 : 0; // simulated, not drawn
        }
        int instance = freeInstances[--freeCount];
        meshFirsts[instance] = meshFirst;
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
            if (own[e] >= 0 && meshFirsts[own[e]] >= 0) {
                hole(meshBase + meshFirsts[own[e]], capacities[own[e]]); // kept warm or given back: drawn no more
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
        if (meshFirsts[instance] >= 0) {
            // Holes from the next simulated frame on: its slots are no emitter's then, and the frames in flight run in
            // order
            meshIds.release(meshFirsts[instance], capacities[instance]);
            hole(meshBase + meshFirsts[instance], capacities[instance]);
            meshFirsts[instance] = -1;
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
        sortedSlots = 0;
        targetCount = 0;
        sourceCount = 0;
        meshCount = 0;
        largestMesh = 0;
        lightCount = 0;
        largestLight = 0;
        sendsEvents = false;
        for (int slot = 0; slot < highest; slot++) {
            long effect = ids[slot];
            if (effect == 0) {
                continue;
            }
            ModulePrograms.Block block = blocks[slot];
            EffectData data = block.data;
            if (!halfSeen) {
                for (int[] drawn : block.drawn) {
                    for (int d : drawn) {
                        halfSeen |= (d & ModulePrograms.DRAWN_HALF) != 0;
                    }
                }
            }
            int floats = data.parameterFloats();
            int parameterOffset = parameterFloats;
            if (floats > 0) {
                parameterFloats += floats;
                putParameters(effects, effect, parameterOffset, floats);
            }
            long time = effects.time(effect);
            double frame = (time - times[slot]) * 1e-6;
            // A reduced update rate (levels of detail): held still between updates, then moved on by the time owed
            boolean decided = runtime != null && runtime.decided(effect); // else as for an update without a runtime
            int every = decided ? runtime.updateEvery(effect) : 1;
            boolean held = every > 1 && (updates + slot) % every != 0;
            double step = held ? 0 : frame + owed[slot];
            owed[slot] = held ? owed[slot] + frame : 0;
            float dt = (float) Math.min(CpuSimulation.MAX_STEP * every, step);
            float drawn = decided ? runtime.drawn(effect) : 1;
            boolean hidden = effects.variant(effect);
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
            int targets = targetCount;
            targetCount += block.programs.length;
            for (int e = 0; e < block.programs.length; e++) {
                targetStaged.putInt(4L * (targets + e), ParticleConstants.NO_EMITTER);
            }
            for (int e = 0; e < block.programs.length; e++) {
                int instance = own[e];
                if (instance < 0) {
                    continue;
                }
                int index = emitterCount++;
                targetStaged.putInt(4L * (targets + e), index);
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
                    | (cpu[instance] ? ParticleConstants.EMITTER_CPU : 0)
                    | (meshFirsts[instance] >= 0 && block.meshRenderers[e] >= 0 ? ParticleConstants.EMITTER_MESH : 0)
                    | (held && !cpu[instance] && !fresh[instance] ? ParticleConstants.EMITTER_HELD : 0)
                    | (hidden ? ParticleConstants.EMITTER_HIDDEN : 0));
                t.putFloat(at + ParticleEmitterLayout.DRAWN, drawn);
                t.putFloat(at + ParticleEmitterLayout.FADE, 1);
                t.putFloat(at + ParticleEmitterLayout.SCALE, 1);
                t.putFloat(at + ParticleEmitterLayout.HELD, held ? (float) owed[slot] : 0);
                listHidden[index] = hidden;
                t.putInt(at + ParticleEmitterLayout.SIDE, sideFirsts[instance]);
                t.putInt(at + ParticleEmitterLayout.SEED, EffectRuntime.emitterSeed(seed, e));
                t.putFloat(at + ParticleEmitterLayout.DT, dt);
                t.putFloat(at + ParticleEmitterLayout.FRAME, (float) Math.min(step, CpuSimulation.MAX_STEP * every));
                putVector(t, at + ParticleEmitterLayout.ROTATION, qx, qy, qz);
                t.putFloat(at + ParticleEmitterLayout.ROTATION + 12, qw);
                t.putInt(at + ParticleEmitterLayout.PARAMETERS, parameterOffset);
                t.putInt(at + ParticleEmitterLayout.CHUNKS, chunkCount);
                t.putInt(at + ParticleEmitterLayout.INSTANCE, instance);
                t.putInt(at + ParticleEmitterLayout.SIDE_UNITS, sideUnits[instance] == 0 ? 0
                    : sideUnits[instance] / capacities[instance]);
                t.putFloat(at + ParticleEmitterLayout.TIME, listTime[index]);
                t.putInt(at + ParticleEmitterLayout.TARGETS, targets);
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
                int source = ParticleConstants.NO_SOURCE;
                if (block.sourced[e] && !cpu[instance]) {
                    if (sources != null) {
                        sources.emit(effect, e); // names its mesh and joints for the frame
                    }
                    source = putSource(instance, x, y, z, qx, qy, qz, qw);
                }
                t.putInt(at + ParticleEmitterLayout.SOURCE, source);
                putMesh(t, at, block, e, index, instance);
                putLight(block, e, index, instance);
                fresh[instance] = false;
                sendsEvents |= !blocks[slot].data.emitters().get(e).events().isEmpty();
                int chunks = cpu[instance] ? 0 : ParticlePool.chunksOf(capacities[instance]);
                for (int c = 0; c < chunks; c++) {
                    chunkStaged.putInt(4L * chunkCount++, index);
                }
                sortOf[index] = ParticleConstants.NO_SORT;
                if (!hidden) { // CPU emitters too: their particles are in their ranges like any
                    sortable(index, block, e, instance);
                }
            }
            times[slot] = time;
            previous[3 * slot] = x;
            previous[3 * slot + 1] = y;
            previous[3 * slot + 2] = z;
        }
        drawCount = emitterCount;
        effects.forEach(placementVisitor);
        if (sortedSlots > sortSlots) {
            sortSlots = (int) Math.min(Integer.MAX_VALUE / 8, sortedSlots + sortedSlots / 2);
        }
    }

    /** Lists the record at {@code index} (of emitter {@code e} of {@code block}, instance {@code instance}) for sorting. */
    private void sortable(int index, ModulePrograms.Block block, int e, int instance) {
        if (sortedCount < ParticleConstants.SORT_EMITTERS && sorted(block.sorted[e])) {
            int place = sortedCount++;
            sortOf[index] = place;
            sortedSlots += capacities[instance];
            sortStaged.room(4L * sortedCount);
            sortStaged.putInt(4L * place, index);
            int chunks = ParticlePool.chunksOf(capacities[instance]);
            sortChunkStaged.room(8L * (sortChunkCount + chunks));
            for (int c = 0; c < chunks; c++) {
                sortChunkStaged.putInt(8L * sortChunkCount, place);
                sortChunkStaged.putInt(8L * sortChunkCount + 4, c);
                sortChunkCount++;
            }
        }
    }

    /**
     * Adds placement {@code effect}'s records to the frame's list, if it is shown (not culled, its share of its variant
     * above 0) and its variant is simulated: a copy of each of its variant's records at the placement's origin,
     * rotation and scale, faded by its share, which the draws and sorts read and the simulation never reaches.
     */
    private void placement(long effect) {
        Effects effects = following;
        if (!effects.placement(effect)) {
            return;
        }
        long variant = effects.variantOf(effect);
        int slot = (int) variant;
        if (runtime != null && !runtime.decided(effect)) {
            return; // played since the runtime's update: drawn from the next on
        }
        float weight = runtime == null ? 1 : runtime.culled(effect) ? 0 : runtime.placementWeight(effect);
        if (variant == 0 || slot >= highest || ids[slot] != variant || !(weight > 0)) {
            return;
        }
        float drawn = runtime == null ? 1 : runtime.drawn(effect);
        double x = effects.position(effect, 0);
        double y = effects.position(effect, 1);
        double z = effects.position(effect, 2);
        int[] own = instances[slot];
        ModulePrograms.Block block = blocks[slot];
        for (int e = 0; e < block.programs.length; e++) {
            int instance = own[e];
            if (instance < 0 || drawCount == listInstance.length) {
                continue;
            }
            int from = places[slot][e];
            int index = drawCount++;
            long at = (long) ParticleEmitterLayout.BYTES * index;
            emitterStaged.room(at + ParticleEmitterLayout.BYTES);
            emitterStaged.copy((long) ParticleEmitterLayout.BYTES * from, at, ParticleEmitterLayout.BYTES);
            putOrigin(emitterStaged, (int) at, x, y, z);
            for (int c = 0; c < 4; c++) {
                emitterStaged.putFloat(at + ParticleEmitterLayout.ROTATION + 4L * c, (float) effects.rotation(effect, c));
            }
            emitterStaged.putInt(at + ParticleEmitterLayout.FLAGS, ParticleConstants.EMITTER_LOCAL
                | ParticleConstants.EMITTER_PLACEMENT);
            emitterStaged.putFloat(at + ParticleEmitterLayout.DRAWN, drawn);
            emitterStaged.putFloat(at + ParticleEmitterLayout.FADE, weight);
            emitterStaged.putFloat(at + ParticleEmitterLayout.SCALE, effects.scale(effect));
            listInstance[index] = instance;
            listSlot[index] = slot;
            listEmitter[index] = e;
            listPosition[3 * index] = x;
            listPosition[3 * index + 1] = y;
            listPosition[3 * index + 2] = z;
            for (int c = 0; c < 4; c++) {
                listRotation[4 * index + c] = effects.rotation(effect, c);
            }
            listTime[index] = listTime[from];
            listHidden[index] = false;
            sortOf[index] = ParticleConstants.NO_SORT;
            sortable(index, block, e, instance);
        }
    }

    /**
     * Writes the mesh renderer of emitter {@code e} of {@code block} (at {@code index} of the list, instance
     * {@code instance}) into its record at {@code at}: its range of the scene's instances, its mesh as registered and
     * its first material; and lists it for the instance pass.
     */
    private void putMesh(Staging t, int at, ModulePrograms.Block block, int e, int index, int instance) {
        int r = block.meshRenderers[e];
        int first = meshFirsts[instance];
        t.putInt(at + ParticleEmitterLayout.MESH_FIRST, first < 0 ? ParticleConstants.NO_INSTANCES : meshBase + first);
        t.putInt(at + ParticleEmitterLayout.MESH, 0);
        if (r < 0 || first < 0) {
            return;
        }
        Renderer renderer = block.data.emitters().get(e).renderers().get(r);
        long[] mesh = renderer.settings().get("mesh") instanceof AssetRef<?> asset ? meshAssets.get(asset) : null;
        if (mesh != null) {
            t.putInt(at + ParticleEmitterLayout.MESH, (int) mesh[0] | com.truesim.engine.render.layout.CommonConstants
                .INSTANCE_LIVE | (renderer.castsShadows()
                ? com.truesim.engine.render.layout.CommonConstants.INSTANCE_CASTS_SHADOWS : 0));
            t.putInt(at + ParticleEmitterLayout.MATERIAL, (int) mesh[1]);
        }
        t.putInt(at + ParticleEmitterLayout.MESH_RENDERER, block.renderers[e][r]);
        meshStaged.putInt(4L * meshCount++, index);
        largestMesh = Math.max(largestMesh, capacities[instance]);
    }

    /**
     * Lists emitter {@code e} of {@code block} (at {@code index} of the list, instance {@code instance}) for the
     * particle lights if it has a light renderer: its budget ({@code max}, 64 by default), the share of its particles
     * lit ({@code fraction}, all by default), their radius and intensity, and the radius per metre of a particle's size
     * ({@code radius.size}: when over 0, a light reaches {@code radius} times its particle's size times it, so a
     * growing flash reaches farther). The frame's budget goes to the candidates reaching farthest: their brightness
     * times their radius.
     */
    private void putLight(ModulePrograms.Block block, int e, int index, int instance) {
        if (lights == null || lightCount == ParticleConstants.MAX_LIGHT_EMITTERS) {
            return;
        }
        List<Renderer> renderers = block.data.emitters().get(e).renderers();
        for (int r = 0; r < renderers.size(); r++) {
            Renderer renderer = renderers.get(r);
            if (renderer.kind() == Renderer.Kind.LIGHT) {
                long at = 4L * ParticleConstants.LIGHT_ENTRY_WORDS * lightCount++;
                lightStaged.putInt(at, index);
                lightStaged.putInt(at + 4, Math.max(0, (int) ModulePrograms.setting(renderer, "max", 64)));
                lightStaged.putFloat(at + 8, ModulePrograms.setting(renderer, "fraction", 1));
                lightStaged.putFloat(at + 12, ModulePrograms.setting(renderer, "radius", 1));
                lightStaged.putFloat(at + 16, ModulePrograms.setting(renderer, "intensity", 1));
                lightStaged.putFloat(at + 20, Math.max(0, ModulePrograms.setting(renderer, "radius.size", 0)));
                largestLight = Math.max(largestLight, capacities[instance]);
                return;
            }
        }
    }

    /** Whether {@code emitter} draws meshes. */
    private static boolean meshRendered(EmitterData emitter) {
        List<Renderer> renderers = emitter.renderers();
        for (int r = 0; r < renderers.size(); r++) {
            if (renderers.get(r).kind() == Renderer.Kind.MESH) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes instance {@code instance}'s source for the frame: its mesh with its alias tables and its joints, in the
     * frame of its emitter at ({@code x}, {@code y}, {@code z}) turned by ({@code qx}, ..., {@code qw}); returns its
     * entry, or {@code NO_SOURCE} with neither.
     */
    private int putSource(int instance, double x, double y, double z, float qx, float qy, float qz, float qw) {
        int mesh = sourceMesh[instance];
        GeometryPool pool = meshRenderer == null ? null : meshRenderer.pool();
        boolean meshed = mesh >= 0 && pool != null && pool.contains(mesh);
        MeshTables.Table table = null;
        if (meshed) {
            int rest = pool.deformedFrom(mesh); // a posed copy draws from its source's tables
            table = meshTables.of(rest >= 0 ? rest : mesh, sourceData[instance]);
            meshed = table.triangles() > 0;
        }
        int joints = jointCounts[instance];
        if (!meshed && joints == 0) {
            return ParticleConstants.NO_SOURCE;
        }
        int index = sourceCount++;
        long at = (long) ParticleSourceLayout.BYTES * index;
        Staging r = sourceStaged;
        r.room(at + ParticleSourceLayout.BYTES);
        r.putInt(at + ParticleSourceLayout.MESH, meshed ? mesh : ParticleConstants.NO_MESH);
        r.putInt(at + ParticleSourceLayout.JOINTS, joints);
        if (meshed) {
            // The mesh's world transform into the emitter's frame: its rotation's inverse times (world - emitter)
            double[] w = sourceWorld[instance];
            for (int column = 0; column < 4; column++) {
                double cx = w[column];
                double cy = w[4 + column];
                double cz = w[8 + column];
                if (column == 3) {
                    cx -= x;
                    cy -= y;
                    cz -= z;
                }
                turnBack(cx, cy, cz, qx, qy, qz, qw);
                for (int row = 0; row < 3; row++) {
                    r.putFloat(at + ParticleSourceLayout.TO_EMITTER + 16L * row + 4L * column, (float) back[row]);
                }
            }
            r.putLong(at + ParticleSourceLayout.TABLE, table.buffer().deviceAddress());
            r.putInt(at + ParticleSourceLayout.TRIANGLES, table.triangles());
            r.putInt(at + ParticleSourceLayout.VERTICES, table.vertices());
        }
        double[] chain = jointWorld[instance];
        for (int j = 0; j < joints; j++) {
            turnBack(chain[3 * j] - x, chain[3 * j + 1] - y, chain[3 * j + 2] - z, qx, qy, qz, qw);
            r.putVector(at + ParticleSourceLayout.CHAIN + 16L * j, (float) back[0], (float) back[1],
                (float) back[2]);
        }
        return index;
    }

    private final double[] back = new double[3];

    /** ({@code x}, {@code y}, {@code z}) turned by the inverse of the rotation ({@code qx}, ..., {@code qw}). */
    private void turnBack(double x, double y, double z, double qx, double qy, double qz, double qw) {
        double cx = 2 * (-qy * z + qz * y);
        double cy = 2 * (-qz * x + qx * z);
        double cz = 2 * (-qx * y + qy * x);
        back[0] = x + qw * cx - qy * cz + qz * cy;
        back[1] = y + qw * cy - qz * cx + qx * cz;
        back[2] = z + qw * cz - qx * cy + qy * cx;
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
            heights = FrameBuffer.create(device, bytes, STORAGE, slots, Particles.queues(device));
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

    /**
     * Lets {@code collide.rays} modules trace their collision rays into the frame's top level of {@code renderer}'s
     * scene (E10, the rays kernel variant, ordered after the top level's build), where the device traces rays; on other
     * devices, and for effects encoded before this, chains fall back to their next method (depth, say). Set before
     * effects play; null: no ray collision.
     */
    public Particles rays(RayTracing rayTracing, MeshletRenderer renderer) {
        boolean traced = rayTracing != null && device.rayTracing();
        this.rayTracing = traced ? rayTracing : null;
        this.rayRenderer = traced ? Objects.requireNonNull(renderer, "renderer") : null;
        programs.rays(traced);
        setup++;
        return this;
    }

    /**
     * Lets effects name the modules of {@code modules} (E24: a game's generated listing, with the built-in modules and
     * its own; null: {@link BuiltInModules#MODULES}): effects encoded from now on, simulated by its kernel variants
     * (with ray collision where {@link #rays} allows it). Set before effects play.
     */
    public Particles modules(ParticleModules modules) {
        this.modules = modules == null ? BuiltInModules.MODULES : modules;
        programs.modules(this.modules);
        setup++;
        return this;
    }

    /** The kernel's modules. */
    ParticleModules modules() {
        return modules;
    }

    /** Whether the frame's simulation may run on the async compute queue ({@link ParticleSimulation#async}). */
    boolean asyncAllowed() {
        return meshRenderer == null && rayTracing == null;
    }

    /**
     * Lets mesh shapes emit from the meshes of {@code renderer}'s geometry pool (null: none): the simulation reads its
     * buffers, a skinned mesh's posed copy after the deformation pass. Set before effects play; what each emitter
     * emits from, its {@link #sources} name.
     */
    public Particles meshes(MeshletRenderer renderer) {
        meshRenderer = renderer;
        meshTables.follow(renderer == null ? null : renderer.pool());
        setup++;
        return this;
    }

    /**
     * {@link #meshes(MeshletRenderer)}, with mesh particles (E15): {@code instances} ids of {@code renderer}'s scene
     * reserved for the GPU to write ({@link GpuScene#reserveGpuRange}), the instances of mesh renderers' particles,
     * which the scene's culling, drawing and shadows take as any (ray tracing leaves them out). The simulation then
     * runs before the scene is culled, after its upload and deformation; emitters with mesh renderers take ranges of
     * their capacity from it (those finding no room simulate without drawing). 0: no mesh particles.
     */
    public Particles meshes(MeshletRenderer renderer, int instances) {
        if (instances < 0) {
            throw new IllegalArgumentException("Not an instance count: " + instances);
        }
        if (meshScene != null) {
            meshScene.notWrittenBy(ParticleKeys.PARTICLES);
            meshScene.releaseGpuRange();
            meshScene = null;
            meshIds = null;
        }
        meshes(renderer);
        if (renderer != null && instances > 0) {
            meshScene = renderer.scene();
            meshBase = meshScene.reserveGpuRange(instances);
            meshScene.writtenBy(ParticleKeys.PARTICLES);
            meshIds = new com.truesim.engine.render.RangeAllocator(instances);
            if (previousInstances != null) {
                retired.add(previousInstances);
            }
            previousInstances = GpuBuffer.deviceLocal(device, (long) com.truesim.engine.render.layout.InstanceLayout
                .BYTES * instances, GPU_USAGE, queues(device));
            previousImport = new ExternalBuffer("particle previous instances", previousInstances);
        }
        return this;
    }

    /** Mesh particles' transforms a frame before, per slot of their range ({@link ParticleKeys#PREVIOUS_INSTANCES}). */
    ExternalBuffer previousInstances() {
        return previousImport;
    }

    /**
     * Draws mesh renderers naming {@code mesh} (their {@code mesh} setting) with pool mesh {@code poolMesh} of the
     * {@link #meshes} renderer and its materials from {@code materialBase} on; until then their particles draw
     * nothing. The scene's resources say where they put them.
     */
    @AllowAllocation("a mesh registered")
    public void mesh(AssetRef<?> mesh, int poolMesh, int materialBase) {
        meshAssets.put(Objects.requireNonNull(mesh, "mesh"), new long[] {poolMesh, materialBase});
    }

    /** Forgets {@code mesh}'s pool mesh. */
    public void forgetMesh(AssetRef<?> mesh) {
        meshAssets.remove(mesh);
    }

    /** Notes ids {@code first} on, {@code count} of them, for the next frame to clear: their emitter is gone. */
    private void hole(int first, int count) {
        if (2 * holeCount + 2 > holes.length) {
            holes = grownHoles(holes);
        }
        holes[2 * holeCount] = first;
        holes[2 * holeCount + 1] = count;
        holeCount++;
    }

    @AllowAllocation("ranges given back growing")
    private static int[] grownHoles(int[] holes) {
        return Arrays.copyOf(holes, 2 * holes.length);
    }

    /**
     * Clears the slots of mesh ranges given back since the last frame recorded, in {@code buffer} (the scene's
     * instances): holes, before the instance pass writes the frame's; a barrier after.
     */
    void clearHoles(org.lwjgl.vulkan.VkCommandBuffer commands, long buffer) {
        if (holeCount == 0) {
            return;
        }
        for (int i = 0; i < holeCount; i++) {
            com.truesim.engine.vulkan.Commands.fillBuffer(commands, buffer, (long) GpuScene.INSTANCE_BYTES
                * holes[2 * i], (long) GpuScene.INSTANCE_BYTES * holes[2 * i + 1], 0);
        }
        holeCount = 0;
        com.truesim.engine.vulkan.Barriers.memory(commands, org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
            org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT | org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT);
    }

    /**
     * Lets light renderers write point lights into {@code lights}' lights the GPU writes ({@link
     * com.truesim.engine.render.Lights#gpuLights}), at most {@code budget} a frame for all emitters, the brightest kept
     * (and each emitter's brightest within its own {@code max}); {@code ClusteredLights} bins them after the scene's,
     * so both shading techniques, every view and lit particles see them. They cast no shadows; nothing is read back.
     * Null: none. Set before the graph builds.
     */
    public Particles lights(com.truesim.engine.render.Lights lights, int budget) {
        if (budget < 0) {
            throw new IllegalArgumentException("Not a light budget: " + budget);
        }
        if (this.lights != null) {
            this.lights.notWrittenBy(ParticleKeys.PARTICLES);
            this.lights.gpuLights(0);
        }
        this.lights = lights;
        lightBudget = lights == null ? 0 : budget;
        if (lights != null) {
            lights.gpuLights(budget);
            lights.writtenBy(ParticleKeys.PARTICLES);
        }
        setup++;
        return this;
    }

    /** The lights particle lights go into, or null. */
    com.truesim.engine.render.Lights lights() {
        return lights;
    }

    /** Emitters of the last update's list with light renderers (at most {@code MAX_LIGHT_EMITTERS}). */
    int lightEmitterCount() {
        return lightCount;
    }

    /** The largest capacity among them. */
    int largestLightEmitter() {
        return largestLight;
    }

    /** The light selection's buffer, as a graph imports it. */
    ExternalBuffer lightStateImport() {
        return lightStateImport;
    }

    /** Clears the light selection and the GPU's lights' count for the frame, in {@code commands}; a barrier after. */
    void clearLights(org.lwjgl.vulkan.VkCommandBuffer commands, long state, long written) {
        com.truesim.engine.vulkan.Commands.fillBuffer(commands, state, 0, lightState.size(), 0);
        com.truesim.engine.vulkan.Commands.fillBuffer(commands, written, 0, 16, 0);
        com.truesim.engine.vulkan.Barriers.memory(commands, org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
            org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }

    /** Whether mesh particles are set up: their instances written before culling. */
    boolean meshParticles() {
        return meshScene != null;
    }

    /** Emitters of the last update's list with mesh renderers. */
    int meshEmitterCount() {
        return meshCount;
    }

    /** The largest capacity among them. */
    int largestMeshEmitter() {
        return largestMesh;
    }

    /** Emitter instances with mesh renderers that found no room for their instances since this was made. */
    public int meshUnplaced() {
        return meshUnplaced;
    }

    /** The renderer whose meshes mesh shapes read, or null. */
    MeshletRenderer meshRenderer() {
        return meshRenderer;
    }

    /** Meshes with alias tables for mesh shapes. */
    public int meshTableCount() {
        return meshTables.size();
    }

    /** A number that changes with what the simulation reads ({@link #rays}, {@link #meshes}): its graph rebuilds. */
    long setup() {
        return setup;
    }

    /**
     * What emitters with mesh or bone shapes ({@code Shape.MESH}, {@code Shape.BONES}) emit from: asked in every
     * {@link #update}, for each such emitter of each playing effect, before its record is written. The engine-scene
     * sync names the mesh of the effect's node and the joints given it.
     */
    @FunctionalInterface
    public interface Sources {

        /**
         * Names what emitter {@code emitter} of playing effect {@code effect} emits from this frame, through
         * {@link #emitFrom} and {@link #joints}; what it named before stays otherwise. Allocates nothing.
         */
        void emit(long effect, int emitter);
    }

    /** Asks {@code sources} what mesh and bone shapes emit from, from the next update on (null: nobody). */
    public Particles sources(Sources sources) {
        this.sources = sources;
        return this;
    }

    /**
     * Emits the mesh shapes of emitter {@code emitter} of playing effect {@code effect} from pool mesh {@code mesh}
     * of the {@link #meshes} renderer, a skinned renderable's posed copy as drawn (-1: from nothing, its particles
     * born dead), whose data (a copy's source's) is {@code data}, its alias tables built from it once. {@code world}
     * places it: rows of the 3 x 4 matrix of its node's world transform, scale included, as of the frame. Kept until
     * set again or the effect ends. Allocates nothing once set.
     */
    public void emitFrom(long effect, int emitter, int mesh, MeshData data, double[] world) {
        int instance = sourced(effect, emitter);
        if (mesh >= 0) {
            Objects.requireNonNull(data, "data");
            if (sourceWorld[instance] == null) {
                sourceWorld[instance] = scratch(12);
            }
            System.arraycopy(world, 0, sourceWorld[instance], 0, 12);
        }
        sourceMesh[instance] = mesh;
        sourceData[instance] = mesh >= 0 ? data : null;
    }

    /**
     * Runs the bone shapes of emitter {@code emitter} of playing effect {@code effect} along {@code count} joints
     * (0 to {@code MAX_JOINTS}) at world positions {@code xyz} (x, y, z each), as of the frame. Kept until set again or
     * the effect ends. Allocates nothing once set.
     */
    public void joints(long effect, int emitter, double[] xyz, int count) {
        int instance = sourced(effect, emitter);
        if (count < 0 || count > ParticleConstants.MAX_JOINTS) {
            throw new IllegalArgumentException("Not a joint count: " + count);
        }
        if (jointWorld[instance] == null) {
            jointWorld[instance] = scratch(3 * ParticleConstants.MAX_JOINTS);
        }
        System.arraycopy(xyz, 0, jointWorld[instance], 0, 3 * count);
        jointCounts[instance] = count;
    }

    /** The GPU instance of emitter {@code emitter} of playing effect {@code effect}, which must have one. */
    private int sourced(long effect, int emitter) {
        int instance = instance(effect, emitter);
        if (instance < 0 || cpu[instance]) {
            throw new IllegalArgumentException("Not a GPU emitter with room of a playing effect: " + emitter + " of "
                + Long.toHexString(effect));
        }
        return instance;
    }

    @AllowAllocation("an emitter's source, set up once")
    private static double[] scratch(int length) {
        return new double[length];
    }

    /** The ray tracing collision rays go into, or null. */
    RayTracing rayTracing() {
        return rayTracing;
    }

    /** The renderer whose scene's top level they trace. */
    MeshletRenderer rayRenderer() {
        return rayRenderer;
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
        return FrameBuffer.create(device, Math.max(bytes, 2 * old.size()), STORAGE, slots, Particles.queues(device));
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
        parentRibbonCount = 0;
        parentRibbonChunks = 0;
        for (int i = 0; i < emitterCount; i++) {
            if (cpu[listInstance[i]]) {
                continue;
            }
            int strands = 1;
            boolean parents = false;
            List<Renderer> renderers = blocks[listSlot[i]].data.emitters().get(listEmitter[i]).renderers();
            for (int r = 0; r < renderers.size(); r++) {
                int n = ModulePrograms.ribbons(renderers.get(r));
                parents |= n == 0;
                strands = Math.max(strands, n);
            }
            if (parents && parentRibbonCount < MAX_PARENT_RIBBONS) {
                // A ribbon per parent: ordered by the radix sort of (place, parent) keys
                int place = parentRibbonCount++;
                parentRibbonsSeen = true;
                parentRibbonStaged.putInt(4L * place, i);
                int chunks = ParticlePool.chunksOf(capacities[listInstance[i]]);
                parentChunkStaged.room(8L * (parentRibbonChunks + chunks));
                for (int c = 0; c < chunks; c++) {
                    parentChunkStaged.putInt(8L * parentRibbonChunks, place);
                    parentChunkStaged.putInt(8L * parentRibbonChunks + 4, c);
                    parentRibbonChunks++;
                }
            } else if (strands > 1) {
                ribbonStaged.putInt(8L * ribbonCount, i);
                ribbonStaged.putInt(8L * ribbonCount + 4, strands);
                ribbonCount++;
            }
        }
    }

    /**
     * Keys a view's sort has room for: the pool's slots, or as many as the frame's sorted records may hold if more
     * (placements draw their variants' particles again), grown by half again; a change rebuilds the draws' graph.
     */
    int sortSlots() {
        return sortSlots;
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

    /** Emitters whose ribbons go a ribbon per parent, ordered by the radix sort (at most 64). */
    int parentRibbonCount() {
        return parentRibbonCount;
    }

    /** Chunks of their ranges. */
    int parentRibbonChunks() {
        return parentRibbonChunks;
    }

    /** Whether any emitter drew a ribbon per parent since this was made: the simulation sorts their keys from then. */
    boolean parentRibbons() {
        return parentRibbonsSeen;
    }

    long parentRibbonListAddress() {
        return parentRibbonList.deviceAddress();
    }

    long parentRibbonChunksAddress() {
        return parentRibbonChunkTable.deviceAddress();
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

    /** The capacity of the emitter at {@code index} of the frame's list. */
    int capacityOf(int index) {
        return capacities[listInstance[index]];
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
        Buffer dispatch, Buffer order, Buffer depth, Buffer events, Buffer eventKeys, Buffer eventKeysOther,
        Buffer eventArgs, Buffer eventCounts, Buffer childPrefix) {
    }

    @AllowAllocation("building the graph, when it changes")
    Buffers importInto(GraphBuilder g) {
        return new Buffers(g.importBuffer(pool.graphBuffer()), g.importBuffer(pool.sideGraphBuffer()),
            g.importBuffer(stateImport), g.importBuffer(chunkCountsImport), g.importBuffer(statisticsImport),
            g.importBuffer(dispatchImport), g.importBuffer(orderImport), g.importBuffer(depthImport),
            g.importBuffer(eventsImport), g.importBuffer(eventKeysImport), g.importBuffer(eventKeysOtherImport),
            g.importBuffer(eventArgsImport), g.importBuffer(eventCountsImport), g.importBuffer(childPrefixImport));
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
        eventsTotal = Integer.toUnsignedLong(data.getAtIndex(ValueLayout.JAVA_INT,
            ParticleConstants.STATISTICS_EVENTS));
        eventsDroppedTotal = Integer.toUnsignedLong(data.getAtIndex(ValueLayout.JAVA_INT,
            ParticleConstants.STATISTICS_EVENTS_DROPPED));
        if (eventsTotal > ParticleConstants.EVENT_SMALL / 2) {
            eventsBusy = Math.max(eventsBusy, frame);
        }
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

    /**
     * Lit renderers draw at {@code quality} from now on ({@code effects.quality}): below high, per-pixel lighting is
     * drawn per vertex; at low, six-way maps too. As authored at high and epic, the default.
     */
    public Particles quality(com.truesim.scene.effects.Quality quality) {
        this.quality = Objects.requireNonNull(quality, "quality");
        return this;
    }

    /** The quality lit renderers draw at. */
    public com.truesim.scene.effects.Quality quality() {
        return quality;
    }

    private com.truesim.scene.effects.Quality quality = com.truesim.scene.effects.Quality.HIGH;

    /** Its renderer {@code r}'s lighting ({@code LIGHTING_*}) as drawn: billboards' as authored, lowered by quality. */
    int rendererLighting(int index, int r) {
        Renderer renderer = blocks[listSlot[index]].data.emitters().get(listEmitter[index]).renderers().get(r);
        return lighting(renderer.kind(), renderer.lighting(), quality);
    }

    /** {@code lighting} of a renderer of {@code kind} drawn at {@code quality}: only billboards are lit. */
    static int lighting(Renderer.Kind kind, Renderer.Lighting lighting, com.truesim.scene.effects.Quality quality) {
        if (kind != Renderer.Kind.BILLBOARD) {
            return ParticleConstants.LIGHTING_UNLIT;
        }
        boolean high = quality.ordinal() >= com.truesim.scene.effects.Quality.HIGH.ordinal();
        boolean low = quality == com.truesim.scene.effects.Quality.LOW;
        return switch (lighting) {
            case UNLIT -> ParticleConstants.LIGHTING_UNLIT;
            case PER_VERTEX -> ParticleConstants.LIGHTING_VERTEX;
            case PER_PIXEL -> high ? ParticleConstants.LIGHTING_PIXEL : ParticleConstants.LIGHTING_VERTEX;
            case SIX_WAY -> low ? ParticleConstants.LIGHTING_VERTEX : ParticleConstants.LIGHTING_SIX_WAY;
        };
    }

    /** Its renderer {@code r}'s second texture's slot: a normal map, six-way maps' second; or {@code NO_TEXTURE}. */
    int rendererExtra(int index, int r) {
        return textureSlot(blocks[listSlot[index]].extras[listEmitter[index]][r]);
    }

    /**
     * Its renderer {@code r}'s {@code DRAWN_*} bits as drawn: half resolution for every billboard at low quality
     * ({@code effects.quality}), as authored otherwise.
     */
    int rendererDrawn(int index, int r) {
        int drawn = blocks[listSlot[index]].drawn[listEmitter[index]][r];
        return quality == com.truesim.scene.effects.Quality.LOW && rendererKind(index, r)
            == ParticleConstants.RENDER_BILLBOARD ? drawn | ModulePrograms.DRAWN_HALF : drawn;
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
    Pipeline pipeline(int kind, int format) {
        return pipeline(kind, format, DRAW_COLOR);
    }

    /** Pipelines' fragment shaders: particles' color, the reactive mask's coverage, an overdraw heat map. */
    static final int DRAW_COLOR = 0;
    static final int DRAW_REACTIVE = 1;
    static final int DRAW_OVERDRAW = 2;

    /**
     * The pipeline drawing renderers of {@code kind} into {@code format}: premultiplied ({@code DRAW_COLOR}), or added:
     * their coverage for the reactive mask, a constant per layer for the overdraw heat map.
     */
    @AllowAllocation("a pipeline made once per kind, color format and mode")
    Pipeline pipeline(int kind, int format, int mode) {
        int key = (format * 8 + kind) * 4 + mode;
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
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shaders, mode == DRAW_REACTIVE ? "reactiveMain"
                        : mode == DRAW_OVERDRAW ? "overdrawMain" : "fragmentMain")
                    .color(format).blend(0, mode == DRAW_COLOR ? Pipeline.Blend.PREMULTIPLIED : Pipeline.Blend.ADDITIVE).pushConstants(ParticleDrawPush.BYTES).doubleSided()
                    .build();
            }
            pipelines.put(key, pipeline);
        }
        return pipeline;
    }

    /** The pipeline laying half-resolution particles over a view of {@code format} (particles/upsample.slang). */
    @AllowAllocation("a pipeline made once per color format")
    Pipeline upsamplePipeline(int format) {
        int key = -1 - format;
        Pipeline pipeline = pipelines.get(key);
        if (pipeline == null) {
            try (ShaderModule shaders = ShaderModule.load(device, "shaders/particles/upsample.spv")) {
                pipeline = Pipeline.graphics(device).stage(VK_SHADER_STAGE_VERTEX_BIT, shaders, "upsampleVertexMain")
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shaders, "upsampleMain").color(format)
                    .blend(0, Pipeline.Blend.PREMULTIPLIED)
                    .pushConstants(com.truesim.engine.render.layout.ParticleUpsamplePush.BYTES).doubleSided().build();
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

    /** Events sent in that frame. */
    public long events() {
        return eventsTotal;
    }

    /**
     * Of them, dropped for want of room in the frame's event buffer. The event radix sort's passes are recorded only
     * while recent frames sent over half of {@code EVENT_SMALL} events (the statistics come a few frames late), so a
     * sudden burst past {@code EVENT_SMALL} after quiet frames keeps {@code EVENT_SMALL} and drops the rest for those
     * few frames.
     */
    public long eventsDropped() {
        return eventsDroppedTotal;
    }

    /** Whether the frame's emitters send events: the event passes run. */
    boolean sendsEvents() {
        return sendsEvents;
    }

    /** Whether the frame records the event radix sort's passes (else the small sort's alone sorts its events). */
    boolean radixEvents() {
        return radixEvents;
    }

    /** Forces an event sort for tests: {@link #EVENTS_EITHER} (as the counts say), {@link #EVENTS_SMALL}, or radix. */
    void eventSort(int sort) {
        eventSort = sort;
    }

    long eventKeysAddress() {
        return eventKeys.deviceAddress();
    }

    long eventKeysOtherAddress() {
        return eventKeysOther.deviceAddress();
    }

    long eventArgsAddress() {
        return eventArgs.deviceAddress();
    }

    long eventCountsAddress() {
        return eventCounts.deviceAddress();
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
        parentRibbonList.close();
        parentRibbonChunkTable.close();
        sortList.close();
        sortChunks.close();
        frameTable.close();
        colliderTable.close();
        if (heights != null) {
            heights.close();
        }
        depth.close();
        events.close();
        eventKeys.close();
        eventKeysOther.close();
        eventArgs.close();
        eventCounts.close();
        childPrefix.close();
        targetTable.close();
        sourceTable.close();
        meshList.close();
        lightList.close();
        lightState.close();
        if (previousInstances != null) {
            previousInstances.close();
        }
        if (lights != null) {
            lights.notWrittenBy(ParticleKeys.PARTICLES);
        }
        if (meshScene != null) {
            meshScene.notWrittenBy(ParticleKeys.PARTICLES);
        }
        meshTables.close();
        programs.close();
        cpuEmitters.close();
        pool.close();
        retired.close();
    }
}
