package com.truesim.engine.xr;

/** What input reads of a headset each frame: {@link XrInput}'s state, or a test's. */
interface XrState {

    boolean pressed(Hand hand, XrButton button);

    boolean touched(Hand hand, XrTouch touch);

    float axis(Hand hand, XrAxis axis);

    boolean pose(Hand hand, XrPose pose, double[] position, float[] orientation);

    boolean head(double[] position, float[] orientation);

    boolean gaze(double[] position, float[] orientation);

    void vibrate(Hand hand, float amplitude, float frequency, long nanos);
}
