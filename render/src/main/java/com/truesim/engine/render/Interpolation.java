package com.truesim.engine.render;

/**
 * How instances moved in fixed steps are drawn between steps (application-api.md D91, D122): each frame shows the
 * world {@code blend} of the way from its pose at the step before to the last step's. Skinned joints moved in fixed
 * steps (animators on fixed steps, animation.md A5) follow the same choice: on the GPU the skinning blends the two
 * steps' joint matrices ({@link Deformation#blended(int, boolean)}; both GPU values alike), on the CPU the sync blends
 * them into the copy's matrices each frame, off they are drawn as each step leaves them.
 */
public enum Interpolation {
    /**
     * On the GPU, poses packed ({@link InstanceMotion.Layout#PACKED}): after a step, each moved instance's pose is
     * uploaded once (32 bytes; the step before's stays on the GPU), each frame passes one blend factor and a compute
     * pass blends them. Frames between steps cost the CPU nothing per instance. Positions within about a millimetre
     * 10 km from where the first instance started moving (the records' origin), rotations within 0.01 degrees.
     */
    GPU,
    /** On the GPU with positions in two parts as instances keep them ({@link InstanceMotion.Layout#PRECISE}): 64 bytes. */
    GPU_PRECISE,
    /**
     * On the CPU: each frame every moving instance's blended pose is computed and written into the instance table, as
     * any moved instance is (frames recorded without a frame graph run no blend pass).
     */
    CPU,
    /** Not at all: an instance is drawn as its last step left it, moving in steps. */
    OFF;

    /** Whether the GPU blends: {@link #GPU} or {@link #GPU_PRECISE}. */
    public boolean onGpu() {
        return this == GPU || this == GPU_PRECISE;
    }
}
