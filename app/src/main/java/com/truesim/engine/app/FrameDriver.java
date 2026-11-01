package com.truesim.engine.app;

/**
 * What paces frames: waits for the moment to start one (the display's vsync, a headset's predicted frame) and finishes
 * it (submits, presents). One plugin may claim the slot during startup ({@link Startup#driveFrames}), e.g. XR pacing
 * frames to the headset; the presenter is the default where rendering is installed ({@link Startup#defaultFrameDriver}),
 * and without either a frame starts at the developer setting {@link Engine#FRAME_RATE}.
 */
public interface FrameDriver {

    /** Waits until frame {@code frame} should start; input and the game's update follow. */
    void beginFrame(Frame frame);

    /** Finishes frame {@code frame}, after every plugin's {@link Plugin#afterUpdate}. */
    default void endFrame(Frame frame) {
    }
}
