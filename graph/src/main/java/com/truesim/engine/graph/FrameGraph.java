package com.truesim.engine.graph;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_LABEL_EXT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D16_UNORM_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_X8_D24_UNORM_PACK32;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK12.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK12.VK_RESOLVE_MODE_AVERAGE_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_INFO;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;
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

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.GraphBuilder.PassInfo;
import com.truesim.engine.graph.GraphCompiler.HistoryPlan;
import com.truesim.engine.graph.ResourcePool.HistoryPair;
import com.truesim.engine.graph.ResourcePool.Physical;
import com.truesim.engine.graph.ResourcePool.PhysicalBuffer;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Bindless;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.common.jobs.JobSystem;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.Queue;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.Vulkan;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import org.lwjgl.vulkan.VkDebugUtilsLabelEXT;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;
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
    final boolean checkDeclared; // with validation on: pass bodies resolve only the resources their pass declared
    private final Bindless bindless;
    private final int framesInFlight;
    private final FrameSlots frameSlots;
    private final boolean ownsSlots;
    private final List<Technique> techniques = new ArrayList<>();
    private final List<ViewPort> views = new ArrayList<>();
    private final List<Function<ViewPort, ? extends Technique>> perView = new ArrayList<>();
    private Technique[] built = new Technique[0]; // every technique of the last build, the views' too
    private long[] builtVersions = new long[0]; // each one's buildVersion() then
    private final Map<Key<Image>, ExternalImage> outputs = new LinkedHashMap<>();
    private final List<Texture> textures = new ArrayList<>();
    private EngineSettings settings = new EngineSettings();
    private EngineSettings.Subscription settingsWatch = watch(settings);
    private Set<String> builtSettings = Set.of(); // the full names of the settings the built graph read
    private Retiring retiring; // removed textures, until no frame in flight samples them
    private final Map<Class<?>, Implementation<?, ?>> implementations = new HashMap<>();
    private final Map<Object, AutoCloseable> cache = new LinkedHashMap<>();
    private final ResourcePool resources; // the images and buffers kept across builds
    private HistoryPair[] activeHistories = new HistoryPair[0];
    private static final Physical[] NO_PHYSICALS = new Physical[0];
    // The seam: transitions joining the histories to a graph just built, recorded at the start of its first frame.
    private Physical[] seamImages = NO_PHYSICALS;
    private int[] seamFrom = new int[0];
    private int[] seamTo = new int[0];
    private final Queue graphicsQueue;
    private final Queue computeQueue;
    private boolean asyncCompute;
    private JobSystem jobs;
    private int grain = 4;
    private PassContext[] contexts = {new PassContext(this)};
    private final long[] oneAcquired = new long[1]; // submit(long, long)'s, as arrays
    private final long[] oneFinished = new long[1];
    private final JobSystem.RangeTask recordChunks = this::recordChunks;
    private Execution execution;
    private boolean dirty = true;
    private long frame;
    private int builds;
    // Tooling: the frame slot being recorded and the frame each slot last recorded (-1: none since it was read), the
    // readbacks of the build, GPU timings when on, and a debug label per pass where the instance has debug utils.
    private int recordSlot;
    private long[] slotFrame;
    private ReadbackPlan[] readbacks = new ReadbackPlan[0];
    private boolean timingsOn;
    private GpuTimings timings;
    private Arena labelArena;
    private long[] passLabels = new long[0];

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
    private int[] barrierBaseMip = new int[0]; // each barrier's range: -1 counts are the rest
    private int[] barrierMipCount = new int[0];
    private int[] barrierBaseLayer = new int[0];
    private int[] barrierLayerCount = new int[0];
    private int[] barrierHistory = new int[0]; // the history whose image each barrier is on, or -1
    // Per-frame view control: the views each pass runs for (null: every frame), the passes skipped this frame, and the
    // view each history is of (null: the frame's, or shared, so it swaps every frame).
    private ViewPort[][] passViews = new ViewPort[0][];
    private boolean[] passSkipped = new boolean[0];
    private ViewPort[] historyView = new ViewPort[0];
    // The view each external image is an output of, if not a texture (a window's image): untouched while the view does
    // not run, as the presentation engine may hold it.
    private ViewPort[] externalView = new ViewPort[0];
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
    // Recording on several threads: the frame start, recorded first on the calling thread and submitted ahead of the
    // first graphics chunk, so no pass body runs before what it uploads is recorded. Null on one thread.
    private VkCommandBuffer frameStart;

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
        this.checkDeclared = device.gpu().instance().validationEnabled();
        this.bindless = device.bindless();
        this.framesInFlight = framesInFlight;
        this.slotFrame = new long[framesInFlight];
        Arrays.fill(slotFrame, -1);
        this.resources = new ResourcePool(device, framesInFlight);
        this.ownsSlots = shared == null;
        this.frameSlots = ownsSlots ? new FrameSlots(framesInFlight) : shared;
        this.retiring = new Retiring(frameSlots);
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

    /** Removes the technique whose {@link Technique#id() id} is {@code id}, if there is one; the graph rebuilds. */
    public FrameGraph remove(Id<? extends Technique> id) {
        Objects.requireNonNull(id, "id");
        dirty |= techniques.removeIf(t -> t.id() == id);
        return this;
    }

    /**
     * Swaps in {@code technique} for the techniques providing any of its keys, e.g. one lighting technique for another:
     * whoever requires those keys gets the new one. Removes nothing if none does. The graph rebuilds on its next frame.
     */
    public FrameGraph replace(Technique technique) {
        List<Key<?>> keys = technique.provides();
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
    public FrameGraph output(Key<Image> key, ExternalImage target) {
        outputs.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(target, "target"));
        dirty = true;
        return this;
    }

    /** Stops outputting {@code key}. */
    /** Copies the frame's result {@code key} into {@code texture} every frame; the graph rebuilds on its next frame. */
    public FrameGraph output(Key<Image> key, Texture texture) {
        return output(key, texture.external);
    }

    /** The settings this graph's techniques read ({@link GraphBuilder#setting}): every default until set. */
    public EngineSettings settings() {
        return settings;
    }

    /**
     * Makes {@code settings} the ones this graph's techniques read (the app's, loaded from its preferences, say): the
     * graph rebuilds on its next frame, and again whenever a setting its build read changes.
     */
    @AllowAllocation("set up rarely")
    public FrameGraph settings(EngineSettings settings) {
        settingsWatch.close();
        this.settings = Objects.requireNonNull(settings, "settings");
        settingsWatch = watch(settings);
        dirty = true;
        return this;
    }

    /** Rebuilds on the next frame whenever a setting the built graph read changes. */
    @AllowAllocation("set up rarely")
    private EngineSettings.Subscription watch(EngineSettings watched) {
        return watched.onAnyChange(name -> {
            if (builtSettings.contains(name)) {
                dirty = true;
            }
        });
    }

    /** The frame's outputs, by key. */
    Map<Key<Image>, ExternalImage> frameOutputs() {
        return outputs;
    }

    /**
     * A new texture of {@code width} by {@code height} {@code format} texels, kept from frame to frame with a fixed
     * bindless slot, for views to render into ({@link ViewPort#output(Key, Texture)}) and passes to sample; closed with
     * the graph. Waits for the GPU once, to clear it.
     */
    @AllowAllocation("set up rarely")
    public Texture texture(String name, Format format, int width, int height) {
        Texture texture = new Texture(device, Objects.requireNonNull(name, "name"),
            Objects.requireNonNull(format, "format"), width, height);
        textures.add(texture);
        return texture;
    }

    /**
     * Removes {@code texture}, made by {@link #texture}: outputs into it go (the graph rebuilds on its next frame), a
     * build reading it fails, and its image and slot are freed once no frame in flight samples it.
     */
    @AllowAllocation("set up rarely")
    public FrameGraph removeTexture(Texture texture) {
        if (!textures.remove(texture)) {
            return this;
        }
        texture.removed = true;
        outputs.values().removeIf(target -> target == texture.external);
        for (ViewPort view : views) {
            view.outputs.values().removeIf(target -> target == texture.external);
        }
        dirty = true;
        retiring.add(texture::close);
        return this;
    }

    /** The textures made and not removed. */
    @AllowAllocation("inspection")
    public List<Texture> textures() {
        return List.copyOf(textures);
    }

    public FrameGraph removeOutput(Key<Image> key) {
        dirty |= outputs.remove(key) != null;
        return this;
    }

    /** Rebuilds on the next frame, e.g. after a technique changed what it builds. */
    public void invalidate() {
        dirty = true;
    }

    /**
     * A new view of the frame, named {@code name} (unique in the graph): a camera's share of it, with techniques and
     * outputs of its own ({@link ViewPort}). Techniques added to the graph itself are the frame's, shared by every
     * view. The graph rebuilds on its next frame.
     */
    public ViewPort addView(String name) {
        Objects.requireNonNull(name, "name");
        for (ViewPort view : views) {
            if (view.name().equals(name)) {
                throw new IllegalArgumentException("The graph has a view named " + name + " already");
            }
        }
        ViewPort view = new ViewPort(this, name);
        views.add(view);
        for (Function<ViewPort, ? extends Technique> factory : perView) {
            view.add(factory.apply(view));
        }
        dirty = true;
        return view;
    }

    /**
     * Adds the technique {@code factory} makes for each view, to every view: those there now and those added later.
     * Per-view state lives in the instance it makes, e.g. post effects; a view drops one with
     * {@link ViewPort#remove(Id)}. The graph rebuilds on its next frame.
     */
    public FrameGraph addPerView(Function<ViewPort, ? extends Technique> factory) {
        perView.add(Objects.requireNonNull(factory, "factory"));
        for (ViewPort view : views) {
            view.add(factory.apply(view));
        }
        return this;
    }

    /**
     * Removes {@code view}, its techniques and outputs, and closes what it owns ({@link ViewPort#own}); the graph
     * rebuilds on its next frame.
     */
    public FrameGraph removeView(ViewPort view) {
        if (views.remove(view)) {
            dirty = true;
            view.closeOwned();
        }
        return this;
    }

    /** The views, in the order added. */
    public List<ViewPort> views() {
        return List.copyOf(views);
    }

    /** A view changed its techniques or outputs: the graph rebuilds on its next frame. */
    void changed() {
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
        finished(slot);
        if (ownsSlots) {
            frameSlots.begin(slot);
        }
        return slot;
    }

    /** The GPU finished what frame slot {@code slot} last recorded: its readbacks are delivered, its timings read. */
    private void finished(int slot) {
        long finished = slotFrame[slot];
        if (finished < 0) {
            return;
        }
        slotFrame[slot] = -1;
        if (timings != null) {
            timings.collect(slot, finished);
        }
        for (ReadbackPlan readback : readbacks) {
            readback.deliver(slot);
        }
    }

    /** The frame slot being recorded. */
    int recordSlot() {
        return recordSlot;
    }

    /** The frame being recorded, counted from 1. */
    long frame() {
        return frame;
    }

    /**
     * Turns GPU timings of every pass on or off; on, {@link #timings()} has the last finished frame's. Rebuilds the graph,
     * which then waits for the GPU once, to make the queries.
     */
    public FrameGraph timings(boolean on) {
        if (on != timingsOn) {
            timingsOn = on;
            dirty = true;
        }
        if (on && timings == null) {
            timings = new GpuTimings(device, framesInFlight); // its queries come with the build
        }
        return this;
    }

    /** The passes' GPU timings of the last finished frame, with {@link #timings(boolean)} on; null otherwise. */
    public GpuTimings timings() {
        return timingsOn ? timings : null;
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
        oneAcquired[0] = acquired;
        oneFinished[0] = renderFinished;
        submit(oneAcquired, acquired == 0 ? 0 : 1, oneFinished, renderFinished == 0 ? 0 : 1);
    }

    /**
     * Like {@link #submit(long, long)}, for several windows: the first batch using an external image waits for binary
     * semaphores {@code acquired[0]} to {@code acquired[acquiredCount - 1]}, and the last graphics batch signals
     * {@code renderFinished[0]} to {@code renderFinished[finishedCount - 1]}. Allocates nothing.
     */
    public void submit(long[] acquired, int acquiredCount, long[] renderFinished, int finishedCount) {
        if (execution == null || !execution.begun) {
            throw new IllegalStateException("Begin the frame first: beginFrame()");
        }
        prepare();
        recordSlot = execution.slot;
        slotFrame[recordSlot] = frame;
        execution.ensureCommandBuffers();
        if (jobs != null && chunkBatch.length > 1) {
            frameStart = execution.commandBuffer(0, GRAPHICS);
            bindless.bind(frameStart, true);
            recordSeam(frameStart);
            frameSlots.recordStart(frameStart);
            Vulkan.check(Commands.endCommandBuffer(frameStart), "Could not finish recording");
            jobs.parallelFor(chunkBatch.length, 1, recordChunks);
        } else {
            frameStart = null;
            recordChunks(0, chunkBatch.length, 0);
        }
        execution.submit(acquired, acquiredCount, renderFinished, finishedCount);
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
        finished(0); // the caller waited for the frame before
        prepare();
        recordSlot = 0;
        slotFrame[0] = frame;
        bindless.bind(commands);
        recordSeam(commands);
        frameSlots.recordStart(commands);
        recordPasses(commands, 0, order.length, true, contexts[0]);
        if (open) {
            frameSlots.end();
        }
    }

    private void prepare() {
        for (int v = 0; v < views.size(); v++) {
            views.get(v).startFrame();
        }
        // A new frame: last frame's current images are this frame's previous ones, also when the graph rebuilds (the
        // pairs it keeps carry their contents over). A skipped view's stay as they are.
        for (int h = 0; h < activeHistories.length; h++) {
            if (historyRuns(h)) {
                activeHistories[h].current.swap(activeHistories[h].previous);
            }
        }
        if (dirty || externalsResized() || techniquesChanged()) {
            build();
        }
        frame++;
        retiring.release(); // removed textures no frame in flight samples any more
        for (int h = 0; h < activeHistories.length; h++) {
            HistoryPair pair = activeHistories[h];
            if (!historyRuns(h) && pair.firstFrame >= frame) {
                pair.firstFrame = frame + 1; // not written yet: the frame after it first is has a last frame
            }
        }
        for (int i = 0; i < order.length; i++) {
            passSkipped[i] = !runs(passViews[i]);
        }
        if (resources.hasRetired()) {
            resources.release(false, frame);
        }
    }

    /** Whether any of {@code views} runs this frame; null: the frame's, always. */
    static boolean runs(ViewPort[] views) {
        if (views == null) {
            return true;
        }
        for (ViewPort view : views) {
            if (view.active) {
                return true;
            }
        }
        return false;
    }

    /** Whether history {@code h} swaps and is written this frame: false while its view is skipped. */
    private boolean historyRuns(int h) {
        return historyView[h] == null || historyView[h].active;
    }

    /** Records chunks {@code from} to {@code to}, on worker {@code worker}: its own command buffers. */
    private void recordChunks(int from, int to, int worker) {
        for (int c = from; c < to; c++) {
            int batch = chunkBatch[c];
            boolean graphics = batchQueue[batch] == GRAPHICS;
            VkCommandBuffer commands = execution.commandBuffer(worker, batchQueue[batch]);
            bindless.bind(commands, graphics);
            boolean last = batch == lastGraphicsBatch && c == batchFirstChunk[batch + 1] - 1;
            if (c == firstGraphicsChunk && frameStart == null) {
                recordSeam(commands); // a graph just built: its histories joined
                frameSlots.recordStart(commands); // uploads and such, ahead of every pass on the graphics queue
            }
            recordPasses(commands, chunkFirstPass[c], chunkEndPass[c], last, contexts[worker]);
            Vulkan.check(Commands.endCommandBuffer(commands), "Could not finish recording");
            chunkCommands[c] = commands;
        }
    }

    /** Passes {@code from} to {@code to} with their barriers, and with {@code last} the outputs' final layouts. */
    private void recordPasses(VkCommandBuffer commands, int from, int to, boolean last, PassContext context) {
        context.commands = commands;
        GpuTimings timed = timingsOn ? timings : null;
        for (int i = from; i < to; i++) {
            boolean labelled = passLabels.length > 0 && Commands.beginLabel(commands, passLabels[i]);
            if (timed != null) {
                timed.write(commands, recordSlot, i, false);
            }
            // A skipped pass keeps its barriers, so every later one finds the layouts and ordering it was compiled
            // for; only those on the histories of a skipped view go, which stay as they are.
            boolean skipped = passSkipped[i];
            barriers(commands, barrierStart[i], barrierStart[i + 1], skipped);
            PassInfo pass = order[i];
            context.pass = pass;
            if (skipped) {
                // its view's frame is skipped: nothing to record
            } else if (pass.attachments.length == 0) {
                pass.body.record(context);
            } else {
                beginRendering(commands, pass.attachments);
                pass.body.record(context);
                Commands.endRendering(commands);
            }
            if (timed != null) {
                timed.write(commands, recordSlot, i, true);
            }
            if (labelled) {
                Commands.endLabel(commands);
            }
        }
        context.pass = null;
        if (last) {
            barriers(commands, barrierStart[order.length], barrierStart[order.length + 1], false);
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
                Vulkan.check(Commands.resetCommandPool(device.handle(), pools[slot][i], 0), "Could not reset a command pool");
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
                device.check(device.direct().waitSemaphores(info, Long.MAX_VALUE), "Could not wait for a frame");
            }
        }

        void waitIdle() {
            waitFor(graphicsValue, computeValue);
        }

        /** Makes sure each worker could record every chunk of each queue: workers take chunks as they come free. */
        void ensureCommandBuffers() {
            for (int i = 0; i < used.length; i++) {
                // The calling thread (worker 0) records the frame start too, when recording on several threads.
                int needed = i % 2 == GRAPHICS ? graphicsChunks + (i == GRAPHICS ? 1 : 0) : computeChunks;
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
                Vulkan.check(Commands.beginCommandBuffer(commands, info), "Could not begin recording");
            }
            return commands;
        }

        /** Submits the batches in order, each to its queue. Allocates nothing. */
        void submit(long[] acquired, int acquiredCount, long[] renderFinished, int finishedCount) {
            long graphicsBase = graphicsValue;
            long computeBase = computeValue;
            boolean acquireWaited = acquiredCount == 0;
            boolean bothQueues = computeBatches > 0;
            boolean firstGraphics = true;
            boolean firstCompute = true;
            for (int b = 0; b < batchQueue.length; b++) {
                int queue = batchQueue[b];
                try (MemoryStack stack = stackPush()) {
                    int from = batchFirstChunk[b];
                    int count = batchFirstChunk[b + 1] - from;
                    boolean start = frameStart != null && from <= firstGraphicsChunk
                        && firstGraphicsChunk < from + count;
                    long infos = stack.ncalloc(VkCommandBufferSubmitInfo.ALIGNOF, count + 1,
                        VkCommandBufferSubmitInfo.SIZEOF);
                    if (start) {
                        commandBufferInfo(infos, frameStart);
                    }
                    for (int c = 0; c < count; c++) {
                        commandBufferInfo(infos + (long) (start ? c + 1 : c) * VkCommandBufferSubmitInfo.SIZEOF,
                            chunkCommands[from + c]);
                    }
                    if (start) {
                        count++;
                    }
                    // Waits: the other queue's batch this one depends on (or, first on its queue, all the other queue
                    // did in the frame before, as images pass between queues), and the acquired image.
                    long waits = stack.ncalloc(VkSemaphoreSubmitInfo.ALIGNOF, 1 + acquiredCount,
                        VkSemaphoreSubmitInfo.SIZEOF);
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
                        for (int a = 0; a < acquiredCount; a++) {
                            semaphore(waits + (long) waitCount++ * VkSemaphoreSubmitInfo.SIZEOF, acquired[a], 0);
                        }
                        acquireWaited = true;
                    }
                    long signals = stack.ncalloc(VkSemaphoreSubmitInfo.ALIGNOF, 1 + finishedCount,
                        VkSemaphoreSubmitInfo.SIZEOF);
                    int signalCount = 1;
                    semaphore(signals, queue == GRAPHICS ? graphicsTimeline : computeTimeline,
                        (queue == GRAPHICS ? graphicsBase : computeBase) + batchOrdinal[b]);
                    if (b == lastGraphicsBatch) {
                        for (int f = 0; f < finishedCount; f++) {
                            semaphore(signals + (long) signalCount++ * VkSemaphoreSubmitInfo.SIZEOF, renderFinished[f],
                                0);
                        }
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

        private static void commandBufferInfo(long info, VkCommandBuffer commands) {
            memPutInt(info + VkCommandBufferSubmitInfo.STYPE, VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO);
            memPutAddress(info + VkCommandBufferSubmitInfo.COMMANDBUFFER, commands.address());
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

    /** Whether a technique would build differently now ({@link Technique#buildVersion}). Allocates nothing. */
    private boolean techniquesChanged() {
        for (int i = 0; i < builtVersions.length; i++) {
            if (built[i].buildVersion() != builtVersions[i]) {
                return true;
            }
        }
        return false;
    }

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

    /**
     * Starts rendering into a pass's attachments, the viewport and scissor all of them; the views are looked up each
     * frame, since a rebuild may move images, and the clear values read, since a technique may change them.
     */
    private void beginRendering(VkCommandBuffer commands, GraphBuilder.Attachment[] attachments) {
        try (MemoryStack stack = stackPush()) {
            long colors = stack.ncalloc(VkRenderingAttachmentInfo.ALIGNOF, attachments.length,
                VkRenderingAttachmentInfo.SIZEOF);
            long depth = 0;
            long stencil = 0;
            int colorCount = 0;
            Image size = attachments[0].target().image();
            for (GraphBuilder.Attachment attachment : attachments) {
                Subresource target = attachment.target();
                long info;
                if (attachment.depth()) {
                    info = stack.ncalloc(VkRenderingAttachmentInfo.ALIGNOF, 1, VkRenderingAttachmentInfo.SIZEOF);
                    depth = info;
                    if ((target.image().format.aspect() & VK_IMAGE_ASPECT_STENCIL_BIT) != 0) {
                        stencil = info;
                    }
                } else {
                    info = colors + (long) colorCount++ * VkRenderingAttachmentInfo.SIZEOF;
                }
                memPutInt(info + VkRenderingAttachmentInfo.STYPE, VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO);
                memPutLong(info + VkRenderingAttachmentInfo.IMAGEVIEW, attachmentView(target));
                memPutInt(info + VkRenderingAttachmentInfo.IMAGELAYOUT, attachment.depth()
                    ? VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                memPutInt(info + VkRenderingAttachmentInfo.LOADOP, attachment.load().op);
                memPutInt(info + VkRenderingAttachmentInfo.STOREOP, attachment.store().op);
                Clear clear = attachment.clear();
                if (clear != null) {
                    long value = info + VkRenderingAttachmentInfo.CLEARVALUE;
                    if (clear.depth) {
                        memPutFloat(value, clear.value(0));
                        memPutInt(value + 4, clear.stencil);
                    } else {
                        for (int c = 0; c < 4; c++) {
                            memPutFloat(value + 4L * c, clear.value(c));
                        }
                    }
                }
                if (attachment.resolve() != null) {
                    memPutInt(info + VkRenderingAttachmentInfo.RESOLVEMODE, VK_RESOLVE_MODE_AVERAGE_BIT);
                    memPutLong(info + VkRenderingAttachmentInfo.RESOLVEIMAGEVIEW, attachmentView(attachment.resolve()));
                    memPutInt(info + VkRenderingAttachmentInfo.RESOLVEIMAGELAYOUT,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                }
            }
            long rendering = stack.ncalloc(VkRenderingInfo.ALIGNOF, 1, VkRenderingInfo.SIZEOF);
            memPutInt(rendering + VkRenderingInfo.STYPE, VK_STRUCTURE_TYPE_RENDERING_INFO);
            memPutInt(rendering + VkRenderingInfo.RENDERAREA + VkRect2D.EXTENT, size.width);
            memPutInt(rendering + VkRenderingInfo.RENDERAREA + VkRect2D.EXTENT + 4, size.height);
            memPutInt(rendering + VkRenderingInfo.LAYERCOUNT, 1);
            memPutInt(rendering + VkRenderingInfo.COLORATTACHMENTCOUNT, colorCount);
            memPutAddress(rendering + VkRenderingInfo.PCOLORATTACHMENTS, colorCount == 0 ? 0 : colors);
            memPutAddress(rendering + VkRenderingInfo.PDEPTHATTACHMENT, depth);
            memPutAddress(rendering + VkRenderingInfo.PSTENCILATTACHMENT, stencil);
            Commands.beginRendering(commands, rendering);
            long viewport = stack.ncalloc(VkViewport.ALIGNOF, 1, VkViewport.SIZEOF);
            memPutFloat(viewport + VkViewport.WIDTH, size.width);
            memPutFloat(viewport + VkViewport.HEIGHT, size.height);
            memPutFloat(viewport + VkViewport.MAXDEPTH, 1);
            Commands.setViewport(commands, 0, 1, viewport);
            long scissor = stack.ncalloc(VkRect2D.ALIGNOF, 1, VkRect2D.SIZEOF);
            memPutInt(scissor + VkRect2D.EXTENT, size.width);
            memPutInt(scissor + VkRect2D.EXTENT + 4, size.height);
            Commands.setScissor(commands, 0, 1, scissor);
        }
    }

    private long attachmentView(Subresource target) {
        return target.whole() ? viewOf(target.image().id) : layerViewOf(target.image().id, target.baseLayer());
    }

    /** Barriers {@code from} to {@code to}; of a {@code skipped} pass, without those on a skipped view's histories. */
    private void barriers(VkCommandBuffer commands, int from, int to, boolean skipped) {
        if (from == to) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            int bufferCount = 0;
            int imageCount = 0;
            for (int b = from; b < to; b++) {
                if (!dropped(b, skipped)) {
                    bufferCount += barrierKind[b];
                    imageCount += 1 - barrierKind[b];
                }
            }
            if (bufferCount + imageCount == 0) {
                return;
            }
            long images = stack.ncalloc(VkImageMemoryBarrier2.ALIGNOF, Math.max(1, imageCount),
                VkImageMemoryBarrier2.SIZEOF);
            long buffers = stack.ncalloc(VkBufferMemoryBarrier2.ALIGNOF, Math.max(1, bufferCount),
                VkBufferMemoryBarrier2.SIZEOF);
            int image = 0;
            int buffer = 0;
            for (int b = from; b < to; b++) {
                if (dropped(b, skipped)) {
                    continue;
                }
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
                memPutInt(range + VkImageSubresourceRange.BASEMIPLEVEL, barrierBaseMip[b]);
                memPutInt(range + VkImageSubresourceRange.LEVELCOUNT, barrierMipCount[b]); // -1: VK_REMAINING_MIP_LEVELS
                memPutInt(range + VkImageSubresourceRange.BASEARRAYLAYER, barrierBaseLayer[b]);
                memPutInt(range + VkImageSubresourceRange.LAYERCOUNT, barrierLayerCount[b]); // -1: VK_REMAINING_ARRAY_LAYERS
            }
            long dependency = stack.ncalloc(VkDependencyInfo.ALIGNOF, 1, VkDependencyInfo.SIZEOF);
            memPutInt(dependency + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
            memPutInt(dependency + VkDependencyInfo.IMAGEMEMORYBARRIERCOUNT, imageCount);
            memPutAddress(dependency + VkDependencyInfo.PIMAGEMEMORYBARRIERS, images);
            memPutInt(dependency + VkDependencyInfo.BUFFERMEMORYBARRIERCOUNT, bufferCount);
            memPutAddress(dependency + VkDependencyInfo.PBUFFERMEMORYBARRIERS, buffers);
            Commands.pipelineBarrier2(commands, dependency);
        }
    }

    /**
     * Whether barrier {@code b}, of a pass {@code skipped} or not, is left out: on a skipped view's history, or on the
     * window image a view not running this frame outputs into (not acquired: the presentation engine may hold it).
     */
    private boolean dropped(int b, boolean skipped) {
        int target = barrierTarget[b];
        if (barrierKind[b] == IMAGE && target < 0) {
            ViewPort view = externalView[~target];
            if (view != null && !view.active) {
                return true;
            }
        }
        return skipped && barrierHistory[b] >= 0 && !historyRuns(barrierHistory[b]);
    }

    long bufferHandle(int buffer) {
        int target = bufferTargetOf[buffer];
        return target >= 0 ? physicalBuffers[target].buffer.handle() : externalBuffers[~target].handle;
    }

    long bufferAddress(int buffer) {
        int target = bufferTargetOf[buffer];
        if (target >= 0) {
            return physicalBuffers[target].buffer.deviceAddress();
        }
        ExternalBuffer external = externalBuffers[~target];
        if (external.address == 0) {
            throw new IllegalStateException("The external buffer " + external.name + " has no device address: made "
                + "without SHADER_DEVICE_ADDRESS usage");
        }
        return external.address;
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
        int target = targetOf[image];
        return target >= 0 ? physicals[target].sampled : externals[~target].sampled;
    }

    int storageSlotOf(int image, int mip) {
        int[] slots = physicals[targetOf[image]].storage;
        return mip < slots.length ? slots[mip] : -1;
    }

    int sampledSlotOf(int image, int mip) {
        int[] slots = physicals[targetOf[image]].sampledMips;
        return mip < slots.length ? slots[mip] : -1;
    }

    long layerViewOf(int image, int layer) {
        int target = targetOf[image];
        return target >= 0 ? physicals[target].image.layerView(layer) : externals[~target].view;
    }

    VulkanDevice device() {
        return device;
    }

    /** How many frames may be in flight: the frame slots {@link #beginFrame} returns. */
    int framesInFlight() {
        return framesInFlight;
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
        return resources.pooledImages();
    }

    /** The transient buffers in the pool, for inspection. */
    public int pooledBuffers() {
        return resources.pooledBuffers();
    }

    /** How many times the graph was built. */
    public int builds() {
        return builds;
    }

    // ---------------------------------------------------------------- building

    @AllowAllocation("building the graph, when it changes")
    private void build() {
        GraphBuilder g = new GraphBuilder(this, techniques, views, implementations);
        Map<Object, ExternalImage> targets = new LinkedHashMap<>(outputs);
        for (ViewPort view : views) {
            for (Map.Entry<Key<Image>, ExternalImage> output : view.outputs.entrySet()) {
                targets.put(g.scoped(view, output.getKey()), output.getValue());
            }
        }
        for (ExternalImage target : targets.values()) {
            if (target.texture != null && target.texture.closed()) {
                throw new IllegalStateException(target.texture + " was closed: remove the outputs into it first");
            }
        }
        CompiledGraph compiled = GraphCompiler.compile(g, targets, asyncCompute,
            graphicsQueue.family() != computeQueue.family(), grain, this::blit);
        // The slots' images and buffers, from the pool; histories keep theirs while their name and slot stay.
        Physical[] claimed = resources.claim(compiled.imageSlots(), frame);
        List<HistoryPlan> plans = compiled.histories();
        boolean[] keptPair = new boolean[plans.size()];
        HistoryPair[] pairs = resources.histories(plans, keptPair, frame);
        Physical[] newPhysicals = Arrays.copyOf(claimed, compiled.physicalCount());
        for (int h = 0; h < plans.size(); h++) {
            newPhysicals[plans.get(h).current()] = pairs[h].current;
            newPhysicals[plans.get(h).previous()] = pairs[h].previous;
        }
        PhysicalBuffer[] newPhysicalBuffers = resources.claimBuffers(compiled.bufferSlots(), frame);

        order = compiled.order();
        targetOf = compiled.targetOf();
        physicals = newPhysicals;
        bufferTargetOf = compiled.bufferTargetOf();
        physicalBuffers = newPhysicalBuffers;
        externals = compiled.externals();
        externalVersions = new int[externals.length];
        for (int i = 0; i < externals.length; i++) {
            externalVersions[i] = externals[i].version;
        }
        externalBuffers = compiled.externalBuffers();
        externalBufferVersions = new int[externalBuffers.length];
        for (int i = 0; i < externalBuffers.length; i++) {
            externalBufferVersions[i] = externalBuffers[i].version;
        }
        CompiledGraph.BarrierList barriers = compiled.barriers();
        barrierStart = barriers.start();
        barrierTarget = barriers.target();
        barrierKind = barriers.kind();
        barrierAspect = barriers.aspect();
        barrierOldLayout = barriers.oldLayout();
        barrierNewLayout = barriers.newLayout();
        barrierSrcStage = barriers.srcStage();
        barrierSrcAccess = barriers.srcAccess();
        barrierDstStage = barriers.dstStage();
        barrierDstAccess = barriers.dstAccess();
        barrierBaseMip = barriers.baseMip();
        barrierMipCount = barriers.mipCount();
        barrierBaseLayer = barriers.baseLayer();
        barrierLayerCount = barriers.layerCount();
        int[] historyOf = new int[newPhysicals.length]; // each physical image's history, or -1
        Arrays.fill(historyOf, -1);
        for (int h = 0; h < plans.size(); h++) {
            historyOf[plans.get(h).current()] = h;
            historyOf[plans.get(h).previous()] = h;
        }
        barrierHistory = new int[barrierTarget.length];
        for (int b = 0; b < barrierTarget.length; b++) {
            barrierHistory[b] = barrierKind[b] == IMAGE && barrierTarget[b] >= 0 ? historyOf[barrierTarget[b]] : -1;
        }
        startViews(g, plans.size());
        builtSettings = Set.copyOf(g.settingsRead);
        CompiledGraph.Batches batches = compiled.batches();
        batchQueue = batches.queue();
        batchOrdinal = batches.ordinal();
        batchWait = batches.waitFor();
        batchAcquire = batches.acquire();
        batchFirstChunk = batches.firstChunk();
        lastGraphicsBatch = batches.lastGraphicsBatch();
        firstGraphicsChunk = batches.firstGraphicsChunk();
        graphicsBatches = batches.graphicsBatches();
        computeBatches = batches.computeBatches();
        graphicsChunks = batches.graphicsChunks();
        computeChunks = batches.computeChunks();
        chunkBatch = batches.chunkBatch();
        chunkFirstPass = batches.chunkFirstPass();
        chunkEndPass = batches.chunkEndPass();
        chunkCommands = new VkCommandBuffer[chunkBatch.length];
        startTooling(g);
        startHistories(pairs, keptPair, compiled);
        dirty = false;
        built = g.techniques().toArray(new Technique[0]);
        builtVersions = new long[built.length];
        for (int i = 0; i < builtVersions.length; i++) {
            builtVersions[i] = built[i].buildVersion();
        }
        builds++;
        LOG.debug("Frame graph built: {} of {} passes in {} batches, {} images in {} pooled, {} buffers in {} pooled, "
            + "{} barriers", order.length, compiled.builtPasses(), batchQueue.length, targetOf.length,
            physicals.length, bufferTargetOf.length, physicalBuffers.length, barrierTarget.length);
    }

    /**
     * Which views the passes just built run for, and which view each history is of: the one view of every pass using
     * it, or none (it swaps every frame) if the frame's passes or several views' use it.
     */
    @AllowAllocation("building the graph, when it changes")
    private void startViews(GraphBuilder g, int historyCount) {
        g.finishRunning();
        externalView = new ViewPort[externals.length];
        for (ViewPort view : views) {
            for (ExternalImage target : view.outputs.values()) {
                for (int e = 0; e < externals.length; e++) {
                    if (externals[e] == target && target.texture == null) {
                        externalView[e] = view;
                    }
                }
            }
        }
        passViews = new ViewPort[order.length][];
        passSkipped = new boolean[order.length];
        historyView = new ViewPort[historyCount];
        boolean[] shared = new boolean[historyCount];
        for (int i = 0; i < order.length; i++) {
            passViews[i] = g.runsFor(order[i].scope);
            for (int image : order[i].images) {
                History history = g.images.get(image).history;
                if (history == null) {
                    continue;
                }
                int h = history.index;
                ViewPort[] runs = passViews[i];
                ViewPort view = runs != null && runs.length == 1 ? runs[0] : null;
                shared[h] |= view == null || historyView[h] != null && historyView[h] != view;
                historyView[h] = view;
            }
        }
        for (int h = 0; h < historyCount; h++) {
            if (shared[h]) {
                historyView[h] = null;
            }
        }
    }

    /**
     * The tooling of the graph just built: its readbacks' host memory, the timings' queries and the passes' debug labels.
     * Readbacks of the graph before that still wait are delivered first, and with them or timings on, the GPU is waited
     * for: neither may change while frames use them.
     */
    @AllowAllocation("building the graph, when it changes")
    private void startTooling(GraphBuilder g) {
        boolean pending = false;
        for (ReadbackPlan readback : readbacks) {
            pending |= readback.pending();
        }
        if (pending || timingsOn || timings != null) {
            waitIdle();
            for (int slot = 0; slot < framesInFlight; slot++) {
                finished(slot);
            }
        }
        for (ReadbackPlan readback : readbacks) {
            readback.close();
        }
        readbacks = g.readbacks.toArray(new ReadbackPlan[0]);
        for (ReadbackPlan readback : readbacks) {
            readback.allocate(device, framesInFlight, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        }
        if (timingsOn) {
            if (timings == null) {
                timings = new GpuTimings(device, framesInFlight);
            }
            int[] families = new int[order.length];
            for (int c = 0; c < chunkBatch.length; c++) {
                int family = batchQueue[chunkBatch[c]] == GRAPHICS ? graphicsQueue.family() : computeQueue.family();
                Arrays.fill(families, chunkFirstPass[c], chunkEndPass[c], family);
            }
            timings.build(order, families);
        } else if (timings != null) {
            timings.close();
            timings = null;
        }
        if (labelArena != null) {
            labelArena.close();
            labelArena = null;
            passLabels = new long[0];
        }
        if (device.handle().getPhysicalDevice().getInstance().getCapabilities().vkCmdBeginDebugUtilsLabelEXT != 0) {
            labelArena = Arena.ofShared();
            passLabels = new long[order.length];
            for (int i = 0; i < order.length; i++) {
                MemorySegment label = labelArena.allocate(VkDebugUtilsLabelEXT.SIZEOF, VkDebugUtilsLabelEXT.ALIGNOF);
                MemorySegment name = labelArena.allocateFrom(order[i].name);
                memPutInt(label.address() + VkDebugUtilsLabelEXT.STYPE, VK_STRUCTURE_TYPE_DEBUG_UTILS_LABEL_EXT);
                memPutAddress(label.address() + VkDebugUtilsLabelEXT.PLABELNAME, name.address());
                passLabels[i] = label.address();
            }
        }
    }

    /**
     * Joins the histories to the graph just built, without waiting for the GPU: the seam. A pair the graph kept keeps
     * its contents, its last frame image moving from the layout the graph before left it in to the one this graph
     * starts with; a new pair starts with undefined contents (its first frame has no last frame). The transitions run
     * at the start of the next frame's graphics commands (a frame starting on the compute queue has them submitted
     * ahead). Each pair notes the layouts this graph leaves it in, for the next seam.
     */
    @AllowAllocation("building the graph, when it changes")
    private void startHistories(HistoryPair[] pairs, boolean[] kept, CompiledGraph compiled) {
        activeHistories = pairs;
        int count = 0;
        Physical[] images = new Physical[2 * pairs.length];
        int[] from = new int[images.length];
        int[] to = new int[images.length];
        for (int h = 0; h < pairs.length; h++) {
            HistoryPair pair = pairs[h];
            // Last frame's current image is this frame's previous one: it holds what the graph before left in it. A
            // pair of a view skipped this frame does not swap: its current image is the one that becomes the previous
            // one when the view next runs, so the two take the layouts their roles start that frame with.
            boolean swapped = historyRuns(h);
            Physical latest = swapped ? pair.previous : pair.current;
            Physical other = swapped ? pair.current : pair.previous;
            int previousFrom = kept[h] ? pair.endCurrent : VK_IMAGE_LAYOUT_UNDEFINED;
            int startPrevious = compiled.historyStartPrevious()[h];
            int startCurrent = compiled.historyStartCurrent()[h];
            if (previousFrom != startPrevious) {
                images[count] = latest;
                from[count] = previousFrom;
                to[count++] = startPrevious;
            }
            if (startCurrent != VK_IMAGE_LAYOUT_UNDEFINED) { // about to be written: contents do not matter
                images[count] = other;
                from[count] = VK_IMAGE_LAYOUT_UNDEFINED;
                to[count++] = startCurrent;
            }
            if (!kept[h]) {
                pair.firstFrame = frame + 1;
            }
            pair.endCurrent = compiled.historyEndCurrent()[h];
        }
        seamImages = Arrays.copyOf(images, count);
        seamFrom = Arrays.copyOf(from, count);
        seamTo = Arrays.copyOf(to, count);
        if (count > 0 && batchQueue.length > 0 && batchQueue[0] != GRAPHICS) {
            device.submitAndWait(graphicsQueue, this::recordSeam); // the frame starts on the compute queue
        }
    }

    /** Records the seam's transitions, if the graph was just built, once. */
    private void recordSeam(VkCommandBuffer commands) {
        for (int i = 0; i < seamImages.length; i++) {
            com.truesim.engine.vulkan.Images.transition(commands, seamImages[i].image.handle(),
                seamImages[i].format.aspect(), seamFrom[i], seamTo[i], VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_ACCESS_2_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        }
        seamImages = NO_PHYSICALS;
    }

    /** Whether history {@code index}'s last frame image holds what that frame wrote. */
    boolean historyValid(int index) {
        return frame > activeHistories[index].firstFrame;
    }

    void blit(PassContext ctx, Image from, Image to) {
        ctx.blit(from.whole(), to.whole(), from.width != to.width || from.height != to.height);
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
        // A cached object that fails to close does not stop the rest: the first failure is thrown at the end.
        IllegalStateException failure = null;
        for (ViewPort view : views) {
            try {
                view.closeOwned();
            } catch (IllegalStateException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        for (AutoCloseable object : cache.values()) {
            try {
                object.close();
            } catch (Exception e) {
                IllegalStateException wrapped = new IllegalStateException("Could not close " + object, e);
                if (failure == null) {
                    failure = wrapped;
                } else {
                    failure.addSuppressed(wrapped);
                }
            }
        }
        cache.clear();
        for (Texture texture : textures) {
            texture.close();
        }
        textures.clear();
        settingsWatch.close();
        retiring.close(); // the frames are done: removed textures go now
        retiring = new Retiring(frameSlots); // for what is removed after, at once
        for (ReadbackPlan readback : readbacks) {
            readback.close();
        }
        readbacks = new ReadbackPlan[0];
        Arrays.fill(slotFrame, -1);
        if (timings != null) {
            timings.close();
            timings = null;
        }
        if (labelArena != null) {
            labelArena.close();
            labelArena = null;
            passLabels = new long[0];
        }
        resources.close();
        activeHistories = new HistoryPair[0];
        physicals = new Physical[0];
        physicalBuffers = new PhysicalBuffer[0];
        order = new PassInfo[0];
        barrierStart = new int[] {0, 0};
        dirty = true;
        if (failure != null) {
            throw failure;
        }
    }

}
