package com.truesim.engine.platform;

/** A pen's state, as bits of {@code state} in pen events. */
public final class PenState {

    /** The tip touches the surface. */
    public static final int DOWN = 1;
    /** Barrel button 1; button n is {@code BUTTON1 << (n - 1)}, up to 5. */
    public static final int BUTTON1 = 1 << 1;
    public static final int BUTTON2 = 1 << 2;
    public static final int BUTTON3 = 1 << 3;
    public static final int BUTTON4 = 1 << 4;
    public static final int BUTTON5 = 1 << 5;
    /** The eraser end is in use. */
    public static final int ERASER = 1 << 6;
    /** The pen is close enough to be tracked. */
    public static final int IN_PROXIMITY = 1 << 7;

    private PenState() {
    }
}
