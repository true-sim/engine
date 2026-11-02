package com.truesim.engine.render.particles;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBufferToImage;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;
import static org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.ValueLayout;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

/**
 * A vector field for particles' {@code force.field} modules (effects.md §2.7): accelerations on a 3D grid, a 3D
 * texture of half floats in the device's bindless set, sampled with linear filtering over the module's box. Made once
 * (it waits for its upload: setup, not a frame); register its slot with {@link Particles#texture} under the asset the
 * effects name, before they play. Closed by its owner once no frame reads it.
 *
 * <pre>{@code
 * VectorField swirl = VectorField.of(device, 16, 16, 16, accelerations);   // x, y, z per cell, x fastest
 * particles.texture(SWIRL, swirl.slot());
 * }</pre>
 */
public final class VectorField implements AutoCloseable {

    private final VulkanDevice device;
    private final GpuImage image;
    private final int slot;

    private VectorField(VulkanDevice device, GpuImage image, int slot) {
        this.device = device;
        this.image = image;
        this.slot = slot;
    }

    /**
     * A field of {@code width} by {@code height} by {@code depth} cells from {@code vectors}: three floats per cell (an
     * acceleration in m/s²), x fastest, then y, then z.
     */
    @AllowAllocation("made once")
    public static VectorField of(VulkanDevice device, int width, int height, int depth, float[] vectors) {
        Objects.requireNonNull(device, "device");
        if (width < 1 || height < 1 || depth < 1 || vectors.length != 3L * width * height * depth) {
            throw new IllegalArgumentException("A " + width + " x " + height + " x " + depth + " field takes "
                + 3L * width * height * depth + " floats: " + vectors.length);
        }
        int cells = width * height * depth;
        GpuImage image = GpuImage.create(device, Format.R16G16B16A16_SFLOAT, width, height, depth, 1, false, 1, 1,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        try (GpuBuffer staging = GpuBuffer.hostVisible(device, 8L * cells, VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
            for (int i = 0; i < cells; i++) {
                for (int c = 0; c < 4; c++) {
                    short half = Float.floatToFloat16(c < 3 ? vectors[3 * i + c] : 0);
                    staging.memory().setAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, 4L * i + c, half);
                }
            }
            device.submitAndWait(device.graphics(), commands -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    barrier(commands, stack, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_PIPELINE_STAGE_2_NONE, VK_ACCESS_2_NONE, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_TRANSFER_WRITE_BIT);
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.imageExtent().set(width, height, depth);
                    vkCmdCopyBufferToImage(commands, staging.handle(), image.handle(),
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                    barrier(commands, stack, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                        VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
                }
            });
        } catch (RuntimeException e) {
            image.close();
            throw e;
        }
        return new VectorField(device, image, device.bindless().addSampled(image.view()));
    }

    private static void barrier(org.lwjgl.vulkan.VkCommandBuffer commands, MemoryStack stack, GpuImage image,
        int oldLayout, int newLayout, long srcStage, long srcAccess, long dstStage, long dstAccess) {
        VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2).srcStageMask(srcStage).srcAccessMask(srcAccess)
            .dstStageMask(dstStage).dstAccessMask(dstAccess).oldLayout(oldLayout).newLayout(newLayout)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image.handle());
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier2(commands, VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
            .pImageMemoryBarriers(barrier));
    }

    /** Its sampled slot in the bindless set. */
    public int slot() {
        return slot;
    }

    @Override
    public void close() {
        device.bindless().removeSampled(slot);
        image.close();
    }
}
