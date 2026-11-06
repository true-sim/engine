package com.truesim.engine.vulkan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatIndexOutOfBoundsException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;

/** Records changed on the CPU and scattered on the GPU at the frame's start, validated. */
class RecordBufferTest {

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

    /** The records as ints, copied back. */
    private static int[] read(VulkanDevice device, RecordBuffer records) {
        try (GpuBuffer readback = GpuBuffer.hostVisible(device, records.buffer().size(),
            VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            device.submitAndWait(device.graphics(), commands -> {
                try (MemoryStack stack = stackPush()) {
                    vkCmdCopyBuffer(commands, records.buffer().handle(), readback.handle(),
                        VkBufferCopy.calloc(1, stack).size(records.buffer().size()));
                }
            });
            return readback.memory().toArray(ValueLayout.JAVA_INT);
        }
    }

    /** Writes record {@code index}: its words {@code value}, {@code value + 1}, ... */
    private static void fill(RecordBuffer records, int index, int value) {
        long at = records.offset(records.claim(index));
        for (int word = 0; word < records.recordBytes() / 4; word++) {
            records.changes(records.claim(index)).set(ValueLayout.JAVA_INT_UNALIGNED, at + 4L * word, value + word);
        }
    }

    @Test
    void changesScatterAtTheFramesStart() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            FrameSlots slots = new FrameSlots(2);
            try (RecordBuffer records = new RecordBuffer(device, 32, 4, slots)) {
                assertThat(records.recordBytes()).isEqualTo(32);
                assertThat(records.capacity()).isEqualTo(4);
                assertThat(records.deviceAddress()).isEqualTo(records.buffer().deviceAddress());
                fill(records, 2, 100);
                fill(records, 0, 10);
                fill(records, 2, 200); // again: the same entry, the last write wins
                assertThat(records.pendingCount()).isEqualTo(2);
                assertThat(read(device, records)).as("zeros until the frame").containsOnly(0);
                assertThatIllegalStateException().as("between frames").isThrownBy(() -> device.submitAndWait(
                    device.graphics(), records::upload)).withMessageContaining("within a frame");
                slots.begin(0);
                device.submitAndWait(device.graphics(), slots::recordStart);
                slots.end();
                assertThat(records.pendingCount()).isZero();
                int[] words = read(device, records);
                assertThat(words[0]).isEqualTo(10);
                assertThat(words[7]).isEqualTo(17);
                assertThat(words[8]).as("record 1, never set").isZero();
                assertThat(words[16]).isEqualTo(200);
                assertThat(words[23]).isEqualTo(207);
                // Cleared, and two uploads in one frame (a pass that changes records between passes).
                slots.begin(1);
                device.submitAndWait(device.graphics(), commands -> {
                    records.clear(0);
                    records.upload(commands);
                    records.upload(commands); // nothing changed since
                    fill(records, 3, 30);
                    records.upload(commands);
                    fill(records, 0, 1);
                    fill(records, 1, 1);
                    fill(records, 2, 1);
                    records.upload(commands); // more than the upload buffer has room for: a bigger one
                });
                slots.end();
                words = read(device, records);
                assertThat(words[0]).as("cleared, then set").isEqualTo(1);
                assertThat(words[8]).isEqualTo(1);
                assertThat(words[16]).isEqualTo(1);
                assertThat(words[24]).isEqualTo(30);
                assertThatIndexOutOfBoundsException().isThrownBy(() -> records.claim(4));
                assertThatIndexOutOfBoundsException().isThrownBy(() -> records.offset(3));
                records.close();
                records.close();
            }
            assertThatIllegalArgumentException().isThrownBy(() -> new RecordBuffer(device, 24, 4, slots));
            assertThatIllegalArgumentException().isThrownBy(() -> new RecordBuffer(device, 0, 4, slots));
            assertThatIllegalArgumentException().isThrownBy(() -> new RecordBuffer(device, 16, 0, slots));
        }
    }

    @Test
    void manyRecordsClearedAtOnceAreZerosAsIfClearedEach() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            FrameSlots slots = new FrameSlots(2);
            for (RecordBuffer.ChangeList list : RecordBuffer.ChangeList.values()) {
                try (RecordBuffer records = new RecordBuffer(device, 32, 300, slots)) {
                    records.changeList(list, 64 * 36); // in place: chunks of 64 records
                    for (int i = 0; i < 300; i++) {
                        fill(records, i, i + 1);
                    }
                    slots.begin(0);
                    device.submitAndWait(device.graphics(), slots::recordStart);
                    slots.end();
                    fill(records, 7, 500); // claimed already in this upload: zeroed where it is
                    int[] gone = new int[260];
                    for (int i = 0; i < gone.length; i++) {
                        gone[i] = i + 7; // 7 to 266, more than a block of zeros
                    }
                    records.clear(gone, 0, gone.length);
                    assertThatIndexOutOfBoundsException().isThrownBy(() -> records.clear(gone, 250, 20));
                    assertThatIndexOutOfBoundsException().isThrownBy(() -> records.clear(new int[] {300}, 0, 1));
                    slots.begin(1);
                    device.submitAndWait(device.graphics(), slots::recordStart);
                    slots.end();
                    int[] words = read(device, records);
                    assertThat(words[8 * 6]).as(list + ": kept").isEqualTo(7);
                    for (int i = 7; i < 267; i++) {
                        assertThat(words[8 * i]).as(list + ": record " + i).isZero();
                        assertThat(words[8 * i + 7]).isZero();
                    }
                    assertThat(words[8 * 267]).isEqualTo(268);
                }
            }
        }
    }

    @Test
    void recordsGrowKeepingWhatTheyHold() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            FrameSlots slots = new FrameSlots(2);
            try (RecordBuffer records = new RecordBuffer(device, 32, 2, slots)) {
                fill(records, 0, 10);
                slots.begin(0);
                device.submitAndWait(device.graphics(), slots::recordStart);
                slots.end();
                // A change claimed, then grown twice before the next upload: the change and the record stay.
                fill(records, 1, 20);
                long firstAddress = records.deviceAddress();
                records.grow(4);
                records.grow(8);
                assertThat(records.capacity()).isEqualTo(8);
                assertThat(records.deviceAddress()).as("frames recorded from now on read the new one")
                    .isNotEqualTo(firstAddress);
                assertThat(records.pendingCount()).isEqualTo(1);
                fill(records, 5, 50);
                slots.begin(1);
                device.submitAndWait(device.graphics(), slots::recordStart);
                slots.end();
                int[] words = read(device, records);
                assertThat(words).hasSize(64);
                assertThat(words[0]).as("copied over").isEqualTo(10);
                assertThat(words[8]).as("claimed before it grew").isEqualTo(20);
                assertThat(words[40]).isEqualTo(50);
                assertThat(words[16]).as("new room: zeros").isZero();
                assertThat(words[56]).isZero();
                // Grown with nothing changed: copied over all the same.
                records.grow(16);
                slots.begin(0);
                device.submitAndWait(device.graphics(), slots::recordStart);
                slots.end();
                words = read(device, records);
                assertThat(words).hasSize(128);
                assertThat(words[0]).isEqualTo(10);
                assertThat(words[40]).isEqualTo(50);
                assertThat(words[127]).isZero();
                assertThatIllegalArgumentException().isThrownBy(() -> records.grow(16));
                assertThatIllegalArgumentException().isThrownBy(() -> records.grow(Integer.MAX_VALUE));
                records.grow(32); // closed before the upload: the replaced buffer is freed with it
                device.waitIdle();
            }
            assertThat(instance.validationErrors()).as("validation errors").isZero();
        }
    }

    @Test
    void passesDeclaringTheRecordsUploadThemInsteadOfTheFramesStart() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            FrameSlots slots = new FrameSlots(2);
            try (RecordBuffer records = new RecordBuffer(device, 32, 4, slots)) {
                records.uploadInPasses();
                records.uploadInPasses(); // once is enough
                fill(records, 1, 50);
                slots.begin(0);
                device.submitAndWait(device.graphics(), slots::recordStart);
                assertThat(records.pendingCount()).as("the frame's start leaves them to the pass").isEqualTo(1);
                device.submitAndWait(device.graphics(), records::upload);
                assertThat(records.pendingCount()).as("another pass's upload leaves them to the pass too")
                    .isEqualTo(1);
                device.submitAndWait(device.graphics(), commands -> {
                    records.uploadInPass(commands);
                    // What a graph places from the pass's declared write and the reader's read.
                    Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                        VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_TRANSFER_READ_BIT);
                });
                slots.end();
                assertThat(records.pendingCount()).isZero();
                assertThat(read(device, records)[8]).isEqualTo(50);
            }
        }
    }

    @Test
    void manyLargeRecordsScatterInSeveralDispatches() {
        // A thread per 16 bytes: 4,100 records of 16 KiB are 4,198,400 threads, more than one dispatch's 4,194,240.
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             RecordBuffer records = new RecordBuffer(device, 16 * 1024, 4_100, FrameSlots.single())) {
            for (int i = 0; i < records.capacity(); i++) {
                long at = records.offset(records.claim(i));
                records.changes(records.claim(i)).set(ValueLayout.JAVA_INT_UNALIGNED, at, i);
                records.changes(records.claim(i)).set(ValueLayout.JAVA_INT_UNALIGNED, at + 16 * 1024 - 4, -i);
            }
            device.submitAndWait(device.graphics(), records::upload);
            int[] words = read(device, records);
            int perRecord = 16 * 1024 / 4;
            for (int i = 0; i < records.capacity(); i += 997) {
                assertThat(words[i * perRecord]).isEqualTo(i);
                assertThat(words[(i + 1) * perRecord - 1]).isEqualTo(-i);
            }
            assertThat(words[(records.capacity() - 1) * perRecord]).isEqualTo(records.capacity() - 1);
        }
    }

    @Test
    void changesWrittenInPlaceInChunksScatterAsCopiedOnes() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            FrameSlots slots = new FrameSlots(2);
            try (RecordBuffer records = new RecordBuffer(device, 32, 64, slots)) {
                assertThat(records.changeList()).isEqualTo(RecordBuffer.ChangeList.COPY);
                assertThat(records.hostBytes()).as("a list of the capacity").isEqualTo(36L * 64);
                // Chunks of 3 records (36 bytes each with its index): 10 changes take 4 chunks.
                records.changeList(RecordBuffer.ChangeList.DIRECT, 3 * 36);
                assertThat(records.changeList()).isEqualTo(RecordBuffer.ChangeList.DIRECT);
                assertThat(records.hostBytes()).as("no list of the capacity").isZero();
                for (int frame = 0; frame < 6; frame++) {
                    for (int i = 0; i < 10; i++) {
                        fill(records, (7 * i + frame) % 64, 1000 * frame + i);
                    }
                    fill(records, frame % 64, 5); // claimed again: the same entry, the last write wins
                    slots.begin(frame % 2);
                    device.submitAndWait(device.graphics(), slots::recordStart);
                    slots.end();
                    assertThat(records.pendingCount()).isZero();
                    int[] words = read(device, records);
                    for (int i = 1; i < 10; i++) {
                        assertThat(words[8 * ((7 * i + frame) % 64)]).as("frame %d, change %d", frame, i)
                            .isEqualTo(1000 * frame + i);
                    }
                    assertThat(words[8 * (frame % 64)]).isEqualTo(5);
                    assertThat(words[8 * (frame % 64) + 7]).isEqualTo(12);
                }
                long pinned = records.pinnedBytes();
                assertThat(pinned).as("chunks reused, not made each frame: 4 a frame, 3 frames' worth at most")
                    .isPositive().isLessThanOrEqualTo(12L * (128 + 3 * 32));
                // Switched back with changes waiting: they upload where they were written, then the list switches.
                fill(records, 63, 63);
                records.changeList(RecordBuffer.ChangeList.COPY, RecordBuffer.CHUNK_BYTES);
                assertThat(records.changeList()).as("after the upload").isEqualTo(RecordBuffer.ChangeList.DIRECT);
                slots.begin(0);
                device.submitAndWait(device.graphics(), slots::recordStart);
                slots.end();
                assertThat(records.changeList()).isEqualTo(RecordBuffer.ChangeList.COPY);
                assertThat(read(device, records)[8 * 63]).isEqualTo(63);
                // Grown while in place: chunks of the new size, the records kept.
                records.changeList(RecordBuffer.ChangeList.DIRECT, RecordBuffer.CHUNK_BYTES);
                records.grow(128);
                fill(records, 127, 127);
                slots.begin(1);
                device.submitAndWait(device.graphics(), slots::recordStart);
                slots.end();
                int[] words = read(device, records);
                assertThat(words[8 * 127]).isEqualTo(127);
                assertThat(words[8 * 63]).isEqualTo(63);
                assertThat(records.uploadNanos()).isPositive();
                device.waitIdle();
            }
            assertThatIllegalArgumentException().isThrownBy(() -> {
                try (RecordBuffer small = new RecordBuffer(device, 32, 4, slots)) {
                    small.changeList(RecordBuffer.ChangeList.DIRECT, 16);
                }
            });
            assertThat(instance.validationErrors()).as("validation errors").isZero();
        }
    }
}
