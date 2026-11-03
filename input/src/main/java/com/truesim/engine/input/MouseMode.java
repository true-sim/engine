package com.truesim.engine.input;

/** What a context does with the mouse (application-api.md D61). */
public enum MouseMode {
    /** As the contexts under it say. */
    INHERIT,
    /** Hidden and held, reporting movement only: mouse look. */
    CAPTURED,
    /** A pointer: menus, the desktop. */
    RELEASED
}
