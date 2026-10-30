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

    /** Draws a frame with {@code shading} and the techniques, and checks the floor is shadowed at {@code shadow}. */
    private byte[] expectShadow(com.truesim.engine.graph.Technique shading, String what, double[] shadow,
        com.truesim.engine.graph.Technique... techniques) {
        graph.close();
        graph = new FrameGraph(device, 2).add(shading);
        for (com.truesim.engine.graph.Technique technique : techniques) {
            graph.add(technique);
        }
        graph.output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
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
            ClusteredLights clustered = new ClusteredLights(lights, camera, SIZE, SIZE);
            double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
            ShadowMapShadows mapped = new ShadowMapShadows(geometry, lights, 0, 0, lightCamera(sun[0], sun[1], sun[2]),
                1024, sunView, 1.5e-3f);
            ClusteredForwardShading forward = new ClusteredForwardShading(geometry, view, SIZE, SIZE,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
            VisibilityBufferShading visibility = new VisibilityBufferShading(geometry, view, SIZE, SIZE,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
            expectShadow(forward, "forward, shadow map", shadow, clustered, mapped);
            assertThat(graph.passes()).contains("meshlet depth, view 1").containsOnlyOnce("meshlet culling");
            assertThat(sunView.visibleMeshlets()).isEqualTo(2); // the sun's view drew both quads
            expectShadow(visibility, "visibility buffer, shadow map", shadow, clustered, mapped);
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
            byte[] masked = expectShadow(forward, "forward, ray-traced mask", shadow, clustered,
                new RayTracedShadows(rayTracing, lights, 0, 0, depthOnly, camera));
            assertThat(graph.passes()).contains("ray-traced shadows of light 0", "top level");
            expectShadow(visibility, "visibility buffer, ray-traced mask", shadow, clustered,
                new RayTracedShadows(rayTracing, lights, 0, 0, ids, camera));
            assertThat(graph.passes()).containsOnlyOnce("meshlet ids early");
            byte[] inline = expectShadow(visibility, "visibility buffer, inline rays", shadow, clustered,
                new InlineRayShadows(rayTracing, view, lights, 0, 0).offset(2e-3f));
            assertThat(graph.passes()).doesNotContain("ray-traced shadows of light 0");
            int differ = 0;
            for (int i = 0; i < inline.length; i += 4) {
                differ += Math.abs((inline[i] & 0xff) - (masked[i] & 0xff)) > 32 ? 1 : 0;
            }
            assertThat(differ).as("inline rays against the mask").isLessThan(SIZE * SIZE / 50);
            // Forward shading cannot trace inline: the light shines unshadowed.
            graph.close();
            graph = new FrameGraph(device, 2).add(forward).add(clustered)
                .add(new InlineRayShadows(rayTracing, view, lights, 0, 0))
                .output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
            frame(colorTarget, 4);
            byte[] color = frame(colorTarget, 4);
            assertThat(floor(color, this, shadow[0], shadow[1])).isGreaterThan(floor(color, this, 3, -2) / 2);
        }
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
            ClusteredLights clustered = new ClusteredLights(lights, camera, SIZE, SIZE);
            ShadowMapShadows mapped = new ShadowMapShadows(geometry, lights, 0, 0, sunCamera, 1024, sunView, 1.5e-3f);
            ClusteredForwardShading forward = new ClusteredForwardShading(geometry, view, SIZE, SIZE,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
            double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
            expectShadow(forward, "far from the origin", shadow, clustered, mapped);
            // The camera moves half a unit right and a quarter back: the shadow stays where it is in the world, so
            // it is that much left and ahead of the camera now.
            camera.position(wx + 0.5, wy, wz + 0.25);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            expectShadow(forward, "the camera moved", new double[] {shadow[0] - 0.5, shadow[1] - 0.25}, clustered,
                mapped);
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
            graph.close();
            graph = new FrameGraph(device, 2)
                .add(new ClusteredForwardShading(geometry, view, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT))
                .add(new ClusteredLights(lights, camera, SIZE, SIZE))
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
            expectShadow(new ClusteredForwardShading(geometry, view, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT), "masked, alpha above the cutoff", shadow,
                new ClusteredLights(lights, camera, SIZE, SIZE),
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
            for (com.truesim.engine.graph.Technique shading : new com.truesim.engine.graph.Technique[] {
                new ClusteredForwardShading(geometry, view, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT),
                new VisibilityBufferShading(geometry, view, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)}) {
                byte[] color = expectShadow(shading, "cascades, near", near,
                    new ClusteredLights(lights, camera, SIZE, SIZE), cascades);
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
            com.truesim.engine.graph.Technique[] shadings = device.primitiveIds()
                ? new com.truesim.engine.graph.Technique[] {
                    new ClusteredForwardShading(geometry, view, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT),
                    new VisibilityBufferShading(geometry, view, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)}
                : new com.truesim.engine.graph.Technique[] {
                    new ClusteredForwardShading(geometry, view, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)};
            CascadedShadowMaps cascades = new CascadedShadowMaps(geometry, lights, 0, 0, camera, 3, 1024,
                ViewOptions.shadow(256, 1 << 12), 40);
            for (com.truesim.engine.graph.Technique shading : shadings) {
                expectShadow(shading, "classic, one map", shadow, new ClusteredLights(lights, camera, SIZE, SIZE),
                    new ShadowMapShadows(geometry, lights, 0, 0, lightCamera(sun[0], sun[1], sun[2]), 1024, sunView,
                        1.5e-3f));
                assertThat(graph.passes()).contains("classic depth, view 1");
                cascades.update();
                expectShadow(shading, "classic, cascades", shadow, new ClusteredLights(lights, camera, SIZE, SIZE),
                    cascades);
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
            byte[] color = expectShadow(new VisibilityBufferShading(geometry, view, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT), "two lights, first", shadowOf(sun[0], sun[1], sun[2]),
                new ClusteredLights(lights, camera, SIZE, SIZE),
                new ShadowMapShadows(geometry, lights, 0, 0, lightCamera(sun[0], sun[1], sun[2]), 1024, sunView, 1.5e-3f),
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
