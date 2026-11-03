package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;

/**
 * The master's accessibility stage (§2.17), before the limiter: a mono downmix (the channels' mean at −3 dB each, so
 * a centred sound keeps its level), a balance (the far side lowered, equal power) and a dynamic range preset: full
 * (nothing), home (a gentle compressor, 2:1 above −24 dBFS, 3 dB of makeup) or night (4:1 above −30 dBFS, 9 dB of
 * makeup). Changes apply at a block's start; processing allocates nothing.
 */
final class Accessibility {

    private final int channels;
    private final BusEffects.Compressor home;
    private final BusEffects.Compressor night;
    private boolean mono;
    private float left = 1;
    private float right = 1;
    private int range;

    @AllowAllocation("made with its renderer")
    Accessibility(int rate, int channels) {
        this.channels = channels;
        home = new BusEffects.Compressor(-24, 2, 0.01, 0.3, 3, rate, channels);
        night = new BusEffects.Compressor(-30, 4, 0.005, 0.2, 9, rate, channels);
    }

    /** Mono downmix, balance (-1 left to 1 right) and dynamic range (0 full, 1 home, 2 night). */
    void set(boolean downmix, float balance, int dynamicRange) {
        mono = downmix;
        double angle = (Math.max(-1, Math.min(1, balance)) + 1) * Math.PI / 4;
        // equal power, normalised so the middle is unity: cos and sin over cos(pi / 4)
        left = (float) Math.min(1, Math.sqrt(2) * com.truesim.common.math.Maths.cos(angle));
        right = (float) Math.min(1, Math.sqrt(2) * com.truesim.common.math.Maths.sin(angle));
        range = dynamicRange;
    }

    void process(float[] buffer, int frames) {
        if (range == 1) {
            home.process(buffer, frames);
        } else if (range == 2) {
            night.process(buffer, frames);
        }
        if (channels < 2 || !mono && left == 1 && right == 1) {
            return;
        }
        float scale = (float) (Math.sqrt(0.5) * 2 / channels);
        for (int f = 0; f < frames; f++) {
            int o = f * channels;
            if (mono) {
                float sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += buffer[o + c];
                }
                for (int c = 0; c < channels; c++) {
                    buffer[o + c] = sum * scale;
                }
            }
            buffer[o] *= left;
            buffer[o + 1] *= right;
        }
    }
}
