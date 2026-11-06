package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;
import static org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2;

import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

/**
 * 3D and dynamic textures (core prerequisite 7.9): volumes uploaded within frames, dynamic textures cleared, changed
 * regions staged and copied at the next frame's start without a queue waited for, a budget a frame, storage images in
 * the general layout; read back texel by texel, validated.
 */
class DynamicTexturesTest {

    private VulkanInstance instance;
    private VulkanDevice device;
    private final FrameSlots slots = new FrameSlots(2);
    private Textures textures;
    private long frame;

    @BeforeEach
    void start() {
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        textures = new Textures(device, slots);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            textures.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** A frame: its start recorded (uploads, regions), then {@code texture}'s texels copied into {@code out}. */
    private void frame(int texture, GpuBuffer out, boolean general) {
        slots.begin((int) (frame++ % slots.count()));
        device.submitAndWait(device.graphics(), commands -> {
            slots.recordStart(commands);
            if (out != null) {
                readBack(commands, textures.image(texture), out, general);
            }
        });
        slots.end();
    }

    private static void readBack(VkCommandBuffer commands, GpuImage image, GpuBuffer out, boolean general) {
        int layout = general ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            barrier(commands, stack, image, layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.imageExtent().set(image.width(), image.height(), image.depth());
            vkCmdCopyImageToBuffer(commands, image.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, out.handle(),
                region);
            barrier(commands, stack, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, layout,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        }
    }

    private static void barrier(VkCommandBuffer commands, MemoryStack stack, GpuImage image, int from, int to,
        long srcStage, long srcAccess, long dstStage, long dstAccess) {
        VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2).srcStageMask(srcStage).srcAccessMask(srcAccess)
            .dstStageMask(dstStage).dstAccessMask(dstAccess).oldLayout(from).newLayout(to)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image.handle());
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier2(commands, VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
            .pImageMemoryBarriers(barrier));
    }

    private static int texel(GpuBuffer out, int width, int height, int x, int y, int z) {
        return out.memory().getAtIndex(ValueLayout.JAVA_INT, (long) (z * height + y) * width + x);
    }

