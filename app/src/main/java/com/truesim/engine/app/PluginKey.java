package com.truesim.engine.app;

import java.util.Objects;

/**
 * The typed key of a plugin, declared as a constant by the module providing it, so code using a plugin compiles only
 * where that module is present: {@code engine.get(Xr.KEY)} returns an {@code Xr}. Compared by identity.
 *
 * <pre>{@code
 * public static final PluginKey<Xr> KEY = PluginKey.of("xr", Xr.class);
 * }</pre>
 *
 * @param <T> the plugin's type
 */
public final class PluginKey<T extends Plugin> {

    private final String name;
    private final Class<T> type;

    private PluginKey(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
    }

    /** A key named {@code name}, for plugins of {@code type}. */
    public static <T extends Plugin> PluginKey<T> of(String name, Class<T> type) {
        return new PluginKey<>(name, type);
    }

    /** Its name, in errors. */
    public String name() {
        return name;
    }

    /** The type of the plugin it names. */
    public Class<T> type() {
        return type;
    }

    @Override
    public String toString() {
        return name;
    }
}
