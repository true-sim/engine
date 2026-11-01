package com.truesim.engine.render;

import com.truesim.engine.render.layout.MaterialsConstants;

/**
 * How a material samples a texture: how it wraps along each axis ({@link Wrap}, glTF's {@code wrapS} and
 * {@code wrapT}) and whether it filters (trilinearly and anisotropically) or takes the nearest texel of the nearest
 * level. A texture as materials name it ({@link Textures#add}) is its slot in the low 16 bits and its sampler's
 * {@link #index} in bits 16-20, so each texture keeps its own; bit 21 picks the second UV set ({@link #secondUv}) and
 * bits 22-25 a texture transform ({@link #transform}), as materials.slang reads them. The index is that of
 * engine-vulkan's {@code Bindless} material samplers, from {@code Bindless.MATERIAL}.
 *
 * @param wrapS   along the texture's width (u)
 * @param wrapT   along its height (v)
 * @param nearest the nearest texel of the nearest level, rather than filtered
 */
public record TextureSampler(Wrap wrapS, Wrap wrapT, boolean nearest) {

    /** How a sampler reads past a texture's edge along one axis. */
    public enum Wrap {
        /** Repeating: tiling, the default. */
        REPEAT,
        /** Clamped to the edge texel: decals, textures covering their surface once. */
        CLAMP,
        /** Repeating mirrored: tiles without seams at their edges. */
        MIRROR
    }

    /** Repeating, filtered: tiling textures, the default. */
    public static final TextureSampler REPEAT = new TextureSampler(Wrap.REPEAT, Wrap.REPEAT, false);
    /** Clamped to the edge, filtered: decals, textures covering their surface once. */
    public static final TextureSampler CLAMP = new TextureSampler(Wrap.CLAMP, Wrap.CLAMP, false);
    /** Repeating mirrored, filtered: tiles without seams at their edges. */
    public static final TextureSampler MIRROR = new TextureSampler(Wrap.MIRROR, Wrap.MIRROR, false);
    /** Nearest texel of the nearest level, repeating: textures meant blocky (pixel art). */
    public static final TextureSampler NEAREST = new TextureSampler(Wrap.REPEAT, Wrap.REPEAT, true);

    /** How many samplers there are: every wrap along each axis, filtered and nearest. */
    public static final int COUNT = 18;
    private static final TextureSampler[] ALL = new TextureSampler[COUNT];

    static {
        Wrap[] wraps = Wrap.values();
        for (int i = 0; i < COUNT; i++) {
            ALL[i] = new TextureSampler(wraps[i % 9 / 3], wraps[i % 3], i >= 9);
        }
    }

    /** The texture transforms a material may have: {@link #transform} numbers them 1 to this. */
    public static final int TRANSFORMS = 8;

    private static final int SAMPLER_SHIFT = 16;
    private static final int SAMPLER_BITS = 0x1f << SAMPLER_SHIFT;
    private static final int UV1 = MaterialsConstants.TEXTURE_UV1;
    private static final int TRANSFORM_SHIFT = MaterialsConstants.TEXTURE_TRANSFORM_SHIFT;

    public TextureSampler {
        java.util.Objects.requireNonNull(wrapS, "wrapS");
        java.util.Objects.requireNonNull(wrapT, "wrapT");
    }

    /** Its number among the samplers, as texture references hold it: nearest ones after the filtered. */
    public int index() {
        return (nearest ? 9 : 0) + wrapS.ordinal() * 3 + wrapT.ordinal();
    }

    /** Texture {@code texture} (as {@link Textures} returns them) sampled this way instead. */
    public int of(int texture) {
        return (texture & ~SAMPLER_BITS) | index() << SAMPLER_SHIFT;
    }

    /** The sampler texture {@code texture} is sampled with. */
    public static TextureSampler from(int texture) {
        return ALL[(texture & SAMPLER_BITS) >>> SAMPLER_SHIFT];
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
