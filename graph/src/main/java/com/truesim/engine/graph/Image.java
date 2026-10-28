package com.truesim.engine.graph;

/**
 * An image of the graph: made by a technique (transient, its memory pooled and shared with images whose lifetimes do
 * not overlap) or an {@link ExternalImage} brought in. A handle is valid for the build that made it; passes resolve it
 * through their {@link PassContext}.
 */
public final class Image {

    final int id;
    final String name;
    final int width;
    final int height;
    final int format;

    Image(int id, String name, int width, int height, int format) {
        this.id = id;
        this.name = name;
        this.width = width;
        this.height = height;
        this.format = format;
    }

    public String name() {
        return name;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The Vulkan format, e.g. {@code VK_FORMAT_R16G16B16A16_SFLOAT}. */
    public int format() {
        return format;
    }

    @Override
    public String toString() {
        return name + " (" + width + " x " + height + ")";
    }
}
