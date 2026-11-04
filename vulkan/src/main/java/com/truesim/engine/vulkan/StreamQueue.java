package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memGetLong;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;
import static org.lwjgl.vulkan.VK10.vkResetCommandBuffer;
import static org.lwjgl.vulkan.VK12.VK_SEMAPHORE_TYPE_TIMELINE;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_SUBMIT_INFO_2;

import com.truesim.annotations.AllowAllocation;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Consumer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;

/**
 * Streamed uploads on the transfer queue (frame-graph-v2.md §4.13): an upload's copies are recorded and submitted at
 * once, when it is added, signalling a timeline semaphore, and the next frame recorded on the {@link FrameSlots} waits
 * for that value on the GPU ({@link FrameSlots.FrameWait}) before its first commands. So what is added is drawn from
 * the next frame, as with uploads recorded in the frame ({@link FrameUploads}), but its copies run beside the frames
 * in flight instead of on the graphics queue in the frame.
 *
 * <pre>{@code
 * StreamQueue stream = StreamQueue.open(device, slots);        // one per frame slots, shared by its users
 * if (!stream.submit(bytes, commands -> copy(commands, staging, target), staging)) {
 *     uploads.record(staging, ...);                            // over this frame's budget: in the frame, as before
 * }
 * }</pre>
 *
 * <p>Buffers written here are shared concurrently with the transfer queue's family ({@link #families}); images change
 * owner: the commands here end with a release to the graphics family, and the frame acquires them before use. Each
 * frame takes at most {@link #budget} bytes here; what comes after goes through the frame as before, so nothing is
 * drawn later than without the transfer queue. What a submit holds (a staging buffer) is freed once the transfer
 * queue finished it. Use it from the thread recording frames.
 */
@AllowAllocation("uploads are rare next to frames")
public final class StreamQueue implements AutoCloseable, FrameSlots.FrameWait {

    private static final com.truesim.engine.app.profile.Zone UPLOAD_ZONE = com.truesim.engine.app.profile.Profiler.zone("stream.upload");

    /** A command buffer and what it holds until the transfer queue reaches its value. */
    private static final class Submitted {
        final VkCommandBuffer commands;
        long value;
        AutoCloseable held;

        Submitted(VkCommandBuffer commands) {
            this.commands = commands;
        }
    }

    private final VulkanDevice device;
    private final FrameSlots slots;
    private final Queue queue;
    private final int graphicsFamily;
    private final long pool;
    private final long timeline;
    private final long counter = MemoryUtil.nmemAlloc(8); // the semaphore's value, read back
    private final ArrayDeque<Submitted> inFlight = new ArrayDeque<>();
    private final ArrayDeque<Submitted> idle = new ArrayDeque<>();
    private final java.util.concurrent.ExecutorService frees = java.util.concurrent.Executors.newSingleThreadExecutor(
        task -> {
            Thread thread = new Thread(task, "stream queue frees");
            thread.setDaemon(true);
            return thread;
        });
    private long submitted;
    private long completed;
    private long budget;
    private long frame = -1;
    private long bytesThisFrame;
    private long bytesStreamed;
    private long bytesRefused;
    private int users;
    private boolean closed;

