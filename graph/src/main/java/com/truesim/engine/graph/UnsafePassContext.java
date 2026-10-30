package com.truesim.engine.graph;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * The raw Vulkan objects behind a {@link PassContext}, for what its helpers do not cover: the command buffer, and the
 * handles behind images and buffers. What is recorded with them is the pass's own business; the graph still places
 * barriers only from the accesses the pass declared, and with validation on only declared resources resolve.
 */
public final class UnsafePassContext {

    private final PassContext context;

    UnsafePassContext(PassContext context) {
        this.context = context;
    }

    /** The command buffer the pass records into. */
    public VkCommandBuffer commands() {
        return context.commands;
    }

    /** The {@code VkImage}. */
    public long image(Image image) {
        return context.image(image);
    }

    /** A {@code VkImageView} of the whole image. */
    public long view(Image image) {
        return context.view(image);
    }

    /** A {@code VkImageView} of part of an image: all of it, or one layer of all its mip levels. */
    public long view(Subresource part) {
        return context.view(part);
    }

    /** The {@code VkBuffer} of a buffer of the graph, transient or imported. */
    public long buffer(Buffer buffer) {
        return context.buffer(buffer);
    }
}
