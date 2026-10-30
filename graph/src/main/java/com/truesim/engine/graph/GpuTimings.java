package com.truesim.engine.graph;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memGetLong;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_64_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_TYPE_TIMESTAMP;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCreateQueryPool;
import static org.lwjgl.vulkan.VK10.vkDestroyQueryPool;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Vulkan;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.Arrays;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

/**
 * How long each pass of the last finished frame took on the GPU, from timestamps written before its barriers and after
 * its commands: {@link FrameGraph#timings(boolean)} turns them on, {@link FrameGraph#timings()} gives them. Read once
 * the GPU finished that frame, a frame or more after it was recorded; reading allocates nothing. A pass on a queue
 * without timestamps, or of a frame not finished yet, has none ({@code NaN}).
 *
 * <pre>{@code
 * graph.timings(true);
 * GpuTimings t = graph.timings();                         // each frame, after beginFrame
 * for (int i = 0; i < t.count(); i++) {
 *     overlay.line(t.pass(i), t.millis(i));
 * }
 * }</pre>
 */
public final class GpuTimings implements AutoCloseable {

    private static final int RESULT_BYTES = 16; // the timestamp, then its availability

    private final VulkanDevice device;
    private final int slots;
    private final double nanosPerTick;
    private final long[] validMask; // per queue family: the bits its timestamps have
    private long pool;
    private int passes;
    private String[] names = new String[0];
    private int[] family = new int[0]; // per pass: the queue family it runs on
    private double[] millis = new double[0];
    private long results; // native, RESULT_BYTES per query of one slot
    private long frame = -1;

    @AllowAllocation("set up once")
    GpuTimings(VulkanDevice device, int slots) {
        this.device = device;
        this.slots = slots;
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(device.gpu().handle(), properties);
            this.nanosPerTick = properties.limits().timestampPeriod();
            var count = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(device.gpu().handle(), count, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device.gpu().handle(), count, families);
            this.validMask = new long[count.get(0)];
            for (int f = 0; f < validMask.length; f++) {
                int bits = families.get(f).timestampValidBits();
                validMask[f] = bits >= 64 ? -1L : (1L << bits) - 1;
            }
        }
    }

    /**
     * Makes queries for {@code order}'s passes, two per pass and frame slot; {@code families[i]}: the queue family pass
     * {@code i} runs on. The GPU must not use the old queries any more.
     */
    @AllowAllocation("building the graph, when it changes")
    void build(GraphBuilder.PassInfo[] order, int[] families) {
        destroy();
        passes = order.length;
        names = new String[passes];
        for (int i = 0; i < passes; i++) {
            names[i] = order[i].name;
        }
        family = families.clone();
        millis = new double[passes];
        Arrays.fill(millis, Double.NaN);
        frame = -1;
        if (passes == 0) {
            return;
        }
        int queries = 2 * passes * slots;
        try (MemoryStack stack = stackPush()) {
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                .queryType(VK_QUERY_TYPE_TIMESTAMP)
                .queryCount(queries);
            var handle = stack.mallocLong(1);
            Vulkan.check(vkCreateQueryPool(device.handle(), info, null, handle), "Could not create a query pool");
            pool = handle.get(0);
        }
        Commands.resetQueryPool(device.handle(), pool, 0, queries);
        results = MemoryUtil.nmemCalloc(2L * passes, RESULT_BYTES);
    }

    /** Writes pass {@code pass}'s start (or with {@code end}, its end) timestamp of frame slot {@code slot}. */
    void write(VkCommandBuffer commands, int slot, int pass, boolean end) {
        if (pool != 0 && validMask[family[pass]] != 0) {
            Commands.writeTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, pool,
                2 * (slot * passes + pass) + (end ? 1 : 0));
        }
    }

    /**
     * Reads frame slot {@code slot}'s timestamps, of frame {@code frame}, which the GPU finished, and resets them for the
     * slot's next frame. Allocates nothing.
     */
    void collect(int slot, long frame) {
        if (pool == 0) {
            return;
        }
        int first = 2 * slot * passes;
        int result = Commands.getQueryPoolResults(device.handle(), pool, first, 2 * passes,
            2L * passes * RESULT_BYTES, results, RESULT_BYTES, VK_QUERY_RESULT_64_BIT
                | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
        if (result < 0) {
            Vulkan.check(result, "Could not read the timestamps");
        }
        boolean any = false;
        for (int i = 0; i < passes; i++) {
            long start = results + 2L * i * RESULT_BYTES;
            long end = start + RESULT_BYTES;
            if (memGetLong(start + 8) != 0 && memGetLong(end + 8) != 0) {
                long ticks = (memGetLong(end) - memGetLong(start)) & validMask[family[i]];
                millis[i] = ticks * nanosPerTick / 1e6;
                any = true;
            } else {
                millis[i] = Double.NaN;
            }
        }
        if (any) {
            this.frame = frame;
        }
        Commands.resetQueryPool(device.handle(), pool, first, 2 * passes);
    }

    /** The passes timed: the graph's, in the order they record. */
    public int count() {
        return passes;
    }

    /** Pass {@code i}'s name. */
    public String pass(int i) {
        return names[i];
    }

    /** How long pass {@code i} took, in milliseconds, its barriers included; {@code NaN} if it has no timestamps. */
    public double millis(int i) {
        return millis[i];
    }

    /** All the timed passes together, in milliseconds. */
    public double totalMillis() {
        double total = 0;
        for (double m : millis) {
            if (!Double.isNaN(m)) {
                total += m;
            }
        }
        return total;
    }

    /** The frame the timings are of (counted by the graph from 1), or -1 before the first finished. */
    public long frame() {
        return frame;
    }

    private void destroy() {
        if (pool != 0) {
            vkDestroyQueryPool(device.handle(), pool, null);
            pool = 0;
        }
        if (results != 0) {
            MemoryUtil.nmemFree(results);
            results = 0;
        }
    }

    @Override
    public void close() {
        destroy();
    }
}
