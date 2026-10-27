package com.truesim.engine.platform;

/** A mouse button. {@link #mask()} is its bit in the button state of {@link PlatformListener#mouseMoved}. */
public enum MouseButton {
    LEFT, MIDDLE, RIGHT, BACK, FORWARD,
    /** Any further button; its number is not kept. */
    OTHER;

    /** This button's bit in a button state; 0 for {@link #OTHER}. */
    public int mask() {
        return this == OTHER ? 0 : 1 << ordinal();
    }
}
