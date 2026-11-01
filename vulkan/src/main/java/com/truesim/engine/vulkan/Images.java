package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

/** Image helpers: views, and layout transitions with synchronization2 barriers. */
public final class Images {

    private Images() {
    }

    /** A 2D view of a whole single-level image. */
    public static long createView(VulkanDevice device, long image, int format, int aspect) {
        return createView(device, image, format, aspect, 0, 1);
    }

    /** A 2D view of {@code count} mip levels from {@code first}. */
    public static long createView(VulkanDevice device, long image, int format, int aspect, int first, int count) {
        return createView(device, image, format, aspect, VK_IMAGE_VIEW_TYPE_2D, first, count, 1);
    }

    /**
     * A view of type {@code viewType} ({@code VK_IMAGE_VIEW_TYPE_*}) of {@code count} mip levels from {@code first} and
     * {@code layers} array layers from the first.
     */
    public static long createView(VulkanDevice device, long image, int format, int aspect, int viewType, int first,
        int count, int layers) {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(viewType)
                .format(format);
            info.subresourceRange().aspectMask(aspect).baseMipLevel(first).levelCount(count).layerCount(layers);
            LongBuffer view = stack.mallocLong(1);
            Vulkan.check(vkCreateImageView(device.handle(), info, null, view), "Could not create an image view");
            return view.get(0);
        }
    }

    /** A 2D view of layer {@code layer}'s first mip level: for rendering into one layer or cube face. */
    public static long createLayerView(VulkanDevice device, long image, int format, int aspect, int layer) {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format);
            info.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(1).baseArrayLayer(layer).layerCount(1);
            LongBuffer view = stack.mallocLong(1);
            Vulkan.check(vkCreateImageView(device.handle(), info, null, view), "Could not create an image view");
            return view.get(0);
        }
    }

    /**
     * Records a layout transition of a whole single-level image: everything in {@code srcStage} with
     * {@code srcAccess} finishes before {@code dstStage} with {@code dstAccess} starts. Allocates nothing.
     */
    public static void transition(VkCommandBuffer commands, long image, int aspect, int oldLayout, int newLayout,
        long srcStage, long srcAccess, long dstStage, long dstAccess) {
        transition(commands, image, aspect, oldLayout, newLayout, srcStage, srcAccess, dstStage, dstAccess, 1, 1);
    }

    /** {@link #transition} of {@code levels} mip levels and {@code layers} layers from the first. */
    public static void transition(VkCommandBuffer commands, long image, int aspect, int oldLayout, int newLayout,
        long srcStage, long srcAccess, long dstStage, long dstAccess, int levels, int layers) {
        // Written field by field into stack memory: no struct wrappers, so nothing is allocated per frame.
        try (MemoryStack stack = stackPush()) {
            long barrier = stack.ncalloc(VkImageMemoryBarrier2.ALIGNOF, 1, VkImageMemoryBarrier2.SIZEOF);
            memPutInt(barrier + VkImageMemoryBarrier2.STYPE, VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2);
            memPutLong(barrier + VkImageMemoryBarrier2.SRCSTAGEMASK, srcStage);
            memPutLong(barrier + VkImageMemoryBarrier2.SRCACCESSMASK, srcAccess);
            memPutLong(barrier + VkImageMemoryBarrier2.DSTSTAGEMASK, dstStage);
            memPutLong(barrier + VkImageMemoryBarrier2.DSTACCESSMASK, dstAccess);
            memPutInt(barrier + VkImageMemoryBarrier2.OLDLAYOUT, oldLayout);
            memPutInt(barrier + VkImageMemoryBarrier2.NEWLAYOUT, newLayout);
            memPutInt(barrier + VkImageMemoryBarrier2.SRCQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED);
            memPutInt(barrier + VkImageMemoryBarrier2.DSTQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED);
            memPutLong(barrier + VkImageMemoryBarrier2.IMAGE, image);
            long range = barrier + VkImageMemoryBarrier2.SUBRESOURCERANGE;
            memPutInt(range + VkImageSubresourceRange.ASPECTMASK, aspect);
            memPutInt(range + VkImageSubresourceRange.LEVELCOUNT, levels);
            memPutInt(range + VkImageSubresourceRange.LAYERCOUNT, layers);
            long dependency = stack.ncalloc(VkDependencyInfo.ALIGNOF, 1, VkDependencyInfo.SIZEOF);
            memPutInt(dependency + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
            memPutInt(dependency + VkDependencyInfo.IMAGEMEMORYBARRIERCOUNT, 1);
            memPutAddress(dependency + VkDependencyInfo.PIMAGEMEMORYBARRIERS, barrier);
            Commands.pipelineBarrier2(commands, dependency);
        }
    }
}
