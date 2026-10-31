package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_ASTC_4x4_SRGB_BLOCK;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_ASTC_4x4_UNORM_BLOCK;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_ASTC_8x8_SRGB_BLOCK;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_ASTC_8x8_UNORM_BLOCK;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B10G11R11_UFLOAT_PACK32;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_BC1_RGBA_SRGB_BLOCK;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_BC1_RGBA_UNORM_BLOCK;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_BC3_SRGB_BLOCK;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_BC3_UNORM_BLOCK;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_BC4_UNORM_BLOCK;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_BC5_UNORM_BLOCK;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_BC6H_UFLOAT_BLOCK;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_BC7_SRGB_BLOCK;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_BC7_UNORM_BLOCK;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;

/**
 * The texel formats {@link Textures} holds: plain ones, which can have their mip chain made on the GPU, and block
 * compressed ones (BC on desktop GPUs, ASTC on mobile ones such as standalone headsets), which come with theirs. Texels
 * are rows top to bottom; compressed ones are blocks of {@link #blockWidth()} x {@link #blockHeight()} texels, rows of
 * blocks top to bottom. {@code _SRGB} formats hold colors, which the sampler returns linear; the others data.
 */
public enum TextureFormat {
    /** 4 bytes per texel, sRGB color and linear alpha. */
    RGBA8_SRGB(VK_FORMAT_R8G8B8A8_SRGB, 1, 1, 4),
    /** 4 bytes per texel, linear data (normals, occlusion-roughness-metalness). */
    RGBA8(VK_FORMAT_R8G8B8A8_UNORM, 1, 1, 4),
    /** 2 bytes per texel: two channels of linear data (a normal map's x and y). */
    RG8(VK_FORMAT_R8G8_UNORM, 1, 1, 2),
    /** 1 byte per texel: one channel of linear data (a mask, a height). */
    R8(VK_FORMAT_R8_UNORM, 1, 1, 1),
    /** 8 bytes per texel, half floats: high dynamic range color, linear. */
    RGBA16F(VK_FORMAT_R16G16B16A16_SFLOAT, 1, 1, 8),
    /** 4 bytes per texel, small unsigned floats (11, 11 and 10 bits): high dynamic range color without alpha. */
    RG11B10F(VK_FORMAT_B10G11R11_UFLOAT_PACK32, 1, 1, 4),
    /** BC1: 8 bytes per 4 x 4 block, sRGB color with 1-bit alpha. */
    BC1_SRGB(VK_FORMAT_BC1_RGBA_SRGB_BLOCK, 4, 4, 8),
    /** BC1 of linear data. */
    BC1(VK_FORMAT_BC1_RGBA_UNORM_BLOCK, 4, 4, 8),
    /** BC3: 16 bytes per 4 x 4 block, sRGB color and smooth alpha. */
    BC3_SRGB(VK_FORMAT_BC3_SRGB_BLOCK, 4, 4, 16),
    /** BC3 of linear data. */
    BC3(VK_FORMAT_BC3_UNORM_BLOCK, 4, 4, 16),
    /** BC4: 8 bytes per 4 x 4 block, one channel of linear data. */
    BC4(VK_FORMAT_BC4_UNORM_BLOCK, 4, 4, 8),
    /** BC5: 16 bytes per 4 x 4 block, two channels of linear data (normal maps). */
    BC5(VK_FORMAT_BC5_UNORM_BLOCK, 4, 4, 16),
    /** BC6H: 16 bytes per 4 x 4 block, unsigned half floats: high dynamic range color. */
    BC6H(VK_FORMAT_BC6H_UFLOAT_BLOCK, 4, 4, 16),
    /** BC7: 16 bytes per 4 x 4 block, sRGB color and alpha at high quality. */
    BC7_SRGB(VK_FORMAT_BC7_SRGB_BLOCK, 4, 4, 16),
    /** BC7 of linear data. */
    BC7(VK_FORMAT_BC7_UNORM_BLOCK, 4, 4, 16),
    /** ASTC: 16 bytes per 4 x 4 block (8 bits per texel), sRGB color and alpha. */
    ASTC_4X4_SRGB(VK_FORMAT_ASTC_4x4_SRGB_BLOCK, 4, 4, 16),
    /** ASTC 4 x 4 of linear data. */
    ASTC_4X4(VK_FORMAT_ASTC_4x4_UNORM_BLOCK, 4, 4, 16),
    /** ASTC: 16 bytes per 8 x 8 block (2 bits per texel), sRGB color and alpha. */
    ASTC_8X8_SRGB(VK_FORMAT_ASTC_8x8_SRGB_BLOCK, 8, 8, 16),
    /** ASTC 8 x 8 of linear data. */
    ASTC_8X8(VK_FORMAT_ASTC_8x8_UNORM_BLOCK, 8, 8, 16);

    private final int vulkan;
    private final int blockWidth;
    private final int blockHeight;
    private final int blockBytes;

    TextureFormat(int vulkan, int blockWidth, int blockHeight, int blockBytes) {
        this.vulkan = vulkan;
        this.blockWidth = blockWidth;
        this.blockHeight = blockHeight;
        this.blockBytes = blockBytes;
    }

    /** The Vulkan format. */
    public int vulkan() {
        return vulkan;
    }

    /** Texels across a block: 1 for plain formats. */
    public int blockWidth() {
        return blockWidth;
    }

    /** Texels down a block: 1 for plain formats. */
    public int blockHeight() {
        return blockHeight;
    }

    /** Bytes per block (per texel for plain formats). */
    public int blockBytes() {
        return blockBytes;
    }

    /** Whether texels come in blocks of several, compressed: such textures come with their mip chain. */
    public boolean compressed() {
        return blockWidth > 1;
    }

    /** Bytes of a {@code width} x {@code height} level: whole blocks, a partial one at the edges taking a full one. */
    public long levelBytes(int width, int height) {
        return (long) ((width + blockWidth - 1) / blockWidth) * ((height + blockHeight - 1) / blockHeight) * blockBytes;
    }

    /** Bytes of the first {@code mips} levels of a {@code width} x {@code height} texture, level after level. */
    public long chainBytes(int width, int height, int mips) {
        long bytes = 0;
        for (int mip = 0; mip < mips; mip++) {
            bytes += levelBytes(Math.max(1, width >> mip), Math.max(1, height >> mip));
        }
        return bytes;
    }
}
