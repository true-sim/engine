package com.truesim.engine.input;

import com.truesim.common.math.Maths;

/**
 * The built-in binding filters (application-api.md D82, D83), saved by id: {@code deadzone(inner, outer)} per
 * component, {@code radial(inner, outer)} on the vector, {@code scale(s)}, {@code axes(x, y, z)} (each component's
 * factor: -1 inverts it), {@code curve(exponent)} on the length, {@code smooth(seconds)} (exponential, on real time),
 * {@code clamp(min, max)}.
 */
public final class Filters {

    /** Values under {@code inner} are 0, over {@code outer} 1, and between are stretched to 0..1; per component. */
    public static final FilterType DEADZONE = new FilterType("deadzone", 0, new String[] {"inner", "outer"}, 0.15f, 1) {
        @Override
        public void apply(float[] value, int dims, float[] params, float[] state, int at, double seconds) {
            for (int i = 0; i < dims; i++) {
                value[i] = stretch(value[i], params[0], params[1]);
            }
        }
    };

    /** As {@link #DEADZONE}, on the vector's length: a stick's dead zone without a cross of dead axes. */
    public static final FilterType RADIAL = new FilterType("radial", 0, new String[] {"inner", "outer"}, 0.15f, 1) {
        @Override
        public void apply(float[] value, int dims, float[] params, float[] state, int at, double seconds) {
            float length = length(value, dims);
            if (length > 0) {
                scaleBy(value, dims, stretch(length, params[0], params[1]) / length);
            }
        }
    };

    /** Multiplies every component by {@code s}: a mouse's sensitivity. */
    public static final FilterType SCALE = new FilterType("scale", 0, new String[] {"s"}, 1) {
        @Override
        public void apply(float[] value, int dims, float[] params, float[] state, int at, double seconds) {
            scaleBy(value, dims, params[0]);
        }
    };

    /** Multiplies each component by its own factor: -1 inverts an axis. */
    public static final FilterType AXES = new FilterType("axes", 0, new String[] {"x", "y", "z"}, 1, 1, 1) {
        @Override
        public void apply(float[] value, int dims, float[] params, float[] state, int at, double seconds) {
            for (int i = 0; i < dims; i++) {
                value[i] *= params[i];
            }
        }
    };

    /** Raises the length to {@code exponent}, keeping the direction: finer control near the middle. */
    public static final FilterType CURVE = new FilterType("curve", 0, new String[] {"exponent"}, 2) {
        @Override
        public void apply(float[] value, int dims, float[] params, float[] state, int at, double seconds) {
            float length = length(value, dims);
            if (length > 0) {
                float e = params[0];
                float raised = e == 2 ? length * length : e == 3 ? length * length * length : e == 1 ? length
                    : (float) Maths.pow(length, e); // whole exponents multiplied: pow costs about 100 times more
                scaleBy(value, dims, raised / length);
            }
        }
    };

    /**
     * Follows the value exponentially with a time constant of {@code seconds} of real time: steadier, a little late.
     * Its state is the value followed and whether it has one.
     */
    public static final FilterType SMOOTH = new FilterType("smooth", 4, new String[] {"seconds"}, 0.05f) {
        @Override
        public void apply(float[] value, int dims, float[] params, float[] state, int at, double seconds) {
            if (state[at + 3] == 0 || params[0] <= 0) {
                state[at + 3] = 1;
                for (int i = 0; i < dims; i++) {
                    state[at + i] = value[i];
                }
                return;
            }
            float follow = (float) (1 - Maths.exp(-seconds / params[0]));
            for (int i = 0; i < dims; i++) {
                state[at + i] += (value[i] - state[at + i]) * follow;
                value[i] = state[at + i];
            }
        }
    };

    /** Keeps each component between {@code min} and {@code max}. */
    public static final FilterType CLAMP = new FilterType("clamp", 0, new String[] {"min", "max"}, -1, 1) {
        @Override
        public void apply(float[] value, int dims, float[] params, float[] state, int at, double seconds) {
            for (int i = 0; i < dims; i++) {
                value[i] = Math.max(params[0], Math.min(params[1], value[i]));
            }
        }
    };

    static final FilterType[] BUILT_IN = {DEADZONE, RADIAL, SCALE, AXES, CURVE, SMOOTH, CLAMP};

    private Filters() {
    }

    static float stretch(float v, float inner, float outer) {
        float magnitude = Math.abs(v);
        if (magnitude <= inner) {
            return 0;
        }
        float stretched = outer > inner ? Math.min(1, (magnitude - inner) / (outer - inner)) : 1;
        return v < 0 ? -stretched : stretched;
    }

    static float length(float[] value, int dims) {
        float sum = 0;
        for (int i = 0; i < dims; i++) {
            sum += value[i] * value[i];
        }
        return (float) Math.sqrt(sum);
    }

    private static void scaleBy(float[] value, int dims, float factor) {
        for (int i = 0; i < dims; i++) {
            value[i] *= factor;
        }
    }
}
