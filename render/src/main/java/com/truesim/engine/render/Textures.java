package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_BLIT_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_BLIT_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
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
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.Commands;
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
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;

/**
 * Material textures in the device's bindless set: each uploaded once with its mip chain, then sampled by slot, e.g.
 * from a {@link Material}. The chain comes with the texels or is made on the GPU ({@link #GENERATE}: each level a
 * linear downsample of the one before). Colors are sRGB ({@link #color}): the sampler returns them linear. Data such as
 * normal maps and occlusion-roughness-metalness are linear ({@link #data}). Other formats, high dynamic range and block
 * compressed ones among them, go through {@link #add(TextureFormat, int, int, int, MemorySegment)}. Each texture keeps
 * the sampler it was added with ({@link TextureSampler}, repeating unless named), in the bits above its slot: what
 * materials name is that whole number.
 *
 * <pre>{@code
 * try (Textures textures = new Textures(device)) {
 *     int albedo = textures.color(512, 512, rgba);      // 4 bytes per texel, rows top to bottom
 *     int normals = textures.data(512, 512, xyz);
 *     if (textures.supports(TextureFormat.BC7_SRGB)) {  // with all 10 levels, as compressed textures come
 *         int bark = textures.add(TextureFormat.BC7_SRGB, 512, 512, 10, blocks);
 *     }
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

    /** As a texture's mip count: only level 0 given, the rest of the chain made on the GPU ({@link #generatesMips}). */
    public static final int GENERATE = 0;

    private final VulkanDevice device;
    private final int[] formatFeatures; // per format: what the GPU does with images of it (optimal tiling)
    private final FrameSlots slots;
    private final FrameUploads uploads;
    // Live textures by bindless slot, and removed ones waiting for the frames that may sample them.
    private final Map<Integer, GpuImage> images = new HashMap<>();
    private final Map<Integer, Long> sizes = new HashMap<>();
    // Per live texture whose upload may still wait for a frame: set once it is freed, so the upload is dropped.
    private final Map<Integer, boolean[]> freed = new HashMap<>();
    private final Retiring retiring;
    private final java.util.List<Materials> namers = new java.util.ArrayList<>(); // checked before a texture goes
    private long bytes;
    private boolean closed;

    /** Textures for frames the caller waits for between removing one and adding another. */
    public Textures(VulkanDevice device) {
        this(device, FrameSlots.single());
    }

    /** Textures whose removed images stay until no frame of {@code slots} (a frame graph's) can sample them. */
    public Textures(VulkanDevice device, FrameSlots slots) {
        this.device = device;
        this.slots = slots;
        this.uploads = new FrameUploads(device, slots);
        this.retiring = new Retiring(slots);
        TextureFormat[] formats = TextureFormat.values();
        formatFeatures = new int[formats.length];
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            for (TextureFormat format : formats) {
                boolean enabled = format.name().startsWith("BC") ? device.bcTextures()
                    : !format.name().startsWith("ASTC") || device.astcTextures();
                if (enabled) {
                    vkGetPhysicalDeviceFormatProperties(device.gpu().handle(), format.vulkan(), properties);
                    formatFeatures[format.ordinal()] = properties.optimalTilingFeatures();
                }
            }
        }
    }

    /** Whether textures of {@code format} can be added: the GPU samples it, with linear filtering. */
    public boolean supports(TextureFormat format) {
        int needed = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
        return (formatFeatures[format.ordinal()] & needed) == needed;
    }

    /**
     * Whether textures of {@code format} can have their mip chain made on the GPU ({@link #GENERATE}): plain formats
     * the GPU blits between with linear filtering, not compressed ones.
     */
    public boolean generatesMips(TextureFormat format) {
        int needed = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
        return supports(format) && !format.compressed() && (formatFeatures[format.ordinal()] & needed) == needed;
    }

    /** An sRGB color texture, {@code rgba} 4 bytes per texel, its mip chain made on the GPU; its sampled slot. */
    public int color(int width, int height, MemorySegment rgba) {
        return add(TextureFormat.RGBA8_SRGB, width, height, GENERATE, rgba);
    }

    /**
     * A linear data texture (normals, occlusion-roughness-metalness), 4 bytes per texel, its mip chain made on the GPU;
     * its sampled slot.
     */
    public int data(int width, int height, MemorySegment rgba) {
        return add(TextureFormat.RGBA8, width, height, GENERATE, rgba);
    }

    /**
     * A {@code width} x {@code height} texture of {@code format}; its sampled slot. {@code texels} holds its first
     * {@code mips} levels, level 0 first, each tightly packed ({@link TextureFormat#chainBytes}): sampled with those
     * levels only, so give the full chain ({@link GpuImage#fullMipCount}) for textures seen from afar. With
     * {@link #GENERATE}, level 0 only, the full chain made on the GPU (where {@link #generatesMips}).
     */
    public int add(TextureFormat format, int width, int height, int mips, MemorySegment texels) {
        return add(format, width, height, mips, texels, TextureSampler.REPEAT);
    }

    /**
     * {@link #add(TextureFormat, int, int, int, MemorySegment)}, sampled by materials with {@code sampler}: the texture
     * as materials name it, its slot and sampler.
     */
    public int add(TextureFormat format, int width, int height, int mips, MemorySegment texels,
        TextureSampler sampler) {
        checkOpen();
        Upload upload = upload(format, width, height, mips, texels, sampler);
        boolean[] gone = new boolean[1];
        try {
            uploads.record(texels, (commands, staging, offset) -> {
                if (!gone[0]) {
                    upload.record(commands, staging, offset);
                }
            });
        } catch (RuntimeException e) {
            upload.image.close();
            throw e;
        }
        return register(upload, gone);
    }

    /** A texture's image and how its levels arrive, its arguments checked. */
    private record Upload(GpuImage image, TextureFormat format, int width, int height, int mips, boolean generate,
        TextureSampler sampler) {

        /** Its levels from the staging buffer at {@code offset}, all left shader-readable. */
        void record(VkCommandBuffer commands, GpuBuffer staging, long offset) {
            if (generate) {
                generated(commands, staging, offset, image, width, height, mips);
            } else {
                given(commands, staging, offset, this);
            }
        }

        long bytes() {
            return format.chainBytes(width, height, mips);
        }
    }

    private Upload upload(TextureFormat format, int width, int height, int mips, MemorySegment texels,
        TextureSampler sampler) {
        java.util.Objects.requireNonNull(sampler, "sampler");
        if (!supports(format)) {
            throw new IllegalArgumentException("This GPU does not sample " + format + " textures");
        }
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Texture sizes are positive: " + width + " x " + height);
        }
        int full = GpuImage.fullMipCount(width, height);
        if (mips < GENERATE || mips > full) {
            throw new IllegalArgumentException("A " + width + " x " + height + " texture has 1 to " + full
                + " mip levels, or GENERATE: " + mips);
        }
        boolean generate = mips == GENERATE;
        if (generate && !generatesMips(format)) {
            throw new IllegalArgumentException("Mip chains of " + format + " textures are not made on this GPU: give "
                + "them");
        }
        long bytes = format.chainBytes(width, height, generate ? 1 : mips);
        if (texels.byteSize() != bytes) {
            throw new IllegalArgumentException("A " + width + " x " + height + " " + format + " texture of "
                + (generate ? 1 : mips) + " levels takes " + bytes + " bytes: " + texels.byteSize());
        }
        int levels = generate ? full : mips;
        int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
            | (generate ? VK_IMAGE_USAGE_TRANSFER_SRC_BIT : 0);
        GpuImage image = GpuImage.mipmapped(device, width, height, format.vulkan(), usage, VK_IMAGE_ASPECT_COLOR_BIT,
            levels);
        return new Upload(image, format, width, height, levels, generate, sampler);
    }

    /**
     * A texture made ready on any thread ({@link #prepareColor}, {@link #prepareData}): its image made and its texels
     * staged, the costly part of adding one (16 MB copied for 2048 x 2048, and memory allocated). Add it on the frame's
     * thread with {@link #add(Prepared)}; close it if it is not added.
     */
    public static final class Prepared implements AutoCloseable {
        private final Upload upload;
        private final GpuBuffer staging;
        private boolean taken;

        private Prepared(Upload upload, GpuBuffer staging) {
            this.upload = upload;
            this.staging = staging;
        }

        /** Frees it, unless it was added. */
        @Override
        public void close() {
            if (!taken) {
                taken = true;
                staging.close();
                upload.image.close();
            }
        }
    }

    /** {@link #color} made ready on any thread: add it with {@link #add(Prepared)} on the frame's thread. */
    public Prepared prepareColor(int width, int height, MemorySegment rgba) {
        return prepare(TextureFormat.RGBA8_SRGB, width, height, GENERATE, rgba);
    }

    /** {@link #data} made ready on any thread: add it with {@link #add(Prepared)} on the frame's thread. */
    public Prepared prepareData(int width, int height, MemorySegment rgba) {
        return prepare(TextureFormat.RGBA8, width, height, GENERATE, rgba);
    }

    /**
     * {@link #add(TextureFormat, int, int, int, MemorySegment)} made ready on any thread: add it with
     * {@link #add(Prepared)} on the frame's thread.
     */
    public Prepared prepare(TextureFormat format, int width, int height, int mips, MemorySegment texels) {
        return prepare(format, width, height, mips, texels, TextureSampler.REPEAT);
    }

    /** {@link #prepare(TextureFormat, int, int, int, MemorySegment)}, sampled by materials with {@code sampler}. */
    public Prepared prepare(TextureFormat format, int width, int height, int mips, MemorySegment texels,
        TextureSampler sampler) {
        Upload upload = upload(format, width, height, mips, texels, sampler);
        GpuBuffer staging;
        try {
            staging = FrameUploads.staging(device, texels.byteSize());
        } catch (RuntimeException e) {
            upload.image.close();
            throw e;
        }
        MemorySegment.copy(texels, 0, staging.memory(), 0, texels.byteSize());
        return new Prepared(upload, staging);
    }

    /**
     * Adds {@code prepared} ({@link #prepareColor}): on the frame's thread, as {@link #color}, but nothing is copied or
     * allocated here; its sampled slot. The prepared texture is these textures' from now on.
     */
    public int add(Prepared prepared) {
        checkOpen();
        if (prepared.taken) {
            throw new IllegalStateException("The prepared texture was added or closed already");
        }
        prepared.taken = true;
        Upload upload = prepared.upload;
        boolean[] gone = new boolean[1];
        uploads.record(prepared.staging, (commands, staging, offset) -> {
            if (!gone[0]) {
                upload.record(commands, staging, offset);
            }
        });
        return register(upload, gone);
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The textures are closed");
        }
    }

    /** {@code upload}'s sampled slot and bookkeeping, once it is queued. */
    private int register(Upload upload, boolean[] gone) {
        retiring.release();
        GpuImage image = upload.image;
        int slot = device.bindless().addSampled(image.view());
        long size = upload.bytes();
        images.put(slot, image);
        sizes.put(slot, size);
        freed.put(slot, gone);
        bytes += size;
        return upload.sampler.of(slot);
    }

    /** Each level from the staging buffer, from {@code offset} on, level after level, all left shader-readable. */
    private static void given(VkCommandBuffer commands, GpuBuffer staging, long offset, Upload upload) {
        GpuImage image = upload.image();
        int mips = upload.mips();
        try (MemoryStack stack = stackPush()) {
            barrier(commands, stack, image, 0, mips, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_PIPELINE_STAGE_2_NONE, VK_ACCESS_2_NONE, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT);
            long regions = stack.ncalloc(VkBufferImageCopy.ALIGNOF, mips, VkBufferImageCopy.SIZEOF);
            long at = offset;
            for (int mip = 0; mip < mips; mip++) {
                int width = Math.max(1, upload.width() >> mip);
                int height = Math.max(1, upload.height() >> mip);
                long region = regions + (long) mip * VkBufferImageCopy.SIZEOF;
                memPutLong(region + VkBufferImageCopy.BUFFEROFFSET, at);
                layers(region + VkBufferImageCopy.IMAGESUBRESOURCE, mip);
                memPutInt(region + VkBufferImageCopy.IMAGEEXTENT, width);
                memPutInt(region + VkBufferImageCopy.IMAGEEXTENT + 4, height);
                memPutInt(region + VkBufferImageCopy.IMAGEEXTENT + 8, 1);
                at += upload.format().levelBytes(width, height);
            }
            nvkCmdCopyBufferToImage(commands, staging.handle(), image.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                mips, regions);
            barrier(commands, stack, image, 0, mips, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        }
    }

    /**
     * Level 0 from the staging buffer at {@code offset}, each further level blitted from the one before, all left
     * shader-readable.
     */
    private static void generated(VkCommandBuffer commands, GpuBuffer staging, long offset, GpuImage image, int width,
        int height, int mips) {
        try (MemoryStack stack = stackPush()) {
            barrier(commands, stack, image, 0, mips, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_PIPELINE_STAGE_2_NONE, VK_ACCESS_2_NONE, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT);
            long region = stack.ncalloc(VkBufferImageCopy.ALIGNOF, 1, VkBufferImageCopy.SIZEOF);
            memPutLong(region + VkBufferImageCopy.BUFFEROFFSET, offset);
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
        Commands.pipelineBarrier2(commands, dependency);
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
     * Has {@link #remove} refuse a texture any material of {@code materials} still names
     * ({@link Materials#textureUsers}): its slot, freed, would be another texture's next.
     */
    public Textures namedBy(Materials materials) {
        if (!namers.contains(materials)) {
            namers.add(java.util.Objects.requireNonNull(materials, "materials"));
        }
        return this;
    }

    /**
     * Removes texture {@code texture} (as added, with any sampler): materials must no longer name it from the next frame
     * on (those of {@link #namedBy} are checked). Its image and slot are freed once no frame in flight can sample it,
     * at the next add or remove.
     */
    public void remove(int texture) {
        int slot = texture & 0xffff;
        if (texture < 0 || !images.containsKey(slot)) {
            throw new IllegalArgumentException("No texture " + texture);
        }
        for (int i = 0; i < namers.size(); i++) {
            int users = namers.get(i).textureUsers(texture);
            if (users > 0) {
                throw new IllegalStateException("Texture " + texture + " is still named by " + users + " materials");
            }
        }
        GpuImage image = images.remove(slot);
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

    /** Whether {@code texture} (with any sampler) is one of these textures. */
    public boolean contains(int texture) {
        return texture >= 0 && images.containsKey(texture & 0xffff);
    }

    /** How many textures there are. */
    public int count() {
        return images.size();
    }

    /** The GPU memory the textures' texels take, mip chains included (about 4/3 of level 0). */
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
