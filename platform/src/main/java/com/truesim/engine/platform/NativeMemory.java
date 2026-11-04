package com.truesim.engine.platform;

import com.truesim.annotations.AllowAllocation;
import java.lang.reflect.Constructor;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryUtil;

/**
 * The engine's own accounting of native memory allocated through LWJGL ({@code MemoryUtil.nmemAlloc} and the rest, the
 * memory of structs, buffers and staging the engine makes): an allocator wrapping LWJGL's standard one that keeps each
 * live allocation's size in a table of its own, so {@link #bytes()} and {@link #count()} say what is allocated now.
 * LWJGL takes its allocator once, when {@code MemoryUtil} first loads: {@link #install()} must run before anything uses
 * LWJGL (the engine's {@code Desktop} and {@code Rendering} plugins install it when their classes load), else
 * {@link #active()} stays false. Memory native libraries allocate themselves (VMA's, the driver's, FreeType's) is not
 * counted; frees of pointers it did not allocate pass through.
 */
public final class NativeMemory {

    private static volatile Counting installed;

    private NativeMemory() {
    }

    /** Puts the counting allocator in place if LWJGL's memory has not been set up yet. Idempotent. */
    @AllowAllocation("once, at startup")
    public static synchronized void install() {
        if (installed != null) {
            return;
        }
        Counting counting = new Counting();
        try {
            Configuration.MEMORY_ALLOCATOR.set(counting);
            installed = counting;
        } catch (RuntimeException | LinkageError e) {
            // LWJGL refused: not counted
        }
    }

    /** Whether LWJGL allocates through the counting allocator. */
    public static boolean active() {
        Counting c = installed;
        if (c == null) {
            return false;
        }
        try {
            return MemoryUtil.getAllocator() == c;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Bytes LWJGL holds allocated now (0 when not {@link #active()}). */
    public static long bytes() {
        Counting c = installed;
        return c == null ? 0 : c.bytes;
    }

    /** Allocations LWJGL holds now. */
    public static long count() {
        Counting c = installed;
        return c == null ? 0 : c.live;
    }

    /** LWJGL's allocator wrapped: sizes kept by address in an open-addressing table. */
    private static final class Counting implements MemoryUtil.MemoryAllocator {
        private MemoryUtil.MemoryAllocator delegate;
        private long[] keys = new long[1 << 12];
        private long[] sizes = new long[1 << 12];
        private int used;
        private volatile long bytes;
        private volatile long live;

        @AllowAllocation("LWJGL's standard allocator, made once")
        private synchronized MemoryUtil.MemoryAllocator delegate() {
            if (delegate == null) {
                try {
                    Class<?> c = Class.forName("org.lwjgl.system.MemoryManage$StdlibAllocator");
                    Constructor<?> k = c.getDeclaredConstructor();
                    k.setAccessible(true);
                    delegate = (MemoryUtil.MemoryAllocator) k.newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("LWJGL's standard allocator is not reachable", e);
                }
            }
            return delegate;
        }

        @Override
        public long getMalloc() {
            return delegate().getMalloc();
        }

        @Override
        public long getCalloc() {
            return delegate().getCalloc();
        }

        @Override
        public long getRealloc() {
            return delegate().getRealloc();
        }

        @Override
        public long getFree() {
            return delegate().getFree();
        }

        @Override
        public long getAlignedAlloc() {
            return delegate().getAlignedAlloc();
        }

        @Override
        public long getAlignedFree() {
            return delegate().getAlignedFree();
        }

        @Override
        public long malloc(long size) {
            return add(delegate().malloc(size), size);
        }

        @Override
        public long calloc(long num, long size) {
            return add(delegate().calloc(num, size), num * size);
        }

        @Override
        public long realloc(long ptr, long size) {
            remove(ptr);
            return add(delegate().realloc(ptr, size), size);
        }

        @Override
        public void free(long ptr) {
            remove(ptr);
            delegate().free(ptr);
        }

        @Override
        public long aligned_alloc(long alignment, long size) {
            return add(delegate().aligned_alloc(alignment, size), size);
        }

        @Override
        public void aligned_free(long ptr) {
            remove(ptr);
            delegate().aligned_free(ptr);
        }

        private synchronized long add(long ptr, long size) {
            if (ptr == 0) {
                return 0;
            }
            if ((used + 1) * 2 > keys.length) {
                grow();
            }
            int i = slot(ptr, keys.length);
            while (keys[i] != 0) {
                i = (i + 1) & (keys.length - 1);
            }
            keys[i] = ptr;
            sizes[i] = size;
            used++;
            bytes += size;
            live++;
            return ptr;
        }

        private synchronized void remove(long ptr) {
            if (ptr == 0) {
                return;
            }
            int mask = keys.length - 1;
            int i = slot(ptr, keys.length);
            while (keys[i] != 0 && keys[i] != ptr) {
                i = (i + 1) & mask;
            }
            if (keys[i] == 0) {
                return; // not ours: a native library's
            }
            bytes -= sizes[i];
            live--;
            keys[i] = 0;
            used--;
            // reinsert the cluster after it (linear probing deletion)
            int j = (i + 1) & mask;
            while (keys[j] != 0) {
                long k = keys[j];
                long s = sizes[j];
                keys[j] = 0;
                int t = slot(k, keys.length);
                while (keys[t] != 0) {
                    t = (t + 1) & mask;
                }
                keys[t] = k;
                sizes[t] = s;
                j = (j + 1) & mask;
            }
        }

        private static int slot(long ptr, int length) {
            long h = (ptr >>> 4) * 0x9E3779B97F4A7C15L;
            return (int) (h >>> 32) & (length - 1);
        }

        @AllowAllocation("the table grows to the most live allocations, rarely")
        private void grow() {
            long[] k = keys;
            long[] s = sizes;
            keys = new long[k.length * 2];
            sizes = new long[k.length * 2];
            for (int i = 0; i < k.length; i++) {
                if (k[i] != 0) {
                    int t = slot(k[i], keys.length);
                    while (keys[t] != 0) {
                        t = (t + 1) & (keys.length - 1);
                    }
                    keys[t] = k[i];
                    sizes[t] = s[i];
                }
            }
        }
    }
}
