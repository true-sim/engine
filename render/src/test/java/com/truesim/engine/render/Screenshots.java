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
             MeshletRenderer renderer = new MeshletRenderer(device, pool, new MeshletRenderer.Limits(100_000,
                 1 << 20, 65_535, 80_000_000), VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)) {
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
                renderer.setInstance(i, rock, (float) random.nextDouble(-300, 300),
                    (float) random.nextDouble(-150, 150), (float) random.nextDouble(-1500, -10),
                    (float) (qx / length), (float) (qy / length), (float) (qz / length), (float) (qw / length),
                    scale, scale, scale);
            }
            renderer.setInstanceCount(count);
            camera.set(0, 0, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
            renderer.setView(camera, 1, MeshletRenderer.MODE_LIT);
            save(device, renderer, color, depth, pixels, out.resolve("rocks-lit.png"));
            renderer.setView(camera, 1, MeshletRenderer.MODE_MESHLETS);
            save(device, renderer, color, depth, pixels, out.resolve("rocks-meshlets.png"));
            // One rock up close at growing error: the coarse levels it falls back to.
            renderer.setInstance(0, rock, 0, 0, -3.2f, 0, 0, 0, 1, 1, 1, 1);
            renderer.setInstanceCount(1);
            for (int error : new int[] {1, 8, 32}) {
                renderer.setView(camera, error, MeshletRenderer.MODE_LIT);
                save(device, renderer, color, depth, pixels, out.resolve("rock-" + error + "px-lit.png"));
                renderer.setView(camera, error, MeshletRenderer.MODE_MESHLETS);
                save(device, renderer, color, depth, pixels, out.resolve("rock-" + error + "px-meshlets.png"));
            }
            renderer.setInstance(0, ground, 0, -25, 0, 0, 0, 0, 1, 1, 1, 1);
            renderer.setInstanceCount(1);
            renderer.setView(camera.set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1), 1,
                MeshletRenderer.MODE_MESHLETS);
            save(device, renderer, color, depth, pixels, out.resolve("terrain-meshlets.png"));
            renderer.setView(camera, 1, MeshletRenderer.MODE_LIT);
            save(device, renderer, color, depth, pixels, out.resolve("terrain-lit.png"));
        }
    }

    /**
     * Sun shadows on rocks resting on the terrain, ray-traced (where the device can) and shadow-mapped (2048 x 2048
     * over a 150 unit square): the shadowed images, both masks, close-ups of the masks near the camera, and where they
     * differ (red: shadowed only by ray tracing, e.g. contact shadows the map misses; blue: only by the map).
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
             MeshletRenderer renderer = new MeshletRenderer(device, pool, new MeshletRenderer.Limits(100_000,
                 1 << 20, 65_535, 20_000_000, 2), VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
             RayTracing rayTracing = device.rayTracing() ? new RayTracing(device, pool, 100_000) : null) {
            com.truesim.scene.assets.mesh.MeshData rockMesh = MeshletBenchmark.rock(6);
            com.truesim.scene.assets.mesh.MeshData terrainMesh = MeshletBenchmark.terrain(1024);
            int rock = pool.add(rockMesh);
            int ground = pool.add(terrainMesh);
            double extent = 150;
            double groundY = -12;
            MeshletBenchmark.rocksOnTerrain(renderer, rock, ground, 3000, extent, groundY);
            Camera camera = new Camera().set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
            renderer.setView(camera, 1, MeshletRenderer.MODE_LIT);
            com.truesim.engine.graph.ExternalImage external = new com.truesim.engine.graph.ExternalImage("target",
                WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), 0);
            int size = 2048;
            com.truesim.engine.graph.Technique mapped = new com.truesim.engine.render.shadows.ShadowMapShadows(
                renderer, camera, MeshletBenchmark.sun(extent, groundY, size), size, 1,
                MeshletBenchmark.sunBias(extent, size));
            int[] mappedMask;
            int[] tracedMask = null;
            try (com.truesim.engine.graph.FrameGraph graph = new com.truesim.engine.graph.FrameGraph(device, 2)) {
                graph.add(new MeshletScene(renderer, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT))
                    .add(mapped)
                    .add(new com.truesim.engine.render.shadows.ShadowedScene(0.3f))
                    .output(Keys.SHADOWED_COLOR, external);
                write(capture(device, graph, target, pixels), out.resolve("shadows-mapped.png"));
                graph.removeOutput(Keys.SHADOWED_COLOR).output(Keys.SHADOW_MASK, external);
                mappedMask = capture(device, graph, target, pixels);
                write(mappedMask, out.resolve("mask-mapped.png"));
                if (rayTracing != null) {
                    rayTracing.add(rock, rockMesh, rockMesh.levels().size() - 1);
                    rayTracing.add(ground, terrainMesh, terrainMesh.levels().size() - 1);
                    graph.replace(new com.truesim.engine.render.shadows.RayTracedShadows(rayTracing, renderer,
                        camera));
                    tracedMask = capture(device, graph, target, pixels);
                    write(tracedMask, out.resolve("mask-traced.png"));
                    graph.removeOutput(Keys.SHADOW_MASK).output(Keys.SHADOWED_COLOR, external);
                    write(capture(device, graph, target, pixels), out.resolve("shadows-traced.png"));
                }
            }
            if (tracedMask != null) {
                int[] difference = new int[tracedMask.length];
                int differ = 0;
                for (int i = 0; i < difference.length; i++) {
                    int traced = tracedMask[i] & 0xff;
                    int map = mappedMask[i] & 0xff;
                    if (traced + 64 < map) {
                        difference[i] = 0xff0000ff; // red: shadowed by ray tracing only
                        differ++;
                    } else if (map + 64 < traced) {
                        difference[i] = 0xffff0000; // blue: shadowed by the map only
                        differ++;
                    } else {
                        int gray = traced / 3;
                        difference[i] = 0xff000000 | gray << 16 | gray << 8 | gray;
                    }
                }
                write(difference, out.resolve("shadows-difference.png"));
                // Close-ups, 4 x, of the bottom middle: the nearest rocks and their contact shadows.
                int w = WIDTH / 8;
                int h = HEIGHT / 8;
                BufferedImage closeUp = new BufferedImage(8 * w + 8, 4 * h, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < 4 * h; y++) {
                    for (int x = 0; x < 4 * w; x++) {
                        int source = (HEIGHT - h - 40 + y / 4) * WIDTH + WIDTH / 2 - w / 2 + x / 4;
                        int traced = tracedMask[source] & 0xff;
                        int map = mappedMask[source] & 0xff;
                        closeUp.setRGB(x, y, traced << 16 | traced << 8 | traced);
                        closeUp.setRGB(4 * w + 8 + x, y, map << 16 | map << 8 | map);
                    }
                }
                ImageIO.write(closeUp, "png", out.resolve("masks-closeup.png").toFile());
                System.out.printf("shadows: %,d of %,d pixels differ (ray-traced left, shadow map right in "
                    + "masks-closeup.png); bottom levels %,d bytes%n", differ, difference.length,
                    rayTracing.bottomLevelBytes());
            }
        }
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
             MeshletRenderer renderer = new MeshletRenderer(device, pool, new MeshletRenderer.Limits(100_000,
                 1 << 20, 65_535, 20_000_000), VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
             Lights lights = new Lights(device, 4096, 1)) {
            int rock = pool.add(MeshletBenchmark.rock(6));
            int ground = pool.add(MeshletBenchmark.terrain(1024));
            double groundY = -12;
            MeshletBenchmark.rocksOnTerrain(renderer, rock, ground, 3000, 150, groundY);
            renderer.setView(new Camera().set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1), 1,
                MeshletRenderer.MODE_LIT);
            MeshletBenchmark.scatterLights(lights, 1024, groundY, 3);
            com.truesim.engine.graph.ExternalImage external = new com.truesim.engine.graph.ExternalImage("target",
                WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), 0);
            MeshletScene scene = new MeshletScene(renderer, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT).lights(lights).clearColor(0.02f, 0.02f, 0.05f);
            int[] forward;
            int[] visibility;
            try (com.truesim.engine.graph.FrameGraph graph = new com.truesim.engine.graph.FrameGraph(device, 2)) {
                graph.add(scene).output(Keys.SCENE_COLOR, external);
                capture(device, graph, target, pixels); // the first frame's late phase draws everything
                forward = capture(device, graph, target, pixels);
                write(forward, out.resolve("lights-forward.png"));
                scene.shading(MeshletScene.Shading.VISIBILITY_BUFFER);
                graph.invalidate();
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
            renderer.record(commands, color.view(), depth.view(), WIDTH, HEIGHT, 0.45f, 0.55f, 0.65f);
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
        System.out.printf("%s: %,d meshlets drawn%n", file.getFileName(), renderer.visibleMeshlets());
    }
}
