package com.truesim.engine.ui;

import com.truesim.engine.input.ActionGroup;
import com.truesim.engine.input.Axis1Action;
import com.truesim.engine.input.Axis2Action;
import com.truesim.engine.input.BinaryAction;
import com.truesim.engine.input.Binding;
import com.truesim.engine.input.Control;
import com.truesim.engine.input.Controls;
import com.truesim.engine.input.PoseAction;
import com.truesim.engine.platform.GamepadAxis;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.HatPosition;
import com.truesim.engine.platform.Key;
import com.truesim.engine.platform.MouseButton;
import java.util.Locale;

/**
 * The UI's input actions (design section 10): the {@code ui} group and its sub-groups, with default bindings for the
 * keyboard and mouse and for gamepads (positions south, east, west, north, so the layout follows the pad, UI29). Their
 * bindings are settings ({@code input.ui.navigation.accept}), rebound by players like any action's. {@link UiCommands}
 * turns this frame's edges into ui-core's {@code UiCommand}s.
 *
 * <pre>{@code
 * input.add(UiActions.UI);                          // once: the Ui plugin does it
 * input.enable(UiActions.GIZMO, false);             // a game without gizmos switches them off in one call
 * }</pre>
 *
 * <p>Touch and pens have no device kind in the action system: fingers and pens reach the UI as pointers
 * ({@link TouchPointers}, {@link PenPointer}). XR controllers' defaults come with engine-xr's device class, set as the
 * game's defaults when a headset's input is there ({@link XrPointers#defaults}): the aim poses and triggers of
 * {@link #AIM_LEFT} and the others, the left menu button held for {@link #TOGGLE_CONSOLE}, and the gizmo's grips,
 * grip poses, thumbstick snap and face-button switches ({@link #GRAB_LEFT} and the others). The mouse's position is
 * read by {@link MousePointer} directly (UI22), so {@link #POSITION} is unbound by default; a gamepad's virtual cursor
 * ({@link VirtualCursor}, off unless its setting is on) reads {@link #CURSOR} and {@link #CURSOR_CLICK}. Text
 * shortcuts use Cmd on macOS, word moves Option there; Ctrl elsewhere.
 */
public final class UiActions {

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    /** The shortcut modifier: Cmd on macOS, Ctrl elsewhere. */
    static final Control SHORTCUT = MAC ? Controls.META : Controls.CONTROL;
    /** The word-move modifier: Option on macOS, Ctrl elsewhere. */
    static final Control WORD = MAC ? Controls.ALT : Controls.CONTROL;

    /** Everything the UI reads. */
    public static final ActionGroup UI = ActionGroup.root("ui");
    /** Focus moving and choosing. */
    public static final ActionGroup NAVIGATION = UI.child("navigation");
    /** Pointers driven by actions: XR rays, a gamepad's virtual cursor; the mouse is read directly. */
    public static final ActionGroup POINTER = UI.child("pointer");
    /** Scrolling. */
    public static final ActionGroup SCROLL = UI.child("scroll");
    /** Text editing: on only while a text field takes text ({@link UiCommands#startText}). */
    public static final ActionGroup TEXT = UI.child("text");
    /** Tab bars. */
    public static final ActionGroup TABS = UI.child("tabs");
    /** Charts. */
    public static final ActionGroup CHARTS = UI.child("charts");
    /** 3D gizmos. */
    public static final ActionGroup GIZMO = UI.child("gizmo");
    /** Dock spaces. */
    public static final ActionGroup DOCKING = UI.child("docking");
    /** The developer console. */
    public static final ActionGroup CONSOLE = UI.child("console");

    // ---------------------------------------------------------------- ui.navigation

