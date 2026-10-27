package com.truesim.engine.platform;

/**
 * An input device: the id that input events carry, its kind and name, and for gamepads and joysticks the USB vendor
 * and product ids (0 when unknown). Event methods name devices by id; an id is not reused while the device is
 * connected.
 */
public record InputDevice(int id, Kind kind, String name, int vendor, int product) {

    public enum Kind {
        KEYBOARD, MOUSE, GAMEPAD, JOYSTICK
    }
}
