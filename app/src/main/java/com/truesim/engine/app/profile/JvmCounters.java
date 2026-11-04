package com.truesim.engine.app.profile;

import com.truesim.annotations.AllowAllocation;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;

/**
 * The JVM's and the process's cheap counters: process and system CPU load ({@code OperatingSystemMXBean}), the heap
 * used and committed, non-heap used, direct and mapped buffers, garbage collections and their time, live threads, the
 * process's resident memory ({@link ProcessMemory}, through FFM), and per profiler thread (each lane of
 * {@link Profiler}) its CPU load and allocation rate ({@code ThreadMXBean}).
 */
@AllowAllocation("runs on the counters thread, off the frame")
public final class JvmCounters implements Counters.Source {

    private final com.sun.management.OperatingSystemMXBean os;
    private final com.sun.management.ThreadMXBean threads;
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final ProcessMemory rss = ProcessMemory.create();
    private int processCpu;
    private int systemCpu;
    private int heapUsed;
    private int heapCommitted;
    private int nonHeap;
    private int direct;
    private int mapped;
    private int gcCount;
    private int gcTime;
    private int liveThreads;
    private int resident;
    private int[] threadCpu = new int[0];
    private int[] threadAlloc = new int[0];
    private long[] lastCpu = new long[0];
    private long[] lastAlloc = new long[0];
    private long lastSample;

    /** The counters of this JVM. */
    @AllowAllocation("made once")
    public JvmCounters() {
        os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (threads.isThreadCpuTimeSupported() && !threads.isThreadCpuTimeEnabled()) {
            threads.setThreadCpuTimeEnabled(true);
        }
        if (threads.isThreadAllocatedMemorySupported() && !threads.isThreadAllocatedMemoryEnabled()) {
            threads.setThreadAllocatedMemoryEnabled(true);
        }
    }

    @Override
    public void declare(Counters into) {
        processCpu = into.counter("cpu.process", "%");
        systemCpu = into.counter("cpu.system", "%");
        heapUsed = into.counter("memory.heap", "bytes");
        heapCommitted = into.counter("memory.heapCommitted", "bytes");
        nonHeap = into.counter("memory.nonHeap", "bytes");
        direct = into.counter("memory.direct", "bytes");
        mapped = into.counter("memory.mapped", "bytes");
        resident = into.counter("memory.resident", "bytes");
        gcCount = into.counter("gc.collections", "");
        gcTime = into.counter("gc.time", "ms");
        liveThreads = into.counter("threads.live", "");
        if (!rss.available()) {
            into.unavailable("process resident memory: " + rss.why());
        }
        if (!threads.isThreadCpuTimeSupported()) {
            into.unavailable("per-thread CPU time: not supported by this JVM");
        }
    }

    @Override
    @AllowAllocation("sampling off the frame (the beans' usage objects)")
    public void sample(Counters into) {
        into.set(processCpu, os.getProcessCpuLoad() * 100);
        into.set(systemCpu, os.getCpuLoad() * 100);
        into.set(heapUsed, memory.getHeapMemoryUsage().getUsed());
        into.set(heapCommitted, memory.getHeapMemoryUsage().getCommitted());
        into.set(nonHeap, memory.getNonHeapMemoryUsage().getUsed());
        for (BufferPoolMXBean pool : pools) {
            if (pool.getName().equals("direct")) {
                into.set(direct, pool.getMemoryUsed());
            } else if (pool.getName().equals("mapped")) {
                into.set(mapped, pool.getMemoryUsed());
            }
        }
        long count = 0;
        long time = 0;
        for (GarbageCollectorMXBean gc : collectors) {
            count += Math.max(0, gc.getCollectionCount());
            time += Math.max(0, gc.getCollectionTime());
        }
        into.set(gcCount, count);
        into.set(gcTime, time);
        into.set(liveThreads, threads.getThreadCount());
        into.set(resident, rss.available() ? rss.resident() : Double.NaN);
        perThread(into);
    }

    private void perThread(Counters into) {
        int n = Profiler.threadCount();
        if (threadCpu.length < n) {
            int old = threadCpu.length;
            threadCpu = java.util.Arrays.copyOf(threadCpu, n);
            threadAlloc = java.util.Arrays.copyOf(threadAlloc, n);
            lastCpu = java.util.Arrays.copyOf(lastCpu, n);
            lastAlloc = java.util.Arrays.copyOf(lastAlloc, n);
            for (int i = old; i < n; i++) {
                String name = Profiler.threadName(i);
                threadCpu[i] = into.counter("thread.cpu." + name, "%");
                threadAlloc[i] = into.counter("thread.alloc." + name, "bytes/s");
                lastCpu[i] = -1;
                lastAlloc[i] = -1;
            }
        }
        long now = System.nanoTime();
        double seconds = lastSample == 0 ? 0 : (now - lastSample) / 1e9;
        lastSample = now;
        for (int i = 0; i < n; i++) {
            Thread t = Profiler.thread(i);
            if (t == null) {
                into.set(threadCpu[i], Double.NaN);
                into.set(threadAlloc[i], Double.NaN);
                continue;
            }
            long id = t.threadId();
            long cpu = threads.isThreadCpuTimeSupported() ? threads.getThreadCpuTime(id) : -1;
            long alloc = threads.isThreadAllocatedMemorySupported() ? threads.getThreadAllocatedBytes(id) : -1;
            if (seconds > 0 && cpu >= 0 && lastCpu[i] >= 0) {
                into.set(threadCpu[i], (cpu - lastCpu[i]) / 1e9 / seconds * 100);
            }
            if (seconds > 0 && alloc >= 0 && lastAlloc[i] >= 0) {
                into.set(threadAlloc[i], (alloc - lastAlloc[i]) / seconds);
            }
            lastCpu[i] = cpu;
            lastAlloc[i] = alloc;
        }
    }
}
