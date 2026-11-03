package com.truesim.engine.audio.dsp;

/**
 * One block for a node: its frames, its channels, and planar buffers. Inputs past those wired read silence; in a
 * patch, input {@code i} is the {@code i}-th signal given to {@code in(...)} (a node may read more inputs than it has
 * channels: a trigger, a side chain).
 */
public final class DspBlock {

    private final float[][] inputs;
    private final float[][] outputs;
    private final int channels;
    private int frames;

    DspBlock(float[][] inputs, float[][] outputs, int channels) {
        this.inputs = inputs;
        this.outputs = outputs;
        this.channels = channels;
    }

    void frames(int frames) {
        this.frames = frames;
    }

    /** Frames in this block (up to the setup's maximum). */
    public int frames() {
        return frames;
    }

    /** Channels it processes. */
    public int channels() {
        return channels;
    }

    /** Input {@code i}: silence where nothing is wired. */
    public float[] input(int i) {
        return inputs[i];
    }

    /** How many inputs it has. */
    public int inputs() {
        return inputs.length;
    }

    /** Output channel {@code c}. */
    public float[] output(int c) {
        return outputs[c];
    }
}
