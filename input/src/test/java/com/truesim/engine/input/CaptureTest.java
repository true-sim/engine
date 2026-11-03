package com.truesim.engine.input;

import static com.truesim.engine.input.Rig.GAME;
import static com.truesim.engine.input.Rig.JUMP;
import static com.truesim.engine.input.Rig.MOVE;
import static com.truesim.engine.input.Rig.SAVE;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.platform.GamepadAxis;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.Key;
import org.junit.jupiter.api.Test;

/** Binding capture: the next input becomes the binding; a device recorded only for pads (D72). */
class CaptureTest {

    @Test
    void aKeyReleasedAloneIsBoundAloneAndTheActionsSeeNothingMeanwhile() {
        try (Rig rig = new Rig(GAME)) {
            rig.key(Key.ENTER, true).frame(); // held when the capture starts: counts only once released
            Capture capture = rig.input.capture(rig.one(), JUMP);
            assertThat(capture.player()).isSameAs(rig.one());
            assertThat(capture.action()).isSameAs(JUMP);
            rig.key(Key.SPACE, true).frame();
            assertThat(rig.one().down(JUMP)).isFalse(); // listening: the actions see nothing
            assertThat(capture.done()).isFalse();
            rig.key(Key.ENTER, false).key(Key.SPACE, false).frame();
            assertThat(capture.done()).isTrue();
            assertThat(capture.cancelled()).isFalse();
            assertThat(capture.binding()).isEqualTo(Binding.key(Key.SPACE));
            assertThat(capture.control()).isEqualTo(Controls.key(Key.SPACE));
            rig.input.rebind(rig.one(), JUMP, 0, Binding.key(Key.J));
            assertThat(rig.one().bindings(JUMP)).containsExactly(Binding.key(Key.J), Binding.pad(GamepadButton.SOUTH));
            rig.input.rebind(rig.one(), JUMP, 5, capture.binding());
            assertThat(rig.one().bindings(JUMP)).hasSize(3);
        }
    }

    @Test
    void aKeyHeldWhileAnotherGoesDownIsAChord() {
        try (Rig rig = new Rig(GAME)) {
            rig.frame();
            Capture capture = rig.input.capture(rig.one(), SAVE);
            rig.key(Key.LEFT_CONTROL, true).frame();
            rig.key(Key.S, true).frame();
            assertThat(capture.binding()).isEqualTo(Binding.chord(Controls.key(Key.LEFT_CONTROL), Controls.key(Key.S)));
            assertThat(rig.input.conflicts(rig.one(), SAVE, capture.binding())).isEmpty();
        }
    }

    @Test
    void aPadRecordsItsDeviceAndAStickBindsItsPair() { // D72
        try (Rig rig = new Rig(GAME)) {
            rig.plug(1, "pad-guid", "SN", "").frame();
            Capture button = rig.input.capture(rig.one(), JUMP);
            rig.pad(1, GamepadButton.LEFT_SHOULDER, true).frame();
            rig.pad(1, GamepadButton.WEST, true).frame();
            assertThat(button.binding().toString()).isEqualTo("pad:LEFT_SHOULDER+pad:WEST device(pad-guid,SN,)");
            assertThat(button.binding().device()).isEqualTo(new DeviceIdentity("pad-guid", "SN", ""));
            rig.pad(1, GamepadButton.LEFT_SHOULDER, false).pad(1, GamepadButton.WEST, false).frame();
            Capture stick = rig.input.capture(rig.one(), MOVE);
            rig.axis(1, GamepadAxis.RIGHT_X, 0.9f).frame();
            assertThat(stick.binding()).isEqualTo(Binding.stick(true).device(new DeviceIdentity("pad-guid", "SN", "")));
            assertThat(stick.control()).isEqualTo(Controls.pad(GamepadAxis.RIGHT_X));
            // Bound to that pad only: another pad does not drive it.
            rig.input.bind(rig.one(), JUMP, button.binding());
            rig.plug(2, "pad-guid", "OTHER", "").frame();
            rig.pad(2, GamepadButton.LEFT_SHOULDER, true).pad(2, GamepadButton.WEST, true).frame();
            assertThat(rig.one().down(JUMP)).isFalse();
            rig.pad(1, GamepadButton.LEFT_SHOULDER, true).pad(1, GamepadButton.WEST, true).frame();
            assertThat(rig.one().down(JUMP)).isTrue();
        }
    }

    @Test
    void escapeCancelsAndAWheelBindsAtOnce() {
        try (Rig rig = new Rig(GAME)) {
            rig.frame();
            Capture capture = rig.input.capture(rig.one(), JUMP);
            rig.key(Key.ESCAPE, true).frame();
            assertThat(capture.done()).isTrue();
            assertThat(capture.cancelled()).isTrue();
            assertThat(capture.binding()).isNull();
            rig.key(Key.ESCAPE, false).frame();
            Capture again = rig.input.capture(rig.one(), JUMP).cancelOn(null);
            Capture replacing = rig.input.capture(rig.one(), JUMP); // replaces the first: it is cancelled
            assertThat(again.cancelled()).isTrue();
            rig.events().mouseWheel(0, 1, 0, 0, 2);
            rig.events().mouseMoved(0, 1, 0, 0, 0, 50, 50, 0); // pointer movement is not bound
            rig.frame();
            assertThat(replacing.binding()).isEqualTo(Binding.of(Controls.WHEEL_Y));
            replacing.cancel(); // done already: nothing changes
            assertThat(replacing.cancelled()).isFalse();
        }
    }
}
