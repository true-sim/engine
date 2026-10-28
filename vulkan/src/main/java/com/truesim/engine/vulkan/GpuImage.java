package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO;
import static org.lwjgl.util.vma.Vma.vmaCreateImage;
import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;

import com.truesim.annotations.AllowAllocation;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;

/** A 2D image in GPU memory with a view of all of it, e.g. a render target, depth buffer or texture. */
@AllowAllocation("created rarely")
public final class GpuImage implements AutoCloseable {

    private static final int VIEW_USAGES = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT
        | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
        | VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;

    private final VulkanDevice device;
    private final long handle;
    private final long allocation;
    private final long view;
    private final int width;
    private final int height;
    private final int format;
    private boolean closed;

    public GpuImage(VulkanDevice device, int width, int height, int format, int usage, int aspect) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Image size must be positive: " + width + " x " + height);
        }
        this.device = device;
        this.width = width;
        this.height = height;
        this.format = format;
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(format)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            LongBuffer image = stack.mallocLong(1);
            PointerBuffer allocated = stack.mallocPointer(1);
            Vulkan.check(vmaCreateImage(device.allocator(), info,
                VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO), image, allocated, null),
                "Could not allocate a " + width + " x " + height + " image");
            this.handle = image.get(0);
            this.allocation = allocated.get(0);
            // Views are for shaders and attachments; an image only copied to and from has none.
            this.view = (usage & VIEW_USAGES) != 0 ? Images.createView(device, handle, format, aspect) : 0;
        }
    }

    public long handle() {
        return handle;
    }

    /** A view of the whole image; 0 for images used only for copies. */
    public long view() {
        return view;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int format() {
        return format;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (view != 0) {
                vkDestroyImageView(device.handle(), view, null);
            }
            vmaDestroyImage(device.allocator(), handle, allocation);
        }
    }
}
