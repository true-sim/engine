package com.truesim.engine.ui;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingGroup;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.ui.core.Tunable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ui-core's {@link Tunable}s as engine settings (the project's rule: tunables are settings): each becomes a decimal
 * {@link Setting} of the same name, range, default and description, registered for options screens and saved with
 * the player's settings. {@link #update} writes changed settings back into the tunables, once a frame, before the UI
 * updates; it allocates nothing.
 *
 * <pre>{@code
 * UiTunables tunables = new UiTunables(engine.settings()).add(registry, uiGroup, Ui.SCALE, Ui.CARET_BLINK);
 * // each frame:
 * tunables.update();
 * }</pre>
 */
public final class UiTunables {

    private final EngineSettings settings;
    private Tunable[] tunables = new Tunable[0];
    private Setting<Float>[] made = newSettings(0);

    /** Tunables following {@code settings} (the engine's, or a scoped view). */
    public UiTunables(EngineSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @SuppressWarnings("unchecked")
    private static Setting<Float>[] newSettings(int length) {
        return (Setting<Float>[]) new Setting<?>[length];
    }

    /** Makes each of {@code added} a setting, registered in {@code registry} under {@code group} (both may be null). */
    @AllowAllocation("set up")
    public UiTunables add(SettingRegistry registry, SettingGroup group, Tunable... added) {
        List<Setting<?>> registered = new ArrayList<>();
        for (Tunable tunable : added) {
            Objects.requireNonNull(tunable, "tunable");
            for (Tunable known : tunables) {
                if (known.name().equals(tunable.name())) {
                    throw new IllegalArgumentException("Two tunables are named " + tunable.name());
                }
            }
            Setting<Float> setting = Setting.decimal(tunable.name(), tunable.initial(), tunable.min(), tunable.max(),
                tunable.description());
            int n = tunables.length;
            tunables = java.util.Arrays.copyOf(tunables, n + 1);
            made = java.util.Arrays.copyOf(made, n + 1);
            tunables[n] = tunable;
            made[n] = setting;
            registered.add(setting);
            tunable.set(settings.get(setting)); // a saved value at once
        }
        if (registry != null) {
            if (group != null) {
                registry.add(group, registered.toArray(new Setting<?>[0]));
            } else {
                registry.addHidden(registered.toArray(new Setting<?>[0]));
            }
        }
        return this;
    }

    /** Writes each setting changed since into its tunable. Allocates nothing. */
    public void update() {
        for (int i = 0; i < tunables.length; i++) {
            float value = settings.get(made[i]);
            if (value != tunables[i].get()) {
                tunables[i].set(value);
            }
        }
    }

    /** The setting made for {@code tunable}. */
    public Setting<Float> setting(Tunable tunable) {
        for (int i = 0; i < tunables.length; i++) {
            if (tunables[i] == tunable) {
                return made[i];
            }
        }
        throw new IllegalArgumentException(tunable.name() + " was not added");
    }

    /** The settings made, in the order added. */
    @AllowAllocation("a snapshot")
    public List<Setting<Float>> settings() {
        return List.of(made);
    }
}