    public static final BinaryAction UP = NAVIGATION.binary("up", Binding.key(Key.UP),
        Binding.pad(GamepadButton.DPAD_UP), Binding.of(Controls.stick(false, HatPosition.UP)));
    public static final BinaryAction DOWN = NAVIGATION.binary("down", Binding.key(Key.DOWN),
        Binding.pad(GamepadButton.DPAD_DOWN), Binding.of(Controls.stick(false, HatPosition.DOWN)));
    public static final BinaryAction LEFT = NAVIGATION.binary("left", Binding.key(Key.LEFT),
        Binding.pad(GamepadButton.DPAD_LEFT), Binding.of(Controls.stick(false, HatPosition.LEFT)));
    public static final BinaryAction RIGHT = NAVIGATION.binary("right", Binding.key(Key.RIGHT),
        Binding.pad(GamepadButton.DPAD_RIGHT), Binding.of(Controls.stick(false, HatPosition.RIGHT)));
    public static final BinaryAction NEXT = NAVIGATION.binary("next", Binding.key(Key.TAB));
    public static final BinaryAction PREVIOUS = NAVIGATION.binary("previous",
        Binding.chord(Controls.SHIFT, Controls.key(Key.TAB)));
    public static final BinaryAction PAGE_UP = NAVIGATION.binary("pageUp", Binding.key(Key.PAGE_UP),
        Binding.pad(GamepadAxis.LEFT_TRIGGER));
    public static final BinaryAction PAGE_DOWN = NAVIGATION.binary("pageDown", Binding.key(Key.PAGE_DOWN),
        Binding.pad(GamepadAxis.RIGHT_TRIGGER));
    public static final BinaryAction HOME = NAVIGATION.binary("home", Binding.key(Key.HOME));
    public static final BinaryAction END = NAVIGATION.binary("end", Binding.key(Key.END));
    /** Accept: Enter, Space; the gamepad's confirm button, south unless {@link UiCommands#CONFIRM} says east. */
    public static final BinaryAction ACCEPT = NAVIGATION.binary("accept", Binding.key(Key.ENTER),
        Binding.key(Key.SPACE), Binding.pad(GamepadButton.SOUTH));
    /** Cancel: Escape; the gamepad's back button, east unless {@link UiCommands#CONFIRM} says otherwise. */
    public static final BinaryAction CANCEL = NAVIGATION.binary("cancel", Binding.key(Key.ESCAPE),
        Binding.pad(GamepadButton.EAST));
    public static final BinaryAction CONTEXT_MENU = NAVIGATION.binary("contextMenu", Binding.key(Key.APPLICATION),
        Binding.chord(Controls.SHIFT, Controls.key(Key.F10)), Binding.pad(GamepadButton.WEST));
    public static final BinaryAction MENU = NAVIGATION.binary("menu", Binding.pad(GamepadButton.START));

    // ---------------------------------------------------------------- ui.pointer

    /** A virtual cursor's position; unbound by default (the mouse is read directly). */
    public static final Axis2Action POSITION = POINTER.axis2("position");
    public static final BinaryAction PRIMARY = POINTER.binary("primary", Binding.mouse(MouseButton.LEFT));
    public static final BinaryAction SECONDARY = POINTER.binary("secondary", Binding.mouse(MouseButton.RIGHT));
    public static final BinaryAction MIDDLE = POINTER.binary("middle", Binding.mouse(MouseButton.MIDDLE));
    public static final BinaryAction DRAG_COPY = POINTER.binary("dragCopy", Binding.of(Controls.CONTROL));
    public static final BinaryAction DRAG_LINK = POINTER.binary("dragLink", Binding.of(Controls.ALT));
    /** A gamepad's virtual cursor moving (by its speed a second at full tilt): the right stick. */
    public static final Axis2Action CURSOR = POINTER.axis2("cursor", Binding.stick(true));
    /** A gamepad's virtual cursor clicking (its primary button): south. */
    public static final BinaryAction CURSOR_CLICK = POINTER.binary("cursorClick", Binding.pad(GamepadButton.SOUTH));
    /** The left hand's XR pointing ray; bound to the controller's aim pose when a headset is there. */
    public static final PoseAction AIM_LEFT = POINTER.pose("aimLeft");
    /** The right hand's XR pointing ray. */
    public static final PoseAction AIM_RIGHT = POINTER.pose("aimRight");
    /** The left XR ray's primary button: the trigger. */
    public static final BinaryAction SELECT_LEFT = POINTER.binary("selectLeft");
    /** The right XR ray's primary button. */
    public static final BinaryAction SELECT_RIGHT = POINTER.binary("selectRight");

    // ---------------------------------------------------------------- ui.scroll

    public static final Axis2Action SCROLL_BY = SCROLL.axis2("scroll",
        Binding.axes2d(Controls.WHEEL_X, Controls.WHEEL_Y), Binding.stick(true));

    // ---------------------------------------------------------------- ui.text

