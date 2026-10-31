package com.truesim.engine.platform;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    public static final Setting<WindowMode> MODE = Setting.choice("window.mode", WindowMode.WINDOWED, "how it covers the screen");
    public static final Setting<String> DISPLAY = Setting.text("window.display", "", "the display it is on, by key (\"\": the primary)");
    public static final Setting<Integer> WIDTH = Setting.integer("window.width", 1280, 1, 65535, "windowed width, in screen coordinates");
    public static final Setting<Integer> HEIGHT = Setting.integer("window.height", 720, 1, 65535, "windowed height");
    public static final Setting<Boolean> CENTERED = Setting.bool("window.centered", true, "windowed: centered on its display, else at (x, y)");
    public static final Setting<Integer> X = Setting.integer("window.x", 0, Integer.MIN_VALUE, Integer.MAX_VALUE, "windowed position, in screen coordinates");
    public static final Setting<Integer> Y = Setting.integer("window.y", 0, Integer.MIN_VALUE, Integer.MAX_VALUE, "windowed position, in screen coordinates");
    public static final Setting<Integer> FULLSCREEN_WIDTH = Setting.integer("window.fullscreenWidth", 0, 0, 65535, "exclusive fullscreen: the display mode wanted (0: the desktop's)");
    public static final Setting<Integer> FULLSCREEN_HEIGHT = Setting.integer("window.fullscreenHeight", 0, 0, 65535, "exclusive fullscreen: the display mode wanted (0: the desktop's)");
    public static final Setting<Float> REFRESH_RATE = Setting.decimal("window.refreshRate", 0f, 0, 1000, "exclusive fullscreen: in Hz (0: the desktop's)");
    public static final Setting<Boolean> MAXIMIZED = Setting.bool("window.maximized", false, "windowed: maximized");
    public static final Setting<Boolean> RESIZABLE = Setting.bool("window.resizable", true, "the user may resize it");
    public static final Setting<Boolean> BORDERED = Setting.bool("window.bordered", true, "has decorations");
    public static final Setting<Boolean> ALWAYS_ON_TOP = Setting.bool("window.alwaysOnTop", false, "stays above other windows");
    public static final Setting<Boolean> FOCUSABLE = Setting.bool("window.focusable", true, "may take input focus");
    public static final Setting<Float> OPACITY = Setting.decimal("window.opacity", 1f, 0, 1, "0 (transparent) to 1 (opaque), where the system supports it");
    public static final Setting<Integer> MIN_WIDTH = Setting.integer("window.minWidth", 0, 0, 65535, "smallest size the user may resize it to (0: no limit)");
    public static final Setting<Integer> MIN_HEIGHT = Setting.integer("window.minHeight", 0, 0, 65535, "smallest size the user may resize it to (0: no limit)");
    public static final Setting<Integer> MAX_WIDTH = Setting.integer("window.maxWidth", 0, 0, 65535, "largest size (0: no limit)");
    public static final Setting<Integer> MAX_HEIGHT = Setting.integer("window.maxHeight", 0, 0, 65535, "largest size (0: no limit)");
    public static final Setting<Float> MIN_ASPECT = Setting.decimal("window.minAspect", 0f, 0, 100, "narrowest width over height while resizing (0: no limit)");
    public static final Setting<Float> MAX_ASPECT = Setting.decimal("window.maxAspect", 0f, 0, 100, "widest (0: no limit)");
    public static final Setting<Boolean> MOUSE_GRAB = Setting.bool("window.mouseGrab", false, "confines the mouse to the window while it has focus");
    public static final Setting<Boolean> KEYBOARD_GRAB = Setting.bool("window.keyboardGrab", false, "takes system shortcuts (such as Alt+Tab) while it has focus");
    public static final Setting<Boolean> RELATIVE_MOUSE = Setting.bool("window.relativeMouse", false, "hides the mouse and reports motion only, for mouse look");
    public static final Setting<Boolean> CURSOR_VISIBLE = Setting.bool("window.cursorVisible", true, "shows the mouse cursor");
    public static final Setting<Boolean> KEEP_SCREEN_ON = Setting.bool("window.keepScreenOn", true, "keeps the screen saver and display sleep off");

    /** Every window setting, in the order of the record's components. */
    public static final List<Setting<?>> ALL = List.of(MODE, DISPLAY, WIDTH, HEIGHT, CENTERED, X, Y, FULLSCREEN_WIDTH, FULLSCREEN_HEIGHT, REFRESH_RATE, MAXIMIZED, RESIZABLE, BORDERED, ALWAYS_ON_TOP, FOCUSABLE, OPACITY, MIN_WIDTH, MIN_HEIGHT, MAX_WIDTH, MAX_HEIGHT, MIN_ASPECT, MAX_ASPECT, MOUSE_GRAB, KEYBOARD_GRAB, RELATIVE_MOUSE, CURSOR_VISIBLE, KEEP_SCREEN_ON);

    /** Each setting's default ({@link #ALL}): windowed, 1280 x 720, centered on the primary display, decorated. */
    public static WindowSettings defaults() {
        return from(new EngineSettings());
    }

    /** The window settings {@code settings} holds (a window's scoped view, say): defaults where none is set. */
    public static WindowSettings from(EngineSettings settings) {
        return new WindowSettings(settings.get(MODE), settings.get(DISPLAY), settings.get(WIDTH), settings.get(HEIGHT), settings.get(CENTERED), settings.get(X), settings.get(Y), settings.get(FULLSCREEN_WIDTH), settings.get(FULLSCREEN_HEIGHT), settings.get(REFRESH_RATE), settings.get(MAXIMIZED), settings.get(RESIZABLE), settings.get(BORDERED), settings.get(ALWAYS_ON_TOP), settings.get(FOCUSABLE), settings.get(OPACITY), settings.get(MIN_WIDTH), settings.get(MIN_HEIGHT), settings.get(MAX_WIDTH), settings.get(MAX_HEIGHT), settings.get(MIN_ASPECT), settings.get(MAX_ASPECT), settings.get(MOUSE_GRAB), settings.get(KEYBOARD_GRAB), settings.get(RELATIVE_MOUSE), settings.get(CURSOR_VISIBLE), settings.get(KEEP_SCREEN_ON));
    }

    /** Writes these into {@code settings} (a window's scoped view, say), telling its listeners what changed. */
    public EngineSettings store(EngineSettings settings) {
        settings.set(MODE, mode);
        settings.set(DISPLAY, display);
        settings.set(WIDTH, width);
        settings.set(HEIGHT, height);
        settings.set(CENTERED, centered);
        settings.set(X, x);
        settings.set(Y, y);
        settings.set(FULLSCREEN_WIDTH, fullscreenWidth);
        settings.set(FULLSCREEN_HEIGHT, fullscreenHeight);
        settings.set(REFRESH_RATE, refreshRate);
        settings.set(MAXIMIZED, maximized);
        settings.set(RESIZABLE, resizable);
        settings.set(BORDERED, bordered);
        settings.set(ALWAYS_ON_TOP, alwaysOnTop);
        settings.set(FOCUSABLE, focusable);
        settings.set(OPACITY, opacity);
        settings.set(MIN_WIDTH, minWidth);
        settings.set(MIN_HEIGHT, minHeight);
        settings.set(MAX_WIDTH, maxWidth);
        settings.set(MAX_HEIGHT, maxHeight);
        settings.set(MIN_ASPECT, minAspect);
        settings.set(MAX_ASPECT, maxAspect);
        settings.set(MOUSE_GRAB, mouseGrab);
        settings.set(KEYBOARD_GRAB, keyboardGrab);
        settings.set(RELATIVE_MOUSE, relativeMouse);
        settings.set(CURSOR_VISIBLE, cursorVisible);
        settings.set(KEEP_SCREEN_ON, keepScreenOn);
        return settings;
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

    /** Every setting as text, by its {@link #ALL setting}'s name: to save. {@link #fromMap} reads it back. */
    public Map<String, String> toMap() {
        return store(new EngineSettings()).toMap();
    }

    /**
     * Settings saved by {@link #toMap()}: unknown keys are ignored, and missing, unreadable or out-of-range ones keep
     * their default, so preferences saved by another version still load.
     */
    public static WindowSettings fromMap(Map<String, String> map) {
        return from(new EngineSettings().load(map));
    }
}
