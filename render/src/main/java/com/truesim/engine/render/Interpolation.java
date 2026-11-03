package com.truesim.engine.render;

/**
 * How instances moved in fixed steps are drawn between steps (application-api.md D91, D122): each frame shows the
 * world {@code blend} of the way from its pose at the step before to the last step's.
 */
public enum Interpolation {
    /**
     * On the GPU: after a step, each moved instance's two poses are uploaded once ({@link InstanceMotion}); each frame
     * passes one blend factor and a compute pass blends them. Frames between steps cost the CPU nothing per instance.
     */
    GPU,
    /**
     * On the CPU: each frame every moving instance's blended pose is computed and written into the instance table, as
     * any moved instance is.
     */
    CPU,
    /** Not at all: an instance is drawn as its last step left it, moving in steps. */
    OFF
}
