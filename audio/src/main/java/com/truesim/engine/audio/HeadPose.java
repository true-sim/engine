package com.truesim.engine.audio;

/**
 * Where a tracked head is (§2.10: in VR the head is the listener): engine-xr's {@code XrInput::head} fits, so audio
 * needs nothing of the XR layer. Read on the game thread once a frame, allocation-free.
 */
@FunctionalInterface
public interface HeadPose {

    /** The head's world position and orientation (a unit quaternion, xyzw) into the arrays; false where not tracked. */
    boolean head(double[] position, float[] orientation);
}
