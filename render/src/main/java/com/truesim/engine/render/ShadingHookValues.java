package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.ShaderVariants;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.Objects;

/**
 * What the scene holds for shading hooks, per device: each hook's authored record (its values by field name, set in
 * content and saved with the scene; engine-scene's {@code ShadingHookSync} sets them from scene components) and
 * whether it is on. A hook the scene holds values for runs in every view that shades: with those values as they are
 * where no technique adds it ({@link ShadingHooks#add}: content-only hooks need no Java), else as its technique merges
 * them with what the frame knows ({@link ShadingHookSource#write}'s {@code authored}).
 *
 * <pre>{@code
 * ShadingHookValues.of(device).set("game.fog", values, true);  // values: MaterialValues, by field name
 * }</pre>
 *
 * <p>Graphs rebuild when the hooks it holds change ({@link #version}), not when their values or their being on do: those
 * are read each frame. Thread-safe; reading allocates nothing.
 */
public final class ShadingHookValues {

    private final ShadingHooks hooks;
    private volatile ShadingHookParameters[] authored; // by hook index, or null
    private volatile boolean[] enabled;
    private volatile ShadingHooks.Set present = ShadingHooks.Set.NONE;
    private volatile long version;
    private ShadingHooks.Set union = ShadingHooks.Set.NONE; // every hook a view was made with (UNION)

    private ShadingHookValues(ShadingHooks hooks) {
        this.hooks = hooks;
        authored = new ShadingHookParameters[hooks.count()];
        enabled = new boolean[hooks.count()];
    }

    /** The values {@code device}'s shading hooks have (made with no values the first time). */
    @AllowAllocation("made once per device")
    public static ShadingHookValues of(VulkanDevice device) {
        ShaderVariants variants = device.shaderVariants();
        ShadingHookValues values = variants.attached(ShadingHookValues.class);
        if (values == null) {
            ShadingHooks hooks = ShadingHooks.of(device);
            synchronized (variants) {
                values = variants.attached(ShadingHookValues.class);
                if (values == null) {
                    values = new ShadingHookValues(hooks);
                    variants.attach(ShadingHookValues.class, values);
                }
            }
        }
        return values;
    }

    /** The listing they are of. */
    public ShadingHooks hooks() {
        return hooks;
    }

    /**
     * Sets hook {@code hook}'s values from {@code values} by field name (its record's defaults where they have none),
     * on or off.
     *
     * @throws IllegalStateException where the shaders were built without the hook
     */
    @AllowAllocation("values change rarely")
    public ShadingHookValues set(String hook, MaterialValues values, boolean on) {
        int index = hooks.require(hook);
        return set(index, hooks.fromValues(index, Objects.requireNonNull(values, "values")), on);
    }

    /**
     * Sets hook {@code hook}'s values to {@code record}, on or off.
     *
     * @throws IllegalArgumentException where {@code record} is not of the hook's parameters' record class
     */
    @AllowAllocation("values change rarely")
    public ShadingHookValues set(String hook, ShadingHookParameters record, boolean on) {
        return set(hooks.require(hook), record, on);
    }

    @AllowAllocation("values change rarely")
    private synchronized ShadingHookValues set(int index, ShadingHookParameters record, boolean on) {
        Objects.requireNonNull(record, "record");
        if (!hooks.parameters(index).isInstance(record)) {
            throw new IllegalArgumentException(hooks.hooks().get(index) + "'s parameters are "
                + hooks.parameters(index).getSimpleName() + ", not " + record.getClass().getSimpleName());
        }
        ShadingHookParameters[] a = authored.clone();
        boolean[] e = enabled.clone();
        a[index] = record;
        e[index] = on;
        enabled = e;
        authored = a;
        if (!present.has(index)) {
            present = present.with(index);
            version++;
        }
        return this;
    }

    /** Turns hook {@code hook} on or off, keeping its values (none: nothing to turn on). */
    @AllowAllocation("values change rarely")
    public synchronized ShadingHookValues on(String hook, boolean on) {
        int index = hooks.require(hook);
        if (authored[index] != null) {
            boolean[] e = enabled.clone();
            e[index] = on;
            enabled = e;
        }
        return this;
    }

    /** Drops hook {@code hook}'s values: it runs no more where no technique adds it. */
    @AllowAllocation("values change rarely")
    public synchronized ShadingHookValues remove(String hook) {
        int index = hooks.index(hook);
        if (index < 0 || authored[index] == null) {
            return this;
        }
        ShadingHookParameters[] a = authored.clone();
        a[index] = null;
        authored = a;
        ShadingHooks.Set left = ShadingHooks.Set.NONE;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != null) {
                left = left.with(i);
            }
        }
        present = left;
        version++;
        return this;
    }

    /** Hook {@code index}'s authored record, or null: the scene holds none. Allocates nothing. */
    public ShadingHookParameters authored(int index) {
        return authored[index];
    }

    /** Hook {@code index}'s authored record, or its defaults. */
    ShadingHookParameters authoredOrDefaults(int index, ShadingHookParameters defaults) {
        ShadingHookParameters a = authored[index];
        return a != null ? a : defaults;
    }

    /** Whether hook {@code index} has values and is on. Allocates nothing. */
    public boolean on(int index) {
        return authored[index] != null && enabled[index];
    }

    /** The hooks the scene holds values for. */
    public ShadingHooks.Set present() {
        return present;
    }

    /**
     * {@code runs} with every hook any view of the device was made with so far ({@link ShadingHookInputs.Specialize#UNION}):
     * growing it changes the {@link #version}, so graphs rebuild with every view running the new hooks.
     */
    @AllowAllocation("building the graph, when it changes")
    synchronized ShadingHooks.Set union(ShadingHooks.Set runs) {
        if (!union.containsAll(runs)) {
            union = union.union(runs);
            version++;
        }
        return union;
    }

    /** Changes whenever the hooks it holds values for do (graphs rebuild): not with their values. */
    public long version() {
        return version;
    }
}
