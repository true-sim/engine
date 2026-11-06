package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_BUFFER_IMAGE_COPY_2;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_COPY_BUFFER_TO_IMAGE_INFO_2;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy2;
import org.lwjgl.vulkan.VkCopyBufferToImageInfo2;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;

/**
 * Regions of dynamic textures written again while they are used (core prerequisite 7.9), uploaded within frames: each
 * region's texels are copied into a staging ring of {@code segments} parts of a frame's budget each, and its copy into
 * the image is recorded at the start of the next frame ({@link FrameSlots#recordStart}), between barriers, no queue
 * waited for. A part is written again once the frame that copied from it finished; a region that finds no room waits
 * for a later frame ({@link #stage} says so). Allocates nothing per frame once its arrays have grown to the regions a
 * frame queues.
 */
final class TextureUpdates implements AutoCloseable {

    /** Regions a frame starts with room for; more grow the arrays. */
    private static final int REGIONS = 16;
    /** Regions start at multiples of this in the staging ring: enough for buffer-to-image copies of any texel. */
    private static final long ALIGNMENT = 16;

    private final VulkanDevice device;
    private final FrameSlots slots;
    private final Retiring retiring;
    private final FrameSlots.FrameStart start = this::record;
    private GpuBuffer ring;
    private long segmentBytes;
    private int segments;
    private long[] segmentRetireAt;
    private int segment; // the part being filled
    private long used; // bytes of it filled
    // The regions waiting for the next frame: image, buffer offset, x, y, z, width, height, depth, layout
    private long[] images = new long[REGIONS];
    private long[] offsets = new long[REGIONS];
    private int[] boxes = new int[6 * REGIONS];
    private boolean[] general = new boolean[REGIONS];
    private int count;
    private long uploaded; // bytes recorded so far, for tests and statistics
    private boolean registered;

    @AllowAllocation("made once")
    TextureUpdates(VulkanDevice device, FrameSlots slots, long budget) {
        this.device = device;
        this.slots = slots;
        this.retiring = new Retiring(slots);
        budget(budget);
    }

    /**
     * {@code bytes} of texels a frame may stage (a setting: {@code render.textureUpdateBudget}); a region larger than it
     * goes alone, into a part of its own size. The ring is made again, once no frame reads the old one.
     */
    @AllowAllocation("the budget changed")
    void budget(long bytes) {
        if (bytes == segmentBytes && ring != null) {
            return;
        }
        if (ring != null) {
            retiring.add(ring);
        }
        segmentBytes = Math.max(ALIGNMENT, bytes);
        segments = slots.count() + 1; // one per frame in flight, and the one being filled
        ring = GpuBuffer.hostVisible(device, segmentBytes * segments, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        segmentRetireAt = new long[segments];
        java.util.Arrays.fill(segmentRetireAt, -1);
        segment = 0;
        used = 0;
        count = 0; // regions staged in the old ring are staged again by their owners: say so through stage's result
    }

    /** The staging budget a frame has, in bytes. */
    long budget() {
        return segmentBytes;
    }

    /**
     * Stages the box ({@code x}, {@code y}, {@code z}) of {@code width} x {@code height} x {@code depth} texels of
     * {@code texelBytes} each from {@code source}, whose rows are {@code rowBytes} apart and slices {@code sliceBytes},
     * the box's first texel at {@code from}; copied into {@code image} at the next frame's start. {@code general}:
     * the image stays in the general layout (a storage image GPU passes write too), else shader-read-only. Returns
     * false when this frame's part of the ring has no room left: stage it again in a later frame. Allocates nothing.
     */
    boolean stage(long image, boolean general, int x, int y, int z, int width, int height, int depth, int texelBytes,
        MemorySegment source, long from, long rowBytes, long sliceBytes) {
        long row = (long) width * texelBytes;
        long bytes = row * height * depth;
        if (bytes > segmentBytes) {
            throw new IllegalArgumentException("A region of " + bytes + " bytes exceeds the texture update budget of "
                + segmentBytes + " bytes a frame: render.textureUpdateBudget");
        }
        if (!slots.isFinished(segmentRetireAt[segment])) {
            return false; // the frame that read this part is still in flight
        }
        long at = (used + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT;
        if (at + bytes > segmentBytes) {
            return false;
        }
        MemorySegment staging = ring.memory();
        long base = (long) segment * segmentBytes + at;
        for (int s = 0; s < depth; s++) {
            for (int r = 0; r < height; r++) {
                MemorySegment.copy(source, from + s * sliceBytes + r * rowBytes, staging, base + (s * height + r) * row,
                    row);
            }
        }
        used = at + bytes;
        if (count == images.length) {
            grow();
        }
        images[count] = image;
        offsets[count] = base;
        this.general[count] = general;
        int b = 6 * count;
        boxes[b] = x;
        boxes[b + 1] = y;
        boxes[b + 2] = z;
        boxes[b + 3] = width;
        boxes[b + 4] = height;
        boxes[b + 5] = depth;
        count++;
        if (!registered) {
            slots.register(start);
            registered = true;
        }
        return true;
    }

    @AllowAllocation("more regions a frame than before")
    private void grow() {
        images = java.util.Arrays.copyOf(images, 2 * images.length);
        offsets = java.util.Arrays.copyOf(offsets, 2 * offsets.length);
        general = java.util.Arrays.copyOf(general, 2 * general.length);
        boxes = java.util.Arrays.copyOf(boxes, 2 * boxes.length);
    }

    /** How many regions wait for the next frame. */
    int pending() {
        return count;
    }

    /** The bytes of texels copied into images so far. */
    long uploaded() {
        return uploaded;
    }

    /** Drops the regions of {@code image} that wait: it is going. */
    void forget(long image) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (images[i] != image) {
                images[kept] = images[i];
                offsets[kept] = offsets[i];
                general[kept] = general[i];
                System.arraycopy(boxes, 6 * i, boxes, 6 * kept, 6);
                kept++;
            }
        }
        count = kept;
    }

