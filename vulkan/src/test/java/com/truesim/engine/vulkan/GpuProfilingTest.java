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
                long first = clock.deviation();
                clock.calibrate();
                // each calibration is within its stated deviation; the clocks' drift between them is far below 1 ms
                assertThat(Math.abs(clock.toCpu(0) - t)).as("drift between calibrations")
                    .isLessThan(1_000_000 + first + clock.deviation());
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
    void aDeviceWithoutTheProfilersCapabilitiesTakesTheFallbacks() {
        try (VulkanInstance instance = instance();
             VulkanDevice device = VulkanDevice.without(instance.bestGpu(), false, java.util.List.of(),
                 java.util.Set.of(VulkanDevice.MEMORY_BUDGET, VulkanDevice.CALIBRATED_TIMESTAMPS,
                     VulkanDevice.PIPELINE_STATISTICS));
             Counters counters = new Counters()) {
            assertThat(device.memoryBudget()).isFalse();
            assertThat(device.calibratedTimestamps()).isNull();
            assertThat(device.pipelineStatistics()).isFalse();
            GpuClock clock = new GpuClock(device);
            assertThat(clock.calibrated()).isFalse();
            assertThat(clock.why()).contains("no calibrated timestamps");
            counters.add(new GpuCounters(device, clock));
            counters.add(new GpuCounters(device, null));
            counters.sampleNow();
            assertThat(counters.unavailable()).anyMatch(u -> u.contains("VK_EXT_memory_budget"))
                .anyMatch(u -> u.contains("calibrated timestamps"));
        }
    }

    @Test
    void theClocksDecisionsHoldForEverySystemsDomains() {
        int device = org.lwjgl.vulkan.KHRCalibratedTimestamps.VK_TIME_DOMAIN_DEVICE_KHR;
        int monotonic = org.lwjgl.vulkan.KHRCalibratedTimestamps.VK_TIME_DOMAIN_CLOCK_MONOTONIC_KHR;
        int raw = org.lwjgl.vulkan.KHRCalibratedTimestamps.VK_TIME_DOMAIN_CLOCK_MONOTONIC_RAW_KHR;
        int qpc = org.lwjgl.vulkan.KHRCalibratedTimestamps.VK_TIME_DOMAIN_QUERY_PERFORMANCE_COUNTER_KHR;
        assertThat(GpuClock.hostDomain(new int[] {device, qpc}, true)).isEqualTo(qpc);
        assertThat(GpuClock.hostDomain(new int[] {device, monotonic, raw}, false)).isEqualTo(monotonic);
        assertThat(GpuClock.hostDomain(new int[] {device, raw}, false)).isEqualTo(-1);
        assertThat(GpuClock.hostDomain(new int[] {monotonic}, false)).as("without the device's").isEqualTo(-1);
        assertThat(GpuClock.hostDomain(new int[] {device, monotonic}, true)).isEqualTo(-1);
        assertThat(GpuClock.hostNanos(5_000, 0, 4_000, 6_000)).isEqualTo(5_000); // nanoseconds already
        assertThat(GpuClock.hostNanos(500, 10, 4_000, 6_000)).isEqualTo(5_000); // 100 MHz ticks
        assertThat(GpuClock.hostNanos(99_000_000, 0, 4_000, 6_000)).as("another clock: the bracket")
            .isEqualTo(5_000);
        assertThat(GpuClock.hostNanos(0, 0, 4_000_000, 6_000_000)).isEqualTo(5_000_000);
    }

    @Test
    void theProfilersExtensionsAreEnabledOnceWhenAskedForToo() {
        try (VulkanInstance instance = instance()) {
            Gpu gpu = instance.bestGpu();
            java.util.List<String> asked = new java.util.ArrayList<>();
            try (VulkanDevice plain = VulkanDevice.createHeadless(gpu)) {
                if (plain.memoryBudget()) {
                    asked.add(org.lwjgl.vulkan.EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME);
                }
                if (plain.calibratedTimestamps() != null) {
                    asked.add(plain.calibratedTimestamps());
                }
            }
            try (VulkanDevice device = VulkanDevice.without(gpu, false, asked, java.util.Set.of())) {
                assertThat(device.memoryBudget()).isEqualTo(asked.contains(
                    org.lwjgl.vulkan.EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME));
            }
        }
    }

    @Test
    void theClockTakesEachPathOfTheDomainsAndCallsItIsGiven() {
        int device = org.lwjgl.vulkan.KHRCalibratedTimestamps.VK_TIME_DOMAIN_DEVICE_KHR;
        int qpc = org.lwjgl.vulkan.KHRCalibratedTimestamps.VK_TIME_DOMAIN_QUERY_PERFORMANCE_COUNTER_KHR;
        int monotonic = org.lwjgl.vulkan.KHRCalibratedTimestamps.VK_TIME_DOMAIN_CLOCK_MONOTONIC_KHR;
        int[] result = {0};
        GpuClock.Source windows = new GpuClock.Source() {
            @Override
            public int[] domains() {
                return new int[] {device, qpc};
            }

            @Override
            public int calibrate(int hostDomain, long[] stamps, long[] max) {
                assertThat(hostDomain).isEqualTo(qpc);
                stamps[0] = 1000; // GPU ticks of 2 ns
                stamps[1] = Math.round(System.nanoTime() / 100.0); // 100 ns performance counter ticks
                max[0] = 50;
                return result[0];
            }
        };
        GpuClock clock = new GpuClock(2, windows, true, () -> 100);
        assertThat(clock.calibrated()).isTrue();
        assertThat(clock.why()).isEmpty();
        clock.observe(5, 10); // before a calibration, frames place it
        assertThat(clock.toCpu(5)).isEqualTo(10);
        result[0] = -1; // a failed call leaves it
        clock.calibrate();
        assertThat(clock.deviation()).isEqualTo(-1);
        result[0] = 0;
        long before = System.nanoTime();
        clock.calibrate();
        assertThat(clock.deviation()).isEqualTo(50);
        assertThat(clock.toCpu(1000)).isBetween(before - 1000, System.nanoTime() + 1000);
        long t = clock.toCpu(0);
        clock.observe(5, 10); // calibrated: frames do not move it
        assertThat(clock.toCpu(0)).isEqualTo(t);

        GpuClock noCounter = new GpuClock(2, windows, true, () -> 0);
        assertThat(noCounter.calibrated()).isFalse();
        assertThat(noCounter.why()).contains("no device and host time domain");
        GpuClock linux = new GpuClock(2, windows, false, () -> 100);
        assertThat(linux.calibrated()).as("no CLOCK_MONOTONIC").isFalse();
        GpuClock monotonicClock = new GpuClock(2, new GpuClock.Source() {
            @Override
            public int[] domains() {
                return new int[] {monotonic, device};
            }

            @Override
            public int calibrate(int hostDomain, long[] stamps, long[] max) {
                stamps[0] = 0;
                stamps[1] = System.nanoTime();
                max[0] = 1;
                return 0;
            }
        }, false, () -> {
            throw new AssertionError("the performance counter is Windows'");
        });
        assertThat(monotonicClock.calibrated()).isTrue();
        monotonicClock.calibrate();
        assertThat(monotonicClock.deviation()).isEqualTo(1);
        GpuClock none = new GpuClock(2, null, true, () -> 100);
        assertThat(none.calibrated()).isFalse();
        assertThat(none.why()).contains("no calibrated timestamps extension");
        none.calibrate();
        assertThat(none.ready()).isFalse();

        assertThat(GpuClock.perTick(1, 10_000_000)).isEqualTo(100);
        assertThat(GpuClock.perTick(0, 10_000_000)).isZero();
        assertThat(GpuClock.perTick(1, 0)).isZero();
    }

    @Test
    void theCountersReportTheCalibratedClockAndCalibrateIt() {
        try (VulkanInstance instance = instance();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             Counters counters = new Counters()) {
            long[] calls = {0};
            GpuClock clock = new GpuClock(1, new GpuClock.Source() {
                @Override
                public int[] domains() {
                    return new int[] {org.lwjgl.vulkan.KHRCalibratedTimestamps.VK_TIME_DOMAIN_DEVICE_KHR,
                        org.lwjgl.vulkan.KHRCalibratedTimestamps.VK_TIME_DOMAIN_CLOCK_MONOTONIC_KHR};
                }

                @Override
                public int calibrate(int hostDomain, long[] stamps, long[] max) {
                    calls[0]++;
                    stamps[1] = System.nanoTime();
                    return 0;
                }
            }, false, () -> 0);
            GpuCounters gpu = new GpuCounters(device, clock);
            gpu.calibrateEvery(3600);
            counters.add(gpu);
            counters.sampleNow();
            counters.sampleNow(); // within the interval: once, however young nanoTime is (an hour on CI's VM)
            assertThat(calls[0]).isEqualTo(1);
            gpu.calibrateEvery(0);
            counters.sampleNow();
            assertThat(calls[0]).isEqualTo(2);
            assertThat(counters.unavailable()).noneMatch(u -> u.contains("calibrated timestamps"));
        }
    }

    @Test
    void memoryCategoriesFollowScopesThenUsages() {
        int as = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
        assertThat(GpuMemory.buffer(as, false)).isEqualTo(GpuMemory.Category.ACCELERATION);
        assertThat(GpuMemory.buffer(VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true)).isEqualTo(GpuMemory.Category.STAGING);
        assertThat(GpuMemory.buffer(VK_BUFFER_USAGE_TRANSFER_SRC_BIT, false)).isEqualTo(GpuMemory.Category.BUFFERS);
        assertThat(GpuMemory.image(VK_IMAGE_USAGE_SAMPLED_BIT)).isEqualTo(GpuMemory.Category.TEXTURES);
        assertThat(GpuMemory.image(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)).isEqualTo(GpuMemory.Category.RENDER_TARGETS);
        GpuMemory.Category before = GpuMemory.enter(GpuMemory.Category.TRANSIENTS);
        try {
            assertThat(GpuMemory.buffer(as, true)).isEqualTo(GpuMemory.Category.TRANSIENTS);
            assertThat(GpuMemory.image(VK_IMAGE_USAGE_SAMPLED_BIT)).isEqualTo(GpuMemory.Category.TRANSIENTS);
        } finally {
            GpuMemory.exit(before);
        }
        assertThat(GpuMemory.buffer(0, false)).isEqualTo(GpuMemory.Category.BUFFERS);
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
