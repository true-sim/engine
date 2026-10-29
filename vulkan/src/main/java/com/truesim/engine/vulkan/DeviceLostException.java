package com.truesim.engine.vulkan;

import static org.lwjgl.vulkan.VK10.VK_ERROR_DEVICE_LOST;

import com.truesim.annotations.AllowAllocation;

/**
 * The GPU was lost ({@code VK_ERROR_DEVICE_LOST}): a fault in submitted work (an address out of range, a shader running
 * too long), a driver reset or the GPU removed. Nothing more runs on the device; close what was made on it and the
 * device, and make new ones to go on.
 */
@AllowAllocation("failures")
public final class DeviceLostException extends VulkanException {

    private static final long serialVersionUID = 1;

    public DeviceLostException(String message) {
        super(message, VK_ERROR_DEVICE_LOST);
    }
}
