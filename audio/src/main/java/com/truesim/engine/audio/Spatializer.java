package com.truesim.engine.audio;

/**
 * Renders mono voices binaurally into a stereo mix (§2.9): the renderer hands a voice's block, attenuated and
 * filtered, to one of its channels and the spatializer adds both ears into the bus. Ours is {@link HrtfSpatializer};
 * a native provider (Steam Audio, the documented fallback of AU6 should our HRTF fail its listening checks or its
 * budget) would implement this as a plugin without touching the mixer.
 *
 * <p>Used on the audio thread: every method allocation-free; made (and prepared) on the game thread for one block
 * size and rate.
 */
public interface Spatializer {

    /** Voices it renders at once at most. */
    int capacity();

    /** Channel {@code channel} is given to a new voice: its history is cleared, its first filter taken at once. */
    void reset(int channel);

    /** Channel {@code channel}'s source is at ({@code x}, {@code y}, {@code z}), metres from the head (x right, y up, -z ahead). */
    void place(int channel, float x, float y, float z);

    /** Blocks a channel still sounds after its last input (its filters' tail): rendered before it is used again. */
    int tailBlocks();

    /** A block begins: budgets per block (filter updates) start again. */
    void begin();

    /** Renders channel {@code channel}'s mono block {@code mono} ({@code frames} long, the block) into stereo {@code out}. */
    void process(int channel, float[] mono, float[] out, int frames);
}
