package com.truesim.engine.graph;

import java.lang.foreign.MemorySegment;

/**
 * Receives what a {@link GraphBuilder#readback} copied off the GPU, on the render thread once the GPU finished the frame
 * that copied it: a frame or more later, in {@link FrameGraph#beginFrame} (or the next {@link FrameGraph#record}). The
 * data is host memory the GPU wrote, visible as it is (coherent: nothing to invalidate), and valid only during the call:
 * copy out what is kept.
 */
@FunctionalInterface
public interface Readback {

    /** {@code data}: the bytes copied in frame {@code frame} (counted by the graph from 1). */
    void receive(MemorySegment data, long frame);
}
