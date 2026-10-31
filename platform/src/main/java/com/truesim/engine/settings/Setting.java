package com.truesim.engine.settings;

import com.truesim.annotations.AllowAllocation;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One engine setting: its name, kind, default, allowed values and description, declared once by the module that uses
 * it, next to that code. Values live in {@link EngineSettings}; a setting with none set reads as its default.
 *
 * <pre>{@code
 * public static final Setting<Boolean> HDR = Setting.bool("present.hdr", true, "HDR output where the display has it");
 * public static final Setting<Float> PAPER_WHITE =
 *     Setting.decimal("display.paperWhite", 200, 80, 1000, "brightness of SDR white, in nits");
 * boolean hdr = settings.get(HDR);
 * }</pre>
 *
 * <p>{@link #live()} tells whether a change takes effect while running (the default) or only when what reads it is
 * made again (a window, a swapchain, the frame graph's next build): options screens can say so.
 *
 * @param <T> the value's type
 */
@AllowAllocation("declared once")
public final class Setting<T> {

    /** What a setting holds, for options screens and consoles. */
    public enum Kind {
        /** On or off. */
        BOOLEAN,
        /** A whole number within a range. */
        INTEGER,
        /** A decimal number within a range. */
        DECIMAL,
        /** Free text. */
        TEXT,
        /** One of an enum's constants. */
        CHOICE
    }

    private final String name;
    private final Kind kind;
    private final T defaultValue;
    private final String description;
    private final Function<String, T> parse;
    private final Predicate<T> valid;
    private final Class<?> type;
    private final Number min;
    private final Number max;
    private final boolean live;

    private Setting(String name, Kind kind, Class<?> type, T defaultValue, String description, Function<String, T> parse,
        Predicate<T> valid, Number min, Number max, boolean live) {
        if (name.isBlank() || !name.equals(name.strip()) || name.contains("=")) {
            throw new IllegalArgumentException("A setting's name is a word or dotted words: '" + name + "'");
        }
        this.name = name;
        this.kind = kind;
        this.type = type;
        this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        this.description = Objects.requireNonNull(description, "description");
        this.parse = parse;
        this.valid = valid;
        this.min = min;
        this.max = max;
        this.live = live;
        if (!valid.test(defaultValue)) {
            throw new IllegalArgumentException(name + ": its default " + defaultValue + " is not allowed");
        }
    }

    /** On or off. */
    public static Setting<Boolean> bool(String name, boolean defaultValue, String description) {
        return new Setting<>(name, Kind.BOOLEAN, Boolean.class, defaultValue, description, Setting::bool, v -> true,
            null, null, true);
    }

    /** A whole number from {@code min} to {@code max}. */
    public static Setting<Integer> integer(String name, int defaultValue, int min, int max, String description) {
        return new Setting<>(name, Kind.INTEGER, Integer.class, defaultValue, description, Integer::valueOf,
            v -> v >= min && v <= max, min, max, true);
    }

    /** A decimal number from {@code min} to {@code max}. */
    public static Setting<Float> decimal(String name, float defaultValue, float min, float max, String description) {
        return new Setting<>(name, Kind.DECIMAL, Float.class, defaultValue, description, Float::valueOf,
            v -> v >= min && v <= max, min, max, true);
    }

    /** Free text. */
    public static Setting<String> text(String name, String defaultValue, String description) {
        return new Setting<>(name, Kind.TEXT, String.class, defaultValue, description, s -> s, v -> true, null, null,
            true);
    }

    /** One of {@code defaultValue}'s enum constants. */
    public static <E extends Enum<E>> Setting<E> choice(String name, E defaultValue, String description) {
        Class<E> type = defaultValue.getDeclaringClass();
        return new Setting<>(name, Kind.CHOICE, type, defaultValue, description,
            s -> Enum.valueOf(type, s.toUpperCase(Locale.ROOT)), v -> true, null, null, true);
    }

    /** This setting, taking effect only when what reads it is made again: not while running. */
    public Setting<T> restartNeeded() {
        return new Setting<>(name, kind, type, defaultValue, description, parse, valid, min, max, false);
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    public T defaultValue() {
        return defaultValue;
    }

    public String description() {
        return description;
    }

    /** The smallest value allowed, for numbers; null otherwise. */
    public Number min() {
        return min;
    }

    /** The largest value allowed, for numbers; null otherwise. */
    public Number max() {
        return max;
    }

    /** The constants a choice may take; empty for other kinds. */
    public Object[] choices() {
        return kind == Kind.CHOICE ? type.getEnumConstants() : new Object[0];
    }

    /** Whether a change takes effect while running; otherwise when what reads it is made again. */
    public boolean live() {
        return live;
    }

    /** Whether {@code value} is of this setting's type and allowed. */
    public boolean allows(Object value) {
        return type.isInstance(value) && valid.test(cast(value));
    }

    @SuppressWarnings("unchecked")
    T cast(Object value) {
        return (T) value;
    }

    /** {@code text} as a value ({@link #format}'s form), or null if it is not one or not allowed. */
    public T parse(String text) {
        try {
            T value = parse.apply(text.strip());
            return value != null && valid.test(value) ? value : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code value} as text, which {@link #parse} reads back. */
    public String format(T value) {
        return value instanceof Enum<?> e ? e.name() : String.valueOf(value);
    }

    private static Boolean bool(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> true;
            case "false", "off", "no", "0" -> false;
            default -> null;
        };
    }

    @Override
    public String toString() {
        return name;
    }
}
