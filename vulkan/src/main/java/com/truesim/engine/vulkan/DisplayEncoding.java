package com.truesim.engine.vulkan;

/**
 * How a swapchain's images reach the display ({@link Swapchain#encoding()}): what an output pass writes for each.
 */
public enum DisplayEncoding {
    /** Standard dynamic range: linear colors from 0 to 1, sRGB-encoded by the image format on write. */
    SDR_SRGB(false),
    /** HDR10: Rec. 2020 primaries, encoded by the PQ curve (SMPTE ST 2084) from absolute brightness in nits. */
    HDR10_PQ(true),
    /** scRGB: linear Rec. 709 primaries in floats, 1.0 being 80 nits and brighter colors above it. */
    SCRGB_LINEAR(true);

    private final boolean hdr;

    DisplayEncoding(boolean hdr) {
        this.hdr = hdr;
    }

    /** Whether the display shows brighter than SDR white. */
    public boolean hdr() {
        return hdr;
    }
}
