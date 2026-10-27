package com.truesim.engine.platform;

/** What a haptic (force feedback) device can do: the kinds of {@link HapticEffect} and the device controls. */
public enum HapticFeature {
    CONSTANT, SINE, SQUARE, TRIANGLE, SAWTOOTH_UP, SAWTOOTH_DOWN, RAMP, SPRING, DAMPER, INERTIA, FRICTION, LEFT_RIGHT,
    CUSTOM,
    /** {@link Haptic#setGain} works. */
    GAIN,
    /** {@link Haptic#setAutocenter} works. */
    AUTOCENTER,
    /** {@link Haptic#isPlaying} works. */
    STATUS,
    /** {@link Haptic#pause} works. */
    PAUSE
}
