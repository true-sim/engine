package com.truesim.engine.graph;

/**
 * An image kept from frame to frame, for temporal techniques (anti-aliasing, denoising, exposure): each frame
 * writes {@link #current()} and may read {@link #previous()}, what the frame before wrote. The graph keeps two images
 * and swaps their roles every frame; they live as long as the graph builds them with the same name, size and format.
 * Last frame's image is undefined on the first frame (and after the graph rebuilds): check
 * {@link PassContext#historyValid(History)}.
 *
 * <pre>{@code
 * History taa = g.history("taa", width, height, VK_FORMAT_R16G16B16A16_SFLOAT);
 * g.pass("taa").read(taa.previous(), Access.SAMPLED_COMPUTE).write(taa.current(), Access.STORAGE_WRITE) ...
 * }</pre>
 */
public final class History {

    final String name;
    final Image current;
    final Image previous;
    int index; // among the build's histories

    History(String name, Image current, Image previous) {
        this.name = name;
        this.current = current;
        this.previous = previous;
    }

    public String name() {
        return name;
    }

    /** This frame's image, to write. */
    public Image current() {
        return current;
    }

    /** What the frame before wrote into its current image; read only. */
    public Image previous() {
        return previous;
    }

    @Override
    public String toString() {
        return "history " + name;
    }
}
