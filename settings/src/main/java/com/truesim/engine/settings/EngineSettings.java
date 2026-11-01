package com.truesim.engine.settings;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The engine's settings: a value per {@link Setting}, the setting's default where none is set. One store serves the
 * whole engine (the frame graph and its techniques, the presenter, windows): parts read what they use and listen for
 * changes, and an app sets, saves and loads them, e.g. from an options screen.
 *
 * <pre>{@code
 * EngineSettings settings = new EngineSettings().load(saved);       // missing keys: defaults
 * settings.set(Presenter.HDR, false);                               // listeners apply it from the next frame
 * store(settings.toMap());                                          // what was set, as text
 * EngineSettings left = settings.scoped("window.left");            // one window's own settings, in the same store
 * }</pre>
 *
 * <p>Loaded values are kept as text until their setting is first read, so loading needs no module present, and keys of
 * settings this version does not know (another version's) are kept and saved again. Use it from one thread at a time;
 * listeners run on the thread that sets. Reading allocates nothing.
 */
@AllowAllocation("set up and changed rarely")
public final class EngineSettings {

    private final Store store;
    private final String prefix; // of this view's names: "" for the store itself, "scope." for a scoped view

    /** Settings with every value at its default. */
    public EngineSettings() {
        this(new Store(), "");
    }

    private EngineSettings(Store store, String prefix) {
        this.store = store;
        this.prefix = prefix;
    }

    /** A view of these settings whose names are under {@code scope} ({@code "window.left"}): one part's own values. */
    public EngineSettings scoped(String scope) {
        if (scope.isBlank() || scope.endsWith(".")) {
            throw new IllegalArgumentException("A scope is a word or dotted words: '" + scope + "'");
        }
        return new EngineSettings(store, prefix + scope + ".");
    }

    /** {@code setting}'s value: as set, as loaded, or its default. Allocates nothing once read. */
    public <T> T get(Setting<T> setting) {
        String key = prefix + setting.name();
        Object value = store.values.get(key);
        if (value == null) {
            String text = store.loaded.get(key);
            if (text != null) {
                T parsed = setting.parse(text);
                store.loaded.remove(key);
                if (parsed != null) {
                    store.values.put(key, parsed);
                    return parsed;
                }
            }
            return setting.defaultValue();
        }
        return setting.allows(value) ? setting.cast(value) : setting.defaultValue();
    }

    /** Whether {@code setting} has a value set or loaded, not only its default. */
    public boolean isSet(Setting<?> setting) {
        String key = prefix + setting.name();
        return store.values.containsKey(key) || store.loaded.containsKey(key);
    }

    /**
     * Sets {@code setting} to {@code value}, telling its listeners if it changed. Throws if the setting does not allow
     * it (out of range, say).
     */
    public <T> EngineSettings set(Setting<T> setting, T value) {
        if (!setting.allows(value)) {
            throw new IllegalArgumentException(setting.name() + " does not allow " + value);
        }
        T before = get(setting);
        store.values.put(prefix + setting.name(), value);
        store.loaded.remove(prefix + setting.name());
        if (!before.equals(value)) {
            store.changed(prefix + setting.name());
        }
        return this;
    }

    /** Back to {@code setting}'s default, telling its listeners if that changed it. */
    public EngineSettings reset(Setting<?> setting) {
        Object before = get(setting);
        store.values.remove(prefix + setting.name());
        store.loaded.remove(prefix + setting.name());
        if (!before.equals(setting.defaultValue())) {
            store.changed(prefix + setting.name());
        }
        return this;
    }

    /**
     * Calls {@code listener} whenever {@code setting} changes in this view, on the thread that changed it. Returns what
     * stops it.
     */
    public Subscription onChange(Setting<?> setting, Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        String key = prefix + setting.name();
        return store.listen(changed -> {
            if (changed.equals(key)) {
                listener.run();
            }
        });
    }

    /** Calls {@code listener} with the full name of every setting that changes, in any view. Returns what stops it. */
    public Subscription onAnyChange(Consumer<String> listener) {
        return store.listen(Objects.requireNonNull(listener, "listener"));
    }

    /** The full name {@code setting} has in this view: its name under the view's scope. */
    public String nameOf(Setting<?> setting) {
        return prefix + setting.name();
    }

    /**
     * Every value set or loaded, in every view, as text by full name: to save. Defaults are left out, so a saved file
     * holds only what was changed, and later versions' defaults apply to the rest.
     */
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>(store.loaded);
        for (Map.Entry<String, Object> entry : store.values.entrySet()) {
            Object value = entry.getValue();
            map.put(entry.getKey(), value instanceof Enum<?> e ? e.name() : String.valueOf(value));
        }
        return map;
    }

    /**
     * Loads saved values ({@link #toMap()}'s), replacing what is set: values are read when their setting is, those it
     * does not allow are its default. Tells every listener of a value that changed.
     */
    public EngineSettings load(Map<String, String> saved) {
        List<String> keys = new ArrayList<>(store.values.keySet());
        keys.addAll(store.loaded.keySet());
        keys.addAll(saved.keySet());
        store.values.clear();
        store.loaded.clear();
        store.loaded.putAll(saved);
        for (String key : keys) {
            store.changed(key);
        }
        return this;
    }

    /** Stops a listener. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    /** What every view of one set of settings shares. */
    private static final class Store {
        final Map<String, Object> values = new LinkedHashMap<>();
        final Map<String, String> loaded = new LinkedHashMap<>(); // read when their setting is first read
        final List<Consumer<String>> listeners = new ArrayList<>();

        Subscription listen(Consumer<String> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        /**
         * Tells every listener, also when one throws: the value is set already, so each must hear of it. The first
         * exception is rethrown after, later ones suppressed in it.
         */
        void changed(String key) {
            RuntimeException failure = null;
            for (Consumer<String> listener : List.copyOf(listeners)) {
                try {
                    listener.accept(key);
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
