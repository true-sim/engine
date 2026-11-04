package com.truesim.engine.ui;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.input.BinaryAction;
import com.truesim.engine.input.Binding;
import com.truesim.engine.input.Controls;
import com.truesim.engine.input.Input;
import com.truesim.engine.input.Player;
import com.truesim.engine.input.PoseAction;
import com.truesim.engine.platform.GamepadAxis;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.Key;
import com.truesim.engine.xr.Hand;
import com.truesim.engine.xr.XrButton;
import com.truesim.engine.xr.XrControls;
import com.truesim.engine.xr.XrPose;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.PointerSource;
import com.truesim.ui.core.input.UiInput;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * XR controllers' pointing rays as UI pointers of {@link Pointer.Kind#XR_RAY} (design 4.7): each hand's
 * {@link UiActions#AIM_LEFT}/{@link UiActions#AIM_RIGHT} pose, while tracked, is a {@link Pointer#ray world ray} from
 * the pose's position along its forward direction (OpenXR's aim pose points along its -Z), its
 * {@link UiActions#SELECT_LEFT}/{@link UiActions#SELECT_RIGHT} the primary button; an untracked hand has no pointer.
 * Scene picking and world panels test the ray (ui-scene); its screen position is far outside every layer, so screen
 * layers never take it. Ids {@link #LEFT_ID} and {@link #RIGHT_ID}, the first player's.
 *
 * <p>{@link #defaults} binds the actions to the headset's controls (the aim poses, the triggers) and adds the left
 * menu button held to the console's toggle, as the game's defaults; the UI plugin does it when a headset's input is
 * there. It binds the gizmo's too: the grips grab ({@link UiActions#GRAB_LEFT}, {@link UiActions#GRAB_RIGHT}, both
 * held for the two-handed scale and turn, {@link XrGizmoGrab}) from the grip poses ({@link UiActions#HAND_LEFT},
 * {@link UiActions#HAND_RIGHT}), either thumbstick pressed snaps ({@link UiActions#SNAP}, rays and grabs alike), the
 * right A gives the next mode, the left X the next space, the left Y the next pivot. Polling allocates nothing.
 */
public final class XrPointers implements PointerSource {

    /** The left hand's pointer id. */
    public static final int LEFT_ID = 3;
    /** The right hand's pointer id. */
    public static final int RIGHT_ID = 4;
    /** Where an XR pointer is on the screen: outside every layer. */
    static final float OFF_SCREEN = -1e9f;

    private final Input input;
    private final double[] position = new double[3];
    private final float[] orientation = new float[4];
    private IntSupplier modifiers;

    /** The rays of {@code input}'s first player. */
    public XrPointers(Input input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    /**
     * The modifiers the rays carry ({@link UiKeyboard#pointerModifiers}); returns this. The gizmo's snap held
     * ({@link UiActions#SNAP}) is added either way.
     */
    public XrPointers modifiers(IntSupplier value) {
        modifiers = value;
        return this;
    }

    /**
     * The game's default bindings of the UI's XR actions on {@code input} (the engine started, a headset's input
     * added): the aim poses, the triggers, and the left menu button held besides the console toggle's keys and pad.
     */
    @AllowAllocation("set up")
    public static void defaults(Input input) {
        input.defaults(UiActions.AIM_LEFT, Binding.of(XrControls.pose(Hand.LEFT, XrPose.AIM)));
        input.defaults(UiActions.AIM_RIGHT, Binding.of(XrControls.pose(Hand.RIGHT, XrPose.AIM)));
        input.defaults(UiActions.SELECT_LEFT, Binding.of(XrControls.button(Hand.LEFT, XrButton.TRIGGER)));
        input.defaults(UiActions.SELECT_RIGHT, Binding.of(XrControls.button(Hand.RIGHT, XrButton.TRIGGER)));
        input.defaults(UiActions.TOGGLE_CONSOLE, Binding.key(Key.BACKQUOTE),
            Binding.chord(Controls.pad(GamepadButton.LEFT_STICK), Controls.pad(GamepadButton.RIGHT_STICK)),
            Binding.of(XrControls.button(Hand.LEFT, XrButton.MENU)).hold());
        // the gizmo: grips grab (both: two-handed), their grip poses are the hands, a thumbstick pressed snaps
        input.defaults(UiActions.GRAB_LEFT, Binding.of(XrControls.button(Hand.LEFT, XrButton.SQUEEZE)));
        input.defaults(UiActions.GRAB_RIGHT, Binding.of(XrControls.button(Hand.RIGHT, XrButton.SQUEEZE)));
        input.defaults(UiActions.HAND_LEFT, Binding.of(XrControls.pose(Hand.LEFT, XrPose.GRIP)));
        input.defaults(UiActions.HAND_RIGHT, Binding.of(XrControls.pose(Hand.RIGHT, XrPose.GRIP)));
        input.defaults(UiActions.SNAP, Binding.of(Controls.CONTROL), Binding.pad(GamepadAxis.LEFT_TRIGGER),
            Binding.of(XrControls.button(Hand.LEFT, XrButton.THUMBSTICK)),
            Binding.of(XrControls.button(Hand.RIGHT, XrButton.THUMBSTICK)));
        // the face buttons no other ui action uses: A the next mode, X the space, Y the pivot
        input.defaults(UiActions.NEXT_MODE, Binding.of(XrControls.button(Hand.RIGHT, XrButton.PRIMARY)));
        input.defaults(UiActions.TOGGLE_SPACE, Binding.key(Key.X),
            Binding.of(XrControls.button(Hand.LEFT, XrButton.PRIMARY)));
        input.defaults(UiActions.NEXT_PIVOT, Binding.key(Key.PERIOD),
            Binding.of(XrControls.button(Hand.LEFT, XrButton.SECONDARY)));
    }

    /** Writes each tracked hand's ray pointer into {@code ui}, removes the others; call at the frame's start. */
    @Override
    public void poll(UiInput ui) {
        Player player = input.player(0);
        hand(ui, player, LEFT_ID, UiActions.AIM_LEFT, UiActions.SELECT_LEFT);
        hand(ui, player, RIGHT_ID, UiActions.AIM_RIGHT, UiActions.SELECT_RIGHT);
    }

    private void hand(UiInput ui, Player player, int id, PoseAction aim, BinaryAction select) {
        if (!player.pose(aim, position, orientation)) {
            ui.removePointer(id);
            return;
        }
        float qx = orientation[0];
        float qy = orientation[1];
        float qz = orientation[2];
        float qw = orientation[3];
        // -Z rotated by the orientation: the negated third column of its rotation matrix.
        double fx = -2 * (qx * qz + qw * qy);
        double fy = -2 * (qy * qz - qw * qx);
        double fz = -(1 - 2 * (qx * qx + qy * qy));
        ui.pointer(id, Pointer.Kind.XR_RAY).moveTo(OFF_SCREEN, OFF_SCREEN)
            .buttons(player.down(select) ? Pointer.PRIMARY : 0)
            .modifiers((modifiers == null ? 0 : modifiers.getAsInt())
                | (player.down(UiActions.SNAP) ? Pointer.GIZMO_SNAP : 0))
            .ray(position[0], position[1], position[2], fx, fy, fz);
    }
}
