package com.truesim.engine.demo;

import com.truesim.engine.input.ActionGroup;
import com.truesim.engine.input.Axis1Action;
import com.truesim.engine.input.Axis2Action;
import com.truesim.engine.input.BinaryAction;
import com.truesim.engine.input.Binding;
import com.truesim.engine.input.Controls;
import com.truesim.engine.input.Filters;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.Key;
import com.truesim.engine.platform.MouseButton;
import com.truesim.engine.xr.Hand;
import com.truesim.engine.xr.XrAxis;
import com.truesim.engine.xr.XrButton;
import com.truesim.engine.xr.XrControls;

/**
 * The demo's actions: the free camera and the settings it switches, bound to keys, the mouse and a gamepad, and the
 * headset's walking. Rebinding them is a matter of the settings file ({@code input.demo.camera.move=...}).
 */
final class DemoActions {

    static final ActionGroup DEMO = ActionGroup.root("demo");

    // The free camera.
    static final ActionGroup CAMERA = DEMO.child("camera");
    /** Walking: W A S D, the left stick. */
    static final Axis2Action MOVE = CAMERA.axis2("move", Binding.keys(Key.A, Key.D, Key.S, Key.W), Binding.stick(false));
    /** Rising and sinking: E and Q, the shoulders. */
    static final Axis1Action RISE = CAMERA.axis1("rise", Binding.axis(Controls.key(Key.Q), Controls.key(Key.E)),
        Binding.axis(Controls.pad(GamepadButton.LEFT_SHOULDER), Controls.pad(GamepadButton.RIGHT_SHOULDER)));
    /** Faster: either shift, the left stick pressed. */
    static final BinaryAction FAST = CAMERA.binary("fast", Binding.of(Controls.SHIFT),
        Binding.pad(GamepadButton.LEFT_STICK));
    /** Holding to look around: the right mouse button (it captures the mouse while held). */
    static final BinaryAction LOOKING = CAMERA.binary("looking", Binding.mouse(MouseButton.RIGHT));
    /** Looking, radians this frame: the mouse moved while the right button is held. */
    static final Axis2Action LOOK = CAMERA.axis2("look", Binding.axes2d(Controls.MOUSE_X, Controls.MOUSE_Y)
        .with(Controls.mouse(MouseButton.RIGHT)).filter(Filters.SCALE, 0.003f));
    /** Turning, radians a second: the right stick, up positive. */
    static final Axis2Action TURN = CAMERA.axis2("turn", Binding.stick(true).filter(Filters.CURVE, 2)
        .filter(Filters.SCALE, 2.5f));

    // What the demo switches.
    static final ActionGroup TOGGLES = DEMO.child("toggles");
    static final BinaryAction FORWARD = TOGGLES.binary("forwardShading", Binding.key(Key.DIGIT_1));
    static final BinaryAction VISIBILITY = TOGGLES.binary("visibilityBuffer", Binding.key(Key.DIGIT_2));
    static final BinaryAction HDR = TOGGLES.binary("hdr", Binding.key(Key.H), Binding.pad(GamepadButton.NORTH));
    static final BinaryAction CURVE = TOGGLES.binary("toneCurve", Binding.key(Key.T), Binding.pad(GamepadButton.WEST));
    static final BinaryAction DARKER = TOGGLES.binary("darker", Binding.key(Key.LEFT_BRACKET),
        Binding.pad(GamepadButton.DPAD_LEFT));
    static final BinaryAction BRIGHTER = TOGGLES.binary("brighter", Binding.key(Key.RIGHT_BRACKET),
        Binding.pad(GamepadButton.DPAD_RIGHT));
    static final BinaryAction PRESENT_MODE = TOGGLES.binary("presentMode", Binding.key(Key.V));
    static final BinaryAction WINDOW_MODE = TOGGLES.binary("windowMode", Binding.key(Key.F), Binding.key(Key.F11));
    static final BinaryAction DYNAMIC = TOGGLES.binary("dynamicResolution", Binding.key(Key.R));
    static final BinaryAction SMALLER = TOGGLES.binary("renderScaleDown", Binding.key(Key.MINUS),
        Binding.pad(GamepadButton.DPAD_DOWN));
    static final BinaryAction LARGER = TOGGLES.binary("renderScaleUp", Binding.key(Key.EQUALS),
        Binding.pad(GamepadButton.DPAD_UP));
    static final BinaryAction GPU_TIMED = TOGGLES.binary("gpuTimed", Binding.key(Key.G));
    static final BinaryAction TARGET_DOWN = TOGGLES.binary("gpuTargetDown", Binding.key(Key.COMMA));
    static final BinaryAction TARGET_UP = TOGGLES.binary("gpuTargetUp", Binding.key(Key.PERIOD));
    static final BinaryAction OCCLUSION = TOGGLES.binary("occlusion", Binding.key(Key.O));
    static final BinaryAction MAP = TOGGLES.binary("map", Binding.key(Key.N), Binding.pad(GamepadButton.BACK));
    static final BinaryAction SPECTATE = TOGGLES.binary("spectate", Binding.key(Key.P));
    static final BinaryAction SAVE = TOGGLES.binary("save", Binding.key(Key.F5),
        Binding.chord(Controls.CONTROL, Controls.key(Key.S)));
    static final BinaryAction QUIT = TOGGLES.binary("quit", Binding.key(Key.ESCAPE),
        Binding.chord(Controls.pad(GamepadButton.BACK), Controls.pad(GamepadButton.START)));

    // The headset.
    static final ActionGroup VR = DEMO.child("vr");
    /** Walking where the head looks: the left thumbstick. */
    static final Axis2Action WALK = VR.axis2("walk", Binding.axes2d(XrControls.axis(Hand.LEFT, XrAxis.THUMBSTICK_X),
        XrControls.axis(Hand.LEFT, XrAxis.THUMBSTICK_Y)).filter(Filters.RADIAL, 0.1f, 1));
    static final BinaryAction LEFT_TRIGGER = VR.binary("leftTrigger",
        Binding.of(XrControls.button(Hand.LEFT, XrButton.TRIGGER)));
    static final BinaryAction RIGHT_TRIGGER = VR.binary("rightTrigger",
        Binding.of(XrControls.button(Hand.RIGHT, XrButton.TRIGGER)));

    private DemoActions() {
    }
}
