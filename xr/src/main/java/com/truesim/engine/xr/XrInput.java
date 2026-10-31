package com.truesim.engine.xr;

import static org.lwjgl.openxr.EXTHandTracking.XR_HAND_JOINT_COUNT_EXT;
import static org.lwjgl.openxr.EXTHandTracking.XR_HAND_JOINT_SET_DEFAULT_EXT;
import static org.lwjgl.openxr.EXTHandTracking.XR_HAND_LEFT_EXT;
import static org.lwjgl.openxr.EXTHandTracking.XR_HAND_RIGHT_EXT;
import static org.lwjgl.openxr.EXTHandTracking.xrCreateHandTrackerEXT;
import static org.lwjgl.openxr.EXTHandTracking.xrDestroyHandTrackerEXT;
import static org.lwjgl.openxr.EXTHandTracking.xrLocateHandJointsEXT;
import static org.lwjgl.openxr.XR10.XR_ACTION_TYPE_BOOLEAN_INPUT;
import static org.lwjgl.openxr.XR10.XR_ACTION_TYPE_FLOAT_INPUT;
import static org.lwjgl.openxr.XR10.XR_ACTION_TYPE_POSE_INPUT;
import static org.lwjgl.openxr.XR10.XR_ACTION_TYPE_VECTOR2F_INPUT;
import static org.lwjgl.openxr.XR10.XR_ACTION_TYPE_VIBRATION_OUTPUT;
import static org.lwjgl.openxr.XR10.XR_NULL_PATH;
import static org.lwjgl.openxr.XR10.XR_REFERENCE_SPACE_TYPE_VIEW;
import static org.lwjgl.openxr.XR10.XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
import static org.lwjgl.openxr.XR10.XR_SPACE_LOCATION_POSITION_VALID_BIT;
import static org.lwjgl.openxr.XR10.xrApplyHapticFeedback;
import static org.lwjgl.openxr.XR10.xrAttachSessionActionSets;
import static org.lwjgl.openxr.XR10.xrCreateAction;
import static org.lwjgl.openxr.XR10.xrCreateActionSet;
import static org.lwjgl.openxr.XR10.xrCreateActionSpace;
import static org.lwjgl.openxr.XR10.xrCreateReferenceSpace;
import static org.lwjgl.openxr.XR10.xrDestroyActionSet;
import static org.lwjgl.openxr.XR10.xrDestroySpace;
import static org.lwjgl.openxr.XR10.xrGetActionStateBoolean;
import static org.lwjgl.openxr.XR10.xrGetActionStateFloat;
import static org.lwjgl.openxr.XR10.xrGetActionStateVector2f;
import static org.lwjgl.openxr.XR10.xrGetCurrentInteractionProfile;
import static org.lwjgl.openxr.XR10.xrLocateSpace;
import static org.lwjgl.openxr.XR10.xrPathToString;
import static org.lwjgl.openxr.XR10.xrStopHapticFeedback;
import static org.lwjgl.openxr.XR10.xrStringToPath;
import static org.lwjgl.openxr.XR10.xrSuggestInteractionProfileBindings;
import static org.lwjgl.openxr.XR10.xrSyncActions;
import static org.lwjgl.system.MemoryStack.stackPush;

