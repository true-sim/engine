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
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkImageSubresourceRange;

/**
 * An image kept from frame to frame, for a view to render into
 * ({@link ViewPort#output(Key, Texture)}) and any pass to sample by its {@link #slot()}, which never changes: a mirror,
 * a security camera's screen, a minimap. Between frames it rests readable by shaders; a frame its view is skipped
 * leaves it as it was. A build reads it with {@link GraphBuilder#texture}, after the view's copy into it. It starts
 * transparent black.
 *
 * <p>Made with {@code new Texture(device, ...)}, it is the app's: any graph may render into it and read it, and it
 * outlives them all (a one-shot render kept for later) until the app closes it, as it would lights or a scene. Made
 * with {@link FrameGraph#texture}, its graph's: freed when removed ({@link FrameGraph#removeTexture}) or the graph
 * closes. A graph rendering into or reading a closed texture fails when it builds.
 *
 * <pre>{@code
 * Texture screen = graph.texture("security screen", Format.R8G8B8A8_UNORM, 512, 512);
 * graph.addView("security").size(512, 512).add(shading).output(Keys.SCENE_COLOR, screen).rate(4);
 * // a technique of the main view, sampling it at screen.slot():
 * pass.read(g.texture(screen), Access.SAMPLED_FRAGMENT);
 * }</pre>
 */
public final class Texture implements AutoCloseable {

    private final VulkanDevice device;
    private final GpuImage image;
    private final int slot;
    final ExternalImage external; // how frames see it: taken from and left in its resting layout, with its contents
    boolean removed; // from its graph: builds may no longer read it
    private boolean closed;

    /**
     * A texture of {@code width} by {@code height} {@code format} texels on {@code device}, the app's to close. Waits
     * for the GPU once, to clear it.
     */
    @AllowAllocation("set up rarely")
    public Texture(VulkanDevice device, String name, Format format, int width, int height) {
        this(device, name, format, width, height, new float[4]);
    }

    /** Like {@link #Texture(VulkanDevice, String, Format, int, int)}, starting filled with {@code rgba}. */
    @AllowAllocation("set up rarely")
    public Texture(VulkanDevice device, String name, Format format, int width, int height, float[] rgba) {
        this.device = java.util.Objects.requireNonNull(device, "device");
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(format, "format");
        int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
            | (format.depth() ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
        image = new GpuImage(device, width, height, format.vk(), usage, format.aspect());
        slot = device.bindless().addSampled(image.view());
        external = new ExternalImage(name, width, height, format, usage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).set(image.handle(), image.view());
        external.sampled = slot;
        external.texture = this;
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
                VkClearColorValue clear = VkClearColorValue.calloc(stack);
                for (int c = 0; c < 4; c++) {
                    clear.float32(c, rgba[c]);
                }
                vkCmdClearColorImage(commands, image.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, range);
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

    /** Whether it was closed: graphs may no longer use it. */
    public boolean closed() {
        return closed;
    }

    /** Frees it now: once no frame in flight reads it (the GPU waited for). */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            free();
        }
    }

    /** Closes it, freeing it once no frame in flight of {@code retiring}'s frame slots reads it. */
    @AllowAllocation("closing, rarely")
    public void close(Retiring retiring) {
        if (!closed) {
            closed = true;
            retiring.add(this::free);
        }
    }

    private void free() {
        device.bindless().removeSampled(slot);
        image.close();
    }

    @Override
    public String toString() {
        return "texture " + external.name;
    }
}
