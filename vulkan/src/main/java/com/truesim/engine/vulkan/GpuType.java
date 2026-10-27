package com.truesim.engine.vulkan;

import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_CPU;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU;

/** What kind of device a {@link Gpu} is, best first. */
public enum GpuType {
    DISCRETE, INTEGRATED, VIRTUAL, CPU, OTHER;

    /** The type of a {@code VkPhysicalDeviceType}. */
    static GpuType of(int deviceType) {
        return switch (deviceType) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> DISCRETE;
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> INTEGRATED;
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> VIRTUAL;
            case VK_PHYSICAL_DEVICE_TYPE_CPU -> CPU;
            default -> OTHER;
        };
    }
}
