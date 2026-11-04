package com.truesim.engine.app.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every system's counter readers on every system: Linux's over fixture folders standing in for {@code /proc} and
 * {@code /sys}, Windows' over a fake PDH and PDH's item array laid out in memory, the process memory readers' answers,
 * a system without readers. So the readers' branches run wherever the tests do.
 */
class OsCountersTest {

    /** The counters a source declared and their values after one sample. */
    private static Map<String, Double> sample(Counters counters) {
        counters.sampleNow();
        double[] values = new double[counters.count()];
        counters.read(values);
        Map<String, Double> out = new HashMap<>();
        for (int i = 0; i < counters.count(); i++) {
            out.put(counters.name(i), values[i]);
        }
        return out;
    }

    @Test
    void linuxReadsProcAndSysFolders(@TempDir Path root) throws Exception {
        Path proc = root.resolve("proc");
        Path sys = root.resolve("sys");
        Files.createDirectories(proc.resolve("self/fdinfo"));
        Files.createDirectories(sys.resolve("class/drm/card0/device"));
        Files.createDirectories(sys.resolve("class/drm/card1")); // no busy file: an iGPU without it
        Files.writeString(proc.resolve("stat"), "cpu  20 0 20 160 0\ncpu0 10 0 10 80 0 0 0 0\ncpu1 10 0 10 80\n");
        Files.writeString(sys.resolve("class/drm/card0/device/gpu_busy_percent"), "42\n");
        Files.writeString(proc.resolve("self/fdinfo/3"), "pos:\t0\ndrm-engine-gfx:\t1000000 ns\n");
        Files.writeString(proc.resolve("self/fdinfo/4"), "pos:\t0\nflags:\t02\n");
        try (Counters counters = new Counters()) {
            counters.add(new OsCounters("Linux", proc, sys, null, 1)).detailed(true);
            Map<String, Double> first = sample(counters);
            assertThat(first).containsEntry("gpu.busy.card0", 42.0);
            Files.writeString(proc.resolve("stat"), "cpu0 30 0 10 90 0 0 0 0\ncpu1 10 0 10 80\n");
            Files.writeString(proc.resolve("self/fdinfo/3"), "drm-engine-gfx:\t900000000 ns\n");
            Thread.sleep(5);
            Map<String, Double> second = sample(counters);
            assertThat(second.get("cpu.core.0")).isCloseTo(200.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(second).doesNotContainKey("cpu.core.1"); // no time passed on it
            assertThat(second.get("gpu.engine.self.gfx")).isPositive();
            assertThat(counters.unavailable()).isEmpty();
        }
        // a Linux without DRM or a readable /proc/stat: each said once
        try (Counters counters = new Counters()) {
            counters.add(new OsCounters("Linux", root.resolve("nothing"), root.resolve("nothing"), null, 1))
                .detailed(true);
            sample(counters);
            assertThat(counters.unavailable()).hasSize(3);
        }
    }

    /** A PDH answering what it is given. */
    private record FakePdh(Map<String, Double> cores, Map<String, Double> engines) implements OsCounters.PdhSource {
        @Override
        public void collect() {
            // nothing to take
        }

        @Override
        public boolean cores(Map<String, Double> out) {
            out.putAll(cores);
            return !cores.isEmpty();
        }

        @Override
        public boolean engines(Map<String, Double> out) {
            out.putAll(engines);
            return engines != null;
        }
    }

    @Test
    void windowsReadsPdhsCountersPerCoreAndPerEngine() {
        Map<String, Double> cores = Map.of("0", 25.0, "1", 75.0, "_Total", 50.0);
        Map<String, Double> engines = Map.of("pid_7_luid_0x0_0x1_phys_0_eng_0_engtype_3D", 30.0,
            "pid_8_luid_0x0_0x1_phys_0_eng_0_engtype_3D", 20.0,
            "pid_7_luid_0x0_0x1_phys_0_eng_3_engtype_Video Decode", 5.0);
        try (Counters counters = new Counters()) {
            counters.add(new OsCounters("Windows 11", Path.of("x"), Path.of("x"), new FakePdh(cores, engines), 7))
                .detailed(true);
            Map<String, Double> v = sample(counters);
            assertThat(v).containsEntry("cpu.core.0", 25.0).containsEntry("cpu.core.total", 50.0)
                .containsEntry("gpu.engine.3D", 50.0).containsEntry("gpu.engine.Video_Decode", 5.0)
                .containsEntry("gpu.engine.self.3D", 30.0);
            assertThat(counters.unavailable()).isEmpty();
        }
        try (Counters counters = new Counters()) {
            counters.add(new OsCounters("Windows 11", Path.of("x"), Path.of("x"), new FakePdh(Map.of(), Map.of()),
                7)).detailed(true);
            sample(counters);
            assertThat(counters.unavailable()).hasSize(2);
        }
    }

    @Test
    void pdhsItemArrayIsReadByNameAndStatus() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(3L * 24, 8);
            String[] names = {"0", "1", "_Total"};
            int[] statuses = {0, 1, 0xC0000BC6}; // valid, new data, no data
            for (int i = 0; i < 3; i++) {
                MemorySegment name = arena.allocateFrom(names[i] + "\0", StandardCharsets.UTF_16LE);
                buffer.set(ValueLayout.ADDRESS, i * 24L, name);
                buffer.set(ValueLayout.JAVA_INT, i * 24L + 8, statuses[i]);
                buffer.set(ValueLayout.JAVA_DOUBLE, i * 24L + 16, 10.0 * (i + 1));
            }
            Map<String, Double> out = new HashMap<>();
            OsCounters.items(buffer, 3, out);
            assertThat(out).containsOnly(Map.entry("0", 10.0), Map.entry("1", 20.0));
        }
        OsCounters.checked(0, "fine");
        assertThatIllegalStateException().isThrownBy(() -> OsCounters.checked(0x800007D2, "PdhSomething"))
            .withMessageContaining("800007d2");
    }

    @Test
    void aSystemWithoutReadersSaysSoAndOnlyItsOwnPdhOpens() {
        try (Counters counters = new Counters()) {
            counters.add(new OsCounters("Haiku", Path.of("x"), Path.of("x"), null, 1)).detailed(true);
            sample(counters);
            assertThat(counters.unavailable()).singleElement().asString().contains("no reader for haiku");
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        try (Counters counters = new Counters()) {
            counters.add(new OsCounters("Windows 11", Path.of("x"), Path.of("x"), null, 1)).detailed(true);
            sample(counters);
            // the real PDH: there on Windows (its counters read), not loadable elsewhere (said once)
            assertThat(counters.unavailable().stream().anyMatch(s -> s.contains("PDH not usable")))
                .isEqualTo(!windows);
        }
    }

    @Test
    void processMemoryReadersReadWhatTheirSystemsAnswer(@TempDir Path root) throws Exception {
        Path statm = Files.createTempFile("statm", ""); // the reader keeps it open: not in the deleted folder
        statm.toFile().deleteOnExit();
        Files.writeString(statm, "1000 250 30 1 0 100 0\n");
        ProcessMemory linux = ProcessMemory.create("Linux", statm);
        assertThat(linux.available()).isTrue();
        assertThat(linux.why()).isEmpty();
        assertThat(linux.resident()).isEqualTo(250.0 * ProcessMemory.pageSize());
        ProcessMemory gone = ProcessMemory.create("Linux", root.resolve("missing"));
        assertThat(gone.available()).isFalse();
        assertThat(gone.why()).contains("not readable");
        assertThat(gone.resident()).isNaN();
        assertThat(ProcessMemory.create("Plan 9", statm).why()).contains("no reader");
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        ProcessMemory windows = ProcessMemory.create("Windows 11", statm);
        assertThat(windows.available()).isEqualTo(os.startsWith("windows"));
        ProcessMemory mac = ProcessMemory.create("Mac OS X", statm);
        assertThat(mac.available()).isEqualTo(os.startsWith("mac"));
        if (windows.available()) {
            assertThat(windows.resident()).isGreaterThan(1e6);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment counters = arena.allocate(ProcessMemory.COUNTERS_BYTES, 8);
            counters.set(ValueLayout.JAVA_LONG, 16, 123_456_789L);
            assertThat(ProcessMemory.workingSet(1, counters)).isEqualTo(123_456_789.0);
            assertThat(ProcessMemory.workingSet(0, counters)).isNaN();
            MemorySegment info = arena.allocate(64, 8);
            info.set(ValueLayout.JAVA_LONG, 8, 987_654L);
            assertThat(ProcessMemory.taskResident(0, info)).isEqualTo(987_654.0);
            assertThat(ProcessMemory.taskResident(5, info)).isNaN();
        }
    }
}
