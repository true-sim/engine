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
import com.truesim.engine.render.shadows.Shadows;
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
    static int frameCount = Integer.getInteger("truesim.demo.frames", 2700); // 30 seconds at 90 Hz
    static boolean paced = true; // frames start at 90 Hz; unpaced (tests), each frame waits for the builds it began
    static int builderThreads = 3;
    // -Pmemory=n: every n frames, memory by kind (GPU by category, LWJGL's native, direct buffers, the process's)
    private static final int MEMORY_EVERY = Integer.getInteger("truesim.demo.memory", 0);
    private static final long BUDGET = 1_000_000_000L / 90; // a headset's frame
    static final double CELL = 24;
    static final int RANGE = 3; // cells each way: the benchmark's default (-Prange=n)
    static final int WINDOW_RANGE = 10; // the window's default: 21 x 21 cells, the ground 250 m ahead
    static int range = RANGE;
    static int groundMaterial; // the small cells' ground tiles'
    static final double SPEED = 10; // metres a second: the benchmark's default (-Pspeed=n)
    static final double WINDOW_SPEED = 60; // the window's default: 2.5 cells a second
    static double speed = SPEED;
    private static final int MESHES_PER_CELL = 3;
    private static final int INSTANCES_PER_CELL = 40;

    /** A cell in range: what it put on the GPU, once its meshes are built. */
    static final class Cell {
        final long key;
        CompletableFuture<Built> building;
        int[] meshes = new int[0];
        int texture = -1;
        int[] materials = new int[0];
        int[] instances = new int[0];
        volatile boolean dropped; // the window's: left range before its build began, so the build is skipped
        Built placedContent; // its CPU-side content, freed a few frames after placing (once its copies recorded)
        long freeAt;

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
    /** Cells' content built and not freed yet. */
    static final java.util.concurrent.atomic.AtomicInteger UNFREED = new java.util.concurrent.atomic.AtomicInteger();

    record Built(List<MeshData> meshes, Arena arena, MemorySegment texels, int textureSize,
        List<GeometryPool.Prepared> preparedMeshes, Textures.Prepared preparedTexture) {

        /**
         * Frees the CPU-side content: the meshes' buffers (a mesh built by hand is closed by its builder once uploaded:
         * a shared arena each, which nothing else frees) and the texels.
         */
        void free() {
            meshes.forEach(MeshData::close);
            arena.close();
            UNFREED.decrementAndGet();
        }
    }

    static boolean direct; // meshes and textures staged on the frame's thread, as before preparing existed
    static GeometryPool sharedPool; // for the workers' preparing
    static Textures sharedTextures;

    static boolean large;
    static boolean heavy;
    static boolean inFrame; // copies in the frame, not on the transfer queue // larger still: terrain tiles of 295,000 triangles, 4096 x 4096 textures
    // Render-thread time in the engine's calls, for the report: meshes added, textures added; total and worst ms.
    private static final double[] meshMillis = new double[2];
    private static final double[] textureMillis = new double[2];

    private static void spent(double[] into, long since) {
        double ms = (System.nanoTime() - since) / 1e6;
        into[0] += ms;
        into[1] = Math.max(into[1], ms);
    } // big meshes and textures: terrain tiles of 130k triangles, 2048 x 2048 texels

    static com.truesim.engine.render.RayTracing rayTracing; // or null
    static boolean rays; // bottom levels for ray tracing too, as meshes come
    static boolean grow; // the pool starts small and grows as meshes come

    private StreamBench() {
    }

    /**
     * Runs the benchmark headless and prints its report; with {@code window} among {@code args}, shows the same
     * fly-over in a window instead ({@link StreamWindow}).
     */
    public static void main(String[] args) throws Exception {
        com.truesim.engine.platform.NativeMemory.install(); // before LWJGL loads, so its allocations are counted
        options(List.of(args));
        if (List.of(args).contains("window")) {
            StreamWindow.run(args);
            return;
        }
        headless(MEMORY_EVERY <= 0 ? null : (f, cells, pool, textures, materials) -> {
            if (f % MEMORY_EVERY == 0) {
                memory(f, cells, pool, textures, materials);
            }
        });
    }

    /** Called every frame of a headless run, before the frame is submitted: what is streamed in now. */
    interface Sampler {
        void sample(int frame, int cells, GeometryPool pool, Textures textures, Materials materials);
    }

    /** The headless benchmark with the options set: its frames, then its report; {@code sampler} may be null. */
    static void headless(Sampler sampler) throws Exception {
        int color = VK_FORMAT_R16G16B16A16_SFLOAT;
        int depth = VK_FORMAT_D32_SFLOAT;
        ExecutorService builders = Executors.newFixedThreadPool(builderThreads);
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("stream bench").surfaces(false)
                 .build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameGraph graph = new FrameGraph(device, 2).timings(true); // GPU time per frame; no rebuilds here
             GeometryPool pool = new GeometryPool(device, capacity(), graph.frameSlots());
             Materials materials = new Materials(device, materialCapacity(), graph.frameSlots());
             Textures textures = new Textures(device, graph.frameSlots());
             GpuScene scene = new GpuScene(device, pool, instanceCapacity(), graph.frameSlots());
             Lights lights = new Lights(device, 4, 8, graph.frameSlots())) {
            StreamBench.device = device;
            scene.setMaterials(materials);
            if (inFrame) { // render.streamBudget 0: every copy in the frame, on the graphics queue
                pool.streamBudget(0);
            }
            sharedPool = pool;
            sharedTextures = textures;
            com.truesim.engine.render.RayTracing rayTracing = rays && device.rayTracing()
                ? new com.truesim.engine.render.RayTracing(device, pool, 512) : null;
            StreamBench.rayTracing = rayTracing;
            MeshletRenderer renderer = new MeshletRenderer(device, scene, color, depth, graph.frameSlots());
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            groundMaterial = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
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
            Shadows sun = new Shadows(geometry, lights); // the sun's cascades, kept while nothing they show moves
            lights.shadow(0, com.truesim.scene.core.ShadowSpec.cascades(3, 2048, 120));
            port.add(new ClusteredForwardShading(geometry, view, color, depth).clearColor(0.4f, 0.55f, 0.8f))
                .add(new ClusteredLights(lights, view)).add(sun.view(view))
                .output(Keys.SCENE_COLOR, new ExternalImage("target", WIDTH, HEIGHT, Format.R8G8B8A8_UNORM, usage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(),
                    target.view()));
            // The ground: a tile in each cell, streamed with it.
            Map<Long, Cell> cells = new HashMap<>();
            ArrayDeque<Integer> freeIds = new ArrayDeque<>();
            for (int id = instanceCapacity() - 1; id >= 1; id--) {
                freeIds.push(id);
            }
            List<Frame> frames = new ArrayList<>();
            long seed = 1;
            long next = System.nanoTime();
            for (int f = 0; f < frameCount; f++) {
                // Paced as a headset paces frames: each starts at its time; its work is timed, the rest idles.
                while (paced && System.nanoTime() < next) {
                    java.util.concurrent.locks.LockSupport.parkNanos(Math.max(1, next - System.nanoTime() - 200_000));
                }
                long start = System.nanoTime();
                next = Math.max(next + BUDGET, start);
                double x = f * speed / 90.0;
                fly(camera, x, WIDTH, HEIGHT);
                int builds = graph.builds();
                int[] added = {0};
                int removed = 0;
                int texturesAdded = 0;
                // Cells in range: start building those new to it; place those whose meshes are built.
                int cx = (int) Math.floor(x / CELL);
                java.util.Set<Long> wanted = new java.util.HashSet<>();
                for (int i = cx - range; i <= cx + range; i++) {
                    for (int j = -range; j <= range; j++) {
                        long key = (long) i << 32 | (j & 0xffffffffL);
                        wanted.add(key);
                        if (!cells.containsKey(key)) {
                            Cell cell = new Cell(key);
                            long cellSeed = seed++;
                            cell.building = CompletableFuture.supplyAsync(() -> buildMeshes(cellSeed, key),
                                builders);
                            cells.put(key, cell);
                        }
                    }
                }
                for (Cell cell : cells.values()) {
                    if (!paced && cell.building != null) {
                        cell.building.join();
                    }
                    if (cell.building != null && cell.building.isDone()) {
                        texturesAdded += place(cell, cell.building.get(), pool, textures, materials, scene, freeIds,
                            added);
                        cell.building = null;
                        cell.freeAt = f + FREE_AFTER;
                    }
                    freePlaced(cell, f);
                }
                for (java.util.Iterator<Cell> it = cells.values().iterator(); it.hasNext();) {
                    Cell cell = it.next();
                    if (!wanted.contains(cell.key) && cell.building == null) {
                        removed += clear(cell, pool, textures, materials, scene, freeIds);
                        it.remove();
                    }
                }
                graph.beginFrame();
                graph.submit(0, 0);
                if (sampler != null) {
                    sampler.sample(f, cells.size(), pool, textures, materials);
                }
                long now = System.nanoTime();
                frames.add(new Frame(f, (now - start) / 1e6, added[0], removed, texturesAdded,
                    graph.builds() - builds));
                com.truesim.engine.graph.GpuTimings gpu = graph.timings();
                if (gpu != null && gpu.frame() >= 1 && gpu.frame() <= frames.size()) {
                    double frameStart = gpu.frameStartMillis(); // the frame start's uploads (textures, mips) too
                    frames.get((int) gpu.frame() - 1).gpu = gpu.totalMillis() + (Double.isNaN(frameStart) ? 0 : frameStart);
                }
            }
            device.waitIdle();
            long placed = cells.values().stream().filter(c -> c.building == null).count();
            System.out.println("Cells in range " + cells.size() + ", placed " + placed + ", meshes "
                + pool.meshCount() + ", camera x " + (frameCount * speed / 90.0));
            report(frames, pool, textures, materials);
            for (Cell cell : cells.values()) {
                if (cell.building == null) {
                    clear(cell, pool, textures, materials, scene, freeIds);
                } else if (!direct) { // built but never placed: its prepared content freed
                    Built unplaced = cell.building.get();
                    unplaced.preparedMeshes().forEach(GeometryPool.Prepared::close);
                    unplaced.preparedTexture().close();
                    unplaced.free();
                } else {
                    cell.building.get().free();
                }
            }
            device.waitIdle();
            sun.close();
            renderer.close();
            target.close();
            if (rayTracing != null) {
                rayTracing.close();
            }
        } finally {
            builders.shutdownNow();
        }
    }

    /** One line of memory by kind, in MB, with what is streamed in now. */
    static void memory(int frame, int cells, GeometryPool pool, Textures textures, Materials materials) {
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "memory frame %6d cells %4d meshes %5d "
            + "textures %4d materials %4d |", frame, cells, pool.meshCount(), textures.count(), materials.count()));
        for (com.truesim.engine.vulkan.GpuMemory.Category c : com.truesim.engine.vulkan.GpuMemory.Category.values()) {
            line.append(String.format(Locale.ROOT, " %s %.0f", c.name().toLowerCase(Locale.ROOT),
                com.truesim.engine.vulkan.GpuMemory.bytes(c) / 1e6));
        }
        long direct = 0;
        for (java.lang.management.BufferPoolMXBean b : java.lang.management.ManagementFactory
            .getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
            direct += b.getMemoryUsed();
        }
        Runtime rt = Runtime.getRuntime();
        long committed = ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory
            .getOperatingSystemMXBean()).getCommittedVirtualMemorySize();
        line.append(String.format(Locale.ROOT, " | lwjgl %.0f (%d) direct %.0f heap %.0f process %.0f",
            com.truesim.engine.platform.NativeMemory.bytes() / 1e6, com.truesim.engine.platform.NativeMemory.count(),
            direct / 1e6, (rt.totalMemory() - rt.freeMemory()) / 1e6, committed / 1e6));
        if (device != null) {
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                org.lwjgl.util.vma.VmaBudget.Buffer budgets = org.lwjgl.util.vma.VmaBudget.calloc(
                    org.lwjgl.vulkan.VK10.VK_MAX_MEMORY_HEAPS, stack);
                org.lwjgl.util.vma.Vma.vmaGetHeapBudgets(device.allocator(), budgets);
                for (int h = 0; h < 3; h++) {
                    org.lwjgl.util.vma.VmaBudget b = budgets.get(h);
                    line.append(String.format(Locale.ROOT, " | heap%d usage %.0f blocks %.0f (%d) allocs %.0f (%d)", h,
                        b.usage() / 1e6, b.statistics().blockBytes() / 1e6, b.statistics().blockCount(),
                        b.statistics().allocationBytes() / 1e6, b.statistics().allocationCount()));
                }
            }
        }
        System.out.println(line);
    }

    static VulkanDevice device; // for the memory line's heaps

    /** Reads the options both modes share: rays, large, heavy, direct, inframe, grow. */
    static void options(List<String> args) {
        rays = args.contains("rays");
        heavy = args.contains("heavy");
        large = heavy || args.contains("large");
        direct = args.contains("direct");
        inFrame = args.contains("inframe");
        grow = args.contains("grow");
        range = Integer.getInteger("truesim.demo.range", args.contains("window") ? WINDOW_RANGE : RANGE);
        speed = Double.parseDouble(System.getProperty("truesim.demo.speed",
            String.valueOf(args.contains("window") ? WINDOW_SPEED : SPEED)));
        if (!(speed > 0)) {
            throw new IllegalArgumentException("speed " + speed + ": metres a second, more than 0");
        }
        if (range < 1) {
            throw new IllegalArgumentException("range " + range + ": at least 1 cell each way");
        }
    }

    /** The most cells the range keeps: a column more while one leaves. */
    private static int cells() {
        return (2 * range + 2) * (2 * range + 1);
    }

    /** Instances to start with: every cell in range, its ground tile and rocks; the default's 8192 at least. */
    static int instanceCapacity() {
        return Math.max(8192, cells() * (INSTANCES_PER_CELL + 1) + 1);
    }

    /** Materials to start with: two a cell in range; the default's 1024 at least. */
    static int materialCapacity() {
        return Math.max(1024, cells() * 2 + 16);
    }

    /**
     * The geometry pool's capacity for the options: the default's, or room for every cell in range where that is more
     * (about 6,000 vertices a small cell, 70,000 a large one, 150,000 a heavy one, with a third to spare); it grows
     * past that if it must.
     */
    static GeometryPool.Capacity capacity() {
        if (grow) {
            return GeometryPool.START;
        }
        long perCell = heavy ? 200_000 : large ? 95_000 : 8_000;
        int vertices = (int) Math.min(1 << 30, Math.max(large ? 1 << 24 : 1 << 22, perCell * cells()));
        return GeometryPool.Capacity.of(vertices, vertices / (large ? 16 : 32), Math.max(512, cells() * 4 + 64));
    }

    /** The camera over the cells at {@code x} along the flight (both modes fly the same path). */
    static void fly(Camera camera, double x, int width, int height) {
        camera.position(x, 8, 0).set(1, -0.25, 0.15, 0, 1, 0, (70 * Math.PI / 180), width, height, 0.1);
    }

    /**
     * A cell's content, on a worker thread: rocks of their own shapes and the cell's ground tile (its heights the
     * world's, so the rocks stand on it), and a texture's texels.
     */
    static Built buildMeshes(long seed, long key) {
        List<MeshData> meshes = new ArrayList<>();
        double baseX = (key >> 32) * CELL;
        double baseZ = (int) key * CELL;
        if (large) { // 66,000 vertices, 131,000 triangles (heavy: 295,000)
            meshes.add(DemoMeshes.groundTile(baseX, baseZ, CELL, heavy ? 384 : 256));
            meshes.add(DemoMeshes.rock(4));
        } else {
            for (int m = 0; m < MESHES_PER_CELL; m++) {
                meshes.add(DemoMeshes.rock(3 + (int) ((seed + m) % 2)));
            }
            meshes.add(DemoMeshes.groundTile(baseX, baseZ, CELL, 24)); // a quad a metre: 1,152 triangles
        }
        int size = heavy ? 4096 : large ? 2048 : 256;
        Arena arena = Arena.ofShared(); // freed with the meshes (Built.free), not left to the collector
        UNFREED.incrementAndGet();
        MemorySegment texels = arena.allocate(4L * size * size);
        int tint = (int) (seed * 2654435761L);
        for (int i = 0; i < size * size; i++) {
            texels.setAtIndex(ValueLayout.JAVA_INT, i, 0xff000000 | (tint ^ i * 31) & 0xffffff);
        }
        if (direct) {
            return new Built(meshes, arena, texels, size, null, null);
        }
        List<GeometryPool.Prepared> prepared = new ArrayList<>();
        for (MeshData mesh : meshes) {
            prepared.add(sharedPool.prepare(mesh));
        }
        return new Built(meshes, arena, texels, size, prepared, sharedTextures.prepareColor(size, size, texels));
    }

    /** Frames after placing a cell before its CPU-side content is freed: its copies recorded by then. */
    static final int FREE_AFTER = 4;

    /** Frees {@code cell}'s CPU-side content once frame {@code frame} is {@link Cell#freeAt} or later. */
    static void freePlaced(Cell cell, long frame) {
        if (cell.placedContent != null && frame >= cell.freeAt) {
            Built content = cell.placedContent;
            FREES.execute(content::free);
            cell.placedContent = null;
        }
    }

    // Closing a shared arena makes every thread reach a safepoint (up to milliseconds): not on the frame's thread.
    private static final ExecutorService FREES = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "stream frees");
        thread.setDaemon(true);
        return thread;
    });

    /** Puts a built cell on the GPU; the textures added. */
    static int place(Cell cell, Built content, GeometryPool pool, Textures textures, Materials materials,
        GpuScene scene, ArrayDeque<Integer> freeIds, int[] added) {
        List<MeshData> built = content.meshes();
        cell.placedContent = content;
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
        cell.instances = new int[INSTANCES_PER_CELL + (large ? 0 : 1)];
        if (!large) { // the cell's ground tile, after the rocks' (which keep their places)
            int id = freeIds.pop();
            cell.instances[INSTANCES_PER_CELL] = id;
            scene.set(id, cell.meshes[MESHES_PER_CELL], groundMaterial, baseX, 0.0, baseZ, 0, 0, 0, 1, 1, 1, 1);
        }
        java.util.SplittableRandom random = new java.util.SplittableRandom(cell.key);
        for (int i = 0; i < INSTANCES_PER_CELL; i++) {
            int id = freeIds.pop();
            cell.instances[i] = id;
            double x = baseX + random.nextDouble(CELL);
            double z = baseZ + random.nextDouble(CELL);
            float s = (float) (0.3 + random.nextDouble(1.5));
            if (large && i == 0) { // the cell's terrain tile, textured, once
                scene.set(id, cell.meshes[0], cell.materials[0], baseX, 0.0, baseZ, 0, 0, 0, 1, 1, 1, 1);
                continue;
            }
            int mesh = large ? cell.meshes[1] : cell.meshes[i % MESHES_PER_CELL];
            scene.set(id, mesh, cell.materials[i % 2], x, DemoMeshes.height(x, z) + s * 0.6, z, 0, 0, 0, 1, s, s, s);
        }
        return 1;
    }

    /** Takes a cell off the GPU; the meshes removed. */
    static int clear(Cell cell, GeometryPool pool, Textures textures, Materials materials, GpuScene scene,
        ArrayDeque<Integer> freeIds) {
        freePlaced(cell, Long.MAX_VALUE);
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
        System.out.printf(Locale.ROOT, "Frame work, %d frames at 90 Hz: p50 %.2f ms, p95 %.2f, p99 %.2f, max %.2f, "
                + "standard deviation %.3f; over the 11.1 ms budget: %d, over 4 ms: %d%n", millis.length,
            at(millis, 0.5), at(millis, 0.95), at(millis, 0.99), millis[millis.length - 1], deviation(millis),
            Arrays.stream(millis).filter(m -> m > 11.1).count(), Arrays.stream(millis).filter(m -> m > 4).count());
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
        System.out.printf(Locale.ROOT, "GPU per frame (graphics queue, frame start included), %d timed: p50 %.2f ms, "
                + "p95 %.2f, p99 %.2f, max %.2f, standard deviation %.3f; over 11.1 ms: %d%n", gpu.length, at(gpu, 0.5),
            at(gpu, 0.95), at(gpu, 0.99), gpu[gpu.length - 1], deviation(gpu),
            Arrays.stream(gpu).filter(g -> g > 11.1).count());
        double gpuAdding = timed.stream().filter(f -> f.meshesAdded() > 0 && !Double.isNaN(f.gpu))
            .mapToDouble(f -> f.gpu).average().orElse(0);
        double gpuQuiet = timed.stream().filter(f -> f.meshesAdded() == 0 && f.meshesRemoved() == 0
            && !Double.isNaN(f.gpu)).mapToDouble(f -> f.gpu).average().orElse(0);
        double gpuAddingMax = timed.stream().filter(f -> f.meshesAdded() > 0 && !Double.isNaN(f.gpu))
            .mapToDouble(f -> f.gpu).max().orElse(0);
        System.out.printf(Locale.ROOT, "GPU mean ms: frames adding %.3f (max %.3f), streaming nothing %.3f; stream "
            + "queue: %s%n", gpuAdding, gpuAddingMax, gpuQuiet,
            inFrame || direct ? "none, copies in the frame" : "transfer");
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

    private static double deviation(double[] values) {
        double mean = Arrays.stream(values).average().orElse(0);
        return Math.sqrt(Arrays.stream(values).map(v -> (v - mean) * (v - mean)).average().orElse(0));
    }

    private static double at(double[] sorted, double fraction) {
        return sorted[Math.min(sorted.length - 1, (int) (fraction * sorted.length))];
    }
}
