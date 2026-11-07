package com.truesim.engine.vulkan;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * A pipeline's specialization constants: 32-bit values by id ({@code [vk::constant_id(id)]}), sparse, sorted by id.
 * A shader's own constants are ids 0, 1, ...; ids from {@link #REQUIRED_FROM} on are the engine's (one per shading
 * hook, say), and a pipeline must set every one of those its shader declares ({@link Pipeline} refuses one that does
 * not), so a pipeline made without them fails instead of silently running without what they switch on. Immutable;
 * equal sets make equal pipelines.
 *
 * <pre>{@code
 * Specialization constants = Specialization.of(features, surfaces).with(1024, 1);
 * Pipeline.compute(device, module, "shadeMain", PUSH_BYTES, constants);
 * }</pre>
 */
@AllowAllocation("pipelines are made when the graph is built")
public final class Specialization {

    /** The first id a pipeline must set wherever its shader declares it. */
    public static final int REQUIRED_FROM = 1024;

    /** No constants. */
    public static final Specialization NONE = new Specialization(new int[0], new int[0]);

    private final int[] ids;
    private final int[] values;

    private Specialization(int[] ids, int[] values) {
        this.ids = ids;
        this.values = values;
    }

    /** Constants 0, 1, ... set to {@code values}: a shader's own. */
    public static Specialization of(int... values) {
        int[] ids = new int[values.length];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = i;
        }
        return new Specialization(ids, values.clone());
    }

    /** These constants with {@code id} set to {@code value} (replacing its value where it has one). */
    public Specialization with(int id, int value) {
        if (id < 0) {
            throw new IllegalArgumentException("Specialization constant ids are not negative: " + id);
        }
        int at = Arrays.binarySearch(ids, id);
        if (at >= 0) {
            int[] v = values.clone();
            v[at] = value;
            return new Specialization(ids, v);
        }
        int insert = -at - 1;
        int[] i2 = new int[ids.length + 1];
        int[] v2 = new int[ids.length + 1];
        System.arraycopy(ids, 0, i2, 0, insert);
        System.arraycopy(values, 0, v2, 0, insert);
        i2[insert] = id;
        v2[insert] = value;
        System.arraycopy(ids, insert, i2, insert + 1, ids.length - insert);
        System.arraycopy(values, insert, v2, insert + 1, ids.length - insert);
        return new Specialization(i2, v2);
    }

    /** These constants with every one of {@code other} too (its values where both have an id). */
    public Specialization with(Specialization other) {
        Specialization result = this;
        for (int i = 0; i < other.ids.length; i++) {
            result = result.with(other.ids[i], other.values[i]);
        }
        return result;
    }

    /** How many constants it sets. */
    public int size() {
        return ids.length;
    }

    /** The id of its {@code i}th constant, by id. */
    public int id(int i) {
        return ids[i];
    }

    /** The value of its {@code i}th constant, by id. */
    public int value(int i) {
        return values[i];
    }

    /** Whether it sets {@code id}. */
    public boolean has(int id) {
        return Arrays.binarySearch(ids, id) >= 0;
    }

    /**
     * Throws unless it sets every id from {@link #REQUIRED_FROM} that {@code module} declares: the pipeline of
     * {@code entry} would otherwise run without what those constants switch on.
     */
    void check(ShaderModule module, String entry) {
        for (int id : module.constantIds()) {
            if (id >= REQUIRED_FROM && !has(id)) {
                throw new IllegalArgumentException("The pipeline of " + entry + " of " + module.name()
                    + " sets no specialization constant " + id + ", which the shader declares: constants from "
                    + REQUIRED_FROM + " (shading hooks) are set by every pipeline of a shader declaring them");
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Specialization other && Arrays.equals(ids, other.ids)
            && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(ids) + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("{");
        for (int i = 0; i < ids.length; i++) {
            s.append(i == 0 ? "" : ", ").append(ids[i]).append('=').append(values[i]);
        }
        return s.append('}').toString();
    }
}
