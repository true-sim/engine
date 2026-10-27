package com.truesim.engine.vulkan;

/** A family of queues on a {@link Gpu}: its index, how many queues it has and what they can do. */
public record QueueFamily(int index, int queues, boolean graphics, boolean compute, boolean transfer) {
}
