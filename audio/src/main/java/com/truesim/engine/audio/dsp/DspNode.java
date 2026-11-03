package com.truesim.engine.audio.dsp;

import java.util.List;

/**
 * A DSP node (AU3, §2.13): prepared once on the game thread, where it allocates everything; processed per block on
 * the audio thread, where it allocates nothing, takes no lock and does no I/O; reset when a pooled voice is reused.
 * Buffers are planar {@code float[]} of the block. Plugins add node types by name ({@link Nodes}).
 */
public interface DspNode {

    /** The parameters it reads through {@link DspSetup#param}; a node made from a {@link NodeType} takes the type's. */
    default List<NodeParam> params() {
        return List.of();
    }

    /** Game thread, once: allocate buffers and look up parameters here. */
    void prepare(DspSetup setup);

    /** Audio thread, per block: no allocation, no locks, no I/O. */
    void process(DspBlock block);

    /** Back to its state after {@link #prepare}: a pooled voice reused. */
    void reset();
}
