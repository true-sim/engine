package com.truesim.engine.audio;

import com.truesim.scene.audio.AudioClock;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * The audio clock as published by the renderer (§2.3): output frames rendered, the {@link System#nanoTime()} of the
 * last block and how much waits ahead of the ear (the output's queue and the device's buffer), under a seqlock so the
 * game thread reads a consistent set without a lock. The schedule point is rendered plus the scheduling latency: the
 * limiter's look-ahead, a block, and the output's high mark. Reading and publishing allocate nothing.
 */
public final class RenderClock implements AudioClock {

    private static final VarHandle SEQUENCE;

    static {
        try {
            SEQUENCE = MethodHandles.lookup().findVarHandle(RenderClock.class, "sequence", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int rate;
    @SuppressWarnings("unused") // through SEQUENCE
    private volatile long sequence;
    private long rendered;
    private long nanos;
    private int queued;
    private int device;
    private volatile int latency;

    RenderClock(int rate, int latency) {
        this.rate = rate;
        this.latency = latency;
    }

    @Override
    public int rate() {
        return rate;
    }

    /** Publishes a block: {@code frames} rendered so far, at {@code time}, {@code waiting} frames queued. */
    void publish(long frames, long time, int waiting) {
        long s = (long) SEQUENCE.getOpaque(this);
        SEQUENCE.setRelease(this, s + 1);
        VarHandle.storeStoreFence();
        rendered = frames;
        nanos = time;
        queued = waiting;
        SEQUENCE.setRelease(this, s + 2);
    }

    /** The output's device buffer, frames: part of what is heard late. */
    void device(int frames) {
        device = frames;
    }

    /** Frames between rendering and the earliest frame a command written now surely starts at. */
    void latency(int frames) {
        latency = frames;
    }

    /** The scheduling latency, frames. */
    public int latency() {
        return latency;
    }

    @Override
    public long renderedFrame() {
        while (true) {
            long s = (long) SEQUENCE.getAcquire(this);
            long r = rendered;
            VarHandle.loadLoadFence();
            if ((s & 1) == 0 && s == (long) SEQUENCE.getAcquire(this)) {
                return r;
            }
            Thread.onSpinWait();
        }
    }

    /** The {@link System#nanoTime()} the last block was published at. */
    public long publishedNanos() {
        while (true) {
            long s = (long) SEQUENCE.getAcquire(this);
            long n = nanos;
            VarHandle.loadLoadFence();
            if ((s & 1) == 0 && s == (long) SEQUENCE.getAcquire(this)) {
                return n;
            }
            Thread.onSpinWait();
        }
    }

    /** Frames waiting in the output's queue at the last block. */
    public int queued() {
        while (true) {
            long s = (long) SEQUENCE.getAcquire(this);
            int q = queued;
            VarHandle.loadLoadFence();
            if ((s & 1) == 0 && s == (long) SEQUENCE.getAcquire(this)) {
                return q;
            }
            Thread.onSpinWait();
        }
    }

    @Override
    public long heardFrame() {
        return renderedFrame() - queued() - device;
    }

    @Override
    public long schedulePoint() {
        return renderedFrame() + latency;
    }
}
