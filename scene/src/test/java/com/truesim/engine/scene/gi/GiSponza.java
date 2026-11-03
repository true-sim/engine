package com.truesim.engine.scene.gi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.ReferencePathTracer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.SkyBackground;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.shadows.InlineRayShadows;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.scene.SceneSync;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.gltf.Gltf;
import com.truesim.scene.render.ImageImporter;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.NodeVisibility;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderables;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Sponza (the Khronos glTF sample, under its own licence: given by path, {@code -PgiSponza=<Sponza.gltf>}, never
 * committed or copied) as GI's large interior: the sun through the courtyard, a blue sky above, path traced from a few
 * cameras beside the real-time image of each view, into GiReferences' directory (TRUESIM_SCREENSHOTS=true).
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class GiSponza {

    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    /** Cameras: position, then the direction looked along. */
    private static final double[][] CAMERAS = {
        {-10, 1.6, -0.2, 1, 0.12, 0.05},
        {9, 6.2, -2.8, -1, -0.35, 0.4},
        {0.5, 1.5, 3.6, -0.3, 0.2, -1},
    };

    @Test
    void sponzaFromAFewCameras() throws Exception {
        String file = System.getProperty("truesim.gi.sponza");
        assumeThat(file).as("no Sponza given: -PgiSponza=<Sponza.gltf>").isNotNull();
        Path gltf = Path.of(file);
        Path out = Files.createDirectories(Path.of(System.getProperty("truesim.gi.out", "build/gi-scenes")));
        int samples = Integer.getInteger("truesim.gi.samples", 2048);
        List<AutoCloseable> closing = new ArrayList<>();
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("gi").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameGraph graph = new FrameGraph(device, 2);
             TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks);
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 22, 1 << 16, 1 << 11),
                 graph.frameSlots());
             Materials materials = new Materials(device, 1024, graph.frameSlots());
             Textures textures = new Textures(device, graph.frameSlots());
             RayTracing rayTracing = new RayTracing(device, pool, 4096);
             SceneResources resources = new SceneResources(cache, pool, materials, textures)) {
            assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
            try {
                resources.rayTracing(rayTracing);
                PathAssets paths = new PathAssets(cache);
                paths.mounts().add("s", PathSource.folder(gltf.getParent()));
                new Gltf(paths).register();
                for (String extension : List.of("png", "jpg", "jpeg", "webp")) {
                    paths.importers().add(RenderAssets.TEXTURE, extension, ImageImporter.INSTANCE);
                }
                AssetRef<ModelData> ref = paths.ref(RenderAssets.MODEL, "s://" + gltf.getFileName());
                cache.acquire(ref);
                ModelData model = await(tasks, cache, ref);
                SceneGraph scene = new SceneGraph(new ChangeClock());
                Renderables renderables = new Renderables(scene);
                NodeVisibility visibility = new NodeVisibility(scene);
                model.spawn(scene, renderables, scene.create(), visibility);
                GpuScene gpu = new GpuScene(device, pool, 4096, graph.frameSlots());
                closing.add(gpu);
                SceneSync sync = new SceneSync(resources, renderables, gpu);
                closing.add(sync);
                MeshletRenderer renderer = new MeshletRenderer(device, gpu, VK_FORMAT_R32G32B32A32_SFLOAT,
                    VK_FORMAT_D32_SFLOAT, graph.frameSlots());
                closing.add(renderer);
                SceneGeometry geometry = SceneGeometry.meshlets(renderer);
                Camera camera = new Camera();
                ViewPort port = graph.addView("main").size(WIDTH, HEIGHT);
                SceneView main = geometry.createView(port, camera, ViewOptions.of(1 << 14, 1 << 22));
                Lights lights = new Lights(device, 1, 1, graph.frameSlots());
                closing.add(lights);
                lights.directional(0, -0.2f, -1, 0.28f, 12, 11, 9.5f).setCount(1);
                Environment sky = Environment.uniform("sky", 0.35f, 0.45f, 0.6f);
                int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
                GpuImage target = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R32G32B32A32_SFLOAT, usage,
                    VK_IMAGE_ASPECT_COLOR_BIT);
                closing.add(target);
                GpuBuffer pixels = GpuBuffer.hostVisible(device, (long) WIDTH * HEIGHT * 16,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT);
                closing.add(pixels);
                port.output(Keys.SCENE_COLOR, new ExternalImage("target", WIDTH, HEIGHT, Format.R32G32B32A32_SFLOAT,
                    usage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .set(target.handle(), target.view()));
                graph.settings().set(ReferencePathTracer.SAMPLES, samples).set(ReferencePathTracer.BOUNCES,
                    Integer.getInteger("truesim.gi.bounces", 32));
                List<String> table = new ArrayList<>();
                for (int c = 0; c < CAMERAS.length; c++) {
                    double[] k = CAMERAS[c];
                    camera.position(k[0], k[1], k[2]).set(k[3], k[4], k[5], 0, 1, 0, Math.toRadians(70), WIDTH, HEIGHT,
                        0.05);
                    main.view().set(camera, 1, MeshletRenderer.MODE_LIT);
                    // The reference: loads settle first (textures, bottom levels), then the samples from a restart
                    ReferencePathTracer reference = new ReferencePathTracer(geometry, rayTracing, main,
                        VK_FORMAT_R32G32B32A32_SFLOAT);
                    List<Technique> techniques = List.of(reference, new ClusteredLights(lights, main),
                        new EnvironmentLighting(sky, main));
                    techniques.forEach(port::add);
                    frames(graph, tasks, cache, scene, renderables, sync, 60);
                    reference.restart();
                    long start = System.nanoTime();
                    frames(graph, tasks, cache, scene, renderables, sync, samples + 2);
                    double seconds = (System.nanoTime() - start) / 1e9;
                    float[] traced = read(device, target, pixels);
                    techniques.forEach(port::remove);
                    List<Technique> raster = List.of(new VisibilityBufferShading(geometry, main,
                        VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_D32_SFLOAT), new ClusteredLights(lights, main),
                        new EnvironmentLighting(sky, main), new SkyBackground(sky, main),
                        new InlineRayShadows(rayTracing, main.view(), lights, 0, 0));
                    raster.forEach(port::add);
                    frames(graph, tasks, cache, scene, renderables, sync, 8);
                    float[] drawn = read(device, target, pixels);
                    raster.forEach(port::remove);
                    double mean = 0;
                    for (int p = 0; p < WIDTH * HEIGHT; p++) {
                        mean += GiError.luminance(traced, p);
                    }
                    mean /= WIDTH * HEIGHT;
                    float exposure = (float) Math.min(16, Math.max(0.25, 0.18 / Math.max(mean, 1e-6)));
                    String name = "sponza-" + (c + 1);
                    GiError.png(traced, WIDTH, HEIGHT, exposure, out.resolve(name + "-reference.png"));
                    GiError.png(drawn, WIDTH, HEIGHT, exposure, out.resolve(name + "-raster.png"));
                    GiError.write(traced, WIDTH, HEIGHT, out.resolve(name + "-reference.f32"));
                    table.add(String.format(Locale.ROOT, "| %s | %.4f | %.1f s | %s |", name, mean, seconds,
                        GiError.of(drawn, traced, WIDTH, HEIGHT)));
                }
                System.out.printf(Locale.ROOT, "Sponza, %d x %d, %d samples on %s; the real-time image (no GI) against "
                    + "the reference:%n", WIDTH, HEIGHT, samples, device.gpu().name());
                System.out.println("| View | Reference's mean luminance | Traced in | Real-time against it |");
                System.out.println("|---|---|---|---|");
                table.forEach(System.out::println);
                assertThat(instance.validationErrors()).isZero();
            } finally {
                device.waitIdle();
                for (int i = closing.size() - 1; i >= 0; i--) {
                    closing.get(i).close();
                }
            }
        }
    }

    private static void frames(FrameGraph graph, TaskScheduler tasks, AssetCache cache, SceneGraph scene,
        Renderables renderables, SceneSync sync, int count) throws InterruptedException {
        for (int i = 0; i < count; i++) {
            graph.beginFrame();
            tasks.drainCompleted();
            scene.update();
            renderables.update();
            sync.update();
            graph.submit(0, 0);
            if (i % 16 == 0) {
                long deadline = System.nanoTime() + 60_000_000_000L;
                while ((cache.loadingCount() > 0 || tasks.pending() > 0) && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                    tasks.drainCompleted();
                }
            }
        }
    }

    private static float[] read(VulkanDevice device, GpuImage target, GpuBuffer pixels) {
        device.waitIdle();
        device.submitAndWait(device.graphics(), commands -> {
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(WIDTH, HEIGHT, 1);
                vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        return pixels.memory().asSlice(0, (long) WIDTH * HEIGHT * 16).toArray(ValueLayout.JAVA_FLOAT);
    }

    private static <T> T await(TaskScheduler tasks, AssetCache cache, AssetRef<T> ref) throws InterruptedException {
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (cache.get(ref) == null && cache.failure(ref) == null) {
            assertThat(System.nanoTime()).as("loading " + ref).isLessThan(deadline);
            Thread.sleep(1);
            tasks.drainCompleted();
        }
        assertThat(cache.failure(ref)).as(ref.toString()).isNull();
        return cache.get(ref);
    }
}
