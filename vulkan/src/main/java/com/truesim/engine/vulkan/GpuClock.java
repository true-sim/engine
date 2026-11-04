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

    /** The calibrated timestamps calls: the device's (KHR or EXT), or a test's. */
    interface Source {
        /** The time domains the device calibrates (VK_TIME_DOMAIN_*). */
        int[] domains();

        /** Reads the device's and {@code hostDomain}'s clocks at once into stamps[0..1], max[0]; a VkResult. */
        int calibrate(int hostDomain, long[] stamps, long[] max);
    }

    private final Source source;
    private final double nanosPerTick;
    private final int hostDomain; // a VK_TIME_DOMAIN_* the GPU calibrates against, or -1
    private final double qpcNanos; // nanoseconds per performance counter tick (Windows), else 0
    private volatile long offset = Long.MIN_VALUE; // CPU nanos = round(ticks * nanosPerTick) + offset
    private volatile long deviation = -1; // the driver's maximum deviation of the last calibration, ns
    private long windowBound = Long.MIN_VALUE; // the fallback's: the latest bound of this window
    private long windowStart;
    private long windowNanos = 2_000_000_000L;
    private final String why;

    /** The clock of {@code device}'s timestamps. */
    @AllowAllocation("made once")
    public GpuClock(VulkanDevice device) {
        this(device, true);
    }

    /** As {@link #GpuClock(VulkanDevice)}; {@code calibrate} false estimates from frames even with the extension. */
    @AllowAllocation("made once")
    GpuClock(VulkanDevice device, boolean calibrate) {
        this(tickNanos(device), source(device, calibrate ? device.calibratedTimestamps() : null),
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"), GpuClock::qpcNanos);
    }

    /**
     * A clock of {@code nanosPerTick} ticks calibrating through {@code source} (null: no extension), on Windows'
     * performance counter ({@code qpcNanos} per tick) if {@code windows}, else on {@code CLOCK_MONOTONIC}.
     */
    @AllowAllocation("made once")
    GpuClock(double nanosPerTick, Source source, boolean windows, java.util.function.DoubleSupplier qpcNanos) {
        this.nanosPerTick = nanosPerTick;
        this.source = source;
        int domain = source == null ? -1 : hostDomain(source.domains(), windows);
        double qpc = domain == KHRCalibratedTimestamps.VK_TIME_DOMAIN_QUERY_PERFORMANCE_COUNTER_KHR
            ? qpcNanos.getAsDouble() : 0;
        if (domain == KHRCalibratedTimestamps.VK_TIME_DOMAIN_QUERY_PERFORMANCE_COUNTER_KHR && qpc <= 0) {
            domain = -1;
        }
        why = source == null ? "the GPU has no calibrated timestamps extension: GPU times are placed by their submits"
            : domain < 0 ? "no device and host time domain to calibrate against" : "";
        hostDomain = domain;
        this.qpcNanos = qpc;
    }

    private static double tickNanos(VulkanDevice device) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties p = VkPhysicalDeviceProperties.calloc(stack);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties(device.gpu().handle(), p);
            return p.limits().timestampPeriod();
        }
    }

    /** The device's calls of {@code extension} (null: none), KHR's and EXT's being the same. */
    static Source source(VulkanDevice device, String extension) {
        if (extension == null) {
            return null;
        }
        boolean khr = extension.equals(KHRCalibratedTimestamps.VK_KHR_CALIBRATED_TIMESTAMPS_EXTENSION_NAME);
        return new Source() {
            @Override
            public int[] domains() {
                try (MemoryStack stack = stackPush()) {
                    IntBuffer count = stack.mallocInt(1);
                    domains(count, null);
                    IntBuffer domains = stack.mallocInt(count.get(0));
                    domains(count, domains);
                    int[] all = new int[count.get(0)];
                    domains.get(all);
                    return all;
                }
            }

            private void domains(IntBuffer count, IntBuffer domains) {
                if (khr) {
                    KHRCalibratedTimestamps.vkGetPhysicalDeviceCalibrateableTimeDomainsKHR(device.gpu().handle(),
                        count, domains);
                } else {
                    EXTCalibratedTimestamps.vkGetPhysicalDeviceCalibrateableTimeDomainsEXT(device.gpu().handle(),
                        count, domains);
                }
            }

            @Override
            public int calibrate(int hostDomain, long[] stamps, long[] max) {
                try (MemoryStack stack = stackPush()) {
                    VkCalibratedTimestampInfoKHR.Buffer infos = VkCalibratedTimestampInfoKHR.calloc(2, stack);
                    infos.get(0).sType$Default().timeDomain(KHRCalibratedTimestamps.VK_TIME_DOMAIN_DEVICE_KHR);
                    infos.get(1).sType$Default().timeDomain(hostDomain);
                    LongBuffer out = stack.mallocLong(2);
                    LongBuffer deviation = stack.mallocLong(1);
                    int r = khr ? KHRCalibratedTimestamps.vkGetCalibratedTimestampsKHR(device.handle(), infos, out,
                        deviation) : EXTCalibratedTimestamps.vkGetCalibratedTimestampsEXT(device.handle(), infos, out,
                        deviation);
                    out.get(stamps);
                    max[0] = deviation.get(0);
                    return r;
                }
            }
        };
    }

    /**
     * The host time domain to calibrate against among {@code domains} (VK_TIME_DOMAIN_*): the one {@code nanoTime}
     * reads (Windows' performance counter, else {@code CLOCK_MONOTONIC}), if the device's is there too; else -1.
     */
    static int hostDomain(int[] domains, boolean windows) {
        boolean device = false;
        int host = -1;
        int wanted = windows ? KHRCalibratedTimestamps.VK_TIME_DOMAIN_QUERY_PERFORMANCE_COUNTER_KHR
            : KHRCalibratedTimestamps.VK_TIME_DOMAIN_CLOCK_MONOTONIC_KHR;
        for (int d : domains) {
            device |= d == KHRCalibratedTimestamps.VK_TIME_DOMAIN_DEVICE_KHR;
            if (d == wanted) {
                host = d;
            }
        }
        return device ? host : -1;
    }

    /**
     * The host stamp of a calibration on {@code nanoTime}'s clock: scaled from performance counter ticks
     * ({@code qpcNanos} per tick; 0: already nanoseconds), or, if that falls outside the call's bracket
     * {@code before..after} by more than a millisecond (not {@code nanoTime}'s clock after all), the bracket's middle.
     */
    static long hostNanos(long stamp, double qpcNanos, long before, long after) {
        long host = qpcNanos > 0 ? Math.round(stamp * qpcNanos) : stamp;
        return host < before - 1_000_000 || host > after + 1_000_000 ? before + (after - before) / 2 : host;
    }

    /** Nanoseconds per tick of a counter of {@code frequency} Hz read by a call that returned {@code ok}; or 0. */
    static double perTick(int ok, long frequency) {
        return ok != 0 && frequency > 0 ? 1e9 / frequency : 0;
    }

    private static double qpcNanos() {
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle frequency = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("kernel32", arena).find("QueryPerformanceFrequency").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MemorySegment out = arena.allocate(8, 8);
            int ok = (int) frequency.invokeExact(out);
            return perTick(ok, out.get(ValueLayout.JAVA_LONG, 0));
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
        long[] stamps = new long[2];
        long[] max = new long[1];
        long before = System.nanoTime();
        int r = source.calibrate(hostDomain, stamps, max);
        long after = System.nanoTime();
        if (r != 0) {
            return;
        }
        offset = hostNanos(stamps[1], qpcNanos, before, after) - Math.round(stamps[0] * nanosPerTick);
        deviation = max[0];
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
