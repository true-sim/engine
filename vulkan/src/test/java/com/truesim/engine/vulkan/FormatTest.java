package com.truesim.engine.vulkan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT;

import org.junit.jupiter.api.Test;

/** Formats carry their VkFormat, aspects and texel size, and are found by VkFormat. */
class FormatTest {

    @Test
    void formatsKnowWhatTheyAre() {
        assertThat(Format.R8G8B8A8_UNORM.aspect()).isEqualTo(VK_IMAGE_ASPECT_COLOR_BIT);
        assertThat(Format.R8G8B8A8_UNORM.bytes()).isEqualTo(4);
        assertThat(Format.D32_SFLOAT.depth()).isTrue();
        assertThat(Format.D32_SFLOAT.stencil()).isFalse();
        assertThat(Format.D32_SFLOAT.aspect()).isEqualTo(VK_IMAGE_ASPECT_DEPTH_BIT);
        assertThat(Format.D24_UNORM_S8_UINT.aspect()).isEqualTo(VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT);
        assertThat(Format.X8_D24_UNORM_PACK32.depth()).isTrue();
        assertThat(Format.BC7_SRGB_BLOCK.compressed()).isTrue();
        assertThat(Format.R16G16B16A16_SFLOAT.compressed()).isFalse();
        assertThat(Format.B10G11R11_UFLOAT_PACK32.depth()).isFalse();
        assertThat(Format.of(VK_FORMAT_D32_SFLOAT)).isEqualTo(Format.D32_SFLOAT);
        for (Format format : Format.values()) {
            assertThat(Format.of(format.vk())).isEqualTo(format);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> Format.of(VK_FORMAT_UNDEFINED))
            .withMessageContaining("No engine format");
    }
}
