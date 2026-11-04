package com.truesim.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.vulkan.GpuMemory;
import com.truesim.engine.vulkan.VulkanException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The stream benchmark's cells streamed in and out many times over keep memory on a plateau: every cell's CPU-side
 * content (its meshes' shared arenas and its texels) is freed after placing, and native memory outside the Java heap
 * and the GPU's stop growing once the range is full. Before the fix, each cell left about 220 KB of meshes' arenas
 * behind (6 GB over five minutes of a fast flight).
 */
class StreamBenchMemoryTest {

    @AfterEach
    void restore() {
        StreamBench.options(List.of());
        StreamBench.frameCount = 2700;
        StreamBench.paced = true;
        StreamBench.builderThreads = 3;
    }

    @Test
    void cellsStreamedInAndOutFreeTheirContentAndMemoryStaysFlat() throws Exception {
        StreamBench.options(List.of());
        StreamBench.speed = 270; // 3 m a frame: a column of 7 cells every 8 frames
        int frames = 1600; // about 1,400 cells in and out, each frame waiting for its cells' builds
        StreamBench.frameCount = frames;
        StreamBench.paced = false;
        StreamBench.builderThreads = Math.max(3, Runtime.getRuntime().availableProcessors() - 1);
        long[] outside = new long[frames]; // the process's committed memory outside the Java heap and the JVM's own
        long[] gpu = new long[frames];
        MemoryMXBean jvm = ManagementFactory.getMemoryMXBean();
        com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        int before = StreamBench.UNFREED.get();
        try {
            StreamBench.headless((f, cells, pool, textures, materials) -> {
                if (f % 100 == 0) {
                    outside[f] = os.getCommittedVirtualMemorySize() - jvm.getHeapMemoryUsage().getCommitted()
                        - jvm.getNonHeapMemoryUsage().getCommitted();
                    gpu[f] = GpuMemory.total();
                }
            });
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
        }
        long deadline = System.nanoTime() + 10_000_000_000L; // frees run on a thread of their own
        while (StreamBench.UNFREED.get() != before && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(StreamBench.UNFREED.get()).as("cells' content left unfreed").isEqualTo(before);
        // From a quarter of the way (the range long full) to the end: another 1,000 cells in and out, which left
        // about 220 MB behind before the fix.
        long settled = Math.max(outside[400], outside[500]);
        long last = Math.max(outside[frames - 100], outside[frames - 200]);
        System.out.printf("Outside the heap: %.0f MB at frame 400, %.0f MB at the end; GPU %.0f MB, %.0f MB%n",
            settled / 1e6, last / 1e6, gpu[400] / 1e6, gpu[frames - 100] / 1e6);
        assertThat((last - settled) / 1e6).as("MB grown outside the heap over 1,000 cells").isLessThan(60);
        assertThat(gpu[frames - 100]).as("GPU memory").isLessThanOrEqualTo(gpu[400] + (gpu[400] >> 3));
    }
}