import com.truesim.annotations.AllowAllocation;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.openxr.XrAction;
import org.lwjgl.openxr.XrActionCreateInfo;
import org.lwjgl.openxr.XrActionSet;
import org.lwjgl.openxr.XrActionSetCreateInfo;
import org.lwjgl.openxr.XrActionSpaceCreateInfo;
import org.lwjgl.openxr.XrActionStateBoolean;
import org.lwjgl.openxr.XrActionStateFloat;
import org.lwjgl.openxr.XrActionStateGetInfo;
import org.lwjgl.openxr.XrActionStateVector2f;
import org.lwjgl.openxr.XrActionSuggestedBinding;
import org.lwjgl.openxr.XrActionsSyncInfo;
import org.lwjgl.openxr.XrActiveActionSet;
import org.lwjgl.openxr.XrHandJointLocationEXT;
import org.lwjgl.openxr.XrHandJointLocationsEXT;
import org.lwjgl.openxr.XrHandJointsLocateInfoEXT;
import org.lwjgl.openxr.XrHandTrackerCreateInfoEXT;
import org.lwjgl.openxr.XrHandTrackerEXT;
import org.lwjgl.openxr.XrHapticActionInfo;
import org.lwjgl.openxr.XrHapticBaseHeader;
import org.lwjgl.openxr.XrHapticVibration;
import org.lwjgl.openxr.XrInstance;
import org.lwjgl.openxr.XrInteractionProfileState;
import org.lwjgl.openxr.XrInteractionProfileSuggestedBinding;
import org.lwjgl.openxr.XrPosef;
import org.lwjgl.openxr.XrReferenceSpaceCreateInfo;
import org.lwjgl.openxr.XrSession;
import org.lwjgl.openxr.XrSessionActionSetsAttachInfo;
import org.lwjgl.openxr.XrSpace;
import org.lwjgl.openxr.XrSpaceLocation;
import org.lwjgl.openxr.XrActionSuggestedBinding.Buffer;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A headset's input, as devices like the platform's: per hand, its controller's buttons, touches and axes (or a tracked
 * hand's gestures: pinch, aim activate, grasp), its poses (grip, aim, pinch, poke), and vibration; the head's pose; the
 * eyes' gaze and the hands' joints where the runtime offers them. Read the state any time (as of the headset's last
 * frame), poll for what changed ({@link #poll}). Whatever the controller (Meta Touch, Valve Index, HTC Vive, Windows
 * Mixed Reality, a simple controller, tracked hands), the same names: the runtime maps them.
 *
 * <p>Positions are world positions: the tracking space's origin ({@link Headset#origin}) plus where the runtime says.
 * Use it on the thread rendering the headset. Reading allocates nothing.
 */
public final class XrInput implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(XrInput.class);
    private static final Hand[] HANDS = Hand.values();
    private static final XrButton[] BUTTONS = XrButton.values();
    private static final XrTouch[] TOUCHES = XrTouch.values();
    private static final XrAxis[] AXES = XrAxis.values();
    private static final XrPose[] POSES = XrPose.values();
    /** Floats per pose: position xyz, orientation xyzw. */
    private static final int POSE = 7;
    /** Floats per joint: position xyz, orientation xyzw, radius. */
    private static final int JOINT = 8;

    private final Headset headset;
    private final XrInstance instance;
    private final XrSession session;
    private final XrSpace base;
    private final long[] handPaths = new long[2];
    private final XrActionSet actions;
    private final XrAction[] buttonActions = new XrAction[BUTTONS.length];
    private final XrAction[] touchActions = new XrAction[TOUCHES.length];
    private final XrAction[] floatActions = new XrAction[2]; // trigger, squeeze
    private final XrAction pinchValue;
    private final XrAction aimActivateValue;
    private final XrAction graspValue;
    private final XrAction thumbstick;
    private final XrAction trackpad;
    private final XrAction haptic;
    private final XrAction gaze; // or null
    private final XrSpace[][] poseSpaces = new XrSpace[2][POSES.length];
    private final XrSpace gazeSpace; // or null
    private final XrSpace head;
    private final XrHandTrackerEXT[] trackers; // or null
    // State as of the last frame, and as of the last poll.
    private final boolean[][] pressed = new boolean[2][BUTTONS.length];
    private final boolean[][] polledPressed = new boolean[2][BUTTONS.length];
    private final boolean[][] touched = new boolean[2][TOUCHES.length];
    private final boolean[][] polledTouched = new boolean[2][TOUCHES.length];
    private final float[][] axes = new float[2][AXES.length];
    private final float[][][] poses = new float[2][POSES.length][POSE];
    private final boolean[][] posesValid = new boolean[2][POSES.length];
    private final float[] headPose = new float[POSE];
    private boolean headValid;
    private final float[] gazePose = new float[POSE];
    private boolean gazeValid;
    private final float[][] joints = new float[2][XR_HAND_JOINT_COUNT_EXT * JOINT];
    private final boolean[] jointsValid = new boolean[2];
    private final String[] profiles = {"", ""};
    private final String[] polledProfiles = {"", ""};

    @AllowAllocation("starting, once")
    XrInput(Headset headset, XrInstance instance, XrSession session, XrSpace base, boolean handInteraction,
        boolean eyeGaze, boolean handTracking) {
        this.headset = headset;
        this.instance = instance;
        this.session = session;
        this.base = base;
        try (MemoryStack stack = stackPush()) {
            handPaths[0] = path(stack, "/user/hand/left");
            handPaths[1] = path(stack, "/user/hand/right");
            PointerBuffer handle = stack.mallocPointer(1);
            check(xrCreateActionSet(instance, XrActionSetCreateInfo.calloc(stack).type$Default()
                .actionSetName(stack.UTF8("truesim")).localizedActionSetName(stack.UTF8("true-sim")).priority(0),
                handle), "make the input's action set");
            actions = new XrActionSet(handle.get(0), instance);
            for (XrButton button : BUTTONS) {
                buttonActions[button.ordinal()] = action(stack, "button_" + button, XR_ACTION_TYPE_BOOLEAN_INPUT, true);
            }
            for (XrTouch touch : TOUCHES) {
                touchActions[touch.ordinal()] = action(stack, "touch_" + touch, XR_ACTION_TYPE_BOOLEAN_INPUT, true);
            }
            floatActions[0] = action(stack, "trigger_value", XR_ACTION_TYPE_FLOAT_INPUT, true);
            floatActions[1] = action(stack, "squeeze_value", XR_ACTION_TYPE_FLOAT_INPUT, true);
            pinchValue = action(stack, "pinch_value", XR_ACTION_TYPE_FLOAT_INPUT, true);
            aimActivateValue = action(stack, "aim_activate_value", XR_ACTION_TYPE_FLOAT_INPUT, true);
            graspValue = action(stack, "grasp_value", XR_ACTION_TYPE_FLOAT_INPUT, true);
            thumbstick = action(stack, "thumbstick", XR_ACTION_TYPE_VECTOR2F_INPUT, true);
            trackpad = action(stack, "trackpad", XR_ACTION_TYPE_VECTOR2F_INPUT, true);
            haptic = action(stack, "haptic", XR_ACTION_TYPE_VIBRATION_OUTPUT, true);
            XrAction[] poseActions = new XrAction[POSES.length];
            for (XrPose pose : POSES) {
                poseActions[pose.ordinal()] = action(stack, "pose_" + pose, XR_ACTION_TYPE_POSE_INPUT, true);
            }
            gaze = eyeGaze ? action(stack, "gaze", XR_ACTION_TYPE_POSE_INPUT, false) : null;
            suggestBindings(stack, poseActions, handInteraction);
            check(xrAttachSessionActionSets(session, XrSessionActionSetsAttachInfo.calloc(stack).type$Default()
                .actionSets(stack.pointers(actions))), "attach the input");
            for (int h = 0; h < 2; h++) {
                for (XrPose pose : POSES) {
                    XrActionSpaceCreateInfo info = XrActionSpaceCreateInfo.calloc(stack).type$Default()
                        .action(poseActions[pose.ordinal()]).subactionPath(handPaths[h]);
                    info.poseInActionSpace().orientation().w(1);
                    check(xrCreateActionSpace(session, info, handle), "make a hand's space");
                    poseSpaces[h][pose.ordinal()] = new XrSpace(handle.get(0), session);
                }
            }
            if (gaze != null) {
                XrActionSpaceCreateInfo info = XrActionSpaceCreateInfo.calloc(stack).type$Default().action(gaze)
                    .subactionPath(XR_NULL_PATH);
                info.poseInActionSpace().orientation().w(1);
                check(xrCreateActionSpace(session, info, handle), "make the gaze's space");
                gazeSpace = new XrSpace(handle.get(0), session);
            } else {
                gazeSpace = null;
            }
            XrReferenceSpaceCreateInfo view = XrReferenceSpaceCreateInfo.calloc(stack).type$Default()
                .referenceSpaceType(XR_REFERENCE_SPACE_TYPE_VIEW);
            view.poseInReferenceSpace().orientation().w(1);
            check(xrCreateReferenceSpace(session, view, handle), "make the head's space");
            head = new XrSpace(handle.get(0), session);
            if (handTracking) {
                trackers = new XrHandTrackerEXT[2];
                for (int h = 0; h < 2; h++) {
                    check(xrCreateHandTrackerEXT(session, XrHandTrackerCreateInfoEXT.calloc(stack).type$Default()
                        .hand(h == 0 ? XR_HAND_LEFT_EXT : XR_HAND_RIGHT_EXT).handJointSet(XR_HAND_JOINT_SET_DEFAULT_EXT),
                        handle), "track a hand");
                    trackers[h] = new XrHandTrackerEXT(handle.get(0), session);
                }
            } else {
                trackers = null;
            }
        }
    }

    @AllowAllocation("starting, once")
    private XrAction action(MemoryStack stack, String name, int type, boolean perHand) {
        String id = name.toLowerCase(java.util.Locale.ROOT);
        XrActionCreateInfo info = XrActionCreateInfo.calloc(stack).type$Default().actionName(stack.UTF8(id))
            .localizedActionName(stack.UTF8(id.replace('_', ' '))).actionType(type);
        if (perHand) {
            info.subactionPaths(stack.longs(handPaths[0], handPaths[1]));
        }
        PointerBuffer handle = stack.mallocPointer(1);
        check(xrCreateAction(actions, info, handle), "make the input action " + id);
        return new XrAction(handle.get(0), actions);
    }

    /**
     * Suggests where each action is on every controller the engine knows, and on tracked hands: a profile the runtime
     * refuses (a path it does not have) is left out, the others stand.
     */
    @AllowAllocation("starting, once")
    private void suggestBindings(MemoryStack stack, XrAction[] poseActions, boolean handInteraction) {
        XrAction triggerClick = buttonActions[XrButton.TRIGGER.ordinal()];
        XrAction squeezeClick = buttonActions[XrButton.SQUEEZE.ordinal()];
        XrAction primary = buttonActions[XrButton.PRIMARY.ordinal()];
        XrAction secondary = buttonActions[XrButton.SECONDARY.ordinal()];
        XrAction menu = buttonActions[XrButton.MENU.ordinal()];
        XrAction stickClick = buttonActions[XrButton.THUMBSTICK.ordinal()];
        XrAction padClick = buttonActions[XrButton.TRACKPAD.ordinal()];
        XrAction triggerValue = floatActions[0];
        XrAction squeezeValue = floatActions[1];
        XrAction grip = poseActions[XrPose.GRIP.ordinal()];
        XrAction aim = poseActions[XrPose.AIM.ordinal()];
        Map<String, Map<String, XrAction>> profiles = new LinkedHashMap<>();
        Map<String, XrAction> simple = both("input/select/click", triggerClick, "input/select/click", triggerValue,
            "input/menu/click", menu, "input/grip/pose", grip, "input/aim/pose", aim, "output/haptic", haptic);
        profiles.put("/interaction_profiles/khr/simple_controller", simple);
        Map<String, XrAction> touch = both("input/trigger/value", triggerClick, "input/trigger/value", triggerValue,
            "input/trigger/touch", touchActions[XrTouch.TRIGGER.ordinal()], "input/squeeze/value", squeezeClick,
            "input/squeeze/value", squeezeValue, "input/thumbstick", thumbstick, "input/thumbstick/click", stickClick,
            "input/thumbstick/touch", touchActions[XrTouch.THUMBSTICK.ordinal()], "input/thumbrest/touch",
            touchActions[XrTouch.THUMBREST.ordinal()], "input/grip/pose", grip, "input/aim/pose", aim,
            "output/haptic", haptic);
        touch.putAll(one("/user/hand/left/", "input/x/click", primary, "input/x/touch",
            touchActions[XrTouch.PRIMARY.ordinal()], "input/y/click", secondary, "input/y/touch",
            touchActions[XrTouch.SECONDARY.ordinal()], "input/menu/click", menu));
        touch.putAll(one("/user/hand/right/", "input/a/click", primary, "input/a/touch",
            touchActions[XrTouch.PRIMARY.ordinal()], "input/b/click", secondary, "input/b/touch",
            touchActions[XrTouch.SECONDARY.ordinal()]));
        profiles.put("/interaction_profiles/oculus/touch_controller", touch);
        profiles.put("/interaction_profiles/valve/index_controller", both("input/trigger/click", triggerClick,
            "input/trigger/value", triggerValue, "input/trigger/touch", touchActions[XrTouch.TRIGGER.ordinal()],
            "input/squeeze/value", squeezeClick, "input/squeeze/value", squeezeValue, "input/thumbstick", thumbstick,
            "input/thumbstick/click", stickClick, "input/thumbstick/touch", touchActions[XrTouch.THUMBSTICK.ordinal()],
            "input/trackpad", trackpad, "input/trackpad/force", padClick, "input/trackpad/touch",
            touchActions[XrTouch.TRACKPAD.ordinal()], "input/a/click", primary, "input/a/touch",
            touchActions[XrTouch.PRIMARY.ordinal()], "input/b/click", secondary, "input/b/touch",
            touchActions[XrTouch.SECONDARY.ordinal()], "input/grip/pose", grip, "input/aim/pose", aim,
            "output/haptic", haptic));
        profiles.put("/interaction_profiles/htc/vive_controller", both("input/trigger/click", triggerClick,
            "input/trigger/value", triggerValue, "input/squeeze/click", squeezeClick, "input/squeeze/click",
            squeezeValue, "input/menu/click", menu, "input/trackpad", trackpad, "input/trackpad/click", padClick,
            "input/trackpad/touch", touchActions[XrTouch.TRACKPAD.ordinal()], "input/grip/pose", grip,
            "input/aim/pose", aim, "output/haptic", haptic));
        profiles.put("/interaction_profiles/microsoft/motion_controller", both("input/trigger/value", triggerClick,
            "input/trigger/value", triggerValue, "input/squeeze/click", squeezeClick, "input/squeeze/click",
            squeezeValue, "input/menu/click", menu, "input/thumbstick", thumbstick, "input/thumbstick/click",
            stickClick, "input/trackpad", trackpad, "input/trackpad/click", padClick, "input/trackpad/touch",
            touchActions[XrTouch.TRACKPAD.ordinal()], "input/grip/pose", grip, "input/aim/pose", aim,
            "output/haptic", haptic));
        if (handInteraction) {
            profiles.put("/interaction_profiles/ext/hand_interaction_ext", both("input/pinch_ext/value",
                buttonActions[XrButton.PINCH.ordinal()], "input/pinch_ext/value", pinchValue,
                "input/aim_activate_ext/value", buttonActions[XrButton.AIM_ACTIVATE.ordinal()],
                "input/aim_activate_ext/value", aimActivateValue, "input/grasp_ext/value", graspValue,
                "input/pinch_ext/pose", poseActions[XrPose.PINCH.ordinal()], "input/poke_ext/pose",
                poseActions[XrPose.POKE.ordinal()], "input/grip/pose", grip, "input/aim/pose", aim));
        }
        if (gaze != null) {
            Map<String, XrAction> eyes = new LinkedHashMap<>();
            eyes.put("/user/eyes_ext/input/gaze_ext/pose", gaze);
            profiles.put("/interaction_profiles/ext/eye_gaze_interaction", eyes);
        }
        for (Map.Entry<String, Map<String, XrAction>> profile : profiles.entrySet()) {
            Buffer bindings = XrActionSuggestedBinding.calloc(profile.getValue().size(), stack);
            int i = 0;
            for (Map.Entry<String, XrAction> binding : profile.getValue().entrySet()) {
                String fullPath = binding.getKey().contains("#") ? binding.getKey().substring(0,
                    binding.getKey().indexOf('#')) : binding.getKey();
                bindings.get(i++).action(binding.getValue()).binding(path(stack, fullPath));
            }
            int result = xrSuggestInteractionProfileBindings(instance, XrInteractionProfileSuggestedBinding.calloc(stack)
                .type$Default().interactionProfile(path(stack, profile.getKey())).suggestedBindings(bindings));
            if (result < 0) {
                LOG.warn("The OpenXR runtime refused the bindings of {} ({}): that controller has no input",
                    profile.getKey(), result);
            }
        }
    }

    /** Bindings of each pair (component path, action), for both hands; an action may be bound more than once. */
    @AllowAllocation("starting, once")
    private static Map<String, XrAction> both(Object... pairs) {
        Map<String, XrAction> map = one("/user/hand/left/", pairs);
        map.putAll(one("/user/hand/right/", pairs));
        return map;
    }

    /** Bindings of each pair (component path, action) under {@code hand} (a "#n" suffix keeps a repeated path apart). */
    @AllowAllocation("starting, once")
    private static Map<String, XrAction> one(String hand, Object... pairs) {
        Map<String, XrAction> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = hand + pairs[i];
            int n = 0;
            while (map.containsKey(key)) {
                key = hand + pairs[i] + "#" + ++n;
            }
            map.put(key, (XrAction) pairs[i + 1]);
        }
        return map;
    }

    @AllowAllocation("starting, once")
    private long path(MemoryStack stack, String text) {
        LongBuffer path = stack.mallocLong(1);
        check(xrStringToPath(instance, text, path), "name " + text);
        return path.get(0);
    }

    /**
     * Reads the input for the frame that shows at {@code time}: actions synchronized (with the session focused), poses
     * located in the tracking space. From the headset's frame. Allocates nothing.
     */
    void update(long time, boolean focused) {
        try (MemoryStack stack = stackPush()) {
            if (focused) {
                XrActiveActionSet.Buffer active = XrActiveActionSet.calloc(1, stack);
                active.get(0).actionSet(actions).subactionPath(XR_NULL_PATH);
                check(xrSyncActions(session, XrActionsSyncInfo.calloc(stack).type$Default().activeActionSets(active)),
                    "read the input");
                readActions(stack);
            }
            XrSpaceLocation location = XrSpaceLocation.calloc(stack).type$Default();
            for (int h = 0; h < 2; h++) {
                for (int p = 0; p < POSES.length; p++) {
                    posesValid[h][p] = locate(poseSpaces[h][p], time, location, poses[h][p]);
                }
            }
            headValid = locate(head, time, location, headPose);
            gazeValid = gazeSpace != null && locate(gazeSpace, time, location, gazePose);
            if (trackers != null) {
                locateJoints(stack, time);
            }
        }
    }

    private void readActions(MemoryStack stack) {
        XrActionStateGetInfo info = XrActionStateGetInfo.calloc(stack).type$Default();
        XrActionStateBoolean bool = XrActionStateBoolean.calloc(stack).type$Default();
        XrActionStateFloat value = XrActionStateFloat.calloc(stack).type$Default();
        XrActionStateVector2f vector = XrActionStateVector2f.calloc(stack).type$Default();
        for (int h = 0; h < 2; h++) {
            info.subactionPath(handPaths[h]);
            for (int b = 0; b < BUTTONS.length; b++) {
                check(xrGetActionStateBoolean(session, info.action(buttonActions[b]), bool), "read a button");
                pressed[h][b] = bool.isActive() && bool.currentState();
            }
            for (int t = 0; t < TOUCHES.length; t++) {
                check(xrGetActionStateBoolean(session, info.action(touchActions[t]), bool), "read a touch");
                touched[h][t] = bool.isActive() && bool.currentState();
            }
            float[] a = axes[h];
            a[XrAxis.TRIGGER.ordinal()] = floatOf(info, floatActions[0], value);
            a[XrAxis.SQUEEZE.ordinal()] = floatOf(info, floatActions[1], value);
            a[XrAxis.PINCH.ordinal()] = floatOf(info, pinchValue, value);
            a[XrAxis.AIM_ACTIVATE.ordinal()] = floatOf(info, aimActivateValue, value);
            a[XrAxis.GRASP.ordinal()] = floatOf(info, graspValue, value);
            check(xrGetActionStateVector2f(session, info.action(thumbstick), vector), "read a thumbstick");
            a[XrAxis.THUMBSTICK_X.ordinal()] = vector.isActive() ? vector.currentState().x() : 0;
            a[XrAxis.THUMBSTICK_Y.ordinal()] = vector.isActive() ? vector.currentState().y() : 0;
            check(xrGetActionStateVector2f(session, info.action(trackpad), vector), "read a trackpad");
            a[XrAxis.TRACKPAD_X.ordinal()] = vector.isActive() ? vector.currentState().x() : 0;
            a[XrAxis.TRACKPAD_Y.ordinal()] = vector.isActive() ? vector.currentState().y() : 0;
        }
    }

    private float floatOf(XrActionStateGetInfo info, XrAction action, XrActionStateFloat value) {
        check(xrGetActionStateFloat(session, info.action(action), value), "read an axis");
        return value.isActive() ? value.currentState() : 0;
    }

    /** {@code space}'s pose in the tracking space at {@code time} into {@code out}; whether tracked. */
    private boolean locate(XrSpace space, long time, XrSpaceLocation location, float[] out) {
        if (xrLocateSpace(space, base, time, location) < 0) {
            return false;
        }
        long valid = XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
        if ((location.locationFlags() & valid) != valid) {
            return false;
        }
        pose(location.pose(), out, 0);
        return true;
    }

    private static void pose(XrPosef pose, float[] out, int at) {
        out[at] = pose.position$().x();
        out[at + 1] = pose.position$().y();
        out[at + 2] = pose.position$().z();
        out[at + 3] = pose.orientation().x();
        out[at + 4] = pose.orientation().y();
        out[at + 5] = pose.orientation().z();
        out[at + 6] = pose.orientation().w();
    }

    private void locateJoints(MemoryStack stack, long time) {
        XrHandJointLocationEXT.Buffer located = XrHandJointLocationEXT.calloc(XR_HAND_JOINT_COUNT_EXT, stack);
        XrHandJointLocationsEXT locations = XrHandJointLocationsEXT.calloc(stack).type$Default()
            .jointLocations(located);
        XrHandJointsLocateInfoEXT info = XrHandJointsLocateInfoEXT.calloc(stack).type$Default().baseSpace(base)
            .time(time);
        long valid = XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
        for (int h = 0; h < 2; h++) {
            jointsValid[h] = xrLocateHandJointsEXT(trackers[h], info, locations) >= 0 && locations.isActive();
            if (jointsValid[h]) {
                for (int j = 0; j < XR_HAND_JOINT_COUNT_EXT; j++) {
                    XrHandJointLocationEXT joint = located.get(j);
                    jointsValid[h] &= (joint.locationFlags() & valid) == valid;
                    pose(joint.pose(), joints[h], j * JOINT);
                    joints[h][j * JOINT + 7] = joint.radius();
                }
            }
        }
    }

    /** What each hand holds changed: read the profiles again (from the headset's events). */
    void profilesChanged() {
        try (MemoryStack stack = stackPush()) {
            XrInteractionProfileState state = XrInteractionProfileState.calloc(stack).type$Default();
            for (int h = 0; h < 2; h++) {
                if (xrGetCurrentInteractionProfile(session, handPaths[h], state) < 0) {
                    continue;
                }
                profiles[h] = state.interactionProfile() == XR_NULL_PATH ? "" : text(stack, state.interactionProfile());
            }
        }
    }

    @AllowAllocation("a profile's name, when it changes")
    private String text(MemoryStack stack, long path) {
        IntBuffer length = stack.mallocInt(1);
        check(xrPathToString(instance, path, length, null), "read a path");
        java.nio.ByteBuffer text = stack.malloc(length.get(0));
        check(xrPathToString(instance, path, length, text), "read a path");
        return org.lwjgl.system.MemoryUtil.memUTF8(text, Math.max(0, length.get(0) - 1));
    }

    // ---------------------------------------------------------------- reading

    /** Whether {@code button} of {@code hand} is pressed, as of the headset's last frame. */
    public boolean pressed(Hand hand, XrButton button) {
        return pressed[hand.ordinal()][button.ordinal()];
    }

    /** Whether a finger rests on {@code touch} of {@code hand}'s controller. */
    public boolean touched(Hand hand, XrTouch touch) {
        return touched[hand.ordinal()][touch.ordinal()];
    }

    /** {@code axis} of {@code hand}: 0 where the controller has none. */
    public float axis(Hand hand, XrAxis axis) {
        return axes[hand.ordinal()][axis.ordinal()];
    }

    /**
     * {@code hand}'s {@code pose} into {@code position} (world xyz) and {@code orientation} (unit quaternion xyzw):
     * false, leaving them, where the hand is not tracked or the controller has no such pose. Allocates nothing.
     */
    public boolean pose(Hand hand, XrPose pose, double[] position, float[] orientation) {
        int h = hand.ordinal();
        int p = pose.ordinal();
        return posesValid[h][p] && out(poses[h][p], 0, position, orientation);
    }

    /** The head's pose (between the eyes) into {@code position} and {@code orientation}; false where not tracked. */
    public boolean head(double[] position, float[] orientation) {
        return headValid && out(headPose, 0, position, orientation);
    }

    /** Where the eyes look, as a pose whose -Z is the gaze; false where the runtime has no eye tracking. */
    public boolean gaze(double[] position, float[] orientation) {
        return gazeValid && out(gazePose, 0, position, orientation);
    }

    /** Whether the runtime tracks the eyes' gaze. */
    public boolean eyeTracking() {
        return gazeSpace != null;
    }

    /** Whether the runtime tracks the hands' joints. */
    public boolean handTracking() {
        return trackers != null;
    }

    /**
     * Joint {@code joint} of tracked hand {@code hand} (OpenXR's 26: palm 0, wrist 1, thumb 2 to 5, index 6 to 10,
     * middle 11 to 15, ring 16 to 20, little 21 to 25) into {@code position} and {@code orientation}; returns its
     * radius, or a negative number where the hand is not tracked. Allocates nothing.
     */
    public float joint(Hand hand, int joint, double[] position, float[] orientation) {
        int h = hand.ordinal();
        java.util.Objects.checkIndex(joint, XR_HAND_JOINT_COUNT_EXT);
        if (!jointsValid[h]) {
            return -1;
        }
        out(joints[h], joint * JOINT, position, orientation);
        return joints[h][joint * JOINT + 7];
    }

    /** What {@code hand} holds: an interaction profile path, or empty ({@link XrListener#profile}). */
    public String profile(Hand hand) {
        return profiles[hand.ordinal()];
    }

    private boolean out(float[] pose, int at, double[] position, float[] orientation) {
        double[] origin = headset.originRef();
        position[0] = origin[0] + pose[at];
        position[1] = origin[1] + pose[at + 1];
        position[2] = origin[2] + pose[at + 2];
        orientation[0] = pose[at + 3];
        orientation[1] = pose[at + 4];
        orientation[2] = pose[at + 5];
        orientation[3] = pose[at + 6];
        return true;
    }

    /** Tells {@code listener} what changed since the last poll: buttons, touches, profiles. Allocates nothing. */
    public void poll(XrListener listener) {
        for (int h = 0; h < 2; h++) {
            Hand hand = HANDS[h];
            if (!profiles[h].equals(polledProfiles[h])) {
                polledProfiles[h] = profiles[h];
                listener.profile(hand, profiles[h]);
            }
            for (int b = 0; b < BUTTONS.length; b++) {
                if (pressed[h][b] != polledPressed[h][b]) {
                    polledPressed[h][b] = pressed[h][b];
                    listener.button(hand, BUTTONS[b], pressed[h][b]);
                }
            }
            for (int t = 0; t < TOUCHES.length; t++) {
                if (touched[h][t] != polledTouched[h][t]) {
                    polledTouched[h][t] = touched[h][t];
                    listener.touch(hand, TOUCHES[t], touched[h][t]);
                }
            }
        }
    }

    // ---------------------------------------------------------------- haptics

    /**
     * Vibrates {@code hand}'s controller at {@code amplitude} (0 to 1) for {@code nanos} nanoseconds (0: the shortest
     * the controller makes), at {@code frequency} Hz (0: what the controller deems best).
     */
    public void vibrate(Hand hand, float amplitude, float frequency, long nanos) {
        try (MemoryStack stack = stackPush()) {
            XrHapticVibration vibration = XrHapticVibration.calloc(stack).type$Default()
                .amplitude(Math.clamp(amplitude, 0, 1)).frequency(Math.max(0, frequency))
                .duration(nanos <= 0 ? -1 : nanos);
            check(xrApplyHapticFeedback(session, XrHapticActionInfo.calloc(stack).type$Default().action(haptic)
                .subactionPath(handPaths[hand.ordinal()]), XrHapticBaseHeader.create(vibration.address())),
                "vibrate");
        }
    }

    /** Stops {@code hand}'s vibration. */
    public void stopVibration(Hand hand) {
        try (MemoryStack stack = stackPush()) {
            check(xrStopHapticFeedback(session, XrHapticActionInfo.calloc(stack).type$Default().action(haptic)
                .subactionPath(handPaths[hand.ordinal()])), "stop vibrating");
        }
    }

    /** Frees the input: from the headset's close. */
    @Override
    public void close() {
        if (trackers != null) {
            for (XrHandTrackerEXT tracker : trackers) {
                xrDestroyHandTrackerEXT(tracker);
            }
        }
        for (XrSpace[] spaces : poseSpaces) {
            for (XrSpace space : spaces) {
                if (space != null) {
                    xrDestroySpace(space);
                }
            }
        }
        if (gazeSpace != null) {
            xrDestroySpace(gazeSpace);
        }
        xrDestroySpace(head);
        xrDestroyActionSet(actions);
    }

    private static void check(int result, String what) {
        if (result < 0) {
            throw new HeadsetException("Could not " + what + " (OpenXR " + result + ")");
        }
    }

}
