package com.truesim.engine.ui.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.console.LogInbox;
import com.truesim.ui.console.LogLevel;
import com.truesim.ui.console.LogRing;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;

/**
 * A log flood through SLF4J into the console ({@code TRUESIM_BENCHMARK=true}): records formatted by the provider and
 * written into the inbox, from one thread and from four, and the frame's drain of 1,000 records into the ring.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class ConsoleLogBenchmarkTest {

    @Test
    void aLogFloodThroughSlf4jIntoTheConsole() throws Exception {
        ConsoleLogProvider provider = new ConsoleLogProvider();
        provider.initialize(null); // the console alone: what the console path costs
        Logger logger = provider.getLoggerFactory().getLogger("com.example.Flood");
        LogInbox inbox = ConsoleLog.inbox();
        LogRing ring = new LogRing(10_000);
        int captured = ConsoleLog.captureLevel();
        ConsoleLog.captureLevel(LogLevel.INFO);
        try {
            int frames = 400;
            double[] logMicros = new double[frames];
            double[] drainMicros = new double[frames];
            for (int f = -100; f < frames; f++) {
                long t0 = System.nanoTime();
                for (int i = 0; i < 1000; i++) {
                    logger.info("entity {} moved to {}", i, f);
                }
                long t1 = System.nanoTime();
                inbox.drainTo(ring);
                long t2 = System.nanoTime();
                if (f >= 0) {
                    logMicros[f] = (t1 - t0) / 1e3;
                    drainMicros[f] = (t2 - t1) / 1e3;
                }
            }
            report("1,000 INFO records a frame, one thread: logging", logMicros);
            report("1,000 records drained into the ring", drainMicros);
            // four threads at once, 25,000 records each
            int threads = 4;
            int each = 25_000;
            CountDownLatch start = new CountDownLatch(1);
            Thread[] workers = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                workers[t] = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int i = 0; i < each; i++) {
                        logger.info("worker record {}", i);
                    }
                });
                workers[t].start();
            }
            long t0 = System.nanoTime();
            start.countDown();
            int drained = 0;
            boolean alive = true;
            while (alive) {
                drained += inbox.drainTo(ring);
                alive = false;
                for (Thread w : workers) {
                    alive |= w.isAlive();
                }
            }
            drained += inbox.drainTo(ring);
            double ms = (System.nanoTime() - t0) / 1e6;
            System.out.printf("%,d records from %d threads while draining: %.1f ms, %.0f ns a record, %,d drained,"
                + " %,d dropped in all%n", threads * each, threads, ms, ms * 1e6 / (threads * each), drained,
                inbox.dropped());
            // warnings look up their source: a stack walk each
            double[] warnMicros = new double[100];
            for (int f = -20; f < 100; f++) {
                long w0 = System.nanoTime();
                for (int i = 0; i < 100; i++) {
                    logger.warn("slow {}", i);
                }
                if (f >= 0) {
                    warnMicros[f] = (System.nanoTime() - w0) / 1e3;
                }
                inbox.drainTo(ring);
            }
            report("100 WARN records with their source", warnMicros);
            assertThat(ring.size()).isPositive();
        } finally {
            ConsoleLog.captureLevel(captured);
        }
    }

    private static void report(String what, double[] micros) {
        double[] sorted = micros.clone();
        Arrays.sort(sorted);
        System.out.printf("%s: %.1f us median, %.1f us p99%n", what, sorted[sorted.length / 2],
            sorted[(int) (sorted.length * 0.99)]);
    }
}
