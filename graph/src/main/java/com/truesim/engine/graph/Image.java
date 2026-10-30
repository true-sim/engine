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

    @Override
    public String toString() {
        return name + " (" + width + " x " + height + ")";
    }
}
