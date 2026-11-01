package com.truesim.engine.app;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What an application sets up before anything starts ({@link Application#configure}): its identity, the plugins it
 * installs (explicitly: nothing is found on the classpath) and its defaults for settings. What the player can change
 * is a setting, not configuration.
 *
 * <pre>{@code
 * app.id("rock-garden").name("Rock Garden").version("1.2")
 *     .use(new Rendering(), new Input(), Xr.optional())
 *     .defaults(Shadows.SIZE, 1024)                       // the game's default; the player's value wins
 *     .saveSettings();                                    // the player's values in the OS user folder
 * }</pre>
 */
@AllowAllocation("configured once")
public final class AppConfig {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_.-]*");

    private final EngineSettings settings;
    private final List<Plugin> plugins = new ArrayList<>();
    private String id = "app";
    private String name = "true-sim application";
    private String version = "0";
    private Path settingsFile;

    AppConfig(EngineSettings settings) {
        this.settings = settings;
    }

    /** Its id: lower-case letters, digits, {@code _ . -}; names its settings folder. */
    public AppConfig id(String appId) {
        if (!ID.matcher(appId).matches()) {
            throw new IllegalArgumentException("An app id is lower-case letters, digits, _ . -: '" + appId + "'");
        }
        this.id = appId;
        return this;
    }

    /** Its name, for players: window titles, error boxes. */
    public AppConfig name(String appName) {
        this.name = Objects.requireNonNull(appName, "name");
        return this;
    }

    /** Its version, for players and saved files. */
    public AppConfig version(String appVersion) {
        this.version = Objects.requireNonNull(appVersion, "version");
        return this;
    }

    /** Installs {@code installed}, after those installed before. */
    public AppConfig use(Plugin... installed) {
        for (Plugin plugin : installed) {
            Objects.requireNonNull(plugin, "plugin");
            for (Plugin other : plugins) {
                if (other.key() == plugin.key()) {
                    throw new IllegalArgumentException("The plugin " + plugin.key() + " is installed twice");
                }
            }
            plugins.add(plugin);
        }
        return this;
    }

    /** Sets the game's default for {@code setting}: over the engine's, under the player's. */
    public <T> AppConfig defaults(Setting<T> setting, T value) {
        settings.set(EngineSettings.Layer.GAME, setting, value);
        return this;
    }

    /**
     * Loads the player's settings from, and saves them on shutdown to, {@code settings.properties} in the OS user
     * folder for this app's id ({@link SettingsFile#folder}).
     */
    public AppConfig saveSettings() {
        return saveSettings(SettingsFile.folder(id).resolve("settings.properties"));
    }

    /** Loads the player's settings from, and saves them on shutdown to, {@code file}. */
    public AppConfig saveSettings(Path file) {
        this.settingsFile = Objects.requireNonNull(file, "file");
        return this;
    }

    /** The settings store: the game's defaults and the registry go here. */
    public EngineSettings settings() {
        return settings;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    /** The plugins installed, in the order given. */
    public List<Plugin> plugins() {
        return List.copyOf(plugins);
    }

    /** Where the player's settings are kept; null if they are not saved. */
    public Path settingsFile() {
        return settingsFile;
    }
}
