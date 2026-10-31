package com.truesim.engine.xr;

/** An analog input of a hand's controller, or of a tracked hand's gestures. */
public enum XrAxis {
    /** 0 released to 1 pulled. */
    TRIGGER,
    /** 0 released to 1 squeezed. */
    SQUEEZE,
    /** -1 left to 1 right. */
    THUMBSTICK_X,
    /** -1 down to 1 up. */
    THUMBSTICK_Y,
    /** -1 left to 1 right. */
    TRACKPAD_X,
    /** -1 down to 1 up. */
    TRACKPAD_Y,
    /** A tracked hand's pinch: 0 apart to 1 together. */
    PINCH,
    /** A tracked hand's aim gesture: 0 to 1 completed. */
    AIM_ACTIVATE,
    /** A tracked hand's grasp: 0 open to 1 closed. */
    GRASP
}