    private StreamQueue(VulkanDevice device, FrameSlots slots, long budget) {
        this.device = device;
        this.slots = slots;
        this.queue = device.transfer();
        this.graphicsFamily = device.graphics().family();
        this.budget = budget;
        try (MemoryStack stack = stackPush()) {
            LongBuffer made = stack.mallocLong(1);
            Vulkan.check(vkCreateCommandPool(device.handle(), VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(queue.family()), null, made), "Could not create a command pool");
            pool = made.get(0);
            VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO)
                .semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE);
            Vulkan.check(vkCreateSemaphore(device.handle(), VkSemaphoreCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                .pNext(type.address()), null, made), "Could not create a timeline semaphore");
            timeline = made.get(0);
        }
        slots.addWait(this);
        // The first submit to a queue costs the driver about 10 ms (RX 9070 XT, Windows): paid here, not while
        // streaming.
        record(0, commands -> { }, null);
    }

    /**
     * The stream queue of {@code slots}' frames, made at the first call (with a budget of {@code budget} bytes per
     * frame) and shared by every caller after; each closes it once. Null for {@link FrameSlots#single()}, whose
     * callers wait for the GPU anyway.
     */
    public static StreamQueue open(VulkanDevice device, FrameSlots slots, long budget) {
        Objects.requireNonNull(device, "device");
        if (slots.isSingle()) {
            return null;
        }
        synchronized (slots) {
            StreamQueue stream = slots.stream();
            if (stream == null) {
                stream = new StreamQueue(device, slots, budget);
                slots.stream(stream);
            }
            stream.users++;
            return stream;
        }
    }

    /** Whether the transfer queue is of a family of its own: images then change owner, buffers are shared. */
    public boolean separate() {
        return queue.family() != graphicsFamily;
    }

    /** The transfer queue's family. */
    public int family() {
        return queue.family();
    }

    /** The graphics queue's family: where images are released to. */
    public int graphicsFamily() {
        return graphicsFamily;
    }

    /** Bytes a frame may take here; what comes after goes through the frame as before. */
    public long budget() {
        return budget;
    }

    /** Sets the bytes a frame may take here (0: none, everything through the frame). */
    public void budget(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("A budget in bytes: " + bytes);
        }
        this.budget = bytes;
    }

    /** Whether {@code bytes} more fit this frame's budget: for loaders that would rather wait than go through frames. */
    public boolean room(long bytes) {
        rollFrame();
        return budget > 0 && (bytesThisFrame == 0 || bytesThisFrame + bytes <= budget);
    }

    /**
     * Records {@code record} (copies reading a staging buffer, say; images end released to {@link #graphicsFamily})
     * and submits it on the transfer queue now, unless {@code bytes} do not fit this frame's budget: then nothing is
     * done and it returns false, for the caller to go through the frame. {@code held} is closed once the transfer queue
     * finished (null: nothing). The next frame recorded waits for it.
     */
    public boolean submit(long bytes, Consumer<VkCommandBuffer> record, AutoCloseable held) {
        checkOpen();
        rollFrame();
        if (budget == 0 || bytesThisFrame > 0 && bytesThisFrame + bytes > budget) { // a frame's first always fits
            bytesRefused += bytes;
            return false;
        }
        com.truesim.engine.app.profile.Profiler.begin(UPLOAD_ZONE);
        record(bytes, record, held);
        com.truesim.engine.app.profile.Profiler.end(UPLOAD_ZONE);
        return true;
    }

    /** Records and submits {@code record}, signalling the next value; {@code held} freed once it is reached. */
    private void record(long bytes, Consumer<VkCommandBuffer> record, AutoCloseable held) {
        release();
        Submitted next = idle.poll();
        if (next == null) {
            next = new Submitted(commandBuffer());
        }
        try (MemoryStack stack = stackPush()) {
            Vulkan.check(vkResetCommandBuffer(next.commands, 0), "Could not reset a command buffer");
            long begin = stack.ncalloc(8, 1, org.lwjgl.vulkan.VkCommandBufferBeginInfo.SIZEOF);
            memPutInt(begin + org.lwjgl.vulkan.VkCommandBufferBeginInfo.STYPE,
                VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            memPutInt(begin + org.lwjgl.vulkan.VkCommandBufferBeginInfo.FLAGS,
                VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            Vulkan.check(Commands.beginCommandBuffer(next.commands, begin), "Could not begin recording");
            record.accept(next.commands);
            Vulkan.check(Commands.endCommandBuffer(next.commands), "Could not finish recording");
            long info = stack.ncalloc(VkCommandBufferSubmitInfo.ALIGNOF, 1, VkCommandBufferSubmitInfo.SIZEOF);
            memPutInt(info + VkCommandBufferSubmitInfo.STYPE, VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO);
            memPutAddress(info + VkCommandBufferSubmitInfo.COMMANDBUFFER, next.commands.address());
            long signal = stack.ncalloc(VkSemaphoreSubmitInfo.ALIGNOF, 1, VkSemaphoreSubmitInfo.SIZEOF);
            memPutInt(signal + VkSemaphoreSubmitInfo.STYPE, VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO);
            memPutLong(signal + VkSemaphoreSubmitInfo.SEMAPHORE, timeline);
            memPutLong(signal + VkSemaphoreSubmitInfo.VALUE, submitted + 1);
            memPutLong(signal + VkSemaphoreSubmitInfo.STAGEMASK, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            long submit = stack.ncalloc(VkSubmitInfo2.ALIGNOF, 1, VkSubmitInfo2.SIZEOF);
            memPutInt(submit + VkSubmitInfo2.STYPE, VK_STRUCTURE_TYPE_SUBMIT_INFO_2);
            memPutInt(submit + VkSubmitInfo2.COMMANDBUFFERINFOCOUNT, 1);
            memPutAddress(submit + VkSubmitInfo2.PCOMMANDBUFFERINFOS, info);
            memPutInt(submit + VkSubmitInfo2.SIGNALSEMAPHOREINFOCOUNT, 1);
            memPutAddress(submit + VkSubmitInfo2.PSIGNALSEMAPHOREINFOS, signal);
            queue.submit(1, submit, 0);
        } catch (RuntimeException e) {
            idle.push(next);
            throw e;
        }
        next.value = ++submitted;
        next.held = held;
        inFlight.add(next);
        bytesThisFrame += bytes;
        bytesStreamed += bytes;
    }

    /** The budget starts over with each frame begun. */
    private void rollFrame() {
        if (slots.frames() != frame) {
            frame = slots.frames();
            bytesThisFrame = 0;
        }
    }

    private VkCommandBuffer commandBuffer() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer handle = stack.mallocPointer(1);
            Vulkan.check(vkAllocateCommandBuffers(device.handle(), VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(pool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1), handle), "Could not allocate a command buffer");
            return new VkCommandBuffer(handle.get(0), device.handle());
        }
    }

    /** Frees what submits the transfer queue finished held, and keeps their command buffers. Allocates nothing. */
    public void release() {
        if (inFlight.isEmpty()) {
            return;
        }
        device.check(device.direct().semaphoreCounterValue(timeline, counter), "Could not read a semaphore");
        completed = memGetLong(counter);
        while (!inFlight.isEmpty() && inFlight.peek().value <= completed) {
            Submitted done = inFlight.poll();
            if (done.held != null) {
                // Freeing a staging buffer took up to 2.4 ms (a shared arena closed, the allocation freed): not here
                AutoCloseable held = done.held;
                frees.execute(() -> close(held));
            }
            done.held = null;
            idle.push(done);
        }
    }

    private static void close(AutoCloseable held) {
        if (held == null) {
            return;
        }
        try {
            held.close();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not free " + held, e);
        }
    }

    /** The timeline semaphore the transfer queue signals. */
    @Override
    public long semaphore() {
        return timeline;
    }

    /** The value of the last submit: what the next frame waits for (0: none yet). */
    @Override
    public long value() {
        return submitted;
    }

    /** Bytes streamed here so far, and refused (over a frame's budget, gone through frames). */
    public long bytesStreamed() {
        return bytesStreamed;
    }

    public long bytesRefused() {
        return bytesRefused;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The stream queue is closed");
        }
    }

    /** Gives back one user's share; the last one frees it, once the transfer queue is idle. */
    @Override
    public void close() {
        synchronized (slots) {
            if (closed || --users > 0) {
                return;
            }
            closed = true;
            slots.stream(null);
            slots.removeWait(this);
        }
        queue.waitIdle();
        while (!inFlight.isEmpty()) {
            close(inFlight.poll().held);
        }
        frees.shutdown();
        try {
            if (!frees.awaitTermination(1, java.util.concurrent.TimeUnit.MINUTES)) {
                throw new IllegalStateException("Staging buffers were not freed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while freeing staging buffers", e);
        }
        vkDestroyCommandPool(device.handle(), pool, null);
        vkDestroySemaphore(device.handle(), timeline, null);
        MemoryUtil.nmemFree(counter);
    }
}
