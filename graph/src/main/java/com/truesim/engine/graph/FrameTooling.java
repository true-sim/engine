package com.truesim.engine.graph;

import static com.truesim.engine.graph.FrameGraph.GRAPHICS;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_LABEL_EXT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder.PassInfo;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDebugUtilsLabelEXT;

/**
 * {@link FrameGraph}'s tooling: the readbacks of the build and of builds before still pending in frames in flight, GPU
 * timings of every pass when on, and a debug label per pass where the instance has debug utils.
 */
@AllowAllocation("set up once")
final class FrameTooling {

    private final VulkanDevice device;
    private final int framesInFlight;
    private ReadbackPlan[] readbacks = new ReadbackPlan[0];
    private ReadbackPlan[] retiredReadbacks = new ReadbackPlan[0]; // an earlier build's, pending in frames in flight
    private int retiredCount;
    private boolean timingsOn;
    private GpuTimings timings;
    private Arena labelArena;
    private long[] passLabels = new long[0];

    FrameTooling(VulkanDevice device, int framesInFlight) {
        this.device = device;
        this.framesInFlight = framesInFlight;
    }

    /** Turns the timings on or off; whether that changed, so the graph rebuilds. */
    boolean timings(boolean on) {
        boolean changed = on != timingsOn;
        timingsOn = on;
        if (on && timings == null) {
            timings = new GpuTimings(device, framesInFlight); // its queries come with the build
        }
        return changed;
    }

    /** The last finished frame's timings while on; null otherwise. */
    GpuTimings timings() {
        return timingsOn ? timings : null;
    }

    /** Whether a build must wait for the GPU first: timings, being laid out by the build, are collected first. */
    boolean waitsForBuild() {
        return timingsOn || timings != null;
    }

    /**
     * The GPU finished frame {@code frame}, which frame slot {@code slot} recorded: its readbacks are delivered, its
     * timings read. Allocates nothing.
     */
    void finished(int slot, long frame) {
        if (timings != null) {
            timings.collect(slot, frame);
        }
        for (ReadbackPlan readback : readbacks) {
            readback.deliver(slot);
        }
        int kept = 0;
        for (int i = 0; i < retiredCount; i++) { // an earlier build's: delivered, then freed once none is pending
            ReadbackPlan readback = retiredReadbacks[i];
            readback.deliver(slot);
            if (readback.pending()) {
                retiredReadbacks[kept++] = readback;
            } else {
                readback.close();
            }
        }
        for (int i = kept; i < retiredCount; i++) {
            retiredReadbacks[i] = null;
        }
        retiredCount = kept;
    }

    /**
     * The tooling of the graph just built, passes {@code order} run as {@code batches} on queue families
     * {@code graphicsFamily} and {@code computeFamily}: its {@code built} readbacks' host memory, the timings' queries
     * and the passes' debug labels. Readbacks of the build before still pending are kept till their frames finish, so
     * a rebuild (a buffer grown while streaming) waits for no GPU; with {@link #waitsForBuild} the caller waited.
     */
    @AllowAllocation("building the graph, when it changes")
    void build(PassInfo[] order, List<ReadbackPlan> built, CompiledGraph.Batches batches, int graphicsFamily,
        int computeFamily) {
        ReadbackPlan[] kept = Arrays.copyOf(retiredReadbacks, retiredCount + readbacks.length);
        int keptCount = retiredCount;
        for (ReadbackPlan readback : readbacks) {
            if (readback.pending()) {
                kept[keptCount++] = readback;
            } else {
                readback.close();
            }
        }
        retiredReadbacks = kept;
        retiredCount = keptCount;
        readbacks = built.toArray(new ReadbackPlan[0]);
        for (ReadbackPlan readback : readbacks) {
            readback.allocate(device, framesInFlight, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        }
        if (timingsOn) {
            if (timings == null) {
                timings = new GpuTimings(device, framesInFlight);
            }
            int[] families = new int[order.length];
            for (int c = 0; c < batches.chunkBatch().length; c++) {
                int family = batches.queue()[batches.chunkBatch()[c]] == GRAPHICS ? graphicsFamily : computeFamily;
                Arrays.fill(families, batches.chunkFirstPass()[c], batches.chunkEndPass()[c], family);
            }
            timings.build(order, families);
        } else if (timings != null) {
            timings.close();
            timings = null;
        }
        closeLabels();
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
     * Before pass {@code pass}, recorded in frame slot {@code slot}: its label opened and its start timed. Whether it
     * was labelled, for {@link #endPass}. Allocates nothing.
     */
    boolean beginPass(VkCommandBuffer commands, int slot, int pass) {
        boolean labelled = passLabels.length > 0 && Commands.beginLabel(commands, passLabels[pass]);
        if (timingsOn && timings != null) {
            timings.write(commands, slot, pass, false);
        }
        return labelled;
    }

    /** After pass {@code pass}: its end timed and, if {@code labelled}, its label closed. Allocates nothing. */
    void endPass(VkCommandBuffer commands, int slot, int pass, boolean labelled) {
        if (timingsOn && timings != null) {
            timings.write(commands, slot, pass, true);
        }
        if (labelled) {
            Commands.endLabel(commands);
        }
    }

    private void closeLabels() {
        if (labelArena != null) {
            labelArena.close();
            labelArena = null;
            passLabels = new long[0];
        }
    }

    /** Frees the readbacks, the timings' queries and the labels; the frames are done. */
    void close() {
        for (ReadbackPlan readback : readbacks) {
            readback.close();
        }
        readbacks = new ReadbackPlan[0];
        for (int i = 0; i < retiredCount; i++) {
            retiredReadbacks[i].close();
        }
        retiredReadbacks = new ReadbackPlan[0];
        retiredCount = 0;
        if (timings != null) {
            timings.close();
            timings = null;
        }
        closeLabels();
    }
}
