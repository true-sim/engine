package com.truesim.engine.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The FFT against a reference DFT, both ways, and allocation-free. */
class FftTest {

    @ParameterizedTest
    @ValueSource(ints = {4, 8, 64, 512, 2048})
    void forwardMatchesTheDftAndInverseRestores(int n) {
        Random random = new Random(n);
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = (float) (random.nextGaussian() * 0.5);
        }
        Fft fft = new Fft(n);
        assertThat(fft.size()).isEqualTo(n);
        float[] re = new float[n / 2 + 1];
        float[] im = new float[n / 2 + 1];
        fft.forward(x, re, im);
        double tolerance = 1e-4 * Math.sqrt(n);
        for (int k = 0; k <= n / 2; k++) {
            double dr = 0;
            double di = 0;
            for (int t = 0; t < n; t++) {
                dr += x[t] * Math.cos(2 * Math.PI * k * t / n);
                di -= x[t] * Math.sin(2 * Math.PI * k * t / n);
            }
            assertThat((double) re[k]).as("re %d", k).isCloseTo(dr, within(tolerance));
            assertThat((double) im[k]).as("im %d", k).isCloseTo(di, within(tolerance));
        }
        float[] back = new float[n];
        fft.inverse(re, im, back);
        for (int i = 0; i < n; i++) {
            assertThat((double) back[i]).isCloseTo(x[i], within(1e-5));
        }
    }

    @Test
    void aSineIsOnePeak() {
        Fft fft = new Fft(512);
        float[] x = new float[512];
        for (int i = 0; i < 512; i++) {
            x[i] = (float) Math.sin(2 * Math.PI * 32 * i / 512);
        }
        float[] re = new float[257];
        float[] im = new float[257];
        fft.forward(x, re, im);
        assertThat((double) im[32]).isCloseTo(-256, within(1e-3));
        assertThat((double) Math.abs(re[31]) + Math.abs(im[31])).isLessThan(1e-3);
        assertAllocationFree(() -> fft.forward(x, re, im));
        assertThatThrownBy(() -> new Fft(6)).hasMessageContaining("power of two");
        assertThatThrownBy(() -> new Fft(2)).hasMessageContaining("power of two");
    }
}
