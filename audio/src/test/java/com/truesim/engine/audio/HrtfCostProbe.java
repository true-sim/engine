package com.truesim.engine.audio;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Bus;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Measures the HRTF's cost per block (§6 step 17: within 0.6 ms for 16 voices), behind {@code TRUESIM_AUDIO=true} (the
 * build then runs without coverage probes): 16 binaural voices of the built-in set, 48 kHz, 256-frame blocks, the whole
 * renderer timed per block (voices, buses, limiter), once with the sources still and once with every source moving
 * each block (every voice wants a new filter; four get one a block).
 *
 * <pre>TRUESIM_AUDIO=true ./gradlew :engine-audio:test --tests '*HrtfCostProbe*'</pre>
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_AUDIO", matches = "true")
class HrtfCostProbe {

    @Test
    void sixteenVoicesStayWithinTheBudget() {
        long[] still = measure(false);
        long[] moving = measure(true);
        System.out.printf("HRTF, 16 voices, still:  median %d us, p99 %d us, max %d us%n", still[0], still[1], still[2]);
        System.out.printf("HRTF, 16 voices, moving: median %d us, p99 %d us, max %d us%n", moving[0], moving[1],
            moving[2]);
        long[] panned = measurePanned();
        System.out.printf("Panned, 16 voices:       median %d us, p99 %d us, max %d us%n", panned[0], panned[1],
            panned[2]);
        assertThat(moving[0]).as("median microseconds a block, moving").isLessThan(600);
        assertThat(still[0]).as("median microseconds a block, still").isLessThan(600);
    }

    private static long[] measure(boolean moving) {
        try (AudioRenderer r = HrtfTest.renderer(256, 16)) {
            return run(r, moving, HrtfTest.SPATIAL_HRTF);
        }
    }

    private static long[] measurePanned() {
        try (AudioRenderer r = new AudioRenderer(AudioFormat.stereo(48_000), 64)) {
            return run(r, true, Voices.SPATIAL);
        }
    }

    private static long[] run(AudioRenderer r, boolean moving, int flags) {
        AudioClip tone = Clips.sine(48_000, 200, 0.03f, 240 * 100);
        for (int v = 0; v < 16; v++) {
            r.commands().play(v, tone, 0, 0, 1, 1 + 0.01f * v, Bus.SFX, flags | Voices.LOOP, 0, v);
            r.commands().spatial(v, (float) Math.sin(v), 0.2f, (float) Math.cos(v) * 3, 1, HrtfTest.FLAT, false);
        }
        float[] out = new float[512];
        int warm = 3_000;
        int blocks = 20_000;
        long[] times = new long[blocks];
        for (int b = 0; b < warm + blocks; b++) {
            if (moving) {
                for (int v = 0; v < 16; v++) {
                    double angle = 0.02 * b + v; // about 1° a block
                    r.commands().spatial(v, (float) Math.sin(angle) * 3, 0.2f, (float) Math.cos(angle) * 3, 1,
                        HrtfTest.FLAT, false);
                }
            }
            long t0 = System.nanoTime();
            r.render(out, 0, 256);
            long t = System.nanoTime() - t0;
            if (b >= warm) {
                times[b - warm] = t;
            }
        }
        Arrays.sort(times);
        return new long[] {times[blocks / 2] / 1_000, times[blocks * 99 / 100] / 1_000, times[blocks - 1] / 1_000};
    }
}
