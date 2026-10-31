package com.truesim.engine.xr;

/** A capacitive sensor of a hand's controller: a finger resting on it. */
public enum XrTouch {
    TRIGGER,
    PRIMARY,
    SECONDARY,
    THUMBSTICK,
    TRACKPAD,
    /** The rest beside the buttons, where a thumb lies when on nothing else. */
    THUMBREST
}
