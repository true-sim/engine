package com.truesim.engine.vulkan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;

/** Calls through {@link Commands} report what the validation layer finds wrong in them, as LWJGL's calls do. */
class CommandsTest {

    @Test
    void validationErrorsInsideCommandsAreReportedNotFatal() {
        // The layer reports from inside the faulty call, through the instance's debug callback (an upcall): out of a
        // critical downcall, that crashed the JVM.
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        try (instance) {
            assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
            assumeThat(instance.validationEnabled()).as("the validation layer").isTrue();
            try (VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
                 GpuBuffer from = GpuBuffer.deviceLocal(device, 64, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                 GpuBuffer to = GpuBuffer.deviceLocal(device, 64, VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
                int before = instance.validationErrors();
                device.submitAndWait(device.graphics(), commands -> {
                    try (MemoryStack stack = stackPush()) {
                        long region = stack.ncalloc(VkBufferCopy.ALIGNOF, 1, VkBufferCopy.SIZEOF);
                        memPutLong(region + VkBufferCopy.SIZE, 64);
                        // Wrong on purpose: the source was made without TRANSFER_SRC usage.
                        Commands.copyBuffer(commands, from.handle(), to.handle(), 1, region);
                    }
                });
                assertThat(instance.validationErrors()).as("the copy reported").isGreaterThan(before);
            }
        }
    }
}
