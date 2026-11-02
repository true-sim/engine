package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;

/**
 * The master's look-ahead peak limiter: 1.5 ms ahead to a ceiling of −1 dBFS. The signal is delayed by the look-ahead;
 * the gain falls by at most 1 / look-ahead a sample towards the least gain any sample within the look-ahead needs, so
 * it has reached it when that sample comes out (no overshoot, no step), and recovers over 50 ms. The renderer starts
 * everything {@link #latency()} frames early, so a sound stamped at frame F still comes out at F. Allocation-free.
 *
 * <p>Peaks are sample peaks: inter-sample (true) peaks of up to about 0.7 dB over the ceiling remain, inside the 1 dB
 * margin to full scale.
 */
final class Limiter {

    private final int channels;
    private final int latency;
    private final float ceiling;
    private final float attack;
    private final float release;
    private final float[] delay;
    private final float[] needs;
    private int at;
    private float gain = 1;

    @AllowAllocation("made with its renderer")
    Limiter(int rate, int channels) {
        this.channels = channels;
        latency = Math.max(1, (int) Math.round(rate * 0.0015));
        ceiling = (float) Maths.pow(10, -1 / 20.0);
        attack = 1f / latency;
        release = (float) (1 / (0.05 * rate));
        delay = new float[latency * channels];
        needs = new float[latency];
        java.util.Arrays.fill(needs, 1);
    }

    /** Frames the limiter delays its output by. */
    int latency() {
        return latency;
    }

    /** The gain applied last: 1 when not limiting. */
    float gain() {
        return gain;
    }

    /** Limits {@code frames} interleaved frames of {@code buffer} in place. */
    void process(float[] buffer, int frames) {
        for (int f = 0; f < frames; f++) {
            int base = f * channels;
            float peak = 0;
            for (int c = 0; c < channels; c++) {
                peak = Math.max(peak, Math.abs(buffer[base + c]));
            }
            // the least need of the samples in the delay (the one leaving now included) and the one arriving
            float least = peak > ceiling ? ceiling / peak : 1;
            for (float need : needs) {
                least = Math.min(least, need);
            }
            needs[at] = peak > ceiling ? ceiling / peak : 1;
            gain = least < gain ? Math.max(least, gain - attack) : Math.min(least, gain + release);
            int d = at * channels;
            for (int c = 0; c < channels; c++) {
                float in = buffer[base + c];
                buffer[base + c] = delay[d + c] * gain;
                delay[d + c] = in;
            }
            at = at + 1 == latency ? 0 : at + 1;
        }
    }
}
