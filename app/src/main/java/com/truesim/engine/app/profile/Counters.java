package com.truesim.engine.app.profile;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The profiler's counters: named values with units ({@code memory.heap} in bytes, {@code cpu.process} in %), sampled
 * by {@link Source}s on a thread of their own ({@code profiler-counters}), so reading the JVM's beans, the operating
 * system and the GPU's memory costs the frame nothing; the main thread copies the newest samples ({@link #read}).
 * Sources are cheap (always on while the profiler shows) or detailed (only while the detailed view shows, by
 * {@link #detailed(boolean)}). A source that cannot run on this machine says so once ({@link #unavailable()}) and
 * is skipped.
 *
 * <pre>{@code
 * counters.add(new JvmCounters()).start(250);
 * int n = counters.read(values);     // each frame: values[i] of counter i, NaN until sampled
 * }</pre>
 */
public final class Counters implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Counters.class);

    /** What samples a group of counters, on the sampling thread. */
    public interface Source {
        /** Declares its counters in {@code into} (once, on the sampling thread before the first sample). */
        void declare(Counters into);

        /** Writes its counters' values with {@link Counters#set}; may allocate (it runs off the frame's thread). */
        void sample(Counters into);

        /** Whether it runs only while the detailed profiler shows. */
        default boolean detailed() {
            return false;
        }

        /** Frees what it holds (the sampling thread stopped). */
        default void close() {
            // nothing by default
        }
    }

    private final Object lock = new Object();
    private final List<Source> sources = new ArrayList<>();
    private final List<Source> declared = new ArrayList<>();
    private final List<String> unavailable = new ArrayList<>();
    private String[] names = new String[64];
    private String[] units = new String[64];
    private double[] values = new double[64]; // the sampling thread's
    private double[] published = new double[64]; // under lock
    private volatile int count;
    private volatile long sampled; // grows with each sample published
    private volatile boolean detailedOn;
    private volatile int intervalMillis = 250;
    private volatile boolean running;
    private Thread thread;

    /** Adds {@code source}: sampled from the next round. Returns this. */
    @AllowAllocation("set up once")
    public Counters add(Source source) {
        synchronized (lock) {
            sources.add(source);
        }
        return this;
    }

    /** Declares counter {@code name} in {@code unit} (from a source's {@link Source#declare}); returns its index. */
    @AllowAllocation("counters are declared once")
    public int counter(String name, String unit) {
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                if (names[i].equals(name)) {
                    return i;
                }
            }
            if (count == names.length) {
                names = Arrays.copyOf(names, count * 2);
                units = Arrays.copyOf(units, count * 2);
                values = Arrays.copyOf(values, count * 2);
                published = Arrays.copyOf(published, count * 2);
            }
            names[count] = name;
            units[count] = unit;
            values[count] = Double.NaN;
            published[count] = Double.NaN;
            count = count + 1;
            return count - 1;
        }
    }

    /** Sets counter {@code index} to {@code value} (from a source's {@link Source#sample}). */
    public void set(int index, double value) {
        values[index] = value;
    }

    /** Says a source cannot run here and why (shown once; the source is then skipped). */
    @AllowAllocation("reporting, once")
    public void unavailable(String what) {
        synchronized (lock) {
            if (!unavailable.contains(what)) {
                unavailable.add(what);
                LOG.info("Profiler counters: {}", what);
            }
        }
    }

    /** What was found unavailable on this machine, one line each. */
    @AllowAllocation("a snapshot")
    public List<String> unavailable() {
        synchronized (lock) {
            return List.copyOf(unavailable);
        }
    }

    /** How many counters are declared. */
    public int count() {
        return count;
    }

    /** Counter {@code i}'s name. */
    public String name(int i) {
        return names[i];
    }

    /** Counter {@code i}'s unit. */
    public String unit(int i) {
        return units[i];
    }

    /** Grows with every sample published. */
    public long sampled() {
        return sampled;
    }

    /**
     * Copies the newest published values into {@code out} (counter {@code i} at {@code out[i]}, NaN until sampled) and
     * returns how many were copied. Allocates nothing.
     */
    public int read(double[] out) {
        synchronized (lock) {
            int n = Math.min(out.length, count);
            System.arraycopy(published, 0, out, 0, n);
            return n;
        }
    }

    /** Samples the detailed sources too ({@code true}) or the cheap ones only. */
    public void detailed(boolean on) {
        detailedOn = on;
    }

    /** Starts sampling every {@code millis} milliseconds (again: changes the interval). Returns this. */
    @AllowAllocation("starting the sampler")
    public synchronized Counters start(int millis) {
        intervalMillis = Math.max(1, millis);
        if (thread == null) {
            running = true;
            thread = Thread.ofPlatform().daemon().name("profiler-counters").start(this::loop);
        }
        return this;
    }

    /** Whether the sampling thread runs. */
    public synchronized boolean started() {
        return thread != null;
    }

    /** Samples every source once, now, on the calling thread (tests; a first value before the thread's). */
    @AllowAllocation("sampling off the frame")
    public void sampleNow() {
        Source[] all;
        synchronized (lock) {
            all = sources.toArray(new Source[0]);
        }
        for (Source s : all) {
            if (!declared.contains(s)) {
                declared.add(s);
                try {
                    s.declare(this);
                } catch (RuntimeException | LinkageError e) {
                    unavailable(s.getClass().getSimpleName() + ": " + e);
                }
            }
            if (s.detailed() && !detailedOn) {
                continue;
            }
            try {
                s.sample(this);
            } catch (RuntimeException | LinkageError e) {
                unavailable(s.getClass().getSimpleName() + " failed: " + e);
            }
        }
        synchronized (lock) {
            System.arraycopy(values, 0, published, 0, count);
        }
        sampled++;
    }

    private void loop() {
        while (running) {
            long next = System.nanoTime() + intervalMillis * 1_000_000L;
            sampleNow();
            long left = next - System.nanoTime();
            if (left > 0) {
                LockSupport.parkNanos(left);
            }
        }
    }

    /** Stops the sampling thread and closes the sources. */
    @Override
    public void close() {
        Thread t;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
        }
        if (t != null) {
            LockSupport.unpark(t);
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lock) {
            for (Source s : sources) {
                s.close();
            }
        }
    }
}
