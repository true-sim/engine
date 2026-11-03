package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * The streaming thread (§2.14): decodes streamed clips into their voices' rings, never on the audio thread, keeping
 * each ring as full as it can and serving a ring that ran dry from where its voice is by now. Rings come from the game
 * thread ({@link #add}) and leave once closed. Started lazily; a daemon.
 */
@AllowAllocation("the streaming thread: not the audio thread")
public final class Streams implements AutoCloseable {

    /** Frames decoded at a time. */
    static final int CHUNK = 1_024;

    private final ConcurrentLinkedQueue<StreamRing> added = new ConcurrentLinkedQueue<>();
    private final List<StreamRing> rings = new ArrayList<>();
    private volatile boolean running;
    private Thread thread;

    /** Hands {@code ring} to the streaming thread, starting it if need be. */
    synchronized void add(StreamRing ring) {
        added.add(ring);
        if (thread == null) {
            running = true;
            thread = Thread.ofPlatform().name("truesim-audio-stream").daemon().start(this::run);
        }
        LockSupport.unpark(thread);
    }

    /** Rings being filled. */
    int size() {
        return rings.size() + added.size();
    }

    private void run() {
        while (running) {
            for (StreamRing ring = added.poll(); ring != null; ring = added.poll()) {
                rings.add(ring);
            }
            for (int i = rings.size() - 1; i >= 0; i--) {
                if (!rings.get(i).fill(CHUNK)) {
                    rings.remove(i);
                }
            }
            LockSupport.parkNanos(2_000_000);
        }
        for (StreamRing ring : rings) {
            ring.close();
            ring.fill(CHUNK);
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (thread != null) {
            LockSupport.unpark(thread);
            try {
                thread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
