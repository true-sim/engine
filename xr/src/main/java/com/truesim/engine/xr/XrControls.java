package com.truesim.engine.xr;

import com.truesim.engine.input.Control;
import com.truesim.engine.input.ControlKind;
import com.truesim.engine.input.DeviceClass;

/**
 * A headset's input as a kind of device for the engine's input (application-api.md D64): the OpenXR actions, attached
 * once per session, are the device layer; engine actions and bindings run on top, so XR bindings are rebound, saved
 * and filtered like any other. Bindings name its controls {@code xr:<HAND>_<BUTTON>} ({@code xr:RIGHT_TRIGGER}),
 * {@code xr:<HAND>_<AXIS>_AXIS}, {@code xr:<HAND>_<TOUCH>_TOUCH}, {@code xr:<HAND>_<POSE>_POSE}, {@code xr:HEAD_POSE}
 * and {@code xr:GAZE_POSE}.
 *
 * <pre>{@code
 * public static final BinaryAction GRAB = GAME.binary("grab", Binding.of(XrControls.button(Hand.RIGHT, XrButton.SQUEEZE)));
 * public static final PoseAction AIM = GAME.pose("aim", Binding.of(XrControls.pose(Hand.RIGHT, XrPose.AIM)));
 * }</pre>
 */
public final class XrControls {

    static final Hand[] HANDS = Hand.values();
    static final XrButton[] BUTTONS = XrButton.values();
    static final XrTouch[] TOUCHES = XrTouch.values();
    static final XrAxis[] AXES = XrAxis.values();
    static final XrPose[] POSES = XrPose.values();
    static final int PER_HAND = BUTTONS.length + TOUCHES.length + AXES.length + POSES.length;
    static final int HEAD = 2 * PER_HAND;
    static final int GAZE = HEAD + 1;

    /** The headset as a kind of device: both hands' controls, the head and the gaze. */
    public static final DeviceClass XR;

    static {
        DeviceClass.Builder xr = DeviceClass.builder("xr", true);
        for (Hand hand : HANDS) {
            for (XrButton button : BUTTONS) {
                xr.add(hand + "_" + button, ControlKind.BUTTON);
            }
            for (XrTouch touch : TOUCHES) {
                xr.add(hand + "_" + touch + "_TOUCH", ControlKind.BUTTON);
            }
            for (XrAxis axis : AXES) {
                xr.add(hand + "_" + axis + "_AXIS", axis.name().endsWith("_X") || axis.name().endsWith("_Y")
                    ? ControlKind.AXIS : ControlKind.HALF_AXIS);
            }
            for (XrPose pose : POSES) {
                xr.add(hand + "_" + pose + "_POSE", ControlKind.POSE);
            }
        }
        XR = xr.add("HEAD_POSE", ControlKind.POSE).add("GAZE_POSE", ControlKind.POSE).build();
    }

    private XrControls() {
    }

    /** {@code button} of {@code hand}. */
    public static Control button(Hand hand, XrButton button) {
        return XR.control(hand.ordinal() * PER_HAND + button.ordinal());
    }

    /** A finger on {@code touch} of {@code hand}'s controller. */
    public static Control touch(Hand hand, XrTouch touch) {
        return XR.control(hand.ordinal() * PER_HAND + BUTTONS.length + touch.ordinal());
    }

    /** {@code axis} of {@code hand}. */
    public static Control axis(Hand hand, XrAxis axis) {
        return XR.control(hand.ordinal() * PER_HAND + BUTTONS.length + TOUCHES.length + axis.ordinal());
    }

    /** {@code hand}'s {@code pose}. */
    public static Control pose(Hand hand, XrPose pose) {
        return XR.control(hand.ordinal() * PER_HAND + BUTTONS.length + TOUCHES.length + AXES.length + pose.ordinal());
    }

    /** The head's pose. */
    public static Control head() {
        return XR.control(HEAD);
    }

    /** The gaze, where the runtime has eye tracking. */
    public static Control gaze() {
        return XR.control(GAZE);
    }
}
