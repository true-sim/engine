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
import org.lwjgl.vulkan.VK10;
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
    /** How many pipeline statistics each pass counts ({@link #statistic}). */
    public static final int STATISTICS = 7;
    private static final int STAT_BYTES = 8 * (STATISTICS + 1); // the values, then the availability
    private static final int STATISTIC_BITS = VK10.VK_QUERY_PIPELINE_STATISTIC_INPUT_ASSEMBLY_VERTICES_BIT
        | VK10.VK_QUERY_PIPELINE_STATISTIC_INPUT_ASSEMBLY_PRIMITIVES_BIT
        | VK10.VK_QUERY_PIPELINE_STATISTIC_VERTEX_SHADER_INVOCATIONS_BIT
        | VK10.VK_QUERY_PIPELINE_STATISTIC_CLIPPING_INVOCATIONS_BIT
        | VK10.VK_QUERY_PIPELINE_STATISTIC_CLIPPING_PRIMITIVES_BIT
        | VK10.VK_QUERY_PIPELINE_STATISTIC_FRAGMENT_SHADER_INVOCATIONS_BIT
        | VK10.VK_QUERY_PIPELINE_STATISTIC_COMPUTE_SHADER_INVOCATIONS_BIT;
    /** The names of the statistics, in the order of {@link #statistic}. */
    public static final java.util.List<String> STATISTIC_NAMES = java.util.List.of("input vertices",
        "input primitives", "vertex shader invocations", "clipping invocations", "clipped primitives",
        "fragment shader invocations", "compute shader invocations");

    private final VulkanDevice device;
    private final int slots;
    private final double nanosPerTick;
    private final long[] validMask; // per queue family: the bits its timestamps have
    private long pool;
    private int passes;
    private String[] names = new String[0];
    private int[] family = new int[0]; // per pass: the queue family it runs on
    private double[] millis = new double[0];
    private long[] startTicks = new long[0]; // per pass, the raw timestamps of the last finished frame
    private long[] endTicks = new long[0];
    private long frameStartTicks;
    // pipeline statistics per pass (graphics queue passes), when on and the device has them
    private boolean statisticsWanted;
    private long statisticsPool;
    private long statisticsResults; // STAT_BYTES per pass of one slot
    private final long[] statisticsTotals = new long[STATISTICS];
    private boolean statisticsValid;
    private long frameStartEndTicks;
    private long results; // native, RESULT_BYTES per query of one slot
    private long frame = -1;
    private double span = Double.NaN; // the first timed pass's start to the last one's end
    private int startFamily; // the frame start's queue family: the graphics queue's
    private double start = Double.NaN; // the frame start's commands (uploads), timed after the passes' queries

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
    void build(GraphBuilder.PassInfo[] order, int[] families, int graphicsFamily) {
        destroy();
        startFamily = graphicsFamily;
        start = Double.NaN;
        passes = order.length;
        names = new String[passes];
        for (int i = 0; i < passes; i++) {
            names[i] = order[i].name;
        }
        family = families.clone();
        millis = new double[passes];
        Arrays.fill(millis, Double.NaN);
        startTicks = new long[passes];
        endTicks = new long[passes];
        frame = -1;
        span = Double.NaN;
        if (passes == 0) {
            return;
        }
        int queries = 2 * (passes + 1) * slots;
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
        results = MemoryUtil.nmemCalloc(2L * (passes + 1), RESULT_BYTES);
        if (statisticsWanted && device.pipelineStatistics()) {
            try (MemoryStack stack = stackPush()) {
                VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                    .queryType(VK10.VK_QUERY_TYPE_PIPELINE_STATISTICS)
                    .queryCount(passes * slots)
                    .pipelineStatistics(STATISTIC_BITS);
                var handle = stack.mallocLong(1);
                Vulkan.check(vkCreateQueryPool(device.handle(), info, null, handle), "Could not create a query pool");
                statisticsPool = handle.get(0);
            }
            Commands.resetQueryPool(device.handle(), statisticsPool, 0, passes * slots);
            statisticsResults = MemoryUtil.nmemCalloc(passes, STAT_BYTES);
        }
    }

    /** Writes pass {@code pass}'s start (or with {@code end}, its end) timestamp of frame slot {@code slot}. */
    void write(VkCommandBuffer commands, int slot, int pass, boolean end) {
        if (pool != 0 && validMask[family[pass]] != 0) {
            Commands.writeTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, pool,
                2 * (slot * passes + pass) + (end ? 1 : 0));
        }
    }

    /** Counts pass {@code pass}'s pipeline statistics from here ({@code end} false) or stops counting them. */
    void statistics(VkCommandBuffer commands, int slot, int pass, boolean end) {
        if (statisticsPool != 0 && family[pass] == startFamily) {
            if (end) {
                Commands.endQuery(commands, statisticsPool, slot * passes + pass);
            } else {
                Commands.beginQuery(commands, statisticsPool, slot * passes + pass, 0);
            }
        }
    }

    /** Asks for pipeline statistics from the next build (the device must have them; {@link #statisticsOn}). */
    void statisticsWanted(boolean on) {
        statisticsWanted = on;
    }

    /** Whether passes count pipeline statistics now. */
    public boolean statisticsOn() {
        return statisticsPool != 0;
    }

    /**
     * Statistic {@code k} ({@link #STATISTIC_NAMES}) of the last finished frame, all graphics queue passes together,
     * or -1 while none came.
     */
    public long statistic(int k) {
        return statisticsValid ? statisticsTotals[k] : -1;
    }

    /** Writes the frame start's start (or with {@code end}, its end) timestamp of frame slot {@code slot}. */
    void writeStart(VkCommandBuffer commands, int slot, boolean end) {
        if (pool != 0 && validMask[startFamily] != 0) {
            Commands.writeTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, pool,
                2 * (slots * passes + slot) + (end ? 1 : 0));
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
        long earliest = 0;
        long latest = 0;
        long mask = -1;
        for (int i = 0; i < passes; i++) {
            long start = results + 2L * i * RESULT_BYTES;
            long end = start + RESULT_BYTES;
            if (memGetLong(start + 8) != 0 && memGetLong(end + 8) != 0) {
                long ticks = (memGetLong(end) - memGetLong(start)) & validMask[family[i]];
                millis[i] = ticks * nanosPerTick / 1e6;
                startTicks[i] = memGetLong(start);
                endTicks[i] = memGetLong(end);
                if (!any) {
                    earliest = memGetLong(start);
                    latest = memGetLong(end);
                    mask = validMask[family[i]];
                } else {
                    earliest = memGetLong(start) - earliest < 0 ? memGetLong(start) : earliest;
                    latest = memGetLong(end) - latest > 0 ? memGetLong(end) : latest;
                }
                any = true;
            } else {
                millis[i] = Double.NaN;
            }
        }
        if (any) {
            this.frame = frame;
            span = ((latest - earliest) & mask) * nanosPerTick / 1e6;
        }
        Commands.resetQueryPool(device.handle(), pool, first, 2 * passes);
        int startQuery = 2 * (slots * passes + slot);
        result = Commands.getQueryPoolResults(device.handle(), pool, startQuery, 2, 2L * RESULT_BYTES, results,
            RESULT_BYTES, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
        if (result < 0) {
            Vulkan.check(result, "Could not read the timestamps");
        }
        if (memGetLong(results + 8) != 0 && memGetLong(results + RESULT_BYTES + 8) != 0) {
            start = ((memGetLong(results + RESULT_BYTES) - memGetLong(results)) & validMask[startFamily])
                * nanosPerTick / 1e6;
            frameStartTicks = memGetLong(results);
            frameStartEndTicks = memGetLong(results + RESULT_BYTES);
        } else {
            start = Double.NaN;
        }
        Commands.resetQueryPool(device.handle(), pool, startQuery, 2);
        if (statisticsPool != 0) {
            result = Commands.getQueryPoolResults(device.handle(), statisticsPool, slot * passes, passes,
                (long) passes * STAT_BYTES, statisticsResults, STAT_BYTES, VK_QUERY_RESULT_64_BIT
                    | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
            if (result >= 0) {
                java.util.Arrays.fill(statisticsTotals, 0);
                boolean anyStat = false;
                for (int i = 0; i < passes; i++) {
                    long base = statisticsResults + (long) i * STAT_BYTES;
                    if (family[i] == startFamily && memGetLong(base + 8L * STATISTICS) != 0) {
                        for (int k = 0; k < STATISTICS; k++) {
                            statisticsTotals[k] += memGetLong(base + 8L * k);
                        }
                        anyStat = true;
                    }
                }
                statisticsValid = anyStat;
            }
            Commands.resetQueryPool(device.handle(), statisticsPool, slot * passes, passes);
        }
    }

    /**
     * How long the frame start took on the graphics queue, in milliseconds: the commands recorded ahead of every pass
     * (uploads with commands of their own, such as textures and their mips; {@code FrameSlots.recordStart}), not
     * counted in {@link #totalMillis}; {@code NaN} if the last finished frame had none timed.
     */
    public double frameStartMillis() {
        return start;
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

    /**
     * The frame's GPU time, in milliseconds: from the first timed pass's start to the last one's end, the gaps between
     * passes and between submits included (where {@link #totalMillis} sums the passes alone); {@code NaN} before the
     * first finished frame.
     */
    public double spanMillis() {
        return span;
    }

    /** Pass {@code i}'s start timestamp (raw ticks of its queue's clock; valid where {@link #millis} is not NaN). */
    public long startTicks(int i) {
        return startTicks[i];
    }

    /** Pass {@code i}'s end timestamp (raw ticks). */
    public long endTicks(int i) {
        return endTicks[i];
    }

    /** The queue family pass {@code i} runs on. */
    public int family(int i) {
        return family[i];
    }

    /** The graphics queue's family (the frame start's). */
    public int graphicsFamily() {
        return startFamily;
    }

    /** The frame start's first timestamp (raw ticks; valid where {@link #frameStartMillis} is not NaN). */
    public long frameStartTicks() {
        return frameStartTicks;
    }

    /** The frame start's last timestamp (raw ticks). */
    public long frameStartEndTicks() {
        return frameStartEndTicks;
    }

    /** Nanoseconds per timestamp tick. */
    public double nanosPerTick() {
        return nanosPerTick;
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
        if (statisticsPool != 0) {
            vkDestroyQueryPool(device.handle(), statisticsPool, null);
            statisticsPool = 0;
            MemoryUtil.nmemFree(statisticsResults);
            statisticsResults = 0;
            statisticsValid = false;
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
