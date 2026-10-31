package com.truesim.engine.platform;

import com.truesim.annotations.AllowAllocation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A window's preferences, everything the platform can set on it, as one value: to open a window with
 * ({@link Platform#createWindow(WindowOptions, WindowSettings)}), to change a live one with ({@link Window#apply}), to
 * read back ({@link Window#settings()}), and to save and load ({@link #toMap()}, {@link #fromMap}), e.g. a game's
 * options screen. Systems apply what they support: opacity, grabs or an exact fullscreen mode may be ignored or
 * approximated, and {@link Window#settings()} tells what took.
 *
 * <pre>{@code
 * WindowSettings saved = WindowSettings.fromMap(load("window"));        // missing or unknown keys: defaults
 * window.apply(saved.withMode(WindowMode.BORDERLESS_FULLSCREEN));      // from the next frame, no restart
 * store("window", window.settings().toMap());
 * }</pre>
 *
 * @param mode how it covers the screen
 * @param display the display it is on, by {@link Platform#displayKey key} ("": the primary)
 * @param width windowed width, in screen coordinates
 * @param height windowed height
 * @param centered windowed: centered on its display, else at (x, y)
 * @param x windowed position, in screen coordinates
 * @param fullscreenWidth exclusive fullscreen: the display mode wanted (0: the desktop's)
 * @param refreshRate exclusive fullscreen: in Hz (0: the desktop's)
 * @param maximized windowed: maximized
 * @param resizable the user may resize it
 * @param bordered has decorations
 * @param alwaysOnTop stays above other windows
 * @param focusable may take input focus
 * @param opacity 0 (transparent) to 1 (opaque), where the system supports it
 * @param minWidth smallest size the user may resize it to (0: no limit)
 * @param maxWidth largest size (0: no limit)
 * @param minAspect narrowest width over height while resizing (0: no limit)
 * @param maxAspect widest (0: no limit)
 * @param mouseGrab confines the mouse to the window while it has focus
 * @param keyboardGrab takes system shortcuts (such as Alt+Tab) while it has focus
 * @param relativeMouse hides the mouse and reports motion only, for mouse look
 * @param cursorVisible shows the mouse cursor
 * @param keepScreenOn keeps the screen saver and display sleep off
 */
@AllowAllocation("set up rarely")
public record WindowSettings(
    WindowMode mode,
    String display,
    int width,
    int height,
    boolean centered,
    int x,
    int y,
    int fullscreenWidth,
    int fullscreenHeight,
    float refreshRate,
    boolean maximized,
    boolean resizable,
    boolean bordered,
    boolean alwaysOnTop,
    boolean focusable,
    float opacity,
    int minWidth,
    int minHeight,
    int maxWidth,
    int maxHeight,
    float minAspect,
    float maxAspect,
    boolean mouseGrab,
    boolean keyboardGrab,
    boolean relativeMouse,
    boolean cursorVisible,
    boolean keepScreenOn) {

    public WindowSettings {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(display, "display");
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("A window has a size: " + width + " x " + height);
        }
        if (!(opacity >= 0 && opacity <= 1)) {
            throw new IllegalArgumentException("Opacity is from 0 to 1: " + opacity);
        }
        if (fullscreenWidth < 0 || fullscreenHeight < 0 || refreshRate < 0 || minWidth < 0 || minHeight < 0
            || maxWidth < 0 || maxHeight < 0 || minAspect < 0 || maxAspect < 0) {
            throw new IllegalArgumentException("Sizes, rates and limits are 0 or more");
        }
    }

    /** What SDL gives a new window: windowed, 1280 x 720, centered on the primary display, decorated, resizable. */
    public static WindowSettings defaults() {
        return new WindowSettings(WindowMode.WINDOWED, "", 1280, 720, true, 0, 0, 0, 0, 0, false, true, true, false, true, 1, 0, 0, 0, 0, 0, 0, false, false, false, true, true);
    }

    public WindowSettings withMode(WindowMode mode) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withDisplay(String display) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withWidth(int width) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withHeight(int height) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withCentered(boolean centered) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withX(int x) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withY(int y) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withFullscreenWidth(int fullscreenWidth) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withFullscreenHeight(int fullscreenHeight) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withRefreshRate(float refreshRate) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withMaximized(boolean maximized) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withResizable(boolean resizable) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withBordered(boolean bordered) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withAlwaysOnTop(boolean alwaysOnTop) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withFocusable(boolean focusable) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withOpacity(float opacity) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withMinWidth(int minWidth) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withMinHeight(int minHeight) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withMaxWidth(int maxWidth) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withMaxHeight(int maxHeight) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withMinAspect(float minAspect) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withMaxAspect(float maxAspect) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withMouseGrab(boolean mouseGrab) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withKeyboardGrab(boolean keyboardGrab) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withRelativeMouse(boolean relativeMouse) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withCursorVisible(boolean cursorVisible) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    public WindowSettings withKeepScreenOn(boolean keepScreenOn) {
        return new WindowSettings(mode, display, width, height, centered, x, y, fullscreenWidth, fullscreenHeight, refreshRate, maximized, resizable, bordered, alwaysOnTop, focusable, opacity, minWidth, minHeight, maxWidth, maxHeight, minAspect, maxAspect, mouseGrab, keyboardGrab, relativeMouse, cursorVisible, keepScreenOn);
    }

    /** Every setting as text, by name: to save. {@link #fromMap} reads it back. */
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("mode", String.valueOf(mode));
        map.put("display", String.valueOf(display));
        map.put("width", String.valueOf(width));
        map.put("height", String.valueOf(height));
        map.put("centered", String.valueOf(centered));
        map.put("x", String.valueOf(x));
        map.put("y", String.valueOf(y));
        map.put("fullscreenWidth", String.valueOf(fullscreenWidth));
        map.put("fullscreenHeight", String.valueOf(fullscreenHeight));
        map.put("refreshRate", String.valueOf(refreshRate));
        map.put("maximized", String.valueOf(maximized));
        map.put("resizable", String.valueOf(resizable));
        map.put("bordered", String.valueOf(bordered));
        map.put("alwaysOnTop", String.valueOf(alwaysOnTop));
        map.put("focusable", String.valueOf(focusable));
        map.put("opacity", String.valueOf(opacity));
        map.put("minWidth", String.valueOf(minWidth));
        map.put("minHeight", String.valueOf(minHeight));
        map.put("maxWidth", String.valueOf(maxWidth));
        map.put("maxHeight", String.valueOf(maxHeight));
        map.put("minAspect", String.valueOf(minAspect));
        map.put("maxAspect", String.valueOf(maxAspect));
        map.put("mouseGrab", String.valueOf(mouseGrab));
        map.put("keyboardGrab", String.valueOf(keyboardGrab));
        map.put("relativeMouse", String.valueOf(relativeMouse));
        map.put("cursorVisible", String.valueOf(cursorVisible));
        map.put("keepScreenOn", String.valueOf(keepScreenOn));
        return map;
    }

    /**
     * Settings saved by {@link #toMap()}: unknown keys are ignored, and missing or unreadable ones keep their default,
     * so preferences saved by another version still load.
     */
    public static WindowSettings fromMap(Map<String, String> map) {
        WindowSettings s = defaults();
        s = attempt(s, w -> w.withMode(read(map, "mode", WindowMode::valueOf, w.mode())));
        s = attempt(s, w -> w.withDisplay(read(map, "display", Function.identity(), w.display())));
        s = attempt(s, w -> w.withWidth(read(map, "width", Integer::parseInt, w.width())));
        s = attempt(s, w -> w.withHeight(read(map, "height", Integer::parseInt, w.height())));
        s = attempt(s, w -> w.withCentered(read(map, "centered", WindowSettings::bool, w.centered())));
        s = attempt(s, w -> w.withX(read(map, "x", Integer::parseInt, w.x())));
        s = attempt(s, w -> w.withY(read(map, "y", Integer::parseInt, w.y())));
        s = attempt(s, w -> w.withFullscreenWidth(read(map, "fullscreenWidth", Integer::parseInt, w.fullscreenWidth())));
        s = attempt(s, w -> w.withFullscreenHeight(read(map, "fullscreenHeight", Integer::parseInt, w.fullscreenHeight())));
        s = attempt(s, w -> w.withRefreshRate(read(map, "refreshRate", Float::parseFloat, w.refreshRate())));
        s = attempt(s, w -> w.withMaximized(read(map, "maximized", WindowSettings::bool, w.maximized())));
        s = attempt(s, w -> w.withResizable(read(map, "resizable", WindowSettings::bool, w.resizable())));
        s = attempt(s, w -> w.withBordered(read(map, "bordered", WindowSettings::bool, w.bordered())));
        s = attempt(s, w -> w.withAlwaysOnTop(read(map, "alwaysOnTop", WindowSettings::bool, w.alwaysOnTop())));
        s = attempt(s, w -> w.withFocusable(read(map, "focusable", WindowSettings::bool, w.focusable())));
        s = attempt(s, w -> w.withOpacity(read(map, "opacity", Float::parseFloat, w.opacity())));
        s = attempt(s, w -> w.withMinWidth(read(map, "minWidth", Integer::parseInt, w.minWidth())));
        s = attempt(s, w -> w.withMinHeight(read(map, "minHeight", Integer::parseInt, w.minHeight())));
        s = attempt(s, w -> w.withMaxWidth(read(map, "maxWidth", Integer::parseInt, w.maxWidth())));
        s = attempt(s, w -> w.withMaxHeight(read(map, "maxHeight", Integer::parseInt, w.maxHeight())));
        s = attempt(s, w -> w.withMinAspect(read(map, "minAspect", Float::parseFloat, w.minAspect())));
        s = attempt(s, w -> w.withMaxAspect(read(map, "maxAspect", Float::parseFloat, w.maxAspect())));
        s = attempt(s, w -> w.withMouseGrab(read(map, "mouseGrab", WindowSettings::bool, w.mouseGrab())));
        s = attempt(s, w -> w.withKeyboardGrab(read(map, "keyboardGrab", WindowSettings::bool, w.keyboardGrab())));
        s = attempt(s, w -> w.withRelativeMouse(read(map, "relativeMouse", WindowSettings::bool, w.relativeMouse())));
        s = attempt(s, w -> w.withCursorVisible(read(map, "cursorVisible", WindowSettings::bool, w.cursorVisible())));
        s = attempt(s, w -> w.withKeepScreenOn(read(map, "keepScreenOn", WindowSettings::bool, w.keepScreenOn())));
        return s;
    }

    /** {@code change} of {@code s}, or {@code s} where the value changed to is out of range. */
    private static WindowSettings attempt(WindowSettings s, UnaryOperator<WindowSettings> change) {
        try {
            return change.apply(s);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    /** {@code map}'s value of {@code key} through {@code parse}, or {@code fallback} if missing or unreadable. */
    private static <T> T read(Map<String, String> map, String key, Function<String, T> parse, T fallback) {
        String value = map.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            T parsed = parse.apply(value.strip());
            return parsed == null ? fallback : parsed;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static Boolean bool(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> null;
        };
    }
}
