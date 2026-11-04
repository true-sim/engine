package com.truesim.engine.app.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** CPU zones per thread, the frame clock, the counters and the runtime's markers. */
class ProfilerTest {

    private static final Zone OUTER = Profiler.zone("test.outer");
    private static final Zone INNER = Profiler.zone("test.inner");

    /** What a drain handed over, as text. */
    private static final class Log implements ZoneSink {
        final List<String> events = new ArrayList<>();
        int lost;

        @Override
        public void begin(int thread, long nanos, int zone, int depth) {
            events.add(Profiler.threadName(thread) + " begin " + Profiler.zoneName(zone) + " " + depth);
        }

        @Override
        public void end(int thread, long nanos, int depth) {
            events.add(Profiler.threadName(thread) + " end " + depth);
        }

        @Override
        public void lost(int thread, long nanos) {
            lost++;
        }

        List<String> of(String thread) {
            return events.stream().filter(e -> e.startsWith(thread + " ")).toList();
        }
    }

    @AfterEach
    void off() {
        Profiler.zones(false);
        Profiler.frames(false);
        Profiler.discard();
    }

    @Test
    void zonesNestPerThreadAndDrainInOrder() throws Exception {
        Profiler.zones(true);
        Profiler.discard();
        Profiler.thread("test-main");
        Profiler.begin(OUTER);
        Profiler.begin(INNER);
        Profiler.end(INNER);
        Profiler.end(OUTER);
        Thread worker = Thread.ofPlatform().name("test-worker").start(() -> {
            Profiler.begin(INNER);
            Profiler.end(INNER);
        });
        worker.join();
        Log log = new Log();
        Profiler.drain(log);
        assertThat(log.of("test-main")).containsExactly("test-main begin test.outer 0",
            "test-main begin test.inner 1", "test-main end 1", "test-main end 0");
        assertThat(log.of("test-worker")).containsExactly("test-worker begin test.inner 0", "test-worker end 0");
        assertThat(Profiler.zone("test.outer").id()).isEqualTo(OUTER.id());
        assertThat(OUTER.name()).isEqualTo("test.outer");
        assertThat(OUTER.toString()).isEqualTo("test.outer");
        Log again = new Log();
        Profiler.drain(again);
        assertThat(again.events).as("drained once").isEmpty();
        // an end without a begin (the drain started inside a zone) is dropped
        Profiler.end(INNER);
        Profiler.drain(again);
        assertThat(again.events).isEmpty();
        boolean found = false;
        for (int i = 0; i < Profiler.threadCount(); i++) {
            if (Profiler.threadName(i).equals("test-worker")) {
                found = true;
                assertThat(Profiler.thread(i)).as("an ended thread").isNull();
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void offRecordsNothing() {
        Profiler.zones(false);
        Profiler.begin(OUTER);
        Profiler.end(OUTER);
        Log log = new Log();
        Profiler.drain(log);
        assertThat(log.events).isEmpty();
        assertThat(Profiler.zones()).isFalse();
    }

    @Test
    void aFullRingLosesTheOldestAndSaysSo() throws Exception {
        Profiler.zones(true);
        Profiler.ringEvents(64);
        CountDownLatch done = new CountDownLatch(1);
        Thread t = Thread.ofPlatform().name("test-flood").start(() -> {
            for (int i = 0; i < 100; i++) {
                Profiler.begin(OUTER);
                Profiler.end(OUTER);
            }
            done.countDown();
        });
        done.await();
        t.join();
        Log log = new Log();
        long lostBefore = Profiler.lostEvents();
        Profiler.drain(log);
        assertThat(log.lost).isEqualTo(1);
        assertThat(log.of("test-flood")).hasSize(64);
        assertThat(Profiler.lostEvents() - lostBefore).isEqualTo(200 - 64);
        Profiler.ringEvents(1 << 16);
    }

    @Test
    void theFrameClockMeasuresStartSubmitWaitAndInterval() throws Exception {
        Profiler.frames(true);
        assertThat(Profiler.frames()).isTrue();
        long t0 = System.nanoTime();
        Profiler.frameStart(t0);
        Profiler.waitBegin();
        Thread.sleep(2);
        Profiler.waitEnd();
        Profiler.submitted(41);
        Profiler.frameEnd();
        assertThat(Profiler.lastFrame()).isEqualTo(41);
        assertThat(Profiler.lastFrameStart()).isEqualTo(t0);
        assertThat(Profiler.lastFrameWait()).isGreaterThanOrEqualTo(1_000_000);
        assertThat(Profiler.lastFrameSubmit()).isGreaterThan(t0);
        assertThat(Profiler.lastFrameInterval()).isEqualTo(-1);
        Profiler.frameStart(t0 + 16_000_000);
        Profiler.frameEnd(); // no submit: its own number, the end as the submit
        assertThat(Profiler.lastFrame()).isGreaterThan(1_000_000_000L);
        assertThat(Profiler.lastFrameInterval()).isEqualTo(16_000_000);
        Profiler.frames(false);
        Profiler.frameStart(t0);
        Profiler.waitBegin();
        Profiler.waitEnd();
        Profiler.submitted(99);
        Profiler.frameEnd();
        assertThat(Profiler.lastFrame()).as("off: unchanged").isNotEqualTo(99);
    }

    @Test
    void theEngineTimesItsPhasesAsZones() {
        Profiler.zones(true);
        Profiler.frames(true);
        try (Engine engine = Engine.create(new Application() {
            @Override
            public void configure(AppConfig app) {
                app.id("profiler-test");
            }
        })) {
            Profiler.thread("engine-test");
            Profiler.discard();
            engine.step();
            Log log = new Log();
            Profiler.drain(log);
            String me = "engine-test";
            Profiler.thread(me); // this thread's lane (other tests may have named it)
            assertThat(log.of(me)).contains(me + " begin frame 0", me + " begin UPDATE 1", me + " begin LATE 1",
                me + " begin end frame 1");
            assertThat(Profiler.lastFrame()).isNotNegative();
        }
    }

    @Test
    void countersSampleTheJvmAndTheProcess() throws Exception {
        try (Counters counters = new Counters()) {
            counters.add(new JvmCounters()).add(new OsCounters());
            counters.detailed(true);
            Profiler.zones(true);
            Profiler.begin(OUTER); // a lane for the per-thread counters
            Profiler.end(OUTER);
            counters.sampleNow();
            Thread.sleep(150);
            counters.sampleNow();
            double[] values = new double[counters.count()];
            assertThat(counters.read(values)).isEqualTo(counters.count());
            assertThat(value(counters, values, "memory.heap")).isPositive();
            assertThat(value(counters, values, "threads.live")).isPositive();
            assertThat(value(counters, values, "cpu.process")).isNotNegative();
            assertThat(counters.unit(index(counters, "memory.heap"))).isEqualTo("bytes");
            if (ProcessMemory.create().available()) {
                assertThat(value(counters, values, "memory.resident")).isGreaterThan(1e6);
            }
            assertThat(counters.sampled()).isEqualTo(2);
            assertThat(counters.counter("memory.heap", "bytes")).isEqualTo(index(counters, "memory.heap"));
            System.out.println("counters here: " + counters.count() + ", unavailable: " + counters.unavailable());
            counters.start(20);
            assertThat(counters.started()).isTrue();
            long before = counters.sampled();
            Thread.sleep(200);
            assertThat(counters.sampled()).isGreaterThan(before);
        }
    }

    @Test
    void aFailingSourceIsReportedAndSkipped() {
        try (Counters counters = new Counters()) {
            counters.add(new Counters.Source() {
                @Override
                public void declare(Counters into) {
                    into.counter("broken", "");
                }

                @Override
                public void sample(Counters into) {
                    throw new IllegalStateException("no");
                }
            });
            counters.sampleNow();
            assertThat(counters.unavailable()).anyMatch(s -> s.contains("failed"));
        }
    }

    @Test
    void theRuntimesGarbageCollectionsBecomeMarkers() throws Exception {
        try (RuntimeEvents events = new RuntimeEvents()) {
            if (!events.start()) {
                System.out.println("JFR unavailable: " + events.failure());
                return;
            }
            assertThat(events.start()).as("again: running").isTrue();
            List<String> seen = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int i = 0; i < 50 && seen.stream().noneMatch(s -> s.startsWith("GC")); i++) {
                System.gc();
                Thread.sleep(100);
                events.drain((kind, name, start, end) -> seen.add(kind + " " + name + " " + (end >= start)
                    + " " + (start > t0 - 60_000_000_000L)));
            }
            assertThat(seen).anyMatch(s -> s.startsWith("GC ") && s.endsWith("true true"));
            assertThat(events.failure()).isEmpty();
        }
    }

    private static int index(Counters c, String name) {
        for (int i = 0; i < c.count(); i++) {
            if (c.name(i).equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static double value(Counters c, double[] values, String name) {
        return values[index(c, name)];
    }

    @Test
    void linuxAndWindowsCountersParseWhatTheSystemsWrite() {
        java.util.Map<String, long[]> last = new java.util.HashMap<>();
        java.util.Map<String, Double> loads = new java.util.HashMap<>();
        OsCounters.cores(List.of("cpu  10 0 10 80 0 0 0 0", "cpu0 5 0 5 40 0 0 0 0", "cpu1 5 0 5 40 0", "intr 1"),
            last, loads::put);
        assertThat(loads).isEmpty(); // the first read only takes the totals
        OsCounters.cores(List.of("cpu0 15 0 5 70 10 0 0 0", "cpu1 5 0 5 40 0"), last, loads::put);
        assertThat(loads).containsOnlyKeys("0");
        assertThat(loads.get("0")).isCloseTo(20.0, org.assertj.core.data.Offset.offset(1e-9)); // 10 busy of 50
        java.util.Map<String, Long> engines = new java.util.HashMap<>();
        OsCounters.fdinfo("pos:\t0\ndrm-driver:\tamdgpu\ndrm-engine-gfx:\t1500 ns\ndrm-engine-compute:\t20 ns\n"
            + "drm-memory-vram:\t1 KiB\n", engines);
        OsCounters.fdinfo("drm-engine-gfx:\t900 ns\n", engines);
        assertThat(engines).containsEntry("gfx", 1500L).containsEntry("compute", 20L).hasSize(2);
        java.util.Map<String, Double> pdh = new java.util.HashMap<>();
        pdh.put("pid_10_luid_0x0_0x1_phys_0_eng_0_engtype_3D", 30.0);
        pdh.put("pid_11_luid_0x0_0x1_phys_0_eng_0_engtype_3D", 25.0);
        pdh.put("pid_10_luid_0x0_0x1_phys_0_eng_1_engtype_3D", 5.0);
        pdh.put("pid_10_luid_0x0_0x1_phys_0_eng_2_engtype_Video Decode", 4.0);
        pdh.put("odd", 1.0);
        java.util.Map<String, Double> perType = new java.util.HashMap<>();
        java.util.Map<String, Double> mine = new java.util.HashMap<>();
        OsCounters.gpuEngines(pdh, "pid_10_", perType, mine);
        assertThat(perType).containsEntry("3D", 55.0).containsEntry("Video Decode", 4.0);
        assertThat(mine).containsEntry("3D", 35.0);
        assertThat(OsCounters.clean("Video Decode")).isEqualTo("Video_Decode");
        byte[] statm = "123456 7890 300 1 0 200 0\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(ProcessMemory.statmPages(statm, statm.length)).isEqualTo(7890);
        assertThat(new OsCounters().detailed()).isTrue();
    }
}
