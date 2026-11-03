package com.truesim.engine.audio.dsp;

import java.util.Objects;

/**
 * A node's parameter: its name, default and range (values set are clamped).
 *
 * @param name     its name, unique within the node
 * @param fallback its value until set
 * @param min      the least value
 * @param max      the greatest value
 */
public record NodeParam(String name, float fallback, float min, float max) {

    public NodeParam {
        Objects.requireNonNull(name, "name");
        if (!(min <= fallback && fallback <= max)) {
            throw new IllegalArgumentException("Node parameter " + name + ": " + fallback + " is not in [" + min
                + ", " + max + "]");
        }
    }

    /** A parameter called {@code name}, {@code fallback} until set, in [{@code min}, {@code max}]. */
    public static NodeParam of(String name, double fallback, double min, double max) {
        return new NodeParam(name, (float) fallback, (float) min, (float) max);
    }
}
