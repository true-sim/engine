package com.truesim.engine.demo;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.shadows.CascadedShadowMaps;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hitches while content streams: a camera flies over a grid of cells; cells coming into range get meshes of their own
 * (built on worker threads, as a game would), a texture, materials and instances, and cells leaving range lose them,
 * while frames run as an application's do (frames in flight, paced at 90 Hz as a headset's). Every frame's work is
 * timed (streaming, recording, waiting for the GPU); the report gives the percentiles and the slowest frames with what
 * happened in them. {@code ./gradlew
 * :engine-demo:streamBench}.
 */
public final class StreamBench {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int FRAMES = 2700; // 30 seconds at 90 Hz
    private static final long BUDGET = 1_000_000_000L / 90; // a headset's frame
    private static final double CELL = 24;
    private static final int RANGE = 3; // cells each way
    private static final double SPEED = 10; // units per second
    private static final int MESHES_PER_CELL = 3;
    private static final int INSTANCES_PER_CELL = 40;

    /** A cell in range: what it put on the GPU, once its meshes are built. */
    private static final class Cell {
        final long key;
        CompletableFuture<Built> building;
        int[] meshes = new int[0];
        int texture = -1;
        int[] materials = new int[0];
        int[] instances = new int[0];

        Cell(long key) {
            this.key = key;
        }
    }

    /** What happened in a frame, for the report; its GPU time once known (NaN until then). */
    private static final class Frame {
        final int index;
        final double millis;
        final int meshesAdded;
        final int meshesRemoved;
        final int textures;
        final int builds;
        double gpu = Double.NaN;

        Frame(int index, double millis, int meshesAdded, int meshesRemoved, int textures, int builds) {
            this.index = index;
            this.millis = millis;
            this.meshesAdded = meshesAdded;
            this.meshesRemoved = meshesRemoved;
            this.textures = textures;
            this.builds = builds;
        }

        double millis() {
            return millis;
        }

        int meshesAdded() {
            return meshesAdded;
        }

        int meshesRemoved() {
            return meshesRemoved;
        }

        int builds() {
            return builds;
        }
    }

    /**
     * A cell's content, made on a worker thread: its meshes and its texture's texels, and (unless {@code direct}) both
     * prepared for the GPU there too, so the frame's thread only adds them.
     */
    private record Built(List<MeshData> meshes, MemorySegment texels, int textureSize,
        List<GeometryPool.Prepared> preparedMeshes, Textures.Prepared preparedTexture) {
    }

    private static boolean direct; // meshes and textures staged on the frame's thread, as before preparing existed
    private static GeometryPool sharedPool; // for the workers' preparing
    private static Textures sharedTextures;

    private static boolean large;
    // Render-thread time in the engine's calls, for the report: meshes added, textures added; total and worst ms.
    private static final double[] meshMillis = new double[2];
    private static final double[] textureMillis = new double[2];

    private static void spent(double[] into, long since) {
        double ms = (System.nanoTime() - since) / 1e6;
        into[0] += ms;
        into[1] = Math.max(into[1], ms);
    } // big meshes and textures: terrain tiles of 130k triangles, 2048 x 2048 texels

    private static com.truesim.engine.render.RayTracing rayTracing; // or null

    private StreamBench() {
    }

