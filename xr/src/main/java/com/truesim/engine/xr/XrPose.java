package com.truesim.engine.xr;

/** Where a hand is, for a purpose: its controller or tracked hand, seen as OpenXR's poses. */
public enum XrPose {
    /** Where the hand holds things: in the palm, for an object held. */
    GRIP,
    /** A ray from the hand pointing ahead, for pointing and aiming. */
    AIM,
    /** Between a tracked hand's thumb and index finger tips. */
    PINCH,
    /** A tracked hand's index finger tip, for pressing things near. */
    POKE
}
