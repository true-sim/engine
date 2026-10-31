package com.truesim.engine.graph;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdClearColorImage;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_NONE;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.Images;
import com.truesim.engine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkImageSubresourceRange;

/**
 * An image a {@link FrameGraph} keeps from frame to frame ({@link FrameGraph#texture}), for a view to render into
 * ({@link ViewPort#output(Key, Texture)}) and any pass to sample by its {@link #slot()}, which never changes: a mirror,
 * a security camera's screen, a minimap. Between frames it rests readable by shaders; a frame its view is skipped
 * leaves it as it was. A build reads it with {@link GraphBuilder#texture}, after the view's copy into it. It starts
 * transparent black, and closes with its graph.
 *
 * <pre>{@code
 * Texture screen = graph.texture("security screen", Format.R8G8B8A8_UNORM, 512, 512);
 * graph.addView("security").size(512, 512).add(shading).output(Keys.SCENE_COLOR, screen).rate(4);
 * // a technique of the main view, sampling it at screen.slot():
 * pass.read(g.texture(screen), Access.SAMPLED_FRAGMENT);
 * }</pre>
 */
public final class Texture {

    private final VulkanDevice device;
    private final GpuImage image;
    private final int slot;
    final ExternalImage external; // how frames see it: taken from and left in its resting layout, with its contents

    @AllowAllocation("set up rarely")
    Texture(VulkanDevice device, String name, Format format, int width, int height) {
        this.device = device;
        int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
            | (format.depth() ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
        image = new GpuImage(device, width, height, format.vk(), usage, format.aspect());
        slot = device.bindless().addSampled(image.view());
        external = new ExternalImage(name, width, height, format, usage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).set(image.handle(), image.view());
        external.sampled = slot;
        device.submitAndWait(device.graphics(), commands -> {
            int aspect = format.aspect();
            if (format.depth()) {
                Images.transition(commands, image.handle(), aspect, VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_2_NONE, 0,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
                return;
            }
            Images.transition(commands, image.handle(), aspect, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_2_NONE, 0, VK_PIPELINE_STAGE_2_CLEAR_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT);
            try (MemoryStack stack = stackPush()) {
                VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                    .aspectMask(aspect).levelCount(1).layerCount(1);
                vkCmdClearColorImage(commands, image.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VkClearColorValue.calloc(stack), range);
            }
            Images.transition(commands, image.handle(), aspect, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_2_CLEAR_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        });
    }

    public String name() {
        return external.name;
    }

    public int width() {
        return external.width;
    }

    public int height() {
        return external.height;
    }

    public Format format() {
        return external.format;
    }

    /** Its sampled slot in the bindless set, the same as long as it lives: for materials and push constants. */
    public int slot() {
        return slot;
    }

    /** Frees it: from its graph's close, once the GPU is done. */
    void close() {
        device.bindless().removeSampled(slot);
        image.close();
    }

    @Override
    public String toString() {
        return "texture " + external.name;
    }
}
