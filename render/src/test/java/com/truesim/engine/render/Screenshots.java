package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.Images;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Renders the benchmark's scenes to PNG files under {@code build/screenshots}: the rock field shaded and colored per
 * meshlet (clusters grow with distance: coarser levels), and the terrain colored per meshlet. Run with
 * {@code TRUESIM_SCREENSHOTS=true ./gradlew :engine-render:test --tests '*Screenshots'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class Screenshots {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    @Test
    void renderScenes() throws IOException {
        Path out = Files.createDirectories(Path.of("build", "screenshots"));
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("screenshots").surfaces(false)
                 .build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 100_000, 4));
             GpuImage color = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
             GpuImage depth = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_D32_SFLOAT,
                 VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, (long) WIDTH * HEIGHT * 4,
                 VK_BUFFER_USAGE_TRANSFER_DST_BIT);
             GpuScene gpu = new GpuScene(device, pool, 100_000);
             MeshletRenderer renderer = MeshletBenchmark.withView(new MeshletRenderer(device, gpu,
                 VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT), 80_000_000)) {
            int rock = pool.add(MeshletBenchmark.rock(6));
            int ground = pool.add(MeshletBenchmark.terrain(1024));
            Camera camera = new Camera();
            SplittableRandom random = new SplittableRandom(7);
            int count = 3000;
            for (int i = 0; i < count; i++) {
                double qx = random.nextDouble(-1, 1);
                double qy = random.nextDouble(-1, 1);
                double qz = random.nextDouble(-1, 1);
                double qw = random.nextDouble(-1, 1);
                double length = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
                float scale = (float) random.nextDouble(1, 6);
                renderer.scene().set(i, rock, (float) random.nextDouble(-300, 300),
                    (float) random.nextDouble(-150, 150), (float) random.nextDouble(-1500, -10),
                    (float) (qx / length), (float) (qy / length), (float) (qz / length), (float) (qw / length),
                    scale, scale, scale);
            }
            renderer.scene().removeFrom(count);
            camera.set(0, 0, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
            MeshletBenchmark.mainView(renderer).set(camera, 1, MeshletRenderer.MODE_LIT);
            save(device, renderer, color, depth, pixels, out.resolve("rocks-lit.png"));
            MeshletBenchmark.mainView(renderer).set(camera, 1, MeshletRenderer.MODE_MESHLETS);
            save(device, renderer, color, depth, pixels, out.resolve("rocks-meshlets.png"));
            // One rock up close at growing error: the coarse levels it falls back to.
            renderer.scene().set(0, rock, 0, 0, -3.2f, 0, 0, 0, 1, 1, 1, 1);
            renderer.scene().removeFrom(1);
            for (int error : new int[] {1, 8, 32}) {
                MeshletBenchmark.mainView(renderer).set(camera, error, MeshletRenderer.MODE_LIT);
                save(device, renderer, color, depth, pixels, out.resolve("rock-" + error + "px-lit.png"));
                MeshletBenchmark.mainView(renderer).set(camera, error, MeshletRenderer.MODE_MESHLETS);
                save(device, renderer, color, depth, pixels, out.resolve("rock-" + error + "px-meshlets.png"));
            }
            renderer.scene().set(0, ground, 0, -25, 0, 0, 0, 0, 1, 1, 1, 1);
            renderer.scene().removeFrom(1);
            MeshletBenchmark.mainView(renderer).set(camera.set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1), 1,
                MeshletRenderer.MODE_MESHLETS);
            save(device, renderer, color, depth, pixels, out.resolve("terrain-meshlets.png"));
            MeshletBenchmark.mainView(renderer).set(camera, 1, MeshletRenderer.MODE_LIT);
            save(device, renderer, color, depth, pixels, out.resolve("terrain-lit.png"));
        }
    }

    /**
     * The sun's shadows on rocks resting on the terrain, through the visibility buffer: by a shadow map (2048 x 2048
     * over a 150 unit square) and by inline ray queries (where the device traces rays); close-ups near the camera, and
     * where they differ (red: shadowed only by ray tracing, e.g. contact shadows the map misses; blue: only by the
     * map).
     */
    @Test
    void renderShadows() throws IOException {
        Path out = Files.createDirectories(Path.of("build", "screenshots"));
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("screenshots").surfaces(false)
                 .build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 100_000, 4));
             GpuImage target = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, usage,
                 VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, (long) WIDTH * HEIGHT * 4,
                 VK_BUFFER_USAGE_TRANSFER_DST_BIT);
             GpuScene gpu = new GpuScene(device, pool, 100_000);
             MeshletRenderer renderer = MeshletBenchmark.withView(new MeshletRenderer(device, gpu,
                 VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT), 20_000_000);
             RayTracing rayTracing = device.rayTracing() ? new RayTracing(device, pool, 100_000) : null) {
            com.truesim.scene.assets.mesh.MeshData rockMesh = MeshletBenchmark.rock(6);
            com.truesim.scene.assets.mesh.MeshData terrainMesh = MeshletBenchmark.terrain(1024);
            int rock = pool.add(rockMesh);
            int ground = pool.add(terrainMesh);
            double extent = 150;
            double groundY = -12;
            MeshletBenchmark.rocksOnTerrain(renderer, rock, ground, 3000, extent, groundY);
            Camera camera = new Camera().set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
            MeshletBenchmark.mainView(renderer).set(camera, 1, MeshletRenderer.MODE_LIT);
            com.truesim.engine.graph.ExternalImage external = new com.truesim.engine.graph.ExternalImage("target",
                WIDTH, HEIGHT, com.truesim.engine.vulkan.Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), 0);
            int size = 2048;
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            int[] mapped;
            int[] traced = null;
            int[] cascaded;
            try (Lights lights = new Lights(device, 1, 3);
                 com.truesim.engine.graph.FrameGraph graph = new com.truesim.engine.graph.FrameGraph(device, 2)) {
                lights.directional(0, -Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z, 3, 3, 3).setCount(1);
                com.truesim.engine.graph.Technique map = new com.truesim.engine.render.shadows.ShadowMapShadows(
                    geometry, lights, 0, 0, MeshletBenchmark.sun(extent, groundY, size), size,
                    geometry.createView(ViewOptions.shadow(1 << 18, 1 << 24)), MeshletBenchmark.sunBias(extent, size));
                com.truesim.engine.graph.ViewPort port = graph.addView("main").size(WIDTH, HEIGHT);
                SceneView main = new SceneView(port, MeshletBenchmark.mainView(renderer), camera);
                port.add(new VisibilityBufferShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT))
                    .add(new ClusteredLights(lights, main)).add(map).output(Keys.SCENE_COLOR, external);
                capture(device, graph, target, pixels); // the first frame's late phase draws everything
                mapped = capture(device, graph, target, pixels);
                write(mapped, out.resolve("shadows-mapped.png"));
                // Three cascades of 2048 x 2048 over the 150 units ahead instead of one map over all of it.
                com.truesim.engine.render.shadows.CascadedShadowMaps cascades =
                    new com.truesim.engine.render.shadows.CascadedShadowMaps(geometry, lights, 0, 0, camera, 3, size,
                        ViewOptions.shadow(1 << 18, 1 << 24), 150);
                cascades.update();
                port.remove(map).add(cascades);
                capture(device, graph, target, pixels);
                cascaded = capture(device, graph, target, pixels);
                write(cascaded, out.resolve("shadows-cascaded.png"));
                port.remove(cascades).add(map);
                if (rayTracing != null) {
                    rayTracing.add(rock, rockMesh, rockMesh.levels().size() - 1);
                    rayTracing.add(ground, terrainMesh, terrainMesh.levels().size() - 1);
                    port.remove(map).add(new com.truesim.engine.render.shadows.InlineRayShadows(
                        rayTracing, MeshletBenchmark.mainView(renderer), lights, 0, 0));
                    capture(device, graph, target, pixels);
                    traced = capture(device, graph, target, pixels);
                    write(traced, out.resolve("shadows-traced.png"));
                }
            }
            if (traced != null) {
                int[] difference = new int[traced.length];
                int differ = 0;
                for (int i = 0; i < difference.length; i++) {
                    int t = traced[i] & 0xff;
                    int m = mapped[i] & 0xff;
                    if (t + 48 < m) {
                        difference[i] = 0xff0000ff; // red: shadowed by ray tracing only
                        differ++;
                    } else if (m + 48 < t) {
                        difference[i] = 0xffff0000; // blue: shadowed by the map only
                        differ++;
                    } else {
                        int gray = t / 3;
                        difference[i] = 0xff000000 | gray << 16 | gray << 8 | gray;
                    }
                }
                write(difference, out.resolve("shadows-difference.png"));
                // Close-ups, 4 x, of the bottom middle: the nearest rocks and their contact shadows.
                int w = WIDTH / 8;
                int h = HEIGHT / 8;
                BufferedImage closeUp = new BufferedImage(12 * w + 16, 4 * h, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < 4 * h; y++) {
                    for (int x = 0; x < 4 * w; x++) {
                        int source = (HEIGHT - h - 40 + y / 4) * WIDTH + WIDTH / 2 - w / 2 + x / 4;
                        closeUp.setRGB(x, y, rgb(traced[source]));
                        closeUp.setRGB(4 * w + 8 + x, y, rgb(cascaded[source]));
                        closeUp.setRGB(8 * w + 16 + x, y, rgb(mapped[source]));
                    }
                }
                ImageIO.write(closeUp, "png", out.resolve("shadows-closeup.png").toFile());
                System.out.printf("shadows: %,d of %,d pixels differ (inline rays left, cascades middle, one map right in "
                    + "shadows-closeup.png); bottom levels %,d bytes%n", differ, difference.length,
                    rayTracing.bottomLevelBytes());
            }
        }
    }

    /** An RGBA texel (red lowest) as Java's RGB. */
    private static int rgb(int rgba) {
        return (rgba & 0xff) << 16 | ((rgba >> 8) & 0xff) << 8 | ((rgba >> 16) & 0xff);
    }

    /**
     * Many lights: 3,000 rocks on the terrain lit by 1,024 colored point lights through light clusters, shaded as they
     * are drawn (clustered forward) and through the visibility buffer, and where the two differ (amplified 8 x).
     */
    @Test
    void renderLights() throws IOException {
        Path out = Files.createDirectories(Path.of("build", "screenshots"));
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("screenshots").surfaces(false)
                 .build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 100_000, 4));
             GpuImage target = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, usage,
                 VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, (long) WIDTH * HEIGHT * 4,
                 VK_BUFFER_USAGE_TRANSFER_DST_BIT);
             GpuScene gpu = new GpuScene(device, pool, 100_000);
             MeshletRenderer renderer = MeshletBenchmark.withView(new MeshletRenderer(device, gpu,
                 VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT), 20_000_000);
             Lights lights = new Lights(device, 4096, 1)) {
            int rock = pool.add(MeshletBenchmark.rock(6));
            int ground = pool.add(MeshletBenchmark.terrain(1024));
            double groundY = -12;
            MeshletBenchmark.rocksOnTerrain(renderer, rock, ground, 3000, 150, groundY);
            Camera camera = new Camera().set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
            MeshletBenchmark.mainView(renderer).set(camera, 1, MeshletRenderer.MODE_LIT);
            MeshletBenchmark.scatterLights(lights, 1024, groundY, 3);
            com.truesim.engine.graph.ExternalImage external = new com.truesim.engine.graph.ExternalImage("target",
                WIDTH, HEIGHT, com.truesim.engine.vulkan.Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), 0);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            int[] forward;
            int[] visibility;
            try (com.truesim.engine.graph.FrameGraph graph = new com.truesim.engine.graph.FrameGraph(device, 2)) {
                SceneView main = new SceneView(graph.addView("main").size(WIDTH, HEIGHT),
                    MeshletBenchmark.mainView(renderer), camera);
                ClusteredForwardShading scene = new ClusteredForwardShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_FORMAT_D32_SFLOAT).clearColor(0.02f, 0.02f, 0.05f);
                main.port().add(scene).add(new ClusteredLights(lights, main)).output(Keys.SCENE_COLOR, external);
                capture(device, graph, target, pixels); // the first frame's late phase draws everything
                forward = capture(device, graph, target, pixels);
                write(forward, out.resolve("lights-forward.png"));
                main.port().replace(new VisibilityBufferShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_FORMAT_D32_SFLOAT).clearColor(0.02f, 0.02f, 0.05f));
                capture(device, graph, target, pixels);
                visibility = capture(device, graph, target, pixels);
                write(visibility, out.resolve("lights-visibility.png"));
            }
            int[] difference = new int[forward.length];
            long total = 0;
            for (int i = 0; i < forward.length; i++) {
                int pixel = 0xff000000;
                for (int shift = 0; shift < 24; shift += 8) {
                    int d = Math.abs(((forward[i] >> shift) & 0xff) - ((visibility[i] >> shift) & 0xff));
                    total += d;
                    pixel |= Math.min(255, 8 * d) << shift;
                }
                difference[i] = pixel;
            }
            write(difference, out.resolve("lights-difference.png"));
            System.out.printf("lights: mean difference %.3f of 255%n", total / (3.0 * forward.length));
        }
    }

    /**
     * Detail, two ways: 3,000 rocks as meshlets of a cluster hierarchy (a color per meshlet: detail varies across a
     * rock) and as discrete LODs drawn the classic way (a color per level: a whole rock switches), and the classic
     * rocks lit.
     */
    @Test
    void renderLods() throws IOException {
        Path out = Files.createDirectories(Path.of("build", "screenshots"));
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("screenshots").surfaces(false)
                 .build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 100_000, 4));
             GpuImage target = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, usage,
                 VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, (long) WIDTH * HEIGHT * 4,
                 VK_BUFFER_USAGE_TRANSFER_DST_BIT);
             GpuScene gpu = new GpuScene(device, pool, 100_000);
             MeshletRenderer renderer = MeshletBenchmark.withView(new MeshletRenderer(device, gpu,
                 VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT), 20_000_000);
             ClassicLodGeometry classic = new ClassicLodGeometry(device, renderer, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, 100_000)) {
            int rock = pool.add(MeshletBenchmark.rock(6));
            com.truesim.scene.assets.mesh.MeshData rockLods = MeshletBenchmark.rockBuilder(6).buildDiscrete(8, 0.5);
            int rockLod = pool.add(rockLods);
            int ground = pool.add(MeshletBenchmark.terrain(1024));
            classic.add(rockLod, rockLods);
            Camera camera = new Camera().set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
            com.truesim.engine.graph.ExternalImage external = new com.truesim.engine.graph.ExternalImage("target",
                WIDTH, HEIGHT, com.truesim.engine.vulkan.Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), 0);
            String[] names = {"lod-meshlets.png", "lod-classic.png", "lod-classic-lit.png"};
            for (int image = 0; image < names.length; image++) {
                boolean meshlets = image == 0;
                // The terrain's place takes a rock too: the terrain is a cluster hierarchy, which classic LOD skips.
                MeshletBenchmark.rocksOnTerrain(renderer, meshlets ? rock : rockLod, ground, 3000, 150, -12);
                renderer.scene().set(0, meshlets ? rock : rockLod, 0, -12, -12, 0, 0, 0, 1, 2, 2, 2);
                MeshletBenchmark.mainView(renderer).set(camera, 1, image == 2 ? MeshletRenderer.MODE_LIT : MeshletRenderer.MODE_MESHLETS);
                SceneGeometry geometry = meshlets ? SceneGeometry.meshlets(renderer) : new SceneGeometry(classic);
                try (com.truesim.engine.graph.FrameGraph graph = new com.truesim.engine.graph.FrameGraph(device, 2)) {
                    SceneView main = new SceneView(graph.addView("main").size(WIDTH, HEIGHT),
                        MeshletBenchmark.mainView(renderer), camera);
                    main.port().add(new ClusteredForwardShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM,
                        VK_FORMAT_D32_SFLOAT).clearColor(0.02f, 0.02f, 0.05f)).output(Keys.SCENE_COLOR, external);
                    capture(device, graph, target, pixels); // the first frame's late phase draws everything
                    write(capture(device, graph, target, pixels), out.resolve(names[image]));
                }
            }
        }
    }

    /** Records {@code graph}'s frame and reads {@code target} back, RGBA per texel. */
    private static int[] capture(VulkanDevice device, com.truesim.engine.graph.FrameGraph graph, GpuImage target,
        GpuBuffer pixels) {
        device.submitAndWait(device.graphics(), commands -> {
            graph.record(commands);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(WIDTH, HEIGHT, 1);
                vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        int[] rgba = new int[WIDTH * HEIGHT];
        for (int i = 0; i < rgba.length; i++) {
            rgba[i] = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i);
        }
        return rgba;
    }

    private static void write(int[] rgba, Path file) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int p = rgba[y * WIDTH + x];
                image.setRGB(x, y, (p & 0xff) << 16 | ((p >> 8) & 0xff) << 8 | ((p >> 16) & 0xff));
            }
        }
        ImageIO.write(image, "png", file.toFile());
    }

    private static void save(VulkanDevice device, MeshletRenderer renderer, GpuImage color, GpuImage depth,
        GpuBuffer pixels, Path file) throws IOException {
        device.submitAndWait(device.graphics(), commands -> {
            Images.transition(commands, color.handle(), VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            Images.transition(commands, depth.handle(), VK_IMAGE_ASPECT_DEPTH_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            renderer.record(commands, MeshletBenchmark.mainView(renderer), color.view(), depth.view(), WIDTH, HEIGHT, 0.45f, 0.55f, 0.65f);
            Images.transition(commands, color.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(WIDTH, HEIGHT, 1);
                vkCmdCopyImageToBuffer(commands, color.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int rgba = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, (long) y * WIDTH + x);
                int r = rgba & 0xff;
                int g = (rgba >> 8) & 0xff;
                int b = (rgba >> 16) & 0xff;
                image.setRGB(x, y, r << 16 | g << 8 | b);
            }
        }
        ImageIO.write(image, "png", file.toFile());
        System.out.printf("%s: %,d meshlets drawn%n", file.getFileName(),
            MeshletBenchmark.mainView(renderer).visibleMeshlets());
    }
}
