package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.scene.audio.AudioEffect;

/**
 * The reverb of aux buses (§2.13, step 10): a feedback delay network of 8 lines mixed by a Hadamard matrix (Jot's
 * FDN), each line's feedback gain set so the tail falls 60 dB in the decay time, a one-pole low-pass in each line
 * (damping: the highs die faster, as in rooms), a short pre-delay and the input spread into every line. Stereo out
 * from two decorrelated sums of the lines. The decay and damping can change while it plays (a zone's reverb blended in:
 * the gains move, the lines stay); delays scale with the size when made. Allocation-free once made.
 *
 * <p>Presets ({@code preset} field): 0 room (0.6 s), 1 hall (2.2 s), 2 cave (4 s), 3 small room (0.3 s); {@code decay}
 * in seconds overrides the preset's, {@code size} scales the room, {@code damping} 0 to 1, {@code mix} the wet level.
 */
final class Reverb implements BusEffects.Effect {

    /** Decay times of the presets, seconds. */
    static final float[] PRESET_DECAY = {0.6f, 2.2f, 4f, 0.3f};
    private static final float[] PRESET_SIZE = {0.5f, 1.2f, 1.6f, 0.3f};
    private static final float[] PRESET_DAMPING = {0.4f, 0.3f, 0.15f, 0.5f};
    /** Line lengths at size 1, ms: mutually prime sample counts at 48 kHz, 29 to 79 ms. */
    private static final double[] LENGTHS_MS = {29.7, 37.1, 41.1, 43.7, 53.9, 61.3, 71.7, 79.3};

    private final int channels;
    private final int rate;
    private final float[][] lines = new float[8][];
    private final int[] lengths = new int[8];
    private final int[] heads = new int[8];
    private final float[] gains = new float[8];
    private final float[] lows = new float[8];
    private final float[] outs = new float[8];
    private final float[] pre;
    private int preAt;
    private float damping;
    private float decay;
    private final float mix;

    @AllowAllocation("built on the game thread")
    private Reverb(int rate, int channels, float size, float decay, float damping, float mix) {
        this.rate = rate;
        this.channels = channels;
        this.mix = mix;
        for (int i = 0; i < 8; i++) {
            lengths[i] = Math.max(16, (int) Math.round(LENGTHS_MS[i] * 0.001 * rate * size));
            lines[i] = new float[lengths[i]];
        }
        pre = new float[Math.max(1, (int) Math.round(0.012 * rate * size))];
        set(decay, damping);
    }

    /** The reverb {@code record} describes. */
    @AllowAllocation("built on the game thread")
    static Reverb create(AudioEffect record, int rate, int channels) {
        int preset = Math.max(0, Math.min(3, (int) record.value("preset", 0)));
        return new Reverb(rate, channels, record.value("size", PRESET_SIZE[preset]),
            record.value("decay", PRESET_DECAY[preset]), record.value("damping", PRESET_DAMPING[preset]),
            record.value("mix", 1));
    }

    /** The decay time a record asks for, seconds: its {@code decay}, else its preset's. */
    static float decayOf(AudioEffect record) {
        return record.value("decay", PRESET_DECAY[Math.max(0, Math.min(3, (int) record.value("preset", 0)))]);
    }

    /** The damping a record asks for. */
    static float dampingOf(AudioEffect record) {
        return record.value("damping", PRESET_DAMPING[Math.max(0, Math.min(3, (int) record.value("preset", 0)))]);
    }

    /** Sets the decay (seconds to fall 60 dB) and damping: each line's gain 10^(-3 length / (decay rate)). */
    void set(float decaySeconds, float dampingAmount) {
        decay = Math.max(0.05f, decaySeconds);
        damping = Math.max(0, Math.min(0.95f, dampingAmount));
        for (int i = 0; i < 8; i++) {
            gains[i] = (float) Maths.pow(10, -3.0 * lengths[i] / (decay * rate));
        }
    }

    float decay() {
        return decay;
    }

    @Override
    public void process(float[] buffer, int frames) {
        float norm = (float) (1 / Math.sqrt(8));
        for (int f = 0; f < frames; f++) {
            int o = f * channels;
            float in = 0;
            for (int c = 0; c < channels; c++) {
                in += buffer[o + c];
            }
            float delayed = pre[preAt];
            pre[preAt] = in * 0.25f;
            preAt = preAt + 1 == pre.length ? 0 : preAt + 1;
            for (int i = 0; i < 8; i++) {
                float x = lines[i][heads[i]];
                lows[i] += (1 - damping) * (x - lows[i]);
                outs[i] = lows[i] * gains[i];
            }
            // the Hadamard matrix of 8, in place (three butterfly stages), scaled to stay lossless
            for (int h = 1; h < 8; h <<= 1) {
                for (int i = 0; i < 8; i += h << 1) {
                    for (int j = i; j < i + h; j++) {
                        float a = outs[j];
                        float b = outs[j + h];
                        outs[j] = a + b;
                        outs[j + h] = a - b;
                    }
                }
            }
            float left = 0;
            float right = 0;
            for (int i = 0; i < 8; i++) {
                float tail = lines[i][heads[i]];
                left += (i & 1) == 0 ? tail : -tail * 0.5f;
                right += (i & 1) == 1 ? tail : -tail * 0.5f;
                lines[i][heads[i]] = outs[i] * norm + delayed;
                heads[i] = heads[i] + 1 == lengths[i] ? 0 : heads[i] + 1;
            }
            left *= mix * 0.35f;
            right *= mix * 0.35f;
            if (channels == 1) {
                buffer[o] = (left + right) * 0.5f;
            } else {
                buffer[o] = left;
                buffer[o + 1] = right;
                for (int c = 2; c < channels; c++) {
                    buffer[o + c] = (c & 1) == 0 ? left * 0.7f : right * 0.7f;
                }
            }
        }
    }
}
