package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Renders on the caller's thread into memory, and writes WAV files of it: tests, tools, offline bounces (§2.24). No
 * device, no threads: {@link #render(double)} renders as many blocks as the seconds need.
 *
 * <pre>{@code
 * OfflineOutput out = new OfflineOutput();
 * out.start(renderer);
 * out.render(2.0);
 * out.write(Path.of("bounce.wav"), 16);
 * }</pre>
 */
public final class OfflineOutput implements AudioOutput {

    private AudioRenderer renderer;
    private AudioFormat format;
    private float[] samples = new float[0];
    private int frames;

    @Override
    public void start(AudioRenderer renderer) {
        this.renderer = renderer;
        format = renderer.format();
    }

    @Override
    public void stop() {
        renderer = null;
    }

    /** Renders at least {@code seconds} (whole blocks), appending to what was rendered. */
    @AllowAllocation("growing the recording")
    public void render(double seconds) {
        if (renderer == null) {
            throw new IllegalStateException("Not started");
        }
        AudioFormat f = renderer.format();
        int blocks = (int) Math.ceil(seconds * f.rate() / f.block());
        int add = blocks * f.block();
        if ((frames + add) * f.channels() > samples.length) {
            samples = Arrays.copyOf(samples, Math.max(2 * samples.length, (frames + add) * f.channels()));
        }
        renderer.render(samples, frames, add);
        frames += add;
    }

    /** Frames rendered. */
    public int frames() {
        return frames;
    }

    /** The samples rendered, interleaved: a copy. */
    @AllowAllocation("a copy")
    public float[] samples() {
        return Arrays.copyOf(samples, format == null ? 0 : frames * format.channels());
    }

    /** Writes what was rendered to {@code file}, 16-bit PCM or 32-bit float. */
    public void write(Path file, int bits) {
        try {
            WavWriter.write(file, Arrays.copyOf(samples, frames * format.channels()), format.channels(),
                format.rate(), bits);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        stop();
    }
}
