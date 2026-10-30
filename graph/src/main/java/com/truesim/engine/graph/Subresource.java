package com.truesim.engine.graph;

import java.util.Objects;

/**
 * Part of an image: a range of mip levels of a range of array layers (cube faces). Passes declare accesses to parts, and
 * the graph tracks each part's state, so a pass may read one mip level while writing the next, or render into one
 * layer while others are sampled, with the barriers between them derived like any other.
 *
 * <pre>{@code
 * g.pass("bloom down " + i).read(bloom.mip(i - 1), Access.SAMPLED_COMPUTE).write(bloom.mip(i), Access.STORAGE_WRITE)
 * g.pass("cascade " + c).write(cascades.layer(c), Access.DEPTH_ATTACHMENT)
 * }</pre>
 *
 * @param image the image
 * @param baseMip the first mip level
 * @param mipCount how many mip levels
 * @param baseLayer the first array layer
 * @param layerCount how many array layers
 */
public record Subresource(Image image, int baseMip, int mipCount, int baseLayer, int layerCount) {

    public Subresource {
        Objects.requireNonNull(image, "image");
        if (baseMip < 0 || mipCount < 1 || baseMip + mipCount > image.mips || baseLayer < 0 || layerCount < 1
            || baseLayer + layerCount > image.desc.layers()) {
            throw new IllegalArgumentException("Mips " + baseMip + " to " + (baseMip + mipCount - 1) + " of layers "
                + baseLayer + " to " + (baseLayer + layerCount - 1) + " are not all in " + image + ": "
                + image.mips + " mips, " + image.desc.layers() + " layers");
        }
    }

    /** Whether it is all of its image. */
    public boolean whole() {
        return baseMip == 0 && mipCount == image.mips && baseLayer == 0 && layerCount == image.desc.layers();
    }

    /** Whether it is a single mip level. */
    public boolean oneMip() {
        return mipCount == 1;
    }

    /** Whether it is a single layer. */
    public boolean oneLayer() {
        return layerCount == 1;
    }

    @Override
    public String toString() {
        if (whole()) {
            return image.name;
        }
        return image.name + " mips " + baseMip + "-" + (baseMip + mipCount - 1) + " layers " + baseLayer + "-"
            + (baseLayer + layerCount - 1);
    }
}
