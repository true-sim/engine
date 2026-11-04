package com.truesim.engine.app.profile;

import com.truesim.annotations.AllowAllocation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

/**
 * The engine's profiler core: CPU zones per thread and the frame clock, both off until something shows or records them
 * (the profiler's settings, {@code profiler.*}, through the UI's profiler).
 *
 * <p><b>Zones</b> are declared once ({@link #zone}) and timed with {@link #begin}/{@link #end} on any thread; they
 * nest. Each thread writes its begins and ends into a ring of its own (no lock, no allocation once the ring exists),
 * which the main thread {@link #drain}s once a frame into a {@link ZoneSink}. Off ({@link #zones(boolean)}), a begin or
 * an end is one read of a volatile flag: games may leave zones in shipping code. A thread's lane is named by the
 * thread's name when it first records, or by {@link #thread(String)}.
 *
 * <p><b>The frame clock</b> ({@link #frames(boolean)}): the engine marks each frame's start ({@link #frameStart}),
 * the time the main thread waited for the GPU or the swapchain ({@link #waitBegin}/{@link #waitEnd}: the frame
 * slot's fence, the swapchain's acquire, the present) and its submit ({@link #submitted}); the last finished frame's
 * values are read with {@link #lastFrame()} and friends. Nothing waits for anything: the times are taken where the
 * frame passes anyway.
 */
public final class Profiler {

    private static final VarHandle HEAD;

