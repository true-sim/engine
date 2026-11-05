package com.truesim.engine.ui;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.app.Frame;
import com.truesim.engine.input.Device;
import com.truesim.engine.input.DeviceIdentity;
import com.truesim.engine.input.Input;
import com.truesim.engine.input.InputSource;
import com.truesim.engine.platform.GamepadAxis;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.MouseButton;
import com.truesim.engine.platform.PenAxis;
import com.truesim.engine.platform.PenState;
import com.truesim.engine.platform.TouchPhase;
import com.truesim.engine.xr.Hand;
import com.truesim.engine.xr.XrButton;
import com.truesim.engine.xr.XrControls;
import com.truesim.engine.xr.XrPose;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import org.junit.jupiter.api.Test;

/** Touch, pen, XR rays and the gamepad's virtual cursor as UI pointers, from synthetic events and recorded poses. */
class UiPointersTest {

    private static Pointer find(UiInput ui, int id) {
        for (int i = 0; i < ui.pointerCount(); i++) {
            if (ui.pointerAt(i).id() == id) {
                return ui.pointerAt(i);
            }
        }
        return null;
    }

    private static void poll(UiInput ui, com.truesim.ui.core.input.PointerSource source) {
        ui.beginFrame();
        source.poll(ui);
    }

    @Test
    void eachFingerIsATouchPointerPressedWhileDownReleasedOnceAndThenGone() {
        TouchPointers touch = new TouchPointers(null).size(1000, 500);
        UiInput ui = new UiInput(8);
        touch.touch(0, 1, 7, 1, TouchPhase.DOWN, 0.1f, 0.2f, 0, 0, 1);
        touch.touch(0, 1, 7, 2, TouchPhase.DOWN, 0.5f, 0.5f, 0, 0, 1);
        poll(ui, touch);
        assertThat(ui.pointerCount()).isEqualTo(2);
        Pointer first = find(ui, TouchPointers.FIRST_ID);
        assertThat(first.kind()).isEqualTo(Pointer.Kind.TOUCH);
        assertThat(first.x()).isEqualTo(100f, within(1e-3f));
        assertThat(first.y()).isEqualTo(100f, within(1e-3f));
        assertThat(first.buttons()).isEqualTo(Pointer.PRIMARY);
        touch.touch(0, 1, 7, 2, TouchPhase.MOVED, 0.6f, 0.5f, 0.1f, 0, 1);
        touch.touch(0, 1, 7, 1, TouchPhase.UP, 0.1f, 0.2f, 0, 0, 0);
        poll(ui, touch);
        assertThat(find(ui, TouchPointers.FIRST_ID).buttons()).as("lifted: released this frame").isZero();
        assertThat(find(ui, TouchPointers.FIRST_ID + 1).x()).isEqualTo(600f, within(1e-3f));
        poll(ui, touch);
        assertThat(find(ui, TouchPointers.FIRST_ID)).as("then gone: fingers do not hover").isNull();
        assertThat(ui.pointerCount()).isOne();
        // A tap between two frames still clicks: pressed one frame, released the next.
        touch.touch(0, 1, 7, 3, TouchPhase.DOWN, 0.3f, 0.3f, 0, 0, 1);
        touch.touch(0, 1, 7, 3, TouchPhase.UP, 0.3f, 0.3f, 0, 0, 0);
        poll(ui, touch);
        assertThat(find(ui, TouchPointers.FIRST_ID).buttons()).isEqualTo(Pointer.PRIMARY);
        poll(ui, touch);
        assertThat(find(ui, TouchPointers.FIRST_ID).buttons()).isZero();
        poll(ui, touch);
        assertThat(find(ui, TouchPointers.FIRST_ID)).isNull();
        // Cancelled by the system: gone without a release.
        touch.touch(0, 1, 7, 2, TouchPhase.CANCELED, 0.6f, 0.5f, 0, 0, 0);
        poll(ui, touch);
        assertThat(ui.pointerCount()).isZero();
        assertThat(touch.count()).isZero();
        // Touches SDL makes from the mouse or a pen are not fingers.
        touch.touch(0, 1, TouchPointers.MOUSE_TOUCH, 1, TouchPhase.DOWN, 0.3f, 0.3f, 0, 0, 1);
        touch.touch(0, 1, TouchPointers.PEN_TOUCH, 1, TouchPhase.DOWN, 0.3f, 0.3f, 0, 0, 1);
        poll(ui, touch);
        assertThat(ui.pointerCount()).isZero();
        // At most MAX_FINGERS.
        for (int f = 0; f < TouchPointers.MAX_FINGERS + 3; f++) {
            touch.touch(0, 1, 7, 100 + f, TouchPhase.DOWN, 0.01f * f, 0.5f, 0, 0, 1);
        }
        poll(ui, touch);
        assertThat(ui.pointerCount()).isEqualTo(TouchPointers.MAX_FINGERS);
    }

