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
import com.truesim.engine.render.shadows.ShadowSettings;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.scene.core.ShadowSpec;
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
 * Shadows through the frame graph by the {@link Shadows} owner, validated (synchronization too): a 2 x 2 quad floats 2
 * units over a 20 x 20 floor; views with tables and cascades of their own or shared, the top level built only when
 * something changed, posed copies, scenes far from the origin, instances casting none, masks, cascades near and far,
 * lights of one frame mixing methods. The methods themselves: {@link ShadowOwnerTest}.
 */
class ShadowsTest {

    private static final int SIZE = 128;

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private RenderView view;     // the camera's
    private RayTracing rayTracing;
    private FrameGraph graph;
    private GpuImage colorTarget;
    private GpuBuffer pixels;
    private final Camera camera = new Camera();
    private final List<Shadows> owners = new java.util.ArrayList<>();
    private MeshData quadMesh;
    private int quad;

    @BeforeEach
    void start(org.junit.jupiter.api.TestInfo test) {
        // Tests named so check shader memory and ray queries too (GPU-assisted validation), wherever they run
        boolean gpuAssisted = test.getDisplayName().contains("UnderGpuAssistedValidation") && rayTracingGpu();
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(!gpuAssisted).gpuAssistedValidation(gpuAssisted).surfaces(false).build();
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

    /**
     * Whether the best GPU is a hardware one tracing rays: GPU-assisted validation only there (lavapipe's test JVM died
     * as it ended, on CI and locally, after a test had made a device with it).
     */
    private static boolean rayTracingGpu() {
        try (VulkanInstance probe = VulkanInstance.builder().applicationName("probe").surfaces(false).build()) {
            if (probe.gpus().isEmpty() || probe.bestGpu().type() == com.truesim.engine.vulkan.GpuType.CPU) {
                return false;
            }
            try (VulkanDevice gpu = VulkanDevice.createHeadless(probe.bestGpu())) {
                return gpu.rayTracing();
            }
        } catch (VulkanException | UnsatisfiedLinkError e) {
            return false;
        }
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            graph.close();
            for (Shadows owner : owners) {
                owner.close();
            }
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

    /** An owner of {@code lights}' shadows drawing {@code geometry}, tracing where the test traces, closed after it. */
    private Shadows owner(SceneGeometry geometry, Lights lights) {
        Shadows owner = new Shadows(geometry, lights).rayTracing(rayTracing);
        owners.add(owner);
        return owner;
    }

    /**
     * Draws a frame in a new graph with {@code shading}, {@code lights} binned and shadowed by a new owner's part in
     * the view, and checks the floor is shadowed at {@code shadow}.
     */
    private byte[] expectShadow(Function<SceneView, Technique> shading, String what, double[] shadow, Lights lights) {
        SceneView main = newGraph();
        main.port().add(shading.apply(main)).add(new ClusteredLights(lights, main))
            .add(owner(SceneGeometry.meshlets(renderer), lights).view(main));
        main.port().output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
        frame(colorTarget, 4);
        frame(colorTarget, 4);
        byte[] color = frame(colorTarget, 4); // the late phase drew everything the first frame; tiles the next
        int lit = floor(color, this, 3, -2);
        int dark = floor(color, this, shadow[0], shadow[1]);
        assertThat(dark).as("%s: the floor in shadow, against %d in the light", what, lit)
            .isLessThan((int) (lit * shadowRatio));
        assertThat(floor(color, this, shadow[0] + 0.4, shadow[1] - 0.4)).as("%s: still in shadow", what)
            .isLessThan((int) (lit * shadowRatio));
        return color;
    }

    @Test
    void eachViewCastsThroughCascadesOfItsOwn() {
        // Two views, each with a part of the owner and a table of its own: the second through a camera 40 units off,
        // whose cascades (to 30 units) are fitted to it and do not reach this floor. Sharing one table, the last
        // written would serve both. A view removed: its table goes with it.
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
        try (Lights lights = new Lights(device, 4, 4); GpuImage awayTarget = new GpuImage(device, SIZE, SIZE,
            VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
            VK_IMAGE_ASPECT_COLOR_BIT)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            lights.shadow(0, ShadowSpec.cascades(3, 1024, 30));
            SceneView here = newGraph();
            Camera awayCamera = new Camera().position(0, 0, 60);
            awayCamera.set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
            SceneView away = geometry.createView(graph.addView("away").size(SIZE, SIZE), awayCamera,
                ViewOptions.of(256, 1 << 12).withOcclusion(true)).set(1, MeshletRenderer.MODE_LIT);
            Shadows owner = owner(geometry, lights);
            here.port().add(forward(geometry).apply(here)).add(new ClusteredLights(lights, here)).add(owner.view(here))
                .output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
            away.port().add(forward(geometry).apply(away)).add(new ClusteredLights(lights, away)).add(owner.view(away))
                .output(Keys.SCENE_COLOR, external(awayTarget, VK_FORMAT_R8G8B8A8_UNORM));
            assertThat(lights.shadowTable(here.port())).isNotSameAs(lights.shadowTable(away.port()));
            for (int i = 0; i < 3; i++) {
                frame(colorTarget, 4);
            }
            byte[] color = frame(colorTarget, 4);
            int lit = floor(color, this, 3, -2);
            assertThat(floor(color, this, shadow[0], shadow[1])).as("its own cascades: in shadow")
                .isLessThan((int) (lit * shadowRatio));
            assertThat(owner.stats().tiles()).as("three cascades a view").isEqualTo(6);
            int tables = lights.shadowTableCount();
            graph.removeView(away.port());
            frame(colorTarget, 4);
            frame(colorTarget, 4);
            assertThat(lights.shadowTableCount()).as("freed once no frame reads it").isEqualTo(tables - 1);
            assertThat(owner.stats().tiles()).as("its cascades' tiles freed").isEqualTo(3);
            device.waitIdle();
        }
    }

    @Test
    void viewsShareOneSetOfCascadesDrawnOnce() {
        // Two views, as a headset's eyes, 64 mm apart: the second's part shares the first's cascades, fitted with a
        // margin for it; one set of tiles, both floors shadowed.
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
        try (Lights lights = new Lights(device, 4, 4); GpuImage rightTarget = new GpuImage(device, SIZE, SIZE,
            VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
            VK_IMAGE_ASPECT_COLOR_BIT)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            lights.shadow(0, ShadowSpec.cascades(3, 1024, 40));
            SceneView left = newGraph();
            Camera rightCamera = new Camera().position(camera.position(0) + 0.064, camera.position(1),
                camera.position(2));
            rightCamera.set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1); // as the first, 64 mm to the right
            SceneView right = geometry.createView(graph.addView("right").size(SIZE, SIZE), rightCamera,
                ViewOptions.of(256, 1 << 12).withOcclusion(true)).set(1, MeshletRenderer.MODE_LIT);
            Shadows owner = owner(geometry, lights);
            Technique leftPart = owner.view(left);
            assertThatIllegalArgumentException().isThrownBy(() -> owner.view(right, leftPart, -1));
            left.port().add(forward(geometry).apply(left)).add(new ClusteredLights(lights, left)).add(leftPart)
                .output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
            Technique rightPart = owner.view(right, leftPart, 0.1);
            assertThatIllegalArgumentException().as("not a part with cascades of its own")
                .isThrownBy(() -> owner.view(left, rightPart, 0.1));
            right.port().add(forward(geometry).apply(right)).add(new ClusteredLights(lights, right)).add(rightPart)
                .output(Keys.SCENE_COLOR, external(rightTarget, VK_FORMAT_R8G8B8A8_UNORM));
            for (int i = 0; i < 3; i++) {
                frame(colorTarget, 4);
            }
            byte[] leftColor = frame(colorTarget, 4);
            byte[] rightColor = frame(rightTarget, 4);
            assertThat(owner.stats().tiles()).as("one set of cascades").isEqualTo(3);
            // 64 mm apart, the floor points fall on the same pixels, near enough.
            assertThat(floor(leftColor, this, shadow[0], shadow[1])).as("the first view's floor in shadow")
                .isLessThan((int) (floor(leftColor, this, 3, -2) * shadowRatio));
            assertThat(floor(rightColor, this, shadow[0], shadow[1])).as("the second view's, from the same maps")
                .isLessThan((int) (floor(rightColor, this, 3, -2) * shadowRatio));
            device.waitIdle();
        }
    }

    @Test
    void viewsShadowOneLightThroughEntriesOfTheirOwn() {
        // The sun's cascades in two views, each fitted to its view, each view's table naming its own entries for
        // the light: both views see the shadow.
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
        try (Lights lights = new Lights(device, 4, 8); GpuImage rightTarget = new GpuImage(device, SIZE, SIZE,
            VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
            VK_IMAGE_ASPECT_COLOR_BIT)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            lights.shadow(0, ShadowSpec.cascades(3, 1024, 40));
            SceneView left = newGraph();
            Camera rightCamera = new Camera().position(camera.position(0) + 0.064, camera.position(1),
                camera.position(2));
            rightCamera.set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
            SceneView right = geometry.createView(graph.addView("right").size(SIZE, SIZE), rightCamera,
                ViewOptions.of(256, 1 << 12).withOcclusion(true)).set(1, MeshletRenderer.MODE_LIT);
            Shadows owner = owner(geometry, lights);
            left.port().add(forward(geometry).apply(left)).add(new ClusteredLights(lights, left)).add(owner.view(left))
                .output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
            right.port().add(forward(geometry).apply(right)).add(new ClusteredLights(lights, right))
                .add(owner.view(right)).output(Keys.SCENE_COLOR, external(rightTarget, VK_FORMAT_R8G8B8A8_UNORM));
            for (int i = 0; i < 3; i++) {
                frame(colorTarget, 4);
            }
            byte[] leftColor = frame(colorTarget, 4);
            byte[] rightColor = frame(rightTarget, 4);
            assertThat(floor(leftColor, this, shadow[0], shadow[1])).as("the first view's floor")
                .isLessThan((int) (floor(leftColor, this, 3, -2) * shadowRatio));
            assertThat(floor(rightColor, this, shadow[0], shadow[1])).as("the second view's")
                .isLessThan((int) (floor(rightColor, this, 3, -2) * shadowRatio));
            device.waitIdle();
        }
    }

    @Test
    void theTopLevelIsBuiltOnlyWhenInstancesTheOriginOrBottomLevelsChange() {
        assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
        rayTracing = new RayTracing(device, pool, 16);
        rayTracing.add(quad, quadMesh, quadMesh.levels().size() - 1);
        try (Lights lights = new Lights(device, 4, 4)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            lights.shadow(0, ShadowSpec.rayTraced());
            expectShadow(visibility(geometry), "the first build", shadow, lights);
            assertThat(rayTracing.topLevelBuilds()).as("nothing changed after the first frame").isEqualTo(1);
            assertThat(rayTracing.topLevelInstances(scene)).as("the floor and the quad").isEqualTo(2);
            frame(colorTarget, 4);
            frame(colorTarget, 4);
            assertThat(rayTracing.topLevelBuilds()).as("a still scene").isEqualTo(1);
            // The floating quad moves 1 right: refitted (the same instances), the shadow with it.
            scene.set(1, quad, 1, -3, -5, 0, 0, 0, 1, 1, 1, 1);
            expectShadow(visibility(geometry), "the quad moved", new double[] {shadow[0] + 1, shadow[1]}, lights);
            assertThat(rayTracing.topLevelBuilds()).isEqualTo(2);
            assertThat(rayTracing.topLevelRefits()).as("moved only: refitted").isEqualTo(1);
            // The origin moves: built anew relative to it, the shadow where it was.
            rayTracing.origin(5, 0, -5);
            expectShadow(visibility(geometry), "the origin moved", new double[] {shadow[0] + 1, shadow[1]}, lights);
            assertThat(rayTracing.topLevelBuilds()).isEqualTo(3);
            assertThat(rayTracing.topLevelRefits()).as("not refitted across a new origin").isEqualTo(1);
            // The quad stops casting shadows: out of the top level (not in it inactive), built anew, never refitted;
            // and back in the same way.
            scene.set(1, quad, 0, 1, false, 1, -3, -5, 0, 0, 0, 1, 1, 1, 1);
            frame(colorTarget, 4);
            assertThat(rayTracing.topLevelInstances(scene)).as("the floor").isEqualTo(1);
            assertThat(rayTracing.topLevelBuilds()).isEqualTo(4);
            scene.set(1, quad, 0, 1, true, 1, -3, -5, 0, 0, 0, 1, 1, 1, 1);
            frame(colorTarget, 4);
            assertThat(rayTracing.topLevelInstances(scene)).as("the quad again").isEqualTo(2);
            assertThat(rayTracing.topLevelBuilds()).isEqualTo(5);
            assertThat(rayTracing.topLevelRefits()).as("a change of who is in it is a build").isEqualTo(1);
        }
        scene.set(1, quad, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
    }

    @Test
    void deformedCopiesCastRayTracedShadowsAsPosed() {
        assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
        rayTracing = new RayTracing(device, pool, 16);
        assumeThat(rayTracing.quantizedVertices()).as("copies' levels read quantized positions").isTrue();
        // The floating quad is a skinned copy: its ray-traced shadow follows its joints, its bottom level refitted.
        MeshData skinned = GraphTechniquesTest.skinnedQuad();
        int source = pool.add(skinned);
        int copy = pool.addDeformed(source);
        Deformation deformation = pool.deformation();
        deformation.add(copy, 2);
        rayTracing.add(quad, quadMesh, quadMesh.levels().size() - 1);
        assertThatIllegalArgumentException().isThrownBy(() -> rayTracing.addDeformed(source, skinned, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> rayTracing.addDeformed(copy,
            MeshletRendererTest.grid(2, 2, false, VertexFormat.POSITIONS_ONLY), 0)); // not its mesh
        assertThat(rayTracing.addDeformed(copy, skinned, 0)).isTrue();
        assertThatIllegalStateException().isThrownBy(() -> rayTracing.addDeformed(copy, skinned, 0));
        assertThatIllegalStateException().isThrownBy(() -> rayTracing.add(copy, skinned, 0));
        assertThat(rayTracing.bottomLevelAdded(copy)).isTrue();
        assertThat(rayTracing.hasBottomLevel(copy)).as("built in a frame").isFalse();
        assertThat(rayTracing.pendingBottomLevels()).isOne();
        assertThatIllegalArgumentException().isThrownBy(() -> rayTracing.rebuildEvery(0));
        scene.set(1, copy, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        try (Lights lights = new Lights(device, 4, 4)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
            lights.shadow(0, ShadowSpec.rayTraced());
            pose(deformation, copy, 0);
            expectShadow(visibility(geometry), "the posed copy", shadow, lights);
            assertThat(rayTracing.hasBottomLevel(copy)).isTrue();
            assertThat(rayTracing.pendingBottomLevels()).isZero();
            assertThat(rayTracing.bottomLevelBytes()).isPositive();
            // Its joints move it 1.5 right: refitted, the shadow with it, none where it was.
            pose(deformation, copy, 1.5);
            byte[] moved = expectShadow(visibility(geometry), "moved by its joints",
                new double[] {shadow[0] + 1.5, shadow[1]}, lights);
            assertThat(floor(moved, this, shadow[0] - 0.4, shadow[1])).as("where it was")
                .isGreaterThan((int) (floor(moved, this, 3, -2) * 0.9));
            assertThat(rayTracing.bottomLevelRefits()).as("refitted").isPositive();
            assertThat(rayTracing.bottomLevelRebuilds()).isZero();
            // Posed as before: nothing refitted, the top level stands.
            long builds = rayTracing.topLevelBuilds();
            frame(colorTarget, 4);
            assertThat(rayTracing.bottomLevelsUpdatedLastFrame()).isZero();
            assertThat(rayTracing.topLevelBuilds()).isEqualTo(builds);
            // A build in place after every refit: the shadow as posed all the same.
            rayTracing.rebuildEvery(1);
            pose(deformation, copy, -1);
            expectShadow(visibility(geometry), "rebuilt", new double[] {shadow[0] - 1, shadow[1]}, lights);
            assertThat(rayTracing.bottomLevelRebuilds()).as("built anew in place").isPositive();
            // The copy leaves the pool: its bottom level with it.
            scene.remove(1);
            pool.remove(copy);
            assertThat(rayTracing.bottomLevelAdded(copy)).isFalse();
            assertThat(rayTracing.hasBottomLevel(copy)).isFalse();
            byte[] gone = frame(colorTarget, 4);
            assertThat(floor(gone, this, shadow[0] - 1, shadow[1])).as("no shadow left")
                .isGreaterThan((int) (floor(gone, this, 3, -2) * 0.9));
            pool.remove(source);
        }
        scene.set(1, quad, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
    }

    /** Poses {@link GraphTechniquesTest#skinnedQuad()}'s copy {@code copy} moved {@code dx} along x by both joints. */
    private static void pose(Deformation deformation, int copy, double dx) {
        for (int joint = 0; joint < 2; joint++) {
            deformation.joint(copy, joint, 1, 0, 0, dx, 0, 1, 0, 0, 0, 0, 1, 0);
        }
        deformation.bounds(copy, -1 + dx, 0, -1, 1 + dx, 0, 1, Math.abs(dx));
    }

    @Test
    void eachSceneHasATopLevelOfItsOwn() {
        assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
        rayTracing = new RayTracing(device, pool, 16);
        rayTracing.add(quad, quadMesh, quadMesh.levels().size() - 1);
        try (GpuScene other = new GpuScene(device, pool, 40);
             MeshletRenderer otherRenderer = new MeshletRenderer(device, other, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT)) {
            other.set(0, quad, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1);
            for (int frame = 0; frame < 3; frame++) {
                device.submitAndWait(device.graphics(), commands -> {
                    rayTracing.buildTopLevel(commands, renderer);
                    rayTracing.buildTopLevel(commands, otherRenderer);
                });
            }
            assertThat(rayTracing.topLevelBuilds()).as("one each: neither rebuilds the other's").isEqualTo(2);
            assertThat(rayTracing.slot(scene)).isNotEqualTo(rayTracing.slot(other));
            assertThat(rayTracing.instanceCapacity(scene)).isEqualTo(16);
            assertThat(rayTracing.instanceCapacity(other)).as("the scene's capacity, more").isEqualTo(40);
            assertThatIllegalStateException().isThrownBy(rayTracing::slot).withMessageContaining("name the scene");
            rayTracing.stopFollowing(other);
            assertThat(rayTracing.slot()).as("one scene left").isEqualTo(rayTracing.slot(scene));
        }
    }

    @Test
    void shadowMapsStayInTheWorldWhileTheCameraMoves() {
        // The scene 7,000 km from the origin, lit by a spot light above the quad: its map sits at the light's world
        // position, not relative to the camera, so it does not change (is not drawn again) as the camera moves, and
        // serves the camera wherever it is.
        double wx = 3_123_456.5;
        double wy = -45_678.25;
        double wz = -7_000_123.75;
        scene.set(0, quad, 0, wx, wy - 5, wz - 5, 0, 0, 0, 1, 10, 1, 10);
        scene.set(1, quad, 0, wx, wy - 3, wz - 5, 0, 0, 0, 1, 1, 1, 1);
        camera.position(wx, wy, wz);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        try (Lights lights = new Lights(device, 4, 4)) {
            lights.spot(0, wx, wy - 1, wz - 5, 20, 60, 60, 60, 0, -1, 0, 0.9, 1.05).setCount(1);
            lights.shadow(0, ShadowSpec.maps(512));
            double[] shadow = {1.4, -5.6, 1.8, -6.0};
            SceneView main = newGraph();
            Shadows owner = owner(geometry, lights);
            main.port().add(forward(geometry).apply(main)).add(new ClusteredLights(lights, main))
                .add(owner.view(main)).output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
            byte[] color = null;
            for (int i = 0; i < 4; i++) {
                color = frame(colorTarget, 4);
            }
            int lit = floor(color, this, 3, -2);
            assertThat(floor(color, this, shadow[0], shadow[1])).as("far from the origin").isLessThan(lit / 2);
            // The camera moves half a unit right and a quarter back: the shadow stays where it is in the world, so
            // it is that much left and ahead of the camera now; the map is not drawn again.
            camera.position(wx + 0.5, wy, wz + 0.25);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            color = frame(colorTarget, 4);
            assertThat(owner.stats().drawn()).as("the light's map kept").isZero();
            color = frame(colorTarget, 4);
            assertThat(floor(color, this, shadow[0] - 0.5, shadow[1] - 0.25)).as("the camera moved")
                .isLessThan(floor(color, this, 2.5, -2.25) / 2);
        }
        camera.position(0, 0, 0);
    }

    @Test
    void instancesCastingNoShadowCastNone() {
        // The floating quad casts no shadow: the tiles' views leave it out, and so do rays; the floor under it is lit.
        scene.set(1, quad, 0, 1, false, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
        if (device.rayTracing()) {
            rayTracing = new RayTracing(device, pool, 16);
            rayTracing.add(quad, quadMesh, quadMesh.levels().size() - 1);
        }
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
        try (Lights lights = new Lights(device, 4, 4)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            List<ShadowSpec> specs = device.rayTracing() ? List.of(ShadowSpec.maps(1024).distance(30),
                ShadowSpec.rayTraced()) : List.of(ShadowSpec.maps(1024).distance(30));
            for (ShadowSpec spec : specs) {
                lights.shadow(0, spec);
                SceneView main = newGraph();
                main.port().add(forward(geometry).apply(main)).add(new ClusteredLights(lights, main))
                    .add(owner(geometry, lights).view(main))
                    .output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
                byte[] color = null;
                for (int i = 0; i < 3; i++) {
                    color = frame(colorTarget, 4);
                }
                int lit = floor(color, this, 3, -2);
                assertThat(floor(color, this, shadow[0], shadow[1])).as("%s: where its shadow would fall",
                    spec.method()).isGreaterThan((int) (lit * 0.9));
            }
        }
        scene.set(1, quad, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
    }

    @Test
    void maskedTexelsCastNoShadow() {
        // The floating quad, alpha-masked: with its alpha above the cutoff it casts its shadow (drawn in the tile's
        // masked bin); with all of it masked it casts none, in the map as for the camera.
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
        try (Lights lights = new Lights(device, 4, 4); Materials materials = new Materials(device, 4)) {
            int solid = materials.add(Material.DEFAULT.alphaMask(0.5f));
            int clear = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(0.8, 0.8, 0.8, 0))).alphaMask(0.5f));
            scene.setMaterials(materials);
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            lights.shadow(0, ShadowSpec.maps(1024).distance(30));
            scene.set(1, quad, solid, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
            expectShadow(forward(geometry), "masked, alpha above the cutoff", shadow, lights);
            scene.set(1, quad, clear, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
            frame(colorTarget, 4);
            byte[] color = frame(colorTarget, 4);
            int lit = floor(color, this, 3, -2);
            assertThat(floor(color, this, shadow[0], shadow[1])).as("where its shadow would fall")
                .isGreaterThan((int) (lit * 0.9));
            device.waitIdle();
            scene.setMaterials(null);
        }
    }

    @Test
    void rayTracedMasksTraceFromTheViewsEdgePixelsUnderGpuAssistedValidation() {
        // The camera looks straight down at a floor filling the view, lit by a hard and a soft traced sun with nothing
        // to cast: every pixel lit alike. Pixels on the view's edges rebuilt their normal from a neighbour clamped onto
        // themselves: a zero cross product, a NaN normal and NaN ray origins (#459), which GPU-assisted validation
        // reports (VUID-RuntimeSpirv-OpRayQueryInitializeKHR-06351) and the stop's validation count fails on.
        assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
        assumeThat(instance.validationEnabled()).as("no validation layer").isTrue();
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        scene.set(0, quad, 0, -5, 0, 0, 0, 0, 1, 100, 1, 100);
        scene.set(1, quad, 0, 50, 0, 0, 0, 0, 1, 1, 1, 1); // behind the camera, above the light's way: no shadow
        camera.set(0, -1, 0, 0, 0, -1, Math.PI / 3, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        rayTracing = new RayTracing(device, pool, 16);
        rayTracing.add(quad, quadMesh, quadMesh.levels().size() - 1);
        try (Lights lights = new Lights(device, 4, 4)) {
            lights.directional(0, 0.3f, -1, 0.2f, 2, 2, 2).directional(1, -0.2f, -1, 0.3f, 1, 1, 1).setCount(2);
            lights.shadow(0, ShadowSpec.rayTraced()).shadow(1, ShadowSpec.softRayTraced(0.05));
            SceneView main = newGraph();
            main.port().add(forward(geometry).apply(main)).add(new ClusteredLights(lights, main))
                .add(owner(geometry, lights).view(main));
            main.port().output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
            frame(colorTarget, 4);
            byte[] color = frame(colorTarget, 4);
            int middle = color[4 * (SIZE / 2 + SIZE / 2 * SIZE)] & 0xff;
            assertThat(middle).as("the floor lit").isGreaterThan(40);
            for (int i = 0; i < SIZE; i++) {
                for (int edge : new int[] {i, i + (SIZE - 1) * SIZE, i * SIZE, SIZE - 1 + i * SIZE}) {
                    assertThat(color[4 * edge] & 0xff).as("edge pixel %d, %d", edge % SIZE, edge / SIZE)
                        .isBetween((int) (middle * 0.8), (int) (middle * 1.25) + 2);
                }
            }
            device.waitIdle();
        }
        scene.set(0, quad, 0, -5, -5, 0, 0, 0, 1, 10, 1, 10);
        scene.set(1, quad, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
        camera.set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
    }

    @Test
    void maskedTexelsCastNoRayTracedShadow() {
        // As maskedTexelsCastNoShadow, by rays: the masked quad's instance comes as a candidate, its texel alpha-tested
        // as rasterizing tests it. Then a quad with texture coordinates whose texture masks out its x < 0 half: only
        // the other half casts.
        assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] sun = {-Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z};
        double[] shadow = shadowOf(sun[0], sun[1], sun[2]);
        MeshData mapped = uvQuad();
        int mappedQuad = pool.add(mapped);
        MeshData second = secondUvQuad();
        int secondQuad = pool.add(second);
        rayTracing = new RayTracing(device, pool, 16);
        rayTracing.add(quad, quadMesh, quadMesh.levels().size() - 1);
        rayTracing.add(mappedQuad, mapped, mapped.levels().size() - 1);
        rayTracing.add(secondQuad, second, second.levels().size() - 1);
        try (Lights lights = new Lights(device, 4, 4); Materials materials = new Materials(device, 4);
             Textures textures = new Textures(device)) {
            java.lang.foreign.MemorySegment half = java.lang.foreign.Arena.ofAuto().allocate(4L * 8 * 8, 4);
            for (int i = 0; i < 64; i++) {
                half.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, i, i % 8 < 4 ? 0x00ffffff : 0xffffffff);
            }
            int solid = materials.add(Material.DEFAULT.alphaMask(0.5f));
            int clear = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(0.8, 0.8, 0.8, 0))).alphaMask(0.5f));
            int texture = textures.color(8, 8, half);
            int halved = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColorTexture(texture)).alphaMask(0.5f));
            // The mask in the second UV set, and moved by half by a transform: the other half kept
            int inSecond = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColorTexture(TextureSampler.secondUv(texture))).alphaMask(0.5f));
            int moved = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColorTexture(TextureSampler.transform(texture, 1))).alphaMask(0.5f)
                .feature(com.truesim.engine.render.materials.TextureTransformParameters.DEFAULTS
                    .withTransform1(new com.truesim.common.math.Vec4(0.5, 0, 1, 1))));
            scene.setMaterials(materials);
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            lights.shadow(0, ShadowSpec.rayTraced());
            scene.set(1, quad, solid, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
            expectShadow(forward(geometry), "masked, alpha above the cutoff, by rays", shadow, lights);
            scene.set(1, quad, clear, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
            frame(colorTarget, 4);
            byte[] color = frame(colorTarget, 4);
            int lit = floor(color, this, 3, -2);
            assertThat(floor(color, this, shadow[0], shadow[1])).as("all masked: no ray stops")
                .isGreaterThan((int) (lit * 0.9));
            scene.set(1, mappedQuad, halved, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
            frame(colorTarget, 4);
            color = frame(colorTarget, 4);
            assertThat(floor(color, this, shadow[0] - 0.5, shadow[1])).as("under the masked half: lit")
                .isGreaterThan((int) (lit * 0.9));
            assertThat(floor(color, this, shadow[0] + 0.5, shadow[1])).as("under the kept half: shadowed")
                .isLessThan((int) (lit * shadowRatio));
            scene.set(1, secondQuad, inSecond, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
            frame(colorTarget, 4);
            color = frame(colorTarget, 4);
            assertThat(floor(color, this, shadow[0] - 0.5, shadow[1])).as("second UV set, masked half: lit")
                .isGreaterThan((int) (lit * 0.9));
            assertThat(floor(color, this, shadow[0] + 0.5, shadow[1])).as("second UV set, kept half: shadowed")
                .isLessThan((int) (lit * shadowRatio));
            scene.set(1, mappedQuad, moved, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
            frame(colorTarget, 4);
            color = frame(colorTarget, 4);
            assertThat(floor(color, this, shadow[0] - 0.5, shadow[1])).as("moved by half: shadowed")
                .isLessThan((int) (lit * shadowRatio));
            assertThat(floor(color, this, shadow[0] + 0.5, shadow[1])).as("moved by half: lit")
                .isGreaterThan((int) (lit * 0.9));
            device.waitIdle();
            scene.setMaterials(null);
        }
        scene.set(1, quad, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
    }

    /**
     * As uvQuad, its u along x in the second UV set; the first, (0.25, 0.5) throughout, in a masked texel of the test's
     * texture.
     */
    private static MeshData secondUvQuad() {
        float[] positions = {-1, 0, -1, 1, 0, -1, -1, 0, 1, 1, 0, 1};
        float[] uv0 = {0.25f, 0.5f, 0.25f, 0.5f, 0.25f, 0.5f, 0.25f, 0.5f};
        float[] uv1 = {0, 0, 1, 0, 0, 1, 1, 1};
        return new com.truesim.scene.meshbuild.MeshBuilder(com.truesim.scene.assets.mesh.VertexFormat.of(
            com.truesim.scene.assets.mesh.VertexAttribute.UV0, com.truesim.scene.assets.mesh.VertexAttribute.UV1), 4)
            .positions(positions).attribute(com.truesim.scene.assets.mesh.VertexAttribute.UV0, uv0)
            .attribute(com.truesim.scene.assets.mesh.VertexAttribute.UV1, uv1)
            .submesh(new int[] {0, 2, 1, 1, 2, 3}).buildFlat();
    }

    /** The test quad (2 x 2, in the xz plane) with texture coordinates: u along x, from 0 at x = -1 to 1 at x = 1. */
    private static MeshData uvQuad() {
        float[] positions = {-1, 0, -1, 1, 0, -1, -1, 0, 1, 1, 0, 1};
        float[] uvs = {0, 0, 1, 0, 0, 1, 1, 1};
        return new com.truesim.scene.meshbuild.MeshBuilder(
            com.truesim.scene.assets.mesh.VertexFormat.of(com.truesim.scene.assets.mesh.VertexAttribute.UV0), 4)
            .positions(positions).attribute(com.truesim.scene.assets.mesh.VertexAttribute.UV0, uvs)
            .submesh(new int[] {0, 2, 1, 1, 2, 3}).buildFlat();
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
            lights.shadow(0, ShadowSpec.cascades(3, 1024, 40));
            double[] near = shadowOf(sun[0], sun[1], sun[2]);
            double[] far = {near[0], near[1] - 9}; // the far quad is 9 farther along z
            for (Function<SceneView, Technique> shading : List.of(forward(geometry), visibility(geometry))) {
                byte[] color = expectShadow(shading, "cascades, near", near, lights);
                int lit = floor(color, this, 3, -2);
                assertThat(floor(color, this, far[0], far[1])).as("cascades, far").isLessThan(lit / 2);
                assertThat(graph.passes()).contains("shadow atlas").containsOnlyOnce("meshlet culling");
            }
            // Coarser casters, uniform splits, more bias: still shadowed near and far
            graph.settings().set(ShadowSettings.CASTER_DETAIL, 3f).set(ShadowSettings.CASCADE_LAMBDA, 0f)
                .set(ShadowSettings.CASCADE_BIAS, 2f);
            frame(colorTarget, 4);
            byte[] color = frame(colorTarget, 4);
            int lit = floor(color, this, 3, -2);
            assertThat(floor(color, this, near[0], near[1])).as("coarser, near").isLessThan(lit / 2);
            assertThat(floor(color, this, far[0], far[1])).as("coarser, far").isLessThan(lit / 2);
            graph.settings().reset(ShadowSettings.CASTER_DETAIL).reset(ShadowSettings.CASCADE_LAMBDA)
                .reset(ShadowSettings.CASCADE_BIAS);
        }
        scene.set(0, quad, 0, -5, -5, 0, 0, 0, 1, 10, 1, 10);
        scene.remove(2);
    }

    @Test
    void castersFarTowardsTheSunShadowEveryCascade() {
        // The showcase's sun, low: four cascades of 4096 texels reaching 600 units (splits about 38, 81, 163). A row of
        // screens facing the camera, each farther than the last and as much larger, past every split, each with a caster
        // between it and the sun whose shadow falls on its middle: first close to it, then 1000 units away towards
        // the sun, as a mountain stands between a valley and a low sun. Both shadow every screen. Each cascade drew only
        // the casters within shadows.cascades.casters (100) beyond its slice: the far ones were clipped by its near
        // plane and culled, and no cascade shadowed (#468). Flattened onto its near plane (depth clamped), they cast.
        double[] depths = {10, 30, 60, 120, 300, 500};
        double sl = Math.sqrt(0.6 * 0.6 + 0.25 * 0.25 + 0.75 * 0.75); // behind the camera, to the right
        double[] sun = {-0.6 / sl, -0.25 / sl, -0.75 / sl};
        float s45 = (float) Math.sqrt(0.5);
        // The casters face the sun: the quad's +y turned onto the direction to it
        double ax = -sun[2];
        double az = sun[0];
        double al = Math.sqrt(ax * ax + az * az);
        double half = Math.acos(-sun[1]) / 2;
        float[] facing = {(float) (ax / al * Math.sin(half)), 0, (float) (az / al * Math.sin(half)),
            (float) Math.cos(half)};
        camera.set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        scene.remove(0);
        scene.remove(1);
        try (Lights lights = new Lights(device, 1, 4)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            lights.shadow(0, ShadowSpec.cascades(4, 4096, 600));
            for (double away : new double[] {0.15, 1000}) {
                for (int i = 0; i < depths.length; i++) {
                    double d = depths[i];
                    double x = d * Math.tan(Math.toRadians(-25 + 10 * i));
                    scene.set(2 * i, quad, 0, x, 0, -d, s45, 0, 0, s45, (float) (0.1 * d), 1, (float) (0.1 * d));
                    double g = away < 1 ? away * d : away; // towards the sun from the screen's middle
                    scene.set(2 * i + 1, quad, 0, x - sun[0] * g, -sun[1] * g, -d - sun[2] * g, facing[0],
                        facing[1], facing[2], facing[3], (float) (0.02 * d), 1, (float) (0.02 * d));
                }
                for (Function<SceneView, Technique> shading : List.of(forward(geometry), visibility(geometry))) {
                    SceneView main = newGraph();
                    main.port().add(shading.apply(main)).add(new ClusteredLights(lights, main))
                        .add(owner(geometry, lights).view(main));
                    main.port().output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
                    frame(colorTarget, 4);
                    frame(colorTarget, 4);
                    byte[] color = frame(colorTarget, 4);
                    for (int i = 0; i < depths.length; i++) {
                        double d = depths[i];
                        double x = d * Math.tan(Math.toRadians(-25 + 10 * i));
                        int lit = red(color, pixel(x + 0.06 * d, -0.06 * d, -d));
                        int middle = red(color, pixel(x, 0, -d));
                        assertThat(middle).as("a caster %s away: the screen at %s shadowed in its middle, against %d lit",
                            away < 1 ? away + " d" : away, d, lit).isLessThan(lit / 2);
                    }
                }
            }
            // The cascades tinted (shadows.cascades.debug), red, green, blue, yellow by depth; a fifth of the light in
            // their shadows
            graph.settings().set(ShadowSettings.CASCADE_DEBUG, true);
            frame(colorTarget, 4);
            byte[] color = frame(colorTarget, 4);
            graph.settings().reset(ShadowSettings.CASCADE_DEBUG);
            int[][] expected = {{0, 1}, {0, 2}, {1, 0}, {2, 0}, {3, 2}, {3, 2}}; // channel above, channel below
            for (int i = 0; i < depths.length; i++) {
                double d = depths[i];
                double x = d * Math.tan(Math.toRadians(-25 + 10 * i));
                for (int p : new int[] {pixel(x + 0.06 * d, -0.06 * d, -d), pixel(x, 0, -d)}) {
                    int[] rgb = {color[4 * p] & 0xff, color[4 * p + 1] & 0xff, color[4 * p + 2] & 0xff};
                    int[] e = expected[i];
                    int above = e[0] == 3 ? Math.min(rgb[0], rgb[1]) : rgb[e[0]];
                    assertThat(above).as("tinted at %s: %s", d, java.util.Arrays.toString(rgb))
                        .isGreaterThan(rgb[e[1]]);
                }
            }
        }
        for (int i = 0; i < 2 * depths.length; i++) {
            scene.remove(i);
        }
        camera.set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        scene.set(0, quad, 0, -5, -5, 0, 0, 0, 1, 10, 1, 10);
        scene.set(1, quad, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
    }

    @Test
    void aForestInTheFarCascadeFitsItsLists() {
        // 15 trees of a cluster hierarchy (bumpy patches 64 units across) a kilometre towards a low sun from a screen 400
        // units away: flattened onto the far cascade (4 of 1024 to 600), they shadow the screen. With the views' lists
        // held to 192 meshlets, the far cascade at the near one's detail (shadows.cascades.farDetail 1: a texel of
        // error) wanted more and was at the limits, casters dropped; at its own (8 texels by default) they fit.
        double sl = Math.sqrt(0.6 * 0.6 + 0.25 * 0.25 + 0.75 * 0.75);
        double[] sun = {-0.6 / sl, -0.25 / sl, -0.75 / sl};
        float s45 = (float) Math.sqrt(0.5);
        int tree = pool.add(MeshletRendererTest.grid(64, 0.25f, true, VertexFormat.POSITIONS_ONLY));
        camera.set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double d = 400;
        scene.set(0, quad, 0, 0, 0, -d, s45, 0, 0, s45, (float) (0.1 * d), 1, (float) (0.1 * d));
        int trees = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 5; j++) {
                double g = 1000 + 70 * j; // within the scene's 16 instances: it grows no table
                scene.set(1 + trees++, tree, 0, (i - 1) * 50 - sun[0] * g, -sun[1] * g, -d - sun[2] * g, 0, 0, 0, 1,
                    4, 4, 4);
            }
        }
        try (Lights lights = new Lights(device, 1, 4)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            lights.shadow(0, ShadowSpec.cascades(4, 1024, 600));
            for (float far : new float[] {ShadowSettings.CASCADE_FAR_DETAIL.defaultValue(), 1}) {
                SceneView main = newGraph();
                graph.settings().set(ShadowSettings.CASCADE_FAR_DETAIL, far).set(ShadowSettings.TILE_DRAWS, 64)
                    .set(MeshletSettings.VIEW_DRAW_LIMIT, 192);
                main.port().add(forward(geometry).apply(main)).add(new ClusteredLights(lights, main))
                    .add(owner(geometry, lights).view(main));
                main.port().output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
                int before = renderer.viewsAtLimit();
                for (int f = 0; f < 12; f++) {
                    frame(colorTarget, 4); // the lists grown as far as they may
                }
                byte[] color = frame(colorTarget, 4);
                int atLimit = renderer.viewsAtLimit() - before;
                if (far > 1) {
                    assertThat(atLimit).as("views at the limits, the far cascade at its own detail").isZero();
                    int lit = red(color, pixel(0.06 * d, -0.06 * d, -d));
                    assertThat(red(color, pixel(0, 0, -d))).as("the screen in the forest's shadow, against %d lit", lit)
                        .isLessThan(lit / 2);
                } else {
                    assertThat(atLimit).as("views at the limits, the far cascade at the near one's detail")
                        .isPositive();
                }
            }
        }
        for (int i = 0; i <= trees; i++) {
            scene.remove(i);
        }
        camera.set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        scene.set(0, quad, 0, -5, -5, 0, 0, 0, 1, 10, 1, 10);
        scene.set(1, quad, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
    }

    @Test
    void theFarFieldShadowsPastTheLastCascade() {
        // Screens at 300 (the last cascade), 1200 and 3000 units (past cascades reaching 600), each shadowed by a caster
        // towards a low sun. Past the last cascade the far field's rays shadow them; without it
        // (shadows.cascades.farField NONE) they are lit, the last cascade's blend fading into the light.
        assumeThat(device.rayTracing()).as("ray tracing").isTrue();
        rayTracing = new RayTracing(device, pool, 16);
        rayTracing.add(quad, quadMesh, quadMesh.levels().size() - 1);
        double sl = Math.sqrt(0.6 * 0.6 + 0.25 * 0.25 + 0.75 * 0.75);
        double[] sun = {-0.6 / sl, -0.25 / sl, -0.75 / sl};
        float s45 = (float) Math.sqrt(0.5);
        double ax = -sun[2];
        double az = sun[0];
        double al = Math.sqrt(ax * ax + az * az);
        double half = Math.acos(-sun[1]) / 2;
        float[] facing = {(float) (ax / al * Math.sin(half)), 0, (float) (az / al * Math.sin(half)),
            (float) Math.cos(half)};
        double[] depths = {300, 1200, 3000};
        camera.set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        for (int i = 0; i < depths.length; i++) {
            double d = depths[i];
            double x = d * Math.tan(Math.toRadians(-15 + 15 * i));
            scene.set(2 * i, quad, 0, x, 0, -d, s45, 0, 0, s45, (float) (0.1 * d), 1, (float) (0.1 * d));
            double g = 0.15 * d;
            scene.set(2 * i + 1, quad, 0, x - sun[0] * g, -sun[1] * g, -d - sun[2] * g, facing[0], facing[1],
                facing[2], facing[3], (float) (0.02 * d), 1, (float) (0.02 * d));
        }
        try (Lights lights = new Lights(device, 1, 8)) {
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            lights.shadow(0, ShadowSpec.cascades(4, 1024, 600));
            for (ShadowSettings.FarField field : ShadowSettings.FarField.values()) {
                for (Function<SceneView, Technique> shading : List.of(forward(geometry), visibility(geometry))) {
                    SceneView main = newGraph();
                    graph.settings().set(ShadowSettings.CASCADE_FAR_FIELD, field);
                    main.port().add(shading.apply(main)).add(new ClusteredLights(lights, main))
                        .add(owner(geometry, lights).view(main));
                    main.port().output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
                    frame(colorTarget, 4);
                    frame(colorTarget, 4);
                    frame(colorTarget, 4);
                    byte[] color = frame(colorTarget, 4);
                    for (int i = 0; i < depths.length; i++) {
                        double d = depths[i];
                        double x = d * Math.tan(Math.toRadians(-15 + 15 * i));
                        int lit = red(color, pixel(x + 0.06 * d, -0.06 * d, -d));
                        int middle = red(color, pixel(x, 0, -d));
                        if (i == 0 || field == ShadowSettings.FarField.RAYS) {
                            assertThat(middle).as("%s: the screen at %s shadowed, against %d lit", field, d, lit)
                                .isLessThan(lit / 2);
                        } else {
                            assertThat(middle).as("%s: the screen at %s lit, as %d", field, d, lit)
                                .isGreaterThan(lit * 9 / 10);
                        }
                    }
                }
            }
        }
        for (int i = 0; i < 2 * depths.length; i++) {
            scene.remove(i);
        }
        camera.set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        scene.set(0, quad, 0, -5, -5, 0, 0, 0, 1, 10, 1, 10);
        scene.set(1, quad, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
    }

    @Test
    void maskedCastersBeforeTheRangeCastOnlyWhenFlattenedToo() {
        // A screen 60 units away, a caster 1000 units towards a low sun from it: opaque, it is flattened onto the
        // cascade and shadows the screen; masked (foliage's bin), it is culled there by default
        // (shadows.cascades.pancakeMasked off: forests towards the sun overflowed the far cascade) and flattened too
        // with the setting on.
        double sl = Math.sqrt(0.6 * 0.6 + 0.25 * 0.25 + 0.75 * 0.75);
        double[] sun = {-0.6 / sl, -0.25 / sl, -0.75 / sl};
        float s45 = (float) Math.sqrt(0.5);
        double ax = -sun[2];
        double az = sun[0];
        double al = Math.sqrt(ax * ax + az * az);
        double half = Math.acos(-sun[1]) / 2;
        float[] facing = {(float) (ax / al * Math.sin(half)), 0, (float) (az / al * Math.sin(half)),
            (float) Math.cos(half)};
        double d = 60;
        double g = 1000;
        camera.set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        try (Lights lights = new Lights(device, 1, 8); Materials materials = new Materials(device, 4)) {
            int opaque = materials.add(Material.DEFAULT);
            int masked = materials.add(Material.DEFAULT.alphaMask(0.5f));
            scene.setMaterials(materials);
            scene.set(0, quad, opaque, 0, 0, -d, s45, 0, 0, s45, (float) (0.1 * d), 1, (float) (0.1 * d));
            lights.directional(0, (float) sun[0], (float) sun[1], (float) sun[2], 3, 3, 3).setCount(1);
            lights.shadow(0, ShadowSpec.cascades(4, 1024, 600));
            for (int run = 0; run < 3; run++) {
                boolean maskedCaster = run > 0;
                boolean flattened = run == 2;
                scene.set(1, quad, maskedCaster ? masked : opaque, -sun[0] * g, -sun[1] * g, -d - sun[2] * g,
                    facing[0], facing[1], facing[2], facing[3], (float) (0.02 * d), 1, (float) (0.02 * d));
                SceneView main = newGraph();
                graph.settings().set(ShadowSettings.CASCADE_PANCAKE_MASKED, flattened);
                main.port().add(forward(geometry).apply(main)).add(new ClusteredLights(lights, main))
                    .add(owner(geometry, lights).view(main));
                main.port().output(Keys.SCENE_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM));
                frame(colorTarget, 4);
                frame(colorTarget, 4);
                byte[] color = frame(colorTarget, 4);
                int lit = red(color, pixel(0.06 * d, -0.06 * d, -d));
                int middle = red(color, pixel(0, 0, -d));
                String what = !maskedCaster ? "opaque" : flattened ? "masked, flattened too" : "masked";
                if (maskedCaster && !flattened) {
                    assertThat(middle).as("%s: the screen lit, as %d", what, lit).isGreaterThan(lit * 9 / 10);
                } else {
                    assertThat(middle).as("%s: the screen shadowed, against %d lit", what, lit).isLessThan(lit / 2);
                }
            }
            device.waitIdle();
            scene.setMaterials(null);
        }
        camera.set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        scene.set(0, quad, 0, -5, -5, 0, 0, 0, 1, 10, 1, 10);
        scene.set(1, quad, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
    }

    /** The red channel, averaged over 3 x 3 pixels about {@code pixel}: a shadow's coverage, not one texel's. */
    private static int red(byte[] color, int pixel) {
        int sum = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                sum += color[4 * (pixel + dx + dy * SIZE)] & 0xff;
            }
        }
        return sum / 9;
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
            // The first light by a map, the second by rays: two shadows, each lit by the other light.
            lights.shadow(0, ShadowSpec.maps(1024).distance(30)).shadow(1, ShadowSpec.rayTraced());
            byte[] color = expectShadow(visibility(geometry), "two lights, first", shadowOf(sun[0], sun[1], sun[2]),
                lights);
            double[] second = shadowOf(other[0], other[1], other[2]);
            int lit = floor(color, this, 3, -2);
            assertThat(floor(color, this, second[0], second[1])).as("the second light's shadow")
                .isLessThan(lit * 3 / 4);
            int first = floor(color, this, shadowOf(sun[0], sun[1], sun[2])[0], shadowOf(sun[0], sun[1], sun[2])[1]);
            assertThat(first).as("in one shadow, lit by the other light").isGreaterThan(lit / 4);
        }
    }
}