    public static final BinaryAction SELECT_ALL = TEXT.binary("selectAll", Binding.chord(SHORTCUT, Controls.key(Key.A)));
    public static final BinaryAction COPY = TEXT.binary("copy", Binding.chord(SHORTCUT, Controls.key(Key.C)));
    public static final BinaryAction CUT = TEXT.binary("cut", Binding.chord(SHORTCUT, Controls.key(Key.X)));
    public static final BinaryAction PASTE = TEXT.binary("paste", Binding.chord(SHORTCUT, Controls.key(Key.V)));
    public static final BinaryAction UNDO = TEXT.binary("undo", Binding.chord(SHORTCUT, Controls.key(Key.Z)));
    public static final BinaryAction REDO = TEXT.binary("redo", Binding.chord(SHORTCUT, Controls.key(Key.Y)),
        Binding.chord(SHORTCUT, Controls.key(Key.Z)).with(Controls.SHIFT));
    public static final BinaryAction DELETE_BACK = TEXT.binary("deleteBack", Binding.key(Key.BACKSPACE));
    public static final BinaryAction DELETE_FORWARD = TEXT.binary("deleteForward", Binding.key(Key.DELETE));
    public static final BinaryAction DELETE_WORD_BACK = TEXT.binary("deleteWordBack",
        Binding.chord(WORD, Controls.key(Key.BACKSPACE)));
    public static final BinaryAction DELETE_WORD_FORWARD = TEXT.binary("deleteWordForward",
        Binding.chord(WORD, Controls.key(Key.DELETE)));
    public static final BinaryAction WORD_LEFT = TEXT.binary("wordLeft", Binding.chord(WORD, Controls.key(Key.LEFT)));
    public static final BinaryAction WORD_RIGHT = TEXT.binary("wordRight",
        Binding.chord(WORD, Controls.key(Key.RIGHT)));
    public static final BinaryAction LINE_START = TEXT.binary("lineStart", Binding.key(Key.HOME));
    public static final BinaryAction LINE_END = TEXT.binary("lineEnd", Binding.key(Key.END));
    public static final BinaryAction DOC_START = TEXT.binary("docStart", Binding.chord(SHORTCUT, Controls.key(Key.HOME)));
    public static final BinaryAction DOC_END = TEXT.binary("docEnd", Binding.chord(SHORTCUT, Controls.key(Key.END)));
    /** Held with the moves, the selection extends ({@code UiInput.EXTEND_SELECTION}). */
    public static final BinaryAction EXTEND_SELECTION = TEXT.binary("extendSelection", Binding.of(Controls.SHIFT));
    public static final BinaryAction SUBMIT = TEXT.binary("submit", Binding.key(Key.ENTER),
        Binding.key(Key.NUMPAD_ENTER), Binding.pad(GamepadButton.SOUTH));
    public static final BinaryAction NEWLINE = TEXT.binary("newline",
        Binding.chord(Controls.SHIFT, Controls.key(Key.ENTER)));

    // ---------------------------------------------------------------- ui.tabs, ui.charts, ui.gizmo, ui.docking, ui.console

    public static final BinaryAction NEXT_TAB = TABS.binary("nextTab",
        Binding.chord(Controls.CONTROL, Controls.key(Key.TAB)),
        Binding.chord(Controls.CONTROL, Controls.key(Key.PAGE_DOWN)), Binding.pad(GamepadButton.RIGHT_SHOULDER));
    public static final BinaryAction PREVIOUS_TAB = TABS.binary("previousTab",
        Binding.chord(Controls.CONTROL, Controls.key(Key.TAB)).with(Controls.SHIFT),
        Binding.chord(Controls.CONTROL, Controls.key(Key.PAGE_UP)), Binding.pad(GamepadButton.LEFT_SHOULDER));
    public static final BinaryAction CLOSE_TAB = TABS.binary("closeTab",
        Binding.chord(SHORTCUT, Controls.key(Key.W)), Binding.chord(Controls.CONTROL, Controls.key(Key.F4)));

    public static final Axis1Action ZOOM = CHARTS.axis1("zoom", Binding.of(Controls.WHEEL_Y),
        Binding.pad(GamepadAxis.RIGHT_Y).filter(com.truesim.engine.input.Filters.AXES, -1, 1, 1));
    public static final BinaryAction PAN = CHARTS.binary("pan", Binding.mouse(MouseButton.MIDDLE),
        Binding.mouse(MouseButton.LEFT));
    public static final BinaryAction BOX_SELECT = CHARTS.binary("boxSelect", Binding.of(Controls.SHIFT));
    public static final BinaryAction BOX_ZOOM = CHARTS.binary("boxZoom", Binding.mouse(MouseButton.RIGHT));
    public static final BinaryAction RESET_VIEW = CHARTS.binary("resetView", Binding.key(Key.HOME),
        Binding.pad(GamepadButton.NORTH));

    // The gizmo's mode, space and pivot switches: keys (not while typing), the d-pad while a gizmo is focused
    // (UiCommands.gizmo; otherwise the d-pad navigates), XR buttons (XrPointers.defaults).

