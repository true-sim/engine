package com.truesim.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.common.math.Maths;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.ReferencePathTracer;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.shadows.InlineRayShadows;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * The demo's scene from where the demo starts, path traced as ground truth (TRUESIM_SCREENSHOTS=true): its rocks,
 * terrain, the sun and the point lights, the constant sky; beside it the visibility buffer's image with inline ray
 * shadows, the real-time lighting it approximates. Into build/screenshots: reference-demo.png and raster-demo.png.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class ReferenceScreenshots {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int SAMPLES = Integer.getInteger("truesim.reference.samples", 1024);

    @Test
    void theDemoScenePathTraced() throws IOException {
        Path out = Files.createDirectories(Path.of("build", "screenshots"));
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("screenshots").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameGraph graph = new FrameGraph(device, 2);
             DemoScene world = new DemoScene(device, graph.frameSlots(), VK_FORMAT_R32G32B32A32_SFLOAT,
                 VK_FORMAT_D32_SFLOAT, true);
             GpuImage target = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R32G32B32A32_SFLOAT, usage,
                 VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, (long) WIDTH * HEIGHT * 16,
                 VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            assertThat(world.rayTracing()).as("ray tracing").isNotNull();
            ExternalImage output = new ExternalImage("target", WIDTH, HEIGHT, Format.R32G32B32A32_SFLOAT, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
            Camera camera = new Camera();
            double yaw = -2.4;
            double pitch = -0.25;
            camera.position(40, 14, 40).set(Maths.cos(pitch) * Maths.sin(yaw), Maths.sin(pitch),
                Maths.cos(pitch) * Maths.cos(yaw), 0, 1, 0, 70 * Math.PI / 180, WIDTH, HEIGHT, 0.1);
            ViewPort port = graph.addView("main").size(WIDTH, HEIGHT);
            SceneView view = world.geometry().createView(port, camera, ViewOptions.of(1 << 14, 1 << 22)
                .withOcclusion(true)).set(1, MeshletRenderer.MODE_LIT);
            ReferencePathTracer reference = new ReferencePathTracer(world.geometry(), world.rayTracing(), view,
                VK_FORMAT_R32G32B32A32_SFLOAT);
            graph.settings().set(ReferencePathTracer.SAMPLES, SAMPLES);
            port.add(reference).add(new ClusteredLights(world.lights(), view)).output(Keys.SCENE_COLOR, output);
            long start = System.nanoTime();
            for (int frame = 0; frame < SAMPLES; frame++) {
                graph.beginFrame();
                graph.submit(0, 0);
            }
            device.waitIdle();
            double seconds = (System.nanoTime() - start) / 1e9;
            write(device, target, pixels, out.resolve("reference-demo.png"));
            assertThat(reference.samples()).isEqualTo(SAMPLES);
            System.out.printf("The demo's scene path traced: %d x %d, %d samples in %.1f s (%.1f ms a sample)%n",
                WIDTH, HEIGHT, SAMPLES, seconds, seconds * 1000 / SAMPLES);
            // The real-time image of the same view: the visibility buffer, the sun's shadows traced inline.
            port.replace(new VisibilityBufferShading(world.geometry(), view, VK_FORMAT_R32G32B32A32_SFLOAT,
                VK_FORMAT_D32_SFLOAT).clearColor(0.2405f, 0.2405f, 0.2405f));
            port.remove(reference);
            port.add(new InlineRayShadows(world.rayTracing(), view.view(), world.lights(), 0, 0));
            for (int frame = 0; frame < 4; frame++) {
                graph.beginFrame();
                graph.submit(0, 0);
            }
            device.waitIdle();
            write(device, target, pixels, out.resolve("raster-demo.png"));
            assertThat(instance.validationErrors()).isZero();
        }
    }

    /**
     * The demo's motion vectors as it starts: the camera stepping forward and turning a little, the spinning rocks
     * turning, the circling lights' fox (none here) aside; motion-demo.png shows each pixel's motion as a color, its
     * direction the hue and its length the brightness (16 pixels: full), beside the frame (motion-demo-color.png).
     */
    @Test
    void theDemoScenesMotionVectors() throws IOException {
        Path out = Files.createDirectories(Path.of("build", "screenshots"));
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("screenshots").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameGraph graph = new FrameGraph(device, 2);
             DemoScene world = new DemoScene(device, graph.frameSlots(), VK_FORMAT_R32G32B32A32_SFLOAT,
                 VK_FORMAT_D32_SFLOAT, false);
             GpuImage target = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R32G32B32A32_SFLOAT, usage,
                 VK_IMAGE_ASPECT_COLOR_BIT);
             GpuImage motionImage = new GpuImage(device, WIDTH, HEIGHT,
                 org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_SFLOAT, usage, VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, (long) WIDTH * HEIGHT * 16,
                 VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            Camera camera = new Camera();
            ViewPort port = graph.addView("main").size(WIDTH, HEIGHT);
            SceneView view = world.geometry().createView(port, camera, ViewOptions.of(1 << 14, 1 << 22)
                .withOcclusion(true)).set(1, MeshletRenderer.MODE_LIT);
            port.add(new VisibilityBufferShading(world.geometry(), view, VK_FORMAT_R32G32B32A32_SFLOAT,
                VK_FORMAT_D32_SFLOAT).clearColor(0.2405f, 0.2405f, 0.2405f))
                .add(new ClusteredLights(world.lights(), view))
                .add(new com.truesim.engine.render.MotionVectors(world.geometry(), view.view(), view,
                    VK_FORMAT_D32_SFLOAT))
                .output(Keys.SCENE_COLOR, new ExternalImage("target", WIDTH, HEIGHT, Format.R32G32B32A32_SFLOAT,
                    usage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .set(target.handle(), target.view()))
                .output(Keys.MOTION, new ExternalImage("motion", WIDTH, HEIGHT, Format.R16G16_SFLOAT, usage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .set(motionImage.handle(), motionImage.view()));
            for (int frame = 0; frame < 4; frame++) {
                double t = frame * 0.25; // a quarter of a second a frame: motion large enough to see
                world.update(t);
                double yaw = -2.4 + 0.01 * frame;
                double pitch = -0.25;
                camera.position(40 - 0.5 * frame, 14, 40 - 0.5 * frame).set(Maths.cos(pitch) * Maths.sin(yaw),
                    Maths.sin(pitch), Maths.cos(pitch) * Maths.cos(yaw), 0, 1, 0, 70 * Math.PI / 180, WIDTH, HEIGHT,
                    0.1);
                graph.beginFrame();
                graph.submit(0, 0);
            }
            device.waitIdle();
            write(device, target, pixels, out.resolve("motion-demo-color.png"));
            device.submitAndWait(device.graphics(), commands -> {
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.imageExtent().set(WIDTH, HEIGHT, 1);
                    vkCmdCopyImageToBuffer(commands, motionImage.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        pixels.handle(), region);
                }
            });
            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            double largest = 0;
            for (int p = 0; p < WIDTH * HEIGHT; p++) {
                double mx = Float.float16ToFloat(pixels.memory().getAtIndex(ValueLayout.JAVA_SHORT, 2L * p));
                double my = Float.float16ToFloat(pixels.memory().getAtIndex(ValueLayout.JAVA_SHORT, 2L * p + 1));
                double length = Math.hypot(mx, my);
                largest = Math.max(largest, length);
                float hue = (float) ((Math.atan2(my, mx) / (2 * Math.PI) + 1) % 1);
                image.setRGB(p % WIDTH, p / WIDTH, java.awt.Color.HSBtoRGB(hue, 0.8f,
                    (float) Math.min(1, length / 16)));
            }
            ImageIO.write(image, "png", out.resolve("motion-demo.png").toFile());
            System.out.printf("Motion vectors of the demo's scene: the largest %.1f pixels%n", largest);
            assertThat(instance.validationErrors()).isZero();
        }
    }

    private static void write(VulkanDevice device, GpuImage target, GpuBuffer pixels, Path file) throws IOException {
        device.submitAndWait(device.graphics(), commands -> {
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(WIDTH, HEIGHT, 1);
                vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        float[] rgba = pixels.memory().asSlice(0, (long) WIDTH * HEIGHT * 16).toArray(ValueLayout.JAVA_FLOAT);
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int p = y * WIDTH + x;
                int rgb = 0;
                for (int c = 0; c < 3; c++) {
                    double v = Math.max(0, rgba[4 * p + c]);
                    if (v > 0.8) {
                        double over = v - 0.8;
                        v = 0.8 + 0.2 * over / (0.2 + over); // a soft shoulder: lights keep their color
                    }
                    double srgb = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
                    rgb = rgb << 8 | (int) Math.round(Math.min(1, srgb) * 255);
                }
                image.setRGB(x, y, rgb);
            }
        }
        ImageIO.write(image, "png", file.toFile());
    }
}
