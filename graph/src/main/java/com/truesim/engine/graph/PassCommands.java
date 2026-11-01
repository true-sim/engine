package com.truesim.engine.graph;

import static com.truesim.engine.graph.FrameGraph.BUFFER;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
import static org.lwjgl.vulkan.VK12.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK12.VK_RESOLVE_MODE_AVERAGE_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_INFO;

import com.truesim.engine.graph.GraphBuilder.PassInfo;
import com.truesim.engine.vulkan.Commands;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferMemoryBarrier2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

/**
 * The commands {@link FrameGraph} records around pass bodies: the barriers before each pass, batched one call per pass,
 * and the start of rendering into a pass's attachments. Both look the images and buffers up in the graph each frame,
 * since a rebuild may move them.
 */
final class PassCommands {

    private PassCommands() {
    }

    /**
     * Starts rendering into a pass's attachments, the viewport and scissor all of them; the views are looked up each
     * frame, since a rebuild may move images, and the clear values read, since a technique may change them.
     */
    static void beginRendering(VkCommandBuffer commands, PassInfo pass, FrameGraph graph) {
        GraphBuilder.Attachment[] attachments = pass.attachments;
        try (MemoryStack stack = stackPush()) {
            long colors = stack.ncalloc(VkRenderingAttachmentInfo.ALIGNOF, attachments.length,
                VkRenderingAttachmentInfo.SIZEOF);
            long depth = 0;
            long stencil = 0;
            int colorCount = 0;
            Image size = attachments[0].target().image();
            int width = pass.region ? graph.regionWidth(pass.view, size) : size.width;
            int height = pass.region ? graph.regionHeight(pass.view, size) : size.height;
            for (GraphBuilder.Attachment attachment : attachments) {
                Subresource target = attachment.target();
                long info;
                if (attachment.depth()) {
                    info = stack.ncalloc(VkRenderingAttachmentInfo.ALIGNOF, 1, VkRenderingAttachmentInfo.SIZEOF);
                    depth = info;
                    if ((target.image().format.aspect() & VK_IMAGE_ASPECT_STENCIL_BIT) != 0) {
                        stencil = info;
                    }
                } else {
                    info = colors + (long) colorCount++ * VkRenderingAttachmentInfo.SIZEOF;
                }
                memPutInt(info + VkRenderingAttachmentInfo.STYPE, VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO);
                memPutLong(info + VkRenderingAttachmentInfo.IMAGEVIEW, attachmentView(graph, target));
                memPutInt(info + VkRenderingAttachmentInfo.IMAGELAYOUT, attachment.depth()
                    ? VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                memPutInt(info + VkRenderingAttachmentInfo.LOADOP, attachment.load().op);
                memPutInt(info + VkRenderingAttachmentInfo.STOREOP, attachment.store().op);
                Clear clear = attachment.clear();
                if (clear != null) {
                    long value = info + VkRenderingAttachmentInfo.CLEARVALUE;
                    if (clear.depth) {
                        memPutFloat(value, clear.value(0));
                        memPutInt(value + 4, clear.stencil);
                    } else {
                        for (int c = 0; c < 4; c++) {
                            memPutFloat(value + 4L * c, clear.value(c));
                        }
                    }
                }
                if (attachment.resolve() != null) {
                    memPutInt(info + VkRenderingAttachmentInfo.RESOLVEMODE, VK_RESOLVE_MODE_AVERAGE_BIT);
                    memPutLong(info + VkRenderingAttachmentInfo.RESOLVEIMAGEVIEW, attachmentView(graph, attachment.resolve()));
                    memPutInt(info + VkRenderingAttachmentInfo.RESOLVEIMAGELAYOUT,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                }
            }
            long rendering = stack.ncalloc(VkRenderingInfo.ALIGNOF, 1, VkRenderingInfo.SIZEOF);
            memPutInt(rendering + VkRenderingInfo.STYPE, VK_STRUCTURE_TYPE_RENDERING_INFO);
            memPutInt(rendering + VkRenderingInfo.RENDERAREA + VkRect2D.EXTENT, width);
            memPutInt(rendering + VkRenderingInfo.RENDERAREA + VkRect2D.EXTENT + 4, height);
            memPutInt(rendering + VkRenderingInfo.LAYERCOUNT, 1);
            memPutInt(rendering + VkRenderingInfo.COLORATTACHMENTCOUNT, colorCount);
            memPutAddress(rendering + VkRenderingInfo.PCOLORATTACHMENTS, colorCount == 0 ? 0 : colors);
            memPutAddress(rendering + VkRenderingInfo.PDEPTHATTACHMENT, depth);
            memPutAddress(rendering + VkRenderingInfo.PSTENCILATTACHMENT, stencil);
            Commands.beginRendering(commands, rendering);
            long viewport = stack.ncalloc(VkViewport.ALIGNOF, 1, VkViewport.SIZEOF);
            memPutFloat(viewport + VkViewport.WIDTH, width);
            memPutFloat(viewport + VkViewport.HEIGHT, height);
            memPutFloat(viewport + VkViewport.MAXDEPTH, 1);
            Commands.setViewport(commands, 0, 1, viewport);
            long scissor = stack.ncalloc(VkRect2D.ALIGNOF, 1, VkRect2D.SIZEOF);
            memPutInt(scissor + VkRect2D.EXTENT, width);
            memPutInt(scissor + VkRect2D.EXTENT + 4, height);
            Commands.setScissor(commands, 0, 1, scissor);
        }
    }

    private static long attachmentView(FrameGraph graph, Subresource target) {
        return target.whole() ? graph.viewOf(target.image().id) : graph.layerViewOf(target.image().id, target.baseLayer());
    }

    /**
     * Barriers {@code from} to {@code to} of {@code barriers}; of a {@code skipped} pass, without those
     * {@link FrameGraph#dropped} leaves out. Allocates nothing.
     */
    static void barriers(VkCommandBuffer commands, CompiledGraph.BarrierList barriers, int from, int to,
        boolean skipped, FrameGraph graph) {
        if (from == to) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            int bufferCount = 0;
            int imageCount = 0;
            for (int b = from; b < to; b++) {
                if (!graph.dropped(b, skipped)) {
                    bufferCount += barriers.kind()[b];
                    imageCount += 1 - barriers.kind()[b];
                }
            }
            if (bufferCount + imageCount == 0) {
                return;
            }
            long images = stack.ncalloc(VkImageMemoryBarrier2.ALIGNOF, Math.max(1, imageCount),
                VkImageMemoryBarrier2.SIZEOF);
            long buffers = stack.ncalloc(VkBufferMemoryBarrier2.ALIGNOF, Math.max(1, bufferCount),
                VkBufferMemoryBarrier2.SIZEOF);
            int image = 0;
            int buffer = 0;
            for (int b = from; b < to; b++) {
                if (graph.dropped(b, skipped)) {
                    continue;
                }
                if (barriers.kind()[b] == BUFFER) {
                    long barrier = buffers + (long) buffer++ * VkBufferMemoryBarrier2.SIZEOF;
                    memPutInt(barrier + VkBufferMemoryBarrier2.STYPE, VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2);
                    memPutLong(barrier + VkBufferMemoryBarrier2.SRCSTAGEMASK, barriers.srcStage()[b]);
                    memPutLong(barrier + VkBufferMemoryBarrier2.SRCACCESSMASK, barriers.srcAccess()[b]);
                    memPutLong(barrier + VkBufferMemoryBarrier2.DSTSTAGEMASK, barriers.dstStage()[b]);
                    memPutLong(barrier + VkBufferMemoryBarrier2.DSTACCESSMASK, barriers.dstAccess()[b]);
                    memPutInt(barrier + VkBufferMemoryBarrier2.SRCQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED);
                    memPutInt(barrier + VkBufferMemoryBarrier2.DSTQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED);
                    int target = barriers.target()[b];
                    memPutLong(barrier + VkBufferMemoryBarrier2.BUFFER, graph.targetBufferHandle(target));
                    memPutLong(barrier + VkBufferMemoryBarrier2.SIZE, VK_WHOLE_SIZE);
                    continue;
                }
                long barrier = images + (long) image++ * VkImageMemoryBarrier2.SIZEOF;
                memPutInt(barrier + VkImageMemoryBarrier2.STYPE, VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2);
                memPutLong(barrier + VkImageMemoryBarrier2.SRCSTAGEMASK, barriers.srcStage()[b]);
                memPutLong(barrier + VkImageMemoryBarrier2.SRCACCESSMASK, barriers.srcAccess()[b]);
                memPutLong(barrier + VkImageMemoryBarrier2.DSTSTAGEMASK, barriers.dstStage()[b]);
                memPutLong(barrier + VkImageMemoryBarrier2.DSTACCESSMASK, barriers.dstAccess()[b]);
                memPutInt(barrier + VkImageMemoryBarrier2.OLDLAYOUT, barriers.oldLayout()[b]);
                memPutInt(barrier + VkImageMemoryBarrier2.NEWLAYOUT, barriers.newLayout()[b]);
                memPutInt(barrier + VkImageMemoryBarrier2.SRCQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED);
                memPutInt(barrier + VkImageMemoryBarrier2.DSTQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED);
                int target = barriers.target()[b];
                memPutLong(barrier + VkImageMemoryBarrier2.IMAGE, graph.targetImageHandle(target));
                long range = barrier + VkImageMemoryBarrier2.SUBRESOURCERANGE;
                memPutInt(range + VkImageSubresourceRange.ASPECTMASK, barriers.aspect()[b]);
                memPutInt(range + VkImageSubresourceRange.BASEMIPLEVEL, barriers.baseMip()[b]);
                // -1 counts: VK_REMAINING_MIP_LEVELS, VK_REMAINING_ARRAY_LAYERS
                memPutInt(range + VkImageSubresourceRange.LEVELCOUNT, barriers.mipCount()[b]);
                memPutInt(range + VkImageSubresourceRange.BASEARRAYLAYER, barriers.baseLayer()[b]);
                memPutInt(range + VkImageSubresourceRange.LAYERCOUNT, barriers.layerCount()[b]);
            }
            long dependency = stack.ncalloc(VkDependencyInfo.ALIGNOF, 1, VkDependencyInfo.SIZEOF);
            memPutInt(dependency + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
            memPutInt(dependency + VkDependencyInfo.IMAGEMEMORYBARRIERCOUNT, imageCount);
            memPutAddress(dependency + VkDependencyInfo.PIMAGEMEMORYBARRIERS, images);
            memPutInt(dependency + VkDependencyInfo.BUFFERMEMORYBARRIERCOUNT, bufferCount);
            memPutAddress(dependency + VkDependencyInfo.PBUFFERMEMORYBARRIERS, buffers);
            Commands.pipelineBarrier2(commands, dependency);
        }
    }
}
