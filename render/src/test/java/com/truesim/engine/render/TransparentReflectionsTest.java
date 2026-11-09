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
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.materials.IorParameters;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.materials.TransmissionParameters;
import com.truesim.engine.render.materials.UnlitParameters;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
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
 * Screen-space reflections of blended and transmissive surfaces ({@code render.ssr.transparent}): a camera at the
 * origin looking down -z (90 degrees), a glossy see-through floor at y = -1 over an opaque bed at y = -2, a red wall
 * standing on it at depth 6, under a uniform blue environment. A point (x, y, z) shows at pixel column
 * SIZE / 2 (1 + x / -z), row SIZE / 2 (1 - y / -z); its mirror image in the floor is (x, -2 - y, z).
 */
class TransparentReflectionsTest {

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
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 12, 64, 8));
        quadData = ScreenSpaceReflectionsTest.quad();
        quad = pool.add(quadData);
        scene = new GpuScene(device, pool, 64);
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
        view = renderer.createView(ViewOptions.of(256, 1 << 12).withOcclusion(true));
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

    private static Material unlit(float r, float g, float b) {
        return new Material(MR.withBaseColor(new Color(r, g, b, 1))).feature(UnlitParameters.DEFAULTS);
    }

    /** Clear glass, nearly a mirror: transmissive, the scene behind seen through it. */
    private static Material glass() {
        return new Material(MR.withBaseColor(new Color(1, 1, 1, 1)).withRoughness(0.02f).withMetallic(0))
            .feature(TransmissionParameters.DEFAULTS.withTransmissionFactor(1)).feature(IorParameters.DEFAULTS);
    }

    /** Half see-through polished metal: blended by its alpha. */
    private static Material blendedMetal() {
        return new Material(MR.withBaseColor(new Color(1, 1, 1, 0.5f)).withRoughness(0.02f).withMetallic(1))
            .alphaBlend().doubleSided(true);
    }

    /** The scene: the see-through floor of {@code floor}, the bed, the wall. */
    private void build(int floor, int bed, int red) {
        scene.set(0, quad, floor, 0, -1, -8, -HALF, 0, 0, HALF, 8, 8, 1);
        scene.set(1, quad, bed, 0, -2, -8, -HALF, 0, 0, HALF, 8, 8, 1);
        scene.set(2, quad, red, 0, 0, -6, 0, 0, 0, 1, 1, 1, 1); // x and y from -1 to 1 at depth 6, on the floor
    }

    private SceneView main(Function<SceneView, Technique> shading, Function<SceneView, Technique> transparency,
        Environment environment) {
        Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        SceneView main = new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera)
            .set(1, MeshletRenderer.MODE_LIT);
        main.port().add(shading.apply(main)).add(new EnvironmentLighting(environment, main, SMALL))
            .add(new SkyBackground(environment, main, SMALL)).add(new ScreenSpaceReflections(main))
            .add(transparency.apply(main)).output(Keys.SCENE_COLOR, output);
        return main;
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

    private static int red(int pixel) {
        return pixel & 0xff;
    }

    /** The mean red over the 5 x 5 pixels around (column, row). */
    private static double redAround(int[] image, int column, int row) {
        double sum = 0;
        for (int y = row - 2; y <= row + 2; y++) {
            for (int x = column - 2; x <= column + 2; x++) {
                sum += red(image[y * SIZE + x]);
            }
        }
        return sum / 25;
    }

    /** A shading of the scene and the geometry it draws. */
    private record Shading(String name, Function<SceneView, Technique> make, SceneGeometry geometry) {
    }

    /** Forward on each meshlet path the device draws on, the visibility buffer, and forward with classic LOD. */
    private List<Shading> shadings(ClassicLodGeometry classic) {
        List<Shading> shadings = new ArrayList<>();
        SceneGeometry meshlets = SceneGeometry.meshlets(renderer);
        shadings.add(new Shading("forward, indexed", v -> {
            renderer.setPath(MeshletRenderer.Path.INDEXED);
            return MeshletBenchmark.forward(v).clearColor(0, 0, 0);
        }, meshlets));
        if (device.meshShaders()) {
            shadings.add(new Shading("forward, mesh shaders", v -> {
                renderer.setPath(MeshletRenderer.Path.MESH_SHADERS);
                return MeshletBenchmark.forward(v).clearColor(0, 0, 0);
            }, meshlets));
        }
        if (device.primitiveIds()) {
            shadings.add(new Shading("visibility buffer", v -> {
                renderer.setPath(MeshletRenderer.Path.AUTO);
                return new VisibilityBufferShading(meshlets, v, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0, 0, 0);
            }, meshlets));
        }
        SceneGeometry classics = new SceneGeometry(classic.add(quad, quadData));
        shadings.add(new Shading("forward, classic LOD", v -> new ClusteredForwardShading(classics, v,
            VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0), classics));
        return shadings;
    }

    private static final List<TechniqueKind<?, ChosenTransparency, Technique>> KINDS =
        List.of(Transparency.SORTED, Transparency.WEIGHTED);

    /**
     * On every geometry path and both transparency kinds, glass and a blended metal reflect the red wall where its
     * mirror image shows; off, the image is as it was, pixel for pixel, and what no see-through surface covers stays
     * as it was with it on.
     */
    @Test
    void seeThroughFloorsReflectTheWallBehindTheirMirrorImage() {
        try (Materials materials = new Materials(device, 8);
             ClassicLodGeometry classic = new ClassicLodGeometry(device, renderer, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, 16)) {
            int glass = materials.add(glass());
            int metal = materials.add(blendedMetal());
            int bed = materials.add(unlit(0.1f, 0.1f, 0.1f));
            int red = materials.add(unlit(0.9f, 0, 0));
            scene.setMaterials(materials);
            Environment sky = Environment.uniform("blue", 0, 0, 0.4f);
            // The wall's center (0, 0, -6) mirrored: (0, -2, -6), on the floor at depth 3
            int column = SIZE / 2;
            int row = (int) Math.floor(SIZE / 2.0 * (1 + 2 / 6.0));
            int horizon = SIZE / 2 - 2; // above it, no floor
            for (Shading shading : shadings(classic)) {
                for (TechniqueKind<?, ChosenTransparency, Technique> kind : KINDS) {
                    for (int floor : new int[] {glass, metal}) {
                        String name = shading.name() + ", " + kind.id() + ", " + (floor == glass ? "glass"
                            : "blended metal");
                        graph.close();
                        graph = new FrameGraph(device, 2);
                        build(floor, bed, red);
                        main(shading.make(), v -> new ChosenTransparency(shading.geometry(), v), sky);
                        Transparency.SLOT.choose(graph.settings(), kind);
                        int[] before = frames(); // the setting as it is by default: off
                        graph.settings().set(ScreenSpaceReflections.TRANSPARENT, true);
                        int[] on = frames();
                        graph.settings().set(ScreenSpaceReflections.TRANSPARENT, false);
                        int[] off = frames();
                        ScreenSpaceReflectionsTest.save(name.replace(", ", "-").replace(' ', '_') + "-on", on,
                            SIZE);
                        double gained = redAround(on, column, row) - redAround(off, column, row);
                        assertThat(gained).as("%s: the wall's red reflected (off %s)", name,
                            redAround(off, column, row)).isGreaterThan(floor == glass ? 8 : 30);
                        assertThat(off).as("%s: off as before", name).isEqualTo(before);
                        for (int i = 0; i < horizon * SIZE; i++) {
                            assertThat(on[i]).as("%s: pixel %d above the floor", name, i).isEqualTo(off[i]);
                        }
                        // Beside the wall's mirror image, the sky's reflection: the environment stays
                        int beside = (int) Math.floor(SIZE / 2.0 * (1 + 2.5 / 6.0));
                        assertThat(Math.abs(redAround(on, beside, row) - redAround(off, beside, row)))
                            .as("%s: the sky's mirror image", name).isLessThan(4);
                    }
                }
            }
        }
    }
}
