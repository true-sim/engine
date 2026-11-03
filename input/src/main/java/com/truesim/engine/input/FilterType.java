package com.truesim.engine.input;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.Objects;

/**
 * A kind of binding filter (application-api.md D82, D83): saved with the binding by its id and parameters
 * ({@code deadzone(0.15,1)}), adjustable by the player. A binding holds a chain of them, run in order each frame on
 * real time, on the binding's value before bindings combine (D85: device feel). Each keeps its state per player and
 * binding in a slice of a float array it is given; the engine resets it to zeros on focus loss, a context switch and a
 * disconnect (D86). {@link Filters} has the built-in ones; a game adds its own with {@link Input#addFilterType}.
 *
 * <p>{@link #apply} runs every frame for every binding using it and must allocate nothing.
 */
public abstract class FilterType {

    private final String id;
    private final String[] names;
    private final float[] defaults;
    private final int stateSize;

    /**
     * A filter saved as {@code id}, with {@code stateSize} floats of state, parameters named {@code names} with
     * {@code defaults} (as many): a saved binding may leave trailing ones out.
     */
    @AllowAllocation("declared once")
    protected FilterType(String id, int stateSize, String[] names, float... defaults) {
        if (!id.matches("[a-z][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("A filter id is a word: '" + id + "'");
        }
        if (names.length != defaults.length || stateSize < 0) {
            throw new IllegalArgumentException(id + ": a default per parameter, and no negative state");
        }
        this.id = id;
        this.names = names.clone();
        this.defaults = defaults.clone();
        this.stateSize = stateSize;
    }

    /** Its saved id. */
    public final String id() {
        return id;
    }

    /** How many parameters it takes. */
    public final int parameterCount() {
        return names.length;
    }

    /** Parameter {@code i}'s name, for options screens. */
    public final String parameterName(int i) {
        return names[i];
    }

    /** Parameter {@code i}'s default. */
    public final float parameterDefault(int i) {
        return defaults[i];
    }

    /** The floats of state it keeps per player and binding. */
    public final int stateSize() {
        return stateSize;
    }

    /** {@code given} parameters, the trailing ones left out filled with defaults; throws on too many. */
    @AllowAllocation("parsing bindings")
    final float[] complete(float[] given) {
        if (given.length > defaults.length) {
            throw new IllegalArgumentException(id + " takes " + defaults.length + " parameters, not " + given.length);
        }
        float[] all = defaults.clone();
        System.arraycopy(given, 0, all, 0, given.length);
        return all;
    }

    /**
     * Filters {@code value} in place: its first {@code dims} components (1 to 3) are the binding's value this frame;
     * {@code params} are the binding's ({@link #parameterCount()} of them); {@code state[at]} to
     * {@code state[at + stateSize() - 1]} are this player's and binding's, zero after a reset; {@code seconds} is the
     * real time since the last frame.
     */
    public abstract void apply(float[] value, int dims, float[] params, float[] state, int at, double seconds);

    @Override
    @AllowAllocation("describing")
    public String toString() {
        return id + Arrays.toString(names);
    }

    /** A filter chain's entry: a type and its parameters. */
    record Entry(FilterType type, float[] params) {
        Entry {
            Objects.requireNonNull(type, "type");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Entry e && e.type == type && Arrays.equals(e.params, params);
        }

        @Override
        public int hashCode() {
            return 31 * type.hashCode() + Arrays.hashCode(params);
        }

        @Override
        @AllowAllocation("saving bindings")
        public String toString() {
            StringBuilder text = new StringBuilder(type.id()).append('(');
            for (int i = 0; i < params.length; i++) {
                if (i > 0) {
                    text.append(',');
                }
                float p = params[i];
                text.append(p == (int) p ? Integer.toString((int) p) : Float.toString(p));
            }
            return text.append(')').toString();
        }
    }
}
