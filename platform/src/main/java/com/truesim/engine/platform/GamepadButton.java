package com.truesim.engine.platform;

/** A gamepad button, by position: {@link #SOUTH} is A on Xbox and cross on PlayStation pads (see {@link Gamepad#label}). */
public enum GamepadButton {
    SOUTH, EAST, WEST, NORTH,
    BACK, GUIDE, START,
    LEFT_STICK, RIGHT_STICK, LEFT_SHOULDER, RIGHT_SHOULDER,
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
    /** Xbox Series share, PS5 microphone, Switch Pro capture, ... */
    MISC1,
    RIGHT_PADDLE1, LEFT_PADDLE1, RIGHT_PADDLE2, LEFT_PADDLE2,
    /** The PlayStation touchpad click. */
    TOUCHPAD,
    MISC2, MISC3, MISC4, MISC5, MISC6
}
