package com.truesim.engine.graph;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D16_UNORM_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_X8_D24_UNORM_PACK32;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_REMAINING_MIP_LEVELS;
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.nvkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkResetCommandPool;
import static org.lwjgl.vulkan.VK12.VK_SEMAPHORE_TYPE_TIMELINE;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_SUBMIT_INFO_2;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;
import static org.lwjgl.vulkan.VK13.nvkCmdPipelineBarrier2;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder.BufferInfo;
import com.truesim.engine.graph.GraphBuilder.ImageInfo;
import com.truesim.engine.graph.GraphBuilder.PassInfo;
import com.truesim.engine.vulkan.Bindless;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.common.jobs.JobSystem;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.Queue;
import com.truesim.engine.vulkan.Vulkan;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferMemoryBarrier2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The frame as a graph of {@link Technique}s: add them, say which results the frame {@link #output outputs} (into the
 * swapchain image, say), and {@link #record} each frame. Nothing is wired by hand:
 *
 * <ul>
 *   <li><b>Resolved by key:</b> techniques require results by {@link Key}, and the technique providing each is built
 *       first. Techniques nothing needs are never built; two providers of one key, a key nobody provides and cycles are
 *       errors that name the techniques.</li>
 *   <li><b>Swappable:</b> {@link #replace} swaps a technique for another providing the same keys (lighting,
 *       translucency), {@link #implement} how a type of request is computed (a cheaper blur); nothing else changes.</li>
 *   <li><b>Shared work runs once:</b> equal {@link Request}s (the same blur of the same image, say) give every
 *       technique the same result.</li>
 *   <li><b>Culled:</b> passes whose images nothing the outputs need are left out.</li>
 *   <li><b>Synchronized:</b> barriers and layout transitions come from the accesses passes declare, batched one call
 *       per pass, read after read without any.</li>
 *   <li><b>Pooled:</b> transient images and buffers live in a pool kept across frames, and those whose lifetimes do
 *       not overlap share one; an output is written straight into its target when it fits.</li>
 * </ul>
 *
 * <p>The graph is built (techniques' {@code build}, resolving, culling, barriers, images) when it changes: techniques
 * or outputs added or removed, an external image resized, or {@link #invalidate()}. Recording a built graph allocates
 * nothing. Per-frame data (cameras, parameters) belongs to the techniques, which their pass bodies read when recording.
 * Frames are recorded on one thread (its passes possibly on {@link #parallel} workers); closing waits for the frames
 * submitted.
 */
@AllowAllocation("set up once")
public final class FrameGraph implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FrameGraph.class);
    static final int GRAPHICS = 0;
    static final int COMPUTE = 1;
    static final int IMAGE = 0;
    static final int BUFFER = 1;

    private final VulkanDevice device;
    private final Bindless bindless;
    private final int framesInFlight;
    private final FrameSlots frameSlots;
    private final boolean ownsSlots;
    private final List<Technique> techniques = new ArrayList<>();
    private final Map<Key, ExternalImage> outputs = new LinkedHashMap<>();
    private final Map<Class<?>, Implementation<?, ?>> implementations = new HashMap<>();
    private final Map<Object, AutoCloseable> cache = new LinkedHashMap<>();
    private final List<Physical> pool = new ArrayList<>();
    private final List<Physical> retired = new ArrayList<>();
    private final List<PhysicalBuffer> bufferPool = new ArrayList<>();
    private final List<PhysicalBuffer> retiredBuffers = new ArrayList<>();
    private final Map<String, HistoryPair> histories = new HashMap<>();
    private HistoryPair[] activeHistories = new HistoryPair[0];
    private final Queue graphicsQueue;
    private final Queue computeQueue;
    private boolean asyncCompute;
    private JobSystem jobs;
    private int grain = 4;
    private PassContext[] contexts = {new PassContext(this)};
    private final JobSystem.RangeTask recordChunks = this::recordChunks;
    private Execution execution;
    private boolean dirty = true;
    private long frame;
    private int builds;

    // The built graph: passes in order, the barriers before each (from barrierStart[i] to barrierStart[i + 1]; the
    // last range is after all passes), and where each image of the build lives: a physical image (index >= 0) or an
    // external one (~index).
    private PassInfo[] order = new PassInfo[0];
    private int[] barrierStart = {0, 0};
    private int[] barrierTarget = new int[0];
    private int[] barrierKind = new int[0]; // IMAGE or BUFFER
    private int[] barrierAspect = new int[0];
    private int[] barrierOldLayout = new int[0];
    private int[] barrierNewLayout = new int[0];
    private long[] barrierSrcStage = new long[0];
    private long[] barrierSrcAccess = new long[0];
    private long[] barrierDstStage = new long[0];
    private long[] barrierDstAccess = new long[0];
    private int[] targetOf = new int[0];
    private Physical[] physicals = new Physical[0];
    private ExternalImage[] externals = new ExternalImage[0];
    private int[] externalVersions = new int[0];
    private ExternalBuffer[] externalBuffers = new ExternalBuffer[0];
    private int[] externalBufferVersions = new int[0];
    private int[] bufferTargetOf = new int[0];
    private PhysicalBuffer[] physicalBuffers = new PhysicalBuffer[0];

    // How the built graph runs: batches of passes, each on one queue, waiting for the other queue's batch of ordinal
    // batchWait (0: none) and, with batchAcquire, for the external images; each cut into chunks recorded in parallel.
    private int[] batchQueue = {GRAPHICS};
    private int[] batchOrdinal = {1};
    private int[] batchWait = {0};
    private boolean[] batchAcquire = {false};
    private int[] batchFirstChunk = {0, 1};
    private int lastGraphicsBatch;
    private int firstGraphicsChunk; // where the frame slots' frame start is recorded
    private int graphicsBatches = 1;
    private int computeBatches;
    private int graphicsChunks = 1;
    private int computeChunks;
    private int[] chunkBatch = {0};
    private int[] chunkFirstPass = {0};
    private int[] chunkEndPass = {0};
    private VkCommandBuffer[] chunkCommands = new VkCommandBuffer[1];

    /** A graph on {@code device} whose frames may be {@code framesInFlight} deep (images retire after that). */
    public FrameGraph(VulkanDevice device, int framesInFlight) {
        this(device, framesInFlight, null);
    }

    /**
     * A graph on {@code device} sharing {@code slots} with other graphs, frames as deep as it has slots: for several
     * graphs drawing views of scenes whose data they share (meshes, materials, renderers' instances). The graphs run in
     * step, each frame of each; the caller opens the frame's slot once every graph has begun its frame, and closes it
     * once every graph has submitted:
     *
     * <pre>{@code
     * int slot = world.beginFrame();
     * minimap.beginFrame();              // the same slot: the graphs run in step
     * slots.begin(slot);                 // no frame in flight reads the slot's copies now
     * world.submit(...);
     * minimap.submit(...);
     * slots.end();
     * }</pre>
     */
    public FrameGraph(VulkanDevice device, FrameSlots slots) {
        this(device, Objects.requireNonNull(slots, "slots").count(), slots);
    }

    private FrameGraph(VulkanDevice device, int framesInFlight, FrameSlots shared) {
        if (framesInFlight < 1) {
            throw new IllegalArgumentException("At least one frame in flight: " + framesInFlight);
        }
        this.device = Objects.requireNonNull(device, "device");
        this.bindless = device.bindless();
        this.framesInFlight = framesInFlight;
        this.ownsSlots = shared == null;
        this.frameSlots = ownsSlots ? new FrameSlots(framesInFlight) : shared;
        this.graphicsQueue = device.graphics();
        this.computeQueue = device.compute();
        this.asyncCompute = graphicsQueue != computeQueue;
    }

    /** Adds a technique; the graph rebuilds on its next frame. */
    public FrameGraph add(Technique technique) {
        if (techniques.contains(Objects.requireNonNull(technique, "technique"))) {
            throw new IllegalArgumentException(GraphBuilder.name(technique) + " is in the graph already");
        }
        techniques.add(technique);
        dirty = true;
        return this;
    }

    /** Removes a technique (switching it off costs nothing from then on); the graph rebuilds on its next frame. */
    public FrameGraph remove(Technique technique) {
        dirty |= techniques.remove(technique);
        return this;
    }

    /**
     * Swaps in {@code technique} for the techniques providing any of its keys, e.g. one lighting technique for another:
     * whoever requires those keys gets the new one. Removes nothing if none does. The graph rebuilds on its next frame.
     */
    public FrameGraph replace(Technique technique) {
        List<Key> keys = technique.provides();
        techniques.removeIf(t -> t.provides().stream().anyMatch(keys::contains));
        return add(technique);
    }

    /**
     * Computes every request of {@code type} with {@code implementation} from now on (null: the request's own
     * {@link Request#build}), e.g. a cheaper blur on low settings. The graph rebuilds on its next frame.
     */
    public <Q extends Request<R>, R> FrameGraph implement(Class<Q> type, Implementation<Q, R> implementation) {
        if (implementation == null) {
            implementations.remove(Objects.requireNonNull(type, "type"));
        } else {
            implementations.put(Objects.requireNonNull(type, "type"), implementation);
        }
        dirty = true;
        return this;
    }

    /** Makes each frame end with result {@code key} in {@code target}; the graph rebuilds on its next frame. */
    public FrameGraph output(Key key, ExternalImage target) {
        outputs.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(target, "target"));
        dirty = true;
        return this;
    }

    /** Stops outputting {@code key}. */
    public FrameGraph removeOutput(Key key) {
        dirty |= outputs.remove(key) != null;
        return this;
    }

    /** Rebuilds on the next frame, e.g. after a technique changed what it builds. */
    public void invalidate() {
        dirty = true;
    }

    // ---------------------------------------------------------------- how frames run

    /**
     * Runs {@link GraphBuilder.PassBuilder#async() async} passes on the compute queue (on by default where the device has a separate
     * one), beside the graphics work that does not depend on them. Off, they run on the graphics queue in their place.
     * On a device whose compute queue is the graphics queue, on still splits the frame into batches the same way, which
     * runs the same code paths on one queue. The graph rebuilds on its next frame.
     */
    public FrameGraph asyncCompute(boolean on) {
        if (on != asyncCompute) {
            waitIdle();
            asyncCompute = on;
            dirty = true;
        }
        return this;
    }

    /**
     * Records with {@code jobs}' threads, in chunks of {@code passes} passes each (null: on the calling thread). The
     * chunks are submitted in order, so the GPU sees the same commands however the recording was scheduled. Pass bodies
     * then run on worker threads, in parallel with each other: they must not share what they change.
     */
    @AllowAllocation("set up once")
    public FrameGraph parallel(JobSystem jobs, int passes) {
        if (passes < 1) {
            throw new IllegalArgumentException("Chunks hold at least one pass: " + passes);
        }
        waitIdle();
        this.jobs = jobs;
        this.grain = passes;
        int threads = jobs == null ? 1 : jobs.threads();
        contexts = new PassContext[threads];
        for (int w = 0; w < threads; w++) {
            contexts[w] = new PassContext(this);
        }
        if (execution != null) {
            execution.close();
            execution = null;
        }
        dirty = true;
        return this;
    }

    /**
     * Starts a frame for {@link #submit}: waits until the GPU finished the frame that last used this one's command
     * buffers ({@code framesInFlight} frames ago) and returns its index, 0 to {@code framesInFlight - 1}, e.g. to pick
     * the semaphore to acquire a swapchain image with. Allocates nothing once warm.
     */
    public int beginFrame() {
        if (execution == null) {
            execution = new Execution();
        }
        int slot = execution.begin();
        if (ownsSlots) {
            frameSlots.begin(slot);
        }
        return slot;
    }

    /**
     * The frame slots this graph's per-frame data keeps a copy per ({@link com.truesim.engine.vulkan.FrameBuffer}s of
     * renderers, lights, ...): {@link #beginFrame} opens the frame's slot, once the GPU has finished with it, and
     * {@link #submit} closes it; slots shared with other graphs are the caller's to open and close.
     */
    public FrameSlots frameSlots() {
        return frameSlots;
    }

    /**
     * Records the frame begun by {@link #beginFrame} (in parallel, with {@link #parallel}) and submits it: batches to
     * the graphics and compute queues in order, ordered across queues by timeline semaphores. The first batch using an
     * external image waits for binary semaphore {@code acquired} (0: none); the last graphics batch signals
     * {@code renderFinished} (0: none), e.g. for presenting. Builds the graph first if it changed; otherwise allocates
     * nothing.
     */
    public void submit(long acquired, long renderFinished) {
        if (execution == null || !execution.begun) {
            throw new IllegalStateException("Begin the frame first: beginFrame()");
        }
        prepare();
        execution.ensureCommandBuffers();
        if (jobs != null && chunkBatch.length > 1) {
            jobs.parallelFor(chunkBatch.length, 1, recordChunks);
        } else {
            recordChunks(0, chunkBatch.length, 0);
        }
        execution.submit(acquired, renderFinished);
        if (ownsSlots) {
            frameSlots.end();
        }
    }

    /**
     * Records the whole frame into {@code commands}, on the calling thread: the bindless set, then each needed pass
     * with its barriers, then the outputs' final layouts. Async passes run in their place. Builds the graph first if it
     * changed; otherwise allocates nothing. Outside {@link #beginFrame} and {@link #submit}, per-frame data uses frame
     * slot 0: the caller waits for the GPU before recording the next frame.
     */
    public void record(VkCommandBuffer commands) {
        // Outside beginFrame and submit, frames are the caller's to wait for: their data is slot 0's.
        boolean open = frameSlots.current() < 0;
        if (open) {
            frameSlots.begin(0);
        }
        prepare();
        bindless.bind(commands);
        frameSlots.recordStart(commands);
        recordPasses(commands, 0, order.length, true, contexts[0]);
        if (open) {
            frameSlots.end();
        }
    }

    private void prepare() {
        if (dirty || externalsResized()) {
            build();
        } else {
            // A new frame: last frame's current images are this frame's previous ones.
            for (HistoryPair pair : activeHistories) {
                pair.current.swap(pair.previous);
            }
        }
        frame++;
        if (!retired.isEmpty() || !retiredBuffers.isEmpty()) {
            release(false);
        }
    }

    /** Records chunks {@code from} to {@code to}, on worker {@code worker}: its own command buffers. */
    private void recordChunks(int from, int to, int worker) {
        for (int c = from; c < to; c++) {
            int batch = chunkBatch[c];
            boolean graphics = batchQueue[batch] == GRAPHICS;
            VkCommandBuffer commands = execution.commandBuffer(worker, batchQueue[batch]);
            bindless.bind(commands, graphics);
            boolean last = batch == lastGraphicsBatch && c == batchFirstChunk[batch + 1] - 1;
            if (c == firstGraphicsChunk) {
                frameSlots.recordStart(commands); // uploads and such, ahead of every pass on the graphics queue
            }
            recordPasses(commands, chunkFirstPass[c], chunkEndPass[c], last, contexts[worker]);
            Vulkan.check(vkEndCommandBuffer(commands), "Could not finish recording");
            chunkCommands[c] = commands;
        }
    }

    /** Passes {@code from} to {@code to} with their barriers, and with {@code last} the outputs' final layouts. */
    private void recordPasses(VkCommandBuffer commands, int from, int to, boolean last, PassContext context) {
        context.commands = commands;
        for (int i = from; i < to; i++) {
            barriers(commands, barrierStart[i], barrierStart[i + 1]);
            order[i].body.record(context);
        }
        if (last) {
            barriers(commands, barrierStart[order.length], barrierStart[order.length + 1]);
        }
        context.commands = null;
    }

    /** Waits until the GPU finished everything submitted, e.g. before changing how frames run. */
    private void waitIdle() {
        if (execution != null) {
            execution.waitIdle();
        }
    }

    /**
     * Each frame in flight's command pools (one per worker and queue, so workers record without locks) and the two
     * timeline semaphores that order batches across queues and tell when a frame is done.
     */
    @AllowAllocation("set up once")
    private final class Execution implements AutoCloseable {
        private final long graphicsTimeline;
        private final long computeTimeline;
        private long graphicsValue;
        private long computeValue;
        private final long[][] pools; // [slot][worker * 2 + queue]
        private final VkCommandBuffer[][][] buffers; // [slot][worker * 2 + queue][i]
        private final int[] used;
        private final long[] slotGraphicsValue;
        private final long[] slotComputeValue;
        private int slot = -1;
        boolean begun;

        Execution() {
            graphicsTimeline = timeline();
            computeTimeline = timeline();
            int workers = contexts.length;
            pools = new long[framesInFlight][workers * 2];
            buffers = new VkCommandBuffer[framesInFlight][workers * 2][0];
            used = new int[workers * 2];
            slotGraphicsValue = new long[framesInFlight];
            slotComputeValue = new long[framesInFlight];
            try (MemoryStack stack = stackPush()) {
                LongBuffer handle = stack.mallocLong(1);
                for (int s = 0; s < framesInFlight; s++) {
                    for (int i = 0; i < workers * 2; i++) {
                        Queue queue = i % 2 == GRAPHICS ? graphicsQueue : computeQueue;
                        Vulkan.check(vkCreateCommandPool(device.handle(), VkCommandPoolCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                            .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                            .queueFamilyIndex(queue.family()), null, handle), "Could not create a command pool");
                        pools[s][i] = handle.get(0);
                    }
                }
            }
        }

        private long timeline() {
            try (MemoryStack stack = stackPush()) {
                VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO)
                    .semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE);
                LongBuffer handle = stack.mallocLong(1);
                Vulkan.check(vkCreateSemaphore(device.handle(), VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                    .pNext(type.address()), null, handle), "Could not create a timeline semaphore");
                return handle.get(0);
            }
        }

        int begin() {
            if (begun) {
                throw new IllegalStateException("The frame was begun already: submit() it first");
            }
            slot = (slot + 1) % framesInFlight;
            waitFor(slotGraphicsValue[slot], slotComputeValue[slot]);
            for (int i = 0; i < used.length; i++) {
                Vulkan.check(vkResetCommandPool(device.handle(), pools[slot][i], 0), "Could not reset a command pool");
                used[i] = 0;
            }
            begun = true;
            return slot;
        }

        private void waitFor(long graphics, long compute) {
            try (MemoryStack stack = stackPush()) {
                long semaphores = stack.nmalloc(8, 16);
                memPutLong(semaphores, graphicsTimeline);
                memPutLong(semaphores + 8, computeTimeline);
                long values = stack.nmalloc(8, 16);
                memPutLong(values, graphics);
                memPutLong(values + 8, compute);
                long info = stack.ncalloc(VkSemaphoreWaitInfo.ALIGNOF, 1, VkSemaphoreWaitInfo.SIZEOF);
                memPutInt(info + VkSemaphoreWaitInfo.STYPE, VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO);
                memPutInt(info + VkSemaphoreWaitInfo.SEMAPHORECOUNT, 2);
                memPutAddress(info + VkSemaphoreWaitInfo.PSEMAPHORES, semaphores);
                memPutAddress(info + VkSemaphoreWaitInfo.PVALUES, values);
                Vulkan.check(device.direct().waitSemaphores(info, Long.MAX_VALUE), "Could not wait for a frame");
            }
        }

        void waitIdle() {
            waitFor(graphicsValue, computeValue);
        }

        /** Makes sure each worker could record every chunk of each queue: workers take chunks as they come free. */
        void ensureCommandBuffers() {
            for (int i = 0; i < used.length; i++) {
                int needed = i % 2 == GRAPHICS ? graphicsChunks : computeChunks;
                if (buffers[slot][i].length < needed) {
                    grow(i, needed);
                }
            }
        }

        @AllowAllocation("building the graph, when it changes")
        private void grow(int i, int needed) {
            VkCommandBuffer[] have = buffers[slot][i];
            VkCommandBuffer[] more = Arrays.copyOf(have, needed);
            try (MemoryStack stack = stackPush()) {
                PointerBuffer handles = stack.mallocPointer(more.length - have.length);
                Vulkan.check(vkAllocateCommandBuffers(device.handle(), VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(pools[slot][i])
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(more.length - have.length), handles), "Could not allocate command buffers");
                for (int b = have.length; b < more.length; b++) {
                    more[b] = new VkCommandBuffer(handles.get(b - have.length), device.handle());
                }
            }
            buffers[slot][i] = more;
        }

        /** The worker's next command buffer for {@code queue}, begun. Allocates nothing. */
        VkCommandBuffer commandBuffer(int worker, int queue) {
            int i = worker * 2 + queue;
            VkCommandBuffer commands = buffers[slot][i][used[i]++];
            try (MemoryStack stack = stackPush()) {
                long info = stack.ncalloc(VkCommandBufferBeginInfo.ALIGNOF, 1, VkCommandBufferBeginInfo.SIZEOF);
                memPutInt(info + VkCommandBufferBeginInfo.STYPE, VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
                memPutInt(info + VkCommandBufferBeginInfo.FLAGS, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                Vulkan.check(nvkBeginCommandBuffer(commands, info), "Could not begin recording");
            }
            return commands;
        }

        /** Submits the batches in order, each to its queue. Allocates nothing. */
        void submit(long acquired, long renderFinished) {
            long graphicsBase = graphicsValue;
            long computeBase = computeValue;
            boolean acquireWaited = acquired == 0;
            boolean bothQueues = computeBatches > 0;
            boolean firstGraphics = true;
            boolean firstCompute = true;
            for (int b = 0; b < batchQueue.length; b++) {
                int queue = batchQueue[b];
                try (MemoryStack stack = stackPush()) {
                    int from = batchFirstChunk[b];
                    int count = batchFirstChunk[b + 1] - from;
                    long infos = stack.ncalloc(VkCommandBufferSubmitInfo.ALIGNOF, Math.max(1, count),
                        VkCommandBufferSubmitInfo.SIZEOF);
                    for (int c = 0; c < count; c++) {
                        long info = infos + (long) c * VkCommandBufferSubmitInfo.SIZEOF;
                        memPutInt(info + VkCommandBufferSubmitInfo.STYPE, VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO);
                        memPutAddress(info + VkCommandBufferSubmitInfo.COMMANDBUFFER, chunkCommands[from + c].address());
                    }
                    // Waits: the other queue's batch this one depends on (or, first on its queue, all the other queue
                    // did in the frame before, as images pass between queues), and the acquired image.
                    long waits = stack.ncalloc(VkSemaphoreSubmitInfo.ALIGNOF, 2, VkSemaphoreSubmitInfo.SIZEOF);
                    int waitCount = 0;
                    long otherBase = queue == GRAPHICS ? computeBase : graphicsBase;
                    long otherValue = batchWait[b] > 0 ? otherBase + batchWait[b] : 0;
                    if ((queue == GRAPHICS ? firstGraphics : firstCompute) && bothQueues) {
                        otherValue = Math.max(otherValue, otherBase);
                    }
                    firstGraphics &= queue != GRAPHICS;
                    firstCompute &= queue != COMPUTE;
                    if (otherValue > 0) {
                        semaphore(waits, queue == GRAPHICS ? computeTimeline : graphicsTimeline, otherValue);
                        waitCount++;
                    }
                    boolean acquire = !acquireWaited && (batchAcquire[b] || b == lastGraphicsBatch);
                    if (acquire) {
                        semaphore(waits + (long) waitCount * VkSemaphoreSubmitInfo.SIZEOF, acquired, 0);
                        waitCount++;
                        acquireWaited = true;
                    }
                    long signals = stack.ncalloc(VkSemaphoreSubmitInfo.ALIGNOF, 2, VkSemaphoreSubmitInfo.SIZEOF);
                    int signalCount = 1;
                    semaphore(signals, queue == GRAPHICS ? graphicsTimeline : computeTimeline,
                        (queue == GRAPHICS ? graphicsBase : computeBase) + batchOrdinal[b]);
                    if (b == lastGraphicsBatch && renderFinished != 0) {
                        semaphore(signals + VkSemaphoreSubmitInfo.SIZEOF, renderFinished, 0);
                        signalCount++;
                    }
                    long submit = stack.ncalloc(VkSubmitInfo2.ALIGNOF, 1, VkSubmitInfo2.SIZEOF);
                    memPutInt(submit + VkSubmitInfo2.STYPE, VK_STRUCTURE_TYPE_SUBMIT_INFO_2);
                    memPutInt(submit + VkSubmitInfo2.WAITSEMAPHOREINFOCOUNT, waitCount);
                    memPutAddress(submit + VkSubmitInfo2.PWAITSEMAPHOREINFOS, waits);
                    memPutInt(submit + VkSubmitInfo2.COMMANDBUFFERINFOCOUNT, count);
                    memPutAddress(submit + VkSubmitInfo2.PCOMMANDBUFFERINFOS, infos);
                    memPutInt(submit + VkSubmitInfo2.SIGNALSEMAPHOREINFOCOUNT, signalCount);
                    memPutAddress(submit + VkSubmitInfo2.PSIGNALSEMAPHOREINFOS, signals);
                    (queue == GRAPHICS ? graphicsQueue : computeQueue).submit(1, submit, 0);
                }
            }
            graphicsValue += graphicsBatches;
            computeValue += computeBatches;
            slotGraphicsValue[slot] = graphicsValue;
            slotComputeValue[slot] = computeValue;
            begun = false;
        }

        private static void semaphore(long info, long semaphore, long value) {
            memPutInt(info + VkSemaphoreSubmitInfo.STYPE, VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO);
            memPutLong(info + VkSemaphoreSubmitInfo.SEMAPHORE, semaphore);
            memPutLong(info + VkSemaphoreSubmitInfo.VALUE, value);
            memPutLong(info + VkSemaphoreSubmitInfo.STAGEMASK, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
        }

        @Override
        public void close() {
            waitIdle();
            for (long[] slotPools : pools) {
                for (long pool : slotPools) {
                    vkDestroyCommandPool(device.handle(), pool, null);
                }
            }
            vkDestroySemaphore(device.handle(), graphicsTimeline, null);
            vkDestroySemaphore(device.handle(), computeTimeline, null);
        }
    }

    // ---------------------------------------------------------------- recording

    private boolean externalsResized() {
        for (int i = 0; i < externalBuffers.length; i++) {
            if (externalBuffers[i].version != externalBufferVersions[i]) {
                return true;
            }
        }
        for (int i = 0; i < externals.length; i++) {
            if (externals[i].version != externalVersions[i]) {
                return true;
            }
        }
        return false;
    }

    private void barriers(VkCommandBuffer commands, int from, int to) {
        if (from == to) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            int bufferCount = 0;
            for (int b = from; b < to; b++) {
                bufferCount += barrierKind[b];
            }
            int imageCount = to - from - bufferCount;
            long images = stack.ncalloc(VkImageMemoryBarrier2.ALIGNOF, Math.max(1, imageCount),
                VkImageMemoryBarrier2.SIZEOF);
            long buffers = stack.ncalloc(VkBufferMemoryBarrier2.ALIGNOF, Math.max(1, bufferCount),
                VkBufferMemoryBarrier2.SIZEOF);
            int image = 0;
            int buffer = 0;
            for (int b = from; b < to; b++) {
                if (barrierKind[b] == BUFFER) {
                    long barrier = buffers + (long) buffer++ * VkBufferMemoryBarrier2.SIZEOF;
                    memPutInt(barrier + VkBufferMemoryBarrier2.STYPE, VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2);
                    memPutLong(barrier + VkBufferMemoryBarrier2.SRCSTAGEMASK, barrierSrcStage[b]);
                    memPutLong(barrier + VkBufferMemoryBarrier2.SRCACCESSMASK, barrierSrcAccess[b]);
                    memPutLong(barrier + VkBufferMemoryBarrier2.DSTSTAGEMASK, barrierDstStage[b]);
                    memPutLong(barrier + VkBufferMemoryBarrier2.DSTACCESSMASK, barrierDstAccess[b]);
                    memPutInt(barrier + VkBufferMemoryBarrier2.SRCQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED);
                    memPutInt(barrier + VkBufferMemoryBarrier2.DSTQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED);
                    int target = barrierTarget[b];
                    memPutLong(barrier + VkBufferMemoryBarrier2.BUFFER, target >= 0
                        ? physicalBuffers[target].buffer.handle() : externalBuffers[~target].handle);
                    memPutLong(barrier + VkBufferMemoryBarrier2.SIZE, VK_WHOLE_SIZE);
                    continue;
                }
                long barrier = images + (long) image++ * VkImageMemoryBarrier2.SIZEOF;
                memPutInt(barrier + VkImageMemoryBarrier2.STYPE, VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2);
                memPutLong(barrier + VkImageMemoryBarrier2.SRCSTAGEMASK, barrierSrcStage[b]);
                memPutLong(barrier + VkImageMemoryBarrier2.SRCACCESSMASK, barrierSrcAccess[b]);
                memPutLong(barrier + VkImageMemoryBarrier2.DSTSTAGEMASK, barrierDstStage[b]);
                memPutLong(barrier + VkImageMemoryBarrier2.DSTACCESSMASK, barrierDstAccess[b]);
                memPutInt(barrier + VkImageMemoryBarrier2.OLDLAYOUT, barrierOldLayout[b]);
                memPutInt(barrier + VkImageMemoryBarrier2.NEWLAYOUT, barrierNewLayout[b]);
                memPutInt(barrier + VkImageMemoryBarrier2.SRCQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED);
                memPutInt(barrier + VkImageMemoryBarrier2.DSTQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED);
                int target = barrierTarget[b];
                memPutLong(barrier + VkImageMemoryBarrier2.IMAGE, target >= 0 ? physicals[target].image.handle()
                    : externals[~target].handle);
                long range = barrier + VkImageMemoryBarrier2.SUBRESOURCERANGE;
                memPutInt(range + VkImageSubresourceRange.ASPECTMASK, barrierAspect[b]);
                memPutInt(range + VkImageSubresourceRange.LEVELCOUNT, VK_REMAINING_MIP_LEVELS);
                memPutInt(range + VkImageSubresourceRange.LAYERCOUNT, 1);
            }
            long dependency = stack.ncalloc(VkDependencyInfo.ALIGNOF, 1, VkDependencyInfo.SIZEOF);
            memPutInt(dependency + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
            memPutInt(dependency + VkDependencyInfo.IMAGEMEMORYBARRIERCOUNT, imageCount);
            memPutAddress(dependency + VkDependencyInfo.PIMAGEMEMORYBARRIERS, images);
            memPutInt(dependency + VkDependencyInfo.BUFFERMEMORYBARRIERCOUNT, bufferCount);
            memPutAddress(dependency + VkDependencyInfo.PBUFFERMEMORYBARRIERS, buffers);
            nvkCmdPipelineBarrier2(commands, dependency);
        }
    }

    long bufferHandle(int buffer) {
        int target = bufferTargetOf[buffer];
        return target >= 0 ? physicalBuffers[target].buffer.handle() : externalBuffers[~target].handle;
    }

    long bufferAddress(int buffer) {
        int target = bufferTargetOf[buffer];
        return target >= 0 ? physicalBuffers[target].buffer.deviceAddress() : externalBuffers[~target].address;
    }

    long handleOf(int image) {
        int target = targetOf[image];
        return target >= 0 ? physicals[target].image.handle() : externals[~target].handle;
    }

    long viewOf(int image) {
        int target = targetOf[image];
        return target >= 0 ? physicals[target].image.view() : externals[~target].view;
    }

    int sampledSlotOf(int image) {
        return physicals[targetOf[image]].sampled;
    }

    int storageSlotOf(int image, int mip) {
        int[] slots = physicals[targetOf[image]].storage;
        return mip < slots.length ? slots[mip] : -1;
    }

    VulkanDevice device() {
        return device;
    }

    @SuppressWarnings("unchecked")
    @AllowAllocation("building the graph, when it changes")
    <T extends AutoCloseable> T cached(Object key, Function<VulkanDevice, T> create) {
        AutoCloseable object = cache.get(Objects.requireNonNull(key, "key"));
        if (object == null) {
            object = Objects.requireNonNull(create.apply(device), "A cached object");
            cache.put(key, object);
        }
        return (T) object;
    }

    // ---------------------------------------------------------------- what was built, for tools and tests

    /** The passes each frame records, in order. */
    @AllowAllocation("inspection")
    public List<String> passes() {
        List<String> names = new ArrayList<>(order.length);
        for (PassInfo pass : order) {
            names.add(pass.name);
        }
        return names;
    }

    /**
     * The batches {@link #submit} submits, in order: their queue, what they wait for and their passes, e.g.
     * {@code "compute 1 (after graphics 1): ssao"}.
     */
    @AllowAllocation("inspection")
    public List<String> batches() {
        List<String> out = new ArrayList<>();
        for (int b = 0; b < batchQueue.length; b++) {
            StringBuilder line = new StringBuilder(batchQueue[b] == GRAPHICS ? "graphics " : "compute ")
                .append(batchOrdinal[b]);
            List<String> after = new ArrayList<>();
            if (batchWait[b] > 0) {
                after.add((batchQueue[b] == GRAPHICS ? "compute " : "graphics ") + batchWait[b]);
            }
            if (batchAcquire[b]) {
                after.add("acquire");
            }
            if (!after.isEmpty()) {
                line.append(" (after ").append(String.join(", ", after)).append(')');
            }
            List<String> names = new ArrayList<>();
            for (int c = batchFirstChunk[b]; c < batchFirstChunk[b + 1]; c++) {
                for (int p = chunkFirstPass[c]; p < chunkEndPass[c]; p++) {
                    names.add(order[p].name);
                }
            }
            out.add(line.append(": ").append(String.join(", ", names)).toString());
        }
        return out;
    }

    /** How many command buffers {@link #submit} records each frame: the chunks of all batches. */
    public int chunks() {
        return chunkBatch.length;
    }

    /** The transient images in the pool, for inspection. */
    public int pooledImages() {
        return pool.size();
    }

    /** The transient buffers in the pool, for inspection. */
    public int pooledBuffers() {
        return bufferPool.size();
    }

    /** How many times the graph was built. */
    public int builds() {
        return builds;
    }

    // ---------------------------------------------------------------- building

    @AllowAllocation("building the graph, when it changes")
    private void build() {
        GraphBuilder g = new GraphBuilder(this, techniques, implementations);
        List<Image> results = new ArrayList<>();
        for (Key key : outputs.keySet()) {
            results.add(g.require(key));
        }
        // An output whose image fits its target (size, format, usage; not sampled or a storage image) is drawn
        // straight into it; others are blitted.
        int[] substitute = new int[g.images.size()];
        Arrays.fill(substitute, -1);
        boolean[] needed = new boolean[g.images.size() + outputs.size()];
        int k = 0;
        for (ExternalImage target : outputs.values()) {
            Image result = results.get(k++);
            ImageInfo info = g.images.get(result.id);
            boolean fits = info.external == null && substitute[result.id] < 0 && result.width == target.width
                && result.height == target.height && result.format == target.format
                && (info.usage & ~target.usage) == 0
                && (info.usage & (VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) == 0; // no slots for externals
            Image external = g.external(target);
            if (fits) {
                substitute = Arrays.copyOf(substitute, g.images.size());
                substitute[result.id] = external.id;
            } else {
                g.pass("output " + target.name)
                    .read(result, Access.TRANSFER_SRC)
                    .write(external, Access.TRANSFER_DST)
                    .record(ctx -> blit(ctx, result, external));
            }
            needed = Arrays.copyOf(needed, g.images.size());
            needed[fits ? result.id : external.id] = true;
        }
        int imageCount = g.images.size();
        substitute = Arrays.copyOf(substitute, imageCount);
        needed = Arrays.copyOf(needed, imageCount);
        // Culled: walking back from the outputs, a pass is needed if it writes an image or buffer needed after it.
        List<PassInfo> passes = g.passes;
        boolean[] neededBuffer = new boolean[g.buffers.size()];
        for (int b = 0; b < neededBuffer.length; b++) {
            neededBuffer[b] = g.buffers.get(b).external != null; // outlives the frame: its writes are kept
        }
        for (History history : g.histories) {
            needed[history.current.id] = true; // the next frame reads it
        }
        boolean[] keep = new boolean[passes.size()];
        for (int p = passes.size() - 1; p >= 0; p--) {
            PassInfo pass = passes.get(p);
            boolean writesNeeded = pass.sideEffects;
            for (int a = 0; a < pass.images.length; a++) {
                writesNeeded |= pass.accesses[a].writes && needed[pass.images[a]];
            }
            for (int a = 0; a < pass.buffers.length; a++) {
                writesNeeded |= pass.bufferAccesses[a].writes && neededBuffer[pass.buffers[a]];
            }
            if (writesNeeded) {
                keep[p] = true;
                for (int image : pass.images) {
                    needed[image] = true;
                }
                for (int buffer : pass.buffers) {
                    neededBuffer[buffer] = true;
                }
            }
        }
        List<PassInfo> kept = new ArrayList<>();
        for (int p = 0; p < passes.size(); p++) {
            if (keep[p]) {
                kept.add(passes.get(p));
            }
        }
        PassInfo[] newOrder = kept.toArray(new PassInfo[0]);
        // The queue of each pass: async ones on the compute queue, when async compute is on.
        int[] queues = new int[newOrder.length];
        for (int p = 0; p < newOrder.length; p++) {
            PassInfo pass = newOrder[p];
            if (pass.async) {
                for (int a = 0; a < pass.images.length; a++) {
                    Access access = pass.accesses[a];
                    if (access == Access.COLOR_ATTACHMENT || access == Access.DEPTH_ATTACHMENT
                        || access == Access.DEPTH_TEST || access == Access.SAMPLED_FRAGMENT) {
                        throw new IllegalStateException(pass.name + " is async but uses "
                            + g.images.get(pass.images[a]).image.name + " as " + access
                            + ": async passes compute, sample, store and copy only");
                    }
                    if (g.images.get(pass.images[a]).external != null) {
                        throw new IllegalStateException(pass.name + " is async but uses the external image "
                            + g.images.get(pass.images[a]).image.name);
                    }
                }
                for (int a = 0; a < pass.buffers.length; a++) {
                    if (g.buffers.get(pass.buffers[a]).external != null) {
                        throw new IllegalStateException(pass.name + " is async but uses the external buffer "
                            + g.buffers.get(pass.buffers[a]).buffer.name);
                    }
                    if (pass.bufferAccesses[a].draws) {
                        throw new IllegalStateException(pass.name + " is async but uses "
                            + g.buffers.get(pass.buffers[a]).buffer.name + " as " + pass.bufferAccesses[a]
                            + ": async passes compute, store and copy only");
                    }
                }
                queues[p] = asyncCompute ? COMPUTE : GRAPHICS;
            }
        }
        // Where images live: external ones (and outputs drawn into their target) in their external image, transient
        // ones in physical images, shared by images of one size and format whose lifetimes do not overlap.
        List<ExternalImage> newExternals = new ArrayList<>();
        int[] newTargetOf = new int[imageCount];
        int[] first = new int[imageCount];
        int[] last = new int[imageCount];
        int[] queueMask = new int[imageCount];
        Arrays.fill(first, -1);
        for (int i = 0; i < newOrder.length; i++) {
            for (int image : newOrder[i].images) {
                if (first[image] < 0) {
                    first[image] = i;
                }
                last[image] = i;
                queueMask[image] |= 1 << queues[i];
            }
        }
        List<Slot> slots = new ArrayList<>();
        for (int image = 0; image < imageCount; image++) {
            newTargetOf[image] = Integer.MIN_VALUE;
        }
        Integer[] byFirstUse = new Integer[imageCount];
        for (int i = 0; i < imageCount; i++) {
            byFirstUse[i] = i;
        }
        Arrays.sort(byFirstUse, (a, b) -> Integer.compare(first[a], first[b]));
        boolean twoFamilies = graphicsQueue.family() != computeQueue.family();
        for (int image : byFirstUse) {
            if (first[image] < 0) {
                continue; // culled
            }
            ImageInfo info = g.images.get(image);
            if (info.history != null) {
                continue; // kept across frames: below
            }
            ExternalImage external = info.external != null ? info.external
                : substitute[image] >= 0 ? g.images.get(substitute[image]).external : null;
            if (external != null) {
                if ((info.usage & ~external.usage) != 0) {
                    throw new IllegalStateException("External image " + external.name + " is used beyond the usage "
                        + "it was created with");
                }
                if ((info.usage & (VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) != 0) {
                    throw new IllegalStateException("External image " + external.name + " is sampled or a storage "
                        + "image; only attachment and transfer uses are supported");
                }
                int index = newExternals.indexOf(external);
                if (index < 0) {
                    index = newExternals.size();
                    newExternals.add(external);
                }
                newTargetOf[image] = ~index;
                continue;
            }
            Slot slot = null;
            for (Slot candidate : slots) {
                // Only between images of one queue: sharing memory with work on the other queue would make each wait
                // for the other, and async compute would no longer run beside graphics.
                if (candidate.last < first[image] && candidate.width == info.image.width
                    && candidate.height == info.image.height && candidate.format == info.image.format
                    && candidate.mips == info.image.mips
                    && candidate.queues == queueMask[image] && queueMask[image] != 3) {
                    slot = candidate;
                    break;
                }
            }
            if (slot == null) {
                slot = new Slot(info.image.width, info.image.height, info.image.format, info.image.mips);
                slots.add(slot);
            }
            slot.last = last[image];
            slot.usage |= info.usage;
            slot.queues |= queueMask[image];
            newTargetOf[image] = slots.indexOf(slot);
        }
        for (Slot slot : slots) {
            slot.shared = slot.queues == 3 && twoFamilies;
        }
        Physical[] claimed = claim(slots);
        // Histories: a pair of images each, kept across builds while the name, size and format stay.
        int historyCount = g.histories.size();
        int[] historyCurrent = new int[historyCount];
        int[] historyPrevious = new int[historyCount];
        int[] previousImages = new int[historyCount];
        HistoryPair[] pairs = new HistoryPair[historyCount];
        List<Physical> all = new ArrayList<>(Arrays.asList(claimed));
        Map<String, HistoryPair> old = new HashMap<>(histories);
        histories.clear();
        if (!old.isEmpty() || historyCount > 0) {
            device.waitIdle(); // their contents start over, and replaced ones are closed
        }
        for (int h = 0; h < historyCount; h++) {
            History history = g.histories.get(h);
            ImageInfo current = g.images.get(history.current.id);
            ImageInfo previous = g.images.get(history.previous.id);
            Slot slot = new Slot(current.image.width, current.image.height, current.image.format, 1);
            slot.usage = current.usage | previous.usage;
            slot.shared = (queueMask[current.image.id] | queueMask[previous.image.id]) == 3 && twoFamilies;
            HistoryPair pair = old.remove(history.name);
            if (pair == null || !pair.fits(slot)) {
                if (pair != null) {
                    pair.close();
                }
                pair = new HistoryPair(slot);
            }
            histories.put(history.name, pair);
            pairs[h] = pair;
            history.index = h;
            historyCurrent[h] = all.size();
            all.add(pair.current);
            historyPrevious[h] = all.size();
            all.add(pair.previous);
            newTargetOf[current.image.id] = historyCurrent[h];
            newTargetOf[previous.image.id] = historyPrevious[h];
            previousImages[h] = previous.image.id;
        }
        for (HistoryPair unused : old.values()) {
            unused.close();
        }
        Physical[] newPhysicals = all.toArray(new Physical[0]);
        // Where buffers live: in physical buffers, shared (sized for the largest) by buffers of one queue whose
        // lifetimes do not overlap.
        int bufferCount = g.buffers.size();
        int[] bufferFirst = new int[bufferCount];
        int[] bufferLast = new int[bufferCount];
        int[] bufferQueues = new int[bufferCount];
        Arrays.fill(bufferFirst, -1);
        for (int i = 0; i < newOrder.length; i++) {
            for (int buffer : newOrder[i].buffers) {
                if (bufferFirst[buffer] < 0) {
                    bufferFirst[buffer] = i;
                }
                bufferLast[buffer] = i;
                bufferQueues[buffer] |= 1 << queues[i];
            }
        }
        Integer[] buffersByFirstUse = new Integer[bufferCount];
        for (int i = 0; i < bufferCount; i++) {
            buffersByFirstUse[i] = i;
        }
        Arrays.sort(buffersByFirstUse, (a, b) -> Integer.compare(bufferFirst[a], bufferFirst[b]));
        int[] newBufferTargetOf = new int[bufferCount];
        Arrays.fill(newBufferTargetOf, -1);
        List<BufferSlot> bufferSlots = new ArrayList<>();
        List<ExternalBuffer> newExternalBuffers = new ArrayList<>();
        for (int buffer : buffersByFirstUse) {
            if (bufferFirst[buffer] < 0) {
                continue; // culled
            }
            BufferInfo info = g.buffers.get(buffer);
            if (info.external != null) {
                newBufferTargetOf[buffer] = ~newExternalBuffers.size(); // not pooled: lives where it is
                newExternalBuffers.add(info.external);
                continue;
            }
            BufferSlot slot = null;
            for (BufferSlot candidate : bufferSlots) {
                if (candidate.last < bufferFirst[buffer] && candidate.queues == bufferQueues[buffer]
                    && bufferQueues[buffer] != 3) {
                    slot = candidate;
                    break;
                }
            }
            if (slot == null) {
                slot = new BufferSlot();
                bufferSlots.add(slot);
            }
            slot.size = Math.max(slot.size, info.buffer.size);
            slot.last = bufferLast[buffer];
            slot.usage |= info.usage;
            slot.queues |= bufferQueues[buffer];
            slot.shared = slot.queues == 3 && twoFamilies;
            newBufferTargetOf[buffer] = bufferSlots.indexOf(slot);
        }
        PhysicalBuffer[] newPhysicalBuffers = claimBuffers(bufferSlots);
        // Barriers, now that every image has a home.
        // The first walk learns the state a frame ends in, which is the next one's start.
        Barriers barriers = new Barriers(newPhysicals.length, newExternals, newPhysicalBuffers.length,
            newExternalBuffers.size());
        barriers.histories(historyCurrent, historyPrevious, previousImages);
        barriers.simulate(newOrder, queues, newTargetOf, newBufferTargetOf, g, false);
        barriers.simulate(newOrder, queues, newTargetOf, newBufferTargetOf, g, true);

        order = newOrder;
        targetOf = newTargetOf;
        physicals = newPhysicals;
        bufferTargetOf = newBufferTargetOf;
        physicalBuffers = newPhysicalBuffers;
        externals = newExternals.toArray(new ExternalImage[0]);
        externalVersions = new int[externals.length];
        for (int i = 0; i < externals.length; i++) {
            externalVersions[i] = externals[i].version;
        }
        externalBuffers = newExternalBuffers.toArray(new ExternalBuffer[0]);
        externalBufferVersions = new int[externalBuffers.length];
        for (int i = 0; i < externalBuffers.length; i++) {
            externalBufferVersions[i] = externalBuffers[i].version;
        }
        barriers.store();
        startHistories(pairs, barriers);
        schedule(queues, barriers.waitPass, barriers.firstExternalPass);
        dirty = false;
        builds++;
        LOG.debug("Frame graph built: {} of {} passes in {} batches, {} images in {} pooled, {} buffers in {} pooled, "
            + "{} barriers", order.length, passes.size(), batchQueue.length, imageCount, physicals.length, bufferCount,
            physicalBuffers.length, barrierTarget.length);
    }

    /**
     * Splits the passes into batches: a new one where the queue changes, where a pass waits for more of the other queue
     * than its batch does, and at the first pass using an external image (which waits for it to be acquired), so work
     * before it runs without waiting. Each batch is cut into chunks of {@link #grain} passes, recorded in parallel.
     */
    @AllowAllocation("building the graph, when it changes")
    private void schedule(int[] queues, int[] waitPass, int firstExternalPass) {
        List<int[]> batches = new ArrayList<>(); // queue, first pass, end pass, wait (other queue's ordinal), acquire
        int[] passBatch = new int[order.length];
        int[] ordinals = new int[2];
        List<Integer> batchOrdinals = new ArrayList<>();
        for (int p = 0; p < order.length; p++) {
            int queue = queues[p];
            int wait = waitPass[p] < 0 ? 0 : batchOrdinals.get(passBatch[waitPass[p]]);
            boolean acquire = p == firstExternalPass;
            int[] current = batches.isEmpty() ? null : batches.getLast();
            if (current == null || current[0] != queue || wait > current[3] || acquire) {
                batches.add(new int[] {queue, p, p, wait, acquire ? 1 : 0});
                batchOrdinals.add(++ordinals[queue]);
                current = batches.getLast();
            }
            current[2] = p + 1;
            passBatch[p] = batches.size() - 1;
        }
        int lastGraphics = -1;
        for (int b = 0; b < batches.size(); b++) {
            lastGraphics = batches.get(b)[0] == GRAPHICS ? b : lastGraphics;
        }
        if (lastGraphics < 0) {
            // Nothing on the graphics queue: an empty batch there still signals and waits as asked.
            batches.add(new int[] {GRAPHICS, order.length, order.length, 0, 0});
            batchOrdinals.add(++ordinals[GRAPHICS]);
            lastGraphics = batches.size() - 1;
        }
        int n = batches.size();
        batchQueue = new int[n];
        batchOrdinal = new int[n];
        batchWait = new int[n];
        batchAcquire = new boolean[n];
        batchFirstChunk = new int[n + 1];
        List<int[]> chunks = new ArrayList<>(); // batch, first pass, end pass
        for (int b = 0; b < n; b++) {
            int[] batch = batches.get(b);
            batchQueue[b] = batch[0];
            batchOrdinal[b] = batchOrdinals.get(b);
            batchWait[b] = batch[3];
            batchAcquire[b] = batch[4] != 0;
            batchFirstChunk[b] = chunks.size();
            for (int from = batch[1]; from < batch[2]; from += grain) {
                chunks.add(new int[] {b, from, Math.min(batch[2], from + grain)});
            }
            if (b == lastGraphics && (chunks.isEmpty() || chunks.getLast()[0] != b)) {
                chunks.add(new int[] {b, batch[2], batch[2]}); // only the outputs' final barriers
            }
        }
        batchFirstChunk[n] = chunks.size();
        lastGraphicsBatch = lastGraphics;
        graphicsBatches = ordinals[GRAPHICS];
        computeBatches = ordinals[COMPUTE];
        chunkBatch = new int[chunks.size()];
        chunkFirstPass = new int[chunks.size()];
        chunkEndPass = new int[chunks.size()];
        for (int c = 0; c < chunks.size(); c++) {
            chunkBatch[c] = chunks.get(c)[0];
            chunkFirstPass[c] = chunks.get(c)[1];
            chunkEndPass[c] = chunks.get(c)[2];
        }
        firstGraphicsChunk = 0;
        while (batchQueue[chunkBatch[firstGraphicsChunk]] != GRAPHICS) {
            firstGraphicsChunk++; // there is one: the last graphics batch has a chunk at least
        }
        chunkCommands = new VkCommandBuffer[chunks.size()];
        graphicsChunks = 0;
        computeChunks = 0;
        for (int batch : chunkBatch) {
            if (batchQueue[batch] == GRAPHICS) {
                graphicsChunks++;
            } else {
                computeChunks++;
            }
        }
    }

    /**
     * Puts each history's images in the layouts a frame starts with (their contents undefined: the first frame has no
     * last frame), and notes that frame.
     */
    @AllowAllocation("building the graph, when it changes")
    private void startHistories(HistoryPair[] pairs, Barriers barriers) {
        activeHistories = pairs;
        if (pairs.length == 0) {
            return;
        }
        device.submitAndWait(graphicsQueue, commands -> {
            for (int h = 0; h < pairs.length; h++) {
                start(commands, pairs[h].current, barriers.startCurrent[h]);
                start(commands, pairs[h].previous, barriers.startPrevious[h]);
                pairs[h].firstFrame = frame + 1;
            }
        });
    }

    private static void start(VkCommandBuffer commands, Physical physical, int layout) {
        if (layout != VK_IMAGE_LAYOUT_UNDEFINED) {
            com.truesim.engine.vulkan.Images.transition(commands, physical.image.handle(), aspect(physical.format),
                VK_IMAGE_LAYOUT_UNDEFINED, layout, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, 0,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        }
    }

    /** Whether history {@code index}'s last frame image holds what that frame wrote. */
    boolean historyValid(int index) {
        return frame > activeHistories[index].firstFrame;
    }

    /** The two images of a history, whose roles swap every frame. */
    @AllowAllocation("building the graph, when it changes")
    private final class HistoryPair {
        final Physical current;
        final Physical previous;
        long firstFrame;

        HistoryPair(Slot slot) {
            current = new Physical(slot);
            previous = new Physical(slot);
        }

        boolean fits(Slot slot) {
            return current.width == slot.width && current.height == slot.height && current.format == slot.format
                && current.usage == slot.usage && current.shared == slot.shared;
        }

        void close() {
            current.close();
            previous.close();
        }
    }

    /** Takes pooled images for the slots, creating the missing ones; pooled images left over retire. */
    @AllowAllocation("building the graph, when it changes")
    private Physical[] claim(List<Slot> slots) {
        Physical[] claimed = new Physical[slots.size()];
        List<Physical> free = new ArrayList<>(pool);
        for (int s = 0; s < slots.size(); s++) {
            Slot slot = slots.get(s);
            for (Physical candidate : free) {
                if (candidate.width == slot.width && candidate.height == slot.height
                    && candidate.format == slot.format && candidate.mips == slot.mips && candidate.usage == slot.usage
                    && candidate.shared == slot.shared) {
                    claimed[s] = candidate;
                    free.remove(candidate);
                    break;
                }
            }
            if (claimed[s] == null) {
                claimed[s] = new Physical(slot);
                pool.add(claimed[s]);
            }
        }
        for (Physical unused : free) {
            pool.remove(unused);
            unused.retireAt = frame + framesInFlight;
            retired.add(unused);
        }
        return claimed;
    }

    /**
     * Takes pooled buffers for the slots, the smallest that fits and is at most twice the size, creating the missing
     * ones; pooled buffers left over retire.
     */
    @AllowAllocation("building the graph, when it changes")
    private PhysicalBuffer[] claimBuffers(List<BufferSlot> slots) {
        PhysicalBuffer[] claimed = new PhysicalBuffer[slots.size()];
        List<PhysicalBuffer> free = new ArrayList<>(bufferPool);
        for (int s = 0; s < slots.size(); s++) {
            BufferSlot slot = slots.get(s);
            for (PhysicalBuffer candidate : free) {
                if (candidate.size >= slot.size && candidate.size / 2 <= slot.size && candidate.usage == slot.usage
                    && candidate.shared == slot.shared
                    && (claimed[s] == null || candidate.size < claimed[s].size)) {
                    claimed[s] = candidate;
                }
            }
            if (claimed[s] == null) {
                claimed[s] = new PhysicalBuffer(slot);
                bufferPool.add(claimed[s]);
            } else {
                free.remove(claimed[s]);
            }
        }
        for (PhysicalBuffer unused : free) {
            bufferPool.remove(unused);
            unused.retireAt = frame + framesInFlight;
            retiredBuffers.add(unused);
        }
        return claimed;
    }

    /** Destroys retired images and buffers whose frames have finished (or all, when closing). */
    private void release(boolean all) {
        for (int i = retiredBuffers.size() - 1; i >= 0; i--) {
            PhysicalBuffer physical = retiredBuffers.get(i);
            if (all || physical.retireAt <= frame) {
                physical.buffer.close();
                retiredBuffers.remove(i);
            }
        }
        for (int i = retired.size() - 1; i >= 0; i--) {
            Physical physical = retired.get(i);
            if (all || physical.retireAt <= frame) {
                physical.close();
                retired.remove(i);
            }
        }
    }

    private void blit(PassContext ctx, Image from, Image to) {
        try (MemoryStack stack = stackPush()) {
            long region = stack.ncalloc(VkImageBlit.ALIGNOF, 1, VkImageBlit.SIZEOF);
            layers(region + VkImageBlit.SRCSUBRESOURCE);
            layers(region + VkImageBlit.DSTSUBRESOURCE);
            offsets(region + VkImageBlit.SRCOFFSETS, from.width, from.height);
            offsets(region + VkImageBlit.DSTOFFSETS, to.width, to.height);
            boolean same = from.width == to.width && from.height == to.height;
            device.direct().cmdBlitImage(ctx.commands(), ctx.image(from), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                ctx.image(to), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, region,
                same ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
        }
    }

    private static void layers(long subresource) {
        memPutInt(subresource + VkImageSubresourceLayers.ASPECTMASK, VK_IMAGE_ASPECT_COLOR_BIT);
        memPutInt(subresource + VkImageSubresourceLayers.LAYERCOUNT, 1);
    }

    private static void offsets(long offsets, int width, int height) {
        // offsets[0] = (0, 0, 0), offsets[1] = (width, height, 1); VkOffset3D is three ints
        memPutInt(offsets + 12, width);
        memPutInt(offsets + 16, height);
        memPutInt(offsets + 20, 1);
    }

    static int aspect(int format) {
        return switch (format) {
            case VK_FORMAT_D16_UNORM, VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D32_SFLOAT -> VK_IMAGE_ASPECT_DEPTH_BIT;
            case VK_FORMAT_D16_UNORM_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT_S8_UINT ->
                VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
            default -> VK_IMAGE_ASPECT_COLOR_BIT;
        };
    }

    @Override
    public void close() {
        if (execution != null) {
            execution.close(); // waits for the frames submitted
            execution = null;
        }
        for (AutoCloseable object : cache.values()) {
            try {
                object.close();
            } catch (Exception e) {
                throw new IllegalStateException("Could not close " + object, e);
            }
        }
        cache.clear();
        for (Physical physical : pool) {
            physical.close();
        }
        pool.clear();
        for (PhysicalBuffer physical : bufferPool) {
            physical.buffer.close();
        }
        bufferPool.clear();
        for (HistoryPair pair : histories.values()) {
            pair.close();
        }
        histories.clear();
        activeHistories = new HistoryPair[0];
        release(true);
        physicals = new Physical[0];
        physicalBuffers = new PhysicalBuffer[0];
        order = new PassInfo[0];
        barrierStart = new int[] {0, 0};
        dirty = true;
    }

    /** A physical image the pool is to have: a size, a format and every usage of the images sharing it. */
    private static final class Slot {
        final int width;
        final int height;
        final int format;
        final int mips;
        int usage;
        int queues;
        boolean shared; // used by both queues, of different families: concurrent sharing
        int last;

        Slot(int width, int height, int format, int mips) {
            this.width = width;
            this.height = height;
            this.format = format;
            this.mips = mips;
        }
    }

    /** A physical buffer the pool is to have: the largest size and every usage of the buffers sharing it. */
    private static final class BufferSlot {
        long size;
        int usage;
        int queues;
        boolean shared;
        int last;
    }

    /** A pooled buffer, reachable by device address. */
    @AllowAllocation("building the graph, when it changes")
    private final class PhysicalBuffer {
        final GpuBuffer buffer;
        final long size;
        final int usage;
        final boolean shared;
        long retireAt;

        PhysicalBuffer(BufferSlot slot) {
            size = slot.size;
            usage = slot.usage;
            shared = slot.shared;
            int all = usage | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
            buffer = shared ? GpuBuffer.deviceLocal(device, size, all, graphicsQueue.family(), computeQueue.family())
                : GpuBuffer.deviceLocal(device, size, all);
        }
    }

    /** A pooled image with its bindless slots. */
    @AllowAllocation("building the graph, when it changes")
    private final class Physical {
        GpuImage image; // these three swap between a history's two images
        final int width;
        final int height;
        final int format;
        final int usage;
        final boolean shared;
        final int mips;
        int sampled;
        int[] storage; // a slot per mip level
        long retireAt;

        Physical(Slot slot) {
            width = slot.width;
            height = slot.height;
            format = slot.format;
            usage = slot.usage;
            shared = slot.shared;
            mips = slot.mips;
            image = shared ? GpuImage.mipmapped(device, width, height, format, usage, aspect(format), mips,
                graphicsQueue.family(), computeQueue.family())
                : GpuImage.mipmapped(device, width, height, format, usage, aspect(format), mips);
            sampled = (usage & VK_IMAGE_USAGE_SAMPLED_BIT) != 0 ? bindless.addSampled(image.view()) : -1;
            storage = new int[(usage & VK_IMAGE_USAGE_STORAGE_BIT) != 0 ? mips : 0];
            for (int mip = 0; mip < storage.length; mip++) {
                storage[mip] = bindless.addStorage(image.view(mip));
            }
        }

        /** Exchanges images (and their slots) with {@code other}: a history's roles, frame to frame. */
        void swap(Physical other) {
            GpuImage i = image;
            image = other.image;
            other.image = i;
            int s = sampled;
            sampled = other.sampled;
            other.sampled = s;
            int[] st = storage;
            storage = other.storage;
            other.storage = st;
        }

        void close() {
            if (sampled >= 0) {
                bindless.removeSampled(sampled);
            }
            for (int slot : storage) {
                bindless.removeStorage(slot);
            }
            image.close();
        }
    }

    /**
     * Derives barriers by walking the passes in order and tracking, per physical or external image and physical buffer
     * (whose layout never changes), its layout, the
     * stages and accesses that last wrote it, the stages that read it since, what is already visible, and which queue
     * used it last. A pass's barriers are batched. Crossing to the other queue, a barrier after all commands follows
     * the batch's semaphore wait. Along the way it notes, per pass, the latest pass of the other queue it depends on
     * (for the batch's wait), and the first pass using an external image (which waits for it to be acquired).
     */
    @AllowAllocation("building the graph, when it changes")
    private final class Barriers {
        final int physicalCount;
        final List<ExternalImage> externalList;
        final int bufferCount;          // pooled buffers, after the images' states
        final int externalBufferCount;  // external buffers, after the pooled ones
        final int[] layout;
        final long[] writeStages;
        final long[] writeAccess;
        final long[] readStages;
        final long[] visibleStages;
        final long[] visibleAccess;
        final int[] tenant;
        final int[] lastQueue;
        final int[] lastWritePass;
        final int[] lastWriteQueue;
        final int[][] lastReadPass;
        int[] waitPass = new int[0];
        int firstExternalPass = -1;
        // target, aspect, old layout, new layout, srcStage, srcAccess, dstStage, dstAccess, kind
        final List<long[]> out = new ArrayList<>();
        int[] historyCurrent = new int[0];
        int[] historyPrevious = new int[0];
        int[] previousImages = new int[0];
        int[] startCurrent = new int[0];
        int[] startPrevious = new int[0];
        final List<Integer> starts = new ArrayList<>();

        Barriers(int physicalCount, List<ExternalImage> externalList, int bufferCount, int externalBufferCount) {
            this.physicalCount = physicalCount;
            this.externalList = externalList;
            this.bufferCount = bufferCount;
            this.externalBufferCount = externalBufferCount;
            int n = physicalCount + externalList.size() + bufferCount + externalBufferCount;
            layout = new int[n];
            writeStages = new long[n];
            writeAccess = new long[n];
            readStages = new long[n];
            visibleStages = new long[n];
            visibleAccess = new long[n];
            tenant = new int[n];
            lastQueue = new int[n];
            lastWritePass = new int[n];
            lastWriteQueue = new int[n];
            lastReadPass = new int[n][2];
            Arrays.fill(lastQueue, -1);
        }

        /** The histories' physical images, by role, and the images of the previous role. */
        void histories(int[] current, int[] previous, int[] images) {
            historyCurrent = current;
            historyPrevious = previous;
            previousImages = images;
            startCurrent = new int[current.length];
            startPrevious = new int[current.length];
        }

        /** Exchanges what is known of physical images {@code a} and {@code b}. */
        private void swapState(int a, int b) {
            swap(layout, a, b);
            swap(writeStages, a, b);
            swap(writeAccess, a, b);
            swap(readStages, a, b);
            swap(visibleStages, a, b);
            swap(visibleAccess, a, b);
            swap(lastQueue, a, b);
        }

        private static void swap(int[] array, int a, int b) {
            int t = array[a];
            array[a] = array[b];
            array[b] = t;
        }

        private static void swap(long[] array, int a, int b) {
            long t = array[a];
            array[a] = array[b];
            array[b] = t;
        }

        private int index(int target) {
            return target >= 0 ? target : physicalCount + ~target;
        }

        /** Walks the passes; with {@code emit} off, only to learn the state a frame leaves its images in. */
        void simulate(PassInfo[] passes, int[] queues, int[] targets, int[] bufferTargets, GraphBuilder g,
            boolean emit) {
            for (int e = 0; e < externalList.size(); e++) {
                ExternalImage external = externalList.get(e);
                int i = physicalCount + e;
                layout[i] = external.initialLayout;
                writeStages[i] = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT; // whatever came before the frame
                writeAccess[i] = external.initialLayout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_2_MEMORY_WRITE_BIT;
                readStages[i] = 0;
                visibleStages[i] = 0;
                visibleAccess[i] = 0;
                lastQueue[i] = GRAPHICS;
            }
            for (int e = 0; e < externalBufferCount; e++) {
                int i = physicalCount + externalList.size() + bufferCount + e;
                writeStages[i] = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT; // whatever came before the frame wrote it
                writeAccess[i] = VK_ACCESS_2_MEMORY_WRITE_BIT;
                readStages[i] = 0;
                visibleStages[i] = 0;
                visibleAccess[i] = 0;
                lastQueue[i] = GRAPHICS;
            }
            Arrays.fill(tenant, -1); // transient contents never outlive their frame
            for (int h = 0; h < historyCurrent.length; h++) {
                if (emit) {
                    // The frame boundary: last frame's current image is this frame's previous one.
                    swapState(historyCurrent[h], historyPrevious[h]);
                    startCurrent[h] = layout[historyCurrent[h]];
                    startPrevious[h] = layout[historyPrevious[h]];
                }
                tenant[historyPrevious[h]] = previousImages[h]; // its contents carry over
            }
            Arrays.fill(lastWritePass, -1);
            for (int[] reads : lastReadPass) {
                Arrays.fill(reads, -1);
            }
            waitPass = new int[passes.length];
            Arrays.fill(waitPass, -1);
            firstExternalPass = -1;
            for (int p = 0; p < passes.length; p++) {
                PassInfo pass = passes[p];
                if (emit) {
                    starts.add(out.size());
                }
                // One merged access per image of the pass.
                int[] seen = new int[pass.images.length];
                int distinct = 0;
                for (int a = 0; a < pass.images.length; a++) {
                    int image = pass.images[a];
                    boolean repeat = false;
                    for (int s = 0; s < distinct; s++) {
                        repeat |= seen[s] == image;
                    }
                    if (repeat) {
                        continue;
                    }
                    seen[distinct++] = image;
                    long stages = 0;
                    long access = 0;
                    boolean writes = false;
                    int wanted = pass.accesses[a].layout;
                    for (int b = a; b < pass.images.length; b++) {
                        if (pass.images[b] == image) {
                            Access other = pass.accesses[b];
                            if (other.layout != wanted) {
                                throw new IllegalStateException(pass.name + " uses " + g.images.get(image).image.name
                                    + " in two layouts: " + pass.accesses[a] + " and " + other);
                            }
                            stages |= other.stages;
                            access |= other.access;
                            writes |= other.writes;
                        }
                    }
                    if (targets[image] < 0 && firstExternalPass < 0) {
                        firstExternalPass = p;
                    }
                    int target = targets[image];
                    access(pass, p, queues[p], index(target), IMAGE, target, aspect(g.images.get(image).image.format),
                        image, g.images.get(image).image.name, target >= 0, stages, access, writes, wanted, emit);
                }
                // And one per buffer.
                int[] seenBuffers = new int[pass.buffers.length];
                int distinctBuffers = 0;
                for (int a = 0; a < pass.buffers.length; a++) {
                    int buffer = pass.buffers[a];
                    boolean repeat = false;
                    for (int s = 0; s < distinctBuffers; s++) {
                        repeat |= seenBuffers[s] == buffer;
                    }
                    if (repeat) {
                        continue;
                    }
                    seenBuffers[distinctBuffers++] = buffer;
                    long stages = 0;
                    long access = 0;
                    boolean writes = false;
                    for (int b = a; b < pass.buffers.length; b++) {
                        if (pass.buffers[b] == buffer) {
                            stages |= pass.bufferAccesses[b].stages;
                            access |= pass.bufferAccesses[b].access;
                            writes |= pass.bufferAccesses[b].writes;
                        }
                    }
                    int target = bufferTargets[buffer];
                    int state = physicalCount + externalList.size() + (target >= 0 ? target : bufferCount + ~target);
                    access(pass, p, queues[p], state, BUFFER, target, 0, buffer, g.buffers.get(buffer).buffer.name,
                        target >= 0, stages, access, writes, 0, emit);
                }
            }
            if (emit) {
                starts.add(out.size());
                for (int e = 0; e < externalList.size(); e++) {
                    int i = physicalCount + e;
                    ExternalImage external = externalList.get(e);
                    // Towards whatever comes after the frame.
                    out.add(new long[] {~e, aspect(external.format), layout[i], external.finalLayout,
                        writeStages[i] | readStages[i], writeAccess[i], VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                        VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT, IMAGE});
                }
            }
        }

        /**
         * Pass {@code p}'s merged access to the image or buffer {@code id} ({@code name}), living at state index
         * {@code i} and barrier target {@code target} of {@code kind}; {@code pooled}: in a physical image or buffer,
         * whose earlier tenants it waits for.
         */
        private void access(PassInfo pass, int p, int queue, int i, int kind, int target, int aspect, int id,
            String name, boolean pooled, long stages, long access, boolean writes, int wanted, boolean emit) {
            if (lastQueue[i] >= 0 && lastQueue[i] != queue) {
                // From the other queue: submitted, its batch's semaphore wait orders and makes visible what came before;
                // recorded into one command buffer, nothing does. A barrier after all commands, making any write
                // visible, is right either way: this queue's stages cannot be named for the other's work.
                writeStages[i] = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
                writeAccess[i] = writeAccess[i] != 0 ? VK_ACCESS_2_MEMORY_WRITE_BIT : 0;
                readStages[i] = 0;
                visibleStages[i] = 0;
                visibleAccess[i] = 0;
            }
            lastQueue[i] = queue;
            boolean newTenant = pooled && tenant[i] != id;
            boolean transition = layout[i] != wanted;
            if (newTenant || writes || transition) {
                depend(p, Math.max(otherQueue(lastWriteQueue[i], queue, lastWritePass[i]),
                    lastReadPass[i][1 - queue]));
                lastWritePass[i] = p;
                lastWriteQueue[i] = queue;
                lastReadPass[i][0] = -1;
                lastReadPass[i][1] = -1;
            } else {
                depend(p, otherQueue(lastWriteQueue[i], queue, lastWritePass[i]));
                lastReadPass[i][queue] = p;
            }
            if (newTenant) {
                // A new image in this physical one: its contents start undefined, after whatever used it before.
                if (!writes) {
                    throw new IllegalStateException(pass.name + " reads " + name + " before any pass writes it");
                }
                long src = writeStages[i] | readStages[i];
                if (emit && (kind == IMAGE || src != 0)) { // a buffer has no layout to start
                    out.add(new long[] {target, aspect, VK_IMAGE_LAYOUT_UNDEFINED, wanted, src, writeAccess[i], stages,
                        access, kind});
                }
                tenant[i] = id;
                set(i, wanted, stages, access, access);
                return;
            }
            if (writes || transition) {
                long src = writeStages[i] | readStages[i];
                if (emit && (src != 0 || transition)) {
                    out.add(new long[] {target, aspect, layout[i], wanted, src, writeAccess[i], stages, access, kind});
                }
                if (writes) {
                    set(i, wanted, stages, access, access);
                } else {
                    // A transition orders like a write: later readers wait for it, later writers for this pass.
                    set(i, wanted, stages, VK_ACCESS_2_MEMORY_WRITE_BIT, access);
                    readStages[i] = stages;
                }
                return;
            }
            // Read after write, in the same layout: one barrier per new reading stage or access, none after that.
            if ((stages & ~visibleStages[i]) != 0 || (access & ~visibleAccess[i]) != 0) {
                if (emit && writeStages[i] != 0) {
                    out.add(new long[] {target, aspect, wanted, wanted, writeStages[i], writeAccess[i], stages,
                        access, kind});
                }
                visibleStages[i] |= stages;
                visibleAccess[i] |= access;
            }
            readStages[i] |= stages;
        }

        /** {@code pass}, if it ran on another queue than {@code queue}; else -1. */
        private int otherQueue(int passQueue, int queue, int pass) {
            return pass >= 0 && passQueue != queue ? pass : -1;
        }

        private void depend(int p, int on) {
            waitPass[p] = Math.max(waitPass[p], on);
        }

        /** After a write (or transition) by {@code stages}: visible to those stages and {@code visible} accesses. */
        private void set(int i, int newLayout, long stages, long access, long visible) {
            layout[i] = newLayout;
            writeStages[i] = stages;
            writeAccess[i] = access;
            readStages[i] = 0;
            visibleStages[i] = stages;
            visibleAccess[i] = visible;
        }

        void store() {
            int n = out.size();
            barrierStart = new int[starts.size() + 1];
            for (int s = 0; s < starts.size(); s++) {
                barrierStart[s] = starts.get(s);
            }
            barrierStart[starts.size()] = n;
            barrierTarget = new int[n];
            barrierKind = new int[n];
            barrierAspect = new int[n];
            barrierOldLayout = new int[n];
            barrierNewLayout = new int[n];
            barrierSrcStage = new long[n];
            barrierSrcAccess = new long[n];
            barrierDstStage = new long[n];
            barrierDstAccess = new long[n];
            for (int b = 0; b < n; b++) {
                long[] barrier = out.get(b);
                barrierTarget[b] = (int) barrier[0];
                barrierKind[b] = (int) barrier[8];
                barrierAspect[b] = (int) barrier[1];
                barrierOldLayout[b] = (int) barrier[2];
                barrierNewLayout[b] = (int) barrier[3];
                barrierSrcStage[b] = barrier[4];
                barrierSrcAccess[b] = barrier[5];
                barrierDstStage[b] = barrier[6];
                barrierDstAccess[b] = barrier[7];
            }
        }
    }
}
