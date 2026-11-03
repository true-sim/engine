package com.truesim.engine.input;

/** What a device's control reports. */
public enum ControlKind {
    /** A button: 0 up, 1 down. */
    BUTTON,
    /** An axis from -1 to 1, held where it is (a stick). */
    AXIS,
    /** An axis from 0 to 1 (a trigger, a stick's direction). */
    HALF_AXIS,
    /** Movement since the last frame, unbounded (the mouse, the wheel, a trackball): 0 when nothing moved. */
    DELTA,
    /** A position and orientation (an XR controller, the head); not a number. */
    POSE
}
