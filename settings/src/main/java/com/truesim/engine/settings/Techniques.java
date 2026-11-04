package com.truesim.engine.settings;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The kinds of technique a store of settings offers, per slot ({@link EngineSettings#techniques()}): the registry the
 * engine registers its own kinds in, and games and mods theirs, through the same {@link #register}. A slot is declared
 * when first used ({@link #slot}): its own kinds register then ({@link TechniqueSlot#declare}).
 *
 * <pre>{@code
 * Techniques techniques = engine.settings().techniques();
 * techniques.register(AmbientOcclusion.SLOT, AcmeAo.KIND);             // a mod's kind, beside the engine's
 * for (TechniqueKind<?, ?, ?> kind : techniques.variants(AmbientOcclusion.SLOT)) { ... }   // an options screen
 * settings.set(AmbientOcclusion.SLOT.setting(), "acme-ao");            // or render.ao=acme-ao in the settings file
 * }</pre>
 *
 * <p>A slot's setting holding an id no kind has (a mod removed since the file was saved) reads as the slot's default
 * kind: the problem is logged once and listed in {@link #problems()}, and the saved id is kept, so the kind is chosen
 * again once its mod is back. Ids match ignoring case, so values older versions saved as enum constants
 * ({@code render.ao=BITMASK}) still load; {@code off}, or {@code none}, turns an optional slot off.
 */
@AllowAllocation("registered once; resolved again when a choice changes")
public final class Techniques {

    private static final Logger LOG = LoggerFactory.getLogger(Techniques.class);

    /**
     * A problem met resolving a slot's setting: the value it held and what was used instead.
     *
     * @param slot the slot's name
     * @param value the value its setting held
     * @param message what happened
     */
    public record Problem(String slot, String value, String message) {
    }

    private final Map<TechniqueSlot<?, ?>, Map<String, TechniqueKind<?, ?, ?>>> slots = new LinkedHashMap<>();
    private final List<Problem> problems = new ArrayList<>();
    private long version; // counts registrations: choices resolved before are resolved again

    Techniques() {
    }

    /** Declares {@code slot}, registering its own kinds, unless declared already. Returns this. */
    public Techniques slot(TechniqueSlot<?, ?> slot) {
        kinds(slot);
        return this;
    }

    private Map<String, TechniqueKind<?, ?, ?>> kinds(TechniqueSlot<?, ?> slot) {
        Map<String, TechniqueKind<?, ?, ?>> kinds = slots.get(slot);
        if (kinds == null) {
            for (TechniqueSlot<?, ?> other : slots.keySet()) {
                if (other.name().equals(slot.name())) {
                    throw new IllegalArgumentException("Two slots are named " + slot.name());
                }
            }
            kinds = new LinkedHashMap<>();
            slots.put(slot, kinds);
            version++;
            slot.declare(this);
        }
        return kinds;
    }

    /**
     * Adds {@code kind} to {@code slot}'s variants, declaring the slot first if needed: the engine's kinds and games'
     * alike. Throws if the kind belongs to another slot or the slot has a kind of its id (ignoring case).
     */
    public <X, T> Techniques register(TechniqueSlot<X, T> slot, TechniqueKind<?, X, T> kind) {
        Objects.requireNonNull(kind, "kind");
        if (kind.slot() != slot) {
            throw new IllegalArgumentException(kind + " is a kind of " + kind.slot().name() + ", not of "
                + slot.name());
        }
        Map<String, TechniqueKind<?, ?, ?>> kinds = kinds(slot);
        String id = kind.id().toLowerCase(Locale.ROOT);
        TechniqueKind<?, ?, ?> before = kinds.putIfAbsent(id, kind);
        if (before != null && before != kind) {
            throw new IllegalArgumentException(slot.name() + " has a kind '" + before.id() + "' already");
        }
        problems.removeIf(problem -> problem.slot().equals(slot.name()) && problem.value().equalsIgnoreCase(id));
        version++;
        return this;
    }

    /** The slots declared, in the order they were. */
    public List<TechniqueSlot<?, ?>> slots() {
        return List.copyOf(slots.keySet());
    }

    /** {@code slot}'s kinds, the engine's first, in the order registered: what an options screen offers. */
    @SuppressWarnings("unchecked")
    public <X, T> List<TechniqueKind<?, X, T>> variants(TechniqueSlot<X, T> slot) {
        List<TechniqueKind<?, X, T>> variants = new ArrayList<>();
        for (TechniqueKind<?, ?, ?> kind : kinds(slot).values()) {
            variants.add((TechniqueKind<?, X, T>) kind);
        }
        return variants;
    }

    /** {@code slot}'s kind named {@code id} (ignoring case), or null. */
    @SuppressWarnings("unchecked")
    public <X, T> TechniqueKind<?, X, T> kind(TechniqueSlot<X, T> slot, String id) {
        return (TechniqueKind<?, X, T>) kinds(slot).get(id.toLowerCase(Locale.ROOT));
    }

    /**
     * Every setting of {@code slot}: its choice, then each kind's tunables, for an options screen or a settings
     * registry ({@link SettingRegistry}).
     */
    public List<Setting<?>> settings(TechniqueSlot<?, ?> slot) {
        List<Setting<?>> settings = new ArrayList<>();
        settings.add(slot.setting());
        for (TechniqueKind<?, ?, ?> kind : kinds(slot).values()) {
            settings.addAll(kind.settings());
        }
        return settings;
    }

    /**
     * The setting at {@code path} among the slots declared: a slot's choice ({@code render.ao}) or a kind's tunable
     * ({@code render.ao.bitmask.radius}); null if none. For consoles.
     */
    public Setting<?> find(String path) {
        for (Map.Entry<TechniqueSlot<?, ?>, Map<String, TechniqueKind<?, ?, ?>>> slot : slots.entrySet()) {
            if (slot.getKey().name().equals(path)) {
                return slot.getKey().setting();
            }
            if (path.startsWith(slot.getKey().name() + ".")) {
                for (TechniqueKind<?, ?, ?> kind : slot.getValue().values()) {
                    Setting<?> found = kind.find(path);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Sets the player's value of the tunable {@code field} to {@code value} in every kind of {@code slot} having one by
     * that name ({@code radius}, whichever kind is chosen). Returns {@code settings}.
     */
    @SuppressWarnings("unchecked")
    public <V> EngineSettings set(EngineSettings settings, TechniqueSlot<?, ?> slot, String field, V value) {
        boolean found = false;
        for (TechniqueKind<?, ?, ?> kind : kinds(slot).values()) {
            Setting<?> setting = kind.find(kind.path(field));
            if (setting != null) {
                settings.set((Setting<V>) setting, value);
                found = true;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("No kind of " + slot.name() + " has a tunable '" + field + "'");
        }
        return settings;
    }

    /**
     * The kind chosen for {@code slot} in {@code settings}, or null when off: its setting's id, or the slot's default
     * where no kind has that id (reported). Allocates nothing until the choice or the kinds change.
     */
    public <X, T> TechniqueKind<?, X, T> chosen(EngineSettings settings, TechniqueSlot<X, T> slot) {
        return chosen(settings, slot, settings.get(slot.setting()));
    }

    /**
     * The kind {@code id} names for {@code slot}, as {@link #chosen(EngineSettings, TechniqueSlot)} resolves it
     * (cached in {@code settings}'s view): for a frame graph build that read the id itself, to rebuild when it changes.
     */
    @SuppressWarnings("unchecked")
    public <X, T> TechniqueKind<?, X, T> chosen(EngineSettings settings, TechniqueSlot<X, T> slot, String id) {
        Object held = settings.cached(slot);
        Choice choice = held == null ? null : (Choice) held;
        if (choice == null) {
            choice = new Choice();
            settings.cache(slot, choice);
        }
        if (!choice.resolved || choice.id != id || choice.version != version) {
            choice.kind = resolve(slot, id);
            choice.id = id;
            choice.version = version;
            choice.resolved = true;
        }
        return (TechniqueKind<?, X, T>) choice.kind;
    }

    private TechniqueKind<?, ?, ?> resolve(TechniqueSlot<?, ?> slot, String id) {
        Map<String, TechniqueKind<?, ?, ?>> kinds = kinds(slot);
        String key = id.toLowerCase(Locale.ROOT);
        if (key.equals(TechniqueSlot.OFF) || key.equals("none")) {
            if (slot.optional()) {
                return null;
            }
            report(slot, id, slot.name() + " cannot be off; " + slot.defaultKind() + " is used");
            return fallback(slot, kinds);
        }
        TechniqueKind<?, ?, ?> kind = kinds.get(key);
        if (kind == null) {
            report(slot, id, slot.name() + "=" + id + " names no kind of technique (its mod removed?); "
                + slot.defaultKind() + " is used");
            return fallback(slot, kinds);
        }
        return kind;
    }

    private static TechniqueKind<?, ?, ?> fallback(TechniqueSlot<?, ?> slot,
        Map<String, TechniqueKind<?, ?, ?>> kinds) {
        String id = slot.defaultKind().toLowerCase(Locale.ROOT);
        if (id.equals(TechniqueSlot.OFF)) {
            return null;
        }
        TechniqueKind<?, ?, ?> kind = kinds.get(id);
        if (kind == null) {
            throw new IllegalStateException(slot.name() + ": its default kind " + id + " is not registered");
        }
        return kind;
    }

    /** Notes a problem with {@code slot}'s value {@code value}, logging it the first time. */
    void report(TechniqueSlot<?, ?> slot, String value, String message) {
        Problem problem = new Problem(slot.name(), value, message);
        if (!problems.contains(problem)) {
            problems.add(problem);
            LOG.warn("Settings: {}", message);
        }
    }

    /** The problems met resolving slots' settings, oldest first; a kind registered later clears those naming it. */
    public List<Problem> problems() {
        return List.copyOf(problems);
    }

    /** One view's resolved choice of one slot. */
    private static final class Choice {
        String id;
        TechniqueKind<?, ?, ?> kind;
        long version;
        boolean resolved;
    }
}
