package com.truesim.engine.graph;

import com.truesim.engine.vulkan.Format;
import java.util.Arrays;
import java.util.Objects;

/**
 * What passes sampling a switched-off producer's image read instead ({@link Switch#fallback}): an image of the same size
 * holding one value everywhere, e.g. 1 for ambient occlusion (nothing occluded).
 *
 * @param format the fallback's format, as the producer's image's
 * @param value its value: red, green, blue, alpha (missing channels 0, alpha 1)
 */
public record Fallback(Format format, float[] value) {

    public Fallback {
        Objects.requireNonNull(format, "format");
        value = Arrays.copyOf(value, 4);
        if (value.length > 0 && Float.isNaN(value[0])) {
            throw new IllegalArgumentException("A fallback holds numbers");
        }
    }

    /** {@code format} holding {@code channels} (red, green, blue, alpha; alpha 1 where not given). */
    public static Fallback constant(Format format, float... channels) {
        float[] value = {0, 0, 0, 1};
        System.arraycopy(channels, 0, value, 0, Math.min(4, channels.length));
        return new Fallback(format, value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Fallback f && f.format == format && Arrays.equals(f.value, value);
    }

    @Override
    public int hashCode() {
        return format.hashCode() * 31 + Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "fallback " + format + " " + Arrays.toString(value);
    }
}
