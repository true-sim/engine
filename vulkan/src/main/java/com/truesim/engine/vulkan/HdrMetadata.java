package com.truesim.engine.vulkan;

import static org.lwjgl.vulkan.EXTHdrMetadata.VK_STRUCTURE_TYPE_HDR_METADATA_EXT;

import org.lwjgl.vulkan.VkHdrMetadataEXT;

/**
 * What an HDR swapchain tells the display about its content ({@code VK_EXT_hdr_metadata}), so the display maps it to
 * what it can show rather than guessing: the brightest the content gets and its average. The primaries are the
 * encoding's (Rec. 2020 for HDR10, Rec. 709 for scRGB), with a D65 white point. Brightness is in nits.
 *
 * @param maxLuminance the brightest the content was graded for (the mastering display's peak)
 * @param minLuminance the darkest
 * @param maxContentLight the brightest pixel of the content (MaxCLL)
 * @param maxFrameAverageLight the brightest frame's average (MaxFALL)
 */
public record HdrMetadata(float maxLuminance, float minLuminance, float maxContentLight, float maxFrameAverageLight) {

    public HdrMetadata {
        if (!(maxLuminance > 0) || minLuminance < 0 || minLuminance >= maxLuminance || !(maxContentLight > 0)
            || !(maxFrameAverageLight > 0)) {
            throw new IllegalArgumentException("HDR metadata out of range: " + maxLuminance + ", " + minLuminance
                + ", " + maxContentLight + ", " + maxFrameAverageLight);
        }
    }

    /** Fills {@code into} for images of {@code encoding} (HDR10 or scRGB). Allocates nothing. */
    void fill(VkHdrMetadataEXT into, DisplayEncoding encoding) {
        into.sType(VK_STRUCTURE_TYPE_HDR_METADATA_EXT);
        if (encoding == DisplayEncoding.HDR10_PQ) {
            into.displayPrimaryRed().set(0.708f, 0.292f);
            into.displayPrimaryGreen().set(0.170f, 0.797f);
            into.displayPrimaryBlue().set(0.131f, 0.046f);
        } else {
            into.displayPrimaryRed().set(0.640f, 0.330f);
            into.displayPrimaryGreen().set(0.300f, 0.600f);
            into.displayPrimaryBlue().set(0.150f, 0.060f);
        }
        into.whitePoint().set(0.3127f, 0.3290f);
        into.maxLuminance(maxLuminance).minLuminance(minLuminance).maxContentLightLevel(maxContentLight)
            .maxFrameAverageLightLevel(maxFrameAverageLight);
    }
}
