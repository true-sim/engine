package com.truesim.engine.ui;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.truesim.common.math.Quat;
import com.truesim.common.math.Transform;
import com.truesim.common.math.Vec3;
import com.truesim.engine.app.Frame;
import com.truesim.engine.input.Binding;
import com.truesim.engine.input.Device;
import com.truesim.engine.input.DeviceIdentity;
import com.truesim.engine.input.Input;
import com.truesim.engine.input.InputSource;
import com.truesim.engine.platform.Key;
import com.truesim.engine.xr.Hand;
import com.truesim.engine.xr.XrButton;
import com.truesim.engine.xr.XrControls;
import com.truesim.engine.xr.XrPose;
import com.truesim.scene.core.Mobility;
import com.truesim.ui.gizmo.Gizmo;
import com.truesim.ui.gizmo.GizmoGrab;
import com.truesim.ui.scene.SceneRig;
import com.truesim.ui.style.tss.Tss;
import org.junit.jupiter.api.Test;

/** A gizmo's XR grabs through the {@code ui.gizmo} actions, from recorded grip poses and buttons: rebindable. */
class XrGizmoGrabTest {

    /** A headset's two controllers: per hand, a grip pose (NaN x: not tracked), the grip, trigger and thumbstick. */
    private static final class Headset implements InputSource {
        final double[][] pose = {{Double.NaN, 0, 0, 0, 0, 0, 1}, {Double.NaN, 0, 0, 0, 0, 0, 1}};
        final boolean[] squeeze = new boolean[2];
        final boolean[] trigger = new boolean[2];
        final boolean[] thumbstick = new boolean[2];
        Device device;

        @Override
        public void start(Input input) {
            input.addDeviceClass(XrControls.XR);
            device = input.connect(XrControls.XR, 0, new DeviceIdentity("openxr", "", "recorded"), "headset", this);
        }

        @Override
        public void sample(Frame f) {
            for (int h = 0; h < 2; h++) {
                Hand hand = h == 0 ? Hand.LEFT : Hand.RIGHT;
                int grip = XrControls.pose(hand, XrPose.GRIP).index();
                double[] p = pose[h];
                if (Double.isNaN(p[0])) {
                    device.lose(grip);
                } else {
                    device.pose(grip, p[0], p[1], p[2], (float) p[3], (float) p[4], (float) p[5], (float) p[6]);
                }
                device.set(XrControls.button(hand, XrButton.SQUEEZE).index(), squeeze[h] ? 1 : 0);
                device.set(XrControls.button(hand, XrButton.TRIGGER).index(), trigger[h] ? 1 : 0);
                device.set(XrControls.button(hand, XrButton.THUMBSTICK).index(), thumbstick[h] ? 1 : 0);
            }
        }

        Headset hand(int h, double x, double y, double z, double qx, double qy, double qz, double qw, boolean grip) {
            double[] p = pose[h];
            p[0] = x;
            p[1] = y;
            p[2] = z;
            p[3] = qx;
            p[4] = qy;
            p[5] = qz;
            p[6] = qw;
            squeeze[h] = grip;
            return this;
        }
    }

    /** The headset, the input, a gizmo on a box and its grab driven by the actions. */
    private static final class Rig implements AutoCloseable {
        final Headset headset = new Headset();
        final InputRig input = new InputRig();
        final SceneRig scene = new SceneRig(Tss.defaultTheme(), null, 800, 600);
        final long node = scene.box(0, 1, -1, 0.25, Mobility.DYNAMIC);
        final Gizmo gizmo = new Gizmo(scene.picking, scene.view, null);
        final XrGizmoGrab driver;

        Rig() {
            input.input.addSource(headset);
            input.frame();
            XrPointers.defaults(input.input);
            gizmo.select(node);
            driver = new XrGizmoGrab(input.input, new GizmoGrab(gizmo, scene.scene));
            scene.frames(2);
        }

        /** A frame: the devices sampled, the grab driven, the UI's frame. */
        Rig frame() {
            input.frame();
            driver.update();
            scene.frame();
            return this;
        }

        @Override
        public void close() {
            input.close();
        }
    }

