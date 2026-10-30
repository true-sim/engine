package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_FENCE_CREATE_SIGNALED_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyFence;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_SUBMIT_INFO_2;

import com.truesim.annotations.AllowAllocation;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;

/**
 * Frames in flight: while the GPU renders one frame, the CPU records the next. Each frame has a fence, an
 * image-available semaphore, and one command pool and primary command buffer per worker, so worker threads (e.g. the
 * workers of a common-jobs {@code JobSystem}, by worker index) record in parallel without locks:
 *
 * <pre>{@code
 * Frames.Frame frame = frames.next();                     // waits until this frame's previous use completed
 * int image = swapchain.acquire(frame.imageAvailable());
 * VkCommandBuffer commands = frame.begin(worker);         // on each worker that records
 * ...
 * frame.end(worker);
 * frame.submit(device.graphics(), swapchain.presentReady(image));
 * swapchain.present(device.graphics(), image);
 * }</pre>
 *
 * <p>Command buffers are submitted in worker order, so the GPU sees the same order however the recording was
 * scheduled. Per frame, nothing is allocated.
 */
public final class Frames implements AutoCloseable {

    private final VulkanDevice device;
    private final Frame[] frames;
    private int current = -1;
    private boolean closed;

    /** {@code inFlight} frames (2 or 3 is typical) with command buffers for {@code workers} recording threads. */
    @AllowAllocation("set up once")
    public Frames(VulkanDevice device, Queue queue, int inFlight, int workers) {
        if (inFlight < 1 || workers < 1) {
            throw new IllegalArgumentException("Need at least one frame and one worker: " + inFlight + ", " + workers);
        }
        this.device = device;
        this.frames = new Frame[inFlight];
        for (int i = 0; i < inFlight; i++) {
            try {
                frames[i] = new Frame(i, queue.family(), workers);
            } catch (RuntimeException e) {
                for (int made = 0; made < i; made++) {
                    frames[made].destroy(); // the frames made before leak nothing
                }
                throw e;
            }
        }
    }

    /** The next frame: waits until the GPU finished its previous use, then resets its command buffers. */
    public Frame next() {
        current = (current + 1) % frames.length;
        Frame frame = frames[current];
        frame.reset();
        return frame;
    }

    public int inFlight() {
        return frames.length;
    }

    /** One frame's synchronization and command buffers. */
    public final class Frame {

        private final int index;
        // 0 until made, so a frame that fails to set up frees what it made (destroying 0 is a no-op).
        private long fence;
        private long imageAvailable;
        private final long[] pools;
        private final VkCommandBuffer[] commands;
        private final boolean[] recorded;

        @AllowAllocation("set up once")
        private Frame(int index, int family, int workers) {
            this.index = index;
            this.pools = new long[workers];
            this.commands = new VkCommandBuffer[workers];
            this.recorded = new boolean[workers];
            try {
                create(family, workers);
            } catch (RuntimeException e) {
                destroy();
                throw e;
            }
        }

