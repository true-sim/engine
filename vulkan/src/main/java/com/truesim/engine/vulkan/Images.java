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
import static org.lwjgl.vulkan.VK13.nvkCmdPipelineBarrier2;

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
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format);
            info.subresourceRange().aspectMask(aspect).levelCount(1).layerCount(1);
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
            memPutInt(range + VkImageSubresourceRange.LEVELCOUNT, 1);
            memPutInt(range + VkImageSubresourceRange.LAYERCOUNT, 1);
            long dependency = stack.ncalloc(VkDependencyInfo.ALIGNOF, 1, VkDependencyInfo.SIZEOF);
            memPutInt(dependency + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
            memPutInt(dependency + VkDependencyInfo.IMAGEMEMORYBARRIERCOUNT, 1);
            memPutAddress(dependency + VkDependencyInfo.PIMAGEMEMORYBARRIERS, barrier);
            nvkCmdPipelineBarrier2(commands, dependency);
        }
    }
}
