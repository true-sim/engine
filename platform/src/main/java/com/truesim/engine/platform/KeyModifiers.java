package com.truesim.engine.platform;

/** The modifier keys held or locked during a key event, as bits of {@code modifiers}. */
public final class KeyModifiers {

    public static final int LEFT_SHIFT = 1;
    public static final int RIGHT_SHIFT = 1 << 1;
    public static final int LEFT_CONTROL = 1 << 2;
    public static final int RIGHT_CONTROL = 1 << 3;
    public static final int LEFT_ALT = 1 << 4;
    public static final int RIGHT_ALT = 1 << 5;
    public static final int LEFT_META = 1 << 6;
    public static final int RIGHT_META = 1 << 7;
    /** AltGr on some layouts. */
    public static final int MODE = 1 << 8;
    /** The ISO level 5 shift. */
    public static final int LEVEL5 = 1 << 9;
    public static final int NUM_LOCK = 1 << 10;
    public static final int CAPS_LOCK = 1 << 11;
    public static final int SCROLL_LOCK = 1 << 12;

    public static final int SHIFT = LEFT_SHIFT | RIGHT_SHIFT;
    public static final int CONTROL = LEFT_CONTROL | RIGHT_CONTROL;
    public static final int ALT = LEFT_ALT | RIGHT_ALT;
    /** Windows, Command or Super. */
    public static final int META = LEFT_META | RIGHT_META;

    private KeyModifiers() {
    }

    /** Whether any of {@code mask}'s modifiers is in {@code modifiers}, e.g. {@code has(modifiers, SHIFT)}. */
    public static boolean has(int modifiers, int mask) {
        return (modifiers & mask) != 0;
    }
}
