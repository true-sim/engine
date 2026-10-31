package com.truesim.engine.graph;

import static com.truesim.engine.graph.FrameGraph.COMPUTE;
import static com.truesim.engine.graph.FrameGraph.GRAPHICS;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
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
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_SUBMIT_INFO_2;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Queue;
import com.truesim.engine.vulkan.Vulkan;
import com.truesim.engine.vulkan.VulkanDevice;
import java.nio.LongBuffer;
import java.util.Arrays;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;

/**
 * Each frame in flight's command pools (one per worker and queue, so workers record without locks) and the two
 * timeline semaphores that order batches across queues and tell when a frame is done.
 */
@AllowAllocation("set up once")
final class FrameExecution implements AutoCloseable {

    private final long graphicsTimeline;
    private final long computeTimeline;
    private long graphicsValue;
    private long computeValue;
    private final long[][] pools; // [slot][worker * 2 + queue]
    private final VkCommandBuffer[][][] buffers; // [slot][worker * 2 + queue][i]
    private final int[] used;
    private final long[] slotGraphicsValue;
    private final long[] slotComputeValue;
    private final VulkanDevice device;
    private final Queue graphicsQueue;
    private final Queue computeQueue;
    private final int framesInFlight;
    private int slot = -1;
    boolean begun;

    /** For {@code framesInFlight} frames recorded by {@code workers} workers onto the two queues. */
    FrameExecution(VulkanDevice device, Queue graphicsQueue, Queue computeQueue, int framesInFlight, int workers) {
        this.device = device;
        this.graphicsQueue = graphicsQueue;
        this.computeQueue = computeQueue;
        this.framesInFlight = framesInFlight;
        graphicsTimeline = timeline();
        computeTimeline = timeline();
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

    /** The frame slot begun last. */
    int slot() {
        return slot;
    }

    /**
     * Begins the next frame slot: waits until the GPU finished the frame that last used it, and resets its command
     * pools. Allocates nothing.
     */
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
    void ensureCommandBuffers(CompiledGraph.Batches batches) {
        int graphicsChunks = batches.graphicsChunks();
        int computeChunks = batches.computeChunks();
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

    /**
     * Submits {@code batches} in order, each to its queue: their chunks' command buffers {@code chunkCommands}, and
     * {@code frameStart} (null: none) ahead of the first graphics chunk. Allocates nothing.
     */
    void submit(CompiledGraph.Batches batches, VkCommandBuffer frameStart, VkCommandBuffer[] chunkCommands,
        long[] acquired, int acquiredCount, long[] renderFinished, int finishedCount) {
        int[] batchQueue = batches.queue();
        int[] batchFirstChunk = batches.firstChunk();
        int[] batchWait = batches.waitFor();
        int firstGraphicsChunk = batches.firstGraphicsChunk();
        int lastGraphicsBatch = batches.lastGraphicsBatch();
        long graphicsBase = graphicsValue;
        long computeBase = computeValue;
        boolean acquireWaited = acquiredCount == 0;
        // Both queues once the compute queue ever ran: a build without async passes still follows one with them,
        // whose compute work of the frames in flight may use what this frame does (pooled images, histories).
        boolean bothQueues = computeValue > 0;
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
                boolean acquire = !acquireWaited && (batches.acquire()[b] || b == lastGraphicsBatch);
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
                    (queue == GRAPHICS ? graphicsBase : computeBase) + batches.ordinal()[b]);
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
        graphicsValue += batches.graphicsBatches();
        computeValue += batches.computeBatches();
        slotGraphicsValue[slot] = graphicsValue;
        slotComputeValue[slot] = computeValue;
        begun = false;
    }

    /**
     * Gives up the frame begun, after recording failed: nothing of it was submitted, its command buffers are reset
     * when its slot comes again, and the next frame may begin.
     */
    void abandon() {
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
