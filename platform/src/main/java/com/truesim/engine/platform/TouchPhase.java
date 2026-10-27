package com.truesim.engine.platform;

/** The phase of a touch on a touchscreen or a gamepad touchpad. */
public enum TouchPhase {
    DOWN, MOVED, UP,
    /** The system took the touch away, e.g. for a gesture; treat it as lifted without acting on it. */
    CANCELED
}
