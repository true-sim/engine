package com.truesim.engine.app.profile;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The operating system's load counters (detailed only): every core's load and the GPU's engines' load as the system
 * sees them. Windows: the Performance Data Helper ({@code pdh.dll} through FFM), {@code \Processor(*)\% Processor
 * Time} and {@code \GPU Engine(*)\Utilization Percentage} ({@code gpu.engine.<type>}: the busiest engine of a type,
 * all processes; {@code gpu.engine.self.<type>}: this process's). Linux: {@code /proc/stat} per core, the AMD driver's
 * {@code /sys/class/drm/card*}{@code /device/gpu_busy_percent}, and the DRM file descriptors' {@code drm-engine-*} busy
 * time ({@code /proc/self/fdinfo}, this process's). Anything missing is reported once as unavailable.
 */
@AllowAllocation("runs on the counters thread, off the frame")
@SuppressWarnings("restricted") // FFM calls into the operating system
public final class OsCounters implements Counters.Source {

    private final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private final Map<String, Integer> ids = new HashMap<>();
    private Pdh pdh;
    // Linux
    private final Map<String, long[]> lastStat = new HashMap<>();
    private final Map<String, Long> lastEngine = new HashMap<>();
    private long lastEngineAt;

    @Override
    public boolean detailed() {
        return true;
    }

    @Override
    public void declare(Counters into) {
        if (os.startsWith("windows")) {
            try {
                pdh = new Pdh();
            } catch (RuntimeException | LinkageError e) {
                into.unavailable("per-core and GPU engine load: PDH not usable: " + e);
            }
        } else if (!os.startsWith("linux")) {
            into.unavailable("per-core and GPU engine load: no reader for " + os);
        }
    }

    private int id(Counters into, String name, String unit) {
        Integer id = ids.get(name);
        if (id == null) {
            id = into.counter(name, unit);
            ids.put(name, id);
        }
        return id;
    }

    @Override
    public void sample(Counters into) {
        if (pdh != null) {
            pdh.sample(this, into);
        } else if (os.startsWith("linux")) {
            linuxCores(into);
            linuxGpu(into);
        }
    }

    // ---------------------------------------------------------------- Linux

    private void linuxCores(Counters into) {
        try {
            cores(Files.readAllLines(Path.of("/proc/stat")), lastStat,
                (core, busy) -> into.set(id(into, "cpu.core." + core, "%"), busy));
        } catch (IOException | RuntimeException e) {
            into.unavailable("per-core load: /proc/stat not readable: " + e);
        }
    }

    /**
     * Each core's load from {@code /proc/stat}'s {@code cpuN} lines against the totals of the call before
     * ({@code last}, updated): {@code out.accept(core, percent)}.
     */
    static void cores(java.util.List<String> lines, Map<String, long[]> last,
        java.util.function.ObjDoubleConsumer<String> out) {
        for (String line : lines) {
            if (!line.startsWith("cpu") || line.startsWith("cpu ")) {
                continue;
            }
            String[] f = line.trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < f.length; i++) {
                total += Long.parseLong(f[i]);
            }
            long idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0);
            long[] before = last.get(f[0]);
            if (before != null && total > before[0]) {
                out.accept(f[0].substring(3), 100.0 * (1 - (double) (idle - before[1]) / (total - before[0])));
            }
            last.put(f[0], new long[] {total, idle});
        }
    }

    /** Adds the {@code drm-engine-<name>: <n> ns} lines of one DRM file descriptor's fdinfo to {@code engines}. */
    static void fdinfo(String text, Map<String, Long> engines) {
        for (String line : text.split("\n")) {
            if (line.startsWith("drm-engine-") && line.endsWith(" ns")) {
                int colon = line.indexOf(':');
                String engine = line.substring("drm-engine-".length(), colon);
                long ns = Long.parseLong(line.substring(colon + 1, line.length() - 3).trim());
                engines.merge(engine, ns, Math::max); // one client's descriptors share its time
            }
        }
    }

    private void linuxGpu(Counters into) {
        boolean any = false;
        try (DirectoryStream<Path> cards = Files.newDirectoryStream(Path.of("/sys/class/drm"), "card[0-9]*")) {
            for (Path card : cards) {
                Path busy = card.resolve("device/gpu_busy_percent");
                if (Files.isReadable(busy)) {
                    into.set(id(into, "gpu.busy." + card.getFileName(), "%"),
                        Double.parseDouble(Files.readString(busy).trim()));
                    any = true;
                }
            }
        } catch (IOException | RuntimeException e) {
            // no DRM: reported below
        }
        if (!any) {
            into.unavailable("GPU load: no /sys/class/drm/card*/device/gpu_busy_percent (AMD's amdgpu has it)");
        }
        Map<String, Long> engines = new HashMap<>();
        try (DirectoryStream<Path> fds = Files.newDirectoryStream(Path.of("/proc/self/fdinfo"))) {
            for (Path fd : fds) {
                String text;
                try {
                    text = Files.readString(fd, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                if (text.contains("drm-engine-")) {
                    fdinfo(text, engines);
                }
            }
        } catch (IOException | RuntimeException e) {
            // reported below
        }
        long now = System.nanoTime();
        if (engines.isEmpty()) {
            into.unavailable("GPU engine load of this process: no drm-engine-* in /proc/self/fdinfo");
        } else if (lastEngineAt != 0) {
            for (Map.Entry<String, Long> e : engines.entrySet()) {
                Long last = lastEngine.get(e.getKey());
                if (last != null) {
                    into.set(id(into, "gpu.engine.self." + e.getKey(), "%"),
                        100.0 * (e.getValue() - last) / (now - lastEngineAt));
                }
            }
        }
        lastEngine.clear();
        lastEngine.putAll(engines);
        lastEngineAt = now;
    }

    // ---------------------------------------------------------------- Windows: PDH

    /** Windows' Performance Data Helper through FFM. */
    private static final class Pdh {
        private static final int PDH_FMT_DOUBLE = 0x00000200;
        private static final int PDH_FMT_NOCAP100 = 0x00008000;
        private static final int PDH_MORE_DATA = 0x800007D2;
        private static final int ITEM = 24; // PDH_FMT_COUNTERVALUE_ITEM_W: name pointer, status, padding, double
        private final MethodHandle collect;
        private final MethodHandle array;
        private final MemorySegment query;
        private final MemorySegment cores;
        private final MemorySegment engines;
        private final Arena arena = Arena.ofShared();
        private MemorySegment buffer;
        private final MemorySegment size;
        private final MemorySegment items;
        private final String self = "pid_" + ProcessHandle.current().pid() + "_";

        Pdh() {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lib = SymbolLookup.libraryLookup("pdh", Arena.global());
            MethodHandle open = linker.downcallHandle(lib.find("PdhOpenQueryW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS));
            MethodHandle add = linker.downcallHandle(lib.find("PdhAddEnglishCounterW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            collect = linker.downcallHandle(lib.find("PdhCollectQueryData").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            array = linker.downcallHandle(lib.find("PdhGetFormattedCounterArrayW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MemorySegment handle = arena.allocate(ValueLayout.ADDRESS);
            check(invoke(open, MemorySegment.NULL, 0L, handle), "PdhOpenQueryW");
            query = handle.get(ValueLayout.ADDRESS, 0);
            cores = counter(add, "\\Processor(*)\\% Processor Time");
            engines = counter(add, "\\GPU Engine(*)\\Utilization Percentage");
            size = arena.allocate(ValueLayout.JAVA_INT);
            items = arena.allocate(ValueLayout.JAVA_INT);
            buffer = arena.allocate(64 * 1024, 8);
            invoke(collect, query);
        }

        private MemorySegment counter(MethodHandle add, String path) {
            MemorySegment handle = arena.allocate(ValueLayout.ADDRESS);
            check(invoke(add, query, wide(path), 0L, handle), "PdhAddEnglishCounterW " + path);
            return handle.get(ValueLayout.ADDRESS, 0);
        }

        private MemorySegment wide(String s) {
            MemorySegment w = arena.allocate((s.length() + 1) * 2L, 2);
            for (int i = 0; i < s.length(); i++) {
                w.set(ValueLayout.JAVA_CHAR, i * 2L, s.charAt(i));
            }
            return w;
        }

        private static int invoke(MethodHandle h, Object... args) {
            try {
                return (int) h.invokeWithArguments(args);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        private static void check(int status, String what) {
            if (status != 0) {
                throw new IllegalStateException(what + " failed: 0x" + Integer.toHexString(status));
            }
        }

        /** The counter's instances and values into {@code out} (name, value), or false. */
        private boolean read(MemorySegment counter, int format, Map<String, Double> out) {
            for (int attempt = 0; attempt < 3; attempt++) {
                size.set(ValueLayout.JAVA_INT, 0, (int) buffer.byteSize());
                int status = invoke(array, counter, format, size, items, buffer);
                if (status == PDH_MORE_DATA) {
                    buffer = arena.allocate(Integer.toUnsignedLong(size.get(ValueLayout.JAVA_INT, 0)) * 2, 8);
                    continue;
                }
                if (status != 0) {
                    return false;
                }
                int n = items.get(ValueLayout.JAVA_INT, 0);
                for (int i = 0; i < n; i++) {
                    long base = (long) i * ITEM;
                    MemorySegment name = buffer.get(ValueLayout.ADDRESS, base).reinterpret(1024);
                    int itemStatus = buffer.get(ValueLayout.JAVA_INT, base + 8);
                    if (itemStatus != 0 && itemStatus != 0x00000001) { // PDH_CSTATUS_VALID_DATA, NEW_DATA
                        continue;
                    }
                    StringBuilder b = new StringBuilder();
                    for (long c = 0; c < 1024; c += 2) {
                        char ch = name.get(ValueLayout.JAVA_CHAR, c);
                        if (ch == 0) {
                            break;
                        }
                        b.append(ch);
                    }
                    out.put(b.toString(), buffer.get(ValueLayout.JAVA_DOUBLE, base + 16));
                }
                return true;
            }
            return false;
        }

        void sample(OsCounters owner, Counters into) {
            invoke(collect, query);
            Map<String, Double> values = new HashMap<>();
            if (read(cores, PDH_FMT_DOUBLE, values)) {
                for (Map.Entry<String, Double> e : values.entrySet()) {
                    String core = e.getKey().equals("_Total") ? "total" : e.getKey();
                    into.set(owner.id(into, "cpu.core." + core, "%"), e.getValue());
                }
            } else {
                into.unavailable("per-core load: PDH \\Processor(*)\\% Processor Time gave nothing");
            }
            values.clear();
            if (read(engines, PDH_FMT_DOUBLE | PDH_FMT_NOCAP100, values) && !values.isEmpty()) {
                Map<String, Double> perType = new HashMap<>();
                Map<String, Double> mine = new HashMap<>();
                gpuEngines(values, self, perType, mine);
                for (Map.Entry<String, Double> e : perType.entrySet()) {
                    into.set(owner.id(into, "gpu.engine." + clean(e.getKey()), "%"), e.getValue());
                }
                for (Map.Entry<String, Double> e : mine.entrySet()) {
                    into.set(owner.id(into, "gpu.engine.self." + clean(e.getKey()), "%"), e.getValue());
                }
            } else {
                into.unavailable("GPU engine load: PDH \\GPU Engine(*)\\Utilization Percentage gave nothing");
            }
        }

        private static String clean(String type) {
            return OsCounters.clean(type);
        }
    }

    /**
     * PDH's {@code GPU Engine} instances ({@code pid_<p>_luid_<l>_phys_<n>_eng_<e>_engtype_<type>}) and their
     * utilization summed per engine over the processes, then the busiest engine of each type ({@code perType}), and
     * the process {@code self} ({@code pid_<p>_}) per type ({@code mine}).
     */
    static void gpuEngines(Map<String, Double> values, String self, Map<String, Double> perType,
        Map<String, Double> mine) {
        Map<String, Double> perEngine = new HashMap<>();
        for (Map.Entry<String, Double> e : values.entrySet()) {
            String instance = e.getKey();
            int t = instance.indexOf("_engtype_");
            int l = instance.indexOf("_luid_");
            if (t < 0 || l < 0) {
                continue;
            }
            String type = instance.substring(t + "_engtype_".length());
            perEngine.merge(instance.substring(l, t) + "|" + type, e.getValue(), Double::sum);
            if (instance.startsWith(self)) {
                mine.merge(type, e.getValue(), Double::sum);
            }
        }
        for (Map.Entry<String, Double> e : perEngine.entrySet()) {
            perType.merge(e.getKey().substring(e.getKey().indexOf('|') + 1), e.getValue(), Math::max);
        }
    }

    static String clean(String type) {
        return type.replace(' ', '_');
    }
}
