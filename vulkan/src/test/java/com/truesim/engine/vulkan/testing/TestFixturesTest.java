package com.truesim.engine.vulkan.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdClearColorImage;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.Images;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkImageSubresourceRange;

/** The published GPU test fixtures: a test GPU, pixels read back, reference images. */
class TestFixturesTest {

    @Test
    void aClearedImageReadsBackAsItsColor() {
        try (TestGpu gpu = TestGpu.open()) {
            GpuImage image = gpu.closing(new GpuImage(gpu.device(), 8, 4, VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT));
            Readback readback = gpu.closing(new Readback(gpu.device(), 8, 4));
            gpu.device().submitAndWait(gpu.device().graphics(), commands -> {
                Images.transition(commands, image.handle(), VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, VK_ACCESS_2_NONE,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
                try (MemoryStack stack = stackPush()) {
                    VkClearColorValue color = VkClearColorValue.calloc(stack).float32(0, 1).float32(1, 0.6f)
                        .float32(2, 0).float32(3, 1);
                    VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    vkCmdClearColorImage(commands, image.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, range);
                }
                Images.transition(commands, image.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_TRANSFER_READ_BIT);
                readback.record(commands, image.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            });
            assertThat(readback.rgba(7, 3)).containsExactly(255, 153, 0, 255);
            assertThat(readback.image().getRGB(0, 0)).isEqualTo(0xffff9900);
            gpu.assertNoValidationErrors("clearing and reading back");
        }
    }

    @Test
    void referenceImagesPassWithinTheirToleranceAndShowWhatDiffers(@TempDir Path dir) throws Exception {
        BufferedImage reference = solid(16, 16, 0x336699);
        ReferenceImages writer = new ReferenceImages(dir.resolve("refs"), dir.resolve("diffs"), true);
        writer.assertMatches("solid", reference, 0, 0);
        assertThat(dir.resolve("refs/solid.png")).isRegularFile();
        ReferenceImages references = new ReferenceImages(dir.resolve("refs"), dir.resolve("diffs"), false);
        BufferedImage near = solid(16, 16, 0x356799); // off by 2 in red and 1 in green
        assertThat(references.assertMatches("solid", near, 2, 0).worst()).isEqualTo(2);
        BufferedImage spot = solid(16, 16, 0x336699);
        spot.setRGB(3, 3, 0xffffffff);
        assertThat(references.assertMatches("solid", spot, 2, 0.01).differing()).isOne();
        assertThatThrownBy(() -> references.assertMatches("solid", spot, 2, 0))
            .isInstanceOf(AssertionError.class).hasMessageContaining("1 of 256 pixels");
        assertThat(dir.resolve("diffs/solid.diff.png")).isRegularFile();
        assertThat(dir.resolve("diffs/solid.actual.png")).isRegularFile();
        assertThatThrownBy(() -> references.assertMatches("missing", spot, 2, 0))
            .isInstanceOf(AssertionError.class).hasMessageContaining(ReferenceImages.UPDATE);
        assertThat(Files.list(dir.resolve("refs"))).hasSize(1);
    }

    private static BufferedImage solid(int width, int height, int rgb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xff000000 | rgb);
            }
        }
        return image;
    }
}