    public static void main(String[] args) throws Exception {
        int color = VK_FORMAT_R16G16B16A16_SFLOAT;
        int depth = VK_FORMAT_D32_SFLOAT;
        boolean rays = List.of(args).contains("rays"); // bottom levels for ray tracing too, as meshes come
        large = List.of(args).contains("large");
        direct = List.of(args).contains("direct");
        ExecutorService builders = Executors.newFixedThreadPool(3);
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("stream bench").surfaces(false)
                 .build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameGraph graph = new FrameGraph(device, 2).timings(true); // GPU time per frame; no rebuilds here
             GeometryPool pool = new GeometryPool(device, large ? GeometryPool.Capacity.of(1 << 24, 1 << 20, 512)
                 : GeometryPool.Capacity.of(1 << 22, 1 << 17, 512),
                 graph.frameSlots());
             Materials materials = new Materials(device, 1024, graph.frameSlots());
             Textures textures = new Textures(device, graph.frameSlots());
             GpuScene scene = new GpuScene(device, pool, 8192, graph.frameSlots());
             Lights lights = new Lights(device, 4, 8, graph.frameSlots())) {
            scene.setMaterials(materials);
            sharedPool = pool;
            sharedTextures = textures;
            com.truesim.engine.render.RayTracing rayTracing = rays && device.rayTracing()
                ? new com.truesim.engine.render.RayTracing(device, pool, 512) : null;
            StreamBench.rayTracing = rayTracing;
            MeshletRenderer renderer = new MeshletRenderer(device, scene, color, depth, graph.frameSlots());
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            int ground = pool.add(DemoMeshes.terrain(64));
            int groundMaterial = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(0.3f, 0.45f, 0.25f, 1))));
            lights.directional(0, -0.4f, -0.8f, -0.45f, 2.5f, 2.3f, 2.0f);
            lights.setCount(1);
            int usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            GpuImage target = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, usage,
                VK_IMAGE_ASPECT_COLOR_BIT);
            Camera camera = new Camera();
            ViewPort port = graph.addView("main").size(WIDTH, HEIGHT);
            SceneView view = geometry.createView(port, camera, ViewOptions.of(1 << 14, 1 << 22).withOcclusion(true))
                .set(1, MeshletRenderer.MODE_LIT);
            CascadedShadowMaps sun = new CascadedShadowMaps(geometry, lights, 0, 0, view, 3, 2048,
                ViewOptions.shadow(1 << 16, 1 << 22), 120);
            port.add(new ClusteredForwardShading(geometry, view, color, depth).clearColor(0.4f, 0.55f, 0.8f))
                .add(new ClusteredLights(lights, view)).add(sun)
                .output(Keys.SCENE_COLOR, new ExternalImage("target", WIDTH, HEIGHT, Format.R8G8B8A8_UNORM, usage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(),
                    target.view()));
            // The ground follows the camera, a tile under it.
            Map<Long, Cell> cells = new HashMap<>();
            ArrayDeque<Integer> freeIds = new ArrayDeque<>();
            for (int id = 8191; id >= 1; id--) {
                freeIds.push(id);
            }
            List<Frame> frames = new ArrayList<>();
            long seed = 1;
            long next = System.nanoTime();
            for (int f = 0; f < FRAMES; f++) {
                // Paced as a headset paces frames: each starts at its time; its work is timed, the rest idles.
                while (System.nanoTime() < next) {
                    java.util.concurrent.locks.LockSupport.parkNanos(Math.max(1, next - System.nanoTime() - 200_000));
                }
                long start = System.nanoTime();
                next = Math.max(next + BUDGET, start);
                double x = f * SPEED / 90.0;
                camera.position(x, 8, 0).set(1, -0.25, 0.15, 0, 1, 0, (70 * Math.PI / 180), WIDTH, HEIGHT, 0.1);
                scene.set(0, ground, groundMaterial, (float) x, 0f, 0f, 0f, 0f, 0f, 1f, 4f, 1f, 4f);
                int builds = graph.builds();
                int[] added = {0};
                int removed = 0;
                int texturesAdded = 0;
                // Cells in range: start building those new to it; place those whose meshes are built.
                int cx = (int) Math.floor(x / CELL);
                java.util.Set<Long> wanted = new java.util.HashSet<>();
                for (int i = cx - RANGE; i <= cx + RANGE; i++) {
                    for (int j = -RANGE; j <= RANGE; j++) {
                        long key = (long) i << 32 | (j & 0xffffffffL);
                        wanted.add(key);
                        if (!cells.containsKey(key)) {
                            Cell cell = new Cell(key);
                            long cellSeed = seed++;
                            cell.building = CompletableFuture.supplyAsync(() -> buildMeshes(cellSeed), builders);
                            cells.put(key, cell);
                        }
                    }
                }
                for (Cell cell : cells.values()) {
                    if (cell.building != null && cell.building.isDone()) {
                        texturesAdded += place(cell, cell.building.get(), pool, textures, materials, scene, freeIds,
                            added);
                        cell.building = null;
                    }
                }
                for (java.util.Iterator<Cell> it = cells.values().iterator(); it.hasNext();) {
                    Cell cell = it.next();
                    if (!wanted.contains(cell.key) && cell.building == null) {
                        removed += clear(cell, pool, textures, materials, scene, freeIds);
                        it.remove();
                    }
                }
                sun.update();
                graph.beginFrame();
                graph.submit(0, 0);
                long now = System.nanoTime();
                frames.add(new Frame(f, (now - start) / 1e6, added[0], removed, texturesAdded,
                    graph.builds() - builds));
                com.truesim.engine.graph.GpuTimings gpu = graph.timings();
                if (gpu != null && gpu.frame() >= 1 && gpu.frame() <= frames.size()) {
                    frames.get((int) gpu.frame() - 1).gpu = gpu.totalMillis(); // frames count from 1
                }
            }
            device.waitIdle();
            long placed = cells.values().stream().filter(c -> c.building == null).count();
            System.out.println("Cells in range " + cells.size() + ", placed " + placed + ", meshes "
                + pool.meshCount() + ", camera x " + (FRAMES * SPEED / 90.0));
            report(frames, pool, textures, materials);
            for (Cell cell : cells.values()) {
                if (cell.building == null) {
                    clear(cell, pool, textures, materials, scene, freeIds);
                } else if (!direct) { // built but never placed: its prepared content freed
                    Built unplaced = cell.building.get();
                    unplaced.preparedMeshes().forEach(GeometryPool.Prepared::close);
                    unplaced.preparedTexture().close();
                }
            }
            device.waitIdle();
            renderer.close();
            target.close();
            if (rayTracing != null) {
                rayTracing.close();
            }
        } finally {
            builders.shutdownNow();
        }
    }

    /** A cell's content, on a worker thread: rocks of their own shapes (or a terrain tile), and a texture's texels. */
    private static Built buildMeshes(long seed) {
        List<MeshData> meshes = new ArrayList<>();
        if (large) {
            meshes.add(DemoMeshes.terrain(256)); // 66,000 vertices, 131,000 triangles
            meshes.add(DemoMeshes.rock(4));
        } else {
            for (int m = 0; m < MESHES_PER_CELL; m++) {
                meshes.add(DemoMeshes.rock(3 + (int) ((seed + m) % 2)));
            }
        }
        int size = large ? 2048 : 256;
        MemorySegment texels = Arena.ofAuto().allocate(4L * size * size);
        int tint = (int) (seed * 2654435761L);
        for (int i = 0; i < size * size; i++) {
            texels.setAtIndex(ValueLayout.JAVA_INT, i, 0xff000000 | (tint ^ i * 31) & 0xffffff);
        }
        if (direct) {
            return new Built(meshes, texels, size, null, null);
        }
        List<GeometryPool.Prepared> prepared = new ArrayList<>();
        for (MeshData mesh : meshes) {
            prepared.add(sharedPool.prepare(mesh));
        }
        return new Built(meshes, texels, size, prepared, sharedTextures.prepareColor(size, size, texels));
    }

    /** Puts a built cell on the GPU; the textures added. */
    private static int place(Cell cell, Built content, GeometryPool pool, Textures textures, Materials materials,
        GpuScene scene, ArrayDeque<Integer> freeIds, int[] added) {
        List<MeshData> built = content.meshes();
        cell.meshes = new int[built.size()];
        for (int m = 0; m < built.size(); m++) {
            long since = System.nanoTime();
            cell.meshes[m] = direct ? pool.add(built.get(m)) : pool.add(content.preparedMeshes().get(m));
            spent(meshMillis, since);
            if (rayTracing != null) {
                rayTracing.add(cell.meshes[m], built.get(m), built.get(m).levels().size() - 1);
            }
            added[0]++;
        }
        long since = System.nanoTime();
        cell.texture = direct ? textures.color(content.textureSize(), content.textureSize(), content.texels())
            : textures.add(content.preparedTexture());
        spent(textureMillis, since);
        cell.materials = new int[] {
            materials.add(new Material(MetallicRoughnessParameters.DEFAULTS.withBaseColorTexture(cell.texture))),
            materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(0.6f, 0.5f, 0.4f, 1)).withRoughness(0.7f)))};
        double baseX = (cell.key >> 32) * CELL;
        double baseZ = (int) cell.key * CELL;
        cell.instances = new int[INSTANCES_PER_CELL];
        java.util.SplittableRandom random = new java.util.SplittableRandom(cell.key);
        for (int i = 0; i < INSTANCES_PER_CELL; i++) {
            int id = freeIds.pop();
            cell.instances[i] = id;
            double x = baseX + random.nextDouble(CELL);
            double z = baseZ + random.nextDouble(CELL);
            float s = (float) (0.3 + random.nextDouble(1.5));
            if (large && i == 0) { // the cell's terrain tile, textured, once
                float tile = (float) (CELL / 256);
                scene.set(id, cell.meshes[0], cell.materials[0], baseX + CELL / 2, 0.0, baseZ + CELL / 2, 0, 0, 0, 1,
                    tile, tile, tile);
                continue;
            }
            int mesh = large ? cell.meshes[1] : cell.meshes[i % cell.meshes.length];
            scene.set(id, mesh, cell.materials[i % 2], x, DemoMeshes.height(x, z) + s * 0.6, z, 0, 0, 0, 1, s, s, s);
        }
        return 1;
    }

    /** Takes a cell off the GPU; the meshes removed. */
    private static int clear(Cell cell, GeometryPool pool, Textures textures, Materials materials, GpuScene scene,
        ArrayDeque<Integer> freeIds) {
        for (int id : cell.instances) {
            scene.remove(id);
            freeIds.push(id);
        }
        for (int material : cell.materials) {
            materials.remove(material);
        }
        if (cell.texture >= 0) {
            textures.remove(cell.texture);
        }
        for (int mesh : cell.meshes) {
            if (rayTracing != null) {
                rayTracing.remove(mesh);
            }
            pool.remove(mesh);
        }
        return cell.meshes.length;
    }

    private static void report(List<Frame> frames, GeometryPool pool, Textures textures, Materials materials) {
        List<Frame> timed = frames.subList(60, frames.size()); // after start-up
        double[] millis = timed.stream().mapToDouble(Frame::millis).sorted().toArray();
        System.out.printf(Locale.ROOT, "Frame work, %d frames at 90 Hz: p50 %.2f ms, p95 %.2f, p99 %.2f, max %.2f; over "
                + "the 11.1 ms budget: %d, over 4 ms: %d%n", millis.length, at(millis, 0.5), at(millis, 0.95),
            at(millis, 0.99), millis[millis.length - 1], Arrays.stream(millis).filter(m -> m > 11.1).count(),
            Arrays.stream(millis).filter(m -> m > 4).count());
        int added = timed.stream().mapToInt(Frame::meshesAdded).sum();
        int removed = timed.stream().mapToInt(Frame::meshesRemoved).sum();
        int builds = timed.stream().mapToInt(Frame::builds).sum();
        System.out.printf(Locale.ROOT, "Streamed: %d meshes added, %d removed, %d graph rebuilds; at the end %d meshes, "
            + "%d textures, %d materials%n", added, removed, builds, pool.meshCount(), textures.count(),
            materials.count());
        double plain = timed.stream().filter(f -> f.meshesAdded() == 0 && f.meshesRemoved() == 0 && f.builds() == 0)
            .mapToDouble(Frame::millis).average().orElse(0);
        double adding = timed.stream().filter(f -> f.meshesAdded() > 0).mapToDouble(Frame::millis).average()
            .orElse(0);
        double removing = timed.stream().filter(f -> f.meshesRemoved() > 0).mapToDouble(Frame::millis).average()
            .orElse(0);
        double rebuilding = timed.stream().filter(f -> f.builds() > 0).mapToDouble(Frame::millis).average()
            .orElse(0);
        System.out.printf(Locale.ROOT, "Mean ms: frames streaming nothing %.2f, adding %.2f, removing %.2f, rebuilding "
            + "%.2f%n", plain, adding, removing, rebuilding);
        double[] gpu = timed.stream().mapToDouble(f -> f.gpu).filter(g -> !Double.isNaN(g)).sorted().toArray();
        System.out.printf(Locale.ROOT, "GPU per frame, %d timed: p50 %.2f ms, p95 %.2f, p99 %.2f, max %.2f; over 11.1 "
                + "ms: %d%n", gpu.length, at(gpu, 0.5), at(gpu, 0.95), at(gpu, 0.99), gpu[gpu.length - 1],
            Arrays.stream(gpu).filter(g -> g > 11.1).count());
        System.out.println("Slowest GPU frames:");
        timed.stream().filter(f -> !Double.isNaN(f.gpu)).sorted((a, b) -> Double.compare(b.gpu, a.gpu)).limit(8)
            .forEach(f -> System.out.printf(Locale.ROOT, "  frame %4d  GPU %7.2f ms (CPU %5.2f)  meshes +%d -%d, "
                + "textures +%d%n", f.index, f.gpu, f.millis, f.meshesAdded, f.meshesRemoved, f.textures));
        System.out.printf(Locale.ROOT, "Render thread in pool.add: %.1f ms in all, %.2f at worst; in textures.color: %.1f "
            + "in all, %.2f at worst%n", meshMillis[0], meshMillis[1], textureMillis[0], textureMillis[1]);
        System.out.println("Slowest frames:");
        timed.stream().sorted((a, b) -> Double.compare(b.millis(), a.millis())).limit(12).forEach(f ->
            System.out.printf(Locale.ROOT, "  frame %4d  %7.2f ms  meshes +%d -%d, textures +%d, rebuilds %d%n",
                f.index, f.millis, f.meshesAdded, f.meshesRemoved, f.textures, f.builds));
    }

    private static double at(double[] sorted, double fraction) {
        return sorted[Math.min(sorted.length - 1, (int) (fraction * sorted.length))];
    }
}
