package com.truesim.engine.input;

import com.truesim.engine.app.Frame;

/**
 * A kind of device brought by a plugin (application-api.md D55, D64): it connects its devices
 * ({@link Input#connect}) and writes their controls each frame, before input takes them. XR controllers come this way
 * with the {@code Xr} plugin, their OpenXR actions being the device layer under the engine's actions and bindings.
 */
public interface InputSource {

    /** Added to {@code input}: register the kinds of device ({@link Input#addDeviceClass}) and connect what is there. */
    void start(Input input);

    /** At the frame's start, before input takes the values: write the devices' controls. Allocates nothing. */
    void sample(Frame frame);

    /**
     * Rumbles {@code device} at {@code low} and {@code high} frequency strengths (0 to 1) for {@code millis}
     * (D66); returns whether it can. Nothing by default.
     */
    default boolean rumble(Device device, float low, float high, int millis) {
        return false;
    }

    /** Input stops: let go of what it holds. */
    default void stop() {
    }
}
