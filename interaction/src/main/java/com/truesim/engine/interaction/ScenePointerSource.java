package com.truesim.engine.interaction;

/**
 * Writes pointers into the {@link SceneInteraction} at each frame's start: the mouse, fingers, a pen, XR rays, a
 * virtual cursor, a test's. A pointer written in a frame is there that frame; one not written any more leaves (its
 * nodes see it exit). Must not allocate.
 */
@FunctionalInterface
public interface ScenePointerSource {

    /** Writes this frame's pointers through {@code pointers.pointer(id, kind)}. */
    void poll(SceneInteraction pointers);
}
