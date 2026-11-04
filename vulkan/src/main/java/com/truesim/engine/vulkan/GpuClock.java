package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;

import com.truesim.annotations.AllowAllocation;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Locale;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTCalibratedTimestamps;
import org.lwjgl.vulkan.KHRCalibratedTimestamps;
import org.lwjgl.vulkan.VkCalibratedTimestampInfoKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/**
 * The GPU's timestamp clock on the CPU's ({@link System#nanoTime()}), for putting GPU passes on the same timeline as
 * CPU zones. With {@code VK_KHR_calibrated_timestamps} (or the EXT one) {@link #calibrate()} reads the device's
 * timestamp and a host clock at once (the host clock: {@code CLOCK_MONOTONIC}, which {@code nanoTime} reads on Linux,
 * or Windows' performance counter, which it scales), so a GPU tick maps to the CPU's nanosecond within the driver's
 * stated deviation; calibrated again now and then, the drift between the clocks stays out. Without the extension
 * ({@link #calibrated()} false) the offset is estimated from frames ({@link #observe}): a frame's first GPU timestamp is
 * never earlier than its submit, so the latest bound over a window, a frame the GPU started at once, places the GPU
 * at most that frame's start delay late.
 *
 * <p>{@link #calibrate} allocates (LWJGL's call); run it off the frame's thread (the profiler's counters thread does).
 * {@link #toCpu} and {@link #observe} allocate nothing.
 */
@SuppressWarnings("restricted") // QueryPerformanceFrequency, through FFM
public final class GpuClock {

    private final VulkanDevice device;
    private final double nanosPerTick;
    private final String extension;
    private final int hostDomain; // a VK_TIME_DOMAIN_* the GPU calibrates against, or -1
    private final double qpcNanos; // nanoseconds per performance counter tick (Windows), else 0
    private volatile long offset = Long.MIN_VALUE; // CPU nanos = round(ticks * nanosPerTick) + offset
    private volatile long deviation = -1; // the driver's maximum deviation of the last calibration, ns
    private long windowBound = Long.MIN_VALUE; // the fallback's: the latest bound of this window
    private long windowStart;
    private long windowNanos = 2_000_000_000L;
    private String why = "";

    /** The clock of {@code device}'s timestamps. */
    @AllowAllocation("made once")
    public GpuClock(VulkanDevice device) {
        this(device, true);
    }

