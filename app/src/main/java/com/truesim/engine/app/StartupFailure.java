package com.truesim.engine.app;

/**
 * A plugin could not start: thrown by the plugin with a reason a player can read ("no headset found"), then handed to
 * {@link Application#onStartupFailure} with the stage, the plugin and whether the game can run without it.
 */
public final class StartupFailure extends RuntimeException {

    /** What the application answers to a startup failure. */
    public enum Response {
        /** Start the plugin's stage again: the player plugged the headset in. */
        RETRY,
        /** Run without the plugin, if it is optional; for a required one this is {@link #QUIT}. */
        CONTINUE_WITHOUT,
        /** Stop starting: what started is stopped, and {@link Engine#create} throws this failure. */
        QUIT
    }

    private static final long serialVersionUID = 1L;

    private final Stage stage;
    private final transient PluginKey<?> plugin;
    private final boolean required;

    /** Thrown by a plugin: {@code reason} is for players. */
    public StartupFailure(String reason) {
        this(reason, null);
    }

    /** Thrown by a plugin, with what caused it. */
    public StartupFailure(String reason, Throwable cause) {
        this(reason, cause, null, null, true);
    }

    private StartupFailure(String reason, Throwable cause, Stage stage, PluginKey<?> plugin, boolean required) {
        super(reason, cause);
        this.stage = stage;
        this.plugin = plugin;
        this.required = required;
    }

    /** {@code thrown} by {@code plugin} in {@code stage}, as the application is told of it. */
    static StartupFailure of(Throwable thrown, Stage stage, Plugin plugin) {
        String reason = thrown instanceof StartupFailure failure ? failure.getMessage()
            : thrown.getMessage() != null ? thrown.getMessage() : thrown.getClass().getSimpleName();
        Throwable cause = thrown instanceof StartupFailure failure ? failure.getCause() : thrown;
        return new StartupFailure(reason, cause, stage, plugin.key(), !plugin.optional());
    }

    /** Why, for players. */
    public String reason() {
        return getMessage();
    }

    /** The stage it failed in; null where the plugin threw it and the engine has not placed it yet. */
    public Stage stage() {
        return stage;
    }

    /** The plugin that failed; null where the plugin threw it and the engine has not placed it yet. */
    public PluginKey<?> plugin() {
        return plugin;
    }

    /** Whether the game cannot run without the plugin. */
    public boolean required() {
        return required;
    }
}
