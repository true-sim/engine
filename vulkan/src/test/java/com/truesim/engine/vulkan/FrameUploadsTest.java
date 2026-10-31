package com.truesim.engine.vulkan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

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
                uploads.record(arena.allocateFrom(ValueLayout.JAVA_INT, 5, 6, 7, 8), (commands, staging, offset) -> {
                    try (MemoryStack stack = stackPush()) {
                        vkCmdCopyBuffer(commands, staging.handle(), other.handle(),
                            VkBufferCopy.calloc(1, stack).srcOffset(offset).size(16));
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
    void passesDeclaringTheTargetsUploadInsteadOfTheFramesStart() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             Arena arena = Arena.ofConfined();
             GpuBuffer target = GpuBuffer.deviceLocal(device, 16, TARGET)) {
            FrameSlots slots = new FrameSlots(2);
            try (FrameUploads uploads = new FrameUploads(device, slots)) {
                uploads.uploadInPasses();
                uploads.uploadInPasses(); // once is enough
                uploads.write(target, 0, arena.allocateFrom(ValueLayout.JAVA_INT, 4, 5, 6, 7));
                slots.begin(0);
                device.submitAndWait(device.graphics(), slots::recordStart);
                assertThat(uploads.pendingCount()).as("the frame's start leaves them to the pass").isEqualTo(1);
                device.submitAndWait(device.graphics(), commands -> {
                    uploads.uploadInPass(commands);
                    // What a graph places from the pass's declared write and the reader's read.
                    Barriers.memory(commands, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
                });
                slots.end();
                assertThat(uploads.pendingCount()).isZero();
                assertThat(read(device, target)).containsExactly(4, 5, 6, 7);
            }
        }
    }

    @Test
    void stagingBuffersAreSharedAndReused() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             Arena arena = Arena.ofConfined();
             GpuBuffer target = GpuBuffer.deviceLocal(device, 64, TARGET);
             GpuBuffer big = GpuBuffer.deviceLocal(device, FrameUploads.CHUNK_BYTES + 16, TARGET)) {
            FrameSlots slots = new FrameSlots(2);
            try (FrameUploads uploads = new FrameUploads(device, slots)) {
                for (int frame = 0; frame < 8; frame++) {
                    // Uploads of a frame share one staging buffer, each at its own aligned offset.
                    for (int i = 0; i < 4; i++) {
                        uploads.write(target, 16L * i, arena.allocateFrom(ValueLayout.JAVA_INT, frame, i), 8);
                    }
                    slots.begin(frame % 2);
                    device.submitAndWait(device.graphics(), slots::recordStart);
                    slots.end();
                    assertThat(read(device, target)).containsExactly(frame, 0, 0, 0, frame, 1, 0, 0, frame, 2, 0, 0,
                        frame, 3, 0, 0);
                }
                // One staging buffer per frame in flight, and the one filled when the oldest is not free yet.
                assertThat(uploads.stagingBuffersCreated()).isLessThanOrEqualTo(3);
                int before = device.openChildren();
                // An upload bigger than the shared buffers gets one of its own, freed once its frame is done.
                MemorySegment large = arena.allocate(FrameUploads.CHUNK_BYTES + 16);
                uploads.write(big, 0, large);
                uploads.write(target, 0, arena.allocateFrom(ValueLayout.JAVA_INT, 7)); // in a shared one again
                for (int frame = 0; frame < 3; frame++) {
                    slots.begin(frame % 2);
                    device.submitAndWait(device.graphics(), slots::recordStart);
                    slots.end();
                }
                assertThat(read(device, target)[0]).isEqualTo(7);
                if (before >= 0) {
                    assertThat(device.openChildren()).as("the big staging buffer freed").isEqualTo(before);
                }
            }
            assertThat(device.openChildren()).isIn(-1, 2); // closing freed every staging buffer: target and big remain
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
            uploads.record(arena.allocateFrom(ValueLayout.JAVA_INT, 7), (commands, staging, offset) -> {
                try (MemoryStack stack = stackPush()) {
                    vkCmdCopyBuffer(commands, staging.handle(), target.handle(), VkBufferCopy.calloc(1, stack)
                        .srcOffset(offset).dstOffset(12).size(4));
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

    @Test
    void stagingFilledOnAnotherThreadIsRecordedAndFreed() throws Exception {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GpuBuffer target = GpuBuffer.deviceLocal(device, 16, TARGET)) {
            FrameSlots slots = new FrameSlots(2);
            try (FrameUploads uploads = new FrameUploads(device, slots)) {
                // Filled by a loader thread; only recorded on the frame's.
                GpuBuffer staging = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    GpuBuffer filled = FrameUploads.staging(device, 16);
                    for (int i = 0; i < 4; i++) {
                        filled.memory().setAtIndex(ValueLayout.JAVA_INT, i, 10 + i);
                    }
                    return filled;
                }).get();
                uploads.record(staging, (commands, from, offset) -> {
                    try (MemoryStack stack = stackPush()) {
                        vkCmdCopyBuffer(commands, from.handle(), target.handle(),
                            VkBufferCopy.calloc(1, stack).srcOffset(offset).size(12)); // uploads of a frame: apart
                    }
                });
                uploads.write(target, 12, java.lang.foreign.Arena.ofAuto().allocateFrom(ValueLayout.JAVA_INT, 99));
                assertThat(uploads.pendingCount()).isEqualTo(2);
                assertThat(uploads.pendingBytes()).isEqualTo(20);
                for (int frame = 0; frame < 4; frame++) { // its frame, then the frames that free its staging
                    slots.begin(frame % 2);
                    device.submitAndWait(device.graphics(), slots::recordStart);
                    slots.end();
                }
                assertThat(read(device, target)).as("the staged buffer's, and the write's")
                    .containsExactly(10, 11, 12, 99);
            }
            // Without frames: at once, and freed.
            try (FrameUploads uploads = new FrameUploads(device, FrameSlots.single())) {
                GpuBuffer staging = FrameUploads.staging(device, 4);
                staging.memory().setAtIndex(ValueLayout.JAVA_INT, 0, 42);
                uploads.record(staging, (commands, from, offset) -> {
                    try (MemoryStack stack = stackPush()) {
                        vkCmdCopyBuffer(commands, from.handle(), target.handle(),
                            VkBufferCopy.calloc(1, stack).srcOffset(offset).size(4));
                    }
                });
                assertThat(read(device, target)).containsExactly(42, 11, 12, 99);
            }
            assertThat(instance.validationErrors()).isZero();
        }
    }
}
