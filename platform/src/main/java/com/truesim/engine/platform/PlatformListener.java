package com.truesim.engine.platform;

/**
 * Receives platform events from {@link Platform#pollEvents}, on the thread that polls. Every method has an empty
 * default, so a listener overrides what it needs. Parameters are plain values, so forwarding allocates nothing; an
 * input module builds actions, bindings and state on top.
 *
 * <p>Windows, keyboards, mice, gamepads and joysticks are named by id ({@link Window#id()}, {@link InputDevice#id()});
 * keyboard and mouse ids are 0 when the system cannot tell devices apart. Timestamps are nanoseconds on SDL's clock.
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

    /** A keyboard, mouse, gamepad or joystick was connected (also for those present at startup). */
    default void deviceAdded(InputDevice device) {
    }

    default void deviceRemoved(InputDevice.Kind kind, int device) {
    }

    // ---------------------------------------------------------------- keyboard and text

    /**
     * A key went down or up. {@code scancode} is the physical key (layout-independent, for game controls),
     * {@code key} the keycode of the current layout, {@code modifiers} SDL's modifier bits.
     */
    default void key(long timestamp, int window, int keyboard, int scancode, int key, int modifiers, boolean down,
        boolean repeat) {
    }

    /** Text typed while text input is on for the window ({@link Window#startTextInput()}). */
    default void text(int window, String text) {
    }

    // ---------------------------------------------------------------- mouse

    /** The mouse moved to ({@code x}, {@code y}) in the window, by ({@code dx}, {@code dy}); {@code buttons} held. */
    default void mouseMoved(long timestamp, int window, int mouse, float x, float y, float dx, float dy, int buttons) {
    }

    /** Button 1 is left, 2 middle, 3 right, 4 and 5 the side buttons. */
    default void mouseButton(long timestamp, int window, int mouse, int button, boolean down, int clicks, float x,
        float y) {
    }

    /** Scrolled by ({@code dx}, {@code dy}); positive {@code dy} is away from the user. */
    default void mouseWheel(long timestamp, int window, int mouse, float dx, float dy) {
    }

    // ---------------------------------------------------------------- gamepads and joysticks

    /** A gamepad axis moved; sticks from -1 to 1, triggers from 0 to 1. */
    default void gamepadAxis(long timestamp, int gamepad, int axis, float value) {
    }

    default void gamepadButton(long timestamp, int gamepad, int button, boolean down) {
    }

    /** An axis of a joystick that is not a gamepad, from -1 to 1. */
    default void joystickAxis(long timestamp, int joystick, int axis, float value) {
    }

    /** A button of a joystick that is not a gamepad. */
    default void joystickButton(long timestamp, int joystick, int button, boolean down) {
    }
}
