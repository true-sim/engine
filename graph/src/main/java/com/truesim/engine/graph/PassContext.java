package com.truesim.engine.graph;

import com.truesim.engine.vulkan.Bindless;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * What a {@link PassBody} records with: the command buffer, and the Vulkan objects behind its {@link Image}s and
 * {@link Buffer}s. Sampled and storage slots are the image's slots in the device's {@link Bindless} set, for shaders
 * to take in their push constants. Nothing allocates.
 */
public final class PassContext {

    private final FrameGraph graph;
    VkCommandBuffer commands;

    PassContext(FrameGraph graph) {
        this.graph = graph;
    }

    public VkCommandBuffer commands() {
        return commands;
    }

    /** The {@code VkImage}. */
    public long image(Image image) {
        return graph.handleOf(image.id);
    }

    /** A {@code VkImageView} of the whole image. */
    public long view(Image image) {
        return graph.viewOf(image.id);
    }

    /** The image's sampled slot, for images some pass samples. */
    public int sampled(Image image) {
        return graph.sampledSlotOf(image.id);
    }

    /** The image's storage slot, for images some pass uses as a storage image (level 0 of a mipmapped one). */
    public int storage(Image image) {
        return graph.storageSlotOf(image.id, 0);
    }

    /** The {@code VkBuffer} of a transient buffer. */
    public long buffer(Buffer buffer) {
        return graph.bufferOf(buffer.id).handle();
    }

    /** The device address of a transient buffer, for shaders to take in their push constants. */
    public long address(Buffer buffer) {
        return graph.bufferOf(buffer.id).deviceAddress();
    }

    /** The storage slot of mip level {@code mip} of the image. */
    public int storage(Image image, int mip) {
        return graph.storageSlotOf(image.id, mip);
    }
}
