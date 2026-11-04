package com.truesim.engine.ui.console;

import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.ui.console.ConsolePermissions;
import java.util.List;
import java.util.Objects;

/**
 * What the player may run in the console, from two settings: {@link #DEVELOPER} ({@code console.developer}: the
 * developer commands, on in development builds or by a launch option) and {@link #CHEATS} ({@code console.cheats}: a
 * server's or single player's cheat flag; a game locks it where cheats are never allowed). Read when a command runs or
 * completes, so a change applies at once.
 */
public final class SettingPermissions implements ConsolePermissions {

    /** Whether developer commands run. */
    public static final Setting<Boolean> DEVELOPER = Setting.bool("console.developer", false,
        "whether the console runs developer commands");
    /** Whether cheat commands run. */
    public static final Setting<Boolean> CHEATS = Setting.bool("console.cheats", false,
        "whether the console runs cheat commands");
    /** Both. */
    public static final List<Setting<?>> ALL = List.of(DEVELOPER, CHEATS);

    private final EngineSettings settings;

    /** Permissions read from {@code settings}. */
    public SettingPermissions(EngineSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public boolean developer() {
        return settings.get(DEVELOPER);
    }

    @Override
    public boolean cheats() {
        return settings.get(CHEATS);
    }
}