        private void create(int family, int workers) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer handle = stack.mallocLong(1);
                Vulkan.check(vkCreateFence(device.handle(), VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT), null, handle), "Could not create a fence");
                this.fence = handle.get(0);
                Vulkan.check(vkCreateSemaphore(device.handle(), VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO), null, handle), "Could not create a semaphore");
                this.imageAvailable = handle.get(0);
                for (int w = 0; w < workers; w++) {
                    Vulkan.check(vkCreateCommandPool(device.handle(), VkCommandPoolCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                        .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                        .queueFamilyIndex(family), null, handle), "Could not create a command pool");
                    pools[w] = handle.get(0);
                    PointerBuffer buffer = stack.mallocPointer(1);
                    Vulkan.check(vkAllocateCommandBuffers(device.handle(), VkCommandBufferAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                        .commandPool(pools[w])
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(1), buffer), "Could not allocate a command buffer");
                    commands[w] = new VkCommandBuffer(buffer.get(0), device.handle());
                }
            }
        }

        private void reset() {
            try (MemoryStack stack = stackPush()) {
                long fences = stack.nmalloc(Long.BYTES, Long.BYTES);
                memPutLong(fences, fence);
                Vulkan.check(Commands.waitForFences(device.handle(), 1, fences, true, Long.MAX_VALUE),
                    "Could not wait for a frame");
            }
            for (int w = 0; w < pools.length; w++) {
                Vulkan.check(Commands.resetCommandPool(device.handle(), pools[w], 0), "Could not reset a command pool");
                recorded[w] = false;
            }
        }

        /** Which of the frames in flight this is. */
        public int index() {
            return index;
        }

        /** Signal this when acquiring a swapchain image; the submit waits for it. */
        public long imageAvailable() {
            return imageAvailable;
        }

        /** Starts recording worker {@code worker}'s command buffer; call from that worker. */
        public VkCommandBuffer begin(int worker) {
            try (MemoryStack stack = stackPush()) {
                long info = stack.ncalloc(VkCommandBufferBeginInfo.ALIGNOF, 1, VkCommandBufferBeginInfo.SIZEOF);
                memPutInt(info + VkCommandBufferBeginInfo.STYPE, VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
                memPutInt(info + VkCommandBufferBeginInfo.FLAGS, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                Vulkan.check(Commands.beginCommandBuffer(commands[worker], info), "Could not begin recording");
            }
            return commands[worker];
        }

        /** Finishes recording worker {@code worker}'s command buffer. */
        public void end(int worker) {
            Vulkan.check(Commands.endCommandBuffer(commands[worker]), "Could not finish recording");
            recorded[worker] = true;
        }

        /**
         * Submits the recorded command buffers in worker order to {@code queue}: they wait for
         * {@link #imageAvailable()} before writing color attachments and signal {@code renderFinished} (e.g. the
         * swapchain's {@link Swapchain#presentReady(int)}; 0 for none). Pass {@code waitForImage} false when nothing was
         * acquired, e.g. offscreen rendering.
         */
        public void submit(Queue queue, long renderFinished, boolean waitForImage) {
            // Written into stack memory by address: no struct wrappers, so submitting allocates nothing.
            try (MemoryStack stack = stackPush()) {
                int count = 0;
                for (boolean done : recorded) {
                    count += done ? 1 : 0;
                }
                long buffers = stack.ncalloc(VkCommandBufferSubmitInfo.ALIGNOF, count, VkCommandBufferSubmitInfo.SIZEOF);
                long next = buffers;
                for (int w = 0; w < commands.length; w++) {
                    if (recorded[w]) {
                        memPutInt(next + VkCommandBufferSubmitInfo.STYPE, VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO);
                        memPutAddress(next + VkCommandBufferSubmitInfo.COMMANDBUFFER, commands[w].address());
                        next += VkCommandBufferSubmitInfo.SIZEOF;
                    }
                }
                long submit = stack.ncalloc(VkSubmitInfo2.ALIGNOF, 1, VkSubmitInfo2.SIZEOF);
                memPutInt(submit + VkSubmitInfo2.STYPE, VK_STRUCTURE_TYPE_SUBMIT_INFO_2);
                memPutInt(submit + VkSubmitInfo2.COMMANDBUFFERINFOCOUNT, count);
                memPutAddress(submit + VkSubmitInfo2.PCOMMANDBUFFERINFOS, buffers);
                if (waitForImage) {
                    memPutInt(submit + VkSubmitInfo2.WAITSEMAPHOREINFOCOUNT, 1);
                    memPutAddress(submit + VkSubmitInfo2.PWAITSEMAPHOREINFOS, semaphoreInfo(stack, imageAvailable));
                }
                if (renderFinished != 0) {
                    memPutInt(submit + VkSubmitInfo2.SIGNALSEMAPHOREINFOCOUNT, 1);
                    memPutAddress(submit + VkSubmitInfo2.PSIGNALSEMAPHOREINFOS, semaphoreInfo(stack, renderFinished));
                }
                long fences = stack.nmalloc(Long.BYTES, Long.BYTES);
                memPutLong(fences, fence);
                Vulkan.check(Commands.resetFences(device.handle(), 1, fences), "Could not reset a fence");
                queue.submit(1, submit, fence);
            }
        }

        private static long semaphoreInfo(MemoryStack stack, long semaphore) {
            long info = stack.ncalloc(VkSemaphoreSubmitInfo.ALIGNOF, 1, VkSemaphoreSubmitInfo.SIZEOF);
            memPutInt(info + VkSemaphoreSubmitInfo.STYPE, VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO);
            memPutLong(info + VkSemaphoreSubmitInfo.SEMAPHORE, semaphore);
            // All commands: the swapchain image may be written by transfers too (a blit, a clear), not only as an
            // attachment, so waiting and signalling at the attachment stage alone would let those race the present.
            memPutLong(info + VkSemaphoreSubmitInfo.STAGEMASK, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            return info;
        }

        private void destroy() {
            vkDestroyFence(device.handle(), fence, null);
            vkDestroySemaphore(device.handle(), imageAvailable, null);
            for (long pool : pools) {
                vkDestroyCommandPool(device.handle(), pool, null);
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        device.waitIdle();
        for (Frame frame : frames) {
            frame.destroy();
        }
    }
}
