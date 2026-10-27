package com.truesim.engine.platform;

/**
 * Receives platform events from {@link Platform#pollEvents}, on the thread that polls. Every method has an empty
 * default, so a listener overrides what it needs. Parameters are engine types and plain values, never the backend's
 * own, so forwarding allocates nothing and an input module built on top does not depend on SDL.
 *
 * <p>Windows and devices are named by id ({@link Window#id()}, {@link InputDevice#id()}, {@link Controller#id()});
 * keyboard and mouse ids are 0 when the system cannot tell devices apart. Timestamps are nanoseconds on the
 * platform's clock; sensor timestamps are the device's own, in nanoseconds.
 */
public interface PlatformListener {

    /** The user or system asked the application to quit, e.g. the last window closed or a signal. */
    default void quitRequested() {
    }

    // ---------------------------------------------------------------- windows

    /** The window's close button was pressed; the window stays open until {@link Window#close()}. */
    default void windowCloseRequested(int window) {
    }

    /** The window's size in screen coordinates changed. */
    default void windowResized(int window, int width, int height) {
    }

    /** The window's size in pixels changed: swapchains must be recreated with the new size. */
    default void windowPixelSizeChanged(int window, int width, int height) {
    }

    default void windowMoved(int window, int x, int y) {
    }

    /** Minimized (nothing to render), or restored. */
    default void windowMinimized(int window, boolean minimized) {
    }

    default void windowFocusChanged(int window, boolean focused) {
    }

    /** The window moved to another display, e.g. with a different scale or refresh rate. */
    default void windowDisplayChanged(int window, int display) {
    }

    /** Displays were connected, disconnected or changed mode: list them again with {@link Platform#displays()}. */
    default void displaysChanged() {
    }

    // ---------------------------------------------------------------- devices

    /**
     * A keyboard, mouse, gamepad or joystick was connected (also for those present at startup). Gamepads and
     * joysticks are open by then: {@link Platform#gamepad} and {@link Platform#joystick} return them.
     */
    default void deviceAdded(InputDevice device) {
    }

    /** A device was disconnected; its {@link Controller} is closed. */
    default void deviceRemoved(InputDevice.Kind kind, int device) {
    }

    /** A gamepad's or joystick's power changed; {@code percent} is the battery charge, or -1 when unknown. */
    default void batteryChanged(int controller, PowerState state, int percent) {
    }

    // ---------------------------------------------------------------- keyboard and text

    /**
     * A key went down or up. {@code key} is the physical key (layout-independent, for game controls);
     * {@code character} the Unicode code point the key has in the current layout, or 0 for keys without one (e.g.
     * arrows); {@code modifiers} are {@link KeyModifiers} bits. Typed text comes through {@link #text}.
     */
    default void key(long timestamp, int window, int keyboard, Key key, int character, int modifiers, boolean down,
        boolean repeat) {
    }

    /** Text typed while text input is on for the window ({@link Window#startTextInput()}). */
    default void text(int window, String text) {
    }

    // ---------------------------------------------------------------- mouse

    /**
     * The mouse moved to ({@code x}, {@code y}) in the window, by ({@code dx}, {@code dy}); {@code buttons} has the
     * {@link MouseButton#mask()} bits of the buttons held.
     */
    default void mouseMoved(long timestamp, int window, int mouse, float x, float y, float dx, float dy, int buttons) {
    }

    /** A button went down or up; {@code clicks} counts quick successive clicks (2 for a double click). */
    default void mouseButton(long timestamp, int window, int mouse, MouseButton button, boolean down, int clicks,
        float x, float y) {
    }

    /** Scrolled by ({@code dx}, {@code dy}); positive {@code dy} is away from the user. */
    default void mouseWheel(long timestamp, int window, int mouse, float dx, float dy) {
    }

    // ---------------------------------------------------------------- gamepads

    /** A gamepad axis moved; sticks from -1 to 1, triggers from 0 to 1. */
    default void gamepadAxis(long timestamp, int gamepad, GamepadAxis axis, float value) {
    }

    default void gamepadButton(long timestamp, int gamepad, GamepadButton button, boolean down) {
    }

    /**
     * A finger on a gamepad's touchpad: ({@code x}, {@code y}) from 0 to 1 from the top left, {@code pressure} from
     * 0 to 1.
     */
    default void gamepadTouchpad(long timestamp, int gamepad, int touchpad, int finger, TouchPhase phase, float x,
        float y, float pressure) {
    }

    /** A reading of a gamepad sensor enabled with {@link Gamepad#setSensorEnabled}; see {@link SensorType}. */
    default void gamepadSensor(long timestamp, int gamepad, SensorType sensor, float x, float y, float z,
        long sensorTimestamp) {
    }

    /** A gamepad's mapping changed, e.g. after {@link Platform#addGamepadMappings}: its layout may differ. */
    default void gamepadRemapped(int gamepad) {
    }

    // ---------------------------------------------------------------- joysticks

    /** An axis of a joystick that is not a gamepad, from -1 to 1. */
    default void joystickAxis(long timestamp, int joystick, int axis, float value) {
    }

    /** A button of a joystick that is not a gamepad. */
    default void joystickButton(long timestamp, int joystick, int button, boolean down) {
    }

    /** A hat (digital direction pad) of a joystick that is not a gamepad. */
    default void joystickHat(long timestamp, int joystick, int hat, HatPosition position) {
    }

    /** A trackball of a joystick moved by ({@code dx}, {@code dy}). */
    default void joystickBall(long timestamp, int joystick, int ball, int dx, int dy) {
    }

    // ---------------------------------------------------------------- sensors, touch and pens

    /** A reading of a sensor opened with {@link Platform#openSensor}. */
    default void sensor(long timestamp, int sensor, float x, float y, float z, long sensorTimestamp) {
    }

    /**
     * A finger on a touchscreen or trackpad ({@link Platform#touchDevices()}): ({@code x}, {@code y}) from 0 to 1 in
     * the window (or on the pad), moved by ({@code dx}, {@code dy}), with {@code pressure} from 0 to 1.
     */
    default void touch(long timestamp, int window, long device, long finger, TouchPhase phase, float x, float y,
        float dx, float dy, float pressure) {
    }

    /** A pen came close enough to be tracked, or left. */
    default void penProximity(long timestamp, int window, int pen, boolean near) {
    }

    /** The pen's tip ({@code eraser} false) or eraser touched or left the surface at ({@code x}, {@code y}). */
    default void penTouch(long timestamp, int window, int pen, float x, float y, boolean eraser, boolean down) {
    }

    /** A barrel button (from 1) went down or up. */
    default void penButton(long timestamp, int window, int pen, float x, float y, int button, boolean down) {
    }

    /** The pen moved; {@code state} has {@link PenState} bits. */
    default void penMoved(long timestamp, int window, int pen, float x, float y, int state) {
    }

    /** A pen axis changed, e.g. pressure or tilt. */
    default void penAxis(long timestamp, int window, int pen, float x, float y, PenAxis axis, float value) {
    }
}
