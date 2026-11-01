package com.truesim.engine.settings;

import java.util.Objects;

/**
 * How deep in an options screen a setting sits, if the game uses tiers at all: a setting has none unless registered
 * with one, and a game declares the tiers it uses ({@link SettingRegistry#tiers}). Queries show the tiers up to one
 * ({@link SettingRegistry#settings}), and a tier can stay hidden until revealed (a debug flag, a launch option).
 *
 * @param name for errors and consoles
 * @param rank deeper tiers rank higher
 */
public record SettingTier(String name, int rank) {

    /** What every player sees. */
    public static final SettingTier BASIC = new SettingTier("basic", 0);
    /** Behind an "advanced" toggle. */
    public static final SettingTier ADVANCED = new SettingTier("advanced", 10);
    /** For developers and testers: validation, timings, debug views. */
    public static final SettingTier DEVELOPER = new SettingTier("developer", 20);

    public SettingTier {
        Objects.requireNonNull(name, "name");
    }

    @Override
    public String toString() {
        return name;
    }
}
