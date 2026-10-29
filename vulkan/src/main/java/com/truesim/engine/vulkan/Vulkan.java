package com.truesim.engine.vulkan;

import static org.lwjgl.vulkan.KHRSurface.VK_ERROR_SURFACE_LOST_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.VK10.VK_ERROR_DEVICE_LOST;
import static org.lwjgl.vulkan.VK10.VK_ERROR_EXTENSION_NOT_PRESENT;
import static org.lwjgl.vulkan.VK10.VK_ERROR_FEATURE_NOT_PRESENT;
import static org.lwjgl.vulkan.VK10.VK_ERROR_INCOMPATIBLE_DRIVER;
import static org.lwjgl.vulkan.VK10.VK_ERROR_INITIALIZATION_FAILED;
import static org.lwjgl.vulkan.VK10.VK_ERROR_LAYER_NOT_PRESENT;
import static org.lwjgl.vulkan.VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY;
import static org.lwjgl.vulkan.VK10.VK_ERROR_OUT_OF_HOST_MEMORY;
import static org.lwjgl.vulkan.VK10.VK_INCOMPLETE;
import static org.lwjgl.vulkan.VK10.VK_NOT_READY;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_TIMEOUT;

import com.truesim.annotations.AllowAllocation;

/** Small helpers shared by the Vulkan classes. */
public final class Vulkan {

    private Vulkan() {
    }

    /**
     * Throws a {@link VulkanException} unless {@code result} is {@code VK_SUCCESS}: a {@link DeviceLostException} for
     * {@code VK_ERROR_DEVICE_LOST}, after which nothing more runs on the device.
     */
    public static void check(int result, String what) {
        if (result != VK_SUCCESS) {
            throw result == VK_ERROR_DEVICE_LOST ? new DeviceLostException(what) : new VulkanException(what, result);
        }
    }

    /** Like {@link #check}, for calls listing things, which also return {@code VK_INCOMPLETE} when the list grew. */
    public static void checkList(int result, String what) {
        if (result != VK_INCOMPLETE) {
            check(result, what);
        }
    }

    /** The name of a {@code VkResult}, e.g. {@code VK_ERROR_DEVICE_LOST}. */
    @AllowAllocation("diagnostics")
    public static String resultName(int result) {
        return switch (result) {
            case VK_SUCCESS -> "VK_SUCCESS";
            case VK_NOT_READY -> "VK_NOT_READY";
            case VK_TIMEOUT -> "VK_TIMEOUT";
            case VK_SUBOPTIMAL_KHR -> "VK_SUBOPTIMAL_KHR";
            case VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            case VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
            case VK_ERROR_LAYER_NOT_PRESENT -> "VK_ERROR_LAYER_NOT_PRESENT";
            case VK_ERROR_EXTENSION_NOT_PRESENT -> "VK_ERROR_EXTENSION_NOT_PRESENT";
            case VK_ERROR_FEATURE_NOT_PRESENT -> "VK_ERROR_FEATURE_NOT_PRESENT";
            case VK_ERROR_INCOMPATIBLE_DRIVER -> "VK_ERROR_INCOMPATIBLE_DRIVER";
            case VK_ERROR_SURFACE_LOST_KHR -> "VK_ERROR_SURFACE_LOST_KHR";
            case VK_ERROR_OUT_OF_DATE_KHR -> "VK_ERROR_OUT_OF_DATE_KHR";
            default -> "VkResult " + result;
        };
    }

    /** {@code version} without its patch number, e.g. 1.4.0 for 1.4.309. */
    public static int withoutPatch(int version) {
        return version & ~0xfff;
    }

    /** The older of two Vulkan versions. */
    public static int min(int a, int b) {
        return Integer.compareUnsigned(a, b) <= 0 ? a : b;
    }

    /** A Vulkan version as {@code major.minor.patch}. */
    @AllowAllocation("diagnostics")
    public static String version(int version) {
        return (version >>> 22 & 0x7f) + "." + (version >>> 12 & 0x3ff) + "." + (version & 0xfff);
    }
}
