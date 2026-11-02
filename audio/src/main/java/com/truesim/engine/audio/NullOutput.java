package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.concurrent.locks.LockSupport;

/**
 * The output when there is no device (D125: no device or no SDL means silence and a log line): a thread renders blocks
 * at the pace of real time and drops them, so the clock moves, sounds end and music keeps time as with a device. With
 * {@code render} false nothing renders and the clock stands still (servers that load the plugin by mistake).
 */
public final class NullOutput implements AudioOutput {

    private final boolean render;
    private volatile boolean running;
    private Thread thread;
    private AudioRenderer renderer;

    /** An output that renders at real time and drops the blocks ({@code render} true), or does nothing. */
    public NullOutput(boolean render) {
        this.render = render;
    }

    @Override
    @AllowAllocation("starting up")
    public synchronized void start(AudioRenderer renderer) {
        this.renderer = renderer;
        renderer.clock().latency(SdlOutput.scheduling(renderer));
        if (!render || running) {
            return;
        }
        running = true;
        thread = Thread.ofPlatform().name("truesim-audio-null").daemon().unstarted(this::run);
        thread.start();
    }

    /** Whether it renders. */
    public boolean rendering() {
        return render;
    }

    private void run() {
        AudioFormat f = renderer.format();
        float[] block = new float[f.block() * f.channels()];
        long blockNanos = Math.round(f.blockSeconds() * 1e9);
        long next = System.nanoTime();
        while (running) {
            renderer.render(block, 0, f.block());
            next += blockNanos;
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            } else if (wait < -100_000_000L) {
                next = System.nanoTime(); // fell far behind (a suspended process): catch up no more than this
            }
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
