package com.truesim.engine.settings;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A place a technique plugs into, such as {@code render.ao}: one setting ({@link #setting()}, named as the slot) holds
 * the id of the {@link TechniqueKind} chosen for it, or {@code off}, and each kind's tunables are settings under
 * {@code <slot>.<kind>.<field>}. The kinds a store offers are its {@link Techniques}: the engine's own, which a slot
 * registers when a store first uses it ({@link #declare}), and those games and mods register through the same
 * {@link Techniques#register}.
 *
 * <pre>{@code
 * public static final TechniqueSlot<AmbientOcclusion, Technique> SLOT = TechniqueSlot.of("render.ao", "bitmask", true,
 *     "ambient occlusion", techniques -> techniques.register(SLOT, GTAO).register(SLOT, BITMASK));
 * TechniqueKind<?, AmbientOcclusion, Technique> kind = SLOT.chosen(settings);  // null: off
 * SLOT.choose(settings, GTAO);                                                    // render.ao=gtao
 * }</pre>
 *
 * <p>Open: a game declares slots of its own, with {@link #of} or its own implementation.
 *
 * @param <X> what the slot's owner hands a kind's factory: the view it works in, say
 * @param <T> what a kind's factory makes: what the slot's owner runs
 */
public interface TechniqueSlot<X, T> {

    /** The id that names no technique: the slot is off. */
    String OFF = "off";

    /** Its name: its setting's, and the prefix of its kinds' tunables ({@code render.ao}). */
    String name();

    /** What it does, for options screens and consoles. */
    String description();

    /** The id of the kind chosen by default, or {@link #OFF}. */
    String defaultKind();

    /** Whether it may be off: no technique at all. */
    boolean optional();

    /**
     * The setting holding the chosen kind's id ({@link Setting#technique}), named {@link #name()} and defaulting to
     * {@link #defaultKind()}: made once, as it is read every frame.
     */
    Setting<String> setting();

    /** Registers the engine's own kinds in {@code techniques}, when a store first uses the slot. None by default. */
    default void declare(Techniques techniques) {
    }

    /** The kind chosen in {@code settings} (its store's {@link Techniques}), or null when off. Allocates nothing. */
    default TechniqueKind<?, X, T> chosen(EngineSettings settings) {
        return settings.techniques().chosen(settings, this);
    }

    /** Chooses {@code kind} as the player's value in {@code settings}; null turns the slot off. */
    default EngineSettings choose(EngineSettings settings, TechniqueKind<?, X, T> kind) {
        return settings.set(setting(), kind == null ? OFF : kind.id());
    }

    /**
     * A slot named {@code name}, choosing {@code defaultKind} (or {@link #OFF}) by default, which may be off when
     * {@code optional}, and whose {@code declare} registers the engine's kinds (null: none).
     */
    @AllowAllocation("declared once")
    static <X, T> TechniqueSlot<X, T> of(String name, String defaultKind, boolean optional, String description,
        Consumer<Techniques> declare) {
        return new Declared<>(name, defaultKind, optional, description, declare);
    }

    /** A slot made by {@link #of}. */
    @AllowAllocation("declared once")
    final class Declared<X, T> implements TechniqueSlot<X, T> {

        private final String name;
        private final String defaultKind;
        private final boolean optional;
        private final String description;
        private final Consumer<Techniques> declare;
        private final Setting<String> setting;

        private Declared(String name, String defaultKind, boolean optional, String description,
            Consumer<Techniques> declare) {
            this.name = Objects.requireNonNull(name, "name");
            this.defaultKind = Objects.requireNonNull(defaultKind, "defaultKind");
            this.optional = optional;
            this.description = Objects.requireNonNull(description, "description");
            this.declare = declare;
            if (defaultKind.equalsIgnoreCase(OFF) && !optional) {
                throw new IllegalArgumentException(name + ": a slot that cannot be off needs a default kind");
            }
            setting = Setting.technique(name, defaultKind, description);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public String defaultKind() {
            return defaultKind;
        }

        @Override
        public boolean optional() {
            return optional;
        }

        @Override
        public Setting<String> setting() {
            return setting;
        }

        @Override
        public void declare(Techniques techniques) {
            if (declare != null) {
                declare.accept(techniques);
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
