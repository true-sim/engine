package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.shadows.RayTracedShadows;
import com.truesim.engine.render.shadows.ShadowMapShadows;
import com.truesim.engine.render.shadows.CascadedShadowMaps;
import com.truesim.engine.render.shadows.InlineRayShadows;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexFormat;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Shadows per light through the frame graph, validated (synchronization too): a 2 x 2 quad floats 2 units over a
 * 20 x 20 floor, lit by directional lights whose shadows come from a shadow map, a ray-traced mask or inline ray
 * queries (where the device traces rays), under both shading techniques; lights of one frame mixing techniques.
 */
class ShadowsTest {

    private static final int SIZE = 128;

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private RenderView view;     // the camera's
    private RenderView sunView;  // a shadow map's
    private RayTracing rayTracing;
    private FrameGraph graph;
    private GpuImage colorTarget;
    private GpuBuffer pixels;
    private final Camera camera = new Camera();
    private MeshData quadMesh;
    private int quad;

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
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 12, 64, 4));
        quadMesh = MeshletRendererTest.grid(1, 2, false, VertexFormat.POSITIONS_ONLY);
        quad = pool.add(quadMesh);
        scene = new GpuScene(device, pool, 16);
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
        view = renderer.createView(ViewOptions.of(256, 1 << 12).withOcclusion(true));
        sunView = renderer.createView(ViewOptions.shadow(256, 1 << 12));
        // The camera at the origin looks down at the floor (y = -5, around z = -5); the quad floats at y = -3.
        scene.set(0, quad, 0, -5, -5, 0, 0, 0, 1, 10, 1, 10);
        scene.set(1, quad, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
        camera.set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        graph = new FrameGraph(device, 2);
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        colorTarget = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        pixels = GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            graph.close();
            if (rayTracing != null) {
                rayTracing.close();
            }
            pixels.close();
            colorTarget.close();
            renderer.close();
            scene.close();
            pool.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** Records a frame and reads back {@code target}: one byte per texel for the mask, four for color. */
    private byte[] frame(GpuImage target, int bytesPerTexel) {
        device.submitAndWait(device.graphics(), commands -> {
            graph.record(commands);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(SIZE, SIZE, 1);
                vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        byte[] texels = new byte[SIZE * SIZE * bytesPerTexel];
        pixels.memory().asSlice(0, texels.length).asByteBuffer().get(texels);
        return texels;
    }

    private ExternalImage external(GpuImage image, int format) {
        return new ExternalImage("target", SIZE, SIZE, com.truesim.engine.vulkan.Format.of(format), VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
            .set(image.handle(), image.view());
    }

    /** The pixel camera-relative point (x, y, z) projects to: x + y * SIZE. */
    private int pixel(double x, double y, double z) {
        float[] m = camera.viewProjection();
        double cx = m[0] * x + m[4] * y + m[8] * z + m[12];
        double cy = m[1] * x + m[5] * y + m[9] * z + m[13];
        double w = m[3] * x + m[7] * y + m[11] * z + m[15];
        int px = (int) ((cx / w * 0.5 + 0.5) * SIZE);
        int py = (int) ((cy / w * 0.5 + 0.5) * SIZE);
        return px + py * SIZE;
    }

    /** The red channel of a frame's color at the floor point (x, -5, z). */
    private static int floor(byte[] color, ShadowsTest test, double x, double z) {
        return color[4 * test.pixel(x, -5, z)] & 0xff;
    }

    /** Where the quad's middle (0, -3, -5) casts its shadow on the floor, 2 below, along direction (dx, dy, dz). */
    private static double[] shadowOf(double dx, double dy, double dz) {
        double run = 2 / -dy;
        return new double[] {dx * run, -5 + dz * run};
    }

    /** An orthographic camera looking along (dx, dy, dz) at the floor's middle: a directional light's map. */
    private static Camera lightCamera(double dx, double dy, double dz) {
        return new Camera().setOrthographic(-20 * dx, -5 - 20 * dy, -5 - 20 * dz, dx, dy, dz, 1, 0, 0, 8, 8, 40,
            1024, 1024);
    }

    /** How dark shadowed floor is against lit floor, at most. */
    private double shadowRatio = 0.5;

    /** Closes the graph for a new one with a view "main" of {@link #view} through the camera, {@link #SIZE} square. */
    private SceneView newGraph() {
        graph.close();
        graph = new FrameGraph(device, 2);
        return new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera);
    }

    /** Clustered forward shading of {@code geometry}, for a view. */
    private static Function<SceneView, Technique> forward(SceneGeometry geometry) {
        return main -> new ClusteredForwardShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
    }

    /** Visibility buffer shading of {@code geometry}, for a view. */
    private static Function<SceneView, Technique> visibility(SceneGeometry geometry) {
        return main -> new VisibilityBufferShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
    }

    /**
     * Draws a frame in a new graph with {@code shading}, {@code lights} binned and the shadow techniques, and checks
     * the floor is shadowed at {@code shadow}.
     */
    private byte[] expectShadow(Function<SceneView, Technique> shading, String what, double[] shadow, Lights lights,
        Technique... shadows) {
        SceneView main = newGraph();
        main.port().add(shading.apply(main)).add(new ClusteredLights(lights, main));
        for (Technique technique : shadows) {
            main.port().add(technique);
        }
        main.port().output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
        frame(colorTarget, 4);
        byte[] color = frame(colorTarget, 4); // the late phase drew everything the first frame
        int lit = floor(color, this, 3, -2);
        int dark = floor(color, this, shadow[0], shadow[1]);
        assertThat(dark).as("%s: the floor in shadow, against %d in the light", what, lit)
            .isLessThan((int) (lit * shadowRatio));
        assertThat(floor(color, this, shadow[0] + 0.4, shadow[1] - 0.4)).as("%s: still in shadow", what)
            .isLessThan((int) (lit * shadowRatio));
        return color;
    }

    @Test
    void everyKindOfShadowFallsWhereTheLightSays() {
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z}; // where the sun shines
        try (Lights lights = new Lights(device, 4, 4)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
            ShadowMapShadows mapped = new ShadowMapShadows(geometry, lights, 0, 0, lightCamera(sun[0], sun[1], sun[2]),
                1024, sunView, 1.5e-3f);
            Function<SceneView, Technique> forward = forward(geometry);
            Function<SceneView, Technique> visibility = visibility(geometry);
            expectShadow(forward, "forward, shadow map", shadow, lights, mapped);
            assertThat(graph.passes()).contains("meshlet depth, view 1").containsOnlyOnce("meshlet culling");
            assertThat(sunView.visibleMeshlets()).isEqualTo(2); // the sun's view drew both quads
            expectShadow(visibility, "visibility buffer, shadow map", shadow, lights, mapped);
            if (!device.rayTracing()) {
                return;
            }
            rayTracing = new RayTracing(device, pool, 16);
            rayTracing.add(quad, quadMesh, quadMesh.levels().size() - 1);
            assertThatIllegalStateException().isThrownBy(() -> rayTracing.add(quad, quadMesh, 0));
            // A mask needs the camera's depth before shading: for forward, a depth-only draw of the camera as a view of
            // its own; for the visibility buffer, the IDs' draw (the same as its own).
            SceneDraw depthOnly = new SceneDraw(geometry, geometry.createView(ViewOptions.of(256, 1 << 12)), camera,
                SurfaceOutput.depth(), VK_FORMAT_D32_SFLOAT, SIZE, SIZE, false);
            SceneDraw ids = new SceneDraw(geometry, view, null, SurfaceOutput.ids(), VK_FORMAT_D32_SFLOAT, SIZE, SIZE,
                true);
            byte[] masked = expectShadow(forward, "forward, ray-traced mask", shadow, lights,
                new RayTracedShadows(rayTracing, lights, 0, 0, depthOnly, camera));
            assertThat(graph.passes()).contains("ray-traced shadows of light 0", "top level");
            expectShadow(visibility, "visibility buffer, ray-traced mask", shadow, lights,
                new RayTracedShadows(rayTracing, lights, 0, 0, ids, camera));
            assertThat(graph.passes()).containsOnlyOnce("meshlet ids early");
            byte[] inline = expectShadow(visibility, "visibility buffer, inline rays", shadow, lights,
                new InlineRayShadows(rayTracing, view, lights, 0, 0).offset(2e-3f));
            assertThat(graph.passes()).doesNotContain("ray-traced shadows of light 0");
            int differ = 0;
            for (int i = 0; i < inline.length; i += 4) {
                differ += Math.abs((inline[i] & 0xff) - (masked[i] & 0xff)) > 32 ? 1 : 0;
            }
            assertThat(differ).as("inline rays against the mask").isLessThan(SIZE * SIZE / 50);
            // Two views tracing inline shadows, one looking from 2 right and 1 back: one build of the top level a frame,
            // relative to an origin away from both cameras; each view moves its rays by its camera less the origin.
            graph.close();
            graph = new FrameGraph(device, 2);
            rayTracing.origin(-3, 1, 2);
            assertThat(rayTracing.origin(0)).isEqualTo(-3);
            Camera away = new Camera().set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1).position(2, 0, 1);
            com.truesim.engine.graph.ViewPort awayPort = graph.addView("away").size(SIZE, SIZE);
            SceneView anchor = geometry.createView(awayPort, away, ViewOptions.of(256, 1 << 12).withOcclusion(true))
                .set(1, MeshletRenderer.MODE_LIT);
            SceneView checked = new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera);
            try (GpuImage awayTarget = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT)) {
                for (SceneView traced : List.of(anchor, checked)) {
                    traced.port().add(visibility.apply(traced)).add(new ClusteredLights(lights, traced))
                        .add(new InlineRayShadows(rayTracing, traced.view(), lights, 0, 0).offset(2e-3f));
                }
                awayPort.output(Keys.SCENE_COLOR, external(awayTarget, VK_FORMAT_R8G8B8A8_UNORM));
                checked.port().output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
                frame(colorTarget, 4);
                byte[] two = frame(colorTarget, 4);
                assertThat(graph.passes()).containsOnlyOnce("top level");
                int lit = floor(two, this, 3, -2);
                assertThat(floor(two, this, shadow[0], shadow[1])).as("in shadow, the top level relative to the origin")
                    .isLessThan((int) (lit * shadowRatio));
                assertThat(floor(two, this, shadow[0] + 0.4, shadow[1] - 0.4)).isLessThan((int) (lit * shadowRatio));
                device.waitIdle();
            }
            rayTracing.origin(0, 0, 0);
            // Forward shading cannot trace inline: the light shines unshadowed.
            SceneView main = newGraph();
            main.port().add(forward.apply(main)).add(new ClusteredLights(lights, main))
                .add(new InlineRayShadows(rayTracing, view, lights, 0, 0))
                .output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
            frame(colorTarget, 4);
            byte[] color = frame(colorTarget, 4);
            assertThat(floor(color, this, shadow[0], shadow[1])).isGreaterThan(floor(color, this, 3, -2) / 2);
        }
    }

    @Test
    void eachViewCastsThroughCascadesOfItsOwn() {
        // Two views through the same camera, each with cascades of its own in its own shadow table: one reaching 50
        // units, the other 1, short of the floor. Sharing one table, the last one written would serve both.
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
        try (Lights lights = new Lights(device, 4, 4); GpuImage shortTarget = new GpuImage(device, SIZE, SIZE,
            VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
            VK_IMAGE_ASPECT_COLOR_BIT)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            SceneView far = newGraph();
            SceneView near = geometry.createView(graph.addView("short").size(SIZE, SIZE), camera,
                ViewOptions.of(256, 1 << 12).withOcclusion(true)).set(1, MeshletRenderer.MODE_LIT);
            CascadedShadowMaps farCascades = new CascadedShadowMaps(geometry, lights, 0, 0, far, 3, 1024,
                ViewOptions.shadow(256, 1 << 12), 50);
            CascadedShadowMaps nearCascades = new CascadedShadowMaps(geometry, lights, 0, 0, near, 3, 1024,
                ViewOptions.shadow(256, 1 << 12), 1);
            far.port().add(forward(geometry).apply(far)).add(new ClusteredLights(lights, far)).add(farCascades)
                .output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
            near.port().add(forward(geometry).apply(near)).add(new ClusteredLights(lights, near)).add(nearCascades)
                .output(Keys.SCENE_COLOR, external(shortTarget, VK_FORMAT_R8G8B8A8_UNORM));
            assertThat(lights.shadowTable(far.port())).isNotSameAs(lights.shadowTable(near.port()));
            farCascades.update();
            nearCascades.update();
            frame(colorTarget, 4);
            byte[] reaching = frame(colorTarget, 4);
            byte[] falling = frame(shortTarget, 4);
            int lit = floor(reaching, this, 3, -2);
            assertThat(floor(reaching, this, shadow[0], shadow[1])).as("cascades reaching the floor: in shadow")
                .isLessThan((int) (lit * shadowRatio));
            assertThat(floor(falling, this, shadow[0], shadow[1])).as("cascades short of the floor: lit")
                .isGreaterThan((int) (floor(falling, this, 3, -2) * 0.8));
            // The cascades' split blend from the graph's settings, refit at the next update: uniform splits.
            graph.settings().set(com.truesim.engine.render.shadows.ShadowSettings.CASCADE_LAMBDA, 0f);
            farCascades.update();
            assertThat(farCascades.split(0)).as("a third of 50 units").isCloseTo(50f / 3, org.assertj.core.data.Offset
                .offset(0.1f));
            farCascades.lambda(1).update();
            assertThat(farCascades.split(0)).as("its own value wins").isLessThan(10);
            // A view removed: its shadow table and its cascades' views go with it.
            int tables = lights.shadowTableCount();
            int views = renderer.views().size();
            graph.removeView(near.port());
            frame(colorTarget, 4);
            assertThat(lights.shadowTableCount()).as("freed once no frame reads it").isEqualTo(tables - 1);
            assertThat(renderer.views()).as("its render view and its 3 cascades'").hasSize(views - 4);
            device.waitIdle();
        }
    }

    @Test
    void viewsShareOneSetOfCascadesDrawnOnce() {
        // Two views, as a headset's eyes, a little apart: one set of cascades fitted to the first, with a margin for the
        // second, shared with it; its maps draw once, and both floors are shadowed.
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
        try (Lights lights = new Lights(device, 4, 4); GpuImage rightTarget = new GpuImage(device, SIZE, SIZE,
            VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
            VK_IMAGE_ASPECT_COLOR_BIT)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            SceneView left = newGraph();
            Camera rightCamera = new Camera().position(camera.position(0) + 0.064, camera.position(1),
                camera.position(2));
            rightCamera.set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1); // as the first, 64 mm to the right
            SceneView right = geometry.createView(graph.addView("right").size(SIZE, SIZE), rightCamera,
                ViewOptions.of(256, 1 << 12).withOcclusion(true)).set(1, MeshletRenderer.MODE_LIT);
            CascadedShadowMaps shared = new CascadedShadowMaps(geometry, lights, 0, 0, camera, 3, 1024,
                ViewOptions.shadow(256, 1 << 12), 40).margin(0.1f);
            assertThatIllegalArgumentException().isThrownBy(() -> shared.margin(-1));
            left.port().add(forward(geometry).apply(left)).add(new ClusteredLights(lights, left)).add(shared)
                .output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
            right.port().add(forward(geometry).apply(right)).add(new ClusteredLights(lights, right)).add(shared.share())
                .output(Keys.SCENE_COLOR, external(rightTarget, VK_FORMAT_R8G8B8A8_UNORM));
            shared.update();
            frame(colorTarget, 4);
            byte[] leftColor = frame(colorTarget, 4);
            byte[] rightColor = frame(rightTarget, 4);
            for (int i = 0; i < 3; i++) {
                assertThat(graph.passes()).containsOnlyOnce("meshlet depth, view " + shared.view(i).index());
            }
            // 64 mm apart, the floor points fall on the same pixels, near enough.
            assertThat(floor(leftColor, this, shadow[0], shadow[1])).as("the first view's floor in shadow")
                .isLessThan((int) (floor(leftColor, this, 3, -2) * shadowRatio));
            assertThat(floor(rightColor, this, shadow[0], shadow[1])).as("the second view's, from the same maps")
                .isLessThan((int) (floor(rightColor, this, 3, -2) * shadowRatio));
            device.waitIdle();
        }
    }

    @Test
    void theTopLevelIsBuiltOnlyWhenInstancesTheOriginOrBottomLevelsChange() {
        assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        rayTracing = new RayTracing(device, pool, 16);
        rayTracing.add(quad, quadMesh, quadMesh.levels().size() - 1);
        try (Lights lights = new Lights(device, 4, 4)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
            InlineRayShadows rays = new InlineRayShadows(rayTracing, view, lights, 0, 0).offset(2e-3f);
            expectShadow(visibility(geometry), "the first build", shadow, lights, rays);
            assertThat(rayTracing.topLevelBuilds()).as("two frames, nothing changed after the first").isEqualTo(1);
            frame(colorTarget, 4);
            frame(colorTarget, 4);
            assertThat(rayTracing.topLevelBuilds()).as("a still scene").isEqualTo(1);
            // The floating quad moves 1 right: rebuilt, the shadow with it.
            scene.set(1, quad, 1, -3, -5, 0, 0, 0, 1, 1, 1, 1);
            expectShadow(visibility(geometry), "the quad moved", new double[] {shadow[0] + 1, shadow[1]}, lights, rays);
            assertThat(rayTracing.topLevelBuilds()).isEqualTo(2);
            // The origin moves: rebuilt relative to it, the shadow where it was.
            rayTracing.origin(5, 0, -5);
            expectShadow(visibility(geometry), "the origin moved", new double[] {shadow[0] + 1, shadow[1]}, lights,
                rays);
            assertThat(rayTracing.topLevelBuilds()).isEqualTo(3);
        }
        scene.set(1, quad, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
    }

    @Test
    void shadowMapsStayInTheWorldWhileTheCameraMoves() {
        // The scene 7,000 km from the origin. The sun's camera sits at a world position of its own over the floor,
        // not relative to the camera: its map does not change as the camera moves, and serves the camera wherever it
        // is (as it would a mirror's view or another player's).
        double wx = 3_123_456.5;
        double wy = -45_678.25;
        double wz = -7_000_123.75;
        scene.set(0, quad, 0, wx, wy - 5, wz - 5, 0, 0, 0, 1, 10, 1, 10);
        scene.set(1, quad, 0, wx, wy - 3, wz - 5, 0, 0, 0, 1, 1, 1, 1);
        camera.position(wx, wy, wz);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        Camera sunCamera = new Camera().position(wx, wy - 5, wz - 5).setOrthographic(-20 * sun[0], -20 * sun[1],
            -20 * sun[2], sun[0], sun[1], sun[2], 1, 0, 0, 8, 8, 40, 1024, 1024);
        float[] map = sunCamera.viewProjection().clone();
        try (Lights lights = new Lights(device, 4, 4)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            ShadowMapShadows mapped = new ShadowMapShadows(geometry, lights, 0, 0, sunCamera, 1024, sunView, 1.5e-3f);
            double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
            expectShadow(forward(geometry), "far from the origin", shadow, lights, mapped);
            // The camera moves half a unit right and a quarter back: the shadow stays where it is in the world, so
            // it is that much left and ahead of the camera now.
            camera.position(wx + 0.5, wy, wz + 0.25);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            expectShadow(forward(geometry), "the camera moved", new double[] {shadow[0] - 0.5, shadow[1] - 0.25},
                lights, mapped);
            assertThat(sunCamera.viewProjection()).as("the sun's camera").containsExactly(map);
        }
        camera.position(0, 0, 0);
    }

    @Test
    void instancesCastingNoShadowCastNone() {
        // The floating quad casts no shadow: the shadow map's view leaves it out, the floor under it is lit.
        scene.set(1, quad, 0, 1, false, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        try (Lights lights = new Lights(device, 4, 4)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            SceneView main = newGraph();
            main.port().add(new ClusteredForwardShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT))
                .add(new ClusteredLights(lights, main))
                .add(new ShadowMapShadows(geometry, lights, 0, 0, lightCamera(sun[0], sun[1], sun[2]), 1024, sunView,
                    1.5e-3f))
                .output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
            frame(colorTarget, 4);
            byte[] color = frame(colorTarget, 4);
            double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
            int lit = floor(color, this, 3, -2);
            assertThat(floor(color, this, shadow[0], shadow[1])).as("where its shadow would fall")
                .isGreaterThan((int) (lit * 0.9));
            assertThat(sunView.visibleMeshlets()).as("the sun's view drew the floor alone").isEqualTo(1);
        }
    }

    @Test
    void maskedTexelsCastNoShadow() {
        // The floating quad, alpha-masked: with its alpha above the cutoff it casts its shadow (drawn in the shadow
        // map's masked bin); with all of it masked it casts none, in the shadow map as for the camera.
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
        try (Lights lights = new Lights(device, 4, 4); Materials materials = new Materials(device, 4)) {
            int solid = materials.add(Material.DEFAULT.alphaMask(0.5f));
            int clear = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS.withBaseColor(new Color(0.8, 0.8, 0.8, 0)))
                .alphaMask(0.5f));
            scene.setMaterials(materials);
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            scene.set(1, quad, solid, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
            expectShadow(forward(geometry), "masked, alpha above the cutoff", shadow, lights,
                new ShadowMapShadows(geometry, lights, 0, 0, lightCamera(sun[0], sun[1], sun[2]), 1024, sunView,
                    1.5e-3f));
            assertThat(sunView.visibleMeshlets(2)).as("the masked quad, in the sun's masked bin").isEqualTo(1);
            scene.set(1, quad, clear, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
            frame(colorTarget, 4);
            byte[] color = frame(colorTarget, 4);
            int lit = floor(color, this, 3, -2);
            assertThat(floor(color, this, shadow[0], shadow[1])).as("where its shadow would fall")
                .isGreaterThan((int) (lit * 0.9));
            scene.setMaterials(null);
        }
    }

    @Test
    void cascadesShadowNearAndFar() {
        // A second quad, farther out on a longer floor: its shadow falls in a farther cascade.
        scene.set(0, quad, 0, -5, -10, 0, 0, 0, 1, 10, 1, 15);
        scene.set(2, quad, 0, -3, -14, 0, 0, 0, 1, 1, 1, 1);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        try (Lights lights = new Lights(device, 1, 4)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            CascadedShadowMaps cascades = new CascadedShadowMaps(geometry, lights, 0, 0, camera, 3, 1024,
                ViewOptions.of(256, 1 << 12), 40);
            cascades.update();
            assertThat(cascades.view(1).options().castersOnly()).as("cascades draw casters only").isTrue();
            assertThat(cascades.split(0)).isLessThan(cascades.split(1)).isLessThan(cascades.split(2));
            assertThat(cascades.split(2)).isCloseTo(40, org.assertj.core.data.Offset.offset(1e-3f));
            double[] near = shadowOf(sun[0], sun[1], sun[2]);
            double[] run = shadowOf(sun[0], sun[1], sun[2]);
            double[] far = {run[0], run[1] - 9}; // the far quad is 9 farther along z
            for (Function<SceneView, Technique> shading : List.of(forward(geometry), visibility(geometry))) {
                byte[] color = expectShadow(shading, "cascades, near", near, lights, cascades);
                int lit = floor(color, this, 3, -2);
                assertThat(floor(color, this, far[0], far[1])).as("cascades, far").isLessThan(lit / 2);
                assertThat(graph.passes()).contains("meshlet depth, view " + cascades.view(0).index(),
                    "meshlet depth, view " + cascades.view(2).index()).containsOnlyOnce("meshlet culling");
            }
            // The cameras of the cascades grow with their slices.
            float[] first = cascades.camera(0).viewProjection();
            float[] last = cascades.camera(2).viewProjection();
            assertThat(Math.abs(first[0]) + Math.abs(first[4]) + Math.abs(first[8]))
                .isGreaterThan(Math.abs(last[0]) + Math.abs(last[4]) + Math.abs(last[8]));
            camera.position(10.3, 0, 4.7);
            cascades.lambda(0).bias(2).casters(50).update();
            assertThat(cascades.split(0)).isCloseTo((float) (0.1 + 39.9 / 3),
                org.assertj.core.data.Offset.offset(1e-3f));
            assertThatIllegalArgumentException().isThrownBy(() -> new CascadedShadowMaps(geometry, lights, 0, 0, camera,
                0, 1024, ViewOptions.of(256, 1 << 12), 40));
        }
    }

    @Test
    void classicGeometryCastsAndCatchesShadows() {
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        try (Lights lights = new Lights(device, 1, 4);
             ClassicLodGeometry classic = new ClassicLodGeometry(device, renderer, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, 16)) {
            classic.add(quad, quadMesh);
            SceneGeometry geometry = new SceneGeometry(classic);
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
            List<Function<SceneView, Technique>> shadings = device.primitiveIds()
                ? List.of(forward(geometry), visibility(geometry)) : List.of(forward(geometry));
            CascadedShadowMaps cascades = new CascadedShadowMaps(geometry, lights, 0, 0, camera, 3, 1024,
                ViewOptions.shadow(256, 1 << 12), 40);
            for (Function<SceneView, Technique> shading : shadings) {
                expectShadow(shading, "classic, one map", shadow, lights, new ShadowMapShadows(geometry, lights, 0, 0, lightCamera(sun[0], sun[1], sun[2]), 1024, sunView,
                        1.5e-3f));
                assertThat(graph.passes()).contains("classic depth, view 1");
                cascades.update();
                expectShadow(shading, "classic, cascades", shadow, lights, cascades);
                assertThat(graph.passes()).contains("classic depth, view 2", "classic depth, view 3",
                    "classic depth, view 4");
            }
        }
    }

    @Test
    void lightsOfOneFrameMixTechniques() {
        assumeThat(device.rayTracing()).as("ray tracing").isTrue();
        rayTracing = new RayTracing(device, pool, 16);
        rayTracing.add(quad, quadMesh, quadMesh.levels().size() - 1);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        double l = Math.sqrt(0.5 * 0.5 + 0.8 * 0.8 + 0.2 * 0.2);
        double[] other = {0.5 / l, -0.8 / l, -0.2 / l};
        try (Lights lights = new Lights(device, 4, 4)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 1, 1, 1)
                .directional(1, (float) other[0], (float) other[1], (float) other[2], 1, 1, 1).setCount(2);
            shadowRatio = 0.8; // a shadow of one light is still lit by the other
            // The first light by a shadow map, the second by inline rays: two shadows, each lit by the other light.
            byte[] color = expectShadow(visibility(geometry), "two lights, first", shadowOf(sun[0], sun[1], sun[2]),
                lights, new ShadowMapShadows(geometry, lights, 0, 0, lightCamera(sun[0], sun[1], sun[2]), 1024, sunView, 1.5e-3f),
                new InlineRayShadows(rayTracing, view, lights, 1, 1));
            double[] second = shadowOf(other[0], other[1], other[2]);
            int lit = floor(color, this, 3, -2);
            assertThat(floor(color, this, second[0], second[1])).as("the second light's shadow")
                .isLessThan(lit * 3 / 4);
            int first = floor(color, this, shadowOf(sun[0], sun[1], sun[2])[0], shadowOf(sun[0], sun[1], sun[2])[1]);
            assertThat(first).as("in one shadow, lit by the other light").isGreaterThan(lit / 4);
        }
    }
}
