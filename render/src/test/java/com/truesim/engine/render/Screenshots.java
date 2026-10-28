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
