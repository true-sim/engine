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
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_CONCURRENT;
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
    private final long[] mipViews;
    private final int mips;
    private final int width;
    private final int height;
    private final int format;
    private boolean closed;

    public GpuImage(VulkanDevice device, int width, int height, int format, int usage, int aspect) {
        this(device, width, height, format, usage, aspect, 1, new int[0]);
    }

    /**
     * An image used by several queue families at once without ownership transfers ({@code VK_SHARING_MODE_CONCURRENT}),
     * e.g. by the graphics and the async compute queue; one family or none is exclusive, as usual.
     */
    public GpuImage(VulkanDevice device, int width, int height, int format, int usage, int aspect,
        int... queueFamilies) {
        this(device, width, height, format, usage, aspect, 1, queueFamilies);
    }

    /**
     * An image of {@code mips} mip levels (each half the one before, down to 1 x 1 at most), shared by
     * {@code queueFamilies} as above. Storage images get a view of each level ({@link #view(int)}), as storage
     * descriptors take one level at a time.
     */
    public static GpuImage mipmapped(VulkanDevice device, int width, int height, int format, int usage, int aspect,
        int mips, int... queueFamilies) {
        return new GpuImage(device, width, height, format, usage, aspect, mips, queueFamilies);
    }

    /** How many mip levels a {@code width} by {@code height} image has, down to 1 x 1. */
    public static int fullMipCount(int width, int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    }

    private GpuImage(VulkanDevice device, int width, int height, int format, int usage, int aspect, int mips,
        int[] queueFamilies) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Image size must be positive: " + width + " x " + height);
        }
        if (mips < 1 || mips > fullMipCount(width, height)) {
            throw new IllegalArgumentException("A " + width + " x " + height + " image has 1 to "
                + fullMipCount(width, height) + " mip levels: " + mips);
        }
        this.mips = mips;
        this.device = device;
        this.width = width;
        this.height = height;
        this.format = format;
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(format)
                .mipLevels(mips)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(queueFamilies.length > 1 ? VK_SHARING_MODE_CONCURRENT : VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            if (queueFamilies.length > 1) {
                info.pQueueFamilyIndices(stack.ints(queueFamilies));
            }
            info.extent().set(width, height, 1);
            LongBuffer image = stack.mallocLong(1);
            PointerBuffer allocated = stack.mallocPointer(1);
            Vulkan.check(vmaCreateImage(device.allocator(), info,
                VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO), image, allocated, null),
                "Could not allocate a " + width + " x " + height + " image");
            this.handle = image.get(0);
            this.allocation = allocated.get(0);
            // Views are for shaders and attachments; an image only copied to and from has none.
            this.mipViews = new long[mips > 1 && (usage & VK_IMAGE_USAGE_STORAGE_BIT) != 0 ? mips : 0];
            long whole = 0;
            try {
                whole = (usage & VIEW_USAGES) != 0 ? Images.createView(device, handle, format, aspect, 0, mips) : 0;
                for (int mip = 0; mip < mipViews.length; mip++) {
                    mipViews[mip] = Images.createView(device, handle, format, aspect, mip, 1);
                }
            } catch (RuntimeException e) {
                destroy(device, whole, mipViews, handle, allocation); // nothing made leaks
                throw e;
            }
            this.view = whole;
        }
    }

    public long handle() {
        return handle;
    }

    /** A view of the whole image; 0 for images used only for copies. */
    public long view() {
        return view;
    }

    /** A view of mip level {@code mip} alone (of a mipmapped storage image), or the view of a one-level image. */
    public long view(int mip) {
        return mips == 1 ? view : mipViews[mip];
    }

    public int mips() {
        return mips;
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
            destroy(device, view, mipViews, handle, allocation);
        }
    }

    /** Destroys the views made (0s are skipped) and the image. */
    private static void destroy(VulkanDevice device, long view, long[] mipViews, long image, long allocation) {
        if (view != 0) {
            vkDestroyImageView(device.handle(), view, null);
        }
        for (long mipView : mipViews) {
            if (mipView != 0) {
                vkDestroyImageView(device.handle(), mipView, null);
            }
        }
        vmaDestroyImage(device.allocator(), image, allocation);
    }
}
