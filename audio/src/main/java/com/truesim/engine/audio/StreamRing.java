package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.ClipDecoder;

/**
 * One streamed voice's ring (§2.14): the streaming thread decodes into it, the audio thread reads it. Frames are
 * numbered as the voice plays them ("stream frames"): a loop is stitched here, the decoder sent back to the loop's
 * start as it reaches its end, so the voice reads on without a seam. The writer keeps the ring at most full of frames
 * the reader has not passed; the reader publishes how far it has read. A reader that runs dry asks for a seek to where
 * it should be by now ({@link #resumeAt}), and the writer starts again there. Single writer, single reader; the
 * counters are volatile, the samples written before the count that covers them.
 */
final class StreamRing {

    private final AudioClip clip;
    private final int channels;
    private final int capacity;
    private final float[] samples;
    private final boolean loop;
    private final long loopStart;
    private final long loopEnd;
    private final long frames;
    private final int prime;
    private volatile long written;
    private volatile long read;
    private volatile long resumeAt = -1;
    private volatile long base;
    private volatile boolean closed;
    private ClipDecoder decoder;
    private long clipAt;
    private float[] chunk;

    /** A ring of {@code seconds} for {@code clip}, looping its loop region if {@code loop}. */
    @AllowAllocation("a ring per streamed voice")
    StreamRing(AudioClip clip, boolean loop, double seconds) {
        this.clip = clip;
        channels = clip.channels();
        capacity = (int) Math.max(1_024, Math.round(seconds * clip.rate()));
        samples = new float[capacity * channels];
        this.loop = loop;
        loopStart = clip.loopStart();
        loopEnd = clip.loopEnd();
        frames = clip.frames();
        prime = (int) Math.min(capacity / 2, frames);
    }

    int channels() {
        return channels;
    }

    AudioClip clip() {
        return clip;
    }

    /** Whether it holds enough to start: half a second (or the whole clip, if shorter). */
    boolean primed() {
        return written >= prime || closed;
    }

    /** Frames the voice plays at most: the clip's length; unbounded for a loop. */
    long end() {
        return loop ? Long.MAX_VALUE : frames;
    }

    /** Frames written so far, as stream frames. */
    long written() {
        return written;
    }

    /** Reader: sample {@code channel} of stream frame {@code frame}, which must be below {@link #written()}. */
    float sample(long frame, int channel) {
        return samples[(int) (frame % capacity) * channels + channel];
    }

    /** Reader: whether stream frame {@code frame} is there to read. */
    boolean holds(long frame) {
        long w = written;
        return frame < w && frame >= Math.max(base, w - capacity);
    }

    /** Reader: it has read up to stream frame {@code frame}; the writer may overwrite what lies before. */
    void readTo(long frame) {
        read = frame;
    }

    /** Reader: it ran dry; the writer starts again at stream frame {@code frame}, where the voice is by now. */
    void resume(long frame) {
        resumeAt = frame;
    }

    /** The voice ended: the streaming thread closes the decoder. */
    void close() {
        closed = true;
    }

    boolean closed() {
        return closed;
    }

    /** Writer: decodes what fits; false once there is nothing more to do (closed, or the clip written whole). */
    @AllowAllocation("the streaming thread's first chunk")
    boolean fill(int chunkFrames) {
        if (closed) {
            if (decoder != null) {
                decoder.close();
                decoder = null;
            }
            return false;
        }
        if (decoder == null) {
            decoder = clip.open();
            chunk = new float[chunkFrames * channels];
        }
        long seek = resumeAt;
        if (seek >= 0) {
            resumeAt = -1;
            clipAt = clipFrame(seek);
            decoder.seek(clipAt);
            read = seek;
            base = seek;
            written = seek;
        }
        long w = written;
        while (w - read + chunkFrames <= capacity && (loop || clipAt < frames)) {
            long until = loop ? loopEnd : frames;
            int want = (int) Math.min(chunkFrames, until - clipAt);
            int n = want <= 0 ? 0 : decoder.read(chunk, 0, want);
            for (int f = 0; f < n; f++) {
                int at = (int) ((w + f) % capacity) * channels;
                System.arraycopy(chunk, f * channels, samples, at, channels);
            }
            w += n;
            clipAt += n;
            written = w;
            if (loop && (clipAt >= loopEnd || n == 0)) {
                clipAt = loopStart; // the seam: stitched here, so the voice reads straight on
                decoder.seek(loopStart);
            } else if (n == 0) {
                break;
            }
        }
        return true;
    }

    /** The clip frame stream frame {@code frame} plays. */
    private long clipFrame(long frame) {
        if (!loop || frame < loopEnd) {
            return Math.min(frame, frames);
        }
        return loopStart + (frame - loopEnd) % (loopEnd - loopStart);
    }
}
