package com.truesim.engine.vulkan;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

/** Frame buffers: a copy per frame in flight, which only the open frame's writes reach. */
class FrameBufferTest {

    private static final int USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED;
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED;

    private static VulkanInstance instance() {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        return instance;
    }

    private static float copy(FrameBuffer buffer, int slot, long offset) {
        return buffer.copy(slot).memory().get(F32, offset);
    }

    @Test
    void writesReachOnlyTheOpenFrame() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameBuffer buffer = FrameBuffer.create(device, 64, USAGE, new FrameSlots(3))) {
            FrameSlots slots = buffer.slots();
            assertThat(slots.count()).isEqualTo(3);
            assertThat(slots.current()).isEqualTo(-1);
            assertThat(slots.last()).isEqualTo(-1);
            assertThat(buffer.size()).isEqualTo(64);
            // Before any frame: only the master; every copy catches up when its slot opens.
            buffer.putFloat(0, 1);
            assertThat(buffer.getFloat(0)).isEqualTo(1);
            for (int s = 0; s < 3; s++) {
                assertThat(copy(buffer, s, 0)).isZero();
            }
            slots.begin(0);
            assertThat(slots.current()).isZero();
            assertThat(copy(buffer, 0, 0)).isEqualTo(1);
            assertThat(copy(buffer, 1, 0)).isZero();
            // While frame 0 records, its writes reach its copy at once.
            buffer.putFloat(4, 2);
            assertThat(copy(buffer, 0, 4)).isEqualTo(2);
            assertThat(buffer.deviceAddress()).isEqualTo(buffer.copy(0).deviceAddress());
            assertThat(buffer.buffer()).isSameAs(buffer.copy(0));
            slots.end();
            assertThat(slots.current()).isEqualTo(-1);
            assertThat(slots.last()).isZero();
            // Frame 0 is in flight: writing frame 1's data leaves its copy alone.
            buffer.putFloat(0, 3);
            buffer.putInt(8, 7);
            buffer.putLong(16, 1L << 40);
            assertThat(copy(buffer, 0, 0)).isEqualTo(1);
            assertThat(buffer.deviceAddress()).isEqualTo(buffer.copy(0).deviceAddress()); // the last one opened
            slots.begin(1);
            assertThat(copy(buffer, 1, 0)).isEqualTo(3);
            assertThat(copy(buffer, 1, 4)).isEqualTo(2); // written while frame 0 recorded: missed by the others
            assertThat(buffer.copy(1).memory().get(U32, 8)).isEqualTo(7);
            assertThat(buffer.copy(1).memory().get(ValueLayout.JAVA_LONG_UNALIGNED, 16)).isEqualTo(1L << 40);
            assertThat(copy(buffer, 0, 0)).isEqualTo(1);
            assertThat(buffer.getInt(8)).isEqualTo(7);
            assertThat(buffer.getLong(16)).isEqualTo(1L << 40);
            slots.end();
            slots.begin(2);
            assertThat(copy(buffer, 2, 0)).isEqualTo(3);
            assertThat(copy(buffer, 2, 4)).isEqualTo(2);
            slots.end();
            // Slot 0 again, with everything it missed.
            slots.begin(0);
            assertThat(copy(buffer, 0, 0)).isEqualTo(3);
            assertThat(buffer.copy(0).memory().get(U32, 8)).isEqualTo(7);
            slots.end();
            assertThatIllegalArgumentException().isThrownBy(() -> slots.begin(3));
            assertThatIllegalArgumentException().isThrownBy(() -> new FrameSlots(0));
            assertThatIllegalArgumentException().isThrownBy(() -> FrameBuffer.create(device, 0, USAGE, slots));
        }
    }

    @Test
    void blockCopies() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameBuffer buffer = FrameBuffer.create(device, 64, USAGE, new FrameSlots(2));
             Arena arena = Arena.ofConfined()) {
            MemorySegment source = arena.allocate(16);
            for (int i = 0; i < 4; i++) {
                source.set(F32, 4L * i, i + 1);
            }
            buffer.slots().begin(0);
            buffer.put(32, source, 4, 12);
            assertThat(copy(buffer, 0, 32)).isEqualTo(2);
            assertThat(copy(buffer, 0, 40)).isEqualTo(4);
            buffer.slots().end();
            buffer.put(0, source, 0, 4);
            buffer.slots().begin(1);
            assertThat(copy(buffer, 1, 0)).isEqualTo(1);
            assertThat(copy(buffer, 1, 36)).isEqualTo(3);
        }
    }

    @Test
    void singleSlotWritesStraightThrough() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameBuffer buffer = FrameBuffer.create(device, 16, USAGE, FrameSlots.single())) {
            FrameSlots slots = buffer.slots();
            assertThat(slots.current()).isZero();
            buffer.putFloat(0, 5);
            buffer.putInt(4, 6);
            buffer.putLong(8, 9);
            assertThat(copy(buffer, 0, 0)).isEqualTo(5);
            assertThat(buffer.copy(0).memory().get(U32, 4)).isEqualTo(6);
            // Opening and closing changes nothing: it stays open.
            slots.end();
            slots.begin(0);
            assertThat(slots.current()).isZero();
            assertThat(buffer.getLong(8)).isEqualTo(9);
        }
    }

    @Test
    void framesAllocateNothing() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameBuffer buffer = FrameBuffer.create(device, 4096, USAGE, new FrameSlots(2))) {
            FrameSlots slots = buffer.slots();
            int[] frame = {0};
            assertAllocationFree(() -> {
                slots.begin(frame[0] % 2);
                for (int i = 0; i < 64; i++) {
                    buffer.putFloat(16L * i, frame[0] + i);
                }
                slots.end();
                buffer.putInt(1024, frame[0]++);
            });
        }
    }
}