    @Test
    void theMouseIgnoresTheEventsSdlMakesFromTouchesAndPens() {
        MousePointer mouse = new MousePointer().ignoreEmulated(true);
        UiInput ui = new UiInput(8);
        mouse.mouseMoved(0, 1, MousePointer.TOUCH_MOUSE, 50, 50, 0, 0, 0);
        mouse.mouseButton(0, 1, MousePointer.PEN_MOUSE, MouseButton.LEFT, true, 1, 50, 50);
        poll(ui, mouse);
        assertThat(ui.pointerCount()).isZero();
        mouse.mouseMoved(0, 1, 3, 10, 20, 0, 0, 0);
        poll(ui, mouse);
        assertThat(find(ui, MousePointer.ID).x()).isEqualTo(10f);
        mouse.ignoreEmulated(false).mouseMoved(0, 1, MousePointer.TOUCH_MOUSE, 50, 50, 0, 0, 0);
        poll(ui, mouse);
        assertThat(find(ui, MousePointer.ID).x()).as("without touch pointers, the emulated mouse is the mouse")
            .isEqualTo(50f);
    }

    @Test
    void aPenHoversPressesWithItsTipAndBarrelButtonsAndLeaves() {
        PenPointer pen = new PenPointer(null).modifiers(() -> Pointer.GIZMO_SNAP);
        UiInput ui = new UiInput(8);
        poll(ui, pen);
        assertThat(ui.pointerCount()).as("away").isZero();
        pen.penProximity(0, 1, 1, true);
        pen.penMoved(0, 1, 1, 40, 30, PenState.IN_PROXIMITY);
        poll(ui, pen);
        Pointer p = find(ui, PenPointer.ID);
        assertThat(p.kind()).isEqualTo(Pointer.Kind.PEN);
        assertThat(p.x()).isEqualTo(40f);
        assertThat(p.buttons()).as("hovering").isZero();
        assertThat(p.modifiers()).isEqualTo(Pointer.GIZMO_SNAP);
        pen.penTouch(0, 1, 1, 41, 31, false, true);
        poll(ui, pen);
        assertThat(find(ui, PenPointer.ID).buttons()).isEqualTo(Pointer.PRIMARY);
        pen.penButton(0, 1, 1, 41, 31, 1, true);
        poll(ui, pen);
        assertThat(find(ui, PenPointer.ID).buttons()).isEqualTo(Pointer.PRIMARY | Pointer.SECONDARY);
        pen.penButton(0, 1, 1, 41, 31, 1, false);
        pen.penTouch(0, 1, 1, 41, 31, false, false);
        pen.penMoved(0, 1, 1, 42, 31, PenState.IN_PROXIMITY | PenState.BUTTON2);
        poll(ui, pen);
        assertThat(find(ui, PenPointer.ID).buttons()).isEqualTo(Pointer.MIDDLE);
        // A touch and lift between two frames still clicks.
        pen.penMoved(0, 1, 1, 42, 31, PenState.IN_PROXIMITY);
        pen.penTouch(0, 1, 1, 42, 31, false, true);
        pen.penTouch(0, 1, 1, 42, 31, false, false);
        poll(ui, pen);
        assertThat(find(ui, PenPointer.ID).buttons()).isEqualTo(Pointer.PRIMARY);
        poll(ui, pen);
        assertThat(find(ui, PenPointer.ID).buttons()).isZero();
        pen.penProximity(0, 1, 1, false);
        poll(ui, pen);
        assertThat(ui.pointerCount()).isZero();
    }

