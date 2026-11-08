package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.EXTMeshShader.VK_PIPELINE_STAGE_MESH_SHADER_BIT_EXT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_INDEX_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_INDEX_INPUT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalBuffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.render.layout.BlendedSortPush;
import com.truesim.engine.render.layout.CommonConstants;
import com.truesim.engine.render.layout.CullPush;
import com.truesim.engine.render.layout.ExpandPush;
import com.truesim.engine.render.layout.InstancePush;
import com.truesim.engine.render.layout.SortPush;
import com.truesim.engine.render.layout.SplitPush;
import com.truesim.engine.render.layout.ViewLayout;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.Bindless;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * A {@link MeshletRenderer}'s culling: the instance pass splitting each instance visible to a view into chunks of 64
 * meshlets, the cull pass listing each chunk's visible meshlets in its view's lists and, on the indexed path, each
 * view's expansion of its lists into indices. The chunks are shared by the views culled together and grow between
 * frames to what frames wanted.
 *
 * <p>A cull that wants more chunks than there is room for loses nothing: it runs in rounds (instances.slang), each
 * round's instance pass filling the chunks and its cull pass emptying them into the views' lists, up to
 * {@link MeshletSettings#CULL_ROUNDS} rounds; a round nothing needed costs an indirect dispatch over no workgroups and
 * two barriers. Once frames fit with room to spare (half again what they wanted) and neither the instances nor the
 * views culled grew, culls run one round. What is dropped all the same (the last round full, or the room at its
 * limit, {@link MeshletSettings#CHUNK_LIMIT}) is counted by the GPU and reported once in the log; the counts are read
 * by {@link #droppedInstances()} and the renderer's profiler counters. Culling allocates nothing.
 */
@AllowAllocation("created rarely")
final class CullPasses implements AutoCloseable {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CullPasses.class);
    /** The most chunks the buffer can grow to: 16 bytes each. */
    static final int CHUNK_LIMIT = 1 << 26;
    /** The most rounds a cull runs. */
    static final int MAX_ROUNDS = 64;
    /** Words of the statistics before the rounds' commands (instances.slang). */
    private static final int STATS_WORDS = 8;
    /** Words of a round's command (instances.slang). */
    private static final int ROUND_WORDS = 8;
    /** VK_PIPELINE_STAGE_2_MESH_SHADER_BIT_EXT: the same bit as the 32-bit stage. */
    private static final long MESH_SHADER_STAGE = VK_PIPELINE_STAGE_MESH_SHADER_BIT_EXT;
    private static final int BINS = MeshletRenderer.BINS;
    private static final int PHASE_ALL = CommonConstants.PHASE_ALL;
    private static final int PHASE_EARLY = CommonConstants.PHASE_EARLY;
    private static final int PHASE_LATE = CommonConstants.PHASE_LATE;
    /** Rows of one instance dispatch: the guaranteed workgroup count. */
    private static final int MAX_ROWS = 65_535;
    /** Workgroups per raster bin counting and scattering a split list, each looping over it. */
    private static final int SPLIT_GROUPS = 256;
    /** Rows one buffer update writes: 8 bytes each, a few KiB of the stack. */
    private static final int ROWS_PER_UPDATE = 512;
    // The shaders' Occlusion struct, the same in the instance and cull pushes: its fields from its start.
    private static final int OCCLUSION_WIDTH = InstancePush.OCCLUSION_DEPTH_WIDTH - InstancePush.OCCLUSION;
    private static final int OCCLUSION_HEIGHT = InstancePush.OCCLUSION_DEPTH_HEIGHT - InstancePush.OCCLUSION;
    private static final int OCCLUSION_MIPS = InstancePush.OCCLUSION_MIPS - InstancePush.OCCLUSION;

    private final VulkanDevice device;
    private final GpuScene scene;
    private final GeometryPool pool;
    private final Bindless bindless;
    private final FrameSlots slots;
    private final RenderViews views;
    private final Retiring retiring;
    private final Runnable grow = this::grow;
    private final Pipeline instancesPass;
    private final Pipeline cull;
    private final Pipeline expand;
    private final Pipeline expandTyped; // a split view's, in the split list's order
    // Forward shading by type: the lists of views split by shading bin (split.slang), counted, laid out, scattered.
    private final Pipeline splitCount;
    private final Pipeline splitLayout;
    private final Pipeline splitScatter;
    private final Pipeline[] splitStages; // the three in order, never written
    // Sorting a view's split bin by distance (meshlets/sort.slang), with the core's radix sort (count, scan,
    // scatter: blocks/sort.slang, RadixSort).
    private final Pipeline sortArgs;
    private final Pipeline sortKeys;
    private final Pipeline sortBlocks;
    private final Pipeline sortScan;
    private final Pipeline sortPlace;
    private final Pipeline sortEntries;
    private final Pipeline radixCount;
    private final Pipeline radixScan;
    private final Pipeline radixScatter;
    private int maxChunks;
    private GpuBuffer chunks; // grown as frames want more (grow)
    // The statistics, then a command per round (instances.slang): host-visible, read as frames end.
    private final GpuBuffer chunkCommand;
    private GpuBuffer retry; // a bit per instance pass workgroup of a cull: to run again in the next round
    private long retryBits;
    private volatile boolean retryShort; // a cull had more workgroups than bits: grown as a frame ends
    // The culling's buffers as imported buffers of the frame graph, which the culling passes declare.
    private final ExternalBuffer chunksImport;
    private final ExternalBuffer chunkCommandImport;
    private final ExternalBuffer retryImport;
    // The settings (MeshletSettings), as the last graph built with them.
    private volatile int chunkLimit = MeshletSettings.CHUNK_LIMIT.defaultValue();
    private volatile int rounds = MeshletSettings.CULL_ROUNDS.defaultValue();
    // Whether culls take one round: frames fitted with room to spare for slots + 1 frame ends, and no cull since had
    // more instances or views than they did. Culls note the most they had (read before written: several threads).
    private volatile boolean settled;
    private volatile int seenInstances;
    private volatile int seenRows;
    private int settledInstances = -1;
    private int settledRows = -1;
    private int quietEnds;
    private volatile int roundsSeen; // the most rounds a cull ran since culls last settled, as read at frame ends
    // What the GPU counted, as last read: instances and chunks dropped, and the reports made.
    private long droppedInstances;
    private long droppedChunks;
    private int lastDropped;
    private boolean droppedLogged;
    private boolean limitLogged;
    private boolean closed;

    /** Culling of {@code scene} into {@code views}, with room for {@code chunks} chunks to start with. */
    CullPasses(VulkanDevice device, GpuScene scene, FrameSlots slots, RenderViews views, Retiring retiring,
        int chunks) {
        this.device = device;
        this.scene = scene;
        this.pool = scene.pool();
        this.bindless = device.bindless();
        this.slots = slots;
        this.views = views;
        this.retiring = retiring;
        this.maxChunks = chunks;
        try (ShaderModule instanceShader = ShaderModule.load(device, "shaders/meshlets/instances.spv");
             ShaderModule cullShader = ShaderModule.load(device, "shaders/meshlets/cull.spv");
             ShaderModule expandShader = ShaderModule.load(device, "shaders/meshlets/expand.spv");
             ShaderModule splitShader = ShaderModule.load(device, "shaders/meshlets/split.spv")) {
            instancesPass = Pipeline.compute(device, instanceShader, "instancesMain", InstancePush.BYTES);
            cull = Pipeline.compute(device, cullShader, "cullMain", CullPush.BYTES);
            expand = Pipeline.compute(device, expandShader, "expandMain", ExpandPush.BYTES);
            expandTyped = Pipeline.compute(device, expandShader, "expandTypedMain", ExpandPush.BYTES);
            splitCount = Pipeline.compute(device, splitShader, "splitCountMain", SplitPush.BYTES);
            splitLayout = Pipeline.compute(device, splitShader, "splitLayoutMain", SplitPush.BYTES);
            splitScatter = Pipeline.compute(device, splitShader, "splitScatterMain", SplitPush.BYTES);
        }
        try (ShaderModule sortShader = ShaderModule.load(device, "shaders/meshlets/sort.spv");
             ShaderModule radixShader = ShaderModule.load(device, RadixSort.SHADER)) {
            sortArgs = Pipeline.compute(device, sortShader, "sortArgsMain", BlendedSortPush.BYTES);
            sortKeys = Pipeline.compute(device, sortShader, "sortKeysMain", BlendedSortPush.BYTES);
            sortBlocks = Pipeline.compute(device, sortShader, "sortBlocksMain", BlendedSortPush.BYTES);
            sortScan = Pipeline.compute(device, sortShader, "sortScanMain", BlendedSortPush.BYTES);
            sortPlace = Pipeline.compute(device, sortShader, "sortPlaceMain", BlendedSortPush.BYTES);
            sortEntries = Pipeline.compute(device, sortShader, "sortEntriesMain", BlendedSortPush.BYTES);
            radixCount = Pipeline.compute(device, radixShader, "sortCountMain", SortPush.BYTES);
            radixScan = Pipeline.compute(device, radixShader, "sortScanMain", SortPush.BYTES);
            radixScatter = Pipeline.compute(device, radixShader, "sortScatterMain", SortPush.BYTES);
        }
        splitStages = new Pipeline[] {splitCount, splitLayout, splitScatter};
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        this.chunks = GpuBuffer.deviceLocal(device, 16L * chunks, storage);
        // The statistics (the most chunks a cull wanted, what was dropped: never reset by the GPU, read by the CPU as
        // frames end), then each round's command: its cull dispatch, chunks wanted and written, the next round's.
        chunkCommand = GpuBuffer.hostVisible(device, 4L * (STATS_WORDS + ROUND_WORDS * MAX_ROUNDS),
            storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        chunkCommand.memory().fill((byte) 0); // read before the first cull: nothing wanted yet (pitfalls.md)
        retryBits = retryBitsFor(4, scene.capacity());
        retry = GpuBuffer.deviceLocal(device, retryBits / 8, storage | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        chunksImport = new ExternalBuffer("meshlet chunks", this.chunks);
        chunkCommandImport = new ExternalBuffer("chunk command", chunkCommand);
        retryImport = new ExternalBuffer("cull retry bits", retry);
        slots.onEnd(grow);
    }

    /** Bits for the instance pass workgroups of a cull of {@code rows} views over {@code instances}: whole words. */
    private static long retryBits(long rows, long instances) {
        return (rows * ((instances + 63) / 64) + 31) / 32 * 32;
    }

    private static long retryBitsFor(long rows, long instances) {
        return Math.max(32, retryBits(rows, instances));
    }

    /** Statistic {@code word} (instances.slang). */
    private int stat(int word) {
        return chunkCommand.memory().get(java.lang.foreign.ValueLayout.JAVA_INT, 4L * word);
    }

    /** Where round {@code round}'s command starts in the chunk command buffer, in bytes. */
    private static long roundOffset(int round) {
        return 4L * (STATS_WORDS + ROUND_WORDS * round);
    }

    /** The most chunks of 64 meshlets a frame wanted so far: what the views culled together needed. */
    int wantedChunks() {
        return stat(0);
    }

    /**
     * Instances (an instance in a view each) culls left out for want of room, since the renderer was made: the last
     * round full, or the room at its limit. Counted by the GPU, read as frames end.
     */
    long droppedInstances() {
        return droppedInstances;
    }

    /** Chunks of 64 meshlets left out, as {@link #droppedInstances()}. */
    long droppedChunks() {
        return droppedChunks;
    }

    /** Whether a frame wanted more chunks than the limit allows ({@link MeshletSettings#CHUNK_LIMIT}). */
    boolean limitReached() {
        int wanted = wantedChunks();
        return wanted < 0 || wanted > chunkLimit;
    }

    /** The most rounds a cull ran since the culls settled last. */
    int roundsUsed() {
        return Math.max(Math.max(1, roundsSeen), stat(2));
    }

    /** Whether culls run in one round now. */
    boolean settled() {
        return settled;
    }

    /**
     * Takes the settings of a graph's build: the most chunks the room grows to ({@link MeshletSettings#CHUNK_LIMIT},
     * at least the room now) and the most rounds a cull runs ({@link MeshletSettings#CULL_ROUNDS}).
     */
    void configure(int limit, int rounds) {
        this.chunkLimit = Math.max(Math.min(limit, CHUNK_LIMIT), 1);
        this.rounds = Math.max(1, Math.min(rounds, MAX_ROUNDS));
    }

    /** How many chunks there is room for now. */
    int capacity() {
        return maxChunks;
    }

    /**
     * As a frame ends: room for the chunks frames wanted, half again for growth, when a frame wanted more than there
     * was. The old buffer goes once no frame reads it; a graph rebuilds at its next frame for the new size.
     */
    @AllowAllocation("growing, rarely")
    private void grow() {
        if (closed) {
            return;
        }
        int wanted = observe();
        if (retryShort) { // a cull had more workgroups than bits (views or instances grew outside a build)
            retryShort = false;
            sizeRetry(views.rows().size() / 8, scene.capacity());
        }
        if (wanted > maxChunks && maxChunks < chunkLimit) {
            int grown = (int) Math.min(chunkLimit, Math.max(2L * maxChunks, wanted + wanted / 2L));
            retiring.add(chunks);
            chunks = GpuBuffer.deviceLocal(device, 16L * grown,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
            chunksImport.set(chunks); // a new size: a graph rebuilds
            maxChunks = grown;
        }
        if (wanted > chunkLimit) {
            if (!limitLogged) {
                limitLogged = true;
                LOG.warn("Meshlet culling wants {} chunks of 64 meshlets, more than {} (render.meshlets.chunkLimit): "
                    + "culls run in rounds, up to render.meshlets.cullRounds", wanted, chunkLimit);
            }
        }
    }

    /**
     * Reads what the GPU counted: drops reported, culls settled or not; returns the most chunks a cull wanted. As frames
     * end, and on a single frame slot (which nothing may end) as each cull records.
     */
    private synchronized int observe() {
        int wanted = wantedChunks(); // unsigned in the GPU's word: past 2^31 read as the most
        if (wanted < 0) {
            wanted = Integer.MAX_VALUE;
        }
        settle(wanted, report());
        return wanted;
    }

    /**
     * Takes in what the GPU dropped since the last frame end, and reports the first drop in the log; returns whether
     * anything was dropped.
     */
    private boolean report() {
        int dropped = stat(1);
        if (dropped == lastDropped) {
            return false;
        }
        droppedInstances += Integer.toUnsignedLong(dropped - lastDropped);
        lastDropped = dropped;
        droppedChunks = Integer.toUnsignedLong(stat(3));
        if (!droppedLogged) {
            droppedLogged = true;
            LOG.warn("Meshlet culling left {} instances out for want of room ({} chunks of 64 meshlets wanted, room "
                    + "for {} in {} rounds; render.meshlets.chunkLimit {}, render.meshlets.cullRounds {}): the room "
                    + "grows, and later drops are counted only (MeshletRenderer.droppedInstances)", droppedInstances,
                wantedChunks(), maxChunks, rounds, chunkLimit, rounds);
        }
        return true;
    }

    /**
     * Whether culls fitted since the last frame end (one round, nothing dropped, room for half again what they wanted,
     * no more instances or views); culls settle into one round after the frame slots' count of such frame ends and
     * run in rounds again at once otherwise.
     */
    private void settle(int wanted, boolean dropped) {
        int instances = seenInstances;
        int rows = seenRows;
        int used = stat(2); // since the last frame end, as the GPU finished them
        if (used != 0) {
            chunkCommand.memory().set(java.lang.foreign.ValueLayout.JAVA_INT, 8, 0);
            roundsSeen = Math.max(roundsSeen, used);
        }
        boolean calm = instances <= settledInstances && rows <= settledRows && used <= 1
            && wanted <= maxChunks - maxChunks / 3 && !dropped;
        if (calm) {
            if (!settled && ++quietEnds > slots.count()) {
                settled = true;
                roundsSeen = 0;
            }
        } else {
            settled = false;
            quietEnds = 0;
            settledInstances = instances;
            settledRows = rows;
        }
    }

    /** Room for the retry bits of a cull of {@code rows} views over {@code instances}, kept once no frame reads it. */
    @AllowAllocation("growing, rarely")
    private void sizeRetry(long rows, long instances) {
        long bits = retryBitsFor(rows, instances);
        if (bits <= retryBits) {
            return;
        }
        retiring.add(retry);
        retryBits = Math.max(bits, 2 * retryBits);
        retry = GpuBuffer.deviceLocal(device, retryBits / 8,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        retryImport.set(retry); // a new size: a graph rebuilds
    }

    /**
     * Declares that {@code pass} culls: it writes the chunks, rows and retry bits (transfers, then compute) and reads
     * the chunk command it writes for its indirect dispatches. Sizes the retry bits for the views and instances there
     * may be.
     */
    @AllowAllocation("building the graph")
    void declare(GraphBuilder g, GraphBuilder.PassBuilder pass) {
        sizeRetry(views.rows().size() / 8, scene.capacity());
        Buffer command = g.importBuffer(chunkCommandImport);
        Buffer culledRows = g.importBuffer(views.rowsImport());
        Buffer retried = g.importBuffer(retryImport);
        pass.write(retried, BufferAccess.TRANSFER_DST)
            .write(retried, BufferAccess.STORAGE_WRITE)
            .write(g.importBuffer(chunksImport), BufferAccess.STORAGE_WRITE)
            .write(command, BufferAccess.TRANSFER_DST)
            .write(command, BufferAccess.STORAGE_WRITE)
            .read(command, BufferAccess.INDIRECT)
            .write(culledRows, BufferAccess.TRANSFER_DST)
            .read(culledRows, BufferAccess.STORAGE_READ);
    }

    /** View {@code i}'s phase in a cull: late without {@code early}, else early or all. */
    private static int phase(boolean[] early, int i) {
        return early == null ? PHASE_LATE : early[i] ? PHASE_EARLY : PHASE_ALL;
    }

    /**
     * Culls {@code views[0]} to {@code views[count - 1]}, each in its phase ({@link #phase}; without {@code early} the
     * late one, against {@code pyramid}): resets their lists, then the instance pass (a row per view), the cull pass
     * over all their chunks and, unless {@code meshShading}, each view's expansion. With {@code declared} a graph
     * orders what the build declared: no barriers of its own for those.
     */
    void cull(VkCommandBuffer commands, RenderView[] views, boolean[] early, int count, int pyramid, int width,
        int height, int mips, boolean declared, boolean meshShading) {
        retiring.release(); // culls of one frame may run on several threads: it locks
        scene.upload(commands);
        for (int v = 0; v < count; v++) {
            views[v].refresh(phase(early, v) != PHASE_LATE); // from where their cameras are now
        }
        // Declared to a graph that orders everything it touches: the indexed path's index buffers too, where the build
        // declared them and the views have them (else, e.g. the path changed since, its own barriers).
        boolean ordered = declared && (meshShading || indicesOrdered(views, count));
        int instanceCount = scene.highWater();
        bindless.bind(commands); // the late phase samples the depth pyramid through it
        // What came before (the early phase, the frame before) used the buffers this resets, and the visibility.
        if (!ordered) {
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        }
        // Rounds: one once culls settled (frames fitted with room to spare), else as many as the settings allow, for
        // what does not fit in a round to be culled in the next; noted, as the most instances and views a cull had.
        if (instanceCount > seenInstances) {
            seenInstances = instanceCount;
        }
        if (count > seenRows) {
            seenRows = count;
        }
        if (slots.isSingle()) {
            observe(); // no frame ends to settle at: culls on a single frame slot settle as they record
        }
        int groupsPerRow = (instanceCount + 63) / 64;
        long groups = (long) count * groupsPerRow;
        int roundCount = settled && instanceCount <= settledInstances && count <= settledRows ? 1 : rounds;
        if (roundCount > 1 && retryBits(count, instanceCount) > retryBits) {
            retryShort = true; // grown as the frame ends; until then what does not fit is dropped (and counted)
            roundCount = 1;
        }
        try (MemoryStack stack = stackPush()) {
            // Each round's command: no chunks yet, cull and next round dispatches of x by 1 by 1; the statistics stay.
            long reset = stack.nmalloc(4, 4 * ROUND_WORDS * roundCount);
            for (int round = 0; round < roundCount; round++) {
                long at = reset + 4L * ROUND_WORDS * round;
                memPutInt(at, 0);
                memPutInt(at + 4, 1);
                memPutInt(at + 8, 1);
                memPutInt(at + 12, 0);
                memPutInt(at + 16, 0);
                memPutInt(at + 20, 0);
                memPutInt(at + 24, 1);
                memPutInt(at + 28, 1);
            }
            Commands.updateBuffer(commands, chunkCommand.handle(), roundOffset(0), 4L * ROUND_WORDS * roundCount,
                reset);
            if (roundCount > 1 && groups > 0) { // no workgroup marked
                Commands.fillBuffer(commands, retry.handle(), 0, (groups + 31) / 32 * 4, 0);
            }
            long dispatches = stack.nmalloc(4, 16 * BINS); // per raster bin: no meshlets, a dispatch of x by 1 by 1
            long draws = stack.ncalloc(4, 6 * BINS, 4); // per bin: no indices, one instance, from the bin's first
            for (int bin = 0; bin < BINS; bin++) {
                memPutInt(dispatches + 16L * bin, 0);
                memPutInt(dispatches + 16L * bin + 4, 1);
                memPutInt(dispatches + 16L * bin + 8, 1);
                memPutInt(dispatches + 16L * bin + 12, 0);
                memPutInt(draws + 24L * bin + 4, 1);
            }
            for (int i = 0; i < count; i++) {
                RenderView v = views[i];
                v.putInt(ViewLayout.INSTANCE_COUNT, instanceCount);
                // mesh shaders read no index buffer: every listed meshlet fits
                long indices = 3L * v.options().triangles();
                long binIndices = 3L * v.options().binTriangles();
                v.putInt(ViewLayout.MAX_INDICES, meshShading ? -1 : (int) indices);
                v.putInt(ViewLayout.BIN_INDICES, meshShading ? -1 : (int) binIndices);
                for (int bin = 1; bin < BINS; bin++) {
                    memPutInt(draws + 24L * bin + 8, (int) (indices + (bin - 1) * binIndices)); // the bin's first index
                }
                if (early != null) {
                    Commands.fillBuffer(commands, v.drawCount().handle(), 0, VK_WHOLE_SIZE, 0); // counts, indices wanted
                }
                v.clearGrownVisibility(commands);
                if (v.splitCategories() > 0) { // the split lists' counts and cursors: laid out anew from them
                    Commands.fillBuffer(commands, v.split().handle(), 0,
                        4L * BINS * v.splitCategories() * CommonConstants.SPLIT_WORDS, 0);
                }
                // The late phase keeps the blended bin's commands: its lists are appended to (visibleCountWord).
                int resetBins = early != null ? BINS : MeshletRenderer.BIN_BLENDED;
                Commands.updateBuffer(commands, v.expandCommand().handle(), 0, 16 * resetBins, dispatches);
                Commands.updateBuffer(commands, v.drawCommand().handle(), 0, 24 * resetBins, draws);
            }
        }
        writeRows(commands, views, early, count);
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            long push = stack.nmalloc(8, InstancePush.BYTES);
            memPutLong(push + InstancePush.VIEWS, this.views.constants().deviceAddress());
            memPutLong(push + InstancePush.MESHES, pool.meshTable().deviceAddress());
            memPutLong(push + InstancePush.INSTANCES, scene.instanceAddress());
            memPutLong(push + InstancePush.CHUNKS, chunks.deviceAddress());
            memPutLong(push + InstancePush.LEVELS, pool.levels().deviceAddress());
            memPutInt(push + InstancePush.MAX_CHUNKS, maxChunks);
            occlusion(push + InstancePush.OCCLUSION, pyramid, width, height, mips);
            memPutLong(push + InstancePush.ROWS, this.views.rows().deviceAddress());
            memPutFloat(push + InstancePush.DISPLACEMENT, scene.materials().largestDisplacement());
            memPutLong(push + InstancePush.STATS, chunkCommand.deviceAddress());
            memPutLong(push + InstancePush.RETRY, retry.deviceAddress());
            memPutInt(push + InstancePush.GROUPS_PER_ROW, groupsPerRow);
            memPutInt(push + InstancePush.ROW_COUNT, count);
            long cullPush = stack.nmalloc(8, CullPush.BYTES);
            memPutLong(cullPush + CullPush.VIEWS, this.views.constants().deviceAddress());
            memPutLong(cullPush + CullPush.MESHES, pool.meshTable().deviceAddress());
            memPutLong(cullPush + CullPush.INSTANCES, scene.instanceAddress());
            memPutLong(cullPush + CullPush.CHUNKS, chunks.deviceAddress());
            memPutLong(cullPush + CullPush.MESHLETS, pool.meshlets().deviceAddress());
            memPutLong(cullPush + CullPush.CLUSTER_LOD, pool.clusterLod().deviceAddress());
            occlusion(cullPush + CullPush.OCCLUSION, pyramid, width, height, mips);
            memPutLong(cullPush + CullPush.MATERIALS, scene.materialsAddress()); // each meshlet's raster bin
            for (int round = 0; round < roundCount; round++) {
                long command = chunkCommand.deviceAddress() + roundOffset(round);
                memPutLong(push + InstancePush.CHUNK_COMMAND, command);
                memPutInt(push + InstancePush.ROUND, round);
                memPutInt(push + InstancePush.LAST_ROUND, round == roundCount - 1 ? 1 : 0);
                instancesPass.bind(commands);
                if (round == 0) {
                    // A row of workgroups per view, at most 65,535 rows a dispatch (the guaranteed size).
                    for (int first = 0; first < count; first += MAX_ROWS) {
                        memPutInt(push + InstancePush.FIRST_ROW, first);
                        instancesPass.push(commands, push, InstancePush.BYTES);
                        Commands.dispatch(commands, groupsPerRow, Math.min(MAX_ROWS, count - first), 1);
                    }
                } else {
                    // The workgroups the round before marked (none: a dispatch of none), after its cull pass read
                    // the chunks this one writes.
                    Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                        VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT
                            | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT
                            | VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
                    memPutInt(push + InstancePush.FIRST_ROW, 0);
                    instancesPass.push(commands, push, InstancePush.BYTES);
                    Commands.dispatchIndirect(commands, chunkCommand.handle(), roundOffset(round - 1) + 20);
                }
                // The chunks feed the cull pass's indirect dispatch and the pass itself.
                Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                        | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
                memPutLong(cullPush + CullPush.CHUNK_COMMAND, command); // how many chunks were written
                cull.bind(commands);
                cull.push(commands, cullPush, CullPush.BYTES);
                Commands.dispatchIndirect(commands, chunkCommand.handle(), roundOffset(round));
            }
        }
        split(commands, views, early, count, meshShading);
        sort(commands, views, early, count, meshShading);
        if (meshShading) { // the lists and their dispatches feed the mesh shaders
            if (!ordered) {
                Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | MESH_SHADER_STAGE,
                    VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
            }
            return;
        }
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        expand(commands, views, early, count);
        if (!ordered) { // the expansion feeds the indexed draws
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_INDEX_INPUT_BIT
                    | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT,
                VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_INDEX_READ_BIT
                    | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        }
    }

    /**
     * Forward shading by type: splits the lists of the views that ask for it ({@link RenderView#splitCategories}) by
     * shading bin, after the cull pass: counts, lays out (each shading bin's part and draw), scatters; on the indexed
     * path, before the expansion, which then writes each shading bin's indices in a range of their own.
     */
    private void split(VkCommandBuffer commands, RenderView[] views, boolean[] early, int count,
        boolean meshShading) {
        boolean any = false;
        for (int i = 0; i < count; i++) {
            any |= views[i].splitCategories() > 0;
        }
        if (!any) {
            return;
        }
        Pipeline[] stages = splitStages;
        for (int stage = 0; stage < 3; stage++) {
            // The cull pass's lists (then the stage before's counts and layout) feed the stage.
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                    | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
            stages[stage].bind(commands);
            for (int i = 0; i < count; i++) {
                RenderView v = views[i];
                if (v.splitCategories() == 0) {
                    continue;
                }
                // Threads per raster bin, each looping over the bin's list: up to 256 groups, fewer for short lists.
                int groups = stage == 1 ? 1 : (int) Math.min(SPLIT_GROUPS, (v.options().allDraws() + 63L) / 64);
                try (MemoryStack stack = stackPush()) {
                    long push = stack.nmalloc(8, SplitPush.BYTES);
                    memPutLong(push + SplitPush.VIEW, v.address());
                    memPutLong(push + SplitPush.SCENE, scene.tableAddress());
                    memPutLong(push + SplitPush.VISIBLE, v.visible().deviceAddress());
                    memPutLong(push + SplitPush.VISIBLE_COUNT, v.drawCount().deviceAddress());
                    memPutLong(push + SplitPush.TYPED, v.split().deviceAddress());
                    memPutInt(push + SplitPush.CATEGORIES, v.splitCategories());
                    memPutInt(push + SplitPush.LATE, early == null ? 1 : 0);
                    memPutInt(push + SplitPush.INDEXED, meshShading ? 0 : 1);
                    memPutInt(push + SplitPush.STRIDE, groups * 64);
                    memPutInt(push + SplitPush.BINS, v.splitBins());
                    memPutInt(push + SplitPush.GEOMETRY_BINS, v.splitGeometryBins());
                    stages[stage].push(commands, push, SplitPush.BYTES);
                    Commands.dispatch(commands, groups, BINS, 1);
                }
            }
        }
    }

    /**
     * Sorts the blended bins of the views that ask for it ({@link RenderView#sortBlended}) farthest first in runs, after
     * the split and before the expansion, in the phase drawing them: the late one, or the only one.
     */
    private void sort(VkCommandBuffer commands, RenderView[] views, boolean[] early, int count, boolean meshShading) {
        for (int i = 0; i < count; i++) {
            RenderView v = views[i];
            if (v.splitCategories() > 0 && v.sortSlots() > 0 && (early == null || !early[i])) {
                sortBin(commands, v, MeshletRenderer.BIN_BLENDED, early == null, meshShading);
            }
        }
    }

    /** One sort of view {@code v}'s raster bin {@code bin} (meshlets/sort.slang), its dispatches each after the last. */
    private void sortBin(VkCommandBuffer commands, RenderView v, int bin, boolean late, boolean meshShading) {
        long base = v.sort().deviceAddress();
        long handle = v.sort().handle();
        long keys = base + v.sortOffset(0);
        long other = base + v.sortOffset(1);
        long argsOffset = v.sortOffset(3);
        long args = base + argsOffset;
        int[] drawn = v.sortDrawn();
        try (MemoryStack stack = stackPush()) {
            long table = stack.nmalloc(4, 4 * Math.max(1, drawn.length)); // the drawn shading bins' numbers
            memPutInt(table, -1);
            for (int b = 0; b < drawn.length; b++) {
                memPutInt(table + 4L * b, drawn[b]);
            }
            Commands.updateBuffer(commands, handle, v.sortOffset(6), 4L * Math.max(1, drawn.length), table);
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_TRANSFER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                    | VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT
                    | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                    | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
            long push = stack.ncalloc(8, 1, BlendedSortPush.BYTES);
            memPutLong(push + BlendedSortPush.VIEW, v.address());
            memPutLong(push + BlendedSortPush.SCENE, scene.tableAddress());
            memPutLong(push + BlendedSortPush.VISIBLE, v.visible().deviceAddress());
            memPutLong(push + BlendedSortPush.VISIBLE_COUNT, v.drawCount().deviceAddress());
            memPutLong(push + BlendedSortPush.TYPED, v.split().deviceAddress());
            memPutLong(push + BlendedSortPush.KEYS, keys);
            memPutLong(push + BlendedSortPush.ARGS, args);
            memPutLong(push + BlendedSortPush.BLOCKS, base + v.sortOffset(4));
            memPutLong(push + BlendedSortPush.RUNS, base + v.sortOffset(5));
            memPutLong(push + BlendedSortPush.DRAWN_BINS, base + v.sortOffset(6));
            memPutInt(push + BlendedSortPush.BIN, bin);
            memPutInt(push + BlendedSortPush.LATE, late ? 1 : 0);
            memPutInt(push + BlendedSortPush.CATEGORIES, v.sortCategories());
            memPutInt(push + BlendedSortPush.TABLE_BINS, drawn.length);
            memPutInt(push + BlendedSortPush.ROOM, v.splitCategories());
            memPutInt(push + BlendedSortPush.SLOTS, Math.max(1, v.sortSlots()));
            memPutInt(push + BlendedSortPush.INDEXED, meshShading ? 0 : 1);
            memPutInt(push + BlendedSortPush.GROUP, RadixSort.GROUP);
            long radix = stack.ncalloc(8, 1, SortPush.BYTES);
            memPutLong(radix + SortPush.HISTOGRAM, base + v.sortOffset(2));
            sortStep(commands, sortArgs, push, -1, 0);
            sortStep(commands, sortKeys, push, handle, argsOffset + 16);
            // Over the distance's 24 bits, then (where the runs need it) the shading bin's 8:
            // an even number of passes each, the result back in the first buffer
            radix(commands, radix, handle, argsOffset, args, keys, other, 0, 6);
            sortStep(commands, sortBlocks, push, handle, argsOffset + 48);
            sortStep(commands, sortScan, push, -1, 0);
            radix(commands, radix, handle, argsOffset + 32, args + 32, keys, other, 6, 2);
            memPutInt(push + BlendedSortPush.FINAL, 1);
            sortStep(commands, sortBlocks, push, handle, argsOffset + 48);
            sortStep(commands, sortScan, push, -1, 0);
            sortStep(commands, sortPlace, push, handle, argsOffset + 48);
            sortStep(commands, sortEntries, push, -1, 0);
            // The split lists feed the expansion, the draws and their commands
            sortBarrier(commands);
        }
    }

    /**
     * A dispatch of {@code pipeline} with the push block at {@code push}: one group without {@code handle} (-1), else
     * indirect from {@code offset} of buffer {@code handle}; after a barrier on what dispatches before wrote.
     */
    private static void sortStep(VkCommandBuffer commands, Pipeline pipeline, long push, long handle, long offset) {
        sortBarrier(commands);
        pipeline.bind(commands);
        pipeline.push(commands, push, BlendedSortPush.BYTES);
        if (handle == -1) {
            Commands.dispatch(commands, 1, 1, 1);
        } else {
            Commands.dispatchIndirect(commands, handle, offset);
        }
    }

    private static void sortBarrier(VkCommandBuffer commands) {
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT,
            VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT
                | VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT);
    }

    /**
     * {@code passes} passes of the core's radix sort from digit {@code first} over the keys at {@code keys} (and
     * {@code other}), its dispatch and key count at {@code argsOffset} of buffer {@code handle} ({@code args}).
     */
    private void radix(VkCommandBuffer commands, long push, long handle, long argsOffset, long args, long keys,
        long other, int first, int passes) {
        memPutLong(push + SortPush.ARGS, args);
        for (int pass = first; pass < first + passes; pass++) {
            boolean even = (pass - first) % 2 == 0;
            memPutLong(push + SortPush.SOURCE, even ? keys : other);
            memPutLong(push + SortPush.TARGET, even ? other : keys);
            memPutInt(push + SortPush.SHIFT, 4 * pass);
            sortBarrier(commands);
            radixCount.bind(commands);
            radixCount.push(commands, push, SortPush.BYTES);
            Commands.dispatchIndirect(commands, handle, argsOffset);
            sortBarrier(commands);
            radixScan.bind(commands);
            radixScan.push(commands, push, SortPush.BYTES);
            Commands.dispatch(commands, 1, 1, 1);
            sortBarrier(commands);
            radixScatter.bind(commands);
            radixScatter.push(commands, push, SortPush.BYTES);
            Commands.dispatchIndirect(commands, handle, argsOffset);
        }
    }

    /** The indexed path's expansion of each view's lists, per raster bin, into its index buffer. */
    private void expand(VkCommandBuffer commands, RenderView[] views, boolean[] early, int count) {
        for (int i = 0; i < count; i++) {
            RenderView v = views[i];
            for (int bin = 0; bin < BINS; bin++) {
                boolean split = v.splitCategories() > 0 && (v.splitBins() & 1 << bin) != 0;
                (split ? expandTyped : expand).bind(commands); // a bin split by shading bin: in the split's order
                try (MemoryStack stack = stackPush()) {
                    long push = stack.nmalloc(8, ExpandPush.BYTES);
                    memPutLong(push + ExpandPush.VIEW, v.address());
                    memPutLong(push + ExpandPush.VISIBLE, v.visible().deviceAddress());
                    memPutLong(push + ExpandPush.VISIBLE_COUNT, v.drawCount().deviceAddress());
                    memPutLong(push + ExpandPush.MESHLETS, pool.meshlets().deviceAddress());
                    memPutLong(push + ExpandPush.MESHES, pool.meshTable().deviceAddress());
                    memPutLong(push + ExpandPush.INSTANCES, scene.instanceAddress());
                    memPutLong(push + ExpandPush.MESHLET_TRIANGLES, pool.meshletTriangles().deviceAddress());
                    memPutLong(push + ExpandPush.INDICES, v.frameIndices().deviceAddress());
                    memPutInt(push + ExpandPush.BIN, bin);
                    memPutInt(push + ExpandPush.LATE, early == null ? 1 : 0);
                    memPutLong(push + ExpandPush.TYPED, split ? v.split().deviceAddress() : device.absentAddress());
                    memPutInt(push + ExpandPush.CATEGORIES, v.splitCategories());
                    expand.push(commands, push, ExpandPush.BYTES);
                    Commands.dispatchIndirect(commands, v.expandCommand().handle(), 16L * bin);
                }
            }
        }
    }

    /** Whether views[0] to views[count - 1] all have the index buffers the graph declared. */
    private static boolean indicesOrdered(RenderView[] views, int count) {
        for (int i = 0; i < count; i++) {
            if (!views[i].indicesOrdered()) {
                return false;
            }
        }
        return true;
    }

    /** The shaders' Occlusion struct at {@code address}: pyramid slot, depth size, pyramid levels. */
    private static void occlusion(long address, int pyramid, int width, int height, int mips) {
        memPutInt(address, pyramid);
        memPutInt(address + OCCLUSION_WIDTH, width);
        memPutInt(address + OCCLUSION_HEIGHT, height);
        memPutInt(address + OCCLUSION_MIPS, mips);
    }

    /**
     * Writes the instance pass's rows: each view culled (its index) and its phase ({@code CulledView}), as commands, so
     * each cull of a frame reads its own. At most 64 KiB per update, as Vulkan allows.
     */
    private void writeRows(VkCommandBuffer commands, RenderView[] views, boolean[] early, int count) {
        for (int first = 0; first < count; first += ROWS_PER_UPDATE) {
            int n = Math.min(ROWS_PER_UPDATE, count - first);
            try (MemoryStack stack = stackPush()) {
                long data = stack.nmalloc(4, 8 * n);
                for (int i = 0; i < n; i++) {
                    RenderView v = views[first + i];
                    memPutInt(data + 8L * i, v.index());
                    memPutInt(data + 8L * i + 4, phase(early, first + i));
                }
                Commands.updateBuffer(commands, this.views.rows().handle(), 8L * first, 8L * n, data);
            }
        }
    }

    /** Closes its pipelines and buffers: the GPU is done with them. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            slots.removeOnEnd(grow);
            instancesPass.close();
            cull.close();
            expand.close();
            expandTyped.close();
            splitCount.close();
            splitLayout.close();
            splitScatter.close();
            for (Pipeline pipeline : new Pipeline[] {sortArgs, sortKeys, sortBlocks, sortScan, sortPlace, sortEntries,
                radixCount, radixScan, radixScatter}) {
                pipeline.close();
            }
            chunks.close();
            chunkCommand.close();
            retry.close();
        }
    }
}
