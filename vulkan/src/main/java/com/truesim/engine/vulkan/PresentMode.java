package com.truesim.engine.vulkan;

import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;

/** How a swapchain hands frames to the display. {@link #FIFO} is always available; others fall back to it. */
public enum PresentMode {
    /** Vsync: frames wait for the display's refresh. No tearing; the frame rate is capped at the refresh rate. */
    FIFO(VK_PRESENT_MODE_FIFO_KHR),
    /** Vsync, but a late frame is shown at once (may tear) instead of waiting another refresh. */
    FIFO_RELAXED(VK_PRESENT_MODE_FIFO_RELAXED_KHR),
    /** No tearing and no cap: the newest finished frame is shown at each refresh, older ones are dropped. */
    MAILBOX(VK_PRESENT_MODE_MAILBOX_KHR),
    /** No vsync: frames are shown as soon as they are ready. Lowest latency; may tear. */
    IMMEDIATE(VK_PRESENT_MODE_IMMEDIATE_KHR);

    final int value;

    PresentMode(int value) {
        this.value = value;
    }
}
