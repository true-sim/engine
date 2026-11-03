package com.truesim.engine.platform;

/**
 * An input device: the id that events and handles use, its kind and name, and for gamepads and joysticks the USB
 * vendor and product ids (0 when unknown) and what tells the device again when it is plugged back in: SDL's GUID (32
 * hex digits: the bus, vendor, product and version, the same for identical controllers), the serial number and the
 * system's path (each empty when unknown). An id is not reused while the device is connected; it is a new one after
 * replugging, the GUID, serial and path are not (application-api.md D72).
 */
public record InputDevice(int id, Kind kind, String name, int vendor, int product, String guid, String serial,
    String path) {

    /** A device without GUID, serial or path: keyboards, mice, and devices the system tells nothing more of. */
    public InputDevice(int id, Kind kind, String name, int vendor, int product) {
        this(id, kind, name, vendor, product, "", "", "");
    }

    public enum Kind {
        KEYBOARD, MOUSE, GAMEPAD, JOYSTICK,
        /** A device's own motion sensor ({@link Platform#sensors()}). */
        SENSOR,
        /** A standalone force feedback device ({@link Platform#haptics()}). */
        HAPTIC
    }
}
