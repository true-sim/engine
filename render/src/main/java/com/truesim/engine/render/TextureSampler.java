package com.truesim.engine.render;

/**
 * How a material samples a texture: a texture as materials name it ({@link Textures#add}) is its slot in the low 16
 * bits and its sampler above, so each texture keeps its own. All filter trilinearly and anisotropically but
 * {@link #NEAREST}. In the order of engine-vulkan's {@code Bindless.MATERIAL} samplers, from it.
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

    /** Texture {@code texture} (as {@link Textures} returns them) sampled this way instead. */
    public int of(int texture) {
        return (texture & 0xffff) | ordinal() << 16;
    }

    /** The sampler texture {@code texture} is sampled with. */
    public static TextureSampler from(int texture) {
        return ALL[texture >>> 16];
    }
}
