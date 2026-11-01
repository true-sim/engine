package com.truesim.engine.render;

import com.truesim.engine.render.layout.MaterialsConstants;

/**
 * How a material samples a texture: a texture as materials name it ({@link Textures#add}) is its slot in the low 16
 * bits and its sampler in bits 16-19, so each texture keeps its own; bit 20 picks the second UV set
 * ({@link #secondUv}) and bits 21-24 a texture transform ({@link #transform}), as materials.slang reads them. All filter
 * trilinearly and anisotropically but {@link #NEAREST}. In the order of engine-vulkan's {@code Bindless.MATERIAL}
 * samplers, from it.
 */
public enum TextureSampler {
    /** Repeating: tiling textures, the default. */
    REPEAT,
    /** Clamped to the edge: decals, textures covering their surface once. */
    CLAMP,
    /** Repeating mirrored: tiles without seams at their edges. */
    MIRROR,
    /** Nearest texel of the nearest level, repeating: textures meant blocky (pixel art). */
    NEAREST;

    private static final TextureSampler[] ALL = values();

    /** The texture transforms a material may have: {@link #transform} numbers them 1 to this. */
    public static final int TRANSFORMS = 8;

    private static final int SAMPLER_BITS = 0xf << 16;
    private static final int UV1 = MaterialsConstants.TEXTURE_UV1;
    private static final int TRANSFORM_SHIFT = MaterialsConstants.TEXTURE_TRANSFORM_SHIFT;

    /** Texture {@code texture} (as {@link Textures} returns them) sampled this way instead. */
    public int of(int texture) {
        return (texture & ~SAMPLER_BITS) | ordinal() << 16;
    }

    /** The sampler texture {@code texture} is sampled with. */
    public static TextureSampler from(int texture) {
        return ALL[(texture & SAMPLER_BITS) >>> 16];
    }

    /** Texture {@code texture} read at the second UV set (glTF's {@code TEXCOORD_1}) instead of the first. */
    public static int secondUv(int texture) {
        return texture | UV1;
    }

    /** Whether texture {@code texture} is read at the second UV set. */
    public static boolean usesSecondUv(int texture) {
        return (texture & UV1) != 0;
    }

    /**
     * Texture {@code texture} read at UVs moved by transform {@code n} (1 to {@link #TRANSFORMS}) of the material's
     * {@code TextureTransformParameters} feature; 0 for none.
     */
    public static int transform(int texture, int n) {
        if (n < 0 || n > TRANSFORMS) {
            throw new IllegalArgumentException("Texture transforms are 1 to " + TRANSFORMS + ", or 0: " + n);
        }
        return (texture & ~(0xf << TRANSFORM_SHIFT)) | n << TRANSFORM_SHIFT;
    }

    /** The transform texture {@code texture} is read with: 1 to {@link #TRANSFORMS}, 0 for none. */
    public static int transformOf(int texture) {
        return (texture >>> TRANSFORM_SHIFT) & 0xf;
    }
}
