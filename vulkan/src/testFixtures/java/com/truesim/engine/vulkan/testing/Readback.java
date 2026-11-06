package com.truesim.engine.vulkan.testing;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import java.awt.image.BufferedImage;
import java.lang.foreign.ValueLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Pixels of an 8-bit RGBA image (R8G8B8A8, UNORM or SRGB) back on the CPU, for tests: a host-visible buffer the size
 * of the image, a copy recorded into it ({@link #record}, after the frame, with the image in a layout copies read),
 * then the pixels read from it. One per image size; reusable frame after frame.
 *
 * <pre>{@code
 * try (Readback readback = new Readback(device, 64, 64)) {
 *     device.submitAndWait(device.graphics(), commands -> {
 *         graph.record(commands);
 *         readback.record(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
 *     });
 *     int[] center = readback.rgba(32, 32);
 *     BufferedImage shot = readback.image();          // for ReferenceImages
 * }
 * }</pre>
 */
public final class Readback implements AutoCloseable {

    private final int width;
    private final int height;
    private final GpuBuffer buffer;

    /** A readback of {@code width} x {@code height} pixels. */
    public Readback(VulkanDevice device, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("A size of pixels, not " + width + " x " + height);
        }
        this.width = width;
        this.height = height;
        buffer = GpuBuffer.hostVisible(device, 4L * width * height, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    /** Its width in pixels. */
    public int width() {
        return width;
    }

    /** Its height in pixels. */
    public int height() {
        return height;
    }

    /**
     * Records the copy of mip 0, layer 0 of {@code image} (in {@code layout}, which copies read: TRANSFER_SRC_OPTIMAL
     * or GENERAL) into this readback. The submission's wait makes it readable.
     */
    public void record(VkCommandBuffer commands, long image, int layout) {
        try (MemoryStack stack = stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.imageExtent().set(width, height, 1);
            vkCmdCopyImageToBuffer(commands, image, layout, buffer.handle(), region);
        }
    }

    /** Pixel ({@code x}, {@code y})'s packed value as copied: red in the lowest byte, alpha in the highest. */
    public int packed(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ") outside " + width + " x " + height);
        }
        return buffer.memory().getAtIndex(ValueLayout.JAVA_INT, (long) y * width + x);
    }

    /** Pixel ({@code x}, {@code y})'s red, green, blue and alpha, 0 to 255. */
    public int[] rgba(int x, int y) {
        int p = packed(x, y);
        return new int[] {p & 0xff, (p >>> 8) & 0xff, (p >>> 16) & 0xff, p >>> 24};
    }

    /** The pixels as an image (alpha kept), for {@link ReferenceImages} or a PNG of what a test saw. */
    public BufferedImage image() {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int p = packed(x, y);
                int argb = (p & 0xff000000) | (p & 0xff) << 16 | (p & 0xff00) | (p >>> 16) & 0xff;
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    @Override
    public void close() {
        buffer.close();
    }
}