    @Test
    void aPenPassesOnItsPressureTiltAndRotation() {
        PenPointer pen = new PenPointer(null);
        UiInput ui = new UiInput(8);
        pen.penProximity(0, 1, 1, true);
        pen.penMoved(0, 1, 1, 40, 30, PenState.IN_PROXIMITY);
        poll(ui, pen);
        Pointer p = find(ui, PenPointer.ID);
        assertThat(p.reportsPressure()).as("no pressure axis yet").isFalse();
        assertThat(p.pressure()).isZero();
        pen.penTouch(0, 1, 1, 40, 30, false, true);
        pen.penAxis(0, 1, 1, 40, 30, PenAxis.PRESSURE, 0.3f);
        pen.penAxis(0, 1, 1, 40, 30, PenAxis.PRESSURE, 0.65f); // the newest before the frame counts
        pen.penAxis(0, 1, 1, 41, 30, PenAxis.TILT_X, -25);
        pen.penAxis(0, 1, 1, 41, 30, PenAxis.TILT_Y, 12.5f);
        pen.penAxis(0, 1, 1, 41, 30, PenAxis.ROTATION, -90);
        pen.penAxis(0, 1, 1, 41, 30, PenAxis.DISTANCE, 0.4f);
        poll(ui, pen);
        p = find(ui, PenPointer.ID);
        assertThat(p.buttons()).isEqualTo(Pointer.PRIMARY);
        assertThat(p.x()).isEqualTo(41f);
        assertThat(p.reportsPressure()).isTrue();
        assertThat(p.pressure()).isEqualTo(0.65f);
        assertThat(p.tiltX()).isEqualTo(-25f);
        assertThat(p.tiltY()).isEqualTo(12.5f);
        assertThat(p.twist()).as("SDL's -180..180 as [0, 360)").isEqualTo(270f);
        assertAllocationFree(() -> {
            pen.penAxis(0, 1, 1, 41, 30, PenAxis.PRESSURE, 0.7f);
            pen.poll(ui);
        });
        // out of range and back: the next pen starts without pressure or tilt
        pen.penProximity(0, 1, 1, false);
        poll(ui, pen);
        pen.penProximity(0, 1, 1, true);
        pen.penMoved(0, 1, 1, 40, 30, PenState.IN_PROXIMITY);
        poll(ui, pen);
        p = find(ui, PenPointer.ID);
        assertThat(p.reportsPressure()).isFalse();
        assertThat(p.tiltX()).isZero();
        assertThat(p.twist()).isZero();
    }

    /** A headset's controllers, playing recorded poses: per frame, the right hand's aim and its trigger. */
    private static final class RecordedHeadset implements InputSource {
        // x, y, z, then the orientation's x, y, z, w; NaN: not tracked
        final double[][] poses;
        final boolean[] trigger;
        int frame;
        Device device;

        RecordedHeadset(double[][] poses, boolean[] trigger) {
            this.poses = poses;
            this.trigger = trigger;
        }

        @Override
        public void start(Input input) {
            input.addDeviceClass(XrControls.XR);
            device = input.connect(XrControls.XR, 0, new DeviceIdentity("openxr", "", "recorded"), "headset", this);
        }

        @Override
        public void sample(Frame f) {
            int at = Math.min(frame++, poses.length - 1);
            double[] p = poses[at];
            int aim = XrControls.pose(Hand.RIGHT, XrPose.AIM).index();
            if (Double.isNaN(p[0])) {
                device.lose(aim);
            } else {
                device.pose(aim, p[0], p[1], p[2], (float) p[3], (float) p[4], (float) p[5], (float) p[6]);
            }
            device.set(XrControls.button(Hand.RIGHT, XrButton.TRIGGER).index(), trigger[at] ? 1 : 0);
            device.lose(XrControls.pose(Hand.LEFT, XrPose.AIM).index());
        }
    }

    @Test
    void xrAimPosesAreRaysAndTheirTriggersPress() {
        float s = (float) Math.sqrt(0.5);
        double[][] poses = {
            {0, 1.5, 0, 0, 0, 0, 1},       // straight ahead: -Z
            {0.2, 1.4, -0.1, 0, s, 0, s},  // turned 90 degrees left about Y: -X
            {0, 1.5, 0, s, 0, 0, s},       // pitched 90 degrees up about X: +Y
            {Double.NaN, 0, 0, 0, 0, 0, 1}, // lost
        };
        boolean[] trigger = {false, true, true, false};
        RecordedHeadset headset = new RecordedHeadset(poses, trigger);
        try (InputRig rig = new InputRig()) {
            rig.input.addSource(headset);
            rig.frame();
            XrPointers.defaults(rig.input);
            assertThat(rig.input.bindingSetting(UiActions.AIM_RIGHT).name()).isEqualTo("input.ui.pointer.aimRight");
            XrPointers rays = new XrPointers(rig.input);
            headset.frame = 0;
            double[][] expected = {{0, 0, -1}, {-1, 0, 0}, {0, 1, 0}};
            for (int f = 0; f < 3; f++) {
                rig.frame();
                rays.poll(rig.ui);
                Pointer p = find(rig.ui, XrPointers.RIGHT_ID);
                assertThat(p).as("frame %d", f).isNotNull();
                assertThat(p.kind()).isEqualTo(Pointer.Kind.XR_RAY);
                assertThat(p.hasRay()).isTrue();
                assertThat(p.rayOriginX()).isEqualTo(poses[f][0], within(1e-6));
                assertThat(p.rayOriginY()).isEqualTo(poses[f][1], within(1e-6));
                assertThat(new double[] {p.rayDirectionX(), p.rayDirectionY(), p.rayDirectionZ()})
                    .as("frame %d's direction", f).containsExactly(expected[f], within(1e-5));
                assertThat(p.buttons()).isEqualTo(trigger[f] ? Pointer.PRIMARY : 0);
                assertThat(p.x()).as("off every screen layer").isLessThan(-1e8f);
                assertThat(find(rig.ui, XrPointers.LEFT_ID)).as("the left hand is not tracked").isNull();
            }
            rig.frame();
            rays.poll(rig.ui);
            assertThat(find(rig.ui, XrPointers.RIGHT_ID)).as("lost: no pointer").isNull();
            rig.frame();
            assertAllocationFree(() -> {
                headset.frame = 1;
                rig.ui.beginFrame();
                rays.poll(rig.ui);
            });
        }
    }