    /** As {@link #GpuClock(VulkanDevice)}; {@code calibrate} false estimates from frames even with the extension. */
    @AllowAllocation("made once")
    GpuClock(VulkanDevice device, boolean calibrate) {
        this.device = device;
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties p = VkPhysicalDeviceProperties.calloc(stack);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties(device.gpu().handle(), p);
            nanosPerTick = p.limits().timestampPeriod();
        }
        extension = calibrate ? device.calibratedTimestamps() : null;
        int domain = -1;
        double qpc = 0;
        if (extension != null) {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
            boolean khr = extension.equals(KHRCalibratedTimestamps.VK_KHR_CALIBRATED_TIMESTAMPS_EXTENSION_NAME);
            try (MemoryStack stack = stackPush()) {
                IntBuffer count = stack.mallocInt(1);
                if (khr) {
                    KHRCalibratedTimestamps.vkGetPhysicalDeviceCalibrateableTimeDomainsKHR(device.gpu().handle(),
                        count, null);
                } else {
                    EXTCalibratedTimestamps.vkGetPhysicalDeviceCalibrateableTimeDomainsEXT(device.gpu().handle(),
                        count, null);
                }
                IntBuffer domains = stack.mallocInt(count.get(0));
                if (khr) {
                    KHRCalibratedTimestamps.vkGetPhysicalDeviceCalibrateableTimeDomainsKHR(device.gpu().handle(),
                        count, domains);
                } else {
                    EXTCalibratedTimestamps.vkGetPhysicalDeviceCalibrateableTimeDomainsEXT(device.gpu().handle(),
                        count, domains);
                }
                boolean hasDevice = false;
                for (int i = 0; i < count.get(0); i++) {
                    int d = domains.get(i);
                    hasDevice |= d == KHRCalibratedTimestamps.VK_TIME_DOMAIN_DEVICE_KHR;
                    if (windows && d == KHRCalibratedTimestamps.VK_TIME_DOMAIN_QUERY_PERFORMANCE_COUNTER_KHR) {
                        domain = d;
                    } else if (!windows && d == KHRCalibratedTimestamps.VK_TIME_DOMAIN_CLOCK_MONOTONIC_KHR) {
                        domain = d;
                    }
                }
                if (!hasDevice) {
                    domain = -1;
                }
            }
            if (domain == KHRCalibratedTimestamps.VK_TIME_DOMAIN_QUERY_PERFORMANCE_COUNTER_KHR) {
                qpc = qpcNanos();
                if (qpc <= 0) {
                    domain = -1;
                }
            }
            if (domain < 0) {
                why = "no device and host time domain to calibrate against";
            }
        } else {
            why = "the GPU has no calibrated timestamps extension: GPU times are placed by their submits";
        }
        hostDomain = domain;
        qpcNanos = qpc;
    }

    private static double qpcNanos() {
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle frequency = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("kernel32", arena).find("QueryPerformanceFrequency").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MemorySegment out = arena.allocate(8, 8);
            int ok = (int) frequency.invokeExact(out);
            long f = out.get(ValueLayout.JAVA_LONG, 0);
            return ok != 0 && f > 0 ? 1e9 / f : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Whether it calibrates through the extension (else it estimates from frames). */
    public boolean calibrated() {
        return hostDomain >= 0;
    }

    /** Why it does not calibrate, or empty. */
    public String why() {
        return why;
    }

    /** The driver's maximum deviation of the last calibration in nanoseconds, or -1. */
    public long deviation() {
        return deviation;
    }

    /** Nanoseconds per timestamp tick. */
    public double nanosPerTick() {
        return nanosPerTick;
    }

    /** How long the fallback's window of frames is, in nanoseconds. */
    public void window(long nanos) {
        windowNanos = Math.max(1, nanos);
    }

    /**
     * Reads the GPU's and the host's clocks at once and sets the offset; does nothing without the extension. Off the
     * frame's thread: it allocates.
     */
    @AllowAllocation("calibrating, off the frame's thread")
    public void calibrate() {
        if (hostDomain < 0) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            VkCalibratedTimestampInfoKHR.Buffer infos = VkCalibratedTimestampInfoKHR.calloc(2, stack);
            infos.get(0).sType$Default().timeDomain(KHRCalibratedTimestamps.VK_TIME_DOMAIN_DEVICE_KHR);
            infos.get(1).sType$Default().timeDomain(hostDomain);
            LongBuffer stamps = stack.mallocLong(2);
            LongBuffer max = stack.mallocLong(1);
            long before = System.nanoTime();
            int r = extension.equals(KHRCalibratedTimestamps.VK_KHR_CALIBRATED_TIMESTAMPS_EXTENSION_NAME)
                ? KHRCalibratedTimestamps.vkGetCalibratedTimestampsKHR(device.handle(), infos, stamps, max)
                : EXTCalibratedTimestamps.vkGetCalibratedTimestampsEXT(device.handle(), infos, stamps, max);
            long after = System.nanoTime();
            if (r != 0) {
                return;
            }
            long host = qpcNanos > 0 ? Math.round(stamps.get(1) * qpcNanos) : stamps.get(1);
            if (host < before - 1_000_000 || host > after + 1_000_000) {
                host = before + (after - before) / 2; // not nanoTime's clock after all: the bracket's middle
            }
            offset = host - Math.round(stamps.get(0) * nanosPerTick);
            deviation = max.get(0);
        }
    }

    /**
     * A frame submitted at {@code submitNanos} (CPU) began on the GPU at {@code startTicks}: without calibration, the
     * offset follows the latest such bound of each window. Allocates nothing.
     */
    public void observe(long startTicks, long submitNanos) {
        if (hostDomain >= 0 && deviation >= 0) {
            return; // calibrated: frames do not move it (until then they place it)
        }
        long bound = submitNanos - Math.round(startTicks * nanosPerTick);
        if (windowBound == Long.MIN_VALUE || bound > windowBound) {
            windowBound = bound;
        }
        if (offset == Long.MIN_VALUE || bound > offset) {
            offset = bound; // never earlier than a submit
        }
        if (submitNanos - windowStart > windowNanos) {
            offset = windowBound; // a new window: drift either way is followed
            windowBound = Long.MIN_VALUE;
            windowStart = submitNanos;
        }
    }

    /** Whether the offset is known (calibrated once, or a frame observed). */
    public boolean ready() {
        return offset != Long.MIN_VALUE;
    }

    /** The CPU's nanoTime of GPU timestamp {@code ticks}. */
    public long toCpu(long ticks) {
        return Math.round(ticks * nanosPerTick) + offset;
    }
}
