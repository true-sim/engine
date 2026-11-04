package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.ui.scene.GpuPicking;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * GPU picking from the visibility buffer: the instance under each pointer's pixel, a frame or more late; and its
 * benchmark at the CPU path's scale (100,000 instances), with TRUESIM_BENCHMARK=true.
 */
class GpuPickingTest {

    @Test
    void thePixelUnderAPointerNamesTheInstanceItShows() {
        try (SceneGpuRig s = SceneGpuRig.open(320, 200, true, 1024, false)) {
            assumeThat(s.rig.device.primitiveIds()).as("the visibility buffer needs primitive IDs").isTrue();
            int red = s.unlit(0.9f, 0.1f, 0.1f);
            int blue = s.unlit(0.1f, 0.1f, 0.9f);
            s.box(0, red, -2, 0, -6, 1);
            s.box(1, blue, 2, 0, -6, 1);
            s.camera.position(0, 0, 0).set(0, 0, -1, 0, 1, 0, Math.toRadians(60), 320, 200, 0.1);
            GpuPicking picking = new GpuPicking(s.rig.graph, 4);
            s.rig.view.add(picking);
            // x = 160 (1 + x / (6 tan 30 * 1.6))
            picking.pixel(0, 160 - 46, 100);
            picking.pixel(1, 160 + 46, 100);
            picking.pixel(2, 160, 20);
            assertThat(picking.instance(0)).as("before any frame").isEqualTo(GpuPicking.NOTHING);
            for (int i = 0; i < 4; i++) {
                s.rig.frame();
            }
            assertThat(picking.frame()).isPositive();
            assertThat(picking.instance(0)).isEqualTo(0);
            assertThat(picking.instance(1)).isEqualTo(1);
            assertThat(picking.instance(2)).as("the sky").isEqualTo(GpuPicking.NOTHING);
            assertThat(picking.answeredX(1)).isEqualTo(206);
            assertThat(picking.instance(3)).isEqualTo(GpuPicking.NOTHING);
            picking.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
    void benchmarkAtTheCpuPathsScale() {
        int side = 317; // 100,489 instances
        for (boolean visibility : new boolean[] {false, true}) {
            try (SceneGpuRig s = SceneGpuRig.open(1920, 1080, visibility, side * side, true)) {
                assumeThat(s.rig.device.primitiveIds()).isTrue();
                int[] materials = {s.unlit(0.8f, 0.2f, 0.2f), s.unlit(0.2f, 0.8f, 0.2f), s.unlit(0.2f, 0.3f, 0.9f)};
                for (int z = 0; z < side; z++) {
                    for (int x = 0; x < side; x++) {
                        s.box(z * side + x, materials[(x + z) % 3], (x - side / 2.0) * 1.5, 0, -(z * 1.5) - 4, 0.4f);
                    }
                }
                s.camera.position(0, 30, 20).set(0, -0.6, -1, 0, 1, 0, Math.toRadians(60), 1920, 1080, 0.1);
                GpuPicking picking = visibility ? new GpuPicking(s.rig.graph, 16) : null;
                if (picking != null) {
                    s.rig.view.add(picking);
                    for (int i = 0; i < 16; i++) {
                        picking.pixel(i, 100 + i * 110, 300 + i * 40);
                    }
                }
                s.rig.graph.timings(true);
                int frames = 60;
                double[] total = new double[frames];
                double[] pick = new double[frames];
                double[] ids = new double[frames];
                long recorded = 0;
                for (int f = -20; f < frames; f++) {
                    s.rig.frame();
                    recorded++;
                    GpuTimings t = s.rig.graph.timings();
                    if (f >= 0 && t != null) {
                        double start = t.frameStartMillis();
                        total[f] = t.totalMillis() + (Double.isNaN(start) ? 0 : start);
                        for (int p = 0; p < t.count(); p++) {
                            String name = t.pass(p);
                            if (name.startsWith("gpu picking")) {
                                pick[f] += t.millis(p);
                            } else if (name.contains("ids")) {
                                ids[f] += t.millis(p);
                            }
                        }
                    }
                }
                String path = visibility ? "visibility buffer" : "clustered forward";
                System.out.printf("%s, %,d instances, 1920 x 1080: frame %.3f ms median, ID passes %.3f ms, picking"
                    + " (dispatch and readback copy) %.4f ms%n", path, side * side, median(total), median(ids),
                    median(pick));
                if (picking != null) {
                    System.out.printf("answers lag %d frame(s) behind the frame recorded (frames waited for here)%n",
                        recorded - picking.frame());
                    int hits = 0;
                    for (int i = 0; i < 16; i++) {
                        hits += picking.instance(i) >= 0 ? 1 : 0;
                    }
                    System.out.printf("%d of 16 pointers over an instance%n", hits);
                    picking.close();
                }
                StringBuilder passes = new StringBuilder();
                GpuTimings t = s.rig.graph.timings();
                for (int p = 0; p < t.count(); p++) {
                    passes.append(String.format("  %s %.3f%n", t.pass(p), t.millis(p)));
                }
                System.out.print(passes);
            }
        }
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
