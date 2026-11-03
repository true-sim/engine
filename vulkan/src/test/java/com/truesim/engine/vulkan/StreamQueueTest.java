package com.truesim.engine.vulkan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO;

import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;

/** Uploads on the transfer queue, signalling what frames wait for, within a budget per frame; validated. */
class StreamQueueTest {

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

    private static void copy(org.lwjgl.vulkan.VkCommandBuffer commands, GpuBuffer from, GpuBuffer to, long bytes) {
        try (MemoryStack stack = stackPush()) {
            long region = stack.ncalloc(VkBufferCopy.ALIGNOF, 1, VkBufferCopy.SIZEOF);
            memPutLong(region + VkBufferCopy.SIZE, bytes);
            Commands.copyBuffer(commands, from.handle(), to.handle(), 1, region);
        }
    }

    /** Waits on the host until {@code stream} reached {@code value}. */
    private static void await(VulkanDevice device, StreamQueue stream, long value) {
        try (MemoryStack stack = stackPush()) {
            long info = stack.ncalloc(VkSemaphoreWaitInfo.ALIGNOF, 1, VkSemaphoreWaitInfo.SIZEOF);
            memPutInt(info + VkSemaphoreWaitInfo.STYPE, VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO);
            memPutInt(info + VkSemaphoreWaitInfo.SEMAPHORECOUNT, 1);
            memPutAddress(info + VkSemaphoreWaitInfo.PSEMAPHORES, org.lwjgl.system.MemoryUtil.memAddress(stack.longs(stream.semaphore())));
            memPutAddress(info + VkSemaphoreWaitInfo.PVALUES, org.lwjgl.system.MemoryUtil.memAddress(stack.longs(value)));
            device.check(device.direct().waitSemaphores(info, Long.MAX_VALUE), "wait");
        }
    }

    private static boolean freed(GpuBuffer buffer) {
        try {
            buffer.memory();
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }

    @Test
    void copiesRunOnTheTransferQueueWithinAFramesBudget() throws Exception {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            FrameSlots slots = new FrameSlots(2);
            assertThat(StreamQueue.open(device, FrameSlots.single(), 1)).as("frames waited for anyway").isNull();
            StreamQueue stream = StreamQueue.open(device, slots, 1024);
            StreamQueue again = StreamQueue.open(device, slots, 1);
            assertThat(again).as("one per frame slots").isSameAs(stream);
            assertThat(slots.waits()).containsExactly(stream);
            GpuBuffer target = GpuBuffer.deviceLocal(device, 1024, VK_BUFFER_USAGE_TRANSFER_DST_BIT
                | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, device.graphics().family(), stream.family());
            GpuBuffer first = GpuBuffer.hostVisible(device, 512, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            first.memory().fill((byte) 7);
            GpuBuffer second = GpuBuffer.hostVisible(device, 1024, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            second.memory().fill((byte) 9);
            slots.begin(0);
            slots.end();
            assertThat(stream.value()).as("an empty submit warms the queue").isEqualTo(1);
            assertThat(stream.submit(512, commands -> copy(commands, first, target, 512), first)).isTrue();
            assertThat(stream.value()).as("what the next frame waits for").isEqualTo(2);
            assertThat(stream.room(1024)).isFalse();
            assertThat(stream.submit(1024, commands -> copy(commands, second, target, 1024), null))
                .as("over the frame's budget: through the frame instead").isFalse();
            assertThat(stream.bytesRefused()).isEqualTo(1024);
            await(device, stream, 2);
            stream.release();
            long until = System.nanoTime() + 5_000_000_000L; // freed on the queue's thread for frees, soon
            while (!freed(first) && System.nanoTime() < until) {
                Thread.sleep(1);
            }
            assertThatIllegalStateException().as("freed once copied").isThrownBy(first::memory);
            slots.begin(1);
            slots.end();
            assertThat(stream.submit(1024, commands -> copy(commands, second, target, 1024), null))
                .as("a frame's first always fits").isTrue();
            assertThat(stream.bytesStreamed()).isEqualTo(1536);
            await(device, stream, 3);
            try (GpuBuffer readback = GpuBuffer.hostVisible(device, 1024, VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
                device.submitAndWait(device.graphics(), commands -> copy(commands, target, readback, 1024));
                assertThat(readback.memory().get(ValueLayout.JAVA_BYTE, 1000)).isEqualTo((byte) 9);
            }
            stream.budget(0);
            assertThat(stream.room(1)).isFalse();
            again.close();
            assertThat(slots.waits()).as("still used once").containsExactly(stream);
            stream.close();
            assertThat(slots.waits()).isEmpty();
            target.close();
            second.close();
            assertThat(instance.validationErrors()).as("validation errors").isZero();
        }
    }
}
