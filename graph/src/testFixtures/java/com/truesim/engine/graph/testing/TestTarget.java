package com.truesim.engine.graph.testing;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.testing.Readback;
import java.awt.image.BufferedImage;

/**
 * What a test's frame graph draws into, read back: an 8-bit RGBA image the size of the view, as an
 * {@link ExternalImage} a view's output goes to ({@link #output()}), left in TRANSFER_SRC_OPTIMAL for the copy that
 * {@link #frame} records after the graph. Pixels are read from it after each frame.
 *
 * <pre>{@code
 * try (TestGpu gpu = TestGpu.open(); FrameGraph graph = new FrameGraph(gpu.device(), 2);
 *      TestTarget target = new TestTarget(gpu.device(), 64, 64)) {
 *     view.port().add(myTechnique).output(MY_KEY, target.output());
 *     target.frames(graph, 3);                                     // occlusion culling settles over a few
 *     assertThat(target.rgba(32, 32)[0]).isGreaterThan(200);
 *     references.assertMatches("my-technique", target.shot(), 3, 0.001);
 * }
 * }</pre>
 */
public final class TestTarget implements AutoCloseable {

    /** What the image can be used as: drawn into, cleared, copied from (and written by compute, but for SRGB). */
    public static final int USAGE = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
        | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    private final VulkanDevice device;
    private final GpuImage image;
    private final ExternalImage output;
    private final Readback readback;

    /** A target of {@code width} x {@code height} R8G8B8A8_UNORM pixels. */
    public TestTarget(VulkanDevice device, int width, int height) {
        this(device, width, height, Format.R8G8B8A8_UNORM);
    }

    /** A target of {@code width} x {@code height} pixels of {@code format}: an 8-bit RGBA one (UNORM or SRGB). */
    public TestTarget(VulkanDevice device, int width, int height, Format format) {
        this.device = device;
        int usage = format == Format.R8G8B8A8_UNORM ? USAGE | VK_IMAGE_USAGE_STORAGE_BIT : USAGE;
        image = new GpuImage(device, width, height, format.vk(), usage, VK_IMAGE_ASPECT_COLOR_BIT);
        output = new ExternalImage("test target", width, height, format, usage, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(image.handle(), image.view());
        readback = new Readback(device, width, height);
    }

    /** The image as a frame graph output: a view's {@code port().output(key, target.output())}. */
    public ExternalImage output() {
        return output;
    }

    /** The image itself. */
    public GpuImage image() {
        return image;
    }

    /** Records a frame of {@code graph}, copies the target back and waits for both. */
    public void frame(FrameGraph graph) {
        device.submitAndWait(device.graphics(), commands -> {
            graph.record(commands);
            readback.record(commands, image.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        });
    }

    /** {@code count} frames, as {@link #frame}: what the last one drew is read back. */
    public void frames(FrameGraph graph, int count) {
        for (int i = 0; i < count; i++) {
            frame(graph);
        }
    }

    /** Pixel ({@code x}, {@code y}) of the last frame: red, green, blue and alpha, 0 to 255. */
    public int[] rgba(int x, int y) {
        return readback.rgba(x, y);
    }

    /** The last frame as an image, for reference images or a PNG. */
    public BufferedImage shot() {
        return readback.image();
    }

    @Override
    public void close() {
        readback.close();
        image.close();
    }
}