    @Test
    void volumesArriveInTheNextFrameSliceBySlice() {
        MemorySegment texels = Arena.ofAuto().allocate(4L * 4 * 3 * 2);
        for (int i = 0; i < 24; i++) {
            texels.setAtIndex(ValueLayout.JAVA_INT, i, i + 1);
        }
        int volume = textures.volume(TextureFormat.RGBA8, 4, 3, 2, texels);
        assertThat(textures.image(volume).depth()).isEqualTo(2);
        try (GpuBuffer out = GpuBuffer.hostVisible(device, 4L * 24, VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            frame(volume, out, false);
            assertThat(texel(out, 4, 3, 0, 0, 0)).isEqualTo(1);
            assertThat(texel(out, 4, 3, 3, 2, 1)).isEqualTo(24);
            assertThat(texel(out, 4, 3, 1, 1, 1)).isEqualTo(18);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> textures.volume(TextureFormat.RGBA8, 4, 3, 2,
            Arena.ofAuto().allocate(8))).withMessageContaining("4 x 3 x 2");
        textures.remove(volume);
        assertThat(textures.contains(volume)).isFalse();
    }

    @Test
    void changedRegionsArriveAtTheNextFramesStartWithinTheBudget() {
        int texture = textures.dynamic(TextureFormat.RGBA8, 4, 4, 2, false);
        assertThat(textures.isDynamic(texture)).isTrue();
        assertThat(textures.storageSlot(texture)).isEqualTo(-1);
        MemorySegment cpu = Arena.ofAuto().allocate(4L * 4 * 4 * 2);
        try (GpuBuffer out = GpuBuffer.hostVisible(device, cpu.byteSize(), VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            frame(texture, out, false);
            assertThat(texel(out, 4, 4, 2, 2, 1)).as("cleared when made").isZero();
            // A 2 x 2 box of slice 1 changes on the CPU: only it goes up
            for (int y = 1; y < 3; y++) {
                for (int x = 1; x < 3; x++) {
                    cpu.setAtIndex(ValueLayout.JAVA_INT, (16 + y * 4 + x), 0x01020304 + x + 16 * y);
                }
            }
            cpu.setAtIndex(ValueLayout.JAVA_INT, 0, 77); // changed but not marked: stays as it was on the GPU
            assertThat(textures.update(texture, 1, 1, 1, 2, 2, 1, cpu, 4L * (16 + 4 + 1), 16, 64)).isTrue();
            assertThat(textures.pendingUpdates()).isOne();
            frame(texture, out, false);
            assertThat(textures.pendingUpdates()).isZero();
            assertThat(texel(out, 4, 4, 1, 1, 1)).isEqualTo(0x01020304 + 1 + 16);
            assertThat(texel(out, 4, 4, 2, 2, 1)).isEqualTo(0x01020304 + 2 + 32);
            assertThat(texel(out, 4, 4, 0, 0, 0)).isZero();
            assertThat(texel(out, 4, 4, 3, 3, 1)).isZero();
            // A budget of 64 bytes a frame: a box of 64 fits, the next waits for the next frame
            textures.updateBudget(64);
            assertThat(textures.updateBudget()).isEqualTo(64);
            assertThat(textures.update(texture, 0, 0, 0, 4, 4, 1, cpu, 0, 16, 64)).isTrue();
            assertThat(textures.update(texture, 0, 0, 1, 1, 1, 1, cpu, 64, 16, 64)).as("no room left").isFalse();
            frame(texture, out, false);
            assertThat(texel(out, 4, 4, 0, 0, 0)).isEqualTo(77);
            assertThat(textures.update(texture, 0, 0, 1, 1, 1, 1, cpu, 64, 16, 64)).isTrue();
            assertThatIllegalArgumentException().isThrownBy(() -> textures.update(texture, 0, 0, 0, 4, 4, 2, cpu, 0, 16,
                64)).withMessageContaining("render.textureUpdateBudget");
            frame(texture, null, false);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> textures.update(texture, 3, 0, 0, 2, 1, 1, cpu, 0, 16,
            64)).withMessageContaining("outside");
        assertThatIllegalArgumentException().isThrownBy(() -> textures.update(12345, 0, 0, 0, 1, 1, 1, cpu, 0, 16,
            64)).withMessageContaining("Not a dynamic texture");
        // Removed with a region waiting: the region is dropped with it
        textures.updateBudget(4096);
        assertThat(textures.update(texture, 0, 0, 0, 1, 1, 1, cpu, 0, 16, 64)).isTrue();
        textures.remove(texture);
        assertThat(textures.pendingUpdates()).isZero();
        assertThat(textures.isDynamic(texture)).isFalse();
        for (int f = 0; f < 3; f++) {
            frame(texture, null, false);
        }
    }

    @Test
    void storageTexturesStayGeneralForPassesWritingThem() {
        assumeThat(textures.supportsStorage(TextureFormat.RGBA8)).isTrue();
        int texture = textures.dynamic(TextureFormat.RGBA8, 2, 2, 1, true);
        assertThat(textures.storageSlot(texture)).isNotNegative();
        MemorySegment cpu = Arena.ofAuto().allocate(16);
        cpu.setAtIndex(ValueLayout.JAVA_INT, 3, 42);
        assertThat(textures.update(texture, 0, 0, 0, 2, 2, 1, cpu, 0, 8, 16)).isTrue();
        try (GpuBuffer out = GpuBuffer.hostVisible(device, 16, VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            frame(texture, out, true);
            assertThat(texel(out, 2, 2, 1, 1, 0)).isEqualTo(42);
        }
        assertThat(textures.storageSlot(-1)).isEqualTo(-1);
        textures.remove(texture);
    }
}
