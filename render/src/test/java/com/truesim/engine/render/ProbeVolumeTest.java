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
     * A surface hook changes surfaces at the probes' rays' hits as it does on screen: the furnace's walls tinted to half
     * their albedo everywhere is E / (1 - rho / 2); tinted on screen alone it would be E + rho / 2 E / (1 - rho). A
     * material opting out is untouched at hits too: E / (1 - rho).
     */
    @Test
    void aSurfaceHookChangesTheProbesHitsAsItDoesTheScreenButNotTheOptedOut() {
        float rho = 0.5f;
        float glow = 0.25f;
        Material walls = new Material(MR.withBaseColor(new Color(rho, rho, rho, 1))
            .withEmissive(new Color(glow, glow, glow, 1))).feature(LAMBERT);
        int furnace = materials.add(walls);
        int optedOut = materials.add(walls.refuseHooks("test.tint"));
        box(furnace);
        lights.setCount(0);
        ShadingHookValues values = ShadingHookValues.of(device);
        values.set("test.tint", new com.truesim.engine.render.materials.TestTintParameters(
            new com.truesim.common.math.Vec3(0.5, 0.5, 0.5), 0), true);
        try {
            lit("main", Environment.uniform("black", 0, 0, 0));
            double tinted = glow / (1 - rho / 2);
            assertThat(mean(frames(60), 0)).as("tinted at hits and on screen").isCloseTo(tinted, within(tinted * 0.03));
            box(optedOut);
            double plain = glow / (1 - rho);
            assertThat(mean(frames(60), 0)).as("opted out").isCloseTo(plain, within(plain * 0.03));
        } finally {
            values.remove("test.tint");
        }
    }

    /**
     * Probe rays' hits take their instance's override entry as the screen does: the furnace untinted by the view's
     * values (tint 1), its six walls given an entry halving their albedo, is E / (1 - rho / 2) (tinted on screen alone,
     * E + rho / 2 E / (1 - rho), 12 % brighter); the walls' entry taken away again, E / (1 - rho).
     */
    @Test
    void probeHitsTakeTheirInstancesOverrides() {
        float rho = 0.5f;
        float glow = 0.25f;
        int furnace = materials.add(new Material(MR.withBaseColor(new Color(rho, rho, rho, 1))
            .withEmissive(new Color(glow, glow, glow, 1))).feature(LAMBERT));
        box(furnace);
        lights.setCount(0);
        ShadingHookValues values = ShadingHookValues.of(device);
        values.set("test.tint", com.truesim.engine.render.materials.TestTintParameters.DEFAULTS, true);
        ShadingHookOverrides overrides = scene.hookOverrides();
        int half = overrides.add();
        overrides.set(half, "test.tint", new com.truesim.engine.render.materials.TestTintParameters(
            new com.truesim.common.math.Vec3(0.5, 0.5, 0.5), 0));
        try {
            lit("main", Environment.uniform("black", 0, 0, 0));
            double plain = glow / (1 - rho);
            assertThat(mean(frames(60), 0)).as("no instance overridden").isCloseTo(plain, within(plain * 0.03));
            for (int wall = 0; wall < 6; wall++) {
                overrides.assign(wall, half);
            }
            double tinted = glow / (1 - rho / 2);
            assertThat(mean(frames(60), 0)).as("tinted by the walls' entry at hits and on screen")
                .isCloseTo(tinted, within(tinted * 0.03));
            for (int wall = 0; wall < 6; wall++) {
                overrides.assign(wall, 0);
            }
            assertThat(mean(frames(60), 0)).as("the entry taken away").isCloseTo(plain, within(plain * 0.03));
        } finally {
            values.remove("test.tint");
        }
    }

    /**
     * The probes' shadow rays go through the holes of a type's keep(): a closed box lit by the sun through its ceiling,
     * a type cut in a checkerboard (half holes), shows the sun's light bounced inside through the probe volume; with
     * the same type keeping all of the ceiling (one cell), nothing comes in. Without light rays waiting for keep(),
     * both are dark: a hooked triangle stops every shadow ray.
     */
    @Test
    void probeShadowRaysGoThroughTheHolesOfKeep() {
        MeshData cut = ShadowOwnerTest.uvQuad();
        int cutQuad = pool.add(cut);
        rayTracing.add(cutQuad, cut, cut.levels().size() - 1);
        materials.register(com.truesim.engine.render.materials.Dissolve.TYPE);
        int white = materials.add(new Material(MR.withBaseColor(new Color(0.8f, 0.8f, 0.8f, 1))).feature(LAMBERT));
        com.truesim.engine.render.materials.DissolveParameters black =
            com.truesim.engine.render.materials.DissolveParameters.DEFAULTS.withColor(new Color(0, 0, 0, 1));
        int holes = materials.add(new Material(black.withCell(0.5f)));
        int whole = materials.add(new Material(black.withCell(1)));
        box(white);
        scene.set(3, cutQuad, holes, 0, 1, 0, ReferencePathTracerTest.DOWN[0], ReferencePathTracerTest.DOWN[1],
            ReferencePathTracerTest.DOWN[2], ReferencePathTracerTest.DOWN[3], 1.001f, 1.001f, 1.001f);
        lights.directional(0, 0.1f, -1, 0.05f, 3, 3, 3).setCount(1);
        lit("main", Environment.uniform("black", 0, 0, 0));
        double through = mean(frames(60), 0);
        ProbeVolumeLighting.SLOT.choose(graph.settings(), null);
        double direct = mean(frames(3), 0);
        ProbeVolumeLighting.SLOT.choose(graph.settings(), ProbeVolumeLighting.PROBES);
        scene.set(3, cutQuad, whole, 0, 1, 0, ReferencePathTracerTest.DOWN[0], ReferencePathTracerTest.DOWN[1],
            ReferencePathTracerTest.DOWN[2], ReferencePathTracerTest.DOWN[3], 1.001f, 1.001f, 1.001f);
        double shut = mean(frames(60), 0);
        assertThat(through - direct).as("light bounced in through the holes (%s with, %s without the volume, %s shut)",
            through, direct, shut).isGreaterThan(0.05);
        assertThat(shut - direct).as("the ceiling whole: nothing comes in").isLessThan((through - direct) / 10);
    }

    @Test
    void theUpdateOnTheAsyncComputeQueueLightsTheSame() {
        float rho = 0.5f;
        float glow = 0.25f;
        int furnace = materials.add(new Material(MR.withBaseColor(new Color(rho, rho, rho, 1))
            .withEmissive(new Color(glow, glow, glow, 1))).feature(LAMBERT));
        box(furnace);
        lights.setCount(0);
        graph.asyncCompute(true).settings().set(ProbeVolume.ASYNC, true);
        lit("main", Environment.uniform("black", 0, 0, 0));
        float[] image = frames(60);
        double expected = glow / (1 - rho);
        assertThat(mean(image, 0)).as("the mean radiance").isCloseTo(expected, within(expected * 0.03));
        ProbeVolumeLighting.SLOT.choose(graph.settings(), ProbeVolumeLighting.CASCADES);
        image = frames(30);
        assertThat(mean(image, 0)).as("the cascades' mean radiance").isCloseTo(expected, within(expected * 0.03));
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

    /**
     * A dim sky holding a small bright sun (10 degrees across, 320 times as bright as the sky: two thirds of what reaches
     * the ground), no lights: a floor seen from above, lit by the volume alone. The rays are turned anew each frame,
     * the same way for every probe; taken along their directions alone, the sun was met in a few frames by one of them
     * (by every probe at once) and in the others by none: the floor's light jumped by 380 to 660 % in those frames
     * (the film of effects-particles' ParticleHookScreenshots flashed so). Through the environment's level twice as wide
     * as each ray's share of the sphere (the default), every frame takes the sun by its share.
     */
    @Test
    void aSmallBrightSunInTheSkyLightsEveryFrameAlikeNotInJumps() {
        int floor = materials.add(new Material(MR.withBaseColor(new Color(0.8f, 0.8f, 0.8f, 1))).feature(LAMBERT));
        wall(0, floor, 0, -1, 0, ReferencePathTracerTest.UP, 8);
        lights.setCount(0);
        try (Textures textures = new Textures(device)) {
            int width = 256;
            int sky = textures.add(TextureFormat.RGBA16F, width, width / 2, Textures.GENERATE, sunSky(width,
                width / 2));
            Environment environment = Environment.panorama("sun sky", sky, 128);
            camera.set(0, -1, -0.4, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.01);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            SceneView main = new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera);
            main.port().add(new VisibilityBufferShading(SceneGeometry.meshlets(renderer), main,
                    VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_D32_SFLOAT)).add(new ClusteredLights(lights, main))
                .add(new EnvironmentLighting(environment, main))
                .add(new ProbeVolumeLighting(main, new ProbeVolume(rayTracing, renderer, lights, environment, main)))
                .output(Keys.SCENE_COLOR, new ExternalImage("target", SIZE, SIZE, Format.R32G32B32A32_SFLOAT,
                    VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT
                        | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
            // Along the rays' directions alone (the volume before): the sun met in some frames only
            graph.settings().set(ProbeVolume.SKY_FOOTPRINT, 0f);
            double alone = largestJump(means(60));
            graph.settings().reset(ProbeVolume.SKY_FOOTPRINT);
            double footprint = largestJump(means(60));
            System.out.printf("largest jump of a frame's mean from its neighbours': %.4f along the rays alone, %.4f"
                + " through their footprints%n", alone, footprint);
            assertThat(alone).as("the test meets the sun along the rays alone").isGreaterThan(0.2);
            assertThat(footprint).as("a frame's light against its neighbours'").isLessThan(0.01)
                .isLessThan(alone / 5);
        }
    }

    /** {@code count} frames' mean red, each frame's, after 40 frames to settle. */
    private double[] means(int count) {
        frames(40); // settled: no update before in the histories
        double[] means = new double[count];
        for (int f = 0; f < count; f++) {
            means[f] = mean(frames(1), 0);
        }
        return means;
    }

    /** The largest difference of a frame's mean from its neighbours' mean, relative to it. */
    private static double largestJump(double[] means) {
        double largest = 0;
        for (int f = 1; f + 1 < means.length; f++) {
            double around = (means[f - 1] + means[f + 1]) / 2;
            largest = Math.max(largest, Math.abs(means[f] - around) / around);
        }
        return largest;
    }

    /**
     * A panorama of half floats: a dim sky (0.05) over a dark ground (0.02), and a sun 10 degrees across (16), 50
     * degrees up.
     */
    private static java.lang.foreign.MemorySegment sunSky(int width, int height) {
        java.lang.foreign.MemorySegment texels = java.lang.foreign.Arena.ofAuto().allocate(8L * width * height);
        ValueLayout.OfShort half = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
        double up = Math.toRadians(50);
        double[] sun = {Math.cos(up) * 0.6, Math.sin(up), Math.cos(up) * 0.8};
        double edge = Math.cos(Math.toRadians(5));
        for (int y = 0; y < height; y++) {
            double theta = Math.PI * (y + 0.5) / height; // environment/filter.slang's panoramaUv
            for (int x = 0; x < width; x++) {
                double phi = 2 * Math.PI * ((x + 0.5) / width - 0.5);
                double[] d = {Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi)};
                double c = d[0] * sun[0] + d[1] * sun[1] + d[2] * sun[2] > edge ? 16 : d[1] >= 0 ? 0.05 : 0.02;
                for (int k = 0; k < 3; k++) {
                    texels.set(half, 8L * (y * width + x) + 2L * k, Float.floatToFloat16((float) c));
                }
                texels.set(half, 8L * (y * width + x) + 6, Float.floatToFloat16(1));
            }
        }
        return texels;
    }

    /**
     * A floor lit only through the volume by a small bright lamp over it (an emissive square 26 cm across, a black
     * sky): the probes' rays meet the lamp in some updates and not in others. The adaptive test took an update that
     * missed it for a change (that update's rays showed little noise) and restarted the history, again and again, and
     * the restarted probes' light, bounced, changed their neighbours' updates too: the floor's light flickered from frame
     * to frame, 47 times as much as by the history alone (the dusk film of effects-particles' ParticleHookScreenshots,
     * its lamp posts' bulbs). Against the noise its history's updates showed as well, 2.5 times (RX 9070 XT).
     */
    @Test
    void aSmallBrightLampFlickersNoMoreWithTheAdaptiveTestThanWithout() {
        int floor = materials.add(new Material(MR.withBaseColor(new Color(0.8f, 0.8f, 0.8f, 1))).feature(LAMBERT));
        int lamp = materials.add(new Material(MR.withBaseColor(new Color(0, 0, 0, 1))
            .withEmissive(new Color(20, 20, 20, 1))).feature(LAMBERT));
        wall(0, floor, 0, -1, 0, ReferencePathTracerTest.UP, 8);
        wall(1, lamp, 0.4f, -0.35f, -1.2f, ReferencePathTracerTest.DOWN, 0.13f);
        lights.setCount(0);
        camera.set(0, -1, -0.6, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.01);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        Environment black = Environment.uniform("black", 0, 0, 0);
        SceneView main = new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera);
        main.port().add(new VisibilityBufferShading(SceneGeometry.meshlets(renderer), main,
                VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_D32_SFLOAT)).add(new ClusteredLights(lights, main))
            .add(new EnvironmentLighting(black, main, SMALL))
            .add(new ProbeVolumeLighting(main, new ProbeVolume(rayTracing, renderer, lights, black, SMALL, main)))
            .output(Keys.SCENE_COLOR, new ExternalImage("target", SIZE, SIZE, Format.R32G32B32A32_SFLOAT,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT
                    | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
        double adaptive = flicker(60);
        graph.settings().set(ProbeVolume.ADAPTIVE, false);
        double history = flicker(60);
        System.out.printf("a pixel's light off its neighbouring frames', relative: %.4f with the adaptive test, %.4f"
            + " by the history alone%n", adaptive, history);
        assertThat(adaptive).as("the adaptive test against the history alone (%s)", history)
            .isLessThan(4 * history);
    }

    /**
     * After 90 frames to settle, {@code count} frames: each pixel's red off the mean of its frames before and after,
     * summed over pixels and frames, relative to the light summed.
     */
    private double flicker(int count) {
        frames(90); // the histories full (30 updates, in turns of two frames)
        float[][] seen = new float[count][];
        for (int f = 0; f < count; f++) {
            seen[f] = frames(1);
        }
        double off = 0;
        double light = 0;
        for (int f = 1; f + 1 < count; f++) {
            for (int p = 0; p < SIZE * SIZE; p++) {
                off += Math.abs(seen[f][4 * p] - (seen[f - 1][4 * p] + seen[f + 1][4 * p]) / 2);
                light += seen[f][4 * p];
            }
        }
        return off / light;
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
            assertThat(Collections.frequency(graph.passes(), "shading hooks")).as("views writing their hook blocks")
                .isEqualTo(2);
            int hook = ShadingHooks.of(device).require(ProbeVolume.HOOK);
            for (RenderView lit : new RenderView[] {view, otherView}) {
                assertThat(lit.hooks().getInt(ShadingHooks.activeWord(hook)) & 1 << hook % 32)
                    .as("view %d lit by it: its hook on", lit.index()).isNotZero();
            }
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
