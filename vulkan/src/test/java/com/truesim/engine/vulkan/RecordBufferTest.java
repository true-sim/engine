package com.truesim.engine.vulkan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatIndexOutOfBoundsException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;

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
            records.changes().set(ValueLayout.JAVA_INT_UNALIGNED, at + 4L * word, value + word);
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
                    assertThatIllegalStateException().as("more than the buffer holds in one frame")
                        .isThrownBy(() -> records.upload(commands));
                });
                slots.end();
                words = read(device, records);
                assertThat(words[0]).as("cleared").isZero();
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
    void manyLargeRecordsScatterInSeveralDispatches() {
        // A thread per 16 bytes: 4,100 records of 16 KiB are 4,198,400 threads, more than one dispatch's 4,194,240.
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             RecordBuffer records = new RecordBuffer(device, 16 * 1024, 4_100, FrameSlots.single())) {
            for (int i = 0; i < records.capacity(); i++) {
                long at = records.offset(records.claim(i));
                records.changes().set(ValueLayout.JAVA_INT_UNALIGNED, at, i);
                records.changes().set(ValueLayout.JAVA_INT_UNALIGNED, at + 16 * 1024 - 4, -i);
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
}