    /** The frame's start: every waiting region copied into its image, between barriers; the part filled moves on. */
    private void record(org.lwjgl.vulkan.VkCommandBuffer commands) {
        if (count == 0) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            for (int i = 0; i < count; i++) {
                int layout = general[i] ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                int copyLayout = general[i] ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
                long readers = general[i] ? VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                    | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT : VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
                barrier(commands, stack, images[i], layout, copyLayout, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, readers,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
                long region = stack.ncalloc(VkBufferImageCopy2.ALIGNOF, 1, VkBufferImageCopy2.SIZEOF);
                memPutInt(region + VkBufferImageCopy2.STYPE, VK_STRUCTURE_TYPE_BUFFER_IMAGE_COPY_2);
                memPutLong(region + VkBufferImageCopy2.BUFFEROFFSET, offsets[i]);
                long layers = region + VkBufferImageCopy2.IMAGESUBRESOURCE;
                memPutInt(layers + VkImageSubresourceLayers.ASPECTMASK, VK_IMAGE_ASPECT_COLOR_BIT);
                memPutInt(layers + VkImageSubresourceLayers.LAYERCOUNT, 1);
                int b = 6 * i;
                memPutInt(region + VkBufferImageCopy2.IMAGEOFFSET, boxes[b]);
                memPutInt(region + VkBufferImageCopy2.IMAGEOFFSET + 4, boxes[b + 1]);
                memPutInt(region + VkBufferImageCopy2.IMAGEOFFSET + 8, boxes[b + 2]);
                memPutInt(region + VkBufferImageCopy2.IMAGEEXTENT, boxes[b + 3]);
                memPutInt(region + VkBufferImageCopy2.IMAGEEXTENT + 4, boxes[b + 4]);
                memPutInt(region + VkBufferImageCopy2.IMAGEEXTENT + 8, boxes[b + 5]);
                long info = stack.ncalloc(VkCopyBufferToImageInfo2.ALIGNOF, 1, VkCopyBufferToImageInfo2.SIZEOF);
                memPutInt(info + VkCopyBufferToImageInfo2.STYPE, VK_STRUCTURE_TYPE_COPY_BUFFER_TO_IMAGE_INFO_2);
                memPutLong(info + VkCopyBufferToImageInfo2.SRCBUFFER, ring.handle());
                memPutLong(info + VkCopyBufferToImageInfo2.DSTIMAGE, images[i]);
                memPutInt(info + VkCopyBufferToImageInfo2.DSTIMAGELAYOUT, copyLayout);
                memPutInt(info + VkCopyBufferToImageInfo2.REGIONCOUNT, 1);
                memPutAddress(info + VkCopyBufferToImageInfo2.PREGIONS, region);
                Commands.copyBufferToImage2(commands, info);
                barrier(commands, stack, images[i], copyLayout, layout, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, readers);
                uploaded += (long) boxes[b + 3] * boxes[b + 4] * boxes[b + 5];
            }
        }
        count = 0;
        segmentRetireAt[segment] = slots.retireAt();
        segment = (segment + 1) % segments;
        used = 0;
        retiring.release();
    }

    private static void barrier(org.lwjgl.vulkan.VkCommandBuffer commands, MemoryStack stack, long image,
        int oldLayout, int newLayout, long srcStage, long srcAccess, long dstStage, long dstAccess) {
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
        memPutInt(range + VkImageSubresourceRange.ASPECTMASK, VK_IMAGE_ASPECT_COLOR_BIT);
        memPutInt(range + VkImageSubresourceRange.LEVELCOUNT, 1);
        memPutInt(range + VkImageSubresourceRange.LAYERCOUNT, 1);
        long dependency = stack.ncalloc(VkDependencyInfo.ALIGNOF, 1, VkDependencyInfo.SIZEOF);
        memPutInt(dependency + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
        memPutInt(dependency + VkDependencyInfo.IMAGEMEMORYBARRIERCOUNT, 1);
        memPutAddress(dependency + VkDependencyInfo.PIMAGEMEMORYBARRIERS, barrier);
        Commands.pipelineBarrier2(commands, dependency);
    }

    @Override
    public void close() {
        if (registered) {
            slots.unregister(start);
        }
        if (ring != null) {
            ring.close();
            ring = null;
        }
        retiring.close();
    }
}
