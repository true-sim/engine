package com.truesim.engine.interaction;

import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.Camera;
import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.PickMesh;
import com.truesim.scene.core.Pickable;
import com.truesim.scene.core.SceneEventType;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.SpatialIndex;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Picking at frame start over 100,000 nodes (half static, half moving every frame), one mouse pointer moving across
 * the view each frame: the interaction's whole frame (scene and index updates excluded) and the picker's raycast alone,
 * with 1% or all nodes pickable, by their boxes or by a sphere mesh of 960 triangles. Medians of 2,000 frames after
 * 2,000 of warm-up; bytes allocated per frame. Run with {@code TRUESIM_BENCHMARK=true}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class ScenePickingBenchmark {

    private static final int NODES = 100_000;

    @Test
    void picking100kNodes() {
        System.out.printf(Locale.ROOT, "Scene picking, %,d nodes, 1 pointer per frame:%n", NODES);
        for (int every : new int[] {100, 1}) {
            for (boolean mesh : new boolean[] {false, true}) {
                run(every, mesh);
            }
        }
    }

    private static void run(int every, boolean mesh) {
        SceneGraph scene = new SceneGraph(new ChangeClock(), NODES + 16, SceneGraph.DEFAULT_LOG_CAPACITY);
        SpatialIndex index = new SpatialIndex(scene);
        SceneInteraction interaction = new SceneInteraction();
        InteractionWorld world = interaction.world(scene, index).updatesScene(false);
        Camera camera = new Camera().position(0, 20, 0).set(0, -0.3, -1, 0, 1, 0, Math.PI / 3, 1920, 1080, 0.1);
        world.view(camera).rect(0, 0, 1920, 1080);
        PickMesh sphere = sphere();
        SplittableRandom random = new SplittableRandom(7);
        long[] moving = new long[NODES / 2];
        int m = 0;
        for (int i = 0; i < NODES; i++) {
            long node = scene.create();
            scene.setTranslation(node, random.nextDouble(-500, 500), random.nextDouble(0, 10),
                random.nextDouble(-1000, 0));
            scene.setLocalBounds(node, -1, -1, -1, 1, 1, 1);
            boolean dynamic = i % 2 == 0;
            scene.setMobility(node, dynamic ? Mobility.DYNAMIC : Mobility.STATIC);
            if (dynamic) {
                moving[m++] = node;
            }
            if (i % every == 0) {
                scene.components().set(node, Pickable.TYPE, mesh ? Pickable.mesh(sphere) : Pickable.bounds());
                world.on(node, SceneEventType.ALL_MASK, e -> { });
            }
        }
        float[] at = {0, 540};
        int[] buttons = {0};
        interaction.pointers(p -> p.pointer(1, PointerKind.MOUSE).moveTo(at[0], at[1]).buttons(buttons[0]));
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        int frames = 4000;
        double[] whole = new double[frames];
        double[] picks = new double[frames];
        long allocated = 0;
        long nanos = 0;
        com.truesim.scene.core.PickHits hits = new com.truesim.scene.core.PickHits(4);
        double[] ray = new double[6];
        for (int f = 0; f < frames; f++) {
            for (int k = f % 64; k < moving.length; k += 64) {
                scene.setTranslation(moving[k], (f * 0.37 + k) % 1000 - 500, 5, -(k % 1000));
            }
            scene.update();
            index.update();
            at[0] = (f * 7.3f) % 1920;
            at[1] = 300 + (f * 3.1f) % 600;
            buttons[0] = f % 10 < 5 ? ScenePointer.PRIMARY : 0;
            nanos += 16_000_000L;
            long before = threads.getThreadAllocatedBytes(thread);
            long t0 = System.nanoTime();
            interaction.update(nanos);
            long t1 = System.nanoTime();
            ScenePointer p = interaction.pointer(0);
            p.ray(ray);
            world.picker().beginFrame();
            long t2 = System.nanoTime();
            world.picker().pick(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], 100_000, -1, hits);
            long t3 = System.nanoTime();
            if (f >= frames / 2) {
                whole[f - frames / 2] = (t1 - t0) / 1e3;
                picks[f - frames / 2] = (t3 - t2) / 1e3;
                allocated += threads.getThreadAllocatedBytes(thread) - before;
            }
        }
        int n = frames / 2;
        java.util.Arrays.sort(whole, 0, n);
        java.util.Arrays.sort(picks, 0, n);
        System.out.printf(Locale.ROOT, "  %s pickable, %s: frame %.1f us (p99 %.1f), raycast %.1f us (p99 %.1f), "
            + "%d bytes allocated per frame%n", every == 1 ? "all" : "1%", mesh ? "mesh (960 triangles)" : "bounds",
            whole[n / 2], whole[n * 99 / 100], picks[n / 2], picks[n * 99 / 100], allocated / n);
    }

    /** A UV sphere of radius 1: 960 triangles. */
    static PickMesh sphere() {
        int rings = 16;
        int segments = 32;
        float[] positions = new float[(rings + 1) * (segments + 1) * 3];
        int k = 0;
        for (int r = 0; r <= rings; r++) {
            double phi = Math.PI * r / rings;
            for (int s = 0; s <= segments; s++) {
                double theta = 2 * Math.PI * s / segments;
                positions[k++] = (float) (Math.sin(phi) * Math.cos(theta));
                positions[k++] = (float) Math.cos(phi);
                positions[k++] = (float) (Math.sin(phi) * Math.sin(theta));
            }
        }
        int[] indices = new int[rings * segments * 6];
        k = 0;
        for (int r = 0; r < rings; r++) {
            for (int s = 0; s < segments; s++) {
                int a = r * (segments + 1) + s;
                int b = a + segments + 1;
                indices[k++] = a;
                indices[k++] = b;
                indices[k++] = a + 1;
                indices[k++] = a + 1;
                indices[k++] = b;
                indices[k++] = b + 1;
            }
        }
        return new PickMesh(positions, java.util.Arrays.copyOf(indices, 960 * 3));
    }
}
