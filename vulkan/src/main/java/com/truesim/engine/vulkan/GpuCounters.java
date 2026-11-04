package com.truesim.engine.vulkan;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.app.profile.Counters;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

/**
 * A device's memory as the profiler's cheap counters, sampled on the counters thread: each heap's usage and budget
 * ({@code gpu.heap.<n>.usage}, {@code .budget}, {@code .local} 1 for device-local heaps; from
 * {@code VK_EXT_memory_budget} through VMA where the GPU has it, VMA's own estimate otherwise), the engine's own
 * accounting by category ({@code gpu.memory.textures}, {@code meshes}, ... from {@link GpuMemory}), and it calibrates
 * the device's {@link GpuClock} every {@link #calibrateEvery}.
 */
@AllowAllocation("runs on the counters thread, off the frame")
public final class GpuCounters implements Counters.Source {

    private final VulkanDevice device;
    private final GpuClock clock;
    private final int heaps;
    private final boolean[] local;
    private final VmaBudget.Buffer budgets;
    private int[] usage;
    private int[] budget;
    private int[] categories;
    private int total;
    private volatile long calibrateNanos = 2_000_000_000L;
    private long calibratedAt;

    /** The counters of {@code device}, calibrating {@code clock} (null: none). */
    public GpuCounters(VulkanDevice device, GpuClock clock) {
        this.device = device;
        this.clock = clock;
        VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc();
        VK10.vkGetPhysicalDeviceMemoryProperties(device.gpu().handle(), memory);
        heaps = memory.memoryHeapCount();
        local = new boolean[heaps];
        for (int h = 0; h < heaps; h++) {
            local[h] = (memory.memoryHeaps(h).flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0;
        }
        memory.free();
        budgets = VmaBudget.calloc(VK10.VK_MAX_MEMORY_HEAPS);
    }

    /** Calibrates the clock every {@code seconds}. */
    public void calibrateEvery(double seconds) {
        calibrateNanos = (long) (seconds * 1e9);
    }

    @Override
    public void declare(Counters into) {
        usage = new int[heaps];
        budget = new int[heaps];
        for (int h = 0; h < heaps; h++) {
            String kind = local[h] ? "local" : "host";
            usage[h] = into.counter("gpu.heap." + h + "." + kind + ".usage", "bytes");
            budget[h] = into.counter("gpu.heap." + h + "." + kind + ".budget", "bytes");
        }
        categories = new int[GpuMemory.Category.count()];
        for (int c = 0; c < categories.length; c++) {
            categories[c] = into.counter("gpu.memory." + GpuMemory.Category.of(c).name().toLowerCase(
                java.util.Locale.ROOT), "bytes");
        }
        total = into.counter("gpu.memory.total", "bytes");
        if (!device.memoryBudget()) {
            into.unavailable("VK_EXT_memory_budget: missing, heap budgets are VMA's estimates");
        }
        if (clock != null && !clock.calibrated()) {
            into.unavailable("calibrated timestamps: " + clock.why());
        }
    }

    @Override
    public void sample(Counters into) {
        Vma.vmaGetHeapBudgets(device.allocator(), budgets);
        for (int h = 0; h < heaps; h++) {
            VmaBudget b = budgets.get(h);
            into.set(usage[h], b.usage());
            into.set(budget[h], b.budget());
        }
        for (int c = 0; c < categories.length; c++) {
            into.set(categories[c], GpuMemory.bytes(GpuMemory.Category.of(c)));
        }
        into.set(total, GpuMemory.total());
        long now = System.nanoTime();
        if (clock != null && now - calibratedAt >= calibrateNanos) {
            calibratedAt = now;
            clock.calibrate();
        }
    }

    @Override
    public void close() {
        budgets.free();
    }
}
