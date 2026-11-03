package com.truesim.engine.app;

/**
 * Where game logic runs in a frame, in order (application-api.md D90, D124): after the finished loads are delivered and
 * input is sampled ({@link Plugin#beforeUpdate}), each world's {@link #FIXED} steps, then {@link #UPDATE},
 * {@link #ANIMATE} and {@link #LATE}; then the scene is updated and synced to the GPU ({@link Plugin#afterUpdate}) and
 * the frame is rendered ({@link FrameDriver#endFrame}). {@link Engine#phase()} names the one running.
 */
public enum Phase {
    /** Each world's fixed steps, as many as its clock owes ({@link Application#fixedUpdate}). */
    FIXED,
    /** Once a frame, on the frame's time ({@link Application#update}): the game sets what animation plays. */
    UPDATE,
    /** Animators advance and pose the scene ({@link Plugin#animate}); world transforms brought up to date after. */
    ANIMATE,
    /** Once a frame, after animation ({@link Application#lateUpdate}): the game reads and overrides the pose. */
    LATE,
    /** Outside the phases: delivering loads, sampling input, syncing the scene, rendering. */
    NONE
}
