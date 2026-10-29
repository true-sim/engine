package com.truesim.engine.scene;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.meshbuild.MeshBuilder;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderables;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The meshlet benchmark's 30,000 rocks on the terrain, through the whole scene path: the rock and terrain meshes
 * generated in the asset cache, 30,001 scene nodes with renderables, a {@link SceneView} on shared
 * {@link SceneResources}, drawn by a frame graph with two frames in flight at 1920 x 1080. Prints the time to load
 * and upload, and per frame the time of each stage (scene, renderables, view, the graph's recording and submission),
 * the whole frame and what the main thread allocated, for a still scene, a moving camera, moving rocks and rocks
 * coming and going. Run with {@code TRUESIM_BENCHMARK=true ./gradlew :engine-scene:test --tests '*SceneBenchmark'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class SceneBenchmark {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int ROCKS = 30_000;
    private static final int FRAMES = 20;
    private static final double EXTENT = 600;
    private static final double GROUND_Y = -12;
    /** Where the world puts the scene: far from its origin, so instances must be camera-relative. */
    private static final double FAR = 1e6;

    record Rock(int subdivisions) {
    }

    record Terrain(int size) {
    }

    record Stone(float r, float g, float b) {
    }

    static final Generator<Rock, MeshData> ROCK_MESHES = (rock, seed) -> rock(rock.subdivisions());
    static final Generator<Terrain, MeshData> TERRAINS = (terrain, seed) -> terrain(terrain.size());
    static final Generator<Stone, MaterialData> STONES = (stone, seed) -> MaterialData.builder()
        .baseColor(stone.r(), stone.g(), stone.b(), 1).roughness(0.8f).build();
    static final AssetRef<MeshData> ROCK = AssetRef.generated(RenderAssets.MESH, ROCK_MESHES, new Rock(6), 1);
    static final AssetRef<MeshData> GROUND = AssetRef.generated(RenderAssets.MESH, TERRAINS, new Terrain(1024), 1);
    static final AssetRef<MaterialData> GRANITE =
        AssetRef.generated(RenderAssets.MATERIAL, STONES, new Stone(0.5f, 0.5f, 0.52f), 1);
    static final AssetRef<MaterialData> GRASS =
        AssetRef.generated(RenderAssets.MATERIAL, STONES, new Stone(0.3f, 0.5f, 0.2f), 1);

    private static final com.sun.management.ThreadMXBean THREADS =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** Per-frame timings of one scenario, in nanoseconds, and bytes allocated. */
    private static final class Stats {
        final long[][] stages = new long[5][FRAMES]; // scene, renderables, view, graph, frame
        final long[] allocated = new long[FRAMES];

        void print(String scenario) {
            String[] names = {"scene", "renderables", "view", "graph", "frame"};
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %-34s", scenario));
            for (int s = 0; s < names.length; s++) {
                long[] sorted = stages[s].clone();
                Arrays.sort(sorted);
                line.append(String.format(Locale.ROOT, " %s %7.3f", names[s], sorted[FRAMES / 2] / 1e6));
            }
            long[] bytes = allocated.clone();
            Arrays.sort(bytes);
            line.append(String.format(Locale.ROOT, " ms median; %,d bytes allocated (median), %,d max",
                bytes[FRAMES / 2], bytes[FRAMES - 1]));
            System.out.println(line);
        }
    }

    /** Changes the scenario makes to the scene before frame {@code frame}. */
    @FunctionalInterface
    private interface Change {
        void before(int frame);
    }

    @Test
    void drawTheRocksThroughTheScene() throws InterruptedException {
        FrameSlots slots = new FrameSlots(2);
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("benchmark").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 100_000, 4), slots);
             Materials materials = new Materials(device, 64, slots);
             Textures textures = new Textures(device, slots);
             TaskScheduler tasks = new TaskScheduler();
             AssetCache assets = new AssetCache(tasks);
             SceneResources resources = new SceneResources(assets, pool, materials, textures);
             MeshletRenderer renderer = new MeshletRenderer(device, pool,
                 new MeshletRenderer.Limits(40_000, 1 << 20, 65_535, 80_000_000, 4), VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, slots);
             GpuImage shaded = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
             FrameGraph graph = new FrameGraph(device, slots)) {
            System.out.printf(Locale.ROOT, "Scene benchmark on %s, %d x %d, %,d rocks on the terrain, 2 frames in "
                + "flight:%n", device.gpu().name(), WIDTH, HEIGHT, ROCKS);
            // The scene: the terrain and the rocks resting on it, far from the world's origin.
            ChangeClock clock = new ChangeClock();
            SceneGraph scene = new SceneGraph(clock, ROCKS + 1024, SceneGraph.DEFAULT_LOG_CAPACITY);
            Renderables renderables = new Renderables(scene);
            long start = System.nanoTime();
            long ground = scene.create();
            scene.setTranslation(ground, FAR, GROUND_Y, FAR);
            renderables.set(ground, GROUND, List.of(GRASS));
            long[] rocks = new long[ROCKS];
            double[] placed = new double[ROCKS * 3];
            SplittableRandom random = new SplittableRandom(11);
            for (int i = 0; i < ROCKS; i++) {
                rocks[i] = scene.create();
                place(scene, rocks[i], random, placed, i);
                renderables.set(rocks[i], ROCK, List.of(GRANITE));
            }
            System.out.printf(Locale.ROOT, "  building the scene: %.1f ms%n", (System.nanoTime() - start) / 1e6);

            Camera camera = new Camera().set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
            SceneView view = new SceneView(resources, renderables, renderer);
            view.setOrigin(FAR, 0, FAR);
            ExternalImage target = new ExternalImage("shaded", WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .set(shaded.handle(), 0);
            ClusteredForwardShading forward = new ClusteredForwardShading(view.geometry(), WIDTH, HEIGHT,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
            graph.add(forward).output(Keys.SCENE_COLOR, target);
            Stats unused = new Stats();

            // Loading: the meshes are generated in the background, uploaded as they arrive at frame boundaries.
            start = System.nanoTime();
            frame(graph, slots, tasks, scene, renderables, view, camera, unused, 0);
            long firstUpdate = unused.stages[2][0];
            int frames = 1;
            while (resources.uploadedMeshCount() < 2) {
                Thread.sleep(1);
                frame(graph, slots, tasks, scene, renderables, view, camera, unused, 0);
                frames++;
            }
            long loaded = System.nanoTime() - start;
            long uploadFrame = unused.stages[2][0];
            System.out.printf(Locale.ROOT, "  loading: %.1f ms over %d frames to generate and upload the rock "
                + "(%,d meshlets) and the terrain (%,d meshlets); first view update (%,d renderables waiting) %.3f ms,"
                + " the frame they arrived %.1f ms; %,d instances drawn%n", loaded / 1e6, frames,
                assets.get(ROCK).meshletCount(), assets.get(GROUND).meshletCount(), view.renderableCount(),
                firstUpdate / 1e6, uploadFrame / 1e6, view.instanceCount());

            run("still", graph, slots, tasks, scene, renderables, view, camera, frame -> { });
            run("camera moving (all rewritten)", graph, slots, tasks, scene, renderables, view, camera,
                frame -> view.setOrigin(FAR + 0.05 * frame, 0, FAR - 0.1 * frame));
            run("1,000 rocks moving", graph, slots, tasks, scene, renderables, view, camera, frame -> {
                for (int i = 0; i < 1000; i++) {
                    int r = (frame * 1000 + i) % ROCKS;
                    scene.setTranslation(rocks[r], placed[3 * r], placed[3 * r + 1] + 0.01 * (frame % 7),
                        placed[3 * r + 2]);
                }
            });
            run("30,000 rocks moving", graph, slots, tasks, scene, renderables, view, camera, frame -> {
                for (int r = 0; r < ROCKS; r++) {
                    scene.setTranslation(rocks[r], placed[3 * r], placed[3 * r + 1] + 0.01 * (frame % 7),
                        placed[3 * r + 2]);
                }
            });
            SplittableRandom churn = new SplittableRandom(3);
            run("100 rocks removed, 100 added", graph, slots, tasks, scene, renderables, view, camera, frame -> {
                for (int i = 0; i < 100; i++) {
                    int r = churn.nextInt(ROCKS);
                    scene.remove(rocks[r]);
                    rocks[r] = scene.create();
                    place(scene, rocks[r], churn, placed, r);
                    renderables.set(rocks[r], ROCK, List.of(GRANITE));
                }
            });
            run("100 rocks changing material", graph, slots, tasks, scene, renderables, view, camera, frame -> {
                for (int i = 0; i < 100; i++) {
                    int r = churn.nextInt(ROCKS);
                    renderables.setMaterials(rocks[r],
                        List.of(renderables.materials(rocks[r]).getFirst() == GRANITE ? GRASS : GRANITE));
                }
            });
            if (device.primitiveIds()) {
                graph.replace(new VisibilityBufferShading(view.geometry(), WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_FORMAT_D32_SFLOAT));
                run("still, visibility buffer", graph, slots, tasks, scene, renderables, view, camera, frame -> { });
                run("camera moving, visibility buffer", graph, slots, tasks, scene, renderables, view, camera,
                    frame -> view.setOrigin(FAR - 0.05 * frame, 0, FAR + 0.1 * frame));
            }
            System.out.printf(Locale.ROOT, "  %,d instances, %,d meshlets visible in the last frame, %d meshes, "
                + "%d material blocks%n", view.instanceCount(), renderer.visibleMeshlets(), resources.meshCount(),
                resources.materialSetCount());
            device.waitIdle();
            view.close();
        }
    }

    /** A rock resting on the terrain: half sunk, scaled 0.5 to 3, turned at random, ahead of the camera. */
    private static void place(SceneGraph scene, long node, SplittableRandom random, double[] placed, int i) {
        double x = random.nextDouble(-EXTENT / 2, EXTENT / 2);
        double z = random.nextDouble(-EXTENT - 5, -5);
        double scale = random.nextDouble(0.5, 3);
        double qx = random.nextDouble(-1, 1);
        double qy = random.nextDouble(-1, 1);
        double qz = random.nextDouble(-1, 1);
        double qw = random.nextDouble(-1, 1);
        double length = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        placed[3 * i] = FAR + x;
        placed[3 * i + 1] = GROUND_Y + terrainHeight(x, z) + 0.4 * scale;
        placed[3 * i + 2] = FAR + z;
        scene.setTranslation(node, placed[3 * i], placed[3 * i + 1], placed[3 * i + 2]);
        scene.setRotation(node, qx / length, qy / length, qz / length, qw / length);
        scene.setScale(node, scale, scale, scale);
    }

    private static void run(String scenario, FrameGraph graph, FrameSlots slots, TaskScheduler tasks,
        SceneGraph scene, Renderables renderables, SceneView view, Camera camera, Change change) {
        Stats stats = new Stats();
        for (int frame = -2; frame < FRAMES; frame++) {
            change.before(frame + 2);
            frame(graph, slots, tasks, scene, renderables, view, camera, stats, Math.max(frame, 0));
        }
        stats.print(scenario);
    }

    /** One frame as an application runs it, timed by stage into {@code stats} at {@code index}. */
    private static void frame(FrameGraph graph, FrameSlots slots, TaskScheduler tasks, SceneGraph scene,
        Renderables renderables, SceneView view, Camera camera, Stats stats, int index) {
        long allocated = THREADS.getCurrentThreadAllocatedBytes();
        long t0 = System.nanoTime();
        int slot = graph.beginFrame();
        slots.begin(slot);
        tasks.drainCompleted();
        long t1 = System.nanoTime();
        scene.update();
        long t2 = System.nanoTime();
        renderables.update();
        long t3 = System.nanoTime();
        view.update();
        view.geometry().setView(0, camera, 1, MeshletRenderer.MODE_LIT);
        long t4 = System.nanoTime();
        graph.submit(0, 0);
        slots.end();
        long t5 = System.nanoTime();
        stats.stages[0][index] = t2 - t1;
        stats.stages[1][index] = t3 - t2;
        stats.stages[2][index] = t4 - t3;
        stats.stages[3][index] = t5 - t4;
        stats.stages[4][index] = t5 - t0; // with the wait for the frame two back: GPU-bound frames show here
        stats.allocated[index] = THREADS.getCurrentThreadAllocatedBytes() - allocated;
    }

    // ---------------------------------------------------------------- the meshlet benchmark's meshes

    /** A bumpy rock: an icosahedron subdivided {@code subdivisions} times, with noise on the radius. */
    static MeshData rock(int subdivisions) {
        double t = (1 + Math.sqrt(5)) / 2;
        List<double[]> vertices = new ArrayList<>(List.of(new double[] {-1, t, 0}, new double[] {1, t, 0},
            new double[] {-1, -t, 0}, new double[] {1, -t, 0}, new double[] {0, -1, t}, new double[] {0, 1, t},
            new double[] {0, -1, -t}, new double[] {0, 1, -t}, new double[] {t, 0, -1}, new double[] {t, 0, 1},
            new double[] {-t, 0, -1}, new double[] {-t, 0, 1}));
        int[] faces = {0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11, 1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1,
            8, 3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9, 4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1};
        for (int s = 0; s < subdivisions; s++) {
            Map<Long, Integer> middles = new HashMap<>();
            int[] next = new int[faces.length * 4];
            int at = 0;
            for (int f = 0; f < faces.length; f += 3) {
                int a = faces[f];
                int b = faces[f + 1];
                int c = faces[f + 2];
                int ab = middle(vertices, middles, a, b);
                int bc = middle(vertices, middles, b, c);
                int ca = middle(vertices, middles, c, a);
                for (int i : new int[] {a, ab, ca, b, bc, ab, c, ca, bc, ab, bc, ca}) {
                    next[at++] = i;
                }
            }
            faces = next;
        }
        float[] positions = new float[vertices.size() * 3];
        for (int v = 0; v < vertices.size(); v++) {
            double[] p = vertices.get(v);
            double length = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            double x = p[0] / length;
            double y = p[1] / length;
            double z = p[2] / length;
            double radius = 1 + 0.15 * Math.sin(5 * x + 3 * y) * Math.cos(4 * z - 2 * x) + 0.05 * Math.sin(17 * y
                + 11 * z);
            positions[3 * v] = (float) (x * radius);
            positions[3 * v + 1] = (float) (y * radius);
            positions[3 * v + 2] = (float) (z * radius);
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), vertices.size()).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals(positions, faces)).submesh(faces).buildHierarchy();
    }

    private static int middle(List<double[]> vertices, Map<Long, Integer> middles, int a, int b) {
        return middles.computeIfAbsent((long) Math.min(a, b) << 32 | Math.max(a, b), k -> {
            double[] p = vertices.get(a);
            double[] q = vertices.get(b);
            vertices.add(new double[] {(p[0] + q[0]) / 2, (p[1] + q[1]) / 2, (p[2] + q[2]) / 2});
            return vertices.size() - 1;
        });
    }

    static float[] normals(float[] positions, int[] indices) {
        double[] sums = new double[positions.length];
        for (int i = 0; i < indices.length; i += 3) {
            int a = 3 * indices[i];
            int b = 3 * indices[i + 1];
            int c = 3 * indices[i + 2];
            double ux = positions[b] - positions[a];
            double uy = positions[b + 1] - positions[a + 1];
            double uz = positions[b + 2] - positions[a + 2];
            double vx = positions[c] - positions[a];
            double vy = positions[c + 1] - positions[a + 1];
            double vz = positions[c + 2] - positions[a + 2];
            double nx = uy * vz - uz * vy;
            double ny = uz * vx - ux * vz;
            double nz = ux * vy - uy * vx;
            for (int corner : new int[] {a, b, c}) {
                sums[corner] += nx;
                sums[corner + 1] += ny;
                sums[corner + 2] += nz;
            }
        }
        float[] normals = new float[positions.length];
        for (int v = 0; v < normals.length; v += 3) {
            double length = Math.sqrt(sums[v] * sums[v] + sums[v + 1] * sums[v + 1] + sums[v + 2] * sums[v + 2]);
            for (int i = 0; i < 3; i++) {
                normals[v + i] = (float) (sums[v + i] / length);
            }
        }
        return normals;
    }

    /** A bumpy {@code n} x {@code n} grid, 1 unit per quad, centered, facing +y. */
    static MeshData terrain(int n) {
        int count = (n + 1) * (n + 1);
        float[] positions = new float[count * 3];
        for (int z = 0; z <= n; z++) {
            for (int x = 0; x <= n; x++) {
                int v = z * (n + 1) + x;
                positions[3 * v] = x - n / 2f;
                positions[3 * v + 1] = (float) (3 * Math.sin(x / 9.0) * Math.cos(z / 13.0) + Math.sin(x * z / 4000.0));
                positions[3 * v + 2] = z - n / 2f;
            }
        }
        int[] indices = new int[n * n * 6];
        int at = 0;
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int a = z * (n + 1) + x;
                int[] quad = {a, a + n + 1, a + 1, a + 1, a + n + 1, a + n + 2};
                System.arraycopy(quad, 0, indices, at, 6);
                at += 6;
            }
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), count).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals(positions, indices)).submesh(indices).buildHierarchy();
    }

    /** The height of {@link #terrain}(1024), placed at the origin, at x, z. */
    static double terrainHeight(double x, double z) {
        double gx = x + 512;
        double gz = z + 512;
        return 3 * Math.sin(gx / 9.0) * Math.cos(gz / 13.0) + Math.sin(gx * gz / 4000.0);
    }
}
