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
import static org.lwjgl.vulkan.VK10.nvkCmdBlitImage;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;
import static org.lwjgl.vulkan.VK13.nvkCmdPipelineBarrier2;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder.ImageInfo;
import com.truesim.engine.graph.GraphBuilder.PassInfo;
import com.truesim.engine.vulkan.Bindless;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;
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
 *   <li><b>Pooled:</b> transient images live in a pool kept across frames, and images whose lifetimes do not overlap
 *       share one; an output is written straight into its target when it fits.</li>
 * </ul>
 *
 * <p>The graph is built (techniques' {@code build}, resolving, culling, barriers, images) when it changes: techniques
 * or outputs added or removed, an external image resized, or {@link #invalidate()}. Recording a built graph allocates
 * nothing. Per-frame data (cameras, parameters) belongs to the techniques, which their pass bodies read when recording.
 * One thread records; close the graph once the device is idle.
 */
@AllowAllocation("set up once")
public final class FrameGraph implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FrameGraph.class);

    private final VulkanDevice device;
    private final Bindless bindless;
    private final int framesInFlight;
    private final List<Technique> techniques = new ArrayList<>();
    private final Map<Key, ExternalImage> outputs = new LinkedHashMap<>();
    private final Map<Class<?>, Implementation<?, ?>> implementations = new HashMap<>();
    private final Map<Object, AutoCloseable> cache = new LinkedHashMap<>();
    private final List<Physical> pool = new ArrayList<>();
    private final List<Physical> retired = new ArrayList<>();
    private final PassContext context = new PassContext(this);
    private boolean dirty = true;
    private long frame;
    private int builds;

    // The built graph: passes in order, the barriers before each (from barrierStart[i] to barrierStart[i + 1]; the
    // last range is after all passes), and where each image of the build lives: a physical image (index >= 0) or an
    // external one (~index).
    private PassInfo[] order = new PassInfo[0];
    private int[] barrierStart = {0, 0};
    private int[] barrierTarget = new int[0];
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

    /** A graph on {@code device} whose frames may be {@code framesInFlight} deep (images retire after that). */
    public FrameGraph(VulkanDevice device, int framesInFlight) {
        if (framesInFlight < 1) {
            throw new IllegalArgumentException("At least one frame in flight: " + framesInFlight);
        }
        this.device = Objects.requireNonNull(device, "device");
        this.bindless = device.bindless();
        this.framesInFlight = framesInFlight;
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

    // ---------------------------------------------------------------- recording

    /**
     * Records the frame into {@code commands}: the bindless set, then each needed pass with its barriers, then the
     * outputs' final layouts. Builds the graph first if it changed; otherwise allocates nothing.
     */
    public void record(VkCommandBuffer commands) {
        if (dirty || externalsResized()) {
            build();
        }
        frame++;
        if (!retired.isEmpty()) {
            release(false);
        }
        bindless.bind(commands);
        context.commands = commands;
        for (int i = 0; i < order.length; i++) {
            barriers(commands, barrierStart[i], barrierStart[i + 1]);
            order[i].body.record(context);
        }
        barriers(commands, barrierStart[order.length], barrierStart[order.length + 1]);
        context.commands = null;
    }

    private boolean externalsResized() {
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
            long array = stack.ncalloc(VkImageMemoryBarrier2.ALIGNOF, to - from, VkImageMemoryBarrier2.SIZEOF);
            for (int b = from; b < to; b++) {
                long barrier = array + (long) (b - from) * VkImageMemoryBarrier2.SIZEOF;
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
                memPutInt(range + VkImageSubresourceRange.LEVELCOUNT, 1);
                memPutInt(range + VkImageSubresourceRange.LAYERCOUNT, 1);
            }
            long dependency = stack.ncalloc(VkDependencyInfo.ALIGNOF, 1, VkDependencyInfo.SIZEOF);
            memPutInt(dependency + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
            memPutInt(dependency + VkDependencyInfo.IMAGEMEMORYBARRIERCOUNT, to - from);
            memPutAddress(dependency + VkDependencyInfo.PIMAGEMEMORYBARRIERS, array);
            nvkCmdPipelineBarrier2(commands, dependency);
        }
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

    int storageSlotOf(int image) {
        return physicals[targetOf[image]].storage;
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

    /** The transient images in the pool, for inspection. */
    public int pooledImages() {
        return pool.size();
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
        // Culled: walking back from the outputs, a pass is needed if it writes an image needed after it.
        List<PassInfo> passes = g.passes;
        boolean[] keep = new boolean[passes.size()];
        for (int p = passes.size() - 1; p >= 0; p--) {
            PassInfo pass = passes.get(p);
            boolean writesNeeded = pass.sideEffects;
            for (int a = 0; a < pass.images.length; a++) {
                writesNeeded |= pass.accesses[a].writes && needed[pass.images[a]];
            }
            if (writesNeeded) {
                keep[p] = true;
                for (int image : pass.images) {
                    needed[image] = true;
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
        // Where images live: external ones (and outputs drawn into their target) in their external image, transient
        // ones in physical images, shared by images of one size and format whose lifetimes do not overlap.
        List<ExternalImage> newExternals = new ArrayList<>();
        int[] newTargetOf = new int[imageCount];
        int[] first = new int[imageCount];
        int[] last = new int[imageCount];
        Arrays.fill(first, -1);
        for (int i = 0; i < newOrder.length; i++) {
            for (int image : newOrder[i].images) {
                if (first[image] < 0) {
                    first[image] = i;
                }
                last[image] = i;
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
        for (int image : byFirstUse) {
            if (first[image] < 0) {
                continue; // culled
            }
            ImageInfo info = g.images.get(image);
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
                if (candidate.last < first[image] && candidate.width == info.image.width
                    && candidate.height == info.image.height && candidate.format == info.image.format) {
                    slot = candidate;
                    break;
                }
            }
            if (slot == null) {
                slot = new Slot(info.image.width, info.image.height, info.image.format);
                slots.add(slot);
            }
            slot.last = last[image];
            slot.usage |= info.usage;
            newTargetOf[image] = slots.indexOf(slot);
        }
        Physical[] newPhysicals = claim(slots);
        // Barriers, now that every image has a home.
        Barriers barriers = new Barriers(newPhysicals.length, newExternals);
        barriers.simulate(newOrder, newTargetOf, g, false); // the state a frame ends in is the next one's start
        barriers.simulate(newOrder, newTargetOf, g, true);

        order = newOrder;
        targetOf = newTargetOf;
        physicals = newPhysicals;
        externals = newExternals.toArray(new ExternalImage[0]);
        externalVersions = new int[externals.length];
        for (int i = 0; i < externals.length; i++) {
            externalVersions[i] = externals[i].version;
        }
        barriers.store();
        dirty = false;
        builds++;
        LOG.debug("Frame graph built: {} of {} passes, {} images in {} pooled, {} barriers", order.length,
            passes.size(), imageCount, physicals.length, barrierTarget.length);
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
                    && candidate.format == slot.format && candidate.usage == slot.usage) {
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

    /** Destroys retired images whose frames have finished (or all, when closing). */
    private void release(boolean all) {
        for (int i = retired.size() - 1; i >= 0; i--) {
            Physical physical = retired.get(i);
            if (all || physical.retireAt <= frame) {
                physical.close();
                retired.remove(i);
            }
        }
    }

    private static void blit(PassContext ctx, Image from, Image to) {
        try (MemoryStack stack = stackPush()) {
            long region = stack.ncalloc(VkImageBlit.ALIGNOF, 1, VkImageBlit.SIZEOF);
            layers(region + VkImageBlit.SRCSUBRESOURCE);
            layers(region + VkImageBlit.DSTSUBRESOURCE);
            offsets(region + VkImageBlit.SRCOFFSETS, from.width, from.height);
            offsets(region + VkImageBlit.DSTOFFSETS, to.width, to.height);
            boolean same = from.width == to.width && from.height == to.height;
            nvkCmdBlitImage(ctx.commands(), ctx.image(from), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
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
        release(true);
        physicals = new Physical[0];
        order = new PassInfo[0];
        barrierStart = new int[] {0, 0};
        dirty = true;
    }

    /** A physical image the pool is to have: a size, a format and every usage of the images sharing it. */
    private static final class Slot {
        final int width;
        final int height;
        final int format;
        int usage;
        int last;

        Slot(int width, int height, int format) {
            this.width = width;
            this.height = height;
            this.format = format;
        }
    }

    /** A pooled image with its bindless slots. */
    @AllowAllocation("building the graph, when it changes")
    private final class Physical {
        final GpuImage image;
        final int width;
        final int height;
        final int format;
        final int usage;
        final int sampled;
        final int storage;
        long retireAt;

        Physical(Slot slot) {
            width = slot.width;
            height = slot.height;
            format = slot.format;
            usage = slot.usage;
            image = new GpuImage(device, width, height, format, usage, aspect(format));
            sampled = (usage & VK_IMAGE_USAGE_SAMPLED_BIT) != 0 ? bindless.addSampled(image.view()) : -1;
            storage = (usage & VK_IMAGE_USAGE_STORAGE_BIT) != 0 ? bindless.addStorage(image.view()) : -1;
        }

        void close() {
            if (sampled >= 0) {
                bindless.removeSampled(sampled);
            }
            if (storage >= 0) {
                bindless.removeStorage(storage);
            }
            image.close();
        }
    }

    /**
     * Derives barriers by walking the passes in order and tracking, per physical or external image, its layout, the
     * stages and accesses that last wrote it, and the stages that read it since. A pass's barriers are batched.
     */
    @AllowAllocation("building the graph, when it changes")
    private final class Barriers {
        final int physicalCount;
        final List<ExternalImage> externalList;
        final int[] layout;
        final long[] writeStages;
        final long[] writeAccess;
        final long[] readStages;
        final long[] readAccess;
        final int[] tenant;
        final List<long[]> out = new ArrayList<>(); // target, aspect, old, new, srcStage, srcAccess, dstStage, dstAccess
        final List<Integer> starts = new ArrayList<>();

        Barriers(int physicalCount, List<ExternalImage> externalList) {
            this.physicalCount = physicalCount;
            this.externalList = externalList;
            int n = physicalCount + externalList.size();
            layout = new int[n];
            writeStages = new long[n];
            writeAccess = new long[n];
            readStages = new long[n];
            readAccess = new long[n];
            tenant = new int[n];
            Arrays.fill(tenant, -1);
        }

        private int index(int target) {
            return target >= 0 ? target : physicalCount + ~target;
        }

        /** Walks the passes; with {@code emit} off, only to learn the state a frame leaves its images in. */
        void simulate(PassInfo[] passes, int[] targets, GraphBuilder g, boolean emit) {
            for (int e = 0; e < externalList.size(); e++) {
                ExternalImage external = externalList.get(e);
                int i = physicalCount + e;
                layout[i] = external.initialLayout;
                writeStages[i] = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT; // whatever came before the frame
                writeAccess[i] = external.initialLayout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_2_MEMORY_WRITE_BIT;
                readStages[i] = 0;
                readAccess[i] = 0;
                tenant[i] = -1;
            }
            Arrays.fill(tenant, 0, physicalCount, -1); // transient contents never outlive their frame
            for (PassInfo pass : passes) {
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
                    access(pass, image, targets[image], g, stages, access, writes, wanted, emit);
                }
            }
            if (emit) {
                starts.add(out.size());
                for (int e = 0; e < externalList.size(); e++) {
                    int i = physicalCount + e;
                    ExternalImage external = externalList.get(e);
                    out.add(new long[] {~e, aspect(external.format), layout[i], external.finalLayout,
                        writeStages[i] | readStages[i], writeAccess[i], VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                        VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT}); // whatever comes after the frame
                }
            }
        }

        private void access(PassInfo pass, int image, int target, GraphBuilder g, long stages, long access,
            boolean writes, int wanted, boolean emit) {
            int i = index(target);
            int aspect = aspect(g.images.get(image).image.format);
            boolean transient_ = target >= 0;
            if (transient_ && tenant[i] != image) {
                // A new image in this physical one: its contents start undefined, after whatever used it before.
                if (!writes) {
                    throw new IllegalStateException(pass.name + " reads " + g.images.get(image).image.name
                        + " before any pass writes it");
                }
                if (emit) {
                    out.add(new long[] {target, aspect, VK_IMAGE_LAYOUT_UNDEFINED, wanted,
                        writeStages[i] | readStages[i], writeAccess[i], stages, access});
                }
                tenant[i] = image;
                set(i, wanted, stages, access);
                return;
            }
            boolean transition = layout[i] != wanted;
            if (writes || transition) {
                long src = writeStages[i] | readStages[i];
                if (emit && (src != 0 || transition)) {
                    out.add(new long[] {target, aspect, layout[i], wanted, src, writeAccess[i], stages, access});
                }
                if (writes) {
                    set(i, wanted, stages, access);
                } else {
                    // A transition orders like a write: later readers wait for it, later writers for this pass.
                    set(i, wanted, stages, VK_ACCESS_2_MEMORY_WRITE_BIT);
                    readStages[i] = stages;
                    readAccess[i] = access;
                }
                return;
            }
            // Read after write, in the same layout: one barrier per new reading stage, none after that.
            if ((stages & ~readStages[i]) != 0 || (access & ~readAccess[i]) != 0) {
                if (emit && writeStages[i] != 0) {
                    out.add(new long[] {target, aspect, wanted, wanted, writeStages[i], writeAccess[i], stages,
                        access});
                }
                readStages[i] |= stages;
                readAccess[i] |= access;
            }
        }

        private void set(int i, int newLayout, long stages, long access) {
            layout[i] = newLayout;
            writeStages[i] = stages;
            writeAccess[i] = access;
            readStages[i] = 0;
            readAccess[i] = 0;
        }

        void store() {
            int n = out.size();
            barrierStart = new int[starts.size() + 1];
            for (int s = 0; s < starts.size(); s++) {
                barrierStart[s] = starts.get(s);
            }
            barrierStart[starts.size()] = n;
            barrierTarget = new int[n];
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
