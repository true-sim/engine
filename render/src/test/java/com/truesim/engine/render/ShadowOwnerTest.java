package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.shadows.ShadowSettings;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.ShadowMethod;
import com.truesim.scene.core.ShadowSpec;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * The {@link Shadows} owner, validated (synchronization too): a 2 x 2 quad floats 2 units over a 20 x 20 floor; lights
 * cast its shadow by the method their spec says, in the place geometry says, under both shading techniques; methods,
 * sizes and lights change frame to frame without a graph build; tiles nothing changed in are not drawn again.
 */
class ShadowOwnerTest {

    private static final int SIZE = 128;

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private RenderView view;
    private RayTracing rayTracing;
    private FrameGraph graph;
    private GpuImage colorTarget;
    private GpuBuffer pixels;
    private final Camera camera = new Camera();
    private MeshData quadMesh;
    private int quad;
    private Lights lights;
    private Shadows shadows;

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
        scene.set(0, quad, 0, -5, -5, 0, 0, 0, 1, 10, 1, 10);
        scene.set(1, quad, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
        camera.set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        graph = new FrameGraph(device, 2);
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        colorTarget = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        pixels = GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        lights = new Lights(device, 4, 16);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            graph.close();
            if (shadows != null) {
                shadows.close();
            }
            lights.close();
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

    private byte[] frame() {
        device.submitAndWait(device.graphics(), commands -> {
            graph.record(commands);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(SIZE, SIZE, 1);
                vkCmdCopyImageToBuffer(commands, colorTarget.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        byte[] texels = new byte[SIZE * SIZE * 4];
        pixels.memory().asSlice(0, texels.length).asByteBuffer().get(texels);
        return texels;
    }

    private int floor(byte[] color, double x, double z) {
        float[] m = camera.viewProjection();
        double y = -5;
        double cx = m[0] * x + m[4] * y + m[8] * z + m[12];
        double cy = m[1] * x + m[5] * y + m[9] * z + m[13];
        double w = m[3] * x + m[7] * y + m[11] * z + m[15];
        int px = (int) ((cx / w * 0.5 + 0.5) * SIZE);
        int py = (int) ((cy / w * 0.5 + 0.5) * SIZE);
        return color[4 * (px + py * SIZE)] & 0xff;
    }

    /** A new graph: shading of one kind, light clusters and the owner's part, in a view "main". */
    private void newGraph(Function<SceneView, Technique> shading) {
        if (shadows != null) {
            shadows.close();
        }
        graph.close();
        graph = new FrameGraph(device, 2);
        graph.settings().set(ShadowSettings.ATLAS_SIZE, 2048);
        SceneView main = new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        shadows = new Shadows(geometry, lights).rayTracing(rayTracing);
        main.port().add(shading.apply(main)).add(new ClusteredLights(lights, main)).add(shadows.view(main))
            .output(Keys.SCENE_COLOR, new ExternalImage("target", SIZE, SIZE,
                com.truesim.engine.vulkan.Format.R8G8B8A8_UNORM, VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                    | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(colorTarget.handle(), colorTarget.view()));
    }

    private static Function<SceneView, Technique> forward() {
        return main -> new ClusteredForwardShading(SceneGeometry.meshlets(main.view().renderer()), main,
            VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
    }

    private static Function<SceneView, Technique> visibility() {
        return main -> new VisibilityBufferShading(SceneGeometry.meshlets(main.view().renderer()), main,
            VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
    }

    /** Frames until the shadows settle, then checks the floor is dark at the two points and lit at the third. */
    private byte[] expectShadow(String what, double[] dark, double[] lit) {
        byte[] color = null;
        for (int i = 0; i < 3; i++) {
            color = frame();
        }
        int light = floor(color, lit[0], lit[1]);
        assertThat(light).as("%s: lit floor", what).isGreaterThan(40);
        for (int i = 0; i < dark.length; i += 2) {
            assertThat(floor(color, dark[i], dark[i + 1])).as("%s: the floor in shadow at %s, %s against %d lit", what,
                dark[i], dark[i + 1], light).isLessThan(light / 2);
        }
        return color;
    }

    /** Where the quad's shadow falls under the sun ({@link Keys#SUN_X} ...): its middle and a point near it. */
    private static double[] sunShadow() {
        double run = 2 / Keys.SUN_Y; // the sun shines along -SUN
        double x = -Keys.SUN_X * run;
        double z = -5 - Keys.SUN_Z * run;
        return new double[] {x, z, x + 0.4, z - 0.4};
    }

    private void sun(ShadowSpec spec) {
        lights.directional(0, (float) -Keys.SUN_X, (float) -Keys.SUN_Y, (float) -Keys.SUN_Z, 3, 3, 3);
        lights.shadow(0, spec).setCount(1);
    }

    @Test
    void directionalLightsCastByMapsAndCascadesUnderBothShadings() {
        for (Function<SceneView, Technique> shading : java.util.List.of(forward(), visibility())) {
            newGraph(shading);
            sun(ShadowSpec.maps(1024).distance(30));
            expectShadow("one map", sunShadow(), new double[] {3, -2});
            sun(ShadowSpec.cascades(3, 512, 50));
            expectShadow("three cascades", sunShadow(), new double[] {3, -2});
            sun(ShadowSpec.cascades(ShadowSpec.AUTO, ShadowSpec.AUTO, 50));
            expectShadow("cascades by the view", sunShadow(), new double[] {3, -2});
            assertThat(shadows.stats().mapped()).isEqualTo(1);
        }
    }

    @Test
    void spotAndPointLightsCastByMaps() {
        double[] dark = {1.4, -5.6, 1.8, -6.0};
        double[] lit = {3, -2};
        for (Function<SceneView, Technique> shading : java.util.List.of(forward(), visibility())) {
            newGraph(shading);
            lights.spot(0, 0, -1, -5, 20, 60, 60, 60, 0, -1, 0, 0.9, 1.05);
            lights.shadow(0, ShadowSpec.maps(512)).setCount(1);
            expectShadow("a spot light's map", dark, lit);
            lights.point(0, 0, -1, -5, 20, 60, 60, 60);
            expectShadow("a point light's cube", dark, lit);
            lights.shadow(0, ShadowSpec.maps(ShadowSpec.AUTO));
            expectShadow("a point light's cube, sized by the view", dark, lit);
            assertThat(shadows.stats().tiles()).isEqualTo(6);
        }
    }

    @Test
    void specsChangeWithoutBuildingTheGraphAndStillTilesAreKept() {
        newGraph(visibility());
        lights.point(0, 0, -1, -5, 20, 60, 60, 60);
        sun(ShadowSpec.cascades(2, 512, 50));
        lights.point(1, 0, -1, -5, 20, 60, 60, 60);
        lights.shadow(1, ShadowSpec.maps(256)).setCount(2);
        frame();
        frame();
        int builds = graph.builds();
        ShadowSpec[] specs = {ShadowSpec.maps(512), ShadowSpec.maps(ShadowSpec.AUTO), ShadowSpec.NONE,
            ShadowSpec.maps(128), ShadowSpec.maps(64)};
        for (ShadowSpec spec : specs) {
            lights.shadow(1, spec);
            frame();
        }
        sun(ShadowSpec.NONE);
        frame();
        sun(ShadowSpec.maps(1024));
        frame();
        assertThat(graph.builds()).as("no build as specs change").isEqualTo(builds);
        // A still scene: nothing drawn again, the frame after the last change
        lights.shadow(1, ShadowSpec.maps(256));
        frame();
        frame();
        lights.setCount(1);
        frame();
        frame();
        assertThat(shadows.stats().drawn()).as("still").isZero();
        assertThat(shadows.stats().tiles()).isEqualTo(1);
        sun(ShadowSpec.NONE);
        lights.shadow(0, ShadowSpec.maps(256)).point(0, 0, -1, -5, 20, 60, 60, 60);
        frame();
        frame();
        assertThat(shadows.stats().drawn()).as("still").isZero();
        // The quad moves: the faces that see it are drawn again, and the shadow follows
        scene.set(1, quad, 0.5f, -3, -5, 0, 0, 0, 1, 1, 1, 1);
        frame();
        assertThat(shadows.stats().drawn()).as("moved").isBetween(1, 6);
        expectShadow("the quad moved", new double[] {2.2, -5.6}, new double[] {3, -2});
        assertThat(graph.builds()).as("no build as tiles are drawn again").isEqualTo(builds);
        scene.set(1, quad, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
    }

    @Test
    void lightsOverTheAtlasShrinkByImportanceThenCastNone() {
        newGraph(forward());
        graph.settings().set(ShadowSettings.ATLAS_SIZE, 512);
        lights.point(0, 0, -1, -5, 20, 60, 60, 60);
        lights.shadow(0, ShadowSpec.maps(256).importance(2));
        lights.point(1, 0, -1, -5, 20, 60, 60, 60);
        lights.shadow(1, ShadowSpec.maps(256)).setCount(2);
        frame();
        frame();
        // 12 faces of 256 in 512 x 512: the second light's faces shrink first
        assertThat(shadows.stats().shrunk()).isGreaterThanOrEqualTo(1);
        assertThat(shadows.stats().texels()).isLessThanOrEqualTo(512L * 512);
        assertThat(shadows.stats().tiles()).isEqualTo(12);
        lights.shadow(1, ShadowSpec.maps(ShadowSpec.AUTO).auto(256, 512));
        frame();
        assertThat(shadows.stats().dropped()).as("too big for what is left").isEqualTo(1);
        assertThat(lights.shadow(1).method()).isEqualTo(ShadowMethod.MAPS);
    }

    /** Ray tracing of the quads, where the device traces rays. */
    private boolean rays(MeshData... more) {
        if (!device.rayTracing()) {
            return false;
        }
        rayTracing = new RayTracing(device, pool, 16);
        rayTracing.add(quad, quadMesh, quadMesh.levels().size() - 1);
        return true;
    }

    @Test
    void lightsCastByHardAndSoftRaysUnderBothShadings() {
        assumeThat(rays()).as("no ray tracing").isTrue();
        double[] dark = {1.4, -5.6, 1.8, -6.0};
        for (Function<SceneView, Technique> shading : java.util.List.of(forward(), visibility())) {
            newGraph(shading);
            sun(ShadowSpec.rayTraced());
            expectShadow("the sun, hard rays", sunShadow(), new double[] {3, -2});
            sun(ShadowSpec.softRayTraced(0.01));
            expectShadow("the sun, soft rays", sunShadow(), new double[] {3, -2});
            lights.directional(0, 0, 0, 0, 0, 0, 0);
            lights.spot(1, 0, -1, -5, 20, 60, 60, 60, 0, -1, 0, 0.9, 1.05);
            lights.shadow(1, ShadowSpec.rayTraced()).setCount(2);
            expectShadow("a spot light, hard rays", dark, new double[] {3, -2});
            lights.point(1, 0, -1, -5, 20, 60, 60, 60);
            lights.shadow(1, ShadowSpec.softRayTraced(0.05));
            expectShadow("a point light, soft rays", dark, new double[] {3, -2});
            assertThat(shadows.stats().traced()).isEqualTo(1);
        }
        ShadowSpec[] specs = {ShadowSpec.rayTraced(), ShadowSpec.maps(256), ShadowSpec.softRayTraced(0.1),
            ShadowSpec.NONE, ShadowSpec.softRayTraced(0.05)};
        int builds = graph.builds();
        for (ShadowSpec spec : specs) {
            lights.shadow(1, spec);
            frame();
        }
        assertThat(graph.builds()).as("the atlas, first used: one build").isEqualTo(builds + 1);
        builds = graph.builds();
        for (int round = 0; round < 3; round++) {
            for (ShadowSpec spec : specs) {
                lights.shadow(1, spec);
                frame();
            }
        }
        assertThat(graph.builds()).as("methods switched without a build").isEqualTo(builds);
    }

    /**
     * The quad of a type whose keep() cuts it in a checkerboard of two by two cells: the kept cells cast, the holes
     * let light through, by maps and by hard and soft rays alike (rays meeting it wait for its keep() test).
     */
    @Test
    void holesOfKeepLetLightThroughByEveryMethod() {
        MeshData mapped = uvQuad();
        int mappedQuad = pool.add(mapped);
        boolean traced = rays();
        if (traced) {
            rayTracing.add(mappedQuad, mapped, mapped.levels().size() - 1);
        }
        try (Materials materials = new Materials(device, 4)) {
            materials.register(com.truesim.engine.render.materials.Dissolve.TYPE);
            int cut = materials.add(new Material(com.truesim.engine.render.materials.DissolveParameters.DEFAULTS
                .withCell(0.5f)));
            scene.setMaterials(materials);
            scene.set(1, mappedQuad, cut, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
            double[] shadow = sunShadow();
            double[] kept = {shadow[0] - 0.5, shadow[1] - 0.5, shadow[0] + 0.5, shadow[1] + 0.5};
            double[] holes = {shadow[0] + 0.5, shadow[1] - 0.5, shadow[0] - 0.5, shadow[1] + 0.5};
            java.util.List<ShadowSpec> specs = new java.util.ArrayList<>(java.util.List.of(ShadowSpec.maps(1024)
                .distance(30)));
            if (traced) {
                specs.add(ShadowSpec.rayTraced());
                specs.add(ShadowSpec.softRayTraced(0.002));
            }
            for (Function<SceneView, Technique> shading : java.util.List.of(forward(), visibility())) {
                newGraph(shading);
                for (ShadowSpec spec : specs) {
                    sun(spec);
                    byte[] color = expectShadow(spec.method() + ": the kept cells", kept, new double[] {3, -2});
                    int lit = floor(color, 3, -2);
                    for (int i = 0; i < holes.length; i += 2) {
                        assertThat(floor(color, holes[i], holes[i + 1])).as("%s: under a hole", spec.method())
                            .isGreaterThan(lit * 9 / 10);
                    }
                }
            }
            scene.setMaterials(null);
        }
        scene.set(1, quad, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
    }

    /**
     * The penumbra soft ray-traced shadows draw against the path-traced reference's, from a sun of 0.15 radians (its
     * spec's softness, the reference sampling its disc): the floor's visibility across the shadow's edge, from umbra
     * (0) to lit (1), measured in each image against its own umbra and lit floor, so ambient light differing between
     * them cancels. Hard rays, against the same reference, show what a wrong penumbra measures.
     */
    @Test
    void softShadowsMatchThePathTracedReference() {
        assumeThat(rays()).as("no ray tracing").isTrue();
        sun(ShadowSpec.softRayTraced(0.15));
        // the reference: direct light, its shadow rays to points of the sun's disc
        if (shadows != null) {
            shadows.close();
            shadows = null;
        }
        graph.close();
        graph = new FrameGraph(device, 2);
        graph.settings().set(ReferencePathTracer.SAMPLES, 512).set(ReferencePathTracer.BOUNCES, 1);
        SceneView main = new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera);
        main.port().add(new ReferencePathTracer(SceneGeometry.meshlets(renderer), rayTracing, main,
            VK_FORMAT_R8G8B8A8_UNORM)).add(new ClusteredLights(lights, main))
            .output(Keys.SCENE_COLOR, new ExternalImage("target", SIZE, SIZE,
                com.truesim.engine.vulkan.Format.R8G8B8A8_UNORM, VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                    | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(colorTarget.handle(), colorTarget.view()));
        byte[] reference = null;
        for (int i = 0; i < 520; i++) {
            reference = frame();
        }
        newGraph(visibility());
        byte[] soft = null;
        for (int i = 0; i < 40; i++) {
            soft = frame();
        }
        sun(ShadowSpec.rayTraced());
        byte[] hard = null;
        for (int i = 0; i < 3; i++) {
            hard = frame();
        }
        double[] want = profile(reference);
        double[] got = profile(soft);
        double[] sharp = profile(hard);
        double error = 0;
        double hardError = 0;
        StringBuilder table = new StringBuilder();
        for (int i = 0; i < want.length; i++) {
            error += Math.abs(got[i] - want[i]) / want.length;
            hardError += Math.abs(sharp[i] - want[i]) / want.length;
            table.append(String.format("%.2f/%.2f/%.2f ", want[i], got[i], sharp[i]));
        }
        System.out.printf(java.util.Locale.ROOT, "penumbra against the reference: soft %.4f, hard %.4f; %s%n", error,
            hardError, table);
        assertThat(error).as("soft rays against the reference (reference/soft/hard): %s", table).isLessThan(0.06);
        assertThat(hardError).as("hard rays against it: the penumbra matters").isGreaterThan(2 * error);
    }

    /** Visibility across the quad's shadow's +x edge, 0 in its umbra to 1 lit, in 25 steps of 0.05 units. */
    private double[] profile(byte[] color) {
        double[] s = sunShadow();
        double umbra = floor(color, s[0], s[1]);
        double lit = floor(color, s[0] + 2.5, s[1]);
        double[] v = new double[25];
        for (int i = 0; i < v.length; i++) {
            v[i] = (floor(color, s[0] + 0.4 + 0.05 * i, s[1]) - umbra) / Math.max(lit - umbra, 1);
        }
        return v;
    }

    /** The test quad (2 x 2, in the xz plane) with texture coordinates: u along x, v along z, 0 to 1. */
    private static MeshData uvQuad() {
        float[] positions = {-1, 0, -1, 1, 0, -1, -1, 0, 1, 1, 0, 1};
        float[] uvs = {0, 0, 1, 0, 0, 1, 1, 1};
        return new com.truesim.scene.meshbuild.MeshBuilder(
            VertexFormat.of(com.truesim.scene.assets.mesh.VertexAttribute.UV0), 4)
            .positions(positions).attribute(com.truesim.scene.assets.mesh.VertexAttribute.UV0, uvs)
            .submesh(new int[] {0, 2, 1, 1, 2, 3}).buildFlat();
    }
}
