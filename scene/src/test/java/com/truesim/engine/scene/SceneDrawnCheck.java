package com.truesim.engine.scene;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Technique;
import com.truesim.common.math.color.Color;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.Renderables;
import java.awt.image.BufferedImage;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.function.Function;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * The benchmark's 30,000 rocks on the terrain, checked to be drawn whole: through the scene (SceneSync) near the
 * origin and 1,000 km out, and straight through the renderer near the origin as the reference, in both shadings.
 * Compares the images and the meshlets drawn once the frames have finished, and saves the images. Run with
 * {@code TRUESIM_SCREENSHOTS=true ./gradlew :engine-scene:test --tests '*SceneDrawnCheck'} (PNGs in
 * {@code TRUESIM_SCREENSHOTS_DIR}, or the build directory).
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class SceneDrawnCheck {

    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    private static final int ROCKS = 30_000;

    /** A drawn frame: its pixels and the meshlets drawn. */
    record Drawn(int[] pixels, int meshlets) {
    }

    @Test
    void everythingIsDrawn() throws Exception {
        Path out = Path.of(System.getenv().getOrDefault("TRUESIM_SCREENSHOTS_DIR", "build/drawn"));
        Files.createDirectories(out);
        MeshData rock = SceneBenchmark.rock(6);
        MeshData terrain = SceneBenchmark.terrain(1024);
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("check").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            System.out.printf(Locale.ROOT, "Drawn check on %s, %d x %d, %,d rocks:%n", device.gpu().name(), WIDTH,
                HEIGHT, ROCKS);
            boolean ids = device.primitiveIds();
            for (boolean visibility : ids ? new boolean[] {false, true} : new boolean[] {false}) {
                String shading = visibility ? "visibility buffer" : "forward";
                Drawn reference = draw(device, rock, terrain, visibility, 0, false);
                Drawn near = draw(device, rock, terrain, visibility, 0, true);
                Drawn far = draw(device, rock, terrain, visibility, 1e6, true);
                save(reference, out.resolve(shading.replace(' ', '-') + "-renderer-near.png"));
                save(near, out.resolve(shading.replace(' ', '-') + "-scene-near.png"));
                save(far, out.resolve(shading.replace(' ', '-') + "-scene-far.png"));
                System.out.printf(Locale.ROOT, "  %s: meshlets drawn, renderer near %,d, scene near %,d, scene far "
                    + "%,d; pixels differing from the renderer's: near %,d, far %,d; drawn (not sky) %,d%n", shading,
                    reference.meshlets(), near.meshlets(), far.meshlets(), differing(reference, near),
                    differing(reference, far), drawnPixels(reference));
                assertThat(near.meshlets()).as("%s, scene near", shading).isEqualTo(reference.meshlets());
                assertThat(differing(reference, near)).as("%s, scene near", shading).isZero();
                assertThat(differing(reference, far)).as("%s, scene far", shading)
                    .isLessThan(WIDTH * HEIGHT / 200);
            }
        }
    }

    /**
     * Draws the rocks around ({@code far}, 0, {@code far}) through the scene, or near the origin straight through the
     * renderer ({@code throughScene} false), with two frames in flight until occlusion culling has settled.
     */
    private static Drawn draw(VulkanDevice device, MeshData rock, MeshData terrain, boolean visibility, double far,
        boolean throughScene) throws InterruptedException {
        FrameSlots slots = new FrameSlots(2);
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 100_000, 4), slots);
             Materials materials = new Materials(device, 64, slots);
             Textures textures = new Textures(device, slots);
             TaskScheduler tasks = new TaskScheduler();
             AssetCache assets = new AssetCache(tasks);
             SceneResources resources = new SceneResources(assets, pool, materials, textures);
             GpuScene gpu = new GpuScene(device, pool, 40_000, slots);
             MeshletRenderer renderer = new MeshletRenderer(device, gpu, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, slots);
             GpuImage target = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, usage,
                 VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, 4L * WIDTH * HEIGHT, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
             FrameGraph graph = new FrameGraph(device, slots)) {
            Camera camera = new Camera().position(far, 0, far)
                .set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            RenderView cameraView = geometry.createView(ViewOptions.of(1 << 20, 80_000_000).withOcclusion(true));
            SceneGraph scene = new SceneGraph(new ChangeClock(), ROCKS + 64, SceneGraph.DEFAULT_LOG_CAPACITY);
            Renderables renderables = new Renderables(scene);
            SceneSync sync = null;
            double[] placed = new double[3 * ROCKS];
            SplittableRandom random = new SplittableRandom(11);
            if (throughScene) {
                long ground = scene.create();
                scene.setTranslation(ground, far, -12, far);
                renderables.set(ground, SceneBenchmark.GROUND, List.of(SceneBenchmark.GRASS));
                for (int i = 0; i < ROCKS; i++) {
                    long node = scene.create();
                    SceneBenchmark.place(scene, node, random, placed, i, far);
                    renderables.set(node, SceneBenchmark.ROCK, List.of(SceneBenchmark.GRANITE));
                }
                sync = new SceneSync(resources, renderables, gpu);
            } else {
                // The reference: the meshes added and the instances set straight, as the renderer's tests do.
                int rockMesh = pool.add(rock);
                int groundMesh = pool.add(terrain);
                for (int i = 0; i < ROCKS; i++) {
                    long node = scene.create();
                    SceneBenchmark.place(scene, node, random, placed, i, 0);
                }
                scene.update();
                // The scene's materials: granite and grass, as SceneResources makes them.
                int granite = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                    .withBaseColor(new Color(0.5, 0.5, 0.52, 1)).withRoughness(0.8f)));
                int grass = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                    .withBaseColor(new Color(0.3, 0.5, 0.2, 1)).withRoughness(0.8f)));
                gpu.setMaterials(materials);
                gpu.set(0, groundMesh, grass, 0, -12, 0, 0, 0, 0, 1, 1, 1, 1);
                var world = scene.worldTransforms();
                for (int i = 0; i < ROCKS; i++) {
                    long o = (long) i * world.stride();
                    var s = world.segment();
                    gpu.set(i + 1, rockMesh, granite, (float) s.get(D, o), (float) s.get(D, o + 8),
                        (float) s.get(D, o + 16), (float) s.get(D, o + 24), (float) s.get(D, o + 32),
                        (float) s.get(D, o + 40), (float) s.get(D, o + 48), (float) s.get(D, o + 56),
                        (float) s.get(D, o + 64), (float) s.get(D, o + 72));
                }
            }
            Technique shading = visibility
                ? new VisibilityBufferShading(geometry, cameraView, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_FORMAT_D32_SFLOAT)
                : new ClusteredForwardShading(geometry, cameraView, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_FORMAT_D32_SFLOAT);
            ExternalImage output = new ExternalImage("target", WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
            graph.add(shading).output(Keys.SCENE_COLOR, output);
            long deadline = System.nanoTime() + 120_000_000_000L;
            int settled = 0;
            while (settled < 4) {
                int slot = graph.beginFrame();
                slots.begin(slot);
                tasks.drainCompleted();
                if (sync != null) {
                    scene.update();
                    renderables.update();
                    sync.update();
                    settled += resources.uploadedMeshCount() == 2 ? 1 : 0;
                } else {
                    settled++;
                }
                cameraView.set(camera, 1, MeshletRenderer.MODE_LIT);
                graph.submit(0, 0);
                slots.end();
                assertThat(System.nanoTime()).as("meshes loading").isLessThan(deadline);
                if (sync != null && resources.uploadedMeshCount() < 2) {
                    Thread.sleep(5);
                }
            }
            device.waitIdle();
            int meshlets = cameraView.visibleMeshlets();
            device.submitAndWait(device.graphics(), commands -> {
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.imageExtent().set(WIDTH, HEIGHT, 1);
                    vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        pixels.handle(), region);
                }
            });
            int[] image = new int[WIDTH * HEIGHT];
            for (int i = 0; i < image.length; i++) {
                image[i] = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i);
            }
            if (sync != null) {
                sync.close();
            }
            device.waitIdle();
            return new Drawn(image, meshlets);
        }
    }

    private static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE_UNALIGNED;

    /** Pixels differing by more than a little in any channel. */
    private static int differing(Drawn a, Drawn b) {
        int count = 0;
        for (int i = 0; i < a.pixels().length; i++) {
            int p = a.pixels()[i];
            int q = b.pixels()[i];
            for (int shift = 0; shift < 24; shift += 8) {
                if (Math.abs(((p >> shift) & 0xff) - ((q >> shift) & 0xff)) > 8) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /** Pixels not the sky's color (the top-left one's). */
    private static int drawnPixels(Drawn a) {
        int sky = a.pixels()[0];
        int count = 0;
        for (int p : a.pixels()) {
            count += p == sky ? 0 : 1;
        }
        return count;
    }

    private static void save(Drawn drawn, Path file) throws java.io.IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Function<Integer, Integer> rgb = p -> (p & 0xff) << 16 | (p & 0xff00) | (p >> 16 & 0xff);
        for (int i = 0; i < drawn.pixels().length; i++) {
            image.setRGB(i % WIDTH, i / WIDTH, rgb.apply(drawn.pixels()[i]));
        }
        ImageIO.write(image, "png", file.toFile());
    }
}