    /** Translate: W; the d-pad's up while a gizmo is focused. */
    public static final BinaryAction TRANSLATE = GIZMO.binary("translate", Binding.key(Key.W),
        Binding.pad(GamepadButton.DPAD_UP));
    /** Rotate: E; the d-pad's right while a gizmo is focused. */
    public static final BinaryAction ROTATE = GIZMO.binary("rotate", Binding.key(Key.E),
        Binding.pad(GamepadButton.DPAD_RIGHT));
    /** Scale: R; the d-pad's down while a gizmo is focused. */
    public static final BinaryAction SCALE = GIZMO.binary("scale", Binding.key(Key.R),
        Binding.pad(GamepadButton.DPAD_DOWN));
    /** Translate, rotate and scale at once: T; the d-pad's left while a gizmo is focused. */
    public static final BinaryAction UNIVERSAL = GIZMO.binary("universal", Binding.key(Key.T),
        Binding.pad(GamepadButton.DPAD_LEFT));
    /** The space cycles (local, world, parent, view): X; with a headset, the left controller's X too. */
    public static final BinaryAction TOGGLE_SPACE = GIZMO.binary("toggleSpace", Binding.key(Key.X));
    /** Snaps while held: Ctrl, the left trigger; with a headset, either thumbstick pressed. */
    public static final BinaryAction SNAP = GIZMO.binary("snap", Binding.of(Controls.CONTROL),
        Binding.pad(GamepadAxis.LEFT_TRIGGER));
    public static final BinaryAction CANCEL_DRAG = GIZMO.binary("cancelDrag", Binding.key(Key.ESCAPE),
        Binding.mouse(MouseButton.RIGHT), Binding.pad(GamepadButton.EAST));
    /** The bounds mode (the selection's box, its faces and corners scaling it): B, for bounds (free in every group). */
    public static final BinaryAction BOUNDS = GIZMO.binary("bounds", Binding.key(Key.B));
    /**
     * The next mode (translate, rotate, scale, bounds, universal): with a headset, the right controller's A; no key
     * (W, E, R, B, T choose directly).
     */
    public static final BinaryAction NEXT_MODE = GIZMO.binary("nextMode");
    /** The space cycles backwards: Shift+X. */
    public static final BinaryAction PREVIOUS_SPACE = GIZMO.binary("previousSpace",
        Binding.chord(Controls.SHIFT, Controls.key(Key.X)));
    /**
     * The pivot cycles (median, individual origins, active, cursor, bounds centre): period, the key of Blender's pivot
     * menu; with a headset, the left controller's Y.
     */
    public static final BinaryAction NEXT_PIVOT = GIZMO.binary("nextPivot", Binding.key(Key.PERIOD));
    /** The pivot cycles backwards: Shift+period. */
    public static final BinaryAction PREVIOUS_PIVOT = GIZMO.binary("previousPivot",
        Binding.chord(Controls.SHIFT, Controls.key(Key.PERIOD)));
    /**
     * The left hand grabs a gizmo's selection directly while held ({@link XrGizmoGrab}): the left grip with a
     * headset. Both hands grabbing scale and turn it (two-handed).
     */
    public static final BinaryAction GRAB_LEFT = GIZMO.binary("grabLeft");
    /** The right hand grabs while held: the right grip with a headset. */
    public static final BinaryAction GRAB_RIGHT = GIZMO.binary("grabRight");
    /** Where the left hand grabs from: the left controller's grip pose with a headset. */
    public static final PoseAction HAND_LEFT = GIZMO.pose("handLeft");
    /** Where the right hand grabs from: the right controller's grip pose. */
    public static final PoseAction HAND_RIGHT = GIZMO.pose("handRight");

    public static final BinaryAction DOCK_MODIFIER = DOCKING.binary("dockModifier", Binding.of(Controls.SHIFT));
    public static final BinaryAction NEXT_WINDOW = DOCKING.binary("nextWindow",
        Binding.chord(Controls.CONTROL, Controls.key(Key.F6)));
    /** Moves the focused (or active) window by keys or the d-pad: Alt+F7, as on Windows and KDE. */
    public static final BinaryAction WINDOW_MOVE = DOCKING.binary("windowMove",
        Binding.chord(Controls.ALT, Controls.key(Key.F7)));
    /** Resizes the focused (or active) window by keys or the d-pad: Alt+F8. */
    public static final BinaryAction WINDOW_RESIZE = DOCKING.binary("windowResize",
        Binding.chord(Controls.ALT, Controls.key(Key.F8)));

    /**
     * Opens and closes the console: the grave key (also while a text field types: it types no grave), both sticks
     * clicked together; with a headset, the left menu button held ({@link XrPointers#defaults}).
     */
    public static final BinaryAction TOGGLE_CONSOLE = CONSOLE.binary("toggle", Binding.key(Key.BACKQUOTE),
        Binding.chord(Controls.pad(GamepadButton.LEFT_STICK), Controls.pad(GamepadButton.RIGHT_STICK)));
    /** Completes the console's command line (only while it has the text: {@link UiCommands#console}). */
    public static final BinaryAction COMPLETE = CONSOLE.binary("complete", Binding.key(Key.TAB));
    public static final BinaryAction HISTORY_UP = CONSOLE.binary("historyUp", Binding.key(Key.UP));
    public static final BinaryAction HISTORY_DOWN = CONSOLE.binary("historyDown", Binding.key(Key.DOWN));

    private UiActions() {
    }

    /** Whether this is macOS, whose shortcuts use Cmd. */
    static boolean mac() {
        return MAC;
    }
}
