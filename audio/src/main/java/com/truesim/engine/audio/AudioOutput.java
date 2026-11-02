package com.truesim.engine.audio;

/**
 * Where a renderer's blocks go (AU1): SDL3's audio stream by default, nothing ({@code NullOutput}), or memory and WAV
 * files ({@link OfflineOutput}). The output owns the audio thread if it has one; swapping it replaces only the sink.
 */
public interface AudioOutput extends AutoCloseable {

    /** Starts rendering {@code renderer}'s blocks and handing them on. */
    void start(AudioRenderer renderer);

    /** Stops rendering; the renderer keeps its state. */
    void stop();

    /** Blocks that came too late for the device (underruns) since started. */
    default long underruns() {
        return 0;
    }

    @Override
    void close();
}
