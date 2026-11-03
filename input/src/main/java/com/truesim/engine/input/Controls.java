package com.truesim.engine.input;

import com.truesim.engine.platform.GamepadAxis;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.HatPosition;
import com.truesim.engine.platform.Key;
import com.truesim.engine.platform.MouseButton;

/** The controls of the platform's devices: keys, mouse buttons and movement, gamepads, joysticks. */
public final class Controls {

    /** Either shift key (D71). */
    public static final Control SHIFT = DeviceClass.KEYBOARD.control(DeviceClass.KEY_SHIFT);
    /** Either control key. */
    public static final Control CONTROL = DeviceClass.KEYBOARD.control(DeviceClass.KEY_CONTROL);
    /** Either alt key. */
    public static final Control ALT = DeviceClass.KEYBOARD.control(DeviceClass.KEY_ALT);
    /** Either meta key (Windows, Command, Super). */
    public static final Control META = DeviceClass.KEYBOARD.control(DeviceClass.KEY_META);
    /** The mouse's movement right since the last frame, in pixels (relative while the mouse is captured). */
    public static final Control MOUSE_X = DeviceClass.MOUSE.control(DeviceClass.MOUSE_X);
    /** The mouse's movement down since the last frame, in pixels. */
    public static final Control MOUSE_Y = DeviceClass.MOUSE.control(DeviceClass.MOUSE_Y);
    /** The wheel's scrolling right since the last frame. */
    public static final Control WHEEL_X = DeviceClass.MOUSE.control(DeviceClass.WHEEL_X);
    /** The wheel's scrolling away from the user since the last frame. */
    public static final Control WHEEL_Y = DeviceClass.MOUSE.control(DeviceClass.WHEEL_Y);

    /** A key, by where it is (layout-independent). */
    public static Control key(Key key) {
        return DeviceClass.KEYBOARD.control(key.ordinal());
    }

    /** A mouse button. */
    public static Control mouse(MouseButton button) {
        return DeviceClass.MOUSE.control(button.ordinal());
    }

    /** A gamepad button, by position. */
    public static Control pad(GamepadButton button) {
        return DeviceClass.GAMEPAD.control(button.ordinal());
    }

    /** A gamepad axis: sticks from -1 to 1 (y positive is down), triggers from 0 to 1. */
    public static Control pad(GamepadAxis axis) {
        return DeviceClass.GAMEPAD.control(DeviceClass.PAD_AXIS + axis.ordinal());
    }

    /**
     * A stick's direction as a half axis, 0 to 1: {@code right} the right stick, else the left; {@code direction} one
     * of {@link HatPosition#UP}, {@code DOWN}, {@code LEFT}, {@code RIGHT}.
     */
    public static Control stick(boolean right, HatPosition direction) {
        return DeviceClass.GAMEPAD.control(DeviceClass.PAD_STICKS + 4 * (right ? 1 : 0) + direction(direction, 0, 1, 2, 3));
    }

    /** Button {@code n} (from 0) of a joystick that is not a gamepad. */
    public static Control joystickButton(int n) {
        return DeviceClass.JOYSTICK.control(check(n, DeviceClass.JOY_BUTTONS));
    }

    /** Axis {@code n} (from 0) of a joystick that is not a gamepad, -1 to 1. */
    public static Control joystickAxis(int n) {
        return DeviceClass.JOYSTICK.control(DeviceClass.JOY_AXIS + check(n, DeviceClass.JOY_AXES));
    }

    /** A direction ({@code UP}, {@code RIGHT}, {@code DOWN}, {@code LEFT}) of hat {@code n} of a joystick. */
    public static Control joystickHat(int n, HatPosition direction) {
        return DeviceClass.JOYSTICK.control(DeviceClass.JOY_HAT + 4 * check(n, DeviceClass.JOY_HATS)
            + direction(direction, 0, 2, 3, 1));
    }

    private static int direction(HatPosition direction, int up, int down, int left, int right) {
        return switch (direction) {
            case UP -> up;
            case DOWN -> down;
            case LEFT -> left;
            case RIGHT -> right;
            default -> throw new IllegalArgumentException("A direction is UP, DOWN, LEFT or RIGHT: " + direction);
        };
    }

    private static int check(int n, int count) {
        if (n < 0 || n >= count) {
            throw new IllegalArgumentException("From 0 to " + (count - 1) + ": " + n);
        }
        return n;
    }

    private Controls() {
    }
}
