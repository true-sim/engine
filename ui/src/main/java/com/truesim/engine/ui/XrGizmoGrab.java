package com.truesim.engine.ui;

import com.truesim.engine.input.BinaryAction;
import com.truesim.engine.input.Input;
import com.truesim.engine.input.Player;
import com.truesim.engine.input.PoseAction;
import com.truesim.ui.gizmo.GizmoGrab;
import java.util.Objects;

/**
 * A gizmo's XR grabs ({@link GizmoGrab}, design 8) from the {@code ui.gizmo} actions rather than the devices, so
 * players rebind them like any action: each hand is where its
 * {@link UiActions#HAND_LEFT}/{@link UiActions#HAND_RIGHT} pose is (the controllers' grip poses by default,
 * {@link XrPointers#defaults}) and grabs while its {@link UiActions#GRAB_LEFT}/{@link UiActions#GRAB_RIGHT} is held
 * (the grips); both held scale and turn the selection (two-handed). {@link UiActions#SNAP} held snaps; {@link UiActions#CANCEL_DRAG} pressed lets go and restores the
 * start (a hand grabs again once its grab was let go). An untracked hand lets go. The first player's actions.
 *
 * <pre>{@code
 * plugin.gizmoGrab(new GizmoGrab(gizmo, graph));   // updated at each frame's start, after the UI's input
 * }</pre>
 *
 * <p>Updating allocates nothing.
 */
public final class XrGizmoGrab {

    private final Input input;
    private final GizmoGrab grab;
    private final double[] position = new double[3];
    private final float[] orientation = new float[4];
    private final boolean[] released = {true, true}; // per hand: let go since a cancel (a cancel waits for that)

    /** {@code grab} driven by {@code input}'s first player. */
    public XrGizmoGrab(Input input, GizmoGrab grab) {
        this.input = Objects.requireNonNull(input, "input");
        this.grab = Objects.requireNonNull(grab, "grab");
    }

    /** The grab it drives. */
    public GizmoGrab grab() {
        return grab;
    }

    /** Feeds this frame's hands to the grab and updates it: grabs, follows, lets go. */
    public void update() {
        Player player = input.player(0);
        if (player.presses(UiActions.CANCEL_DRAG) > 0 && grab.grabbing()) {
            grab.cancel();
            released[0] = false;
            released[1] = false;
        }
        boolean snap = player.down(UiActions.SNAP);
        hand(player, 0, UiActions.HAND_LEFT, UiActions.GRAB_LEFT, snap);
        hand(player, 1, UiActions.HAND_RIGHT, UiActions.GRAB_RIGHT, snap);
        grab.update();
    }

    private void hand(Player player, int index, PoseAction pose, BinaryAction grip, boolean snap) {
        boolean held = player.down(grip);
        released[index] |= !held;
        if (player.pose(pose, position, orientation)) {
            grab.hand(index, position[0], position[1], position[2], orientation[0], orientation[1], orientation[2],
                orientation[3], held && released[index], snap);
        } else {
            grab.hand(index, 0, 0, 0, 0, 0, 0, 1, false, snap); // not tracked: lets go
        }
    }
}
