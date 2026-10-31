package com.truesim.engine.graph;

/**
 * A buffer kept from frame to frame, for temporal work on buffers (a particle state, an exposure history): each frame
 * writes {@link #current()} and may read {@link #previous()}, what the frame before wrote. The graph keeps two buffers
 * and swaps their roles every frame its view runs; they live while builds ask for them by the same name and size, and
 * start zeroed. Last frame's buffer is zeros on the first frame: see {@link PassContext#historyValid(BufferHistory)}.
 *
 * <pre>{@code
 * BufferHistory particles = g.historyBuffer("particles", 64L * count);
 * g.pass("simulate").read(particles.previous(), BufferAccess.STORAGE_READ)
 *     .write(particles.current(), BufferAccess.STORAGE_WRITE) ...
 * }</pre>
 */
public final class BufferHistory {

    final Persistent pair; // the graph's two buffers, swapped every frame
    private final Buffer current;
    private final Buffer previous;

    BufferHistory(Persistent pair, Buffer current, Buffer previous) {
        this.pair = pair;
        this.current = current;
        this.previous = previous;
    }

    /** This frame's buffer, to write. */
    public Buffer current() {
        return current;
    }

    /** What the frame before wrote into its current buffer. */
    public Buffer previous() {
        return previous;
    }

    @Override
    public String toString() {
        return "buffer history " + pair.key;
    }
}