    static {
        try {
            HEAD = MethodHandles.lookup().findVarHandle(ThreadZones.class, "head", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static volatile boolean zonesOn;
    private static volatile boolean framesOn;
    private static volatile int ringEvents = 1 << 16;

    private static final Object LOCK = new Object();
    private static String[] zoneNames = new String[256];
    private static volatile int zoneCount;
    private static volatile ThreadZones[] threads = new ThreadZones[0];
    private static final ThreadLocal<ThreadZones> CURRENT = new ThreadLocal<>();

    // the frame clock (main thread)
    private static long frameStart;
    private static long previousStart = Long.MIN_VALUE;
    private static long waitStarted;
    private static long waited;
    private static long submitAt;
    private static long submittedFrame = -1;
    private static long ownFrame;
    // the last frame that ended
    private static long lastNumber = -1;
    private static long lastStart;
    private static long lastSubmit;
    private static long lastWait;
    private static long lastInterval;

    private Profiler() {
    }

    // ---------------------------------------------------------------- switches

    /** Turns CPU zones on or off (off: begins and ends do nothing). */
    public static void zones(boolean on) {
        zonesOn = on;
    }

    /** Whether CPU zones are recorded. */
    public static boolean zones() {
        return zonesOn;
    }

    /** Turns the frame clock on or off. */
    public static void frames(boolean on) {
        framesOn = on;
        if (!on) {
            previousStart = Long.MIN_VALUE;
        }
    }

    /** Whether the frame clock runs. */
    public static boolean frames() {
        return framesOn;
    }

    /** The events (a begin or an end each) a thread's ring holds; rings made from now on take it (power of two). */
    public static void ringEvents(int events) {
        ringEvents = Integer.highestOneBit(Math.max(64, events) - 1) << 1;
    }

    // ---------------------------------------------------------------- zones

    /** The zone named {@code name}: the same name, the same zone. Declare zones once, in static fields. */
    @AllowAllocation("zones are declared once")
    public static Zone zone(String name) {
        synchronized (LOCK) {
            for (int i = 0; i < zoneCount; i++) {
                if (zoneNames[i].equals(name)) {
                    return new Zone(i, name);
                }
            }
            if (zoneCount == zoneNames.length) {
                zoneNames = Arrays.copyOf(zoneNames, zoneCount * 2);
            }
            zoneNames[zoneCount] = name;
            Zone z = new Zone(zoneCount, name);
            zoneCount = zoneCount + 1;
            return z;
        }
    }

    /** How many zones are declared (ids 0 to this minus one). */
    public static int zoneCount() {
        return zoneCount;
    }

    /** Zone {@code id}'s name. */
    public static String zoneName(int id) {
        return zoneNames[id];
    }

    /** Begins {@code zone} on the calling thread. Off: does nothing. Allocates nothing once the thread's ring exists. */
    public static void begin(Zone zone) {
        if (zonesOn) {
            current().write(System.nanoTime(), zone.id());
        }
    }

    /** Ends the calling thread's innermost open zone ({@code zone}, for reading). Off: does nothing. */
    public static void end(Zone zone) {
        if (zonesOn) {
            current().write(System.nanoTime(), ~zone.id());
        }
    }

    private static ThreadZones current() {
        ThreadZones t = CURRENT.get();
        return t != null ? t : register(Thread.currentThread().getName());
    }

    /** Names the calling thread's lane {@code name} (by default the thread's name), registering it if new. */
    public static void thread(String name) {
        ThreadZones t = CURRENT.get();
        if (t == null) {
            register(name);
        } else {
            t.name = name;
        }
    }

    @AllowAllocation("a thread's first zone")
    private static ThreadZones register(String name) {
        ThreadZones t = new ThreadZones(threads.length, name, ringEvents, Thread.currentThread());
        synchronized (LOCK) {
            ThreadZones[] grown = Arrays.copyOf(threads, threads.length + 1);
            t.index = threads.length;
            grown[t.index] = t;
            threads = grown;
        }
        CURRENT.set(t);
        return t;
    }

    /** How many threads have recorded zones (lanes 0 to this minus one). */
    public static int threadCount() {
        return threads.length;
    }

    /** Thread lane {@code i}'s name. */
    public static String threadName(int i) {
        return threads[i].name;
    }

    /** Thread lane {@code i}'s thread (for its CPU time), or null if it ended. */
    public static Thread thread(int i) {
        Thread t = threads[i].thread;
        return t != null && t.isAlive() ? t : null;
    }

    /**
     * Hands everything the threads recorded since the last drain to {@code sink}, thread by thread, in order. Call on
     * one thread (the main thread, once a frame); allocates nothing.
     */
    public static void drain(ZoneSink sink) {
        ThreadZones[] all = threads;
        for (ThreadZones t : all) {
            t.drain(sink);
        }
    }

    /** Throws away everything recorded and not drained (the profiler was off). */
    public static void discard() {
        ThreadZones[] all = threads;
        for (ThreadZones t : all) {
            t.tail = (long) HEAD.getAcquire(t);
            t.depth = 0;
        }
    }

    /** Events lost by threads whose rings filled between drains, in all. */
    public static long lostEvents() {
        long n = 0;
        for (ThreadZones t : threads) {
            n += t.lost;
        }
        return n;
    }

    // ---------------------------------------------------------------- frame clock

    /** A frame starts at {@code nanos} (the engine calls it). */
    public static void frameStart(long nanos) {
        if (!framesOn) {
            return;
        }
        frameStart = nanos;
        waited = 0;
        submitAt = 0;
        submittedFrame = -1;
    }

    /** The main thread starts waiting for the GPU or the swapchain. */
    public static void waitBegin() {
        if (framesOn) {
            waitStarted = System.nanoTime();
        }
    }

    /** The wait begun by {@link #waitBegin} is over. */
    public static void waitEnd() {
        if (framesOn && waitStarted != 0) {
            waited += System.nanoTime() - waitStarted;
            waitStarted = 0;
        }
    }

    /** The frame was submitted as frame graph frame {@code frame} (its GPU times come under that number). */
    public static void submitted(long frame) {
        if (framesOn) {
            submitAt = System.nanoTime();
            submittedFrame = frame;
        }
    }

    /** The frame ends (the engine calls it, after the frame driver's end): its values become the last frame's. */
    public static void frameEnd() {
        if (!framesOn) {
            return;
        }
        long now = System.nanoTime();
        lastNumber = submittedFrame >= 0 ? submittedFrame : ++ownFrame + 1_000_000_000L;
        lastStart = frameStart;
        lastSubmit = submitAt != 0 ? submitAt : now;
        lastWait = waited;
        lastInterval = previousStart == Long.MIN_VALUE ? -1 : frameStart - previousStart;
        previousStart = frameStart;
    }

    /** The last ended frame's number (the frame graph's when it submitted one), or -1. */
    public static long lastFrame() {
        return lastNumber;
    }

    /** The last ended frame's start ({@link System#nanoTime()}). */
    public static long lastFrameStart() {
        return lastStart;
    }

    /** When the last ended frame was submitted (its end, without a submit). */
    public static long lastFrameSubmit() {
        return lastSubmit;
    }

    /** How long the last ended frame waited for the GPU or the swapchain, in nanoseconds. */
    public static long lastFrameWait() {
        return lastWait;
    }

    /** The time from the frame before's start to the last ended frame's start, or -1 for the first. */
    public static long lastFrameInterval() {
        return lastInterval;
    }

    // ---------------------------------------------------------------- one thread's ring

    static final class ThreadZones {
        final Thread thread;
        volatile String name;
        int index;
        final long[] times;
        final int[] ids; // a zone id: its begin; ~id: its end
        final int mask;
        long head; // written by the thread (release), read by the drain (acquire)
        long tail; // the drain's
        int depth; // the drain's
        long lost;

        ThreadZones(int index, String name, int events, Thread thread) {
            this.index = index;
            this.name = name;
            this.thread = thread;
            times = new long[events];
            ids = new int[events];
            mask = events - 1;
        }

        void write(long nanos, int id) {
            long h = head;
            int s = (int) h & mask;
            times[s] = nanos;
            ids[s] = id;
            HEAD.setRelease(this, h + 1);
        }

        void drain(ZoneSink sink) {
            long h = (long) HEAD.getAcquire(this);
            long from = tail;
            if (h - from > times.length) {
                lost += h - from - times.length;
                from = h - times.length;
                sink.lost(index, times[(int) from & mask]);
                depth = 0;
            }
            for (long i = from; i < h; i++) {
                int s = (int) i & mask;
                int id = ids[s];
                if (id >= 0) {
                    sink.begin(index, times[s], id, depth++);
                } else if (depth > 0) {
                    sink.end(index, times[s], --depth);
                }
            }
            tail = h;
        }
    }
}
