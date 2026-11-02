package com.truesim.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Captures go through the display transform: a sky brighter than white comes out tone mapped by the ACES curve at the
 * graph's exposure and sRGB-encoded, not clipped, as a window shows it.
 */
class CaptureTest {

    private static final int SIZE = 64;

    @Test
    void capturesAreToneMappedAndSrgbEncoded(@TempDir Path folder) throws Exception {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            return;
        }
        float[] sky = {3, 0.5f, 0.05f}; // linear scene color, red far above white
        float exposure = 0.8f;
        try (instance; VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameGraph graph = new FrameGraph(device, 2);
             DemoScene world = new DemoScene(device, graph.frameSlots(), VK_FORMAT_R16G16B16A16_SFLOAT,
                 VK_FORMAT_D32_SFLOAT)) {
            graph.settings().set(DisplayTransform.EXPOSURE, exposure);
            Camera camera = new Camera();
            ViewPort port = graph.addView("main").size(SIZE, SIZE);
            SceneView view = world.geometry().createView(port, camera, ViewOptions.of(1 << 12, 1 << 20))
                .set(1, MeshletRenderer.MODE_LIT);
            port.add(new ClusteredForwardShading(world.geometry(), view, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_FORMAT_D32_SFLOAT).clearColor(sky[0], sky[1], sky[2])).add(new ClusteredLights(world.lights(), view));
            try (Capture capture = new Capture(device, port)) {
                for (int frame = 0; frame < 3; frame++) {
                    world.update(frame / 60.0);
                    camera.position(0, 40, 0).set(0, 1, 0, 0, 0, -1, Math.toRadians(30), SIZE, SIZE, 0.1); // the sky
                    graph.beginFrame();
                    graph.submit(0, 0);
                }
                device.waitIdle();
                capture.write(folder.resolve("sky.png"));
            }
            assertThat(instance.validationErrors()).isZero();
        }
        BufferedImage image = javax.imageio.ImageIO.read(folder.resolve("sky.png").toFile());
        int rgb = image.getRGB(SIZE / 2, SIZE / 2);
        int[] channels = {rgb >> 16 & 0xff, rgb >> 8 & 0xff, rgb & 0xff};
        for (int c = 0; c < 3; c++) {
            assertThat((double) channels[c]).as("channel " + c).isCloseTo(255 * srgb(aces(sky[c] * exposure)),
                within(2.0));
        }
        assertThat(channels[0]).as("rolled off, not clipped").isLessThan(250);
    }

    /** encode.slang's curve: Narkowicz's ACES fit, its input scaled by 0.6. */
    private static double aces(double c) {
        double x = c * 0.6;
        return Math.clamp(x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14), 0, 1);
    }

    private static double srgb(double linear) {
        return linear <= 0.0031308 ? 12.92 * linear : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
    }
}
