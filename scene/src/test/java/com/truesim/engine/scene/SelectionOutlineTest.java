package com.truesim.engine.scene;

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

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.SelectionHighlights;
import com.truesim.engine.render.SelectionOutline;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.Hovered;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.Selected;
import com.truesim.scene.render.Renderable;
import java.awt.image.BufferedImage;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * The selection outline drawn: three rocks on the ground, the left one selected, the right one hovered, in both
 * shadings and each kind (outline, tint, off), checked by the colours that show and saved as PNGs with
 * {@code TRUESIM_SCREENSHOTS=true} (in {@code TRUESIM_SCREENSHOTS_DIR}, or the build directory). With
 * {@code TRUESIM_BENCHMARK=true} it times the outline's passes at 1920 x 1080 and 3840 x 2160 over the benchmark's
 * 30,000 rocks (the GPU's own timestamps, medians of 120 frames).
 */
class SelectionOutlineTest {

    private static final boolean SCREENSHOTS = "true".equals(System.getenv("TRUESIM_SCREENSHOTS"));
    private static final boolean BENCHMARK = "true".equals(System.getenv("TRUESIM_BENCHMARK"));

    /** What a run drew: its pixels and the outline passes' median GPU times. */
    record Drawn(int[] pixels, double maskMillis, double outlineMillis, double frameMillis) {
    }

    @Test
    void selectedAndHoveredObjectsAreOutlinedOrTinted() throws Exception {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("outline").surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        try (instance; VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            int w = 480;
            int h = 270;
            boolean ids = device.primitiveIds();
            int[] tinted = new int[2];
            for (boolean visibility : ids ? new boolean[] {false, true} : new boolean[] {false}) {
                String shading = visibility ? "visibility" : "forward";
                Drawn outline = draw(device, w, h, visibility, SelectionOutline.OUTLINE, 3, 8);
                Drawn tint = draw(device, w, h, visibility, SelectionOutline.TINT, 3, 8);
                Drawn off = draw(device, w, h, visibility, null, 3, 8);
                save(outline, w, h, "selection-outline-" + shading + ".png");
                save(tint, w, h, "selection-tint-" + shading + ".png");
                save(off, w, h, "selection-off-" + shading + ".png");
                assertThat(count(off, SelectionOutlineTest::orange)).as("%s, off", shading).isZero();
                assertThat(count(off, SelectionOutlineTest::blue)).as("%s, off", shading).isZero();
                assertThat(count(outline, SelectionOutlineTest::orange)).as("%s: the selected rock's outline", shading)
                    .isGreaterThan(100);
                assertThat(count(outline, SelectionOutlineTest::blue)).as("%s: the hovered rock's outline", shading)
                    .isGreaterThan(100);
                assertThat(differing(outline, off)).as("%s: only around the rocks", shading).isLessThan(w * h / 10);
                assertThat(differing(tint, off)).as("%s: the tint covers the rocks", shading).isGreaterThan(500);
                tinted[visibility ? 1 : 0] = differing(tint, off);
            }
            if (ids) {
                // the forward view's IDs hold the highlighted alone, depth tested: the same pixels as the visibility
                // buffer's, the part of the selected rock behind the front one left out in both
                assertThat((double) tinted[0]).as("forward against visibility buffer").isCloseTo(tinted[1],
                    org.assertj.core.data.Offset.offset(tinted[1] * 0.05));
            }
            if (BENCHMARK) {
                benchmark(device);
            }
        }
    }

    /** How many of the benchmark's rocks are highlighted: 0, 1 (selected), 300 or 3,000 (one of them hovered). */
    private static int highlighted = 300;

    private static void benchmark(VulkanDevice device) throws InterruptedException {
        System.out.printf(Locale.ROOT, "Selection outline on %s, 30,000 rocks:%n", device.gpu().name());
        for (int[] size : new int[][] {{1920, 1080}, {3840, 2160}}) {
            for (boolean visibility : device.primitiveIds() ? new boolean[] {false, true} : new boolean[] {false}) {
                for (int run = 0; run < 6; run++) {
                    var kind = run == 0 ? null : run == 5 ? SelectionOutline.TINT : SelectionOutline.OUTLINE;
                    highlighted = run <= 1 ? 0 : run == 2 ? 1 : run == 3 ? 300 : 3000;
                    Drawn d = draw(device, size[0], size[1], visibility, kind, 30_000, 160);
                    System.out.printf(Locale.ROOT, "  %d x %d, %s, %s, %d highlighted: mask %.3f ms, outline %.3f ms, "
                        + "frame %.3f ms%n", size[0], size[1], visibility ? "visibility buffer" : "forward",
                        kind == null ? "off" : kind.id(), highlighted, d.maskMillis(), d.outlineMillis(),
                        d.frameMillis());
                }
            }
        }
        highlighted = 300;
    }

    /**
     * Draws {@code rocks} rocks (three in a row for the checks, else the benchmark's field) on the ground, the first
     * selected and the last hovered (every hundredth selected in the field), through {@code kind} (null: off), for
     * {@code frames} frames after the meshes loaded.
     */
    private static Drawn draw(VulkanDevice device, int width, int height, boolean visibility,
        com.truesim.engine.settings.TechniqueKind<?, SelectionOutline, Technique> kind, int rocks, int frames)
        throws InterruptedException {
        FrameSlots slots = new FrameSlots(2);
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 100_000, 4), slots);
             Materials materials = new Materials(device, 64, slots);
             Textures textures = new Textures(device, slots);
             TaskScheduler tasks = new TaskScheduler();
             AssetCache assets = new AssetCache(tasks);
             SceneResources resources = new SceneResources(assets, pool, materials, textures);
             GpuScene gpu = new GpuScene(device, pool, rocks + 64, slots);
             MeshletRenderer renderer = new MeshletRenderer(device, gpu, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, slots);
             GpuImage target = new GpuImage(device, width, height, VK_FORMAT_R8G8B8A8_UNORM, usage,
                 VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, 4L * width * height, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
             FrameGraph graph = new FrameGraph(device, slots)) {
            graph.timings(true);
            Camera camera = new Camera().position(0, 0, 0).set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, width, height, 0.1);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            ViewPort port = graph.addView("main").size(width, height);
            SceneView view = geometry.createView(port, camera, ViewOptions.of(1 << 20, 80_000_000).withOcclusion(true));
            SceneGraph scene = new SceneGraph(new ChangeClock(), rocks + 64, SceneGraph.DEFAULT_LOG_CAPACITY);
            ComponentStore<Renderable> renderables = scene.components().store(Renderable.TYPE);
            long ground = scene.create();
            scene.setTranslation(ground, 0, -12, 0);
            renderables.set(ground, Renderable.of(SceneBenchmark.GROUND, List.of(SceneBenchmark.GRASS)));
            long[] nodes = new long[rocks];
            if (rocks == 3) {
                for (int i = 0; i < 3; i++) {
                    nodes[i] = scene.create();
                    scene.setTranslation(nodes[i], -9 + 9 * i, -6, -26);
                    scene.setScale(nodes[i], 3, 3, 3);
                    renderables.set(nodes[i], Renderable.of(SceneBenchmark.ROCK, List.of(SceneBenchmark.GRANITE)));
                }
                scene.components().set(nodes[0], Selected.TYPE, Selected.YES);
                scene.components().set(nodes[2], Hovered.TYPE, Hovered.YES);
                long front = scene.create(); // hides part of the selected rock: only what shows is highlighted
                scene.setTranslation(front, -5.5, -5, -17);
                scene.setScale(front, 2, 2, 2);
                renderables.set(front, Renderable.of(SceneBenchmark.ROCK, List.of(SceneBenchmark.GRANITE)));
            } else {
                SplittableRandom random = new SplittableRandom(11);
                double[] placed = new double[3 * rocks];
                for (int i = 0; i < rocks; i++) {
                    nodes[i] = scene.create();
                    SceneBenchmark.place(scene, nodes[i], random, placed, i, 0);
                    renderables.set(nodes[i], Renderable.of(SceneBenchmark.ROCK, List.of(SceneBenchmark.GRANITE)));
                    if (highlighted == 300 && i % 100 == 0 || highlighted == 3000 && i % 10 == 0
                        || highlighted == 1 && i == 0) {
                        scene.components().set(nodes[i], Selected.TYPE, Selected.YES);
                    }
                }
                if (highlighted >= 300) {
                    scene.components().set(nodes[1], Hovered.TYPE, Hovered.YES);
                }
            }
            SceneSync sync = new SceneSync(resources, scene, gpu);
            SelectionHighlights highlights = new SelectionHighlights();
            SceneHighlights fill = new SceneHighlights(sync, highlights);
            Technique shading = visibility
                ? new VisibilityBufferShading(geometry, view, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0.4f, 0.55f, 0.6f)
                : new ClusteredForwardShading(geometry, view, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
            com.truesim.engine.render.RenderView idsView = visibility ? view.view()
                : renderer.createView(ViewOptions.of(1 << 20, 80_000_000).withOcclusion(true));
            SelectionOutline outline = new SelectionOutline(graph, geometry, idsView, view, VK_FORMAT_D32_SFLOAT,
                highlights);
            SelectionOutline.SLOT.choose(graph.settings(), kind);
            ExternalImage output = new ExternalImage("target", width, height, Format.R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
            port.add(shading).add(outline).output(Keys.SCENE_COLOR, output);
            long deadline = System.nanoTime() + 300_000_000_000L;
            int settled = 0;
            double[] masks = new double[frames];
            double[] outlines = new double[frames];
            double[] totals = new double[frames];
            int timed = 0;
            while (settled < frames) {
                int slot = graph.beginFrame();
                slots.begin(slot);
                tasks.drainCompleted();
                scene.update();
                sync.update();
                fill.update();
                boolean loaded = resources.uploadedMeshCount() == 2;
                settled += loaded ? 1 : 0;
                view.set(1, MeshletRenderer.MODE_LIT);
                if (!visibility) {
                    idsView.set(camera, 1, MeshletRenderer.MODE_LIT);
                }
                graph.submit(0, 0);
                slots.end();
                if (loaded && settled > frames / 4) {
                    GpuTimings t = graph.timings();
                    masks[timed] = millis(t, "selection mask");
                    outlines[timed] = millis(t, "selection outline");
                    totals[timed++] = t.totalMillis();
                }
                assertThat(System.nanoTime()).as("meshes loading").isLessThan(deadline);
                if (!loaded) {
                    Thread.sleep(5);
                }
            }
            device.waitIdle();
            device.submitAndWait(device.graphics(), commands -> {
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.imageExtent().set(width, height, 1);
                    vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        pixels.handle(), region);
                }
            });
            int[] image = new int[width * height];
            for (int i = 0; i < image.length; i++) {
                image[i] = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i);
            }
            sync.close();
            outline.close();
            device.waitIdle();
            return new Drawn(image, median(masks, timed), median(outlines, timed), median(totals, timed));
        }
    }

    private static double millis(GpuTimings t, String pass) {
        for (int i = 0; i < t.count(); i++) {
            if (t.pass(i).equals(pass)) {
                return t.millis(i);
            }
        }
        return 0;
    }

    private static double median(double[] values, int n) {
        if (n == 0) {
            return Double.NaN;
        }
        double[] sorted = java.util.Arrays.copyOf(values, n);
        java.util.Arrays.sort(sorted);
        return sorted[n / 2];
    }

    /** The selection's orange (R8G8B8A8 in memory: red lowest). */
    private static boolean orange(int p) {
        int r = p & 0xff;
        int g = p >> 8 & 0xff;
        int b = p >> 16 & 0xff;
        return r > 200 && g > 60 && g < 170 && b < 90;
    }

    /** The hover's light blue. */
    private static boolean blue(int p) {
        int r = p & 0xff;
        int g = p >> 8 & 0xff;
        int b = p >> 16 & 0xff;
        return b > 200 && g > 110 && g < 200 && r < 120;
    }

    private static int count(Drawn d, java.util.function.IntPredicate test) {
        int n = 0;
        for (int p : d.pixels()) {
            n += test.test(p) ? 1 : 0;
        }
        return n;
    }

    private static int differing(Drawn a, Drawn b) {
        int n = 0;
        for (int i = 0; i < a.pixels().length; i++) {
            n += a.pixels()[i] != b.pixels()[i] ? 1 : 0;
        }
        return n;
    }

    private static void save(Drawn drawn, int width, int height, String name) throws java.io.IOException {
        if (!SCREENSHOTS) {
            return;
        }
        Path out = Path.of(System.getenv().getOrDefault("TRUESIM_SCREENSHOTS_DIR", "build/selection"));
        Files.createDirectories(out);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < drawn.pixels().length; i++) {
            int p = drawn.pixels()[i];
            image.setRGB(i % width, i / width, (p & 0xff) << 16 | (p & 0xff00) | (p >> 16 & 0xff));
        }
        ImageIO.write(image, "png", out.resolve(name).toFile());
    }
}
