package com.truesim.engine.platform;

/**
 * An input device: the id that events and handles use, its kind and name, and for gamepads and joysticks the USB
 * vendor and product ids (0 when unknown). An id is not reused while the device is connected.
 */
public record InputDevice(int id, Kind kind, String name, int vendor, int product) {

    public enum Kind {
        KEYBOARD, MOUSE, GAMEPAD, JOYSTICK,
        /** A device's own motion sensor ({@link Platform#sensors()}). */
        SENSOR,
        /** A standalone force feedback device ({@link Platform#haptics()}). */
        HAPTIC
    }
}
