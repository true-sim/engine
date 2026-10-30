package com.truesim.engine.graph;

import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import java.util.Objects;

/**
 * What an image of the graph is: its format, size, array layers (cube faces, six per cube), samples per texel and mip
 * levels. Its usage is not part of it: the graph infers that from how passes use the image.
 *
 * <pre>{@code
 * Image hdr = g.create("hdr", ImageDesc.of(Format.R16G16B16A16_SFLOAT, 1920, 1080));
 * Image bloom = g.create("bloom", ImageDesc.like(hdr, Ratio.of(1, 2)).fullMips());
 * Image cascades = g.create("cascades", ImageDesc.of(Format.D32_SFLOAT, 2048, 2048).layers(4));
 * Image sky = g.create("sky", ImageDesc.of(Format.R16G16B16A16_SFLOAT, 256, 256).cube());
 * Image fog = g.create("fog", ImageDesc.of(Format.R16G16B16A16_SFLOAT, 160, 90).volume(64));
 * }</pre>
 *
 * @param format its format
 * @param width its width in texels
 * @param height its height in texels
 * @param depth its depth in texels: more than 1 for a 3D image
 * @param layers its array layers: six per cube map
 * @param cubeMap whether its layers are cube map faces
 * @param samples its samples per texel: more than 1 for a multisampled render target
 * @param mips its mip levels, each half the size of the one before
 */
public record ImageDesc(Format format, int width, int height, int depth, int layers, boolean cubeMap, int samples,
    int mips) {

    public ImageDesc {
        Objects.requireNonNull(format, "format");
        if (width < 1 || height < 1 || depth < 1 || layers < 1) {
            throw new IllegalArgumentException("An image's size and layers are positive: " + width + " x " + height
                + " x " + depth + ", " + layers + " layers");
        }
        if (depth > 1 && (layers > 1 || cubeMap || samples > 1)) {
            throw new IllegalArgumentException("A 3D image has one layer and one sample");
        }
        if (cubeMap && (width != height || layers % 6 != 0)) {
            throw new IllegalArgumentException("Cube maps are square, six layers each: " + width + " x " + height
                + ", " + layers + " layers");
        }
        if (Integer.bitCount(samples) != 1 || samples > 64 || samples > 1 && mips > 1) {
            throw new IllegalArgumentException("Samples are a power of two up to 64, with one mip level when more "
                + "than one: " + samples + " samples, " + mips + " mips");
        }
        int full = GpuImage.fullMipCount(Math.max(width, depth), height);
        if (mips < 1 || mips > full) {
            throw new IllegalArgumentException("A " + width + " x " + height + " image has 1 to " + full
                + " mip levels: " + mips);
        }
    }

    /** A 2D image of {@code format}, {@code width} by {@code height} texels: one layer, one sample, one mip level. */
    public static ImageDesc of(Format format, int width, int height) {
        return new ImageDesc(format, width, height, 1, 1, false, 1, 1);
    }

    /**
     * An image like {@code image} (its format, layers and samples) scaled by {@code scale}, at least 1 texel, with one
     * mip level.
     */
    public static ImageDesc like(Image image, Ratio scale) {
        ImageDesc desc = image.desc();
        return new ImageDesc(desc.format, scale.apply(desc.width), scale.apply(desc.height), desc.depth, desc.layers,
            desc.cubeMap, desc.samples, 1);
    }

    /** This image in {@code newFormat}. */
    public ImageDesc format(Format newFormat) {
        return new ImageDesc(newFormat, width, height, depth, layers, cubeMap, samples, mips);
    }

    /** This image with {@code count} mip levels. */
    public ImageDesc mips(int count) {
        return new ImageDesc(format, width, height, depth, layers, cubeMap, samples, count);
    }

    /** This image with every mip level, down to 1 x 1. */
    public ImageDesc fullMips() {
        return mips(GpuImage.fullMipCount(Math.max(width, depth), height));
    }

    /** This image with {@code count} array layers. */
    public ImageDesc layers(int count) {
        return new ImageDesc(format, width, height, depth, count, cubeMap, samples, mips);
    }

    /** This image as a cube map: six square faces. */
    public ImageDesc cube() {
        return cubes(1);
    }

    /** This image as {@code count} cube maps, six faces each. */
    public ImageDesc cubes(int count) {
        return new ImageDesc(format, width, height, depth, 6 * count, true, samples, mips);
    }

    /** This image as a 3D one, {@code texels} deep. */
    public ImageDesc volume(int texels) {
        return new ImageDesc(format, width, height, texels, layers, cubeMap, samples, mips);
    }

    /** This image with {@code count} samples per texel. */
    public ImageDesc samples(int count) {
        return new ImageDesc(format, width, height, depth, layers, cubeMap, count, mips);
    }

    /** Whether it is a plain 2D image: one layer, one sample, not a cube map or a 3D image. */
    public boolean flat() {
        return depth == 1 && layers == 1 && !cubeMap && samples == 1;
    }
}
