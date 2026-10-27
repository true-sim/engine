package com.truesim.engine.platform;

/** A touch device: a screen touched where the content is, or a pad elsewhere. */
public enum TouchDeviceType {
    UNKNOWN,
    /** A touchscreen: positions are on the window. */
    DIRECT,
    /** A trackpad with absolute positions. */
    INDIRECT_ABSOLUTE,
    /** A trackpad with relative positions. */
    INDIRECT_RELATIVE
}
