package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_MEMORY_BARRIER_2;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

/** Barriers recorded by hand, where no frame graph derives them: one helper instead of a copy per caller. */
public final class Barriers {

    private Barriers() {
    }

    /**
     * A global memory barrier: what {@code srcStage} wrote with {@code srcAccess} is visible to {@code dstAccess} in
     * {@code dstStage}, which waits for it. Allocates nothing.
     */
    public static void memory(VkCommandBuffer commands, long srcStage, long srcAccess, long dstStage, long dstAccess) {
        try (MemoryStack stack = stackPush()) {
            long barrier = stack.ncalloc(VkMemoryBarrier2.ALIGNOF, 1, VkMemoryBarrier2.SIZEOF);
            memPutInt(barrier + VkMemoryBarrier2.STYPE, VK_STRUCTURE_TYPE_MEMORY_BARRIER_2);
            memPutLong(barrier + VkMemoryBarrier2.SRCSTAGEMASK, srcStage);
            memPutLong(barrier + VkMemoryBarrier2.SRCACCESSMASK, srcAccess);
            memPutLong(barrier + VkMemoryBarrier2.DSTSTAGEMASK, dstStage);
            memPutLong(barrier + VkMemoryBarrier2.DSTACCESSMASK, dstAccess);
            long dependency = stack.ncalloc(VkDependencyInfo.ALIGNOF, 1, VkDependencyInfo.SIZEOF);
            memPutInt(dependency + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
            memPutInt(dependency + VkDependencyInfo.MEMORYBARRIERCOUNT, 1);
            memPutAddress(dependency + VkDependencyInfo.PMEMORYBARRIERS, barrier);
            Commands.pipelineBarrier2(commands, dependency);
        }
    }
}
