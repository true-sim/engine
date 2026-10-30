package com.truesim.engine.graph;

import java.util.Objects;

/**
 * The value an attachment is cleared to when its pass starts rendering: a color, or a depth (and stencil). A clear made
 * {@linkplain #color(float[]) of an array} reads it each frame, so a technique can change its clear color without
 * rebuilding the graph.
 */
public final class Clear {

    /** Depth cleared to 0: the far plane in reverse depth, the engine's convention. */
    public static final Clear FAR = depth(0);

    final float[] values;
    final int stencil;
    final boolean depth;

    private Clear(float[] values, int stencil, boolean depth) {
        this.values = values;
        this.stencil = stencil;
        this.depth = depth;
    }

    /** A color, fixed. */
    public static Clear color(float r, float g, float b, float a) {
        return new Clear(new float[] {r, g, b, a}, 0, false);
    }

    /** A color read from {@code rgba} (three or four values; alpha 1 when three) every frame. */
    public static Clear color(float[] rgba) {
        Objects.requireNonNull(rgba, "rgba");
        if (rgba.length < 3 || rgba.length > 4) {
            throw new IllegalArgumentException("A clear color has 3 or 4 values: " + rgba.length);
        }
        return new Clear(rgba, 0, false);
    }

    /** A depth. */
    public static Clear depth(float depth) {
        return depthStencil(depth, 0);
    }

    /** A depth and a stencil value. */
    public static Clear depthStencil(float depth, int stencil) {
        return new Clear(new float[] {depth}, stencil, true);
    }

    /** Component {@code i} of the color, or the depth for {@code i} 0 of a depth clear; alpha defaults to 1. */
    float value(int i) {
        return i < values.length ? values[i] : 1;
    }
}
