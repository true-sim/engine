package com.truesim.engine.vulkan;

import com.truesim.annotations.AllowAllocation;

/** A Vulkan call failed. {@link #result()} is the {@code VkResult}. */
@AllowAllocation("failures")
public class VulkanException extends RuntimeException {

    private static final long serialVersionUID = 1;

    private final int result;

    public VulkanException(String message, int result) {
        super(message + ": " + Vulkan.resultName(result));
        this.result = result;
    }

    public VulkanException(String message) {
        super(message);
        this.result = 0;
    }

    public int result() {
        return result;
    }
}
