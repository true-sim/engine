package com.truesim.engine.scene.gi;

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

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.AmbientOcclusion;
import com.truesim.engine.render.ProbeVolume;
import com.truesim.engine.render.ProbeVolumeLighting;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.ReferencePathTracer;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.SkyBackground;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.shadows.InlineRayShadows;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Renders {@link GiScenes} for GI's tests and benchmarks: the path-traced reference of a scene in a state, and the
 * real-time image of the same view (the visibility buffer, the sun's shadows traced inline, the environment's light
 * and sky), as linear RGBA floats to compare ({@link GiError}). Needs ray tracing. One device, one frame graph and one
 * view, the scenes built into it in turn.
 */
public final class GiHarness implements AutoCloseable {

    private final VulkanDevice device;
    private final int width;
    private final int height;
    private final FrameGraph graph;
    private final GeometryPool pool;
    private final GpuScene scene;
    private final Materials materials;
    private final Lights lights;
    private final RayTracing rayTracing;
    private final MeshletRenderer renderer;
    private final SceneGeometry geometry;
    private final RenderView view;
    private final Camera camera = new Camera();
    private final SceneView main;
    private final GpuImage target;
    private final GpuBuffer pixels;
    private final ExternalImage output;
    private final GiScenes.Stage stage;
    private final List<Technique> added = new ArrayList<>();

    /** A harness rendering {@code width} x {@code height} images on {@code device} (which must trace rays). */
    public GiHarness(VulkanDevice device, int width, int height) {
        if (!device.rayTracing()) {
            throw new IllegalArgumentException("GI's references trace rays: " + device.gpu().name() + " cannot");
        }
        this.device = device;
        this.width = width;
        this.height = height;
        graph = new FrameGraph(device, 2);
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 16, 1 << 10, 64), graph.frameSlots());
        scene = new GpuScene(device, pool, 256, graph.frameSlots());
        materials = new Materials(device, 256, graph.frameSlots());
        scene.setMaterials(materials);
        lights = new Lights(device, 16, 4, graph.frameSlots());
        rayTracing = new RayTracing(device, pool, 256);
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_D32_SFLOAT,
            graph.frameSlots());
        geometry = SceneGeometry.meshlets(renderer);
        ViewPort port = graph.addView("main").size(width, height);
        main = geometry.createView(port, camera, ViewOptions.of(1 << 12, 1 << 20));
        view = main.view();
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        target = new GpuImage(device, width, height, VK_FORMAT_R32G32B32A32_SFLOAT, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        pixels = GpuBuffer.hostVisible(device, (long) width * height * 16, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        output = new ExternalImage("target", width, height, Format.R32G32B32A32_SFLOAT, usage,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
        stage = new GiScenes.Stage(pool, scene, materials, lights, rayTracing, camera, width, height);
        main.port().output(Keys.SCENE_COLOR, output);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The path-traced reference of scene {@code name} in {@code state}: {@code samples} samples, {@code bounces} at most. */
    public float[] reference(String name, int state, int samples, int bounces) {
        GiScenes.build(name, stage, state);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        graph.settings().set(ReferencePathTracer.SAMPLES, samples).set(ReferencePathTracer.BOUNCES, bounces);
        ReferencePathTracer reference = new ReferencePathTracer(geometry, rayTracing, main,
            VK_FORMAT_R32G32B32A32_SFLOAT);
        use(reference, new ClusteredLights(lights, main), new EnvironmentLighting(stage.environment, main));
        frames(8); // the scene's bottom levels and pipelines in place
        reference.restart();
        frames(samples + 2);
        return read();
    }

    /** The real-time image of scene {@code name} in {@code state}: no global illumination yet. */
    public float[] raster(String name, int state) {
        GiScenes.build(name, stage, state);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        List<Technique> techniques = new ArrayList<>(List.of(new VisibilityBufferShading(geometry, main,
            VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_D32_SFLOAT), new ClusteredLights(lights, main),
            new EnvironmentLighting(stage.environment, main), new SkyBackground(stage.environment, main)));
        if (stage.sun) {
            techniques.add(new InlineRayShadows(rayTracing, view, lights, 0, 0));
        }
        use(techniques.toArray(Technique[]::new));
        frames(8);
        return read();
    }

    /**
     * Sets up scene {@code name} in {@code state} for the real-time image with global illumination: the visibility
     * buffer, the sun's shadows traced inline, the environment, ambient occlusion and the probe volume, each as the
     * graph's settings choose ({@link AmbientOcclusion#MODE}, {@link ProbeVolumeLighting#MODE}); then
     * {@link #render} draws frames of it and {@link #state} changes its state in place.
     */
    public void realtime(String name, int state) {
        GiScenes.build(name, stage, state);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        List<Technique> techniques = new ArrayList<>(List.of(new VisibilityBufferShading(geometry, main,
            VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_D32_SFLOAT), new ClusteredLights(lights, main),
            new EnvironmentLighting(stage.environment, main), new SkyBackground(stage.environment, main),
            new AmbientOcclusion(main, rayTracing), new ProbeVolumeLighting(main, new ProbeVolume(rayTracing,
                renderer, lights, stage.environment, main))));
        if (stage.sun) {
            techniques.add(new InlineRayShadows(rayTracing, view, lights, 0, 0));
        }
        use(techniques.toArray(Technique[]::new));
    }

    /** Scene {@code name} in {@code state}, built again in place: the same materials and instance ids. */
    public void state(String name, int state) {
        GiScenes.build(name, stage, state);
    }

    /** {@code count} frames, the last read back: linear RGBA floats. */
    public float[] render(int count) {
        frames(count);
        return read();
    }

    /** The harness's frame graph (its settings). */
    public FrameGraph graph() {
        return graph;
    }

    /** The view's techniques: these, the ones before removed. */
    private void use(Technique... techniques) {
        for (Technique t : added) {
            main.port().remove(t);
        }
        added.clear();
        for (Technique t : techniques) {
            main.port().add(t);
            added.add(t);
        }
    }

    private void frames(int count) {
        for (int i = 0; i < count; i++) {
            graph.beginFrame();
            graph.submit(0, 0);
        }
        device.waitIdle();
    }

    private float[] read() {
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

    @Override
    public void close() {
        device.waitIdle();
        graph.close();
        pixels.close();
        target.close();
        view.close();
        renderer.close();
        rayTracing.close();
        lights.close();
        scene.close();
        materials.close();
        pool.close();
    }
}
