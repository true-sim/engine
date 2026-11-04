package com.truesim.engine.vulkan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;

import com.truesim.engine.app.profile.Counters;
import org.junit.jupiter.api.Test;

/** The profiler's GPU side: the GPU clock on the CPU's, the memory counters, the engine's accounting by category. */
class GpuProfilingTest {

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

    @Test
    void theClockCalibratesWhereItCanAndIsBoundedBySubmitsElsewhere() {
        try (VulkanInstance instance = instance();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            GpuClock clock = new GpuClock(device);
            System.out.println("calibrated timestamps: " + device.calibratedTimestamps() + ", calibrated "
                + clock.calibrated() + (clock.why().isEmpty() ? "" : " (" + clock.why() + ")")
                + ", memory budget " + device.memoryBudget() + ", pipeline statistics "
                + device.pipelineStatistics() + ", performance query " + device.performanceQuery());
            assertThat(clock.nanosPerTick()).isPositive();
            if (clock.calibrated()) {
                assertThat(clock.ready()).isFalse();
                clock.observe(5, 10); // before a calibration, frames place it
                assertThat(clock.ready()).isTrue();
                clock.calibrate();
                assertThat(clock.ready()).isTrue();
                assertThat(clock.deviation()).isNotNegative();
                clock.observe(5, 10); // calibrated: frames do not move it
                long t = clock.toCpu(0);
                clock.calibrate();
                assertThat(Math.abs(clock.toCpu(0) - t)).as("drift between calibrations").isLessThan(1_000_000);
            }
            GpuClock bounded = new GpuClock(device, false);
            assertThat(bounded.calibrated()).isFalse();
            assertThat(bounded.why()).isNotEmpty();
            bounded.calibrate(); // nothing to calibrate with
            assertThat(bounded.ready()).isFalse();
            double tick = bounded.nanosPerTick();
            bounded.window(1_000_000_000L);
            bounded.observe(1000, 5_000_000);
            assertThat(bounded.ready()).isTrue();
            assertThat(bounded.toCpu(1000)).isEqualTo(5_000_000);
            long later = 5_000_000 + Math.round(2000 * tick) - Math.round(1000 * tick) + 300;
            bounded.observe(2000, later); // started 300 ns after its submit: a later bound
            assertThat(bounded.toCpu(2000)).isEqualTo(later);
            bounded.observe(3000, 5_000_000); // an earlier bound: the later one stands within the window
            assertThat(bounded.toCpu(2000)).isEqualTo(later);
            bounded.observe(4000, 3_000_000_000L); // a new window
            assertThat(bounded.ready()).isTrue();
        }
    }

    @Test
    void memoryIsCountedByCategoryAndHeapsHaveBudgets() {
        try (VulkanInstance instance = instance();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             Counters counters = new Counters()) {
            GpuClock clock = new GpuClock(device);
            GpuCounters gpu = new GpuCounters(device, clock);
            gpu.calibrateEvery(0);
            counters.add(gpu);
            long staging = GpuMemory.bytes(GpuMemory.Category.STAGING);
            long meshes = GpuMemory.bytes(GpuMemory.Category.MESHES);
            long textures = GpuMemory.bytes(GpuMemory.Category.TEXTURES);
            long targets = GpuMemory.bytes(GpuMemory.Category.RENDER_TARGETS);
            long buffers = GpuMemory.count(GpuMemory.Category.BUFFERS);
            try (GpuBuffer up = GpuBuffer.hostVisible(device, 1 << 16, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
                 GpuBuffer plain = GpuBuffer.deviceLocal(device, 1 << 16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                 GpuImage texture = GpuImage.mipmapped(device, 64, 64, VK_FORMAT_R8G8B8A8_UNORM,
                     VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT, 1);
                 GpuImage target = GpuImage.mipmapped(device, 64, 64, VK_FORMAT_R8G8B8A8_UNORM,
                     VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_IMAGE_ASPECT_COLOR_BIT, 1)) {
                GpuBuffer mesh;
                GpuMemory.Category before = GpuMemory.enter(GpuMemory.Category.MESHES);
                try {
                    mesh = GpuBuffer.deviceLocal(device, 1 << 16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                } finally {
                    GpuMemory.exit(before);
                }
                assertThat(GpuMemory.bytes(GpuMemory.Category.STAGING)).isGreaterThanOrEqualTo(staging + (1 << 16));
                assertThat(GpuMemory.bytes(GpuMemory.Category.MESHES)).isGreaterThanOrEqualTo(meshes + (1 << 16));
                assertThat(GpuMemory.bytes(GpuMemory.Category.TEXTURES)).isGreaterThan(textures);
                assertThat(GpuMemory.bytes(GpuMemory.Category.RENDER_TARGETS)).isGreaterThan(targets);
                assertThat(GpuMemory.count(GpuMemory.Category.BUFFERS)).isGreaterThan(buffers);
                assertThat(GpuMemory.total()).isPositive();
                counters.sampleNow();
                double[] values = new double[counters.count()];
                counters.read(values);
                int heaps = 0;
                for (int i = 0; i < counters.count(); i++) {
                    if (counters.name(i).endsWith(".usage")) {
                        heaps++;
                        assertThat(values[i]).isNotNegative();
                    }
                    if (counters.name(i).equals("gpu.memory.meshes")) {
                        assertThat(values[i]).isGreaterThanOrEqualTo(1 << 16);
                    }
                }
                assertThat(heaps).isPositive();
                mesh.close();
            }
            assertThat(GpuMemory.bytes(GpuMemory.Category.MESHES)).isEqualTo(meshes);
            assertThat(GpuMemory.bytes(GpuMemory.Category.STAGING)).isEqualTo(staging);
            assertThat(GpuMemory.Category.of(0)).isEqualTo(GpuMemory.Category.TEXTURES);
        }
    }
}