    @Test
    void theGripsGrabThroughTheActionsOneHandFollowsTwoHandsScaleSnapAndCancel() {
        try (Rig r = new Rig()) {
            assertThat(r.input.input.bindingSetting(UiActions.GRAB_RIGHT).name())
                .isEqualTo("input.ui.gizmo.grabRight");
            assertThat(r.input.input.bindingSetting(UiActions.HAND_LEFT).name()).isEqualTo("input.ui.gizmo.handLeft");
            GizmoGrab grab = r.driver.grab();
            // recorded: the right hand reaches into the box, grips, moves 0.5 m right turning 90 degrees about y
            double s = Math.sin(Math.PI / 4);
            double c = Math.cos(Math.PI / 4);
            double[][] right = {{0.4, 1, -1, 0, 0, 0, 1, 0}, {0.1, 1, -1, 0, 0, 0, 1, 1},
                {0.3, 1, -1, 0, s / 2, 0, c, 1}, {0.6, 1, -1, 0, s, 0, c, 1}, {0.6, 1, -1, 0, s, 0, c, 0}};
            for (double[] p : right) {
                r.headset.hand(1, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7] > 0);
                r.frame();
            }
            assertThat(grab.grabbing()).isFalse();
            Transform t = r.scene.scene.local(r.node);
            Vec3 expected = new Quat(0, s, 0, c).normalize().rotate(new Vec3(-0.1, 0, 0)).add(new Vec3(0.6, 1, -1));
            assertThat(t.translation().x()).isCloseTo(expected.x(), offset(1e-6));
            assertThat(t.translation().z()).isCloseTo(expected.z(), offset(1e-6));
            assertThat(t.rotation().y()).isCloseTo(s, offset(1e-6));

            // both grips in the box, pulled apart to 2.07 times their distance with a thumbstick pressed: 2.1
            Vec3 at = t.translation();
            double[] spread = {0.1, 0.1, 0.15, 0.207};
            for (int i = 0; i < spread.length; i++) {
                boolean gripping = i > 0;
                r.headset.thumbstick[1] = i == 3;
                r.headset.hand(0, at.x() - spread[i], at.y(), at.z(), 0, 0, 0, 1, gripping);
                r.headset.hand(1, at.x() + spread[i], at.y(), at.z(), 0, 0, 0, 1, gripping);
                r.frame();
            }
            assertThat(grab.hands()).as("two-handed: both grabs held").isEqualTo(2);
            assertThat(grab.delta().snapped()).isTrue();
            assertThat(r.scene.scene.local(r.node).scale().x()).isCloseTo(2.1, offset(1e-6));

            // the cancel action restores the start; the grips still held grab again only once let go
            r.input.key(Key.ESCAPE, true);
            r.frame();
            r.input.key(Key.ESCAPE, false);
            assertThat(grab.grabbing()).isFalse();
            assertThat(r.scene.scene.local(r.node).scale().x()).isCloseTo(1, offset(1e-9));
            r.frame();
            assertThat(grab.grabbing()).as("held through the cancel: no grab").isFalse();
            r.headset.squeeze[0] = false;
            r.headset.squeeze[1] = false;
            r.frame();
            r.headset.squeeze[1] = true;
            r.frame();
            assertThat(grab.grabbing()).isTrue();
            assertThat(grab.hands()).isEqualTo(1);
            assertAllocationFree(() -> r.driver.update());

            // a hand lost: it lets go
            r.headset.pose[1][0] = Double.NaN;
            r.frame();
            assertThat(grab.grabbing()).isFalse();
        }
    }

    @Test
    void rebindingTheGrabMovesItToAnotherButton() {
        try (Rig r = new Rig()) {
            r.input.input.bind(r.input.input.player(0), UiActions.GRAB_RIGHT,
                Binding.of(XrControls.button(Hand.RIGHT, XrButton.TRIGGER)));
            r.headset.hand(1, 0.1, 1, -1, 0, 0, 0, 1, true);
            r.frame().frame();
            assertThat(r.driver.grab().grabbing()).as("the grip no longer grabs").isFalse();
            r.headset.squeeze[1] = false;
            r.headset.trigger[1] = true;
            r.frame();
            assertThat(r.driver.grab().grabbing()).as("the trigger does").isTrue();
            r.headset.hand(1, 0.3, 1, -1, 0, 0, 0, 1, false);
            r.frame();
            assertThat(r.scene.scene.local(r.node).translation().x()).isCloseTo(0.2, offset(1e-6));
            r.headset.trigger[1] = false;
            r.frame();
            assertThat(r.driver.grab().grabbing()).isFalse();
        }
    }
}
