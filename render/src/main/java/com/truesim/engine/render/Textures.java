package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.nvkCmdBlitImage;
import static org.lwjgl.vulkan.VK10.nvkCmdCopyBufferToImage;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;
import static org.lwjgl.vulkan.VK13.nvkCmdPipelineBarrier2;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.Bindless;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.FrameUploads;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;

/**
 * Material textures in the device's bindless set: each uploaded once with its full mip chain (made on the GPU, each
 * level a linear downsample of the one before), then sampled by slot, e.g. from a {@link Material}. Colors are sRGB
 * ({@link #color}): the sampler returns them linear. Data such as normal maps and occlusion-roughness-metalness are
 * linear ({@link #data}).
 *
 * <pre>{@code
 * try (Textures textures = new Textures(device)) {
 *     int albedo = textures.color(512, 512, rgba);      // 4 bytes per texel, rows top to bottom
 *     int normals = textures.data(512, 512, xyz);
 * }
 * }</pre>
 *
 * <p>Given a frame graph's {@link FrameSlots}, adding a texture does not wait for the GPU: its slot is taken at once,
 * its texels staged, and the image and its mip chain are made at the start of the next frame ({@link FrameUploads}),
 * so it is sampled from the next frame recorded on. Without, adding waits until it is done. Use it from one thread, the
 * one recording frames.
 */
@AllowAllocation("created rarely; adding textures uploads them")
public final class Textures implements AutoCloseable {

    private final VulkanDevice device;
    private final FrameSlots frames;
    private final FrameUploads uploads;
    // Live textures by bindless slot, and removed ones waiting for the frames that may sample them.
    private final Map<Integer, GpuImage> images = new HashMap<>();
    private final Map<Integer, Long> sizes = new HashMap<>();
    // Per live texture whose upload may still wait for a frame: set once it is freed, so the upload is dropped.
    private final Map<Integer, boolean[]> freed = new HashMap<>();
    private final Retiring retiring;
    private long bytes;
    private boolean closed;

    /** Textures for frames the caller waits for between removing one and adding another. */
    public Textures(VulkanDevice device) {
        this(device, FrameSlots.single());
    }

    /** Textures whose removed images stay until no frame of {@code frames} (a frame graph's) can sample them. */
    public Textures(VulkanDevice device, FrameSlots frames) {
        this.device = device;
        this.frames = frames;
        this.uploads = new FrameUploads(device, frames);
        this.retiring = new Retiring(frames);
    }

    /** An sRGB color texture, {@code rgba} 4 bytes per texel; its sampled slot. */
    public int color(int width, int height, MemorySegment rgba) {
        return add(width, height, VK_FORMAT_R8G8B8A8_SRGB, rgba);
    }

    /** A linear data texture (normals, occlusion-roughness-metalness), 4 bytes per texel; its sampled slot. */
    public int data(int width, int height, MemorySegment rgba) {
        return add(width, height, VK_FORMAT_R8G8B8A8_UNORM, rgba);
    }

    private int add(int width, int height, int format, MemorySegment texels) {
        if (closed) {
            throw new IllegalStateException("The textures are closed");
        }
        if (width < 1 || height < 1 || texels.byteSize() != 4L * width * height) {
            throw new IllegalArgumentException("A " + width + " x " + height + " texture takes " + 4L * width * height
                + " bytes: " + texels.byteSize());
        }
        int mips = GpuImage.fullMipCount(width, height);
        GpuImage image = GpuImage.mipmapped(device, width, height, format, VK_IMAGE_USAGE_SAMPLED_BIT
            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT, mips);
        boolean[] gone = new boolean[1];
        try {
            uploads.record(texels, (commands, staging) -> {
                if (!gone[0]) {
                    upload(commands, staging, image, width, height, mips);
                }
            });
        } catch (RuntimeException e) {
            image.close();
            throw e;
        }
        retiring.release();
        int slot = device.bindless().addSampled(image.view());
        long size = 4L * width * height * 4 / 3;
        images.put(slot, image);
        sizes.put(slot, size);
        freed.put(slot, gone);
        bytes += size;
        return slot;
    }

