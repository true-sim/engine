package com.truesim.engine.xr;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.input.Binding;
import com.truesim.engine.input.BinaryAction;
import com.truesim.engine.input.Input;
import com.truesim.engine.input.Player;
import com.truesim.engine.input.PoseAction;
import com.truesim.engine.interaction.PointerKind;
import com.truesim.engine.interaction.SceneActions;
import com.truesim.engine.interaction.SceneInteraction;
import com.truesim.engine.interaction.ScenePointer;
import com.truesim.engine.interaction.ScenePointerSource;
import com.truesim.engine.interaction.ScenePointers;
import java.util.Objects;

/**
 * XR controllers' aim rays as scene pointers ({@link PointerKind#XR_RAY}): a {@link ScenePointerSource} reading
 * {@link SceneActions#AIM_LEFT} and {@link SceneActions#AIM_RIGHT}, their triggers ({@link SceneActions#SELECT_LEFT},
 * {@link SceneActions#SELECT_RIGHT}) the primary button, as pointers {@link ScenePointers#LEFT_RAY_ID} and
 * {@link ScenePointers#RIGHT_RAY_ID}. The {@link Xr} plugin adds it to the scene interaction when both are installed;
 * an untracked hand has no pointer. Polling allocates nothing.
 *
 * <pre>{@code
 * XrSceneRays.defaults(input);                           // the aim poses and triggers bound to the controllers
 * interaction.pointers(new XrSceneRays(input));           // what the Xr plugin does by itself
 * }</pre>
 */
public final class XrSceneRays implements ScenePointerSource {

    private final Input input;
    private final double[] position = new double[3];
    private final float[] orientation = new float[4];

    /** The rays of {@code input}'s first player. */
    public XrSceneRays(Input input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    /**
     * The game's default bindings of the scene's ray actions with a headset's input: the controllers' aim poses and
     * triggers; and the XR controls as a device class of {@code input}.
     */
    @AllowAllocation("set up")
    public static void defaults(Input input) {
        input.addDeviceClass(XrControls.XR);
        input.defaults(SceneActions.AIM_LEFT, Binding.of(XrControls.pose(Hand.LEFT, XrPose.AIM)));
        input.defaults(SceneActions.AIM_RIGHT, Binding.of(XrControls.pose(Hand.RIGHT, XrPose.AIM)));
        input.defaults(SceneActions.SELECT_LEFT, Binding.of(XrControls.button(Hand.LEFT, XrButton.TRIGGER)));
        input.defaults(SceneActions.SELECT_RIGHT, Binding.of(XrControls.button(Hand.RIGHT, XrButton.TRIGGER)));
    }

    @Override
    public void poll(SceneInteraction pointers) {
        Player player = input.player(0);
        hand(pointers, player, ScenePointers.LEFT_RAY_ID, SceneActions.AIM_LEFT, SceneActions.SELECT_LEFT);
        hand(pointers, player, ScenePointers.RIGHT_RAY_ID, SceneActions.AIM_RIGHT, SceneActions.SELECT_RIGHT);
    }

    private void hand(SceneInteraction pointers, Player player, int id, PoseAction aim, BinaryAction select) {
        if (!player.pose(aim, position, orientation)) {
            return; // untracked: the pointer leaves
        }
        float qx = orientation[0];
        float qy = orientation[1];
        float qz = orientation[2];
        float qw = orientation[3];
        // -Z turned by the orientation: the negated third column of its rotation matrix
        double fx = -2 * (qx * qz + qw * qy);
        double fy = -2 * (qy * qz - qw * qx);
        double fz = -(1 - 2 * (qx * qx + qy * qy));
        pointers.pointer(id, PointerKind.XR_RAY).ray(position[0], position[1], position[2], fx, fy, fz, null)
            .buttons(player.down(select) ? ScenePointer.PRIMARY : 0).modifiers(ScenePointers.modifiers(input));
    }
}
