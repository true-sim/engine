package com.truesim.engine.settings;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The engine's settings: values per {@link Setting} in layers, and what the hardware or the game allows on top. One
 * store serves the whole engine (the frame graph and its techniques, the presenter, windows): parts read what they use
 * and listen for changes, and an app sets, saves and loads them, e.g. from an options screen.
 *
 * <pre>{@code
 * EngineSettings settings = new EngineSettings().load(saved);       // the player's values; missing keys: lower layers
 * settings.set(Layer.GAME, Shadows.SIZE, 1024);                      // the game's default, under the player's
 * settings.commandLine(args);                                        // --set name=value, over everything
 * settings.set(Presenter.HDR, false);                                // the player's; listeners apply it next frame
 * settings.constrain(Presenter.HDR, Constraint.lock(false, "no HDR display")); // get() false, preferred() kept
 * store(settings.toMap());                                           // the player's values, as text
 * EngineSettings left = settings.scoped("window.left");             // one window's own settings, in the same store
 * }</pre>
 *
 * <p>A setting's <em>preferred</em> value ({@link #preferred}) is the highest {@link Layer} holding one: the command
 * line over the player's over the game's over the setting's own default. Its <em>effective</em> value ({@link #get},
 * what the engine uses) is the preferred one passed through the {@link Constraint}s on it. Only the player's layer is
 * saved, so a constraint or a game default never overwrites what the player chose.
 *
 * <p>Loaded values are kept as text until their setting is first read, so loading needs no module present, and keys of
 * settings this version does not know (another version's) are kept and saved again. Use it from one thread at a time;
 * listeners run on the thread that changes a value, and hear of a change to the effective or the preferred value.
 * Reading allocates nothing once read.
 */
@AllowAllocation("set up and changed rarely")
public final class EngineSettings {

    /** Where a value comes from, lowest first; the setting's own default is under them all. */
    public enum Layer {
        /** The game's defaults, over the engine's. */
        GAME,
        /** The player's values: the one layer saved ({@link #toMap()}). */
        PLAYER,
        /** Given on the command line ({@link #commandLine}): over everything, for this run only. */
        COMMAND_LINE
    }

    private static final Layer[] TOP_DOWN = {Layer.COMMAND_LINE, Layer.PLAYER, Layer.GAME};

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

    /**
     * {@code setting}'s effective value: {@link #preferred} through the constraints on it. What the engine uses.
     * Allocates nothing once read.
     */
    public <T> T get(Setting<T> setting) {
        String key = keyOf(setting);
        Object cached = store.effective.get(key);
        if (cached != null && setting.allows(cached)) {
            return setting.cast(cached);
        }
        T value = preferred(setting, key);
        List<Constraint<?>> constraints = store.constraints.get(key);
        if (constraints != null) {
            for (Constraint<?> constraint : constraints) {
                @SuppressWarnings("unchecked")
                Constraint<T> typed = (Constraint<T>) constraint;
                T allowed = typed.apply(value);
                if (allowed == null || !setting.allows(allowed)) {
                    throw new IllegalStateException(key + ": the constraint '" + constraint.reason() + "' gives "
                        + allowed + ", which the setting does not allow");
                }
                value = allowed;
            }
        }
        store.effective.put(key, value);
        return value;
    }

    /**
     * {@code setting}'s preferred value: the highest layer's (command line, player, game), else its default. What
     * options screens show as chosen. Allocates nothing once read.
     */
    public <T> T preferred(Setting<T> setting) {
        return preferred(setting, keyOf(setting));
    }

    private <T> T preferred(Setting<T> setting, String key) {
        for (Layer layer : TOP_DOWN) {
            T value = store.layer(layer).read(setting, key);
            if (value != null) {
                return value;
            }
        }
        return setting.defaultValue();
    }

    /** Whether any layer holds a value for {@code setting}, set or loaded, not only its default. */
    public boolean isSet(Setting<?> setting) {
        String key = keyOf(setting);
        for (Layer layer : TOP_DOWN) {
            if (store.layer(layer).holds(key)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code layer} holds a value for {@code setting}, set or loaded. */
    public boolean isSet(Layer layer, Setting<?> setting) {
        return store.layer(layer).holds(keyOf(setting));
    }

    /** Whether a constraint keeps {@code setting} from its preferred value now. */
    public boolean isConstrained(Setting<?> setting) {
        return !get(setting).equals(preferred(setting));
    }

    /** The constraints on {@code setting}, in the order they apply. */
    public List<Constraint<?>> constraints(Setting<?> setting) {
        List<Constraint<?>> constraints = store.constraints.get(keyOf(setting));
        return constraints == null ? List.of() : List.copyOf(constraints);
    }

    /**
     * Sets the player's value of {@code setting} to {@code value}, telling its listeners if that changed it. Throws if
     * the setting does not allow it (out of range, say).
     */
    public <T> EngineSettings set(Setting<T> setting, T value) {
        return set(Layer.PLAYER, setting, value);
    }

    /** Sets {@code layer}'s value of {@code setting} to {@code value}, telling its listeners if that changed it. */
    public <T> EngineSettings set(Layer layer, Setting<T> setting, T value) {
        if (!setting.allows(value)) {
            throw new IllegalArgumentException(setting.name() + " does not allow " + value);
        }
        return change(setting, () -> store.layer(layer).put(keyOf(setting), value));
    }

    /** Clears the player's value of {@code setting}: the layers under it decide again. */
    public EngineSettings reset(Setting<?> setting) {
        return clear(Layer.PLAYER, setting);
    }

    /** Clears {@code layer}'s value of {@code setting}, telling its listeners if that changed it. */
    public EngineSettings clear(Layer layer, Setting<?> setting) {
        return change(setting, () -> store.layer(layer).remove(keyOf(setting)));
    }

    /**
     * Adds {@code constraint} to {@code setting} in this view, after those already on it, telling its listeners if that
     * changed it. Returns what removes it again.
     */
    public <T> Subscription constrain(Setting<T> setting, Constraint<T> constraint) {
        Objects.requireNonNull(constraint, "constraint");
        String key = keyOf(setting);
        Runnable remove = () -> {
            List<Constraint<?>> constraints = store.constraints.get(key);
            if (constraints != null && constraints.remove(constraint) && constraints.isEmpty()) {
                store.constraints.remove(key);
            }
        };
        try {
            change(setting, () -> store.constraints.computeIfAbsent(key, k -> new ArrayList<>()).add(constraint));
        } catch (IllegalStateException e) {
            remove.run(); // it gives a value the setting does not allow: not added
            throw e;
        }
        return () -> change(setting, remove);
    }

    private EngineSettings change(Setting<?> setting, Runnable change) {
        String key = keyOf(setting);
        Object before = get(setting);
        Object preferredBefore = preferred(setting, key);
        change.run();
        store.effective.remove(key);
        Object after = get(setting);
        if (!before.equals(after) || !preferredBefore.equals(preferred(setting, key))) {
            store.changed(key);
        }
        return this;
    }

    /**
     * Calls {@code listener} whenever {@code setting} changes in this view, on the thread that changed it. Returns what
     * stops it.
     */
    public Subscription onChange(Setting<?> setting, Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        String key = keyOf(setting);
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
        return keyOf(setting);
    }

    private String keyOf(Setting<?> setting) {
        return prefix.isEmpty() ? setting.name() : prefix + setting.name();
    }

    /**
     * The player's values, in every view, as text by full name: to save. Other layers and defaults are left out, so a
     * saved file holds only what the player changed, and later versions' defaults apply to the rest.
     */
    public Map<String, String> toMap() {
        return toMap(Layer.PLAYER);
    }

    /** {@code layer}'s values, in every view, as text by full name. */
    public Map<String, String> toMap(Layer layer) {
        return store.layer(layer).toMap();
    }

    /**
     * Loads saved values ({@link #toMap()}'s) as the player's, replacing the player's: values are read when their
     * setting is, those it does not allow fall through to the layers under. Tells every listener of a value that may
     * have changed.
     */
    public EngineSettings load(Map<String, String> saved) {
        return load(Layer.PLAYER, saved);
    }

    /** Loads {@code values} (by full name, as text) into {@code layer}, replacing what it held. */
    public EngineSettings load(Layer layer, Map<String, String> values) {
        Values replaced = store.layer(layer);
        Set<String> keys = new LinkedHashSet<>(replaced.values.keySet());
        keys.addAll(replaced.loaded.keySet());
        keys.addAll(values.keySet());
        replaced.values.clear();
        replaced.loaded.clear();
        replaced.loaded.putAll(values);
        store.effective.clear();
        store.changedAll(keys);
        return this;
    }

    /**
     * Takes {@code --set name=value} (or {@code --set=name=value}) from {@code args} as the command line layer,
     * replacing what it held; other arguments are left to the app. Throws on a {@code --set} without
     * {@code name=value}.
     */
    public EngineSettings commandLine(String... args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String pair;
            if (args[i].equals("--set")) {
                if (i + 1 == args.length) {
                    throw new IllegalArgumentException("--set needs name=value");
                }
                pair = args[++i];
            } else if (args[i].startsWith("--set=")) {
                pair = args[i].substring("--set=".length());
            } else {
                continue;
            }
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("--set needs name=value: '" + pair + "'");
            }
            values.put(pair.substring(0, equals).strip(), pair.substring(equals + 1));
        }
        return load(Layer.COMMAND_LINE, values);
    }

    /** Stops a listener, or removes a constraint. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    /** One layer's values. */
    private static final class Values {
        final Map<String, Object> values = new LinkedHashMap<>();
        final Map<String, String> loaded = new LinkedHashMap<>(); // read when their setting is first read

        /** The value for {@code key} if this layer holds one {@code setting} allows, else null. */
        <T> T read(Setting<T> setting, String key) {
            Object value = values.get(key);
            if (value == null) {
                String text = loaded.get(key);
                if (text == null) {
                    return null;
                }
                T parsed = setting.parse(text);
                loaded.remove(key);
                if (parsed == null) {
                    return null; // unreadable: as if not there
                }
                values.put(key, parsed);
                return parsed;
            }
            return setting.allows(value) ? setting.cast(value) : null;
        }

        boolean holds(String key) {
            return values.containsKey(key) || loaded.containsKey(key);
        }

        void put(String key, Object value) {
            values.put(key, value);
            loaded.remove(key);
        }

        void remove(String key) {
            values.remove(key);
            loaded.remove(key);
        }

        Map<String, String> toMap() {
            Map<String, String> map = new LinkedHashMap<>(loaded);
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                Object value = entry.getValue();
                map.put(entry.getKey(), value instanceof Enum<?> e ? e.name() : String.valueOf(value));
            }
            return map;
        }
    }

    /** What every view of one set of settings shares. */
    private static final class Store {
        final Values[] layers = {new Values(), new Values(), new Values()}; // by Layer.ordinal()
        final Map<String, List<Constraint<?>>> constraints = new HashMap<>();
        final Map<String, Object> effective = new HashMap<>(); // each key's effective value, once read
        final List<Consumer<String>> listeners = new ArrayList<>();

        Values layer(Layer layer) {
            return layers[layer.ordinal()];
        }

        Subscription listen(Consumer<String> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        void changed(String key) {
            changedAll(List.of(key));
        }

        /**
         * Tells every listener of every key, also when one throws: the values are set already, so each must hear of
         * them. The first exception is rethrown after, later ones suppressed in it.
         */
        void changedAll(Iterable<String> keys) {
            RuntimeException failure = null;
            List<Consumer<String>> told = List.copyOf(listeners);
            for (String key : keys) {
                for (Consumer<String> listener : told) {
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
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
