package com.truesim.engine.app.profile;

import com.truesim.annotations.AllowAllocation;
import java.time.Duration;
import java.time.Instant;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

/**
 * The runtime's pauses and work as timeline markers (detailed only), from a JFR {@link RecordingStream} in this
 * process: garbage collections ({@code jdk.GarbageCollection}: the collector's name), safepoints
 * ({@code jdk.SafepointBegin}: from the request to the end of the safepoint's operation) and compilations
 * ({@code jdk.Compilation}: {@code C1} or {@code C2} by tier). The stream runs on JFR's thread; events go into a ring of
 * plain values the main thread {@link #drain}s. Their times come as wall-clock instants and are moved onto
 * {@link System#nanoTime()}'s clock by an offset taken when the stream starts (a few microseconds off).
 */
@AllowAllocation("runs on JFR's thread, off the frame; the drain allocates nothing")
public final class RuntimeEvents implements AutoCloseable {

    /** What a marker is. */
    public enum Kind {
        /** A garbage collection. */
        GC,
        /** A safepoint. */
        SAFEPOINT,
        /** A JIT compilation. */
        JIT
    }

    /** Receives the markers drained. */
    public interface Sink {
        /** A marker of {@code kind} named {@code name} from {@code start} to {@code end} (nanoTime's clock). */
        void marker(Kind kind, String name, long start, long end);
    }

    private static final int CAPACITY = 4096;
    private final Object lock = new Object();
    private final int[] kinds = new int[CAPACITY];
    private final String[] names = new String[CAPACITY];
    private final long[] starts = new long[CAPACITY];
    private final long[] ends = new long[CAPACITY];
    private long head;
    private long tail;
    private RecordingStream stream;
    private long offset; // nanoTime - epoch nanos
    private String failure = "";
    private static final Kind[] KINDS = Kind.values();

    /** Starts the stream; false (and {@link #failure()}) if JFR cannot run here. */
    public synchronized boolean start() {
        if (stream != null) {
            return true;
        }
        try {
            Instant now = Instant.now();
            long nano = System.nanoTime();
            offset = nano - (now.getEpochSecond() * 1_000_000_000L + now.getNano());
            RecordingStream s = new RecordingStream();
            s.enable("jdk.GarbageCollection").withoutThreshold();
            s.enable("jdk.SafepointBegin").withoutThreshold();
            s.enable("jdk.Compilation").withThreshold(Duration.ofMillis(1));
            s.setMaxAge(Duration.ofSeconds(10));
            s.onEvent("jdk.GarbageCollection", e -> add(Kind.GC, e.getString("name"), e));
            s.onEvent("jdk.SafepointBegin", e -> add(Kind.SAFEPOINT, "safepoint", e));
            s.onEvent("jdk.Compilation", e -> add(Kind.JIT, e.getShort("compileLevel") >= 4 ? "C2" : "C1", e));
            s.startAsync();
            stream = s;
            return true;
        } catch (RuntimeException | LinkageError e) {
            failure = "JFR: " + e;
            return false;
        }
    }

    /** Why the stream could not start, or empty. */
    public String failure() {
        return failure;
    }

    private void add(Kind kind, String name, RecordedEvent e) {
        Instant s = e.getStartTime();
        Instant f = e.getEndTime();
        long start = s.getEpochSecond() * 1_000_000_000L + s.getNano() + offset;
        long end = f.getEpochSecond() * 1_000_000_000L + f.getNano() + offset;
        synchronized (lock) {
            int i = (int) (head % CAPACITY);
            kinds[i] = kind.ordinal();
            names[i] = name.intern(); // a few distinct names: the sink keeps them
            starts[i] = start;
            ends[i] = end;
            head++;
            if (head - tail > CAPACITY) {
                tail = head - CAPACITY;
            }
        }
    }

    /** Hands the markers since the last drain to {@code sink}; allocates nothing. */
    public void drain(Sink sink) {
        synchronized (lock) {
            for (; tail < head; tail++) {
                int i = (int) (tail % CAPACITY);
                sink.marker(KINDS[kinds[i]], names[i], starts[i], ends[i]);
            }
        }
    }

    /** Stops the stream. */
    @Override
    public synchronized void close() {
        if (stream != null) {
            stream.close();
            stream = null;
        }
    }
}
