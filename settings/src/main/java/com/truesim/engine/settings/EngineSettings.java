package com.truesim.engine.settings;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
 *
 * <p>Each setting also has an {@link Availability}: whether a player can change it now, and why not. Owning parts
 * register rules ({@link #availableWhen}, {@link #dependsOn}), the game locks values ({@link #lock}), and a setting
 * that is not {@link Setting#live() live} is pending a restart while its value differs from the one {@link #applied}.
 * Availability listeners hear of changes when the app calls {@link #refreshAvailability()}, at a frame boundary.
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
        store.availabilityDirty = true;
        Object after = get(setting);
        if (!before.equals(after) || !preferredBefore.equals(preferred(setting, key))) {
            store.changed(key);
        }
        return this;
    }

    /**
     * Fixes {@code setting} at {@code value} for the game, because {@code reason}: a constraint whose availability reads
     * {@link Availability.State#LOCKED}. Returns what unlocks it.
     */
    public <T> Subscription lock(Setting<T> setting, T value, String reason) {
        Constraint<T> lock = Constraint.lock(value, reason);
        store.locks.add(lock);
        Subscription constraint;
        try {
            constraint = constrain(setting, lock);
        } catch (RuntimeException e) {
            store.locks.remove(lock);
            throw e;
        }
        know(setting);
        return () -> {
            constraint.close();
            store.locks.remove(lock);
        };
    }

    /**
     * Adds {@code rule} deciding {@code setting}'s availability in this view, after those already on it: the first rule
     * not available decides. Returns what removes it.
     */
    public Subscription availableWhen(Setting<?> setting, Availability.Rule rule) {
        Objects.requireNonNull(rule, "rule");
        String key = keyOf(setting);
        store.rules.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
        know(setting);
        return () -> {
            List<Availability.Rule> rules = store.rules.get(key);
            if (rules != null && rules.remove(rule) && rules.isEmpty()) {
                store.rules.remove(key);
            }
            store.availabilityDirty = true;
        };
    }

    /**
     * Makes {@code setting} depend on {@code on}, both in this view: {@link Availability.State#DEPENDS_ON}, because
     * {@code reason}, while {@code when} does not hold for {@code on}'s value
     * ({@code dependsOn(SOFT, SHADOWS, on -> on, "shadows are off")}).
     */
    public <T> Subscription dependsOn(Setting<?> setting, Setting<T> on, Predicate<T> when, String reason) {
        Objects.requireNonNull(when, "when");
        Availability off = Availability.dependsOn(reason);
        return availableWhen(setting, settings -> when.test(get(on)) ? Availability.AVAILABLE : off);
    }

    /**
     * Notes that {@code setting}'s value now is the one in use (a window made, a swapchain built): a setting that is not
     * live is pending a restart while its value differs from this one.
     */
    public EngineSettings applied(Setting<?> setting) {
        store.applied.put(keyOf(setting), get(setting));
        know(setting);
        return this;
    }

    private void know(Setting<?> setting) {
        store.known.putIfAbsent(keyOf(setting), new Known(this, setting));
        store.availabilityDirty = true;
    }

    /**
     * Whether {@code setting} can be changed now, and why not: the first of its rules not available, else the game's
     * lock, else a pending restart, else available.
     */
    public Availability availability(Setting<?> setting) {
        String key = keyOf(setting);
        List<Availability.Rule> rules = store.rules.get(key);
        if (rules != null) {
            for (Availability.Rule rule : rules) {
                Availability availability = rule.evaluate(this);
                if (availability.state() != Availability.State.AVAILABLE) {
                    return availability;
                }
            }
        }
        List<Constraint<?>> constraints = store.constraints.get(key);
        if (constraints != null) {
            for (Constraint<?> constraint : constraints) {
                if (store.locks.contains(constraint)) {
                    return Availability.locked(constraint.reason());
                }
            }
        }
        Object applied = store.applied.get(key);
        if (!setting.live() && applied != null && !applied.equals(get(setting))) {
            return Availability.restartPending("takes effect on restart");
        }
        return Availability.AVAILABLE;
    }

    /**
     * Calls {@code listener} with the full name of every setting whose availability changes, when
     * {@link #refreshAvailability()} finds it. Returns what stops it.
     */
    public Subscription onAvailabilityChange(Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener");
        store.availabilityListeners.add(listener);
        return () -> store.availabilityListeners.remove(listener);
    }

    /**
     * Says that what availability rules read outside the settings changed (a device or display came or went), so the
     * next {@link #refreshAvailability()} evaluates them again.
     */
    public EngineSettings invalidateAvailability() {
        store.availabilityDirty = true;
        return this;
    }

    /**
     * At a frame boundary: if anything availability depends on changed since the last call (a value, a rule, a lock,
     * {@link #invalidateAvailability}), evaluates every setting with rules, locks or an applied value, and tells
     * availability listeners of each whose availability changed. Does nothing otherwise.
     */
    public void refreshAvailability() {
        if (!store.availabilityDirty) {
            return;
        }
        store.availabilityDirty = false;
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Known> entry : store.known.entrySet()) {
            Known known = entry.getValue();
            Availability now = known.view.availability(known.setting);
            Availability before = store.lastAvailability.put(entry.getKey(), now);
            if (before == null ? now.state() != Availability.State.AVAILABLE : !before.equals(now)) {
                changed.add(entry.getKey());
            }
        }
        store.tell(store.availabilityListeners, changed);
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
        store.availabilityDirty = true;
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

    /** Stops a listener, or removes a constraint, a lock or a rule. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    /** A setting availability is kept for, with the view it was registered in. */
    private record Known(EngineSettings view, Setting<?> setting) {
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
        final Map<String, List<Availability.Rule>> rules = new HashMap<>();
        final Set<Constraint<?>> locks = new HashSet<>();
        final Map<String, Object> applied = new HashMap<>(); // the value in use, of settings that are not live
        final Map<String, Known> known = new LinkedHashMap<>(); // settings with rules, locks or an applied value
        final Map<String, Availability> lastAvailability = new HashMap<>(); // as of the last refresh
        final List<Consumer<String>> availabilityListeners = new ArrayList<>();
        boolean availabilityDirty;

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
            tell(listeners, keys);
        }

        /** Tells each of {@code listeners} of each of {@code keys}; see {@link #changedAll}. */
        void tell(List<Consumer<String>> listeners, Iterable<String> keys) {
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
