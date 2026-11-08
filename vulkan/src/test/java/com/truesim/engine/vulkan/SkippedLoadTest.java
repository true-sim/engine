package com.truesim.engine.vulkan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.engine.vulkan.testing.TestGpu;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Lavapipe (Mesa 26.0.8, 26.2.6) performs a load through a push constant's pointer in a branch no invocation takes
 * (some builds only when the shader returned early before it; true-sim/engine#456, docs/pitfalls.md): with the
 * pointer 0 the test JVM crashes. So the engine never hands a shader the address 0, even behind a branch that skips the
 * read: this checks that a valid address there is harmless and leaves the result as the branch says.
 * {@code TRUESIM_SKIPPED_LOAD_NULL=true} runs the same with the pointer 0, the crash on lavapipe (not for CI).
 */
class SkippedLoadTest {

    private TestGpu gpu;

    @BeforeEach
    void start() {
        gpu = TestGpu.open();
    }

    @AfterEach
    void stop() {
        gpu.close();
    }

    /** {@code guarded.slang}'s {@code entry} over {@code count} values with {@code maybe} as the skipped pointer. */
    private int[] run(String entry, int count, boolean nullPointer) {
        VulkanDevice device = gpu.device();
        try (GpuBuffer values = GpuBuffer.hostVisible(device, 4 * 256,
                 VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
             GpuBuffer stand = GpuBuffer.hostVisible(device, 16,
                 VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
             ShaderModule shader = ShaderModule.load(device, "shaders/test/guarded.spv");
             Pipeline pipeline = Pipeline.compute(device, shader, entry, 24)) {
            stand.memory().set(ValueLayout.JAVA_INT, 0, 1000); // what the branch would read, were it taken
            device.submitAndWait(device.compute(), commands -> {
                Commands.bindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.handle());
                try (MemoryStack stack = stackPush()) {
                    ByteBuffer push = stack.malloc(24);
                    push.putLong(0, values.deviceAddress()).putLong(8, nullPointer ? 0 : stand.deviceAddress())
                        .putInt(16, 0).putInt(20, count); // mode 0: the branch reading `maybe` is never taken
                    Commands.pushConstants(commands, pipeline.layout(), VK_SHADER_STAGE_ALL, 0, 24,
                        MemoryUtil.memAddress(push));
                }
                Commands.dispatch(commands, (count + 63) / 64, 1, 1);
            });
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                out[i] = values.memory().get(ValueLayout.JAVA_INT, 4L * i);
            }
            return out;
        }
    }

    @Test
    void aLoadInABranchNoInvocationTakesAfterAnEarlyReturnReadsNothing() {
        boolean nullPointer = "true".equals(System.getenv("TRUESIM_SKIPPED_LOAD_NULL"));
        for (String entry : new String[] {"guardedMain", "branchedMain", "uniformMain"}) {
            for (int count : new int[] {250, 256}) {
                int[] out = run(entry, count, nullPointer);
                assertThat(out[10]).as(entry + " over " + count).isEqualTo(7 + 10);
                assertThat(out[count - 1]).as(entry + " over " + count).isEqualTo(7 + count - 1);
            }
        }
        assertThat(gpu.instance().validationErrors()).as("validation errors").isZero();
    }
}
