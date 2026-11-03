package com.truesim.engine.render;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.ResolutionSettings;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.materials.UnlitParameters;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;
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
 * Screen-space reflections against analytic mirror images: a camera at the origin looking down -z (a 90 degree field
 * of view), a floor at y = -1 and walls facing it, lit by a uniform environment. A point (x, y, z) shows at pixel
 * column SIZE / 2 (1 + x / -z), row SIZE / 2 (1 - y / -z); its mirror image in the floor is (x, -2 - y, z).
 */
class ScreenSpaceReflectionsTest {

    private static final int SIZE = 128;
    private static final float HALF = (float) Math.sqrt(0.5);
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS;
    private static final EnvironmentMaps.Quality SMALL = new EnvironmentMaps.Quality(64, 32, 4, 16, 64);

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private RenderView view;
    private FrameGraph graph;
    private GpuImage target;
    private ExternalImage output;
    private GpuBuffer pixels;
    private int quad;
    private MeshData quadData;

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
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 14, 256, 8));
        quadData = quad();
        quad = pool.add(quadData);
        scene = new GpuScene(device, pool, 1024);
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
        view = renderer.createView(ViewOptions.of(4096, 1 << 16).withOcclusion(true));
        graph = new FrameGraph(device, 2);
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        output = new ExternalImage("target", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
        pixels = GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            graph.close();
            pixels.close();
            target.close();
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

    /** A 2 x 2 quad in the xy plane facing +z. */
    private static MeshData quad() {
        float[] positions = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0};
        float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), 4).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).submesh(new int[] {0, 1, 2, 0, 2, 3}).buildFlat();
    }

    /** A floor of {@code material} at y = -1, from z = 0 to -16, 16 wide. */
    private void floor(int id, int material) {
        scene.set(id, quad, material, 0, -1, -8, -HALF, 0, 0, HALF, 8, 8, 1);
    }

    /** A wall of {@code material} facing the camera at depth {@code depth}, centered at ({@code x}, {@code y}). */
    private void wall(int id, int material, float x, float y, float depth, float halfWidth, float halfHeight) {
        scene.set(id, quad, material, x, y, -depth, 0, 0, 0, 1, halfWidth, halfHeight, 1);
    }

    private static Material unlit(float r, float g, float b) {
        return new Material(MR.withBaseColor(new Color(r, g, b, 1))).feature(UnlitParameters.DEFAULTS);
    }

    private static Material metal(float roughness) {
        return new Material(MR.withBaseColor(new Color(1, 1, 1, 1)).withRoughness(roughness).withMetallic(1));
    }

    /** The main view, shaded by {@code shading}, lit by and showing {@code environment}, with reflections. */
    private SceneView main(Camera camera, Function<SceneView, Technique> shading, Environment environment) {
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        SceneView main = new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera)
            .set(1, MeshletRenderer.MODE_LIT);
        main.port().add(shading.apply(main)).add(new EnvironmentLighting(environment, main, SMALL))
            .add(new SkyBackground(environment, main, SMALL)).add(new ScreenSpaceReflections(main))
            .output(Keys.SCENE_COLOR, output);
        return main;
    }

    private static Camera ahead() {
        return new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
    }

    /** Three frames (occlusion culling's phases settled), the last one read back. */
    private int[] frames() {
        for (int i = 0; i < 3; i++) {
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
        }
        int[] image = new int[SIZE * SIZE];
        for (int i = 0; i < image.length; i++) {
            image[i] = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i);
        }
        return image;
    }

    /**
     * Writes {@code image} as {@code name}.png into the directory TRUESIM_SSR_IMAGES names, if it is set (looking at
     * what a test saw).
     */
    static void save(String name, int[] image, int size) {
        String directory = System.getenv("TRUESIM_SSR_IMAGES");
        if (directory == null) {
            return;
        }
        java.awt.image.BufferedImage png = new java.awt.image.BufferedImage(size, size,
            java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < image.length; i++) {
            int p = image[i];
            png.setRGB(i % size, i / size, (p & 0xff) << 16 | (p & 0xff00) | (p >> 16 & 0xff));
        }
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(directory));
            javax.imageio.ImageIO.write(png, "png", java.nio.file.Path.of(directory, name + ".png").toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static int red(int pixel) {
        return pixel & 0xff;
    }

    private static int green(int pixel) {
        return (pixel >> 8) & 0xff;
    }

    private static int blue(int pixel) {
        return (pixel >> 16) & 0xff;
    }

    private static int at(int[] image, int column, int row) {
        return image[row * SIZE + column];
    }

    /** The pixel where camera-relative point (x, y, -depth) shows: column, row. */
    private static int[] pixelOf(double x, double y, double depth) {
        return new int[] {(int) Math.floor(SIZE / 2.0 * (1 + x / depth)), (int) Math.floor(SIZE / 2.0 * (1 - y / depth))};
    }

    /** A shading of the scene and the geometry it draws. */
    private record Shading(String name, Function<SceneView, Technique> make) {
    }

    /** Forward on each meshlet path the device draws on, the visibility buffer, and forward with classic LOD. */
    private List<Shading> shadings(ClassicLodGeometry classic) {
        List<Shading> shadings = new ArrayList<>();
        shadings.add(new Shading("forward, indexed", v -> {
            renderer.setPath(MeshletRenderer.Path.INDEXED);
            return MeshletBenchmark.forward(v).clearColor(0, 0, 0);
        }));
        if (device.meshShaders()) {
            shadings.add(new Shading("forward, mesh shaders", v -> {
                renderer.setPath(MeshletRenderer.Path.MESH_SHADERS);
                return MeshletBenchmark.forward(v).clearColor(0, 0, 0);
            }));
        }
        if (device.primitiveIds()) {
            shadings.add(new Shading("visibility buffer", v -> {
                renderer.setPath(MeshletRenderer.Path.AUTO);
                return new VisibilityBufferShading(SceneGeometry.meshlets(renderer), v, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0);
            }));
        }
        shadings.add(new Shading("forward, classic LOD", v -> new ClusteredForwardShading(
            new SceneGeometry(classic.add(quad, quadData)), v, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
            .clearColor(0, 0, 0)));
        return shadings;
    }

    private static final ScreenSpaceReflections.Mode[] TRACED = {ScreenSpaceReflections.Mode.LINEAR,
        ScreenSpaceReflections.Mode.HIZ};

    @Test
    void aMirrorFloorReflectsARedWallWhereItsMirrorImageShows() {
        try (Materials materials = new Materials(device, 8);
             ClassicLodGeometry classic = new ClassicLodGeometry(device, renderer, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, 16)) {
            int mirror = materials.add(metal(0));
            int red = materials.add(unlit(0.9f, 0, 0));
            scene.setMaterials(materials);
            floor(0, mirror);
            wall(1, red, 0, 0, 6, 1, 1); // x and y from -1 to 1 at depth 6, standing on the floor
            scene.removeFrom(2);
            Environment sky = Environment.uniform("blue", 0, 0, 0.4f);
            // The wall's center (0, 0, -6) mirrored: (0, -2, -6); 2.25 to the right, past its edge, the sky's mirror image
            int[] image = pixelOf(0, -2, 6);
            int[] beside = pixelOf(2.25, -2, 6);
            SceneView main = null;
            for (Shading shading : shadings(classic)) {
                if (main == null) {
                    main = main(ahead(), shading.make(), sky);
                } else {
                    main.port().replace(shading.make().apply(main));
                }
                graph.settings().set(ScreenSpaceReflections.MODE, ScreenSpaceReflections.Mode.OFF);
                int[] off = frames();
                assertThat(red(at(off, image[0], image[1]))).as("%s, off: the sky's reflection", shading.name())
                    .isLessThan(30);
                assertThat(blue(at(off, image[0], image[1]))).isGreaterThan(60);
                for (ScreenSpaceReflections.Mode mode : TRACED) {
                    graph.settings().set(ScreenSpaceReflections.MODE, mode);
                    int[] on = frames();
                    String what = shading.name() + ", " + mode;
                    int p = at(on, image[0], image[1]);
                    assertThat(red(p)).as("%s: the red wall's mirror image", what).isGreaterThan(170);
                    assertThat(blue(p)).as("%s: the sky's reflection replaced", what).isLessThan(30);
                    int q = at(on, beside[0], beside[1]);
                    assertThat(red(q)).as("%s: beside it the sky's", what).isLessThan(30);
                    assertThat(blue(q)).isCloseTo(blue(at(off, beside[0], beside[1])), org.assertj.core.api.Assertions
                        .within(3));
                    // Above the floor, nothing changes: the wall itself
                    int[] wallPixel = pixelOf(0, 0, 6);
                    assertThat(at(on, wallPixel[0], wallPixel[1])).isEqualTo(at(off, wallPixel[0], wallPixel[1]));
                }
            }
            renderer.setPath(MeshletRenderer.Path.AUTO);
            scene.setMaterials(null);
        }
    }

    @Test
    void reflectingWhatTheEnvironmentShowsKeepsTheEnergy() {
        try (Materials materials = new Materials(device, 8)) {
            float grey = 0.35f;
            int mirror = materials.add(metal(0));
            int wallMaterial = materials.add(unlit(grey, grey, grey)); // as bright as the environment
            scene.setMaterials(materials);
            floor(0, mirror);
            wall(1, wallMaterial, 0, 5, 8, 12, 6);
            scene.removeFrom(2);
            SceneView main = main(ahead(), v -> MeshletBenchmark.forward(v).clearColor(0, 0, 0),
                Environment.uniform("grey", grey, grey, grey));
            for (float roughness : new float[] {0, 0.3f}) {
                materials.set(mirror, metal(roughness));
                graph.settings().set(ScreenSpaceReflections.MODE, ScreenSpaceReflections.Mode.OFF);
                int[] off = frames();
                for (ScreenSpaceReflections.Mode mode : TRACED) {
                    graph.settings().set(ScreenSpaceReflections.MODE, mode);
                    int[] on = frames();
                    int worst = 0;
                    for (int row = SIZE / 2 + 4; row < SIZE; row++) { // the floor
                        for (int column = 0; column < SIZE; column++) {
                            for (int shift = 0; shift < 24; shift += 8) {
                                worst = Math.max(worst, Math.abs((at(on, column, row) >> shift & 0xff)
                                    - (at(off, column, row) >> shift & 0xff)));
                            }
                        }
                    }
                    assertThat(worst).as("roughness %s, %s: the wall's reflection replaces the environment's, as bright",
                        roughness, mode).isLessThanOrEqualTo(4);
                }
            }
            // Not trivially: a brighter wall shows in the floor, so the floor did reflect it
            materials.set(wallMaterial, unlit(0.9f, 0.9f, 0.9f));
            materials.set(mirror, metal(0));
            graph.settings().set(ScreenSpaceReflections.MODE, ScreenSpaceReflections.Mode.HIZ);
            int[] brighter = frames();
            int[] floorPixel = pixelOf(0, -2, 8);
            assertThat(green(at(brighter, floorPixel[0], floorPixel[1]))).as("the brighter wall reflected")
                .isGreaterThan(200);
            scene.setMaterials(null);
        }
    }

    @Test
    void raysLeavingTheImageFadeIntoTheEnvironmentWithoutASeam() {
        try (Materials materials = new Materials(device, 8)) {
            int mirror = materials.add(metal(0));
            int green = materials.add(unlit(0, 0.8f, 0));
            scene.setMaterials(materials);
            floor(0, mirror);
            wall(1, green, 0, 39, 10, 12, 40); // from the floor far above the image's top
            scene.removeFrom(2);
            // Looking down: the near floor's rays rise past the image's top, the far floor's meet the wall in it
            Camera camera = new Camera().set(0, -0.7, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            main(camera, v -> MeshletBenchmark.forward(v).clearColor(0, 0, 0), Environment.uniform("blue", 0, 0, 0.4f));
            graph.settings().set(ScreenSpaceReflections.MODE, ScreenSpaceReflections.Mode.OFF);
            int[] off = frames();
            for (ScreenSpaceReflections.Mode mode : TRACED) {
                graph.settings().set(ScreenSpaceReflections.MODE, mode);
                graph.settings().set(ScreenSpaceReflections.EDGE_FADE, 0.001f);
                int[] unfaded = frames();
                save("seam-" + mode + "-hard", unfaded, SIZE);
                int hard = largestStep(unfaded);
                graph.settings().set(ScreenSpaceReflections.EDGE_FADE, 0.15f);
                int[] on = frames();
                save("seam-" + mode + "-soft", on, SIZE);
                int soft = largestStep(on);
                assertThat(hard).as("%s: without a fade, a seam where rays leave the image", mode).isGreaterThan(100);
                assertThat(soft).as("%s: faded, none", mode).isLessThan(hard / 3);
                int bottom = at(on, SIZE / 2, SIZE - 1);
                assertThat(green(bottom)).as("%s: the nearest floor reflects the environment alone", mode)
                    .isEqualTo(green(at(off, SIZE / 2, SIZE - 1)));
                assertThat(blue(bottom)).isEqualTo(blue(at(off, SIZE / 2, SIZE - 1)));
                assertThat(green(at(on, SIZE / 2, 30))).as("%s: the far floor reflects the wall", mode)
                    .isGreaterThan(green(at(off, SIZE / 2, 30)) + 100);
            }
            graph.settings().reset(ScreenSpaceReflections.EDGE_FADE);
            scene.setMaterials(null);
        }
    }

    /** The largest change of green from one row to the next down the image's middle column. */
    private static int largestStep(int[] image) {
        int largest = 0;
        for (int row = 0; row < SIZE - 1; row++) {
            largest = Math.max(largest, Math.abs(green(at(image, SIZE / 2, row)) - green(at(image, SIZE / 2, row + 1))));
        }
        return largest;
    }

    @Test
    void roughFloorsBlurTheirReflections() {
        try (Materials materials = new Materials(device, 8)) {
            int floorMaterial = materials.add(metal(0));
            int red = materials.add(unlit(1, 0, 0));
            scene.setMaterials(materials);
            floor(0, floorMaterial);
            wall(1, red, 0, 1, 6, 0.15f, 2); // a thin upright stripe
            scene.removeFrom(2);
            main(ahead(), v -> MeshletBenchmark.forward(v).clearColor(0, 0, 0), Environment.uniform("dark", 0, 0, 0.1f));
            int[] image = pixelOf(0, -2.5, 6);
            for (ScreenSpaceReflections.Mode mode : TRACED) {
                graph.settings().set(ScreenSpaceReflections.MODE, mode);
                materials.set(floorMaterial, metal(0));
                int[] mirrored = frames();
                save("rough-" + mode + "-0", mirrored, SIZE);
                int sharp = width(mirrored, image[1]);
                materials.set(floorMaterial, metal(0.3f));
                int[] rough = frames();
                save("rough-" + mode + "-0.3", rough, SIZE);
                int blurred = width(rough, image[1]);
                assertThat(sharp).as("%s: the mirror's stripe", mode).isBetween(2, 5);
                assertThat(blurred).as("%s: wider on the rough floor", mode).isGreaterThanOrEqualTo(sharp + 3);
            }
            scene.setMaterials(null);
        }
    }

    /** How many pixels of {@code row} are at least a third as red as its reddest. */
    private static int width(int[] image, int row) {
        int peak = 0;
        for (int column = 0; column < SIZE; column++) {
            peak = Math.max(peak, red(at(image, column, row)));
        }
        int count = 0;
        for (int column = 0; column < SIZE; column++) {
            count += peak > 20 && red(at(image, column, row)) * 3 >= peak ? 1 : 0;
        }
        return count;
    }

    @Test
    void reflectionsFollowTheRenderRegion() {
        try (Materials materials = new Materials(device, 8)) {
            int mirror = materials.add(metal(0));
            int red = materials.add(unlit(0.9f, 0, 0));
            scene.setMaterials(materials);
            floor(0, mirror);
            wall(1, red, 0, 0, 6, 1, 1);
            scene.removeFrom(2);
            SceneView main = main(ahead(), v -> MeshletBenchmark.forward(v).clearColor(0, 0, 0),
                Environment.uniform("blue", 0, 0, 0.4f));
            graph.settings().set(ResolutionSettings.DYNAMIC, true).set(ResolutionSettings.HEADROOM, 1.5f);
            int[] image = pixelOf(0, -2, 6);
            for (ScreenSpaceReflections.Mode mode : TRACED) {
                graph.settings().set(ScreenSpaceReflections.MODE, mode);
                for (float scale : new float[] {1, 0.5f}) {
                    main.port().renderScale(scale);
                    int[] on = frames();
                    int p = at(on, image[0], image[1]);
                    assertThat(red(p)).as("%s at %s of the view: the wall's mirror image", mode, scale)
                        .isGreaterThan(170);
                    assertThat(blue(p)).isLessThan(30);
                }
                graph.settings().set(ScreenSpaceReflections.RESOLUTION_SCALE, 0.5f);
                int[] half = frames();
                save("half-" + mode, half, SIZE);
                int p = at(half, image[0], image[1]);
                assertThat(red(p)).as("%s traced at half resolution", mode).isGreaterThan(170);
                graph.settings().reset(ScreenSpaceReflections.RESOLUTION_SCALE);
            }
            main.port().renderScale(1);
            graph.settings().set(ResolutionSettings.DYNAMIC, false);
            scene.setMaterials(null);
        }
    }

    /** A technique asking for the surface outputs, as post materials will. */
    private static final class Reader implements Technique, SurfaceOutputs.Consumer {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public boolean wantsSurfaces(GraphBuilder g) {
            return true;
        }

        @Override
        public void build(GraphBuilder g) {
        }
    }

    @Test
    void surfaceOutputsAreWrittenOnlyWhenAsked() {
        try (Materials materials = new Materials(device, 8)) {
            int floorMaterial = materials.add(metal(0.5f));
            scene.setMaterials(materials);
            floor(0, floorMaterial);
            scene.removeFrom(1);
            Camera camera = ahead();
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            SceneView main = new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera)
                .set(1, MeshletRenderer.MODE_LIT);
            main.port().add(MeshletBenchmark.forward(main).clearColor(0, 0, 0)).add(new ScreenSpaceReflections(main))
                .output(Keys.SCENE_COLOR, output);
            assertThat(graph.settings().get(ScreenSpaceReflections.MODE)).as("on by default")
                .isEqualTo(ScreenSpaceReflections.Mode.HIZ);
            graph.settings().set(ScreenSpaceReflections.MODE, ScreenSpaceReflections.Mode.OFF);
            frames();
            assertThat(renderer.pipelineCount(ShadingPipelines.COLOR_SURFACES)).as("off: no surface pipelines").isZero();
            assertThat(graph.passes()).noneMatch(n -> n.startsWith("reflection"));
            List<Function<SceneView, Technique>> shadings = new ArrayList<>();
            shadings.add(v -> MeshletBenchmark.forward(v).clearColor(0, 0, 0));
            if (device.primitiveIds()) {
                shadings.add(v -> new VisibilityBufferShading(SceneGeometry.meshlets(renderer), v,
                    VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0));
            }
            int[] floorPixel = pixelOf(0, -1, 4);
            for (Function<SceneView, Technique> shading : shadings) {
                Reader reader = new Reader();
                main.port().replace(shading.apply(main)).removeOutput(Keys.SCENE_COLOR).add(reader);
                main.port().output(Keys.SURFACE_MATERIAL, output);
                int material = at(frames(), floorPixel[0], floorPixel[1]);
                assertThat(red(material)).as("F0: white metal").isGreaterThan(250);
                assertThat(material >>> 24).as("roughness").isBetween(126, 129);
                main.port().removeOutput(Keys.SURFACE_MATERIAL).output(Keys.SURFACE_REFLECTANCE, output);
                int reflectance = at(frames(), floorPixel[0], floorPixel[1]);
                assertThat(red(reflectance)).as("the environment's reflection's weight").isBetween(100, 255);
                assertThat(reflectance >>> 24).as("a surface").isEqualTo(255);
                assertThat(at(frames(), SIZE / 2, 2) >>> 24).as("the sky: none").isZero();
                main.port().removeOutput(Keys.SURFACE_REFLECTANCE).output(Keys.SCENE_COLOR, output);
                main.port().remove(reader);
            }
            assertThat(renderer.pipelineCount(ShadingPipelines.COLOR_SURFACES)).as("asked: made").isPositive();
            scene.setMaterials(null);
        }
    }

    @Test
    void reflectingFramesAllocateNothing() {
        try (Materials materials = new Materials(device, 8)) {
            int mirror = materials.add(metal(0.2f));
            int red = materials.add(unlit(0.9f, 0, 0));
            scene.setMaterials(materials);
            floor(0, mirror);
            wall(1, red, 0, 0, 6, 1, 1);
            scene.removeFrom(2);
            main(ahead(), v -> MeshletBenchmark.forward(v).clearColor(0, 0, 0), Environment.uniform("blue", 0, 0, 0.4f));
            for (ScreenSpaceReflections.Mode mode : TRACED) {
                graph.settings().set(ScreenSpaceReflections.MODE, mode);
                frames();
                long builds = graph.builds();
                for (int i = 0; i < 5; i++) {
                    graph.beginFrame();
                    graph.submit(0, 0);
                }
                assertThat(graph.builds()).as("no rebuilds").isEqualTo(builds);
                assertAllocationFree(() -> {
                    graph.beginFrame();
                    graph.submit(0, 0);
                }, 8000, 300, 8);
                device.waitIdle();
            }
            scene.setMaterials(null);
        }
    }
}
