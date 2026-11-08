package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.common.math.Vec3;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.materials.TestWindParameters;
import com.truesim.engine.render.materials.Wave;
import com.truesim.engine.render.materials.WaveParameters;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.ShadowSpec;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Per-material displacement ({@link Material#GEOMETRY_DISPLACE}), validated: the test type {@code wave} (test/wave.slang)
 * moves its vertices by a constant offset plus a swing driven by the scene's wind (the test geometry hook
 * {@code test.wind}, in {@link GpuScene#geometryHooks}). Both modes: in the vertex stages of every pass
 * ({@link Displacement#RASTER}) and once a frame into a copy ({@link Displacement#COMPUTE}). A 2 x 2 quad floats 2 units
 * over a 20 x 20 floor (as {@link ShadowsTest}), displaced 1.5 along x: its shadow by maps, the surface the camera sees,
 * culling with grown bounds; motion vectors of a swinging quad; ray-traced shadows and path-traced hits of a copy.
 */
class DisplacementTest {

    private static final int SIZE = 128;
    private static final float HALF = (float) Math.sqrt(0.5);
    private static final float OFFSET = 1.5f;

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private GpuScene scene;
    private Materials materials;
    private MeshletRenderer renderer;
    private RenderView view;
    private RayTracing rayTracing;
    private FrameGraph graph;
    private GpuImage target;
    private GpuBuffer pixels;
    private final Camera camera = new Camera();
    private final List<Shadows> owners = new ArrayList<>();
    private MeshData quadMesh;
    private int quad;
    private int floorMaterial;
    private int waveMaterial;

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
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 12, 64, 8));
        quadMesh = MeshletRendererTest.grid(1, 2, false, VertexFormat.POSITIONS_ONLY);
        quad = pool.add(quadMesh);
        scene = new GpuScene(device, pool, 16);
        materials = new Materials(device, 16);
        materials.register(Wave.TYPE);
        floorMaterial = 0;
        waveMaterial = materials.add(new Material(WaveParameters.DEFAULTS
            .withOffset(new Vec3(OFFSET, 0, 0))).displacement(OFFSET + 0.1f));
        scene.setMaterials(materials);
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
        view = renderer.createView(ViewOptions.of(256, 1 << 12).withOcclusion(true));
        graph = new FrameGraph(device, 2);
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
            if (target != null) {
                pixels.close();
                target.close();
            }
            renderer.close();
            scene.close();
            materials.close();
            pool.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A target of {@code format} ({@code bytes} a texel) and its readback, made once. */
    private ExternalImage target(int format, Format graphFormat, int bytes) {
        if (target == null) {
            target = new GpuImage(device, SIZE, SIZE, format, VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
            pixels = GpuBuffer.hostVisible(device, (long) SIZE * SIZE * bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        }
        return new ExternalImage("target", SIZE, SIZE, graphFormat, VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
            .set(target.handle(), target.view());
    }

    /** Records {@code frames} frames, reading the target back after the last. */
    private void frames(int frames) {
        for (int f = 0; f < frames; f++) {
            boolean last = f == frames - 1;
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
    }

    private byte[] bytes() {
        byte[] texels = new byte[SIZE * SIZE * 4];
        pixels.memory().asSlice(0, texels.length).asByteBuffer().get(texels);
        return texels;
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

    private static Function<SceneView, Technique> forward(SceneGeometry geometry) {
        return main -> new ClusteredForwardShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
    }

    private static Function<SceneView, Technique> visibility(SceneGeometry geometry) {
        return main -> new VisibilityBufferShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
    }

    /** A new graph, its view "main" of {@link #view} through the camera. */
    private SceneView newGraph() {
        graph.close();
        graph = new FrameGraph(device, 2);
        return new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera);
    }

    /** The floor and quad of {@link ShadowsTest}: the camera at the origin looking down at the floor around z = -5. */
    private void floorAndQuad(int quadMeshId) {
        scene.set(0, quad, floorMaterial, 0, -5, -5, 0, 0, 0, 1, 10, 1, 10);
        scene.set(1, quadMeshId, waveMaterial, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
        camera.set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
    }

    /** Where the quad's middle, displaced by {@code dx}, casts its shadow on the floor along the sun. */
    private static double[] shadowOf(double dx) {
        double run = 2 / Keys.SUN_Y;
        return new double[] {dx - Keys.SUN_X * run, -5 - Keys.SUN_Z * run};
    }

    /**
     * Draws the floor and quad in a new graph shaded by {@code shading} under the sun shadowed as {@code spec}, and
     * returns the color: the third frame (the late phase drew everything the first, tiles the next).
     */
    private byte[] shadowed(Function<SceneView, Technique> shading, ShadowSpec spec) {
        try (Lights lights = new Lights(device, 4, 4)) {
            lights.directional(0, (float) -Keys.SUN_X, (float) -Keys.SUN_Y, (float) -Keys.SUN_Z, 3, 3, 3).setCount(1);
            lights.shadow(0, spec);
            SceneView main = newGraph();
            Shadows owner = new Shadows(SceneGeometry.meshlets(renderer), lights).rayTracing(rayTracing);
            owners.add(owner);
            main.port().add(shading.apply(main)).add(new ClusteredLights(lights, main)).add(owner.view(main))
                .output(Keys.SCENE_COLOR, target(VK_FORMAT_R8G8B8A8_UNORM, Format.R8G8B8A8_UNORM, 4));
            frames(3);
            device.waitIdle();
            return bytes();
        }
    }

    private int floor(byte[] color, double x, double z) {
        return color[4 * pixel(x, -5, z)] & 0xff;
    }

    /** Checks the floor is in shadow around {@code shadow} and lit where the undisplaced quad's shadow would be. */
    private void expectShadowAt(byte[] color, double[] shadow, double[] notAt, String what) {
        int lit = floor(color, 3, -2);
        assertThat(floor(color, shadow[0], shadow[1])).as("%s: in shadow, against %d lit", what, lit)
            .isLessThan(lit / 2);
        assertThat(floor(color, shadow[0] + 0.4, shadow[1] - 0.4)).as("%s: still in shadow", what)
            .isLessThan(lit / 2);
        assertThat(floor(color, notAt[0], notAt[1])).as("%s: lit where it was", what)
            .isGreaterThan((int) (lit * 0.9));
    }

    // ---------------------------------------------------------------- raster

    @Test
    void aDisplacedQuadCastsItsDisplacedShadowByMapsAndIsSeenWhereDisplacedOnBothShadings() {
        floorAndQuad(quad);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] shadow = shadowOf(OFFSET);
        double[] undisplaced = shadowOf(-0.7); // under the quad as it is: past its left edge displaced
        List<Function<SceneView, Technique>> shadings = device.primitiveIds()
            ? List.of(forward(geometry), visibility(geometry)) : List.of(forward(geometry));
        for (int run = 0; run < 2 * shadings.size(); run++) {
            Function<SceneView, Technique> shading = shadings.get(run % shadings.size());
            // mesh shaders (where the GPU has them), then the indexed path
            renderer.setPath(run < shadings.size() ? MeshletRenderer.Path.AUTO : MeshletRenderer.Path.INDEXED);
            byte[] color = shadowed(shading, ShadowSpec.cascades(3, 1024, 30));
            expectShadowAt(color, shadow, undisplaced, "cascades");
            // The quad glows green (its type's emission) where displaced, not where it is
            int seen = pixel(OFFSET, -3, -5);
            int was = pixel(-0.7, -3, -5);
            assertThat(color[4 * seen + 1] & 0xff).as("the displaced quad, green").isGreaterThan(200);
            assertThat(color[4 * seen] & 0xff).as("the displaced quad, greener than red")
                .isLessThan((color[4 * seen + 1] & 0xff) - 60);
            assertThat(color[4 * was + 1] & 0xff).as("past where it was: the floor")
                .isLessThan(color[4 * seen + 1] & 0xff);
        }
    }

    @Test
    void theIndexedPathsIdsOfATypedDrawCountFromItsPart() {
        // The visibility buffer on the indexed path: a type with geometry hooks (here dissolve's keep()) draws its IDs
        // from a part of the split lists, whose draw starts past the shared types' part; primitive IDs count from the
        // part's first index, so the first triangle must too. Counted from the bin's, the IDs named triangles past
        // their meshlets' and the shading read out of bounds: the device was lost (on main before this, as found here).
        assumeThat(device.primitiveIds()).as("primitive IDs in fragment shaders").isTrue();
        materials.register(com.truesim.engine.render.materials.Dissolve.TYPE);
        int dissolving = materials.add(new Material(com.truesim.engine.render.materials.DissolveParameters.DEFAULTS));
        floorAndQuad(quad);
        scene.set(1, quad, dissolving, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
        renderer.setPath(MeshletRenderer.Path.INDEXED);
        SceneView main = newGraph();
        main.port().add(visibility(SceneGeometry.meshlets(renderer)).apply(main))
            .output(Keys.SCENE_COLOR, target(VK_FORMAT_R8G8B8A8_UNORM, Format.R8G8B8A8_UNORM, 4));
        frames(3);
        byte[] color = bytes();
        int yellow = 0;
        for (int p = 0; p < SIZE * SIZE; p++) {
            yellow += (color[4 * p] & 0xff) > 200 && (color[4 * p + 1] & 0xff) > 200 && (color[4 * p + 2] & 0xff) < 80
                ? 1 : 0;
        }
        assertThat(yellow).as("the dissolving quad's cells, shaded from their IDs").isGreaterThan(50);
    }

    @Test
    void culledByBoundsGrownByTheDeclaredDisplacement() {
        // The quad stands 12 to the left, out of view; displaced 12 right it is under the camera. Culling tests its
        // instance's sphere grown by the table's largest displacement and its meshlets' by their material's.
        int far = materials.add(new Material(WaveParameters.DEFAULTS.withOffset(new Vec3(12, 0, 0)))
            .displacement(12.5f));
        floorAndQuad(quad);
        scene.set(1, quad, far, -12, -3, -5, 0, 0, 0, 1, 1, 1, 1);
        SceneView main = newGraph();
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        main.port().add(forward(geometry).apply(main))
            .output(Keys.SCENE_COLOR, target(VK_FORMAT_R8G8B8A8_UNORM, Format.R8G8B8A8_UNORM, 4));
        frames(3);
        byte[] color = bytes();
        int seen = pixel(0, -3, -5);
        assertThat(color[4 * seen + 1] & 0xff).as("the quad, drawn where displaced").isGreaterThan(200);
        assertThat(view.visibleMeshlets()).as("its meshlet listed, with the floor's").isGreaterThanOrEqualTo(2);
        assertThat(scene.displacedInStages(1)).isTrue();
        assertThat(scene.displacedInStages(0)).as("the floor's material does not displace").isFalse();
    }

    @Test
    void aDisplacingTypesMaterialsDeclareHowFar() {
        assertThatIllegalArgumentException().isThrownBy(() -> materials.add(new Material(WaveParameters.DEFAULTS)))
            .withMessageContaining("Material.displacement");
        assertThatIllegalArgumentException().isThrownBy(() -> new Material(WaveParameters.DEFAULTS).displacement(-1));
        assertThat(materials.largestDisplacement()).isEqualTo(OFFSET + 0.1f);
        assertThat(materials.displaces(waveMaterial)).isTrue();
        assertThat(materials.displaces(floorMaterial)).isFalse();
        assertThat(Wave.TYPE.displaces()).isTrue();
        assertThat(materials.displacingTypes()).containsExactly(Wave.TYPE);
        // Drawn by an instance, a material keeps displacing or not when replaced in place
        floorAndQuad(quad);
        materials.reference(waveMaterial, 1);
        assertThatIllegalArgumentException().isThrownBy(() -> materials.set(waveMaterial, Material.DEFAULT));
        materials.displacementMode(Wave.TYPE, Displacement.COMPUTE);
        assertThat(materials.displacementMode(Wave.TYPE)).isEqualTo(Displacement.COMPUTE);
        materials.displacementMode(Wave.TYPE, null);
        assertThat(materials.displacementMode(Wave.TYPE)).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> materials.displacementMode(
            com.truesim.engine.render.materials.MetallicRoughnessMaterial.TYPE, Displacement.RASTER));
    }

    // ---------------------------------------------------------------- motion vectors

    /**
     * Motion vectors of the quad swinging by the wind: facing the camera 4 ahead (scaled 0.5: a unit square), its
     * vertices moved by swing (2, 0, 0) times the sine of the wind's time, in the camera's 90 degree view over the
     * target: 32 pixels per unit at distance 1 for 64 pixels, here {@code SIZE / 2} per unit.
     */
    private void swingingQuadMotion(Function<SceneView, Technique> shading, RenderView ids, int mesh) {
        int swinging = materials.add(new Material(WaveParameters.DEFAULTS.withSwing(new Vec3(2, 0, 0)))
            .displacement(2.1f));
        scene.set(0, quad, floorMaterial, 0, -1, -4, 0, 0, 0, 1, 10, 10, 10);
        scene.set(1, mesh, swinging, 0, 0, -4, HALF, 0, 0, HALF, 0.5f, 0.5f, 0.5f);
        camera.set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        ids.set(camera, 1, MeshletRenderer.MODE_LIT);
        GeometryHooks hooks = scene.geometryHooks();
        TestWindParameters wind = TestWindParameters.DEFAULTS.withFrequency(1);
        hooks.set("test.wind", wind.withTime(0));
        SceneView main = newGraph();
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        main.port().add(shading.apply(main)).add(new MotionVectors(geometry, ids, main, VK_FORMAT_D32_SFLOAT))
            .output(Keys.MOTION, target(VK_FORMAT_R16G16_SFLOAT, Format.R16G16_SFLOAT, 4));
        frames(3);
        // sin(pi / 6) = 0.5: the quad 1 to the right in its mesh, 0.5 in the world, at depth 4
        hooks.set("test.wind", wind.withTime((float) (Math.PI / 6)));
        frames(1);
        double perUnit = SIZE / 2.0 / 4; // pixels per world unit at depth 4
        int x = SIZE / 2 + (int) (0.5 * perUnit) + 2; // inside the quad where it is now (0.5 to the right)
        float[] motion = motion();
        expect(motion, x, SIZE / 2, -0.5 * perUnit, "swung right: it was left of here");
        expect(motion, SIZE / 2, SIZE - 4, 0, "the floor, still");
        // The same time again: no motion, though displaced
        frames(1);
        expect(motion(), x, SIZE / 2, 0, "the wind's time stood");
        // Back to 0: it was right of here
        hooks.set("test.wind", wind.withTime(0));
        frames(1);
        expect(motion(), SIZE / 2 + 2, SIZE / 2, 0.5 * perUnit, "swung back");
        // Off: displaced by its offset alone (0), as the frame before had it on at time 0 (sin 0 = 0)
        hooks.turn("test.wind", false);
        frames(1);
        expect(motion(), SIZE / 2 + 2, SIZE / 2, 0, "the wind off");
    }

    private float[] motion() {
        float[] motion = new float[2 * SIZE * SIZE];
        for (int i = 0; i < motion.length; i++) {
            motion[i] = Float.float16ToFloat(pixels.memory().getAtIndex(ValueLayout.JAVA_SHORT, i));
        }
        return motion;
    }

    private static void expect(float[] motion, int x, int y, double mx, String what) {
        int p = y * SIZE + x;
        assertThat((double) motion[2 * p]).as("%s, x", what).isCloseTo(mx, within(0.1));
        assertThat((double) motion[2 * p + 1]).as("%s, y", what).isCloseTo(0, within(0.1));
    }

    @Test
    void motionVectorsOfAnAnimatedDisplacementOnTheVisibilityBuffer() {
        assumeThat(device.primitiveIds()).as("primitive IDs in fragment shaders").isTrue();
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        swingingQuadMotion(visibility(geometry), view, quad);
        assertThat(graph.passes()).contains("motion vectors", "displaced motion vectors");
    }

    @Test
    void motionVectorsOfAnAnimatedDisplacementOnForwardShading() {
        assumeThat(device.primitiveIds()).as("primitive IDs in fragment shaders").isTrue();
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        RenderView ids = renderer.createView(ViewOptions.of(256, 1 << 12));
        swingingQuadMotion(forward(geometry), ids, quad);
        ids.close();
    }

    @Test
    void motionVectorsOfAnAnimatedDisplacementIntoACopy() {
        assumeThat(device.primitiveIds()).as("primitive IDs in fragment shaders").isTrue();
        int copy = pool.addDeformed(quad);
        pool.deformation().displace(copy);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        swingingQuadMotion(visibility(geometry), view, copy);
        assertThat(scene.displacedInStages(1)).as("drawn as the copy is").isFalse();
        assertThat(pool.deformation().displacedCount()).isOne();
    }

    // ---------------------------------------------------------------- compute: ray tracing sees it

    @Test
    void aCopyDisplacedOnceAFrameCastsItsDisplacedShadowByRaysAndMaps() {
        assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
        rayTracing = new RayTracing(device, pool, 16);
        assumeThat(rayTracing.quantizedVertices()).as("copies' levels read quantized positions").isTrue();
        rayTracing.add(quad, quadMesh, quadMesh.levels().size() - 1);
        int copy = pool.addDeformed(quad);
        pool.deformation().displace(copy);
        assertThat(rayTracing.addDeformed(copy, quadMesh, 0)).isTrue();
        floorAndQuad(copy);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        double[] shadow = shadowOf(OFFSET);
        double[] undisplaced = shadowOf(-0.7);
        Function<SceneView, Technique> shading = device.primitiveIds() ? visibility(geometry) : forward(geometry);
        expectShadowAt(shadowed(shading, ShadowSpec.rayTraced()), shadow, undisplaced, "rays, copy");
        assertThat(rayTracing.bottomLevelRefits()).as("refitted as displaced each frame").isPositive();
        expectShadowAt(shadowed(shading, ShadowSpec.cascades(3, 1024, 30)), shadow, undisplaced, "maps, copy");
        // In the vertex stages instead, rays meet the quad as it is: COMPUTE is what ray tracing sees
        scene.set(1, quad, waveMaterial, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
        byte[] raster = shadowed(shading, ShadowSpec.rayTraced());
        expectShadowAt(raster, shadowOf(0), shadowOf(OFFSET + 0.7), "rays, the quad displaced where drawn");
    }

    @Test
    void pathTracedHitsSeeACopyDisplacedOnceAFrame() {
        assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
        rayTracing = new RayTracing(device, pool, 16);
        assumeThat(rayTracing.quantizedVertices()).as("copies' levels read quantized positions").isTrue();
        int copy = pool.addDeformed(quad);
        pool.deformation().displace(copy);
        rayTracing.addDeformed(copy, quadMesh, 0);
        // The glowing quad facing the camera 4 ahead, displaced 1.5 right (scaled 0.5: 0.75 in the world)
        scene.set(0, copy, waveMaterial, 0, 0, -4, HALF, 0, 0, HALF, 0.5f, 0.5f, 0.5f);
        camera.set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        SceneView main = newGraph();
        try (Lights lights = new Lights(device, 4, 4)) {
            main.port().add(new ReferencePathTracer(SceneGeometry.meshlets(renderer), rayTracing, main,
                    VK_FORMAT_R32G32B32A32_SFLOAT)).add(new ClusteredLights(lights, main))
                .output(Keys.SCENE_COLOR, target(VK_FORMAT_R32G32B32A32_SFLOAT, Format.R32G32B32A32_SFLOAT, 16));
            frames(4);
            device.waitIdle();
        }
        float[] image = pixels.memory().asSlice(0, (long) SIZE * SIZE * 16).toArray(ValueLayout.JAVA_FLOAT);
        double perUnit = SIZE / 2.0 / 4;
        int seen = SIZE / 2 * SIZE + SIZE / 2 + (int) (0.75 * perUnit);
        int was = SIZE / 2 * SIZE + SIZE / 2 - (int) (0.4 * perUnit);
        assertThat((double) image[4 * seen + 1]).as("the copy's hit where displaced: its glow").isGreaterThan(0.5);
        assertThat((double) image[4 * was + 1]).as("where it was: the sky through it")
            .isLessThan(image[4 * seen + 1] * 0.5);
    }
}
