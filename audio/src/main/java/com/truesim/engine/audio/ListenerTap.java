package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;

/**
 * A rendering listener's tap (§2.10): its mix, interleaved, in a single-producer single-consumer ring the audio thread
 * writes and the game or a tool reads (recording, voice-chat loopback, tests). Lock-free; what is not read in time is
 * overwritten (counted by {@link #lost}).
 */
public final class ListenerTap {

    private final float[] ring;
    private final int channels;
    private volatile long written;
    private long read;
    private long lost;

    /** A tap of {@code channels} holding {@code frames} frames (a power of two). */
    @AllowAllocation("made once per tapped listener")
    public ListenerTap(int channels, int frames) {
        if (Integer.bitCount(frames) != 1) {
            throw new IllegalArgumentException("A tap holds a power of two of frames, not " + frames);
        }
        this.channels = channels;
        ring = new float[frames * channels];
    }

    public int channels() {
        return channels;
    }

    /** Audio thread: appends {@code frames} frames of {@code samples}. */
    void write(float[] samples, int frames) {
        int mask = ring.length - 1;
        long w = written * channels;
        for (int i = 0; i < frames * channels; i++) {
            ring[(int) ((w + i) & mask)] = samples[i];
        }
        written += frames;
    }

    /** Reads up to {@code into.length / channels} frames into {@code into}; returns how many frames. */
    public int read(float[] into) {
        long w = written;
        int capacity = ring.length / channels;
        if (w - read > capacity) {
            lost += w - read - capacity;
            read = w - capacity;
        }
        int n = (int) Math.min(into.length / channels, w - read);
        int mask = ring.length - 1;
        long r = read * channels;
        for (int i = 0; i < n * channels; i++) {
            into[i] = ring[(int) ((r + i) & mask)];
        }
        read += n;
        return n;
    }

    /** Frames overwritten before they were read. */
    public long lost() {
        return lost;
    }
}
