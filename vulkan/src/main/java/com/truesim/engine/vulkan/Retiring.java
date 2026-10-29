package com.truesim.engine.vulkan;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * GPU resources no longer used from now on, freed once no frame in flight of a {@link FrameSlots} can read them: a
 * buffer replaced by a bigger one, a removed texture's image and bindless slot. Each owner keeps one, {@link #add}s
 * what it lets go of, {@link #release}s now and then (when it adds or removes, or once a frame), and closes it with
 * itself, which frees the rest at once: the caller has waited for the GPU then.
 *
 * <pre>{@code
 * Retiring retiring = new Retiring(graph.frameSlots());
 * retiring.add(oldBuffer);                                  // frames in flight may still read it
 * retiring.add(() -> bindless.removeSampled(slot));         // a slot is reused only once no frame samples it
 * retiring.release();                                       // frees what no frame can read any more
 * }</pre>
 *
 * <p>Resources are freed in the order they were added. With {@link FrameSlots#single()}, whose users wait for the GPU
 * between frames, {@link #release} frees everything. Safe to use from several threads (frame graph passes recording
 * in parallel); releasing allocates nothing.
 */
public final class Retiring implements AutoCloseable {

    private final FrameSlots slots;
    private AutoCloseable[] resources = new AutoCloseable[8];
    private long[] retireAt = new long[8];
    private int count;
    private boolean closed;

    /** Resources retired from frames of {@code slots}. */
    public Retiring(FrameSlots slots) {
        this.slots = java.util.Objects.requireNonNull(slots, "slots");
    }

    /** Frees {@code resource} once no frame in flight now can read it; at once if this is closed. */
    @AllowAllocation("retiring, rarely")
    public synchronized void add(AutoCloseable resource) {
        java.util.Objects.requireNonNull(resource, "resource");
        if (closed) {
            close(resource);
            return;
        }
        if (count == resources.length) {
            resources = Arrays.copyOf(resources, 2 * count);
            retireAt = Arrays.copyOf(retireAt, 2 * count);
        }
        resources[count] = resource;
        retireAt[count++] = slots.retireAt();
    }

    /** Frees what no frame in flight can read any more, oldest first. Allocates nothing. */
    public synchronized void release() {
        int freed = 0;
        while (freed < count && slots.isFinished(retireAt[freed])) {
            freed++;
        }
        if (freed == 0) {
            return;
        }
        AutoCloseable[] freeing = resources;
        int first = 0;
        try {
            while (first < freed) {
                close(freeing[first++]);
            }
        } finally {
            System.arraycopy(resources, first, resources, 0, count - first);
            System.arraycopy(retireAt, first, retireAt, 0, count - first);
            Arrays.fill(resources, count - first, count, null);
            count -= first;
        }
    }

    /** How many resources wait for their frames. */
    public synchronized int count() {
        return count;
    }

    /** Frees everything waiting, at once: call when the GPU no longer runs the frames. */
    @Override
    public synchronized void close() {
        closed = true;
        int left = count;
        count = 0;
        for (int i = 0; i < left; i++) {
            AutoCloseable resource = resources[i];
            resources[i] = null;
            close(resource);
        }
    }

    private static void close(AutoCloseable resource) {
        try {
            resource.close();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not free " + resource, e);
        }
    }
}
