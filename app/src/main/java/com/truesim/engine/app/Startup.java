package com.truesim.engine.app;

import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.SettingRegistry;
import java.util.Objects;
import java.util.Optional;

/** What a plugin can do while it starts ({@link Plugin#start}): reach the engine and the plugins started before it. */
public final class Startup {

    private final Engine engine;
    private Plugin current;

    Startup(Engine engine) {
        this.engine = engine;
    }

    void current(Plugin plugin) {
        current = plugin;
    }

    /** The engine starting. */
    public Engine engine() {
        return engine;
    }

    /** The settings store. */
    public EngineSettings settings() {
        return engine.settings();
    }

    /** The settings registry: plugins register their settings and groups here. */
    public SettingRegistry registry() {
        return engine.registry();
    }

    /** The plugin installed by {@code key}; throws naming {@code app.use(...)} if it is not. */
    public <T extends Plugin> T get(PluginKey<T> key) {
        return engine.get(key);
    }

    /** The plugin installed by {@code key}, if it is and was not dropped. */
    public <T extends Plugin> Optional<T> find(PluginKey<T> key) {
        return engine.find(key);
    }

    /**
     * Makes {@code driver} pace frames, instead of the default: one plugin may claim it. Throws if another plugin did.
     */
    public void driveFrames(FrameDriver driver) {
        engine.claimFrameDriver(current, Objects.requireNonNull(driver, "driver"));
    }

    /**
     * Makes {@code driver} pace frames unless a plugin claims them ({@link #driveFrames}): rendering's presenter, over
     * the engine's own pacing. One plugin may give it. Throws if another plugin did.
     */
    public void defaultFrameDriver(FrameDriver driver) {
        engine.defaultFrameDriver(current, Objects.requireNonNull(driver, "driver"));
    }
}
