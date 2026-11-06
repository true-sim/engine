package com.truesim.engine.render;

import com.truesim.scene.core.ShadowSpec;
import java.util.SplittableRandom;

/**
 * The many-light scene of {@link ShadowsBenchmark} and {@link ShadowsVideo}: 200 point lights 1 to 4 units over
 * {@link MeshletBenchmark#rocksOnTerrain}'s field (as {@link MeshletBenchmark#scatterLights}), and what moves in it:
 * {@code rocks} rocks (instances 1 on) circling the lights inside their ranges, ten or so to a light, and a share of the
 * lights circling where they stood. Positions are a function of time, so a fixed step gives the same frames on any GPU.
 */
final class ManyLights {

    static final int COUNT = 200;
    static final double GROUND_Y = -12;

    private final double[] x = new double[COUNT];
    private final double[] y = new double[COUNT];
    private final double[] z = new double[COUNT];
    private final float[] range = new float[COUNT];
    private final float[] r = new float[COUNT];
    private final float[] g = new float[COUNT];
    private final float[] b = new float[COUNT];

    /** The lights of {@link MeshletBenchmark#scatterLights}(lights, 200, -12, {@code brightness}). */
    ManyLights(double brightness) {
        SplittableRandom random = new SplittableRandom(5);
        for (int i = 0; i < COUNT; i++) {
            x[i] = random.nextDouble(-100, 100);
            z[i] = random.nextDouble(-205, -5);
            y[i] = GROUND_Y + MeshletBenchmark.terrainHeight(x[i], z[i]) + random.nextDouble(1, 4);
            range[i] = (float) random.nextDouble(6, 15);
            r[i] = (float) (brightness * random.nextDouble(0.1, 1));
            g[i] = (float) (brightness * random.nextDouble(0.1, 1));
            b[i] = (float) (brightness * random.nextDouble(0.1, 1));
        }
    }

    /** Places the lights where they stand (light {@code first} on), each shadowed by {@code spec}. */
    void place(Lights lights, int first, ShadowSpec spec) {
        for (int i = 0; i < COUNT; i++) {
            lights.point(first + i, x[i], y[i], z[i], range[i], r[i], g[i], b[i]);
            lights.shadow(first + i, spec);
        }
        lights.setCount(first + COUNT);
    }

    /** Whether light {@code i} moves when {@code share} of them do (0.1: every tenth). */
    static boolean moves(int i, double share) {
        return share > 0 && i % Math.max(1, (int) Math.round(1 / share)) == 0;
    }

    /**
     * The scene at {@code seconds}: rocks 1 to {@code rocks} (of mesh {@code rock}) circling the lights, the lights of
     * {@code share} circling 4 units wide where they stood (light {@code first} on).
     */
    void move(GpuScene scene, int rock, int rocks, Lights lights, int first, double share, double seconds) {
        for (int k = 0; k < rocks; k++) {
            int light = k % COUNT;
            int ring = k / COUNT;
            double radius = range[light] * (0.25 + 0.5 * ((ring * 0.618034) % 1.0));
            double angle = ring * 2.399963 + (0.5 + 0.1 * (ring % 3)) * seconds * (k % 2 == 0 ? 1 : -1);
            double px = x[light] + radius * Math.cos(angle);
            double pz = z[light] + radius * Math.sin(angle);
            float scale = (float) (0.6 + 0.15 * (k % 5));
            double spin = 0.5 * angle;
            scene.set(k + 1, rock, (float) px,
                (float) (GROUND_Y + MeshletBenchmark.terrainHeight(px, pz) + 0.4 * scale), (float) pz,
                0, (float) Math.sin(spin), 0, (float) Math.cos(spin), scale, scale, scale);
        }
        if (share > 0) {
            for (int i = 0; i < COUNT; i++) {
                if (moves(i, share)) {
                    double a = 0.8 * seconds + i;
                    lights.point(first + i, x[i] + 2 * Math.cos(a), y[i], z[i] + 2 * Math.sin(a), range[i], r[i],
                        g[i], b[i]);
                }
            }
        }
    }
}