    /** Level 0 from the staging buffer, each further level blitted from the one before, all left shader-readable. */
    private static void upload(VkCommandBuffer commands, GpuBuffer staging, GpuImage image, int width, int height,
        int mips) {
        try (MemoryStack stack = stackPush()) {
            barrier(commands, stack, image, 0, mips, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_PIPELINE_STAGE_2_NONE, VK_ACCESS_2_NONE, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT);
            long region = stack.ncalloc(VkBufferImageCopy.ALIGNOF, 1, VkBufferImageCopy.SIZEOF);
            layers(region + VkBufferImageCopy.IMAGESUBRESOURCE, 0);
            memPutInt(region + VkBufferImageCopy.IMAGEEXTENT, width);
            memPutInt(region + VkBufferImageCopy.IMAGEEXTENT + 4, height);
            memPutInt(region + VkBufferImageCopy.IMAGEEXTENT + 8, 1);
            nvkCmdCopyBufferToImage(commands, staging.handle(), image.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1,
                region);
            for (int mip = 1; mip < mips; mip++) {
                barrier(commands, stack, image, mip - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
                long blit = stack.ncalloc(VkImageBlit.ALIGNOF, 1, VkImageBlit.SIZEOF);
                layers(blit + VkImageBlit.SRCSUBRESOURCE, mip - 1);
                layers(blit + VkImageBlit.DSTSUBRESOURCE, mip);
                corner(blit + VkImageBlit.SRCOFFSETS, Math.max(1, width >> (mip - 1)), Math.max(1, height >> (mip - 1)));
                corner(blit + VkImageBlit.DSTOFFSETS, Math.max(1, width >> mip), Math.max(1, height >> mip));
                nvkCmdBlitImage(commands, image.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, image.handle(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, blit, VK_FILTER_LINEAR);
            }
            // Every level but the last is a blit source now; all become shader-readable.
            barrier(commands, stack, image, 0, mips - 1, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
            barrier(commands, stack, image, mips - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        }
    }

    private static void barrier(VkCommandBuffer commands, MemoryStack stack, GpuImage image, int firstMip, int mips,
        int oldLayout, int newLayout, long srcStage, long srcAccess, long dstStage, long dstAccess) {
        if (mips == 0) {
            return;
        }
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
        memPutLong(barrier + VkImageMemoryBarrier2.IMAGE, image.handle());
        long range = barrier + VkImageMemoryBarrier2.SUBRESOURCERANGE;
        memPutInt(range + VkImageSubresourceRange.ASPECTMASK, VK_IMAGE_ASPECT_COLOR_BIT);
        memPutInt(range + VkImageSubresourceRange.BASEMIPLEVEL, firstMip);
        memPutInt(range + VkImageSubresourceRange.LEVELCOUNT, mips);
        memPutInt(range + VkImageSubresourceRange.LAYERCOUNT, 1);
        long dependency = stack.ncalloc(VkDependencyInfo.ALIGNOF, 1, VkDependencyInfo.SIZEOF);
        memPutInt(dependency + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
        memPutInt(dependency + VkDependencyInfo.IMAGEMEMORYBARRIERCOUNT, 1);
        memPutAddress(dependency + VkDependencyInfo.PIMAGEMEMORYBARRIERS, barrier);
        nvkCmdPipelineBarrier2(commands, dependency);
    }

    private static void layers(long subresource, int mip) {
        memPutInt(subresource + VkImageSubresourceLayers.ASPECTMASK, VK_IMAGE_ASPECT_COLOR_BIT);
        memPutInt(subresource + VkImageSubresourceLayers.MIPLEVEL, mip);
        memPutInt(subresource + VkImageSubresourceLayers.LAYERCOUNT, 1);
    }

    /** offsets[1] = (width, height, 1); offsets[0] stays (0, 0, 0). VkOffset3D is three ints. */
    private static void corner(long offsets, int width, int height) {
        memPutInt(offsets + 12, width);
        memPutInt(offsets + 16, height);
        memPutInt(offsets + 20, 1);
    }

    /**
     * Removes the texture in sampled slot {@code slot}: materials must no longer name it from the next frame on. Its
     * image and slot are freed once no frame in flight can sample it, at the next add or remove.
     */
    public void remove(int slot) {
        GpuImage image = images.remove(slot);
        if (image == null) {
            throw new IllegalArgumentException("No texture in slot " + slot);
        }
        bytes -= sizes.remove(slot);
        boolean[] gone = freed.remove(slot);
        Bindless bindless = device.bindless();
        retiring.add(() -> {
            gone[0] = true;
            bindless.removeSampled(slot);
            image.close();
        });
        retiring.release();
    }

    /** Whether sampled slot {@code slot} holds one of these textures. */
    public boolean contains(int slot) {
        return images.containsKey(slot);
    }

    /** How many textures there are. */
    public int count() {
        return images.size();
    }

    /** The GPU memory the textures take, mip chains included (about 4/3 of level 0). */
    public long bytes() {
        return bytes;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            device.waitIdle();
            uploads.close();
            for (Map.Entry<Integer, GpuImage> entry : images.entrySet()) {
                device.bindless().removeSampled(entry.getKey());
                entry.getValue().close();
            }
            retiring.close();
        }
    }
}
