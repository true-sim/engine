package com.truesim.engine.vulkan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;

/** Uploads deferred to the next frame's start, validated: nothing reaches the GPU before, everything then. */
class FrameUploadsTest {

    private static final int TARGET = VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

    private static VulkanInstance instance() {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        return instance;
    }

    /** The ints of device-local {@code buffer}, copied back. */
    private static int[] read(VulkanDevice device, GpuBuffer buffer) {
        try (GpuBuffer readback = GpuBuffer.hostVisible(device, buffer.size(), VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            device.submitAndWait(device.graphics(), commands -> {
                try (MemoryStack stack = stackPush()) {
                    vkCmdCopyBuffer(commands, buffer.handle(), readback.handle(),
                        VkBufferCopy.calloc(1, stack).size(buffer.size()));
                }
            });
            return readback.memory().toArray(ValueLayout.JAVA_INT);
        }
    }

    @Test
    void uploadsWaitForTheNextFrame() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             Arena arena = Arena.ofConfined();
             GpuBuffer target = GpuBuffer.deviceLocal(device, 16, TARGET);
             GpuBuffer other = GpuBuffer.deviceLocal(device, 16, TARGET)) {
            FrameSlots slots = new FrameSlots(2);
            try (FrameUploads uploads = new FrameUploads(device, slots)) {
                assertThat(uploads.deferred()).isTrue();
                MemorySegment data = arena.allocateFrom(ValueLayout.JAVA_INT, 1, 2, 3);
                uploads.write(target, 4, data);
                data.setAtIndex(ValueLayout.JAVA_INT, 0, 9); // staged: the caller's data is free again
                uploads.write(target, 16, data.asSlice(0, 0)); // nothing
                uploads.record(arena.allocateFrom(ValueLayout.JAVA_INT, 5, 6, 7, 8), (commands, staging) -> {
                    try (MemoryStack stack = stackPush()) {
                        vkCmdCopyBuffer(commands, staging.handle(), other.handle(),
                            VkBufferCopy.calloc(1, stack).size(16));
                    }
                });
                assertThat(uploads.pendingCount()).isEqualTo(2);
                assertThat(uploads.pendingBytes()).isEqualTo(28);
                assertThat(read(device, target)).as("not before the frame").containsExactly(0, 0, 0, 0);
                // The frame: its first commands are the uploads, and what follows sees them.
                for (int frame = 0; frame < 3; frame++) {
                    slots.begin(frame % 2);
                    device.submitAndWait(device.graphics(), slots::recordStart);
                    slots.end();
                }
                assertThat(uploads.pendingCount()).isZero();
                assertThat(read(device, target)).containsExactly(0, 1, 2, 3);
                assertThat(read(device, other)).containsExactly(5, 6, 7, 8);
                assertThatIllegalArgumentException().isThrownBy(() -> uploads.write(target, 8, data));
                assertThatIllegalArgumentException().isThrownBy(() -> uploads.write(target, -1, data));
                uploads.write(target, 0, data); // dropped by close: waiting for a frame that never comes
                uploads.close();
                uploads.close();
                assertThatIllegalStateException().isThrownBy(() -> uploads.write(target, 0, data));
            }
            device.submitAndWait(device.graphics(), slots::recordStart); // closed uploads record nothing
            assertThat(read(device, target)).containsExactly(0, 1, 2, 3);
        }
    }

    @Test
    void withoutFramesUploadsAreDoneAtOnce() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             Arena arena = Arena.ofConfined();
             GpuBuffer target = GpuBuffer.deviceLocal(device, 16, TARGET);
             FrameUploads uploads = new FrameUploads(device, FrameSlots.single())) {
            assertThat(uploads.deferred()).isFalse();
            uploads.write(target, 0, arena.allocateFrom(ValueLayout.JAVA_INT, 4, 3, 2, 1));
            assertThat(read(device, target)).containsExactly(4, 3, 2, 1);
            uploads.record(arena.allocateFrom(ValueLayout.JAVA_INT, 7), (commands, staging) -> {
                try (MemoryStack stack = stackPush()) {
                    vkCmdCopyBuffer(commands, staging.handle(), target.handle(), VkBufferCopy.calloc(1, stack)
                        .dstOffset(12).size(4));
                }
            });
            assertThat(read(device, target)).containsExactly(4, 3, 2, 7);
            // Data of a length not a multiple of 4, padded to whole words with zeros.
            uploads.write(target, 8, arena.allocateFrom(ValueLayout.JAVA_BYTE, (byte) 5, (byte) 6, (byte) 7), 8);
            assertThat(read(device, target)).containsExactly(4, 3, 0x070605, 0);
            assertThatIllegalArgumentException().as("less than the data").isThrownBy(() -> uploads.write(target, 0,
                arena.allocate(8), 4));
            assertThat(uploads.pendingCount()).isZero();
        }
    }
}
