package com.truesim.engine.audio;

/**
 * What the renderer makes: frames a second, output channels and the block it renders at once (AU4: 256 frames by
 * default; the block is part of the format, so offline renders and their goldens name it).
 *
 * <pre>{@code
 * AudioFormat.stereo(48_000).block(256)
 * }</pre>
 *
 * @param rate     frames a second: the device's native rate (AU14)
 * @param channels output channels: 2, or 6 (5.1) and 8 (7.1) for speakers
 * @param block    frames rendered at once
 */
public record AudioFormat(int rate, int channels, int block) {

    /** Frames a block holds by default. */
    public static final int DEFAULT_BLOCK = 256;

    public AudioFormat {
        if (rate < 8_000 || rate > 384_000) {
            throw new IllegalArgumentException("Not an output rate: " + rate);
        }
        if (channels != 1 && channels != 2 && channels != 4 && channels != 6 && channels != 8) {
            throw new IllegalArgumentException("Outputs have 1, 2, 4, 6 or 8 channels, not " + channels);
        }
        if (block < 16 || block > 8_192 || Integer.bitCount(block) != 1) {
            throw new IllegalArgumentException("Blocks are a power of two from 16 to 8,192 frames, not " + block);
        }
    }

    /** Stereo at {@code rate}, in blocks of {@value #DEFAULT_BLOCK}. */
    public static AudioFormat stereo(int rate) {
        return new AudioFormat(rate, 2, DEFAULT_BLOCK);
    }

    /** {@code channels} channels at {@code rate}, in blocks of {@value #DEFAULT_BLOCK}. */
    public static AudioFormat of(int rate, int channels) {
        return new AudioFormat(rate, channels, DEFAULT_BLOCK);
    }

    /** A copy rendering {@code frames} at once. */
    public AudioFormat block(int frames) {
        return new AudioFormat(rate, channels, frames);
    }

    /** Seconds a block lasts. */
    public double blockSeconds() {
        return (double) block / rate;
    }
}
