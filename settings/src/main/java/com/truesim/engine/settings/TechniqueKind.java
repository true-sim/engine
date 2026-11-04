package com.truesim.engine.settings;

import com.truesim.annotations.AllowAllocation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One kind of technique for a {@link TechniqueSlot}: its id ({@code bitmask}), the record type of its tunables
 * ({@link TechniqueConfig}) with their defaults, and the factory making the technique. Each of the record's components
 * is a {@link Setting} named by its dotted path ({@code render.ao.bitmask.radius}): set, saved, loaded, overridden on
 * the command line and read alone like any other, and read together as the record by {@link #config}.
 *
 * <pre>{@code
 * public static final TechniqueKind<Bitmask, AmbientOcclusion, Technique> BITMASK = TechniqueKind.of(SLOT, "bitmask",
 *     new Bitmask(0.5f, 2, 8, 0.25f, true), "a mask of sectors per slice", (ao, config) -> new BitmaskAo(ao, config));
 * settings.techniques().register(SLOT, BITMASK);
 * Bitmask config = BITMASK.config(settings);                       // allocates nothing until a value changes
 * settings.set(BITMASK.setting("radius"), 1f);                     // render.ao.bitmask.radius=1.0
 * }</pre>
 *
 * <p>The factory is given the slot owner's context and the kind's live config: a supplier of the record as the owner's
 * settings hold it now, which allocates nothing until a value changes (read it as frames record).
 *
 * @param <C> its tunables' record
 * @param <X> what the slot's owner hands the factory
 * @param <T> what the factory makes
 */
@AllowAllocation("declared once; configs made when a value changes")
public final class TechniqueKind<C extends Record & TechniqueConfig, X, T> {

    /** Makes a kind's technique. */
    @FunctionalInterface
    public interface Factory<C, X, T> {

        /** The technique, working in {@code context}, with {@code config} supplying its tunables as they are now. */
        T create(X context, Supplier<C> config);
    }

    private final TechniqueSlot<X, T> slot;
    private final String id;
    private final Class<C> type;
    private final C defaults;
    private final String description;
    private final Factory<C, X, T> factory;
    private final Setting<?>[] fields; // in the record's order
    private final Map<String, Setting<?>> byName = new LinkedHashMap<>();
    private final Constructor<C> constructor;

    private TechniqueKind(TechniqueSlot<X, T> slot, String id, C defaults, String description,
        Factory<C, X, T> factory) {
        this.slot = Objects.requireNonNull(slot, "slot");
        this.id = Objects.requireNonNull(id, "id");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.description = Objects.requireNonNull(description, "description");
        this.factory = Objects.requireNonNull(factory, "factory");
        if (!Setting.techniqueId(id) || id.equalsIgnoreCase(TechniqueSlot.OFF)) {
            throw new IllegalArgumentException("A technique's id is letters, digits, _ and -, and not 'off': '" + id
                + "'");
        }
        @SuppressWarnings("unchecked")
        Class<C> recordType = (Class<C>) defaults.getClass();
        type = recordType;
        RecordComponent[] components = type.getRecordComponents();
        fields = new Setting<?>[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            fields[i] = field(components[i]);
            byName.put(components[i].getName(), fields[i]);
            types[i] = components[i].getType();
        }
        try {
            constructor = type.getDeclaredConstructor(types);
            constructor.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalArgumentException(type.getName() + ": its canonical constructor is not accessible", e);
        }
    }

    /**
     * A kind of {@code slot}'s technique named {@code id} (letters, digits, {@code _} and {@code -}; unique in the
     * slot, ignoring case), its tunables the record {@code defaults} is one of, made by {@code factory}.
     */
    public static <C extends Record & TechniqueConfig, X, T> TechniqueKind<C, X, T> of(TechniqueSlot<X, T> slot,
        String id, C defaults, String description, Factory<C, X, T> factory) {
        return new TechniqueKind<>(slot, id, defaults, description, factory);
    }

    /** The setting of {@code component}, defaulting to the defaults' value, named by its path. */
    private Setting<?> field(RecordComponent component) {
        String name = path(component.getName());
        TechniqueConfig.Tunable tunable = component.getAnnotation(TechniqueConfig.Tunable.class);
        String about = tunable == null ? component.getName() : tunable.value();
        double min = tunable == null ? -Double.MAX_VALUE : tunable.min();
        double max = tunable == null ? Double.MAX_VALUE : tunable.max();
        Object value;
        try {
            component.getAccessor().setAccessible(true);
            value = component.getAccessor().invoke(defaults);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalArgumentException(name + ": its default cannot be read", e);
        }
        Class<?> kind = component.getType();
        Setting<?> setting;
        if (kind == boolean.class) {
            setting = Setting.bool(name, (Boolean) value, about);
        } else if (kind == int.class) {
            setting = Setting.integer(name, (Integer) value, (int) Math.max(min, Integer.MIN_VALUE),
                (int) Math.min(max, Integer.MAX_VALUE), about);
        } else if (kind == float.class) {
            setting = Setting.decimal(name, (Float) value, (float) Math.max(min, -Float.MAX_VALUE),
                (float) Math.min(max, Float.MAX_VALUE), about);
        } else if (kind == String.class) {
            setting = Setting.text(name, (String) value, about);
        } else if (kind.isEnum()) {
            setting = choice(name, (Enum<?>) value, about);
        } else {
            throw new IllegalArgumentException(name + ": a tunable is a boolean, int, float, String or enum, not "
                + kind.getName());
        }
        return tunable == null || tunable.formerly().length == 0 ? setting : setting.formerly(tunable.formerly());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Setting<?> choice(String name, Enum<?> value, String about) {
        return Setting.choice(name, (Enum) value, about);
    }

    /** The slot it plugs into. */
    public TechniqueSlot<X, T> slot() {
        return slot;
    }

    /** Its id: the value of its slot's setting choosing it. */
    public String id() {
        return id;
    }

    /** The record type of its tunables. */
    public Class<C> type() {
        return type;
    }

    /** Its tunables' defaults. */
    public C defaults() {
        return defaults;
    }

    public String description() {
        return description;
    }

    /** The full name of its tunable {@code field}: {@code <slot>.<id>.<field>}. */
    public String path(String field) {
        return slot.name() + "." + id + "." + field;
    }

    /** Its tunables' settings, in the record's order. */
    public List<Setting<?>> settings() {
        return Collections.unmodifiableList(new ArrayList<>(byName.values()));
    }

    /** The setting of its tunable {@code field} (a component's name); throws if it has none. */
    @SuppressWarnings("unchecked")
    public <V> Setting<V> setting(String field) {
        Setting<?> setting = byName.get(field);
        if (setting == null) {
            throw new IllegalArgumentException(slot.name() + "." + id + " has no tunable '" + field + "'");
        }
        return (Setting<V>) setting;
    }

    /** The setting of its tunable at {@code path} ({@code render.ao.bitmask.radius}), or null. */
    Setting<?> find(String path) {
        for (Setting<?> field : fields) {
            if (field.name().equals(path)) {
                return field;
            }
        }
        return null;
    }

    /**
     * Its tunables as {@code settings} hold them now: the same record until one of them changes, so reading it every
     * frame allocates nothing. Values the record's constructor refuses together keep the record before, reported in
     * the store's {@link Techniques#problems()}.
     */
    public C config(EngineSettings settings) {
        Object held = settings.cached(this);
        @SuppressWarnings("unchecked")
        Cached<C> cached = held == null ? null : (Cached<C>) held;
        if (cached == null) {
            cached = new Cached<>(fields.length);
            settings.cache(this, cached);
        }
        long version = settings.version();
        if (cached.config == null || cached.version != version) {
            boolean changed = cached.config == null;
            for (int i = 0; i < fields.length; i++) {
                Object value = settings.get(fields[i]);
                if (!value.equals(cached.values[i])) {
                    cached.values[i] = value;
                    changed = true;
                }
            }
            if (changed) {
                cached.config = make(settings, cached.values, cached.config);
            }
            cached.version = version;
        }
        return cached.config;
    }

    private C make(EngineSettings settings, Object[] values, C before) {
        try {
            return constructor.newInstance(values);
        } catch (InvocationTargetException e) {
            settings.techniques().report(slot, id, slot.name() + "." + id + ": the values together are refused ("
                + e.getCause().getMessage() + "); kept " + (before == null ? "the defaults" : "the values before"));
            return before == null ? defaults : before;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getName() + " cannot be made", e);
        }
    }

    /** Sets the player's value of each tunable whose value in {@code settings} differs from {@code config}'s. */
    public EngineSettings set(EngineSettings settings, C config) {
        RecordComponent[] components = type.getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            Object value;
            try {
                value = components[i].getAccessor().invoke(config);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(fields[i] + " cannot be read", e);
            }
            if (!value.equals(settings.preferred(fields[i]))) {
                put(settings, fields[i], value);
            }
        }
        return settings;
    }

    @SuppressWarnings("unchecked")
    private static <V> void put(EngineSettings settings, Setting<V> setting, Object value) {
        settings.set(setting, (V) value);
    }

    /** Its technique, working in {@code context}, with {@code config} supplying its tunables. */
    public T create(X context, Supplier<C> config) {
        return factory.create(context, config);
    }

    @Override
    public String toString() {
        return slot.name() + "=" + id;
    }

    /** One view's record, and the values it was made from. */
    private static final class Cached<C> {
        final Object[] values;
        C config;
        long version;

        Cached(int fields) {
            values = new Object[fields];
        }
    }
}
