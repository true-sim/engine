package com.truesim.engine.xr;

/** A button of a hand's controller, or a hand's gesture that acts as one; a controller has those it has. */
public enum XrButton {
    /** The index finger's trigger (pulled far enough), or a simple controller's select. */
    TRIGGER,
    /** The grip (squeezed far enough). */
    SQUEEZE,
    /** A, or X on a left Touch controller. */
    PRIMARY,
    /** B, or Y on a left Touch controller. */
    SECONDARY,
    /** The menu button. */
    MENU,
    /** The thumbstick, pressed down. */
    THUMBSTICK,
    /** The trackpad, pressed. */
    TRACKPAD,
    /** A tracked hand's pinch (thumb and index finger together): a click. */
    PINCH,
    /** A tracked hand's aim gesture, completed: a click along the aim ray. */
    AIM_ACTIVATE
}
