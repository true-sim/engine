package com.truesim.engine.graph;

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
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.lwjgl.vulkan.VkCommandBuffer;
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
    private final PersistentResources persistents;
    private static final Physical[] NO_PHYSICALS = new Physical[0];
    /** Before the first build: no barriers. */
    private static final CompiledGraph.BarrierList NO_BARRIERS = new CompiledGraph.BarrierList(new int[] {0, 0},
        new int[0], new int[0], new int[0], new int[0], new int[0], new long[0], new long[0], new long[0], new long[0],
        new int[0], new int[0], new int[0], new int[0]);
    /** Before the first build: one graphics batch of one empty chunk. */
    private static final CompiledGraph.Batches ONE_BATCH = new CompiledGraph.Batches(new int[] {GRAPHICS},
        new int[] {1}, new int[] {0}, new boolean[] {false}, new int[] {0, 1}, 0, 0, 1, 0, 1, 0, new int[] {0},
        new int[] {0}, new int[] {0});
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
    private FrameExecution execution;
    private boolean dirty = true;
    private long frame;
    private int builds;
    // Tooling: the frame slot being recorded and the frame each slot last recorded (-1: none since it was read), the
    // readbacks of the build, GPU timings when on, and a debug label per pass where the instance has debug utils.
    private int recordSlot;
    private long[] slotFrame;
    private final FrameTooling tooling;
    private boolean historiesSwapped;
    Object currentBuild; // the last build's builder: images and buffers of others are refused (PassContext) // by the frame being submitted: swapped back if it is dropped

    // The built graph: passes in order, the barriers before each (CompiledGraph.BarrierList), and where each image of
    // the build lives: a physical image (index >= 0) or an external one (~index).
    private PassInfo[] order = new PassInfo[0];
    private CompiledGraph.BarrierList barriers = NO_BARRIERS;
    private int[] barrierHistory = new int[0]; // the history whose image each barrier is on, or -1
    // Per-frame view control: the views each pass runs for (null: every frame), the passes skipped this frame, and the
    // view each history is of (null: the frame's, or shared, so it swaps every frame).
    private ViewPort[][] passViews = new ViewPort[0][];
    private boolean[] passSkipped = new boolean[0];
    private ViewPort[] historyView = new ViewPort[0];
    // The view each external image is an output of, if not a texture (a window's image): untouched while the view does
    // not run, as the presentation engine may hold it.
    private ViewPort[] externalView = new ViewPort[0];
    // Switches: each pass's (null: none), the switches of the build with their views, and each image's that falls
    // back while its switch is off, with the fallback's sampled slot.
    private Switch[] passSwitch = new Switch[0];
    private Switch[] switches = new Switch[0];
    private ViewPort[] switchViews = new ViewPort[0];
    private Switch[] imageSwitch = new Switch[0];
    private int[] fallbackSlot = new int[0];
    private int[] targetOf = new int[0];
    private boolean[] unscaled = new boolean[0]; // each image's: written whole, not within a render region
    private Physical[] physicals = new Physical[0];
    private ExternalImage[] externals = new ExternalImage[0];
    private int[] externalVersions = new int[0];
    private ExternalBuffer[] externalBuffers = new ExternalBuffer[0];
    private int[] externalBufferVersions = new int[0];
    private int[] bufferTargetOf = new int[0];
    private PhysicalBuffer[] physicalBuffers = new PhysicalBuffer[0];

    // How the built graph runs: batches of passes, each on one queue, cut into chunks recorded in parallel.
    private CompiledGraph.Batches batches = ONE_BATCH;
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
        this.tooling = new FrameTooling(device, framesInFlight);
        this.persistents = new PersistentResources(device, graphicsQueue, computeQueue);
        this.asyncCompute = graphicsQueue != computeQueue;
    }

    /** Adds a technique; the graph rebuilds on its next frame. */
    public FrameGraph add(Technique technique) {
        if (techniques.contains(Objects.requireNonNull(technique, "technique"))) {
            throw new IllegalArgumentException(GraphBuilder.name(technique) + " is in the graph already");
        }
        String place = placeOf(technique);
        if (place != null) {
            throw new IllegalArgumentException(GraphBuilder.name(technique) + " is in " + place + " already: a "
                + "technique builds for one view or the frame");
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
     * Swaps in {@code technique} for the techniques providing any of its keys, e.g. one lighting technique for another,
     * and for those of its {@link Technique#id() id}, e.g. one transparency technique (modifying, providing nothing) for
     * another: whoever requires those keys gets the new one. Removes nothing if none does. The graph rebuilds on its next
     * frame.
     */
    public FrameGraph replace(Technique technique) {
        List<Key<?>> keys = technique.provides();
        Id<? extends Technique> id = technique.id();
        techniques.removeIf(t -> t.provides().stream().anyMatch(keys::contains) || id != null && t.id() == id);
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
            view.outputs.values().forEach(targets -> targets.removeIf(target -> target == texture.external));
            view.outputs.values().removeIf(List::isEmpty);
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

    /** Stops outputting {@code key}; the graph rebuilds on its next frame. */
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
            execution = new FrameExecution(device, graphicsQueue, computeQueue, framesInFlight, contexts.length);
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
        tooling.finished(slot, finished);
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
        if (tooling.timings(on)) {
            dirty = true;
        }
        return this;
    }

    /** The passes' GPU timings of the last finished frame, with {@link #timings(boolean)} on; null otherwise. */
    public GpuTimings timings() {
        return tooling.timings();
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
        boolean submitted = false;
        try {
            prepare();
            recordSlot = execution.slot();
            slotFrame[recordSlot] = frame;
            execution.ensureCommandBuffers(batches);
            if (jobs != null && batches.chunkBatch().length > 1) {
                frameStart = execution.commandBuffer(0, GRAPHICS);
                bindless.bind(frameStart, true);
                recordSeam(frameStart);
                frameSlots.recordStart(frameStart);
                Vulkan.check(Commands.endCommandBuffer(frameStart), "Could not finish recording");
                jobs.parallelFor(batches.chunkBatch().length, 1, recordChunks);
            } else {
                frameStart = null;
                recordChunks(0, batches.chunkBatch().length, 0);
            }
            execution.submit(batches, frameStart, chunkCommands, acquired, acquiredCount, renderFinished,
                finishedCount);
            submitted = true;
        } finally {
            // A build or a pass that threw leaves the graph usable: the frame is dropped, the next one begins (a graph
            // that failed to build builds again then).
            if (!submitted) {
                execution.abandon();
                if (historiesSwapped) {
                    swapHistories(); // back: the frame dropped wrote none, so last frame's stay the previous ones
                }
            }
            historiesSwapped = false;
            if (ownsSlots) {
                frameSlots.end();
            }
        }
    }

    /**
     * Records the whole frame into {@code commands}, on the calling thread: the bindless set, then each needed pass
     * with its barriers, then the outputs' final layouts. Async passes run in their place. Builds the graph first if it
     * changed; otherwise allocates nothing. Outside {@link #beginFrame} and {@link #submit}, per-frame data uses frame
     * slot 0: the caller waits for the GPU before recording the next frame.
     */
    public void record(VkCommandBuffer commands) {
        if (execution != null && !execution.begun) {
            execution.waitIdle(); // frames submitted before: slot 0's data is rewritten now, so they finish first
        }
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

    /** Swaps each running history pair's current and previous images, and the buffer histories alike. */
    private void swapHistories() {
        for (int h = 0; h < activeHistories.length; h++) {
            if (historyRuns(h)) {
                activeHistories[h].current.swap(activeHistories[h].previous);
            }
        }
        persistents.swapHistories();
    }

    private void prepare() {
        for (int v = 0; v < views.size(); v++) {
            views.get(v).startFrame();
        }
        // A new frame: last frame's current images are this frame's previous ones, also when the graph rebuilds (the
        // pairs it keeps carry their contents over). A skipped view's stay as they are.
        swapHistories();
        historiesSwapped = true;
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
        persistents.startFrame(frame);
        for (int s = 0; s < switches.length; s++) {
            switches[s].on = switches[s].on(switchViews[s], settings);
        }
        for (ExternalImage external : externals) {
            external.heldThisFrame = external.held; // read once: hold() from another thread waits for the next frame
        }
        for (int i = 0; i < order.length; i++) {
            ExternalImage into = order[i].into;
            Switch switched = passSwitch[i];
            passSkipped[i] = !runs(passViews[i]) || into != null && into.heldThisFrame
                || switched != null && !switched.on;
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
            int batch = batches.chunkBatch()[c];
            boolean graphics = batches.queue()[batch] == GRAPHICS;
            VkCommandBuffer commands = execution.commandBuffer(worker, batches.queue()[batch]);
            bindless.bind(commands, graphics);
            boolean last = batch == batches.lastGraphicsBatch() && c == batches.firstChunk()[batch + 1] - 1;
            if (c == batches.firstGraphicsChunk() && frameStart == null) {
                recordSeam(commands); // a graph just built: its histories joined
                frameSlots.recordStart(commands); // uploads and such, ahead of every pass on the graphics queue
            }
            recordPasses(commands, batches.chunkFirstPass()[c], batches.chunkEndPass()[c], last, contexts[worker]);
            Vulkan.check(Commands.endCommandBuffer(commands), "Could not finish recording");
            chunkCommands[c] = commands;
        }
    }

    /** Passes {@code from} to {@code to} with their barriers, and with {@code last} the outputs' final layouts. */
    private void recordPasses(VkCommandBuffer commands, int from, int to, boolean last, PassContext context) {
        context.commands = commands;
        for (int i = from; i < to; i++) {
            boolean labelled = tooling.beginPass(commands, recordSlot, i);
            // A skipped pass keeps its barriers, so every later one finds the layouts and ordering it was compiled
            // for; only those on the histories of a skipped view go, which stay as they are.
            boolean skipped = passSkipped[i];
            PassCommands.barriers(commands, barriers, barriers.start()[i], barriers.start()[i + 1], skipped, this);
            PassInfo pass = order[i];
            context.pass = pass;
            if (skipped) {
                // its view's frame is skipped: nothing to record
            } else if (pass.attachments.length == 0) {
                pass.body.record(context);
            } else {
                PassCommands.beginRendering(commands, pass, this);
                pass.body.record(context);
                Commands.endRendering(commands);
            }
            tooling.endPass(commands, recordSlot, i, labelled);
        }
        context.pass = null;
        if (last) {
            PassCommands.barriers(commands, barriers, barriers.start()[order.length],
                barriers.start()[order.length + 1], false, this);
        }
        context.commands = null;
    }

    /** Waits until the GPU finished everything submitted, e.g. before changing how frames run. */
    private void waitIdle() {
        if (execution != null) {
            execution.waitIdle();
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
     * Whether barrier {@code b}, of a pass {@code skipped} or not, is left out: on a skipped view's history, or on the
     * window image a view not running this frame outputs into (not acquired: the presentation engine may hold it).
     */
    boolean dropped(int b, boolean skipped) {
        int target = barriers.target()[b];
        if (barriers.kind()[b] == IMAGE && target < 0) {
            ViewPort view = externalView[~target];
            if (externals[~target].heldThisFrame || view != null && !view.active) {
                return true;
            }
        }
        return skipped && barrierHistory[b] >= 0 && !historyRuns(barrierHistory[b]);
    }

    long bufferHandle(int buffer) {
        return targetBufferHandle(bufferTargetOf[buffer]);
    }

    /** The buffer barriers' {@code target} is: pooled (>= 0) or external ({@code ~target}). */
    long targetBufferHandle(int target) {
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
        return targetImageHandle(targetOf[image]);
    }

    /** The image barriers' {@code target} is: pooled (>= 0) or external ({@code ~target}). */
    long targetImageHandle(int target) {
        return target >= 0 ? physicals[target].image.handle() : externals[~target].handle;
    }

    long viewOf(int image) {
        int target = targetOf[image];
        return target >= 0 ? physicals[target].image.view() : externals[~target].view;
    }

    int sampledSlotOf(int image) {
        Switch switched = imageSwitch[image];
        if (switched != null && !switched.on) {
            return fallbackSlot[image];
        }
        int target = targetOf[image];
        return target >= 0 ? physicals[target].sampled : externals[~target].sampled;
    }

    int storageSlotOf(int image, int mip) {
        int target = targetOf[image];
        if (target < 0) {
            return mip == 0 ? externals[~target].storage : -1;
        }
        int[] slots = physicals[target].storage;
        return mip < slots.length ? slots[mip] : -1;
    }

    int sampledSlotOf(int image, int mip) {
        int target = targetOf[image];
        if (target < 0) {
            return mip == 0 ? externals[~target].sampled : -1;
        }
        int[] slots = physicals[target].sampledMips;
        return mip < slots.length ? slots[mip] : -1;
    }

    long layerViewOf(int image, int layer) {
        int target = targetOf[image];
        return target >= 0 ? physicals[target].image.layerView(layer) : externals[~target].view;
    }

    /** The device this graph renders with. */
    public VulkanDevice device() {
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
        for (int b = 0; b < batches.queue().length; b++) {
            StringBuilder line = new StringBuilder(batches.queue()[b] == GRAPHICS ? "graphics " : "compute ")
                .append(batches.ordinal()[b]);
            List<String> after = new ArrayList<>();
            if (batches.waitFor()[b] > 0) {
                after.add((batches.queue()[b] == GRAPHICS ? "compute " : "graphics ") + batches.waitFor()[b]);
            }
            if (batches.acquire()[b]) {
                after.add("acquire");
            }
            if (!after.isEmpty()) {
                line.append(" (after ").append(String.join(", ", after)).append(')');
            }
            List<String> names = new ArrayList<>();
            for (int c = batches.firstChunk()[b]; c < batches.firstChunk()[b + 1]; c++) {
                for (int p = batches.chunkFirstPass()[c]; p < batches.chunkEndPass()[c]; p++) {
                    names.add(order[p].name);
                }
            }
            out.add(line.append(": ").append(String.join(", ", names)).toString());
        }
        return out;
    }

    /** How many command buffers {@link #submit} records each frame: the chunks of all batches. */
    public int chunks() {
        return batches.chunkBatch().length;
    }

    /** The transient images in the pool, for inspection. */
    public int pooledImages() {
        return resources.pooledImages();
    }

    /** The transient buffers in the pool, for inspection. */
    public int pooledBuffers() {
        return resources.pooledBuffers();
    }

    /**
     * Where {@code technique} (this instance, whether switched or not: {@code t.when(s)} wraps t anew each call) is:
     * "the graph", "view <name>", or null.
     */
    String placeOf(Technique technique) {
        Technique inner = unswitched(technique);
        for (Technique added : techniques) {
            if (unswitched(added) == inner) {
                return "the graph";
            }
        }
        for (ViewPort view : views) {
            for (Technique added : view.techniques) {
                if (unswitched(added) == inner) {
                    return "view " + view.name();
                }
            }
        }
        return null;
    }

    /** The technique a switch (or switches) wraps; {@code technique} itself if none. */
    private static Technique unswitched(Technique technique) {
        while (technique instanceof Switch switched) {
            technique = switched.technique();
        }
        return technique;
    }

    /** How many times the graph was built. */
    public int builds() {
        return builds;
    }

    // ---------------------------------------------------------------- building

    @AllowAllocation("building the graph, when it changes")
    private void build() {
        GraphBuilder g = new GraphBuilder(this, techniques, views, implementations);
        for (ViewPort view : views) {
            view.resolve(settings, g.settingsRead); // the images' sizes, for the techniques to build with
        }
        List<Map.Entry<Object, ExternalImage>> targets = new ArrayList<>();
        for (Map.Entry<Key<Image>, ExternalImage> output : outputs.entrySet()) {
            targets.add(Map.entry(output.getKey(), output.getValue()));
        }
        for (ViewPort view : views) {
            for (Map.Entry<Key<Image>, List<ExternalImage>> output : view.outputs.entrySet()) {
                for (ExternalImage target : output.getValue()) {
                    targets.add(Map.entry(g.scoped(view, output.getKey()), target));
                }
            }
        }
        for (Map.Entry<Object, ExternalImage> output : targets) {
            ExternalImage target = output.getValue();
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
        unscaled = new boolean[targetOf.length];
        for (int image = 0; image < unscaled.length; image++) {
            unscaled[image] = g.images.get(image).unscaled;
        }
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
        barriers = compiled.barriers();
        int[] historyOf = new int[newPhysicals.length]; // each physical image's history, or -1
        Arrays.fill(historyOf, -1);
        for (int h = 0; h < plans.size(); h++) {
            historyOf[plans.get(h).current()] = h;
            historyOf[plans.get(h).previous()] = h;
        }
        int[] target = barriers.target();
        barrierHistory = new int[target.length];
        for (int b = 0; b < target.length; b++) {
            barrierHistory[b] = barriers.kind()[b] == IMAGE && target[b] >= 0 ? historyOf[target[b]] : -1;
        }
        startViews(g, plans.size());
        builtSettings = Set.copyOf(g.settingsRead);
        batches = compiled.batches();
        chunkCommands = new VkCommandBuffer[batches.chunkBatch().length];
        startTooling(g);
        startHistories(pairs, keptPair, compiled);
        persistents.start(g, builds + 1L, retiring);
        dirty = false;
        currentBuild = g; // the build images and buffers resolved in passes must be of
        built = g.techniques().toArray(new Technique[0]);
        builtVersions = new long[built.length];
        for (int i = 0; i < builtVersions.length; i++) {
            builtVersions[i] = built[i].buildVersion();
        }
        builds++;
        LOG.debug("Frame graph built: {} of {} passes in {} batches, {} images in {} pooled, {} buffers in {} pooled, "
            + "{} barriers", order.length, compiled.builtPasses(), batches.queue().length, targetOf.length,
            physicals.length, bufferTargetOf.length, physicalBuffers.length, barriers.target().length);
    }

    /** The switches of the graph just built: each pass's, their views, and the images falling back with them. */
    @AllowAllocation("building the graph, when it changes")
    private void startSwitches(GraphBuilder g) {
        passSwitch = new Switch[order.length];
        List<Switch> all = new ArrayList<>();
        for (int i = 0; i < order.length; i++) {
            if (order[i].scope instanceof Switch switched) {
                passSwitch[i] = switched;
                if (!all.contains(switched)) {
                    all.add(switched);
                }
            }
        }
        imageSwitch = new Switch[targetOf.length];
        fallbackSlot = new int[targetOf.length];
        for (Map.Entry<Integer, GraphBuilder.Fallen> entry : g.fallbacks.entrySet()) {
            int image = entry.getKey();
            Switch switched = entry.getValue().switched();
            if (!all.contains(switched)) {
                all.add(switched);
            }
            Image info = g.images.get(image).image;
            Fallback fallback = entry.getValue().fallback();
            Texture texture = cached(new FallbackKey(fallback, info.width, info.height),
                d -> new Texture(d, fallback.toString(), fallback.format(), info.width, info.height, fallback.value()));
            imageSwitch[image] = switched;
            fallbackSlot[image] = texture.slot();
        }
        switches = all.toArray(new Switch[0]);
        switchViews = new ViewPort[switches.length];
        for (int s = 0; s < switches.length; s++) {
            switchViews[s] = g.viewOf(switches[s]);
            switches[s].on = switches[s].on(switchViews[s], settings);
        }
    }

    /** A fallback texture's identity in the graph's cache: its value and size. */
    private record FallbackKey(Fallback fallback, int width, int height) {
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
            for (ExternalImage target : view.outputs.values().stream().flatMap(List::stream).toList()) {
                for (int e = 0; e < externals.length; e++) {
                    if (externals[e] == target && target.texture == null) {
                        externalView[e] = view;
                    }
                }
            }
        }
        startSwitches(g);
        passViews = new ViewPort[order.length][];
        passSkipped = new boolean[order.length];
        historyView = new ViewPort[historyCount];
        boolean[] shared = new boolean[historyCount];
        for (int i = 0; i < order.length; i++) {
            if (order[i].view == null) {
                order[i].view = g.regionView(order[i].scope);
            }
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
     * The tooling of the graph just built ({@link FrameTooling#build}). With timings on or just turned off, the GPU is
     * waited for and every frame's timings collected first: the build lays them out anew.
     */
    @AllowAllocation("building the graph, when it changes")
    private void startTooling(GraphBuilder g) {
        if (tooling.waitsForBuild()) {
            waitIdle();
            for (int slot = 0; slot < framesInFlight; slot++) {
                finished(slot);
            }
        }
        tooling.build(order, g.readbacks, batches, graphicsQueue.family(), computeQueue.family());
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
        if (count > 0 && batches.queue().length > 0 && batches.queue()[0] != GRAPHICS) {
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

    /**
     * The persistent image ({@code desc}) or {@code count} buffers of {@code size} bytes by {@code key}, for the build
     * under way: kept from the builds before when they match, else made anew (the ones replaced are freed after the
     * build).
     */
    @AllowAllocation("building the graph, when it changes")
    Persistent persistent(String key, ImageDesc desc, long size, int count) {
        return persistents.get(key, desc, size, count, frame, builds + 1L);
    }

    /** How many persistent images and buffers the graph keeps. */
    int persistentCount() {
        return persistents.count();
    }

    /** Whether {@code history}'s last frame buffer holds what that frame wrote. */
    boolean historyValid(Persistent history) {
        return frame > history.firstFrame;
    }

    /** Whether history {@code index}'s last frame image holds what that frame wrote. */
    boolean historyValid(int index) {
        return frame > activeHistories[index].firstFrame;
    }

    /**
     * An output's copy: the render region of {@code from} (all of it outside views) scaled into all of {@code to}. A
     * region smaller than the image is scaled unfiltered, as filtering would blend in texels beyond its edge; a technique
     * scaling it up within its bounds does better ({@code DisplayTransform}).
     */
    void blit(PassContext ctx, Image from, Image to) {
        int width = ctx.regionWidth(from);
        int height = ctx.regionHeight(from);
        boolean whole = width == from.width && height == from.height;
        ctx.blit(from.whole(), width, height, to.whole(), whole && (width != to.width || height != to.height));
    }

    /**
     * The width of {@code view}'s render region in {@code image}: its share of an image of the view's size (null: all
     * of it).
     */
    int regionWidth(ViewPort view, Image image) {
        if (view == null || !view.dynamic() || view.width() == view.regionWidth() || unscaled[image.id]) {
            return image.width; // all of it at a fixed resolution
        }
        return Math.max(1, (int) (((long) image.width * view.regionWidth() + view.width() - 1) / view.width()));
    }

    /** The height of {@code view}'s render region in {@code image}. */
    int regionHeight(ViewPort view, Image image) {
        if (view == null || !view.dynamic() || view.height() == view.regionHeight() || unscaled[image.id]) {
            return image.height;
        }
        return Math.max(1, (int) (((long) image.height * view.regionHeight() + view.height() - 1) / view.height()));
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
        persistents.close();
        settingsWatch.close();
        retiring.close(); // the frames are done: removed textures go now
        retiring = new Retiring(frameSlots); // for what is removed after, at once
        tooling.close();
        Arrays.fill(slotFrame, -1);
        resources.close();
        activeHistories = new HistoryPair[0];
        physicals = new Physical[0];
        physicalBuffers = new PhysicalBuffer[0];
        order = new PassInfo[0];
        barriers = NO_BARRIERS;
        dirty = true;
        if (failure != null) {
            throw failure;
        }
    }

}
