package com.truesim.engine.render;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.materials.SpecularParameters;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexFormat;
import java.lang.foreign.ValueLayout;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * The ray-traced probe volume against closed forms and its lag targets: a closed box from -1 to 1, its faces inward,
 * the camera inside it, probes half a meter apart. The white furnace (every wall gives off E and reflects rho of what
 * reaches it diffusely) is E / (1 - rho) everywhere, which only infinite bounces through the probes' own light reach;
 * a light inside the box changing color shows within the frames design D3 allows.
 */
class ProbeVolumeTest {

    private static final int SIZE = 32;
    private static final float HALF = (float) Math.sqrt(0.5);
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS;
    private static final SpecularParameters LAMBERT = SpecularParameters.DEFAULTS.withSpecularFactor(0);
    private static final EnvironmentMaps.Quality SMALL = new EnvironmentMaps.Quality(64, 32, 4, 16, 64);

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private RenderView view;
    private RayTracing rayTracing;
    private Materials materials;
    private Lights lights;
    private FrameGraph graph;
    private GpuImage target;
    private GpuBuffer pixels;
    private int quad;
    private final Camera camera = new Camera();

    @BeforeEach
    void start() {
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 12, 64, 4));
        MeshData quadMesh = MeshletRendererTest.grid(1, 2, false, VertexFormat.POSITIONS_ONLY);
        quad = pool.add(quadMesh);
        scene = new GpuScene(device, pool, 32);
        materials = new Materials(device, 16);
        scene.setMaterials(materials);
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_D32_SFLOAT);
        view = renderer.createView(ViewOptions.of(256, 1 << 12));
        rayTracing = new RayTracing(device, pool, 32);
        rayTracing.add(quad, quadMesh, quadMesh.levels().size() - 1);
        lights = new Lights(device, 8, 4);
        graph = new FrameGraph(device, 2);
        target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R32G32B32A32_SFLOAT, VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
            VK_IMAGE_ASPECT_COLOR_BIT);
        pixels = GpuBuffer.hostVisible(device, (long) SIZE * SIZE * 16, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        graph.settings().set(ProbeVolume.CASCADES, 1).set(ProbeVolume.PROBES, 8).set(ProbeVolume.PROBES_UP, 8)
            .set(ProbeVolume.SPACING, 0.5f).set(ProbeVolume.RAYS, 128);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            if (graph != null) {
                graph.close();
                pixels.close();
                target.close();
                lights.close();
                rayTracing.close();
                renderer.close();
                scene.close();
                materials.close();
                pool.close();
            }
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    private void wall(int id, int material, float x, float y, float z, float[] facing, float half) {
        scene.set(id, quad, material, x, y, z, facing[0], facing[1], facing[2], facing[3], half, half, half);
    }

    /** A box from -1 to 1 each way, its faces inward, all of {@code material}. */
    private void box(int material) {
        float half = 1.001f;
        wall(0, material, -1, 0, 0, ReferencePathTracerTest.PLUS_X, half);
        wall(1, material, 1, 0, 0, ReferencePathTracerTest.MINUS_X, half);
        wall(2, material, 0, -1, 0, ReferencePathTracerTest.UP, half);
        wall(3, material, 0, 1, 0, ReferencePathTracerTest.DOWN, half);
        wall(4, material, 0, 0, -1, ReferencePathTracerTest.PLUS_Z, half);
        wall(5, material, 0, 0, 1, ReferencePathTracerTest.MINUS_Z, half);
    }

    /** A view looking into the box from its middle, shaded by the visibility buffer, lit by the volume; returns it. */
    private SceneView lit(String name, Environment environment) {
        camera.set(0.3, -0.2, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.01);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        SceneView main = new SceneView(graph.addView(name).size(SIZE, SIZE), view, camera);
        main.port().add(new VisibilityBufferShading(SceneGeometry.meshlets(renderer), main,
            VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_D32_SFLOAT)).add(new ClusteredLights(lights, main))
            .add(new EnvironmentLighting(environment, main, SMALL))
            .add(new ProbeVolumeLighting(main, new ProbeVolume(rayTracing, renderer, lights, environment, SMALL, main)))
            .output(Keys.SCENE_COLOR, new ExternalImage("target", SIZE, SIZE, Format.R32G32B32A32_SFLOAT,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT
                    | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
        return main;
    }

    /** {@code frames} frames, the last read back: rgba floats per pixel. */
    private float[] frames(int frames) {
        for (int i = 0; i < frames; i++) {
            boolean last = i == frames - 1;
            device.submitAndWait(device.graphics(), commands -> {
                graph.record(commands);
                if (last) {
                    try (MemoryStack stack = stackPush()) {
                        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                        region.imageExtent().set(SIZE, SIZE, 1);
                        vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            pixels.handle(), region);
                    }
                }
            });
        }
        return pixels.memory().asSlice(0, (long) SIZE * SIZE * 16).toArray(ValueLayout.JAVA_FLOAT);
    }

    private static double mean(float[] image, int channel) {
        double sum = 0;
        for (int p = 0; p < SIZE * SIZE; p++) {
            sum += image[4 * p + channel];
        }
        return sum / (SIZE * SIZE);
    }

    @Test
    void aClosedBoxGlowingAndReflectingEvenlyIsEOverOneMinusTheAlbedoThroughTheProbes() {
        float rho = 0.5f;
        float glow = 0.25f;
        int furnace = materials.add(new Material(MR.withBaseColor(new Color(rho, rho, rho, 1))
            .withEmissive(new Color(glow, glow, glow, 1))).feature(LAMBERT));
        box(furnace);
        lights.setCount(0);
        lit("main", Environment.uniform("black", 0, 0, 0));
        float[] image = frames(60);
        double expected = glow / (1 - rho);
        double worst = 0;
        for (int p = 0; p < SIZE * SIZE; p++) {
            worst = Math.max(worst, Math.abs(image[4 * p] - expected) / expected);
        }
        assertThat(mean(image, 0)).as("the mean radiance").isCloseTo(expected, within(expected * 0.03));
        assertThat(worst).as("each pixel").isLessThan(0.06);
        // Without the volume the walls show their glow and the black environment's nothing
        ProbeVolumeLighting.SLOT.choose(graph.settings(), null);
        image = frames(3);
        assertThat(mean(image, 0)).as("without global illumination").isCloseTo(glow, within(glow * 0.01));
        ProbeVolumeLighting.SLOT.choose(graph.settings(), ProbeVolumeLighting.PROBES);
    }

    /**
     * Frames until a light's change shows within 10 % of what the image settles at (design D3): the light inside the
     * box turns from red to green; the walls' light is mostly bounced (the camera faces away from where it shines).
     */
    private int lag() {
        lights.point(0, 0, 0.6, 0.5, 4, 2, 0, 0);
        float[] red = frames(80);
        lights.point(0, 0, 0.6, 0.5, 4, 0, 2, 0);
        float[][] seen = new float[40][];
        for (int f = 0; f < seen.length; f++) {
            seen[f] = frames(1);
        }
        float[] green = seen[seen.length - 1];
        double change = Math.abs(mean(green, 1) - mean(red, 1)) + Math.abs(mean(green, 0) - mean(red, 0));
        for (int f = 0; f < seen.length; f++) {
            double left = Math.abs(mean(seen[f], 1) - mean(green, 1)) + Math.abs(mean(seen[f], 0) - mean(green, 0));
            if (left <= 0.1 * change) {
                return f + 1;
            }
        }
        return seen.length + 1;
    }

    @Test
    void aChangedLightShowsWithinFourFramesAndUnannouncedWithinFifteen() {
        int white = materials.add(new Material(MR.withBaseColor(new Color(0.5f, 0.5f, 0.5f, 1))).feature(LAMBERT));
        box(white);
        lights.setCount(1);
        lit("main", Environment.uniform("black", 0, 0, 0));
        int known = lag();
        assertThat(known).as("frames to 90 % after a light the volume is told of changed").isLessThanOrEqualTo(4);
        graph.settings().set(ProbeVolume.INVALIDATE, false);
        int unannounced = lag();
        System.out.println("LAG known " + known + " unannounced " + unannounced);
        assertThat(unannounced).as("frames to 90 % with the change found by the probes alone").isLessThanOrEqualTo(15);
        graph.settings().set(ProbeVolume.ADAPTIVE, false);
        int plain = lag();
        assertThat(plain).as("frames to 90 % by the history cap alone (the targets need the response)")
            .isGreaterThan(unannounced);
    }

    @Test
    void twoViewsShareOneVolume() {
        int white = materials.add(new Material(MR.withBaseColor(new Color(0.8f, 0.8f, 0.8f, 1))).feature(LAMBERT));
        box(white);
        lights.setCount(1);
        lights.point(0, 0, 0.6, 0.5, 4, 2, 2, 2);
        Environment black = Environment.uniform("black", 0, 0, 0);
        SceneView left = lit("left", black);
        camera.set(0.3, -0.2, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.01);
        Camera other = new Camera().set(-0.3, -0.2, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.01);
        RenderView otherView = renderer.createView(ViewOptions.of(256, 1 << 12));
        otherView.set(other, 1, MeshletRenderer.MODE_LIT);
        SceneView right = new SceneView(graph.addView("right").size(SIZE, SIZE), otherView, other);
        right.port().add(new VisibilityBufferShading(SceneGeometry.meshlets(renderer), right,
            VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)).add(new ClusteredLights(lights, right))
            .add(new EnvironmentLighting(black, right, SMALL))
            .add(new ProbeVolumeLighting(right, new ProbeVolume(rayTracing, renderer, lights, black, SMALL, left)));
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT
            | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        GpuImage second = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        try {
            right.port().output(Keys.SCENE_COLOR, new ExternalImage("right", SIZE, SIZE, Format.R8G8B8A8_UNORM,
                usage, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(second.handle(), second.view()));
            frames(4);
            assertThat(Collections.frequency(graph.passes(), "probe blending")).as("updates of the volume a frame")
                .isEqualTo(1);
            assertThat(Collections.frequency(graph.passes(), "probe volume lighting")).as("views lit by it")
                .isEqualTo(2);
        } finally {
            device.waitIdle();
            graph.close();
            graph = new FrameGraph(device, 2);
            second.close();
            otherView.close();
        }
    }

    @Test
    void probeVolumeFramesAllocateNothing() {
        int white = materials.add(new Material(MR.withBaseColor(new Color(0.8f, 0.8f, 0.8f, 1))).feature(LAMBERT));
        box(white);
        lights.setCount(1);
        lights.point(0, 0, 0.6, 0.5, 4, 2, 2, 2);
        lit("main", Environment.uniform("grey", 0.1f, 0.1f, 0.1f));
        frames(3);
        long builds = graph.builds();
        for (int i = 0; i < 5; i++) {
            graph.beginFrame();
            graph.submit(0, 0);
        }
        assertThat(graph.builds()).as("no rebuilds").isEqualTo(builds);
        assertAllocationFree(() -> {
            graph.beginFrame();
            graph.submit(0, 0);
        }, 4000, 300, 8);
        device.waitIdle();
    }
}
