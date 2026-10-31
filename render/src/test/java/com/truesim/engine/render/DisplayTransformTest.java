package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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

import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

class DisplayTransformTest {

    private static final int SIZE = 8;
    private static final Key<Image> SCENE = Key.image("test.scene");
    private static final Key<Image> SHOWN = Key.image("test.shown");

    private VulkanInstance instance;
    private VulkanDevice device;
    private GpuImage target;
    private GpuBuffer pixels;

    @BeforeEach
    void start() {
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        pixels = GpuBuffer.hostVisible(device, SIZE * SIZE * 8L, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            pixels.close();
            target.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** The scene's linear color, {@code rgb} everywhere, in 16-bit floats. */
    private record Scene(float red, float green, float blue) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(SCENE);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.create("scene", ImageDesc.of(Format.R16G16B16A16_SFLOAT, SIZE, SIZE));
            g.pass("scene").write(image, Access.TRANSFER_DST)
                .record(ctx -> ctx.clear(image.whole(), red, green, blue, 1));
            g.provide(SCENE, image);
        }
    }

    /** The first pixel's red, green and blue after transforming scene color {@code rgb} for {@code encoding}. */
    private float[] shown(DisplayEncoding encoding, DisplayTransform.Curve curve, float... rgb) {
        try (FrameGraph graph = new FrameGraph(device, 2)) {
            graph.settings().set(DisplayTransform.CURVE, curve);
            ExternalImage out = new ExternalImage("shown", SIZE, SIZE, Format.R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
            graph.addView("main").size(SIZE, SIZE).add(new Scene(rgb[0], rgb[1], rgb[2]))
                .add(new DisplayTransform().transform(SCENE, SHOWN, encoding)).output(SHOWN, out);
            device.submitAndWait(device.graphics(), commands -> {
                graph.record(commands);
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.imageExtent().set(SIZE, SIZE, 1);
                    vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        pixels.handle(), region);
                }
            });
            float[] color = new float[3];
            for (int c = 0; c < 3; c++) {
                color[c] = Float.float16ToFloat(pixels.memory().get(ValueLayout.JAVA_SHORT_UNALIGNED, 2L * c));
            }
            return color;
        }
    }

    /** SMPTE ST 2084 of {@code nits}, as the shader should compute it. */
    private static double pq(double nits) {
        double y = Math.pow(nits / 10000, 0.1593017578125);
        return Math.pow((0.8359375 + 18.8515625 * y) / (1 + 18.6875 * y), 78.84375);
    }

    @Test
    void sdrToneMapsIntoTheDisplaysRange() {
        assertThat(shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.NONE, 0.5f, 2, 0.25f))
            .as("clipped at white").containsExactly(new float[] {0.5f, 1, 0.25f}, within(2e-3f));
        assertThat(shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.REINHARD, 1, 3, 0))
            .containsExactly(new float[] {0.5f, 0.75f, 0}, within(2e-3f));
        float[] aces = shown(DisplayEncoding.SDR_SRGB, DisplayTransform.Curve.ACES, 0.18f, 1, 16);
        assertThat(aces[0]).as("middle gray stays darker than white").isBetween(0.1f, 0.3f);
        assertThat(aces[1]).as("white: below 1, the shoulder").isBetween(0.6f, 0.75f);
        assertThat(aces[2]).as("bright: near white, not beyond").isBetween(0.98f, 1f);
    }

    @Test
    void hdrShowsWhiteAtPaperWhiteAndEncodesItForTheDisplay() {
        // Paper white 200 nits, peak 1000 (the defaults); gray stays gray in Rec. 2020.
        float[] hdr10 = shown(DisplayEncoding.HDR10_PQ, DisplayTransform.Curve.NONE, 1, 1, 1);
        assertThat(hdr10[0]).as("SDR white at 200 nits, PQ-encoded").isCloseTo((float) pq(200), within(2e-3f));
        assertThat(hdr10[1]).isCloseTo(hdr10[0], within(2e-3f));
        float[] bright = shown(DisplayEncoding.HDR10_PQ, DisplayTransform.Curve.NONE, 10, 10, 10);
        assertThat(bright[0]).as("clipped at the peak").isCloseTo((float) pq(1000), within(2e-3f));
        float[] scrgb = shown(DisplayEncoding.SCRGB_LINEAR, DisplayTransform.Curve.NONE, 1, 0.5f, 0);
        assertThat(scrgb).as("200 nits over 80").containsExactly(new float[] {2.5f, 1.25f, 0}, within(5e-3f));
        float[] rolled = shown(DisplayEncoding.SCRGB_LINEAR, DisplayTransform.Curve.REINHARD, 5, 5, 5);
        assertThat(rolled[0]).as("five times white: rolled off below the peak").isCloseTo(1000 * 0.5f / 80,
            within(2e-2f));
        assertThat(DisplayTransform.ALL).containsExactly(DisplayTransform.CURVE, DisplayTransform.EXPOSURE,
            DisplayTransform.PAPER_WHITE, DisplayTransform.PEAK);
    }

    @Test
    void hdrDisplaysAreToldThePeakAndPaperWhite() {
        com.truesim.engine.settings.EngineSettings settings = new com.truesim.engine.settings.EngineSettings();
        assertThat(new DisplayTransform().metadata(settings))
            .isEqualTo(new com.truesim.engine.vulkan.HdrMetadata(1000, 0.0001f, 1000, 200));
        settings.set(DisplayTransform.PEAK, 400f).set(DisplayTransform.PAPER_WHITE, 600f);
        assertThat(new DisplayTransform().metadata(settings).maxFrameAverageLight()).as("never above the peak")
            .isEqualTo(400);
    }
}
