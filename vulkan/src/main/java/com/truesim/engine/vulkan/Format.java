package com.truesim.engine.vulkan;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT;

import org.lwjgl.vulkan.VK10;

/**
 * Image formats, by Vulkan's names without the prefix ({@code R16G16B16A16_SFLOAT} is
 * {@code VK_FORMAT_R16G16B16A16_SFLOAT}): what engine APIs take instead of raw {@code VkFormat} numbers. Each knows its
 * aspects and the bytes of a texel (of a block, for compressed formats).
 */
public enum Format {

    R8_UNORM(VK10.VK_FORMAT_R8_UNORM, 1),
    R8G8_UNORM(VK10.VK_FORMAT_R8G8_UNORM, 2),
    R8G8B8A8_UNORM(VK10.VK_FORMAT_R8G8B8A8_UNORM, 4),
    R8G8B8A8_SRGB(VK10.VK_FORMAT_R8G8B8A8_SRGB, 4),
    B8G8R8A8_UNORM(VK10.VK_FORMAT_B8G8R8A8_UNORM, 4),
    B8G8R8A8_SRGB(VK10.VK_FORMAT_B8G8R8A8_SRGB, 4),
    A2B10G10R10_UNORM_PACK32(VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32, 4),
    B10G11R11_UFLOAT_PACK32(VK10.VK_FORMAT_B10G11R11_UFLOAT_PACK32, 4),
    R16_SFLOAT(VK10.VK_FORMAT_R16_SFLOAT, 2),
    R16G16_SFLOAT(VK10.VK_FORMAT_R16G16_SFLOAT, 4),
    R16G16B16A16_SFLOAT(VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 8),
    R16G16B16A16_UNORM(VK10.VK_FORMAT_R16G16B16A16_UNORM, 8),
    R32_SFLOAT(VK10.VK_FORMAT_R32_SFLOAT, 4),
    R32G32_SFLOAT(VK10.VK_FORMAT_R32G32_SFLOAT, 8),
    R32G32B32_SFLOAT(VK10.VK_FORMAT_R32G32B32_SFLOAT, 12),
    R32G32B32A32_SFLOAT(VK10.VK_FORMAT_R32G32B32A32_SFLOAT, 16),
    R32_UINT(VK10.VK_FORMAT_R32_UINT, 4),
    R32G32_UINT(VK10.VK_FORMAT_R32G32_UINT, 8),
    R32G32B32A32_UINT(VK10.VK_FORMAT_R32G32B32A32_UINT, 16),
    D16_UNORM(VK10.VK_FORMAT_D16_UNORM, 2),
    X8_D24_UNORM_PACK32(VK10.VK_FORMAT_X8_D24_UNORM_PACK32, 4),
    D32_SFLOAT(VK10.VK_FORMAT_D32_SFLOAT, 4),
    D16_UNORM_S8_UINT(VK10.VK_FORMAT_D16_UNORM_S8_UINT, 3),
    D24_UNORM_S8_UINT(VK10.VK_FORMAT_D24_UNORM_S8_UINT, 4),
    D32_SFLOAT_S8_UINT(VK10.VK_FORMAT_D32_SFLOAT_S8_UINT, 5),
    BC1_RGBA_SRGB_BLOCK(VK10.VK_FORMAT_BC1_RGBA_SRGB_BLOCK, 8),
    BC4_UNORM_BLOCK(VK10.VK_FORMAT_BC4_UNORM_BLOCK, 8),
    BC5_UNORM_BLOCK(VK10.VK_FORMAT_BC5_UNORM_BLOCK, 16),
    BC6H_UFLOAT_BLOCK(VK10.VK_FORMAT_BC6H_UFLOAT_BLOCK, 16),
    BC7_UNORM_BLOCK(VK10.VK_FORMAT_BC7_UNORM_BLOCK, 16),
    BC7_SRGB_BLOCK(VK10.VK_FORMAT_BC7_SRGB_BLOCK, 16);

    private static final Format[] ALL = values(); // values() copies its array each call

    private final int vk;
    private final int bytes;

    Format(int vk, int bytes) {
        this.vk = vk;
        this.bytes = bytes;
    }

    /** The {@code VkFormat}. */
    public int vk() {
        return vk;
    }

    /** The bytes of a texel, or of a 4 x 4 block of a compressed format. */
    public int bytes() {
        return bytes;
    }

    /** Whether it has depth. */
    public boolean depth() {
        return name().startsWith("D") || this == X8_D24_UNORM_PACK32;
    }

    /** Whether it has stencil. */
    public boolean stencil() {
        return name().endsWith("_S8_UINT");
    }

    /** Whether it is block compressed. */
    public boolean compressed() {
        return name().startsWith("BC");
    }

    /** Its aspects: color, or depth and stencil as it has them. */
    public int aspect() {
        if (!depth()) {
            return VK_IMAGE_ASPECT_COLOR_BIT;
        }
        return stencil() ? VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT : VK_IMAGE_ASPECT_DEPTH_BIT;
    }

    /**
     * The format of {@code VkFormat} {@code vk}, e.g. a swapchain's.
     *
     * @throws IllegalArgumentException if the engine has no such format
     */
    public static Format of(int vk) {
        for (Format format : ALL) {
            if (format.vk == vk) {
                return format;
            }
        }
        throw new IllegalArgumentException("No engine format for VkFormat " + vk);
    }
}
