package com.truesim.engine.audio;

import com.truesim.scene.audio.HrtfSet;

/** Small HRTF sets made in code. */
final class HrtfSetsForTests {

    private HrtfSetsForTests() {
    }

    /**
     * 14 directions (an octahedron's and a cube's corners) with 64-tap filters decaying by direction and delays by how
     * far left or right each is (10 samples apart per unit of x), at {@code rate}.
     */
    static HrtfSet ring(int rate) {
        float[] d = {1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1,
            1, 1, 1, 1, 1, -1, 1, -1, 1, 1, -1, -1, -1, 1, 1, -1, 1, -1, -1, -1, 1, -1, -1, -1};
        int n = d.length / 3;
        int taps = 64;
        float[] distances = new float[n];
        float[] delays = new float[2 * n];
        float[] filters = new float[2 * n * taps];
        for (int i = 0; i < n; i++) {
            distances[i] = 1.5f;
            double x = d[3 * i] / Math.sqrt(d[3 * i] * d[3 * i] + d[3 * i + 1] * d[3 * i + 1] + d[3 * i + 2] * d[3 * i + 2]);
            delays[2 * i] = (float) (10 * (1 + x));
            delays[2 * i + 1] = (float) (10 * (1 - x));
            for (int ear = 0; ear < 2; ear++) {
                double decay = 0.7 + 0.02 * i + 0.1 * ear;
                for (int t = 0; t < taps; t++) {
                    filters[(2 * i + ear) * taps + t] = (float) (0.3 * Math.pow(decay, t));
                }
            }
        }
        return HrtfSet.of(rate, taps, "test ring", d, distances, delays, filters);
    }
}
