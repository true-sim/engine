package com.truesim.engine.graph;

import com.truesim.engine.vulkan.Format;
import java.util.Objects;

/**
 * An image the graph does not own, such as the swapchain image or a persistent texture: its size, format and usage are
 * fixed, its handle may change every frame ({@link #set}). The graph takes it from {@code initialLayout} (with its
 * contents, unless that is {@code VK_IMAGE_LAYOUT_UNDEFINED}) and leaves it in {@code finalLayout}, e.g.
 * {@code VK_IMAGE_LAYOUT_PRESENT_SRC_KHR}. A new size is a {@link #resize}: the graph rebuilds on its next frame.
 */
public final class ExternalImage {

    final String name;
    Format format;
    final int usage;
    final int initialLayout;
    final int finalLayout;
    int width;
    int height;
    int version;
    long handle;
    long view;
    int sampled = -1; // its sampled slot in the bindless set, if it has one (a texture's)
    Texture texture; // the texture it is, or null

    /**
     * An external image of {@code width} by {@code height} {@code format} texels, created with {@code usage} (the
     * graph never uses it in ways outside it).
     */
    public ExternalImage(String name, int width, int height, Format format, int usage, int initialLayout,
        int finalLayout) {
        this.name = Objects.requireNonNull(name, "name");
        this.format = Objects.requireNonNull(format, "format");
        this.usage = usage;
        this.initialLayout = initialLayout;
        this.finalLayout = finalLayout;
        resize(width, height);
    }

    /** The image (and a view of all of it) for the frames recorded from now on. Allocates nothing. */
    public ExternalImage set(long image, long imageView) {
        this.handle = image;
        this.view = imageView;
        return this;
    }

    /** A new format (a swapchain's, rebuilt for HDR): the graph rebuilds on its next frame. */
    void reformat(Format newFormat) {
        if (newFormat != format) {
            format = newFormat;
            version++;
        }
    }

    /** A new size: the graph rebuilds on its next frame. */
    public void resize(int newWidth, int newHeight) {
        if (newWidth < 1 || newHeight < 1) {
            throw new IllegalArgumentException("Image size must be positive: " + newWidth + " x " + newHeight);
        }
        if (newWidth != width || newHeight != height) {
            width = newWidth;
            height = newHeight;
            version++;
        }
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

    public Format format() {
        return format;
    }
}
