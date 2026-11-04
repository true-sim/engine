package com.truesim.engine.ui.console;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.settings.Availability;
import com.truesim.engine.settings.Constraint;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.ui.console.ConsoleVariable;
import com.truesim.ui.console.ConsoleVariables;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The engine's settings as console variables (design 5.10): every setting of the {@link SettingRegistry} by its full
 * name, read from and written to the player's layer of {@link EngineSettings}, through the constraint overlay. Typing
 * {@code render.ssr} prints the value, its default, its range or choices and its availability; {@code render.ssr OFF}
 * sets it, or says why not: the setting is unavailable (unsupported, depends on another, locked by the game) or a
 * constraint would not give the value asked for (the hardware allows less). Asked on events (a command run, a key
 * typed in the console), never per frame.
 */
public final class SettingVariables implements ConsoleVariables {

    private final SettingRegistry registry;
    private final EngineSettings settings;

    /** The settings of {@code registry}, valued in {@code settings}. */
    public SettingVariables(SettingRegistry registry, EngineSettings settings) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    @AllowAllocation("asked on events")
    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (SettingRegistry.Entry entry : registry.entries()) {
            names.add(entry.fullName());
        }
        names.sort(null);
        return names;
    }

    @Override
    @AllowAllocation("asked on events")
    public ConsoleVariable find(String name) {
        SettingRegistry.Entry entry = registry.find(name);
        return entry == null ? null : new Variable<>(entry, entry.setting(), entry.view(settings));
    }

    /** One setting as a variable. */
    private record Variable<T>(SettingRegistry.Entry entry, Setting<T> setting, EngineSettings settings)
        implements ConsoleVariable {

        @Override
        public String name() {
            return entry.fullName();
        }

        @Override
        public String description() {
            return setting.description();
        }

        @Override
        public Kind kind() {
            return switch (setting.kind()) {
                case BOOLEAN -> Kind.BOOLEAN;
                case INTEGER -> Kind.INTEGER;
                case DECIMAL -> Kind.DECIMAL;
                case TEXT -> Kind.TEXT;
                case CHOICE, TECHNIQUE -> Kind.CHOICE; // a technique: one of the ids its slot knows
            };
        }

        @Override
        public String value() {
            return setting.format(settings.get(setting));
        }

        @Override
        public String defaultValue() {
            return setting.format(setting.defaultValue());
        }

        @Override
        public double min() {
            return setting.min() == null ? Double.NaN : setting.min().doubleValue();
        }

        @Override
        public double max() {
            return setting.max() == null ? Double.NaN : setting.max().doubleValue();
        }

        @Override
        @AllowAllocation("asked on events")
        public List<String> choices() {
            List<String> choices = new ArrayList<>();
            for (Object choice : setting.choices()) {
                choices.add(choice instanceof Enum<?> e ? e.name() : String.valueOf(choice));
            }
            return choices;
        }

        @Override
        public String availability() {
            Availability availability = settings.availability(setting);
            if (availability.changeable()) {
                return null;
            }
            return describe(availability);
        }

        @Override
        @AllowAllocation("a command run")
        public String set(String text) {
            T value = setting.parse(text);
            if (value == null) {
                return "not a value of " + name() + ": " + text;
            }
            Availability availability = settings.availability(setting);
            if (!availability.changeable()) {
                return describe(availability);
            }
            // the constraint overlay: what the hardware or the game would make of it
            T allowed = value;
            for (Constraint<?> constraint : settings.constraints(setting)) {
                @SuppressWarnings("unchecked")
                Constraint<T> typed = (Constraint<T>) constraint;
                T next = typed.apply(allowed);
                if (!Objects.equals(next, allowed)) {
                    String reason = constraint.reason().isEmpty() ? "constrained" : constraint.reason();
                    return "refused, " + reason + " (it allows " + setting.format(next) + ")";
                }
                allowed = next;
            }
            settings.set(setting, value);
            return null;
        }

        @AllowAllocation("a command run")
        private static String describe(Availability availability) {
            String state = switch (availability.state()) {
                case UNSUPPORTED -> "unsupported";
                case DEPENDS_ON -> "depends on another setting";
                case LOCKED -> "locked";
                case RESTART_PENDING -> "restart pending";
                case AVAILABLE -> "available";
            };
            return availability.reason().isEmpty() ? state : state + ": " + availability.reason();
        }
    }
}
