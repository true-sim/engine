package com.truesim.engine.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.ui.core.bind.Bindable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The value a generated settings screen edits (design 5.11): the engine's {@link EngineSettings} as a {@link Bindable}
 * of immutable {@link Values} snapshots, so property panels edit it as they edit records (withers, undo by replacing
 * the root). {@link #get()} is a snapshot of the preferred values (what the player chose), taken again after any
 * setting changed, from the screen or elsewhere (the console, the game, a constraint); {@link #set} writes the
 * settings whose value differs from the current snapshot into the player's layer. {@link SettingsSource} gives its
 * property model; build the screen with {@code source.builder().build(controls, screen)}.
 */
public final class SettingsScreen implements Bindable<SettingsScreen.Values>, AutoCloseable {

    /**
     * The preferred value of every setting shown, by full name: immutable; {@link #with} makes a copy.
     *
     * @param values by full name
     */
    public record Values(Map<String, Object> values) {

        /** Copies {@code values}. */
        public Values {
            values = Map.copyOf(values);
        }

        /** The value of {@code fullName}. */
        public Object get(String fullName) {
            return values.get(fullName);
        }

        /** A copy with {@code fullName} set to {@code value}. */
        @AllowAllocation("an edit")
        public Values with(String fullName, Object value) {
            Map<String, Object> copy = new HashMap<>(values);
            copy.put(fullName, value);
            return new Values(copy);
        }
    }

    private final SettingRegistry registry;
    private final EngineSettings settings;
    private final EngineSettings.Subscription listening;
    private long version = 1;
    private long taken;
    private Values snapshot;

    /** The screen of the settings of {@code registry} in {@code settings}; listens to them until {@link #close()}d. */
    @AllowAllocation("made when the screen opens")
    public SettingsScreen(SettingRegistry registry, EngineSettings settings) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.settings = Objects.requireNonNull(settings, "settings");
        listening = settings.onAnyChange(name -> version++);
    }

    /** The settings it edits. */
    public EngineSettings settings() {
        return settings;
    }

    @Override
    @AllowAllocation("a snapshot after settings changed")
    public Values get() {
        if (snapshot == null || taken != version) {
            Map<String, Object> values = new HashMap<>();
            for (SettingRegistry.Entry entry : registry.entries()) {
                values.put(entry.fullName(), entry.view(settings).preferred(entry.setting()));
            }
            snapshot = new Values(values);
            taken = version;
        }
        return snapshot;
    }

    @Override
    public boolean set(Values value) {
        Values current = get();
        boolean changed = false;
        for (Map.Entry<String, Object> e : value.values().entrySet()) {
            if (!Objects.equals(current.get(e.getKey()), e.getValue())) {
                SettingRegistry.Entry entry = registry.find(e.getKey());
                if (entry != null && entry.setting().allows(e.getValue())) {
                    write(entry, entry.setting(), e.getValue());
                    changed = true;
                }
            }
        }
        return changed;
    }

    private <T> void write(SettingRegistry.Entry entry, Setting<T> setting, Object value) {
        @SuppressWarnings("unchecked")
        T typed = (T) value;
        entry.view(settings).set(setting, typed);
    }

    @Override
    public void touch() {
        version++;
    }

    @Override
    public long version() {
        return version;
    }

    /** Stops listening to the settings. */
    @Override
    public void close() {
        try {
            listening.close();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