    @Test
    void theVirtualCursorIsOffByDefaultMovesByTheRightStickAndClicksWithSouth() {
        try (InputRig rig = new InputRig()) {
            VirtualCursor cursor = new VirtualCursor(rig.input, rig.engine.settings(), () -> rig.now / 1e9,
                () -> 800, () -> 600);
            rig.commands.virtualCursor(cursor::on);
            rig.plug(1).frame();
            cursor.poll(rig.ui);
            assertThat(cursor.on()).isFalse();
            assertThat(rig.ui.pointerCount()).isZero();
            rig.pad(1, GamepadButton.SOUTH, true).frame();
            assertThat(rig.commands()).as("off: south accepts").containsExactly("ACCEPT");
            rig.pad(1, GamepadButton.SOUTH, false).frame();
            rig.engine.settings().set(VirtualCursor.ON, true).set(VirtualCursor.SPEED, 500f);
            rig.frame(100);
            cursor.poll(rig.ui);
            Pointer p = find(rig.ui, VirtualCursor.ID);
            assertThat(p.kind()).isEqualTo(Pointer.Kind.VIRTUAL_CURSOR);
            assertThat(p.x()).as("starts in the middle").isEqualTo(400f);
            assertThat(p.y()).isEqualTo(300f);
            rig.events().gamepadAxis(0, 1, GamepadAxis.RIGHT_X, 1);
            rig.frame(100);
            cursor.poll(rig.ui);
            p = find(rig.ui, VirtualCursor.ID);
            assertThat(p.x()).as("0.1 s at 500 px/s").isCloseTo(450f, within(5f));
            rig.events().gamepadAxis(0, 1, GamepadAxis.RIGHT_X, 0);
            rig.events().gamepadAxis(0, 1, GamepadAxis.RIGHT_Y, 1); // pushed down (SDL's y grows downwards)
            rig.frame(100);
            cursor.poll(rig.ui);
            p = find(rig.ui, VirtualCursor.ID);
            assertThat(p.y()).as("down on the stick is down").isCloseTo(350f, within(5f));
            rig.events().gamepadAxis(0, 1, GamepadAxis.RIGHT_X, 0);
            rig.events().gamepadAxis(0, 1, GamepadAxis.RIGHT_Y, 0);
            rig.pad(1, GamepadButton.SOUTH, true).frame();
            cursor.poll(rig.ui);
            assertThat(find(rig.ui, VirtualCursor.ID).buttons()).isEqualTo(Pointer.PRIMARY);
            assertThat(rig.commands()).as("on: south clicks, it does not accept").isEmpty();
            rig.pad(1, GamepadButton.SOUTH, false).frame();
            rig.events().gamepadAxis(0, 1, GamepadAxis.RIGHT_X, -1);
            for (int i = 0; i < 30; i++) {
                rig.frame(100);
                cursor.poll(rig.ui);
            }
            assertThat(find(rig.ui, VirtualCursor.ID).x()).as("kept on the UI").isZero();
            rig.events().gamepadAxis(0, 1, GamepadAxis.RIGHT_X, 0);
            rig.frame();
            assertAllocationFree(() -> {
                rig.ui.beginFrame();
                rig.now += 16 * InputRig.MS;
                cursor.poll(rig.ui);
            });
            rig.engine.settings().set(VirtualCursor.ON, false);
            rig.frame();
            cursor.poll(rig.ui);
            assertThat(find(rig.ui, VirtualCursor.ID)).isNull();
        }
    }

    @Test
    void touchAndPenPollingAllocateNothing() {
        TouchPointers touch = new TouchPointers(null).size(100, 100);
        PenPointer pen = new PenPointer(null);
        UiInput ui = new UiInput(8);
        long[] finger = {0};
        assertAllocationFree(() -> {
            touch.touch(0, 1, 7, finger[0], TouchPhase.DOWN, 0.5f, 0.5f, 0, 0, 1);
            pen.penMoved(0, 1, 1, 10, 10, PenState.IN_PROXIMITY | PenState.DOWN);
            ui.beginFrame();
            touch.poll(ui);
            pen.poll(ui);
            touch.touch(0, 1, 7, finger[0], TouchPhase.UP, 0.5f, 0.5f, 0, 0, 1);
            touch.poll(ui);
            touch.poll(ui);
            finger[0]++;
        });
    }
}
