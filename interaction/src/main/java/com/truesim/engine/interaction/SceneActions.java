package com.truesim.engine.interaction;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.input.ActionGroup;
import com.truesim.engine.input.Axis2Action;
import com.truesim.engine.input.BinaryAction;
import com.truesim.engine.input.Binding;
import com.truesim.engine.input.Controls;
import com.truesim.engine.input.Input;
import com.truesim.engine.input.PoseAction;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.Key;
import com.truesim.engine.platform.MouseButton;
import com.truesim.engine.xr.Hand;
import com.truesim.engine.xr.XrButton;
import com.truesim.engine.xr.XrControls;
import com.truesim.engine.xr.XrPose;
import java.util.Locale;

/**
 * The scene interaction's input actions: the {@code scene} group, its bindings settings like any action's
 * ({@code input.scene.pointer.press}), rebound and saved by players (application-api.md D58). Pointer positions come
 * from the platform (the mouse, fingers, pens), XR rays from their aim poses; buttons, the selection's modifiers and
 * commands are actions.
 *
 * <pre>{@code
 * input.rebind(player, SceneActions.PRESS, 0, Binding.mouse(MouseButton.RIGHT));     // a left-handed player
 * input.enable(SceneActions.SELECTION, false);                                      // no selection keys in play
 * }</pre>
 */
public final class SceneActions {

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    private static final com.truesim.engine.input.Control SHORTCUT = MAC ? Controls.META : Controls.CONTROL;

    /** Everything the scene interaction reads. */
    public static final ActionGroup SCENE = ActionGroup.root("scene");
    /** Pointer buttons and the pointers driven by actions (XR rays, a virtual cursor). */
    public static final ActionGroup POINTER = SCENE.child("pointer");
    /** Selection's modifiers and commands. */
    public static final ActionGroup SELECTION = SCENE.child("selection");

    /** The mouse's primary button over the scene: its left. */
    public static final BinaryAction PRESS = POINTER.binary("press", Binding.mouse(MouseButton.LEFT));
    /** The mouse's secondary button: its right (context). */
    public static final BinaryAction CONTEXT = POINTER.binary("context", Binding.mouse(MouseButton.RIGHT));
    /** The mouse's middle button. */
    public static final BinaryAction MIDDLE = POINTER.binary("middle", Binding.mouse(MouseButton.MIDDLE));
    /** A gamepad's virtual cursor moving: the right stick. */
    public static final Axis2Action CURSOR = POINTER.axis2("cursor", Binding.stick(true));
    /** A gamepad's virtual cursor's primary button: south. */
    public static final BinaryAction CURSOR_PRESS = POINTER.binary("cursorPress", Binding.pad(GamepadButton.SOUTH));
    /** The left XR ray: bound to the controller's aim pose when a headset is there. */
    public static final PoseAction AIM_LEFT = POINTER.pose("aimLeft");
    /** The right XR ray. */
    public static final PoseAction AIM_RIGHT = POINTER.pose("aimRight");
    /** The left XR ray's primary button: the trigger. */
    public static final BinaryAction SELECT_LEFT = POINTER.binary("selectLeft");
    /** The right XR ray's primary button. */
    public static final BinaryAction SELECT_RIGHT = POINTER.binary("selectRight");

    /** Held while clicking: adds to the selection (Shift). */
    public static final BinaryAction ADD = SELECTION.binary("add", Binding.of(Controls.SHIFT));
    /** Held while clicking: toggles in or out of the selection (Ctrl, Cmd on macOS). */
    public static final BinaryAction TOGGLE = SELECTION.binary("toggle", Binding.of(SHORTCUT));
    /** Selects every pickable object (Ctrl+A, Cmd+A). */
    public static final BinaryAction ALL = SELECTION.binary("all", Binding.chord(SHORTCUT, Controls.key(Key.A)));
    /** Selects nothing (Escape). */
    public static final BinaryAction NONE = SELECTION.binary("none", Binding.key(Key.ESCAPE));

    private SceneActions() {
    }

    /** The game's default bindings of the XR actions (a headset's input added): the aim poses and the triggers. */
    @AllowAllocation("set up")
    public static void xrDefaults(Input input) {
        input.defaults(AIM_LEFT, Binding.of(XrControls.pose(Hand.LEFT, XrPose.AIM)));
        input.defaults(AIM_RIGHT, Binding.of(XrControls.pose(Hand.RIGHT, XrPose.AIM)));
        input.defaults(SELECT_LEFT, Binding.of(XrControls.button(Hand.LEFT, XrButton.TRIGGER)));
        input.defaults(SELECT_RIGHT, Binding.of(XrControls.button(Hand.RIGHT, XrButton.TRIGGER)));
    }
}
