package com.truesim.engine.vulkan;

import static org.lwjgl.vulkan.KHRSwapchain.nvkQueuePresentKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkQueuePresentKHR;
import static org.lwjgl.vulkan.VK10.vkQueueWaitIdle;
import static org.lwjgl.vulkan.VK13.nvkQueueSubmit2;
import static org.lwjgl.vulkan.VK13.vkQueueSubmit2;

import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo2;

/**
 * A device queue. Vulkan queues must not be used by two threads at once, so submitting and presenting synchronize on
 * the queue: worker threads can record command buffers in parallel and hand them to the same queue.
 */
public final class Queue {

    private final VkQueue handle;
    private final int family;

    Queue(VkQueue handle, int family) {
        this.handle = handle;
        this.family = family;
    }

    public VkQueue handle() {
        return handle;
    }

    /** The queue family index, e.g. for command pools. */
    public int family() {
        return family;
    }

    /** Submits work; {@code fence} (or 0) is signaled when it completes. */
    public synchronized void submit(VkSubmitInfo2.Buffer submits, long fence) {
        Vulkan.check(vkQueueSubmit2(handle, submits, fence), "Could not submit to the queue");
    }

    /** Presents swapchain images; returns the {@code VkResult}, as out-of-date and suboptimal are expected. */
    public synchronized int present(VkPresentInfoKHR info) {
        return vkQueuePresentKHR(handle, info);
    }

    /** Submits {@code count} {@code VkSubmitInfo2} structs at {@code submits}, without allocating. */
    public synchronized void submit(int count, long submits, long fence) {
        Vulkan.check(nvkQueueSubmit2(handle, count, submits, fence), "Could not submit to the queue");
    }

    /** Presents with the {@code VkPresentInfoKHR} at {@code info}, without allocating; returns the {@code VkResult}. */
    public synchronized int present(long info) {
        return nvkQueuePresentKHR(handle, info);
    }

    /** Waits until everything submitted to this queue has completed. */
    public synchronized void waitIdle() {
        Vulkan.check(vkQueueWaitIdle(handle), "Could not wait for the queue");
    }
}
