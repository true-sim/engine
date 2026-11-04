package com.truesim.engine.interaction;

/** What drives a {@link ScenePointer}. */
public enum PointerKind {
    /** The mouse: a point in a window, buttons through the {@code scene.pointer} actions. */
    MOUSE,
    /** A finger: a point, pressed while it touches. */
    TOUCH,
    /** A pen: a point, its tip the primary button, its barrel buttons the others. */
    PEN,
    /** A gamepad's virtual cursor: a point moved by an action. */
    VIRTUAL_CURSOR,
    /** An XR controller's aim: a world ray of its own. */
    XR_RAY
}
