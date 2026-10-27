package com.truesim.engine.vulkan;

import static org.lwjgl.vulkan.VK10.VK_QUEUE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_TRANSFER_BIT;

/** A family of queues on a {@link Gpu}: its index, how many queues it has and what they can do. */
public record QueueFamily(int index, int queues, boolean graphics, boolean compute, boolean transfer) {

    /** A family from its {@code VkQueueFlags}. */
    static QueueFamily of(int index, int queues, int flags) {
        return new QueueFamily(index, queues, (flags & VK_QUEUE_GRAPHICS_BIT) != 0, (flags & VK_QUEUE_COMPUTE_BIT) != 0,
            (flags & VK_QUEUE_TRANSFER_BIT) != 0);
    }
}
