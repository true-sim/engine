package com.truesim.engine.graph;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.vkCmdClearColorImage;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_NONE;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.Images;
import com.truesim.engine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageSubresourceRange;

/**
 * What a graph keeps for {@link GraphBuilder#persistent}, {@link GraphBuilder#persistentBuffer} and
 * {@link GraphBuilder#historyBuffer}: an image, or one or two buffers, of its own, imported into every build that asks
 * for them by the same name and description, contents kept. An image rests in the general layout, with a sampled slot
 * and, where its format allows, a storage one. Everything starts zeroed (a depth image: undefined).
 */
final class Persistent implements AutoCloseable {

    private final VulkanDevice device;
    final String key;
    final ImageDesc desc; // an image's, else null
    final long size; // a buffer's, else 0
    final ExternalImage image; // the image as builds import it, or null
    final ExternalBuffer[] buffers; // as builds import them: a history's current one first
    private GpuImage gpuImage;
    private final GpuBuffer[] gpuBuffers;
    long firstFrame; // a history's first frame: its previous buffer holds zeros until the frame after
    long built; // the last build that asked for it

    /** An image of {@code desc}: one 2D layer, one mip level, one sample. Waits for the GPU once, to clear it. */
    @AllowAllocation("made rarely")
    Persistent(VulkanDevice device, String key, ImageDesc desc) {
        if (!desc.flat() || desc.layers() > 1 || desc.mips() > 1 || desc.samples() > 1) {
            throw new IllegalArgumentException("A persistent image is one 2D layer, mip level and sample: " + key);
        }
        this.device = device;
        this.key = key;
        this.desc = desc;
        this.size = 0;
        this.buffers = new ExternalBuffer[0];
        this.gpuBuffers = new GpuBuffer[0];
        int features;
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(device.gpu().handle(), desc.format().vk(), properties);
            features = properties.optimalTilingFeatures();
        }
        boolean depth = desc.format().depth();
        int attachment = depth
            ? (features & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0 ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT : 0
            : (features & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) != 0 ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : 0;
        int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
            | attachment | ((features & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0 ? VK_IMAGE_USAGE_STORAGE_BIT : 0);
        int aspect = desc.format().aspect();
        gpuImage = new GpuImage(device, desc.width(), desc.height(), desc.format().vk(), usage, aspect);
        image = new ExternalImage(key, desc.width(), desc.height(), desc.format(), usage, VK_IMAGE_LAYOUT_GENERAL,
            VK_IMAGE_LAYOUT_GENERAL).set(gpuImage.handle(), gpuImage.view());
        image.kept = true;
        image.sampled = device.bindless().addSampled(gpuImage.view());
        if ((usage & VK_IMAGE_USAGE_STORAGE_BIT) != 0) {
            image.storage = device.bindless().addStorage(gpuImage.view());
        }
        long handle = gpuImage.handle();
        device.submitAndWait(device.graphics(), commands -> {
            if (depth) {
                Images.transition(commands, handle, aspect, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                    VK_PIPELINE_STAGE_2_NONE, 0, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                    VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
                return;
            }
            Images.transition(commands, handle, aspect, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_2_NONE, 0, VK_PIPELINE_STAGE_2_CLEAR_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
            try (MemoryStack stack = stackPush()) {
                vkCmdClearColorImage(commands, handle, VK_IMAGE_LAYOUT_GENERAL, VkClearColorValue.calloc(stack),
                    VkImageSubresourceRange.calloc(1, stack).aspectMask(aspect).levelCount(1).layerCount(1));
            }
            Images.transition(commands, handle, aspect, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_2_CLEAR_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        });
    }

    /**
     * {@code count} buffers (one; two for a history) of {@code size} bytes, shared by the queues of {@code families}.
     * Waits for the GPU once, to zero them.
     */
    @AllowAllocation("made rarely")
    Persistent(VulkanDevice device, String key, long size, int count, int... families) {
        if (size < 1) {
            throw new IllegalArgumentException("Buffer size must be positive: " + key + ", " + size);
        }
        this.device = device;
        this.key = key;
        this.desc = null;
        this.size = size;
        this.image = null;
        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
            | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        gpuBuffers = new GpuBuffer[count];
        buffers = new ExternalBuffer[count];
        for (int b = 0; b < count; b++) {
            gpuBuffers[b] = GpuBuffer.deviceLocal(device, size, usage, families);
            buffers[b] = new ExternalBuffer(b == 0 ? key : key + " (last frame)", gpuBuffers[b]);
        }
        device.submitAndWait(device.graphics(), commands -> {
            for (GpuBuffer buffer : gpuBuffers) {
                Commands.fillBuffer(commands, buffer.handle(), 0, buffer.size(), 0);
            }
        });
    }

    /** Whether it is an image of {@code wanted}, or {@code count} buffers of {@code wantedSize} bytes. */
    boolean is(ImageDesc wanted, long wantedSize, int count) {
        return desc != null ? desc.equals(wanted) : wanted == null && size == wantedSize && buffers.length == count;
    }

    /** A history's new frame: last frame's current buffer is this frame's previous one. Allocates nothing. */
    void swap() {
        GpuBuffer current = gpuBuffers[0];
        gpuBuffers[0] = gpuBuffers[1];
        gpuBuffers[1] = current;
        buffers[0].set(gpuBuffers[0]);
        buffers[1].set(gpuBuffers[1]);
    }

    @Override
    public void close() {
        if (gpuImage != null) {
            device.bindless().removeSampled(image.sampled);
            if (image.storage >= 0) {
                device.bindless().removeStorage(image.storage);
            }
            gpuImage.close();
            gpuImage = null;
        }
        for (int b = 0; b < gpuBuffers.length; b++) {
            if (gpuBuffers[b] != null) {
                gpuBuffers[b].close();
                gpuBuffers[b] = null;
            }
        }
    }

    @Override
    public String toString() {
        return "persistent " + key;
    }
}
