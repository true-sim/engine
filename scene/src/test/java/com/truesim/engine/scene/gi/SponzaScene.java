package com.truesim.engine.scene.gi;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.truesim.engine.render.AmbientOcclusion;
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
import com.truesim.engine.render.ProbeVolume;
import com.truesim.engine.render.ProbeVolumeLighting;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.ReferencePathTracer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.SkyBackground;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.scene.SceneSync;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.gltf.Gltf;
import com.truesim.scene.render.ImageImporter;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.NodeVisibility;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderable;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Sponza loaded for GI's references, evaluations and benchmarks (a local asset under its own licence, read where it
 * is, never committed): the sun through the courtyard, a blue sky, one view of {@code width} x {@code height} into a
 * float image, its techniques chosen per use (path traced, or real time with ambient occlusion and the probe volume
 * as the graph's settings say).
 */
final class SponzaScene implements AutoCloseable {

    /** Cameras: position, then the direction looked along. */
    static final double[][] CAMERAS = {
        {-10, 1.6, -0.2, 1, 0.12, 0.05},
        {9, 6.2, -2.8, -1, -0.35, 0.4},
        {0.5, 1.5, 3.6, -0.3, 0.2, -1},
    };

    final VulkanDevice device;
    final int width;
    final int height;
    final FrameGraph graph;
    final TaskScheduler tasks = new TaskScheduler();
    final AssetCache cache = new AssetCache(tasks);
    final GeometryPool pool;
    final Materials materials;
    final Textures textures;
    final RayTracing rayTracing;
    final SceneResources resources;
    final SceneGraph scene = new SceneGraph(new ChangeClock());
    final ComponentStore<Renderable> renderables = scene.components().store(Renderable.TYPE);
    final GpuScene gpu;
    final SceneSync sync;
    final MeshletRenderer renderer;
    final SceneGeometry geometry;
    final Camera camera = new Camera();
    final ViewPort port;
    final SceneView main;
    final Lights lights;
    final Environment sky = Environment.uniform("sky", 0.35f, 0.45f, 0.6f);
    final GpuImage target;
    final GpuBuffer pixels;
    private final List<Technique> added = new ArrayList<>();

    SponzaScene(VulkanDevice device, Path gltf, int width, int height) throws InterruptedException {
        this.device = device;
        this.width = width;
        this.height = height;
        graph = new FrameGraph(device, 2);
        GiHarness.settings(graph);
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 22, 1 << 16, 1 << 11), graph.frameSlots());
        materials = new Materials(device, 1024, graph.frameSlots());
        textures = new Textures(device, graph.frameSlots());
        rayTracing = new RayTracing(device, pool, 4096);
        resources = new SceneResources(cache, pool, materials, textures);
        resources.rayTracing(rayTracing);
        PathAssets paths = new PathAssets(cache);
        paths.mounts().add("s", PathSource.folder(gltf.getParent()));
        new Gltf(paths).register();
        for (String extension : List.of("png", "jpg", "jpeg", "webp")) {
            paths.importers().add(RenderAssets.TEXTURE, extension, ImageImporter.INSTANCE);
        }
        AssetRef<ModelData> ref = paths.ref(RenderAssets.MODEL, "s://" + gltf.getFileName());
        cache.acquire(ref);
        ModelData model = await(ref);
        NodeVisibility visibility = new NodeVisibility(scene);
        model.spawn(scene, scene.create(), visibility);
        gpu = new GpuScene(device, pool, 4096, graph.frameSlots());
        sync = new SceneSync(resources, scene, gpu);
        renderer = new MeshletRenderer(device, gpu, VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_D32_SFLOAT,
            graph.frameSlots());
        geometry = SceneGeometry.meshlets(renderer);
        port = graph.addView("main").size(width, height);
        main = geometry.createView(port, camera, ViewOptions.of(1 << 14, 1 << 22));
        lights = new Lights(device, 1, 1, graph.frameSlots());
        lights.directional(0, -0.2f, -1, 0.28f, 12, 11, 9.5f).setCount(1);
        lights.shadow(0, com.truesim.scene.core.ShadowSpec.rayTraced());
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        target = new GpuImage(device, width, height, VK_FORMAT_R32G32B32A32_SFLOAT, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        pixels = GpuBuffer.hostVisible(device, (long) width * height * 16, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        port.output(Keys.SCENE_COLOR, new ExternalImage("target", width, height, Format.R32G32B32A32_SFLOAT, usage,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
    }

    /** Looks from camera {@code c} of {@link #CAMERAS}. */
    void look(int c) {
        double[] k = CAMERAS[c];
        camera.position(k[0], k[1], k[2]).set(k[3], k[4], k[5], 0, 1, 0, Math.toRadians(70), width, height, 0.05);
        main.view().set(camera, 1, MeshletRenderer.MODE_LIT);
    }

    /** The path tracer as the view's technique; returned to restart it. */
    ReferencePathTracer traced() {
        ReferencePathTracer reference = new ReferencePathTracer(geometry, rayTracing, main,
            VK_FORMAT_R32G32B32A32_SFLOAT);
        use(reference, new ClusteredLights(lights, main), new EnvironmentLighting(sky, main));
        return reference;
    }

    /** The real-time image: the visibility buffer, traced sun shadows, the sky, ambient occlusion and the volume. */
    void realtime() {
        use(new VisibilityBufferShading(geometry, main, VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_D32_SFLOAT),
            new ClusteredLights(lights, main), new EnvironmentLighting(sky, main), new SkyBackground(sky, main),
            sunShadows(), new AmbientOcclusion(main, rayTracing),
            new ProbeVolumeLighting(main, new ProbeVolume(rayTracing, renderer, lights, sky, main)));
    }

    /** The sun's shadows' part in the view, made once: the owner traces them. */
    private Technique sunShadows() {
        if (sunShadows == null) {
            shadows = new Shadows(geometry, lights).rayTracing(rayTracing);
            sunShadows = shadows.view(main);
        }
        return sunShadows;
    }

    private Shadows shadows;
    private Technique sunShadows;

    private void use(Technique... techniques) {
        for (Technique t : added) {
            port.remove(t);
        }
        added.clear();
        for (Technique t : techniques) {
            port.add(t);
            added.add(t);
        }
    }

    /** {@code count} frames, loading as they go. */
    void frames(int count) throws InterruptedException {
        for (int i = 0; i < count; i++) {
            graph.beginFrame();
            tasks.drainCompleted();
            scene.update();
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

    /** The image the frame recorded last drew: linear RGBA floats. */
    float[] read() {
        device.waitIdle();
        device.submitAndWait(device.graphics(), commands -> {
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(width, height, 1);
                vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        return pixels.memory().asSlice(0, (long) width * height * 16).toArray(ValueLayout.JAVA_FLOAT);
    }

    private <T> T await(AssetRef<T> ref) throws InterruptedException {
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (cache.get(ref) == null && cache.failure(ref) == null) {
            assertThat(System.nanoTime()).as("loading " + ref).isLessThan(deadline);
            Thread.sleep(1);
            tasks.drainCompleted();
        }
        assertThat(cache.failure(ref)).as(ref.toString()).isNull();
        return cache.get(ref);
    }

    @Override
    public void close() {
        try {
            closeAll();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void closeAll() throws Exception {
        device.waitIdle();
        pixels.close();
        target.close();
        lights.close();
        if (shadows != null) {
            shadows.close();
        }
        renderer.close();
        sync.close();
        gpu.close();
        resources.close();
        rayTracing.close();
        textures.close();
        materials.close();
        pool.close();
        cache.close();
        tasks.close();
        graph.close();
    }
}
