package com.truesim.engine.platform;

/** A pen: on a screen (drawn where it points) or on a separate tablet. */
public enum PenType {
    UNKNOWN,
    /** Pen on screen: positions are where the pen points. */
    DIRECT,
    /** A graphics tablet: positions are mapped to the window. */
    INDIRECT
}
