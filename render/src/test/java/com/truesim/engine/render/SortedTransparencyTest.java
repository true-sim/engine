package com.truesim.engine.render;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
import com.truesim.engine.render.testing.TestMeshes;
import com.truesim.engine.render.testing.TestRenderer;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.materials.IorParameters;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.assets.mesh.MeshData;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Sorted transparency against the analytic "over" of its layers, and weighted blended transparency's error beside it:
 * glowing glass (black base, emissive color, alpha) over black, so a layer of color c and alpha a over what is behind
 * it, b, gives a c + (1 - a) b.
 */
class SortedTransparencyTest {

    private static final int SIZE = 64;
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS;
    private static final int CENTER = (SIZE / 2) * SIZE + SIZE / 2;

    private TestGpu gpu;
    private TestRenderer rig;
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

    @BeforeEach
    void start() {
        gpu = TestGpu.open(true); // synchronization validated too: the sorts' passes and barriers
        device = gpu.device();
        rig = gpu.closing(TestRenderer.of(device, SIZE, SIZE));
        pool = rig.pool();
        scene = rig.scene();
        renderer = rig.renderer();
        view = rig.view();
        graph = rig.graph();
        quad = rig.add(TestMeshes.quad());
        target = gpu.closing(image());
        output = external(target);
        pixels = gpu.closing(GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT));
    }

    @AfterEach
    void stop() {
        if (gpu != null) {
            gpu.close(); // fails on validation errors
        }
    }

    private GpuImage image() {
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        return new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
    }

    private static ExternalImage external(GpuImage image) {
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        return new ExternalImage("target", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(image.handle(), image.view());
    }

    /** Glowing glass of color (r, g, b) and {@code alpha}, double-sided; with {@code ior}, in a shading bin apart. */
    private static Material glass(float r, float g, float b, float alpha, boolean ior) {
        Material glass = new Material(MR.withBaseColor(new Color(0, 0, 0, alpha)).withRoughness(1)
            .withEmissive(new Color(r, g, b, 1))).alphaBlend().doubleSided(true);
        return ior ? glass.feature(IorParameters.DEFAULTS) : glass;
    }

    /** The over-composite of layers {@code (r, g, b, a)}, farthest first, on black, as 0 to 255 per channel. */
    private static double[] over(float[]... layers) {
        double[] c = new double[3];
        for (float[] layer : layers) {
            for (int k = 0; k < 3; k++) {
                c[k] = layer[3] * layer[k] + (1 - layer[3]) * c[k];
            }
        }
        return new double[] {255 * c[0], 255 * c[1], 255 * c[2]};
    }

    private SceneView main(Camera camera) {
        return new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera).set(1, MeshletRenderer.MODE_LIT);
    }

    private void frame() {
        frame(target, pixels);
    }

    /** Records a frame and reads {@code image} back into {@code into}. */
    private void frame(GpuImage image, GpuBuffer into) {
        device.submitAndWait(device.graphics(), commands -> {
            graph.record(commands);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(SIZE, SIZE, 1);
                vkCmdCopyImageToBuffer(commands, image.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    into.handle(), region);
            }
        });
    }

    /** Three frames: occlusion culling's phases settled (pitfalls.md). */
    private void frames() {
        for (int i = 0; i < 3; i++) {
            frame();
        }
    }

    private static int[] channels(GpuBuffer buffer, int pixel) {
        int p = buffer.memory().getAtIndex(ValueLayout.JAVA_INT, pixel);
        return new int[] {p & 0xff, (p >> 8) & 0xff, (p >> 16) & 0xff};
    }

    /** The largest difference of the center pixel from {@code expected}, over the three channels. */
    private double error(double[] expected) {
        int[] c = channels(pixels, CENTER);
        double worst = 0;
        for (int k = 0; k < 3; k++) {
            worst = Math.max(worst, Math.abs(c[k] - expected[k]));
        }
        return worst;
    }

    /** The meshlet paths the device draws on. */
    private List<MeshletRenderer.Path> paths() {
        return device.meshShaders() ? List.of(MeshletRenderer.Path.INDEXED, MeshletRenderer.Path.MESH_SHADERS)
            : List.of(MeshletRenderer.Path.INDEXED);
    }

    /** Forward shading, and the visibility buffer where the device has primitive IDs. */
    private List<Function<SceneView, Technique>> shadings() {
        List<Function<SceneView, Technique>> shadings = new ArrayList<>();
        shadings.add(v -> MeshletBenchmark.forward(v).clearColor(0, 0, 0));
        if (device.primitiveIds()) {
            shadings.add(v -> new VisibilityBufferShading(SceneGeometry.meshlets(renderer), v,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0));
        }
        return shadings;
    }

    @Test
    void redOverBlueIsNotBlueOverRed() {
        float[] red = {1, 0, 0, 0.6f};
        float[] blue = {0, 0, 1, 0.6f};
        try (Materials materials = new Materials(device, 8)) {
            int redGlass = materials.add(glass(1, 0, 0, 0.6f, false));
            int blueGlass = materials.add(glass(0, 0, 1, 0.6f, false));
            int blueApart = materials.add(glass(0, 0, 1, 0.6f, true)); // another shading bin: runs across bins
            scene.setMaterials(materials);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            SceneView main = main(camera);
            main.port().add(shadings().get(0).apply(main)).add(new ChosenTransparency(SceneGeometry.meshlets(renderer),
                main)).output(Keys.SCENE_COLOR, output);
            for (int s = 0; s < shadings().size(); s++) {
                main.port().replace(shadings().get(s).apply(main));
                for (MeshletRenderer.Path path : paths()) {
                    renderer.setPath(path);
                    for (int blueMaterial : new int[] {blueGlass, blueApart}) {
                        String what = path + (s == 0 ? ", forward" : ", visibility buffer")
                            + (blueMaterial == blueApart ? ", blue in a shading bin apart" : "");
                        // Red in front, blue behind; then the other way round
                        Transparency.SLOT.choose(graph.settings(), Transparency.SORTED);
                        scene.set(0, quad, redGlass, 0, 0, -2, 0, 0, 0, 1, 2, 2, 2);
                        scene.set(1, quad, blueMaterial, 0, 0, -3, 0, 0, 0, 1, 3, 3, 3);
                        frames();
                        assertThat(error(over(blue, red))).as("%s: red over blue", what).isLessThanOrEqualTo(3);
                        scene.set(0, quad, redGlass, 0, 0, -3, 0, 0, 0, 1, 3, 3, 3);
                        scene.set(1, quad, blueMaterial, 0, 0, -2, 0, 0, 0, 1, 2, 2, 2);
                        frames();
                        assertThat(error(over(red, blue))).as("%s: blue over red", what).isLessThanOrEqualTo(3);
                        int[] sortedBlueOverRed = channels(pixels, CENTER);
                        // Weighted: the same either way (order-independent), off by about 46 of 255 from both
                        Transparency.SLOT.choose(graph.settings(), Transparency.WEIGHTED);
                        frames();
                        int[] weighted = channels(pixels, CENTER);
                        double weightedError = error(over(red, blue));
                        scene.set(0, quad, redGlass, 0, 0, -2, 0, 0, 0, 1, 2, 2, 2);
                        scene.set(1, quad, blueMaterial, 0, 0, -3, 0, 0, 0, 1, 3, 3, 3);
                        frames();
                        assertThat(channels(pixels, CENTER)).as("%s: weighted, either order", what)
                            .satisfies(c -> assertThat(Math.abs(c[0] - weighted[0]) + Math.abs(c[2] - weighted[2]))
                                .isLessThanOrEqualTo(4));
                        assertThat(weightedError).as("%s: weighted's error (sorted: %s, weighted: %s)", what,
                            java.util.Arrays.toString(sortedBlueOverRed), java.util.Arrays.toString(weighted))
                            .isGreaterThan(30);
                    }
                }
            }
            renderer.setPath(MeshletRenderer.Path.AUTO);
            scene.setMaterials(null);
        }
    }

    @Test
    void aDoubleWallDrawsItsFarWallFirst() {
        // One instance, its two walls of many meshlets each: only sorting meshlets, not instances, orders them.
        int walls = pool.add(TestMeshes.walls(12));
        float[] red = {1, 0, 0, 0.6f};
        float[] blue = {0, 0, 1, 0.6f};
        try (Materials materials = new Materials(device, 8)) {
            int first = materials.add(glass(1, 0, 0, 0.6f, false)); // the +z wall's: submesh 0
            materials.add(glass(0, 0, 1, 0.6f, false));           // the -z wall's: submesh 1
            scene.setMaterials(materials);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            SceneView main = main(camera);
            main.port().add(shadings().get(0).apply(main)).add(new SortedTransparency(SceneGeometry.meshlets(renderer),
                main)).output(Keys.SCENE_COLOR, output);
            for (MeshletRenderer.Path path : paths()) {
                renderer.setPath(path);
                scene.set(0, walls, first, 0, 0, -3, 0, 0, 0, 1, 2, 2, 2); // the red wall nearer
                frames();
                assertThat(error(over(blue, red))).as("%s: the red wall in front", path).isLessThanOrEqualTo(3);
                scene.set(0, walls, first, 0, 0, -3, 0, 1, 0, 0, 2, 2, 2); // turned about y: the blue one nearer
                frames();
                assertThat(error(over(red, blue))).as("%s: the blue wall in front", path).isLessThanOrEqualTo(3);
            }
            renderer.setPath(MeshletRenderer.Path.AUTO);
            scene.setMaterials(null);
        }
    }

    @Test
    void manyLayersCompositeInOrder() {
        // 300 quads over the center, at distinct depths, in shuffled instance order, three colors in two shading bins
        int layers = 300;
        try (Materials materials = new Materials(device, 8)) {
            int[] ids = {materials.add(glass(1, 0, 0, 0.15f, false)), materials.add(glass(0, 1, 0, 0.15f, true)),
                materials.add(glass(0, 0, 1, 0.15f, false))};
            float[][] colors = {{1, 0, 0, 0.15f}, {0, 1, 0, 0.15f}, {0, 0, 1, 0.15f}};
            scene.setMaterials(materials);
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < layers; i++) {
                order.add(i);
            }
            Collections.shuffle(order, new Random(7));
            float[][] farthestFirst = new float[layers][];
            for (int i = 0; i < layers; i++) {
                int layer = order.get(i); // layer 0 nearest
                float z = -2 - 0.01f * layer;
                scene.set(i, quad, ids[layer % 3], 0, 0, z, 0, 0, 0, 1, 2, 2, 2);
                farthestFirst[layers - 1 - layer] = colors[layer % 3];
            }
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            SceneView main = main(camera);
            main.port().add(shadings().get(0).apply(main)).add(new SortedTransparency(SceneGeometry.meshlets(renderer),
                main)).output(Keys.SCENE_COLOR, output);
            // Every layer starts a run (the shading bins alternate): slots for them all
            graph.settings().set(Transparency.SORTED_RUNS, layers);
            for (Function<SceneView, Technique> shading : shadings()) {
                main.port().replace(shading.apply(main));
                for (MeshletRenderer.Path path : paths()) {
                    renderer.setPath(path);
                    frames();
                    assertThat(error(over(farthestFirst))).as("%s: %d layers", path, layers).isLessThanOrEqualTo(4);
                }
            }
            // More runs than slots: ordered by shading bin first (red and blue's, then green's), each back to front
            graph.settings().set(Transparency.SORTED_RUNS, 2);
            renderer.setPath(paths().get(0));
            frames();
            List<float[]> byBin = new ArrayList<>();
            for (float[] layer : farthestFirst) {
                if (layer[1] == 0) {
                    byBin.add(layer);
                }
            }
            for (float[] layer : farthestFirst) {
                if (layer[1] != 0) {
                    byBin.add(layer);
                }
            }
            assertThat(error(over(byBin.toArray(new float[0][])))).as("past the slots").isLessThanOrEqualTo(6);
            renderer.setPath(MeshletRenderer.Path.AUTO);
            scene.setMaterials(null);
        }
    }

    @Test
    void eachViewSortsFromItsOwnEye() {
        // Two views (a headset's eyes, two windows) on opposite sides of red and blue glass: each sees its own nearer.
        float[] red = {1, 0, 0, 0.6f};
        float[] blue = {0, 0, 1, 0.6f};
        RenderView otherView = renderer.createView(ViewOptions.of(4096, 1 << 16).withOcclusion(true));
        GpuImage otherTarget = image();
        GpuBuffer otherPixels = GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        try (Materials materials = new Materials(device, 8)) {
            int redGlass = materials.add(glass(1, 0, 0, 0.6f, false));
            int blueGlass = materials.add(glass(0, 0, 1, 0.6f, true));
            scene.setMaterials(materials);
            scene.set(0, quad, redGlass, 0, 0, -2, 0, 0, 0, 1, 2, 2, 2);
            scene.set(1, quad, blueGlass, 0, 0, -3, 0, 0, 0, 1, 2, 2, 2);
            Camera front = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            Camera back = new Camera().set(0, 0, -5, 0, 0, 1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            SceneView left = new SceneView(graph.addView("left").size(SIZE, SIZE), view, front).set(1, MeshletRenderer.MODE_LIT);
            SceneView right = new SceneView(graph.addView("right").size(SIZE, SIZE), otherView, back)
                .set(1, MeshletRenderer.MODE_LIT);
            for (SceneView eye : new SceneView[] {left, right}) {
                eye.port().add(MeshletBenchmark.forward(eye).clearColor(0, 0, 0))
                    .add(new ChosenTransparency(SceneGeometry.meshlets(renderer), eye))
                    .output(Keys.SCENE_COLOR, eye == left ? output : external(otherTarget));
            }
            for (MeshletRenderer.Path path : paths()) {
                renderer.setPath(path);
                for (int i = 0; i < 3; i++) {
                    frame(otherTarget, otherPixels);
                }
                frame();
                assertThat(error(over(blue, red))).as("%s: the front eye, red nearer", path).isLessThanOrEqualTo(3);
                int[] seen = channels(otherPixels, CENTER);
                double[] expected = over(red, blue);
                assertThat(seen[0]).as("%s: the back eye, blue nearer", path).isCloseTo((int) Math.round(expected[0]),
                    within(3));
                assertThat(seen[2]).isCloseTo((int) Math.round(expected[2]), within(3));
            }
            renderer.setPath(MeshletRenderer.Path.AUTO);
            scene.setMaterials(null);
        } finally {
            device.waitIdle();
            otherPixels.close();
            otherTarget.close();
        }
    }

    @Test
    void classicLodSortsWithinAShadingBin() {
        float[] red = {1, 0, 0, 0.6f};
        float[] blue = {0, 0, 1, 0.6f};
        MeshData mesh = TestMeshes.quad();
        try (Materials materials = new Materials(device, 8);
             ClassicLodGeometry classic = new ClassicLodGeometry(device, renderer, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, 64)) {
            int redGlass = materials.add(glass(1, 0, 0, 0.6f, false));
            int blueGlass = materials.add(glass(0, 0, 1, 0.6f, false));
            scene.setMaterials(materials);
            int classicQuad = pool.add(mesh);
            SceneGeometry classics = new SceneGeometry(classic.add(classicQuad, mesh));
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            SceneView main = main(camera);
            main.port().add(new ClusteredForwardShading(classics, main, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                .clearColor(0, 0, 0)).add(new SortedTransparency(classics, main)).output(Keys.SCENE_COLOR, output);
            for (int flip = 0; flip < 2; flip++) {
                // Added nearest first, then farthest first: drawn back to front either way
                scene.set(flip, classicQuad, redGlass, 0, 0, -2, 0, 0, 0, 1, 2, 2, 2);
                scene.set(1 - flip, classicQuad, blueGlass, 0, 0, -3, 0, 0, 0, 1, 3, 3, 3);
                frames();
                assertThat(error(over(blue, red))).as("classic, red over blue, %d", flip).isLessThanOrEqualTo(3);
            }
            scene.set(0, classicQuad, redGlass, 0, 0, -3, 0, 0, 0, 1, 3, 3, 3);
            scene.set(1, classicQuad, blueGlass, 0, 0, -2, 0, 0, 0, 1, 2, 2, 2);
            frames();
            assertThat(error(over(red, blue))).as("classic, blue over red").isLessThanOrEqualTo(3);
            if (device.primitiveIds()) {
                main.port().replace(new VisibilityBufferShading(classics, main, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0));
                frames();
                assertThat(error(over(red, blue))).as("classic, visibility buffer").isLessThanOrEqualTo(3);
            }
            scene.setMaterials(null);
        }
    }

    @Test
    void aSkyBuildsWithEveryTransparency() {
        // The sky reads the opaque depth before transparency; drawn as opaque, transparency writes the depth after
        // it. Requiring the depth's last version, the sky needed that technique built first: a cycle.
        float[] blue = {0, 0, 1, 0.6f};
        try (Materials materials = new Materials(device, 8)) {
            int blueGlass = materials.add(glass(0, 0, 1, 0.6f, false));
            scene.setMaterials(materials);
            scene.set(0, quad, blueGlass, 0, 0, -2, 0, 0, 0, 1, 0.5f, 0.5f, 0.5f);
            Environment environment = Environment.uniform("red sky", 1, 0, 0);
            EnvironmentMaps.Quality small = new EnvironmentMaps.Quality(64, 32, 4, 16, 64);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            SceneView main = main(camera);
            SceneGeometry meshlets = SceneGeometry.meshlets(renderer);
            main.port().add(MeshletBenchmark.forward(main).clearColor(0, 0, 0))
                .add(new SkyBackground(environment, main, small)).output(Keys.SCENE_COLOR, output);
            List<Technique> ways = List.of(new OpaqueTransparency(meshlets, main),
                new SortedTransparency(meshlets, main), new WeightedBlendedTransparency(meshlets, main),
                new ChosenTransparency(meshlets, main));
            for (Technique way : ways) {
                for (TechniqueKind<?, ChosenTransparency, Technique> mode : List.of(Transparency.SORTED,
                    Transparency.WEIGHTED, Transparency.OPAQUE)) {
                    Transparency.SLOT.choose(graph.settings(), mode);
                    main.port().remove(Transparency.ID).add(way);
                    frames();
                    String what = way.getClass().getSimpleName() + ", " + mode;
                    int[] corner = channels(pixels, 2 * SIZE + 2);
                    assertThat(corner[0]).as("%s: the red sky", what).isGreaterThan(200);
                    int[] center = channels(pixels, CENTER);
                    boolean opaque = way instanceof OpaqueTransparency
                        || way instanceof ChosenTransparency && mode == Transparency.OPAQUE;
                    if (opaque) {
                        assertThat(center[0]).as("%s: the glass covers the sky", what).isLessThan(30);
                    } else if (way instanceof SortedTransparency
                        || way instanceof ChosenTransparency && mode == Transparency.SORTED) {
                        double[] expected = over(new float[] {corner[0] / 255f, 0, 0, 1}, blue);
                        assertThat(error(expected)).as("%s: the glass over the sky", what).isLessThanOrEqualTo(6);
                    } else {
                        assertThat(center[2]).as("%s: blue glass", what).isGreaterThan(80);
                        assertThat(center[0]).as("%s: the sky through it", what).isGreaterThan(40);
                    }
                    if (!(way instanceof ChosenTransparency)) {
                        break; // the setting changes the chosen one only
                    }
                }
            }
            scene.setMaterials(null);
        }
    }

    @Test
    void sortedFramesAllocateNothing() {
        try (Materials materials = new Materials(device, 8)) {
            int redGlass = materials.add(glass(1, 0, 0, 0.6f, false));
            int blueGlass = materials.add(glass(0, 0, 1, 0.6f, true));
            scene.setMaterials(materials);
            for (int i = 0; i < 40; i++) {
                scene.set(i, quad, i % 2 == 0 ? redGlass : blueGlass, 0.1 * (i % 5), 0, -2 - 0.05 * i, 0, 0, 0, 1, 1,
                    1, 1);
            }
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            SceneView main = main(camera);
            main.port().add(shadings().get(shadings().size() - 1).apply(main))
                .add(new ChosenTransparency(SceneGeometry.meshlets(renderer), main)).output(Keys.SCENE_COLOR, output);
            Transparency.SLOT.choose(graph.settings(), Transparency.SORTED);
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
            scene.setMaterials(null);
        }
    }
}
