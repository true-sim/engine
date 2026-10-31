package com.truesim.engine.xr;

/**
 * What a headset's input reports when polled ({@link XrInput#poll}): changes since the last poll. Every method does
 * nothing by default.
 */
public interface XrListener {

    /** {@code button} of {@code hand} was pressed or released. */
    default void button(Hand hand, XrButton button, boolean pressed) {
    }

    /** A finger came onto or left {@code touch} of {@code hand}'s controller. */
    default void touch(Hand hand, XrTouch touch, boolean touched) {
    }

    /**
     * What {@code hand} holds changed: an interaction profile path such as
     * {@code /interaction_profiles/oculus/touch_controller} or {@code /interaction_profiles/ext/hand_interaction_ext}
     * for a tracked hand, or empty for nothing.
     */
    default void profile(Hand hand, String profile) {
    }
}
