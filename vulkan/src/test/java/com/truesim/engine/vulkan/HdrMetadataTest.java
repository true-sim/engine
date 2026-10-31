package com.truesim.engine.vulkan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;
import static org.lwjgl.system.MemoryStack.stackPush;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkHdrMetadataEXT;

class HdrMetadataTest {

    @Test
    void theEncodingsPrimariesAndTheBrightnessAreTold() {
        HdrMetadata metadata = new HdrMetadata(1000, 0.001f, 900, 200);
        try (MemoryStack stack = stackPush()) {
            VkHdrMetadataEXT told = VkHdrMetadataEXT.calloc(stack);
            metadata.fill(told, DisplayEncoding.HDR10_PQ);
            assertThat(told.displayPrimaryGreen().y()).as("Rec. 2020").isCloseTo(0.797f, within(1e-6f));
            assertThat(told.whitePoint().x()).isCloseTo(0.3127f, within(1e-6f));
            assertThat(told.maxLuminance()).isEqualTo(1000);
            assertThat(told.minLuminance()).isEqualTo(0.001f);
            assertThat(told.maxContentLightLevel()).isEqualTo(900);
            assertThat(told.maxFrameAverageLightLevel()).isEqualTo(200);
            metadata.fill(told, DisplayEncoding.SCRGB_LINEAR);
            assertThat(told.displayPrimaryRed().x()).as("Rec. 709").isCloseTo(0.64f, within(1e-6f));
        }
    }

    @Test
    void brightnessOutOfRangeIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> new HdrMetadata(0, 0, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new HdrMetadata(100, -1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new HdrMetadata(100, 100, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new HdrMetadata(100, 0, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new HdrMetadata(100, 0, 1, 0));
    }
}
