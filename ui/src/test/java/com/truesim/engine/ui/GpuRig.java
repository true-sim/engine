package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.UiTextures;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * A headless device and a frame graph with one view whose result (16-bit floats) is read back after each frame: the UI
 * image itself ({@code Keys.UI}), or the display transform's result. Validation on; closing fails on any validation
 * error.
 */
final class GpuRig implements AutoCloseable {

    /** Where screenshots go, with {@code TRUESIM_SCREENSHOTS=true}: {@code TRUESIM_SCREENSHOTS_DIR}, or build/. */
    static final Path SCREENSHOTS = Path.of(System.getenv().getOrDefault("TRUESIM_SCREENSHOTS_DIR",
        "build/screenshots/ui"));

    final VulkanInstance instance;
    final VulkanDevice device;
    final FrameGraph graph;
    final ViewPort view;
    final int width;
    final int height;
    private final GpuImage target;
    private final GpuBuffer pixels;
    private final ExternalImage out;
    final float[] rgba;

    /** A rig whose view is {@code width x height}, or null (the test skipped) without a Vulkan 1.3 GPU. */
    static GpuRig open(int width, int height) {
        return open(width, height, true);
    }

    /** As {@link #open(int, int)}, with validation or without (benchmarks). */
    static GpuRig open(int width, int height, boolean validation) {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(validation)
                .synchronizationValidation(validation).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        if (instance.gpus().isEmpty()) {
            instance.close();
            assumeThat(false).as("no Vulkan 1.3 GPU").isTrue();
        }
        return new GpuRig(instance, width, height);
    }

    private GpuRig(VulkanInstance instance, int width, int height) {
        this.instance = instance;
        this.width = width;
        this.height = height;
        device = VulkanDevice.createHeadless(instance.bestGpu());
        graph = new FrameGraph(device, 2);
        view = graph.addView("main").size(width, height);
        target = new GpuImage(device, width, height, VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        pixels = GpuBuffer.hostVisible(device, (long) width * height * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        out = new ExternalImage("read back", width, height, Format.R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
        rgba = new float[width * height * 4];
    }

    /** Reads {@code key} of the view back after each frame. */
    GpuRig show(Key<Image> key) {
        view.output(key, out);
        return this;
    }

    /** Submits {@code layers} to {@code ui} and renders a frame, waited for and read back. */
    GpuRig frame(GpuUiRenderer ui, UiTextures textures, DrawList... layers) {
        ui.submit(layers, layers.length, textures, width, height);
        return frame();
    }

    /** Renders a frame, waited for, and reads the result back into {@link #rgba}. */
    GpuRig frame() {
        device.submitAndWait(device.graphics(), commands -> {
            graph.record(commands);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(width, height, 1);
                vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        for (int i = 0; i < rgba.length; i++) {
            rgba[i] = Float.float16ToFloat(pixels.memory().getAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, i));
        }
        return this;
    }

    /** Pixel {@code (x, y)}: red, green, blue, alpha. */
    float[] at(int x, int y) {
        int o = (y * width + x) * 4;
        return new float[] {rgba[o], rgba[o + 1], rgba[o + 2], rgba[o + 3]};
    }

    /** Channel {@code c} of pixel {@code (x, y)}. */
    float at(int x, int y, int c) {
        return rgba[(y * width + x) * 4 + c];
    }

    /**
     * Writes the read-back premultiplied UI over a checkerboard (or an opaque result as it is) as an sRGB PNG named
     * {@code name}, with {@code TRUESIM_SCREENSHOTS=true}.
     */
    void screenshot(String name) {
        screenshot(name, -1);
    }

    /**
     * As {@link #screenshot(String)}, over an opaque backdrop of linear grey level {@code backdrop} (negative: the
     * checkerboard), as a UI shows over a dark scene.
     */
    void screenshot(String name, float backdrop) {
        if (!"true".equals(System.getenv("TRUESIM_SCREENSHOTS"))) {
            return;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int o = (y * width + x) * 4;
                float a = Math.min(1, Math.max(0, rgba[o + 3]));
                float back = backdrop >= 0 ? backdrop : ((x / 16 + y / 16) & 1) == 0 ? 0.18f : 0.26f;
                int rgb = 0;
                for (int c = 0; c < 3; c++) {
                    float linear = rgba[o + c] + back * (1 - a);
                    rgb = rgb << 8 | (int) (srgb(Math.min(1, Math.max(0, linear))) * 255 + 0.5f);
                }
                image.setRGB(x, y, rgb);
            }
        }
        try {
            Path file = SCREENSHOTS.resolve(name + ".png"); // a name may name a folder: "ui-data/data-default"
            Files.createDirectories(file.getParent());
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static float srgb(float linear) {
        return linear <= 0.0031308f ? linear * 12.92f : (float) (1.055 * Math.pow(linear, 1 / 2.4) - 0.055);
    }

    @Override
    public void close() {
        device.waitIdle();
        graph.close();
        int errors = instance.validationErrors();
        pixels.close();
        target.close();
        device.close();
        instance.close();
        assertThat(errors).as("validation errors").isZero();
    }
}
