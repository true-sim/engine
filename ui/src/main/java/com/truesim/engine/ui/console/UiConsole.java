package com.truesim.engine.ui.console;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.ui.console.AssetProvider;
import com.truesim.ui.console.Console;
import com.truesim.ui.console.ConsoleShell;
import com.truesim.ui.console.ConsoleVariables;
import com.truesim.ui.console.DebugCommands;
import com.truesim.ui.console.HistoryStore;
import com.truesim.ui.console.LogLevel;
import com.truesim.ui.console.NodeProvider;
import com.truesim.ui.console.ScriptSource;
import com.truesim.ui.console.TunableVariables;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.debug.UiDebugger;
import java.util.List;
import java.util.Objects;

/**
 * The developer console in the engine (design 5.10, UI28): a {@link Console} on the UI whose shell sees every engine
 * setting as a variable ({@link SettingVariables}, through the constraint overlay; the UI's tunables too, already
 * settings), whose permissions come from settings ({@link SettingPermissions}), whose history is kept by a
 * {@link HistoryStore} (the user's settings folder: {@link FileHistoryStore}) and whose log is fed by SLF4J through
 * {@link ConsoleLogProvider} and {@link ConsoleLog}'s inbox. Made by {@code UiPlugin.console(appId, store)};
 * {@link #debugger()} adds the UI debugger and its commands. Give it scripts, assets and nodes where the game has them.
 */
public final class UiConsole {

    /** The levels {@link #CAPTURE_LEVEL} and {@link #SOURCE_LEVEL} take. */
    public static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");
    /** The lowest level of log records the console captures. */
    public static final Setting<String> CAPTURE_LEVEL = Setting.choice("ui.console.captureLevel", "INFO", LEVELS,
        "the least severe log records the console keeps");
    /** The lowest level of log records whose source file and line the console shows (a stack walk each). */
    public static final Setting<String> SOURCE_LEVEL = Setting.choice("ui.console.sourceLevel", "WARN", LEVELS,
        "the least severe log records the console shows the source line of");
    /** Its settings, with {@link SettingPermissions#ALL}. */
    public static final List<Setting<?>> SETTINGS = List.of(CAPTURE_LEVEL, SOURCE_LEVEL, SettingPermissions.DEVELOPER,
        SettingPermissions.CHEATS);

    private final Ui ui;
    private final Console console;
    private final EngineSettings settings;
    private UiDebugger debugger;

    /**
     * The console of {@code ui} for app {@code appId}, its history in {@code store}, its variables the settings of
     * {@code registry} in {@code settings}.
     */
    @AllowAllocation("made once")
    public UiConsole(Ui ui, String appId, HistoryStore store, SettingRegistry registry, EngineSettings settings) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.settings = Objects.requireNonNull(settings, "settings");
        console = Console.create(ui, appId, store);
        // the engine's settings first; the UI's tunables are among them, else (no engine behind a tunable) as they are
        console.shell().variables(ConsoleVariables.of(new SettingVariables(registry, settings),
            new TunableVariables(ui.tunables())));
        console.shell().permissions(new SettingPermissions(settings));
        console.inbox(ConsoleLog.inbox());
        apply();
        settings.onChange(CAPTURE_LEVEL, this::apply);
        settings.onChange(SOURCE_LEVEL, this::apply);
    }

    private void apply() {
        ConsoleLog.captureLevel(level(settings.get(CAPTURE_LEVEL)));
        ConsoleLog.sourceLevel(level(settings.get(SOURCE_LEVEL)));
    }

    /** The console's level of a name of {@link #LEVELS}: OFF is above every level. */
    static int level(String name) {
        return "OFF".equals(name) ? LogLevel.ERROR + 1 : LogLevel.of(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** The console widget. */
    public Console console() {
        return console;
    }

    /** Its shell. */
    public ConsoleShell shell() {
        return console.shell();
    }

    /** The UI debugger with its console commands ({@code ui.inspect}, {@code ui.overlay}, {@code ui.pick}), made once. */
    @AllowAllocation("made once")
    public UiDebugger debugger() {
        if (debugger == null) {
            debugger = new UiDebugger(ui);
            DebugCommands.register(console.shell(), debugger);
        }
        return debugger;
    }

    /** Reads {@code exec} scripts from {@code source}; returns this. */
    public UiConsole scripts(ScriptSource source) {
        console.shell().scripts(source);
        return this;
    }

    /** Completes and checks asset arguments through {@code provider}; returns this. */
    public UiConsole assets(AssetProvider provider) {
        console.shell().assets(provider);
        return this;
    }

    /** Completes and checks node arguments through {@code provider}; returns this. */
    public UiConsole nodes(NodeProvider provider) {
        console.shell().nodes(provider);
        return this;
    }
}
