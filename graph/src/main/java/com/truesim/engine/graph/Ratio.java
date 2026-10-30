package com.truesim.engine.graph;

/**
 * A scale as a fraction, for sizes relative to another image's: applied with ceiling division, so tile grids and mip
 * chains come out exact ({@code Ratio.of(1, 2)} of 1081 texels is 541, never 540.5 rounded either way).
 *
 * @param numerator the fraction's numerator
 * @param denominator the fraction's denominator
 */
public record Ratio(int numerator, int denominator) {

    /** The whole size. */
    public static final Ratio ONE = new Ratio(1, 1);

    public Ratio {
        if (numerator < 1 || denominator < 1) {
            throw new IllegalArgumentException("A scale is a positive fraction: " + numerator + "/" + denominator);
        }
    }

    public static Ratio of(int numerator, int denominator) {
        return new Ratio(numerator, denominator);
    }

    /** {@code size} scaled, rounded up, at least 1. */
    public int apply(int size) {
        return (int) Math.max(1, ((long) size * numerator + denominator - 1) / denominator);
    }

    @Override
    public String toString() {
        return numerator + "/" + denominator;
    }
}
