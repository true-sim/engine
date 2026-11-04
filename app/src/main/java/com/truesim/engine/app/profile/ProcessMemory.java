package com.truesim.engine.app.profile;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The process's resident memory (what the operating system holds in RAM for it: its working set) through FFM: Windows'
 * {@code K32GetProcessMemoryInfo}, Linux's {@code /proc/self/statm}, macOS's {@code task_info}. Read on the counters
 * thread; a platform without it says why ({@link #why()}). What each system's call answers is read by a function of
 * its own ({@link #workingSet}, {@link #statmPages}, {@link #taskResident}), tested on every system.
 */
@AllowAllocation("runs on the counters thread, off the frame")
@SuppressWarnings("restricted") // FFM calls into the operating system
public abstract class ProcessMemory {

    /** {@code sizeof(PROCESS_MEMORY_COUNTERS)} on 64-bit Windows. */
    static final int COUNTERS_BYTES = 72;

    /** Resident bytes, or NaN. */
    public abstract double resident();

    /** Whether it reads anything here. */
    public abstract boolean available();

    /** Why not, when it does not. */
    public String why() {
        return "";
    }

    /** The reader for this operating system. */
    public static ProcessMemory create() {
        return create(System.getProperty("os.name", ""), Path.of("/proc/self/statm"));
    }

    /** The reader for system {@code osName} ({@code os.name}'s value), Linux's file being {@code statm}. */
    static ProcessMemory create(String osName, Path statm) {
        String os = osName.toLowerCase(Locale.ROOT);
        try {
            if (os.startsWith("windows")) {
                return new Windows();
            }
            if (os.startsWith("linux")) {
                return new Linux(statm);
            }
            if (os.startsWith("mac")) {
                return new Mac();
            }
        } catch (RuntimeException | LinkageError | IOException e) {
            return none("not readable here: " + e);
        }
        return none("no reader for " + os);
    }

    private static ProcessMemory none(String reason) {
        return new ProcessMemory() {
            @Override
            public double resident() {
                return Double.NaN;
            }

            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String why() {
                return reason;
            }
        };
    }

    /** {@code /proc/self/statm}'s second field (resident pages) of its first {@code n} bytes. */
    static long statmPages(byte[] text, int n) {
        int i = 0;
        while (i < n && text[i] != ' ') {
            i++;
        }
        long pages = 0;
        for (i++; i < n && text[i] >= '0' && text[i] <= '9'; i++) {
            pages = pages * 10 + text[i] - '0';
        }
        return pages;
    }

    /** {@code K32GetProcessMemoryInfo}'s answer: {@code WorkingSetSize} when it succeeded ({@code ok} not 0). */
    static double workingSet(int ok, MemorySegment counters) {
        return ok != 0 ? counters.get(ValueLayout.JAVA_LONG, 16) : Double.NaN;
    }

    /** {@code task_info}'s answer: {@code resident_size} (after {@code virtual_size}) when it succeeded ({@code 0}). */
    static double taskResident(int result, MemorySegment info) {
        return result == 0 ? info.get(ValueLayout.JAVA_LONG, 8) : Double.NaN;
    }

    /** {@code K32GetProcessMemoryInfo(GetCurrentProcess(), &counters, 72)}: the working set. */
    private static final class Windows extends ProcessMemory {
        private final MethodHandle info;
        private final MethodHandle process;
        private final MemorySegment counters = Arena.global().allocate(COUNTERS_BYTES, 8);

        Windows() {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            info = linker.downcallHandle(kernel32.find("K32GetProcessMemoryInfo").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT));
            process = linker.downcallHandle(kernel32.find("GetCurrentProcess").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        }

        @Override
        public double resident() {
            try {
                counters.set(ValueLayout.JAVA_INT, 0, COUNTERS_BYTES);
                return workingSet((int) info.invokeExact((MemorySegment) process.invokeExact(), counters,
                    COUNTERS_BYTES), counters);
            } catch (Throwable e) {
                return Double.NaN;
            }
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    /** {@code /proc/self/statm}'s second field (resident pages) times the page size. */
    private static final class Linux extends ProcessMemory {
        private final RandomAccessFile statm;
        private final byte[] buffer = new byte[256];
        private final long pageSize;

        Linux(Path file) throws IOException {
            statm = new RandomAccessFile(file.toFile(), "r");
            pageSize = pageSize();
        }

        @Override
        public double resident() {
            try {
                statm.seek(0);
                return statmPages(buffer, statm.read(buffer)) * (double) pageSize;
            } catch (IOException e) {
                return Double.NaN;
            }
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    /** {@code getpagesize()} where the C library has it, else 4 KiB (Linux's usual page). */
    static long pageSize() {
        try {
            MethodHandle getpagesize = Linker.nativeLinker().downcallHandle(
                Linker.nativeLinker().defaultLookup().find("getpagesize").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
            return (int) getpagesize.invokeExact();
        } catch (Throwable e) {
            return 4096;
        }
    }

    /** {@code task_info(mach_task_self_, MACH_TASK_BASIC_INFO, ...)}: its {@code resident_size}. Not run here yet. */
    private static final class Mac extends ProcessMemory {
        private static final int MACH_TASK_BASIC_INFO = 20;
        private static final int COUNT = 12; // sizeof(mach_task_basic_info) / sizeof(natural_t)
        private final MethodHandle taskInfo;
        private final int task;
        private final MemorySegment out = Arena.global().allocate(64, 8);
        private final MemorySegment count = Arena.global().allocate(4, 4);

        Mac() {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = linker.defaultLookup();
            taskInfo = linker.downcallHandle(lookup.find("task_info").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            task = lookup.find("mach_task_self_").orElseThrow().reinterpret(4).get(ValueLayout.JAVA_INT, 0);
        }

        @Override
        public double resident() {
            try {
                count.set(ValueLayout.JAVA_INT, 0, COUNT);
                return taskResident((int) taskInfo.invokeExact(task, MACH_TASK_BASIC_INFO, out, count), out);
            } catch (Throwable e) {
                return Double.NaN;
            }
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
