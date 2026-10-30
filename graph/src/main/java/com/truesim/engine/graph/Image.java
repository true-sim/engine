package com.truesim.engine.graph;

import com.truesim.engine.vulkan.Format;

/**
 * An image of the graph: made by a technique (transient, its memory pooled and shared with images whose lifetimes do
 * not overlap) or an {@link ExternalImage} brought in. A handle is valid for the build that made it; passes resolve it
 * through their {@link PassContext}.
 */
public final class Image {

    final int id;
    final String name;
    final ImageDesc desc;
    final int width;
    final int height;
    final Format format;
    final int mips;

    Image(int id, String name, ImageDesc desc) {
        this.id = id;
        this.name = name;
        this.desc = desc;
        this.width = desc.width();
        this.height = desc.height();
        this.format = desc.format();
        this.mips = desc.mips();
    }

    public String name() {
        return name;
    }

    /** What it is: format, size, layers, samples, mip levels. */
    public ImageDesc desc() {
        return desc;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Mip levels: 1, or more for a pyramid (each half the size of the one before). */
    public int mips() {
        return mips;
    }

    public Format format() {
        return format;
    }

    /** All of it. */
    public Subresource whole() {
        return new Subresource(this, 0, mips, 0, desc.layers());
    }

    /** Mip level {@code level} of every layer. */
    public Subresource mip(int level) {
        return new Subresource(this, level, 1, 0, desc.layers());
    }

    /** {@code count} mip levels from {@code first}, of every layer. */
    public Subresource mips(int first, int count) {
        return new Subresource(this, first, count, 0, desc.layers());
    }

    /** Array layer {@code index}, every mip level. */
    public Subresource layer(int index) {
        return new Subresource(this, 0, mips, index, 1);
    }

    /** {@code count} array layers from {@code first}, every mip level. */
    public Subresource layers(int first, int count) {
        return new Subresource(this, 0, mips, first, count);
    }

    /** Face {@code face} (0 to 5: +x, -x, +y, -y, +z, -z) of cube map {@code cube}. */
    public Subresource face(int cube, int face) {
        if (!desc.cubeMap() || face < 0 || face > 5) {
            throw new IllegalArgumentException(name + " has no face " + face + ": " + (desc.cubeMap() ? "faces are 0 to 5"
                : "not a cube map"));
        }
        return layer(6 * cube + face);
    }

    @Override
    public String toString() {
        return name + " (" + width + " x " + height + ")";
    }
}
