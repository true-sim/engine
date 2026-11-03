package com.truesim.engine.audio;

import com.truesim.scene.audio.Transmission;

/**
 * What sounds pass through, traced in batches answered later (§2.11): the engine's ray tracing structures, whose rays a
 * GPU pass traces once a frame and reads back a frame or more later, where an {@code AudioWorld} answers each ray at
 * once. The occlusion hands over each frame's rays as one batch and takes the results when they come; the smoothing
 * (attack 100 ms) hides the frames between. The same rays give the same transmissions as an {@code AudioWorld} over
 * the same geometry would. Called from the thread updating the {@link AudioSync}; allocates nothing per batch.
 */
public interface BatchedAudioWorld {

    /** The most rays a batch holds. */
    int capacity();

    /**
     * Hands over {@code count} rays (at most {@link #capacity}): 6 values each in {@code rays}, from ({@code x0},
     * {@code y0}, {@code z0}) to ({@code x1}, {@code y1}, {@code z1}). Returns the batch's ticket (positive), or 0 when
     * it cannot take a batch now (nothing taken).
     */
    long submit(double[] rays, int count);

    /**
     * Fills {@code out[offset]} on, one per ray of batch {@code ticket}, with what the surfaces each ray crossed let
     * through (opened, then crossed), once the batch is traced: returns its ray count, or -1 while it is not (or no
     * longer kept).
     */
    int collect(long ticket, Transmission[] out, int offset);
}
