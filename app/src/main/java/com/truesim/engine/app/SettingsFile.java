package com.truesim.engine.app;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** The player's settings as a {@code .properties} file, and where an app keeps it. */
@AllowAllocation("loading and saving")
public final class SettingsFile {

    private SettingsFile() {
    }

    /**
     * The OS user folder for app {@code id}: {@code %APPDATA%\id} on Windows, {@code ~/Library/Application Support/id}
     * on macOS, {@code $XDG_CONFIG_HOME/id} (else {@code ~/.config/id}) elsewhere.
     */
    public static Path folder(String id) {
        return folder(id, System.getProperty("os.name"), System.getenv("APPDATA"), System.getenv("XDG_CONFIG_HOME"),
            System.getProperty("user.home"));
    }

    static Path folder(String id, String os, String appData, String xdgConfig, String home) {
        String system = os.toLowerCase(Locale.ROOT);
        if (system.startsWith("windows") && appData != null) {
            return Path.of(appData, id);
        }
        if (system.startsWith("mac")) {
            return Path.of(home, "Library", "Application Support", id);
        }
        return xdgConfig != null && !xdgConfig.isEmpty() ? Path.of(xdgConfig, id) : Path.of(home, ".config", id);
    }

    /** The values in {@code file}, by name; empty if there is no file. */
    public static Map<String, String> load(Path file) {
        Map<String, String> values = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return values;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the settings in " + file, e);
        }
        properties.stringPropertyNames().forEach(name -> values.put(name, properties.getProperty(name)));
        return values;
    }

    /** Writes {@code values} to {@code file}, making its folder. */
    public static void save(Path file, Map<String, String> values, String comment) {
        Properties properties = new Properties();
        properties.putAll(values);
        try {
            Path folder = file.toAbsolutePath().getParent();
            Files.createDirectories(folder);
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, comment);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the settings to " + file, e);
        }
    }
}
