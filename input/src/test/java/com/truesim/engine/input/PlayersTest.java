package com.truesim.engine.input;

import static com.truesim.engine.input.Rig.GAME;
import static com.truesim.engine.input.Rig.JUMP;
import static com.truesim.engine.input.Rig.MOVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.truesim.engine.app.Frame;
import com.truesim.engine.platform.GamepadAxis;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.InputDevice;
import com.truesim.engine.platform.Key;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Players, devices, replugging, rumble, glyphs and plugins' kinds of device (D55, D62–D66, D72, D73). */
class PlayersTest {

    /** Logs what input reports. */
    private static final class Log implements InputListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void deviceConnected(Device device, Player player) {
            events.add("connected " + device.platformId() + " to " + player);
        }

        @Override
        public void deviceLost(Device device, Player player) {
            events.add("lost " + device.platformId() + " of " + player);
        }

        @Override
        public void deviceReturned(Device device, Player player) {
            events.add("returned " + device.platformId() + " to " + player);
        }
    }

    @Test
    void aSinglePlayerGetsEveryDevice() { // D63
        try (Rig rig = new Rig(GAME)) {
            rig.plug(1).plug(2).frame();
            assertThat(rig.input.players()).isEqualTo(1);
            rig.pad(2, GamepadButton.SOUTH, true).frame();
            assertThat(rig.one().pressed(JUMP)).isTrue();
            assertThat(rig.one().devices()).hasSize(4); // keyboard, mouse, two pads
            assertThat(rig.input.devices()).hasSize(4);
        }
    }

    @Test
    void unpluggingIsReportedAndReplugReturnsTheDeviceToItsPlayer() { // D73
        try (Rig rig = new Rig(GAME)) {
            Log log = new Log();
            rig.input.listen(log);
            Player two = rig.input.addPlayer();
            rig.plug(1, "guid-a", "", "").plug(2, "guid-a", "", "").frame();
            assertThat(rig.pad(2).player()).isSameAs(two);
            rig.unplug(2).frame();
            assertThat(rig.input.devices()).hasSize(3);
            rig.unplug(1).frame(); // now both players have no pad
            // Plugged back in another order, with new ids: the same GUID alone cannot tell them apart, so the
            // first lost returns first; serials and paths do (below).
            rig.plug(7, "guid-a", "", "").frame();
            assertThat(log.events).containsExactly("connected 1 to player 0", "connected 2 to player 1",
                "lost 2 of player 1", "lost 1 of player 0", "returned 7 to player 1");
            rig.pad(7, GamepadButton.SOUTH, true).frame();
            assertThat(two.pressed(JUMP)).isTrue();
            assertThat(rig.one().pressed(JUMP)).isFalse();
        }
    }

    @Test
    void identicalPadsAreToldApartBySerialThenPath() { // D72
        try (Rig rig = new Rig(GAME)) {
            Log log = new Log();
            rig.input.listen(log);
            Player two = rig.input.addPlayer();
            rig.plug(1, "same", "S1", "").plug(2, "same", "S2", "").plug(3, "same", "", "usb-3").frame();
            two.assign(rig.pad(1));
            rig.one().assign(rig.pad(2));
            rig.unplug(1).unplug(2).unplug(3).frame();
            log.events.clear();
            rig.plug(11, "same", "", "usb-3").plug(12, "same", "S2", "").plug(13, "same", "S1", "").frame();
            // 11 is the pad no one had (by path): a new device again, to the first player without one
            assertThat(log.events).containsExactly("connected 11 to player 0", "returned 12 to player 0",
                "returned 13 to player 1");
        }
    }

    @Test
    void anUnassignedDeviceCanJoinByAPress() { // D63
        try (Rig rig = new Rig(GAME)) {
            Player two = rig.input.addPlayer();
            rig.plug(1).plug(2).plug(3).frame();
            assertThat(rig.pad(3).player()).isNull();
            rig.pad(3, GamepadButton.SOUTH, true).frame();
            assertThat(rig.input.players()).isEqualTo(2); // not joining unless asked
            rig.pad(3, GamepadButton.SOUTH, false).frame();
            rig.input.joinOnPress(true);
            rig.pad(3, GamepadButton.SOUTH, true).frame();
            assertThat(rig.input.players()).isEqualTo(3);
            Player three = rig.input.player(2);
            assertThat(rig.pad(3).player()).isSameAs(three);
            rig.frame();
            assertThat(three.down(JUMP)).isTrue();
            rig.input.removePlayer();
            assertThat(rig.pad(3).player()).isNull();
            rig.input.removePlayer();
            assertThatIllegalStateException().isThrownBy(rig.input::removePlayer);
            assertThat(two.index()).isEqualTo(1);
        }
    }

    @Test
    void glyphsFollowTheLastDeviceUsed() { // D62
        try (Rig rig = new Rig(GAME)) {
            rig.plug(1).frame();
            assertThat(rig.one().lastDevice()).isNull();
            assertThat(rig.one().glyph(JUMP)).isEqualTo(Binding.key(Key.SPACE)); // its first, before any use
            rig.pad(1, GamepadButton.EAST, true).frame();
            assertThat(rig.one().lastDevice()).isSameAs(rig.pad(1));
            assertThat(rig.one().glyph(JUMP)).isEqualTo(Binding.pad(GamepadButton.SOUTH));
            assertThat(rig.one().glyph(MOVE).toString()).startsWith("2d(pad:LEFT_X");
            rig.events().mouseMoved(0, 1, 0, 0, 0, 10, 0, 0);
            rig.frame();
            assertThat(rig.one().lastDevice()).isSameAs(rig.input.mouseDevice());
            assertThat(rig.one().glyph(JUMP)).isEqualTo(Binding.key(Key.SPACE)); // keyboard and mouse are one
            rig.axis(1, GamepadAxis.LEFT_X, 0.2f).frame(); // a small push is not a use
            assertThat(rig.one().lastDevice()).isSameAs(rig.input.mouseDevice());
        }
    }

    /** A plugin's kind of device: two hands with a trigger, a stick and a pose, and rumble. */
    private static final class Hands implements InputSource {
        static final DeviceClass HANDS = DeviceClass.builder("hands", true).add("TRIGGER", ControlKind.HALF_AXIS)
            .add("AIM", ControlKind.POSE).build();
        final List<String> rumbles = new ArrayList<>();
        Device left;
        Device right;
        float trigger;
        boolean tracked = true;
        int samples;
        boolean stopped;

        @Override
        public void start(Input input) {
            input.addDeviceClass(HANDS);
            left = input.connect(HANDS, 1, new DeviceIdentity("hands", "", "left"), "left hand", this);
            right = input.connect(HANDS, 2, new DeviceIdentity("hands", "", "right"), "right hand", this);
        }

        @Override
        public void sample(Frame frame) {
            samples++;
            right.set(0, trigger);
            if (tracked) {
                right.pose(1, 1, 2, 3, 0, 0, 0, 1);
            } else {
                right.lose(1);
            }
        }

        @Override
        public boolean rumble(Device device, float low, float high, int millis) {
            rumbles.add(device.name() + " " + high + " " + millis);
            return true;
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    @Test
    void pluginsBringKindsOfDeviceWithPosesAndRumblePerPlayer() { // D55, D64, D66, D75
        ActionGroup vr = ActionGroup.root("vr");
        BinaryAction grab = vr.binary("grab", Binding.of(Hands.HANDS.control("trigger")));
        PoseAction aim = vr.pose("aim", Binding.of(Hands.HANDS.control("AIM")));
        Hands hands = new Hands();
        Rig rig = new Rig(vr);
        rig.input.addSource(hands);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(
            () -> rig.input.addDeviceClass(DeviceClass.builder("hands", true).build())); // one kind per prefix
        hands.trigger = 0.9f;
        rig.frame();
        assertThat(rig.one().down(grab)).isTrue();
        double[] position = new double[3];
        float[] orientation = new float[4];
        assertThat(rig.one().pose(aim, position, orientation)).isTrue();
        assertThat(position).containsExactly(1, 2, 3);
        assertThat(orientation).containsExactly(0, 0, 0, 1);
        assertThat(hands.right.pose(Hands.HANDS.control("AIM"), position, orientation)).isTrue();
        hands.tracked = false;
        rig.frame();
        assertThat(rig.one().pose(aim, position, orientation)).isFalse();
        assertThat(rig.input.parse("hands:TRIGGER; key:Q")).hasSize(2);
        assertThat(rig.one().rumble(0.2f, 0.7f, 100)).isTrue();
        assertThat(hands.rumbles).contains("left hand 0.7 100", "right hand 0.7 100");
        // With two players, rumble reaches only a player's own devices.
        Player two = rig.input.addPlayer();
        two.assign(hands.right);
        rig.frame();
        hands.rumbles.clear();
        two.rumble(0, 1, 50);
        assertThat(hands.rumbles).containsExactly("right hand 1.0 50");
        assertThat(hands.samples).isEqualTo(3);
        rig.close();
        assertThat(hands.stopped).isTrue();
    }

    @Test
    void joysticksAreDevicesToo() {
        try (Rig rig = new Rig(GAME)) {
            ActionGroup sim = ActionGroup.root("sim");
            Axis1Action pitch = sim.axis1("pitch", Binding.of(Controls.joystickAxis(1)));
            BinaryAction trim = sim.binary("trim", Binding.of(Controls.joystickHat(0, com.truesim.engine.platform
                .HatPosition.UP)), Binding.of(Controls.joystickButton(3)));
            Axis2Action ball = sim.axis2("ball", Binding.axes2d(DeviceClass.JOYSTICK.control("BALL_0_X"),
                DeviceClass.JOYSTICK.control("BALL_0_Y")));
            rig.input.add(sim);
            rig.events().deviceAdded(new InputDevice(5, InputDevice.Kind.JOYSTICK, "stick", 1, 2, "j", "", ""));
            rig.events().deviceAdded(new InputDevice(6, InputDevice.Kind.KEYBOARD, "kb", 0, 0)); // all keyboards: one
            rig.frame();
            rig.events().joystickAxis(0, 5, 1, -0.5f);
            rig.events().joystickHat(0, 5, 0, com.truesim.engine.platform.HatPosition.UP_LEFT);
            rig.events().joystickBall(0, 5, 0, 3, 4);
            rig.events().joystickButton(0, 5, 40, true); // past the buttons input names: ignored
            rig.frame();
            assertThat(rig.one().value(pitch)).isEqualTo(-0.5f);
            assertThat(rig.one().down(trim)).isTrue();
            assertThat(rig.one().x(ball)).isEqualTo(3);
            assertThat(rig.one().y(ball)).isEqualTo(4);
            rig.events().joystickHat(0, 5, 0, com.truesim.engine.platform.HatPosition.CENTERED);
            rig.events().joystickButton(0, 5, 3, true);
            rig.frame();
            assertThat(rig.one().down(trim)).isTrue();
            rig.events().deviceRemoved(InputDevice.Kind.JOYSTICK, 5);
            rig.events().deviceRemoved(InputDevice.Kind.KEYBOARD, 6);
            rig.frame();
            assertThat(rig.one().down(trim)).isFalse();
            assertThat(rig.one().rumble(1, 1, 10)).isFalse(); // no desktop: nothing rumbles
        }
    }
}
