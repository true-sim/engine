package com.truesim.engine.audio;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.truesim.annotations.AllowAllocation;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.locks.LockSupport;

/**
 * How the audio thread sleeps (AU15 and the reviewer's note): on Windows a high-resolution waitable timer
 * ({@code CreateWaitableTimerExW} with {@code CREATE_WAITABLE_TIMER_HIGH_RESOLUTION}, Windows 10 1803+), because
 * {@link LockSupport#parkNanos} rides the system timer and Windows 11 ignores a raised timer resolution for minimised
 * windows; elsewhere {@code parkNanos}. The downcall handles are made once and kept (pitfalls: no wrapping per call);
 * sleeping allocates nothing. Also raises the calling thread to MMCSS "Pro Audio" on Windows ({@code avrt.dll}).
 * One per thread.
 */
@SuppressWarnings("restricted") // downcalls into kernel32 and avrt
final class Waiter implements AutoCloseable {

    private static final int HIGH_RESOLUTION = 0x2;
    private static final int TIMER_ALL_ACCESS = 0x1F0003;
    private static final int INFINITE = 0xFFFFFFFF;

    private final Arena arena = Arena.ofConfined();
    private final MethodHandle setTimer;
    private final MethodHandle waitFor;
    private final MethodHandle closeHandle;
    private final MemorySegment timer;
    private final MemorySegment due;

    @AllowAllocation("made once per audio thread")
    Waiter() {
        MethodHandle set = null;
        MethodHandle wait = null;
        MethodHandle close = null;
        MemorySegment handle = MemorySegment.NULL;
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
                MethodHandle create = linker.downcallHandle(kernel32.findOrThrow("CreateWaitableTimerExW"),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
                set = linker.downcallHandle(kernel32.findOrThrow("SetWaitableTimer"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
                wait = linker.downcallHandle(kernel32.findOrThrow("WaitForSingleObject"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
                close = linker.downcallHandle(kernel32.findOrThrow("CloseHandle"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
                handle = (MemorySegment) create.invokeExact(MemorySegment.NULL, MemorySegment.NULL, HIGH_RESOLUTION,
                    TIMER_ALL_ACCESS);
            } catch (Throwable e) {
                handle = MemorySegment.NULL; // older Windows: park instead
            }
        }
        timer = handle;
        setTimer = set;
        waitFor = wait;
        closeHandle = close;
        due = arena.allocate(JAVA_LONG);
    }

    /** Whether it sleeps on a high-resolution timer (else on {@code parkNanos}). */
    boolean precise() {
        return !timer.equals(MemorySegment.NULL);
    }

    /** Sleeps about {@code nanos}. */
    void sleep(long nanos) {
        if (nanos <= 0) {
            return;
        }
        if (timer.equals(MemorySegment.NULL)) {
            LockSupport.parkNanos(nanos);
            return;
        }
        due.set(JAVA_LONG, 0, -(nanos / 100)); // relative, in 100 ns units
        try {
            int ok = (int) setTimer.invokeExact(timer, due, 0, MemorySegment.NULL, MemorySegment.NULL, 0);
            if (ok != 0) {
                int unused = (int) waitFor.invokeExact(timer, INFINITE);
            } else {
                LockSupport.parkNanos(nanos);
            }
        } catch (Throwable e) {
            LockSupport.parkNanos(nanos);
        }
    }

    /**
     * Registers the calling thread with MMCSS as "Pro Audio" on Windows, as SDL's own WASAPI thread does; returns
     * whether it did. Elsewhere it does nothing.
     */
    @AllowAllocation("once per audio thread")
    static boolean proAudio() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return false;
        }
        try (Arena a = Arena.ofConfined()) {
            SymbolLookup avrt = SymbolLookup.libraryLookup("avrt", a);
            MethodHandle set = Linker.nativeLinker().downcallHandle(avrt.findOrThrow("AvSetMmThreadCharacteristicsW"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            MemorySegment name = a.allocateFrom("Pro Audio", java.nio.charset.StandardCharsets.UTF_16LE);
            MemorySegment task = a.allocate(JAVA_INT);
            MemorySegment handle = (MemorySegment) set.invokeExact(name, task);
            return !handle.equals(MemorySegment.NULL);
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    public void close() {
        if (closeHandle != null && precise()) {
            try {
                int unused = (int) closeHandle.invokeExact(timer);
            } catch (Throwable e) {
                // closing anyway
            }
        }
        arena.close();
    }
}
