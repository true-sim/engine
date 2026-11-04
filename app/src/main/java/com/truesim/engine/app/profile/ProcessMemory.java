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
import java.util.Locale;

/**
 * The process's resident memory (what the operating system holds in RAM for it: its working set) through FFM: Windows'
 * {@code K32GetProcessMemoryInfo}, Linux's {@code /proc/self/statm}, macOS's {@code task_info}. Read on the counters
 * thread; a platform without it says why ({@link #why()}).
 */
@AllowAllocation("runs on the counters thread, off the frame")
@SuppressWarnings("restricted") // FFM calls into the operating system
public abstract class ProcessMemory {

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
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.startsWith("windows")) {
                return new Windows();
            }
            if (os.startsWith("linux")) {
                return new Linux();
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

    /** {@code K32GetProcessMemoryInfo(GetCurrentProcess(), &counters, 72)}: the working set. */
    private static final class Windows extends ProcessMemory {
        private final MethodHandle info;
        private final MethodHandle process;
        private final MemorySegment counters = Arena.global().allocate(72, 8);

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
                counters.set(ValueLayout.JAVA_INT, 0, 72);
                int ok = (int) info.invokeExact((MemorySegment) process.invokeExact(), counters, 72);
                return ok != 0 ? counters.get(ValueLayout.JAVA_LONG, 16) : Double.NaN; // WorkingSetSize
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

        Linux() throws IOException {
            statm = new RandomAccessFile("/proc/self/statm", "r");
            long size = 4096;
            try {
                MethodHandle getpagesize = Linker.nativeLinker().downcallHandle(
                    Linker.nativeLinker().defaultLookup().find("getpagesize").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
                size = (int) getpagesize.invokeExact();
            } catch (Throwable e) {
                // 4 KiB, Linux's usual page
            }
            pageSize = size;
        }

        @Override
        public double resident() {
            try {
                statm.seek(0);
                int n = statm.read(buffer);
                int i = 0;
                while (i < n && buffer[i] != ' ') {
                    i++;
                }
                long pages = 0;
                for (i++; i < n && buffer[i] >= '0' && buffer[i] <= '9'; i++) {
                    pages = pages * 10 + buffer[i] - '0';
                }
                return pages * (double) pageSize;
            } catch (IOException e) {
                return Double.NaN;
            }
        }

        @Override
        public boolean available() {
            return true;
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
                int r = (int) taskInfo.invokeExact(task, MACH_TASK_BASIC_INFO, out, count);
                return r == 0 ? out.get(ValueLayout.JAVA_LONG, 8) : Double.NaN; // after virtual_size
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
