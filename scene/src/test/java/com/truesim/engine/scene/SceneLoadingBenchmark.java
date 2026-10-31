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
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.engine.scene.SceneResources.Loading;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderables;
import com.truesim.scene.render.TextureData;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Loading a world of distinct assets through the scene loader while frames run: 256 rock meshes and 256 materials, each
 * with a 512 x 512 texture of its own, on 8,192 nodes, at 1920 x 1080 with two frames in flight. Per way of loading
 * (streamed or whole, textures and meshes made ready on the frame's thread or on workers) it prints the time until
 * everything is on the GPU ({@link SceneResources#loadingCount()} 0), the frames that took, when the first renderable
 * showed and when nothing renderables wait for was on its way any more ({@link SceneResources#loaded()}), the frame times meanwhile (median, 99th
 * percentile, worst) and the worst time the frame's thread spent taking in what arrived. Run with
 * {@code TRUESIM_BENCHMARK=true ./gradlew :engine-scene:test --tests '*SceneLoadingBenchmark'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class SceneLoadingBenchmark {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int MESHES = 256;
    private static final int MATERIALS = 256;
    private static final int TEXTURE_SIZE = 512;
    private static final int NODES = 8192;
    private static final double FAR = 1e6;
    private static final long TIMEOUT_NANOS = 120_000_000_000L;
    /** Runs only the ways whose names contain it (TRUESIM_BENCHMARK_SCENARIO), e.g. "streamed, 16". */
    private static final String SCENARIO = System.getenv().getOrDefault("TRUESIM_BENCHMARK_SCENARIO", "");

    record Rock(int subdivisions) {
    }

    record Noise(int size) {
    }

    record Painted(int index) {
    }

    /** A rock: one per seed, a cache entry of its own however alike. */
    static final Generator<Rock, MeshData> ROCKS = (rock, seed) -> SceneBenchmark.rock(rock.subdivisions());
    /** Colored noise, one per seed. */
    static final Generator<Noise, TextureData> NOISE = (noise, seed) -> {
        int size = noise.size();
        MemorySegment texels = Arena.ofAuto().allocate(4L * size * size, 4);
        SplittableRandom random = new SplittableRandom(seed);
        int tint = random.nextInt() | 0xff000000;
        for (long i = 0; i < (long) size * size; i++) {
            texels.setAtIndex(ValueLayout.JAVA_INT, i, tint & (random.nextInt() | 0xff808080));
        }
        return new TextureData(size, size, TextureData.ColorSpace.SRGB, texels);
    };

    /** One way of loading: whole or streamed, with how many workers making meshes and textures ready (0: none). */
    private record Way(String name, Loading mode, int workers) {
    }

    @Test
    void loadAWorldOfDistinctAssets() {
        int workers = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        List<Way> ways = List.of(new Way("streamed, on the frame's thread", Loading.STREAM, 0),
            new Way("streamed, " + workers + " workers", Loading.STREAM, workers),
            new Way("whole, on the frame's thread", Loading.WHOLE, 0),
            new Way("whole, " + workers + " workers", Loading.WHOLE, workers));
        FrameSlots slots = new FrameSlots(2);
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("benchmark").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            System.out.printf(Locale.ROOT, "Scene loading benchmark on %s, %d x %d, %d meshes, %d materials with a "
                + "%d x %d texture each, %,d nodes, 2 frames in flight:%n", device.gpu().name(), WIDTH, HEIGHT, MESHES,
                MATERIALS, TEXTURE_SIZE, TEXTURE_SIZE, NODES);
            load(device, slots, ways.getLast(), 100, false); // warming up: pipelines made, code compiled
            for (int w = 0; w < ways.size(); w++) {
                if (ways.get(w).name().contains(SCENARIO)) {
                    load(device, slots, ways.get(w), 1000 * (1 + w), true);
                }
            }
        }
    }

    private static void load(VulkanDevice device, FrameSlots slots, Way way, int seed, boolean print) {
        try (GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 1 << 16, MESHES + 4), slots);
             Materials materials = new Materials(device, MATERIALS + 8, slots);
             Textures textures = new Textures(device, slots);
             TaskScheduler tasks = new TaskScheduler();
             AssetCache assets = new AssetCache(tasks);
             SceneResources resources = new SceneResources(assets, pool, materials, textures);
             GpuScene gpu = new GpuScene(device, pool, NODES + 64, slots);
             MeshletRenderer renderer = new MeshletRenderer(device, gpu, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, slots);
             GpuImage shaded = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
             FrameGraph graph = new FrameGraph(device, slots)) {
            resources.loading(way.mode());
            if (way.workers() > 0) {
                resources.prepareOn(tasks, way.workers());
            }
            // Fresh assets per way (seeds apart), so no way finds another's in the cache.
            List<AssetRef<MeshData>> meshes = new ArrayList<>();
            for (int m = 0; m < MESHES; m++) {
                meshes.add(AssetRef.generated(RenderAssets.MESH, ROCKS, new Rock(4), seed + m));
            }
            List<AssetRef<MaterialData>> painted = new ArrayList<>();
            for (int m = 0; m < MATERIALS; m++) {
                AssetRef<TextureData> texture = AssetRef.generated(RenderAssets.TEXTURE, NOISE,
                    new Noise(TEXTURE_SIZE), seed + m);
                painted.add(AssetRef.generated(RenderAssets.MATERIAL, (Generator<Painted, MaterialData>) (p, s) ->
                    MaterialData.builder(MaterialData.METALLIC_ROUGHNESS).texture("baseColorTexture", texture)
                        .set("roughness", 0.7f).build(), new Painted(m), seed + m));
            }
            SceneGraph scene = new SceneGraph(new ChangeClock(), NODES + 64, SceneGraph.DEFAULT_LOG_CAPACITY);
            Renderables renderables = new Renderables(scene);
            SplittableRandom random = new SplittableRandom(5);
            double[] placed = new double[3 * NODES];
            for (int i = 0; i < NODES; i++) {
                long node = scene.create();
                SceneBenchmark.place(scene, node, random, placed, i, FAR);
                renderables.set(node, meshes.get(i % MESHES), List.of(painted.get(random.nextInt(MATERIALS))));
            }
            Camera camera = new Camera().set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
            camera.position(FAR, 0, FAR);
            SceneSync sync = new SceneSync(resources, renderables, gpu, null);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            ViewPort port = graph.addView("main").size(WIDTH, HEIGHT);
            SceneView main = geometry.createView(port, camera, ViewOptions.of(1 << 20, 80_000_000).withOcclusion(true));
            RenderView view = main.view();
            ExternalImage target = new ExternalImage("shaded", WIDTH, HEIGHT, Format.R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .set(shaded.handle(), 0);
            port.add(new ClusteredForwardShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT))
                .output(Keys.SCENE_COLOR, target);
            // Frames until everything is in, as an application with a loading screen (or streaming) runs them.
            long[] frames = new long[4096];
            long[] drains = new long[4096];
            int count = 0;
            int firstShown = -1;
            int usable = -1; // the first frame nothing waited for was on its way (loaded())
            int worstMeshes = 0; // what arrived in the frame of the worst take-in
            int worstTextures = 0;
            int worstShown = 0;
            long start = System.nanoTime();
            do {
                long t0 = System.nanoTime();
                int slot = graph.beginFrame();
                slots.begin(slot);
                long t1 = System.nanoTime();
                int meshesBefore = resources.uploadedMeshCount();
                int texturesBefore = textures.count();
                int shownBefore = sync.instanceCount();
                tasks.drainCompleted(); // what arrived: uploads, renderables shown
                long t2 = System.nanoTime();
                if (count == 0 || t2 - t1 > Arrays.stream(drains, 0, count).max().orElse(0)) {
                    worstMeshes = resources.uploadedMeshCount() - meshesBefore;
                    worstTextures = textures.count() - texturesBefore;
                    worstShown = sync.instanceCount() - shownBefore;
                }
                scene.update();
                renderables.update();
                sync.update();
                view.set(camera, 1, MeshletRenderer.MODE_LIT);
                graph.submit(0, 0);
                slots.end();
                long t3 = System.nanoTime();
                if (count == frames.length) {
                    frames = Arrays.copyOf(frames, 2 * count);
                    drains = Arrays.copyOf(drains, 2 * count);
                }
                frames[count] = t3 - t0;
                drains[count] = t2 - t1;
                if (firstShown < 0 && sync.instanceCount() > 0) {
                    firstShown = count;
                }
                if (usable < 0 && resources.loaded()) {
                    usable = count;
                }
                count++;
            } while (!(resources.loadingCount() == 0 && resources.preparingCount() == 0)
                && System.nanoTime() - start < TIMEOUT_NANOS);
            long loaded = System.nanoTime() - start;
            device.waitIdle();
            long[] sorted = Arrays.copyOf(frames, count);
            Arrays.sort(sorted);
            long worstDrain = Arrays.stream(drains, 0, count).max().orElse(0);
            if (!print) {
                sync.close();
                return;
            }
            System.out.printf(Locale.ROOT, "  %-34s all in after %7.1f ms, %4d frames; first shown at frame %3d, "
                + "loaded() at %3d; frames %6.2f ms median, %6.2f p99, %7.2f worst; worst take-in %7.2f ms (%d meshes, "
                + "%d textures, %,d shown); %,d instances, %d textures%n", way.name(), loaded / 1e6, count, firstShown,
                usable, sorted[count / 2] / 1e6,
                sorted[Math.min(count - 1, count * 99 / 100)] / 1e6, sorted[count - 1] / 1e6, worstDrain / 1e6, worstMeshes, worstTextures, worstShown,
                sync.instanceCount(), textures.count());
            sync.close();
        }
    }
}
