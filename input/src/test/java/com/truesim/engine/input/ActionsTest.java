package com.truesim.engine.input;

import static com.truesim.engine.input.Rig.BOOST;
import static com.truesim.engine.input.Rig.CROUCH;
import static com.truesim.engine.input.Rig.FIRE;
import static com.truesim.engine.input.Rig.GAME;
import static com.truesim.engine.input.Rig.JUMP;
import static com.truesim.engine.input.Rig.MOVE;
import static com.truesim.engine.input.Rig.SAVE;
import static com.truesim.engine.input.Rig.SAVE_ALL;
import static com.truesim.engine.input.Rig.STEER;
import static com.truesim.engine.input.Rig.THROTTLE;
import static com.truesim.engine.input.Rig.VEHICLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.platform.GamepadAxis;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.Key;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Actions from bindings: sampling, chords, modifiers, thresholds, timing, toggles, sources, combining (D57–D87). */
class ActionsTest {

    @Test
    void aPressIsReadInTheFrameItWasSampledIn() { // D59, D70: no latency, a press fires at once
        try (Rig rig = new Rig(GAME)) {
            List<String> seen = new ArrayList<>();
            rig.onUpdate = frame -> seen.add(frame.number() + " " + rig.one().pressed(JUMP) + " " + rig.one().down(JUMP));
            rig.frame();
            rig.key(Key.SPACE, true).frame();
            rig.frame();
            rig.key(Key.SPACE, false).frame();
            assertThat(seen).containsExactly("0 false false", "1 true true", "2 false true", "3 false false");
            assertThat(rig.one().released(JUMP)).isTrue();
        }
    }

    @Test
    void aPressAndReleaseWithinAFrameIsStillAPress() {
        try (Rig rig = new Rig(GAME)) {
            rig.frame();
            rig.tap(Key.SPACE).frame();
            assertThat(rig.one().pressed(JUMP)).isTrue();
            assertThat(rig.one().down(JUMP)).isTrue();
            rig.frame();
            assertThat(rig.one().released(JUMP)).isTrue();
            assertThat(rig.one().down(JUMP)).isFalse();
        }
    }

    @Test
    void buttonsMakeAxesThroughAdapters() { // D81
        try (Rig rig = new Rig(GAME)) {
            rig.key(Key.W, true).key(Key.D, true).frame();
            assertThat(rig.one().x(MOVE)).isEqualTo(1);
            assertThat(rig.one().y(MOVE)).isEqualTo(1);
            rig.key(Key.W, false).key(Key.S, true).key(Key.A, true).frame();
            assertThat(rig.one().value(MOVE, new float[2])).containsExactly(0, -1);
            rig.key(Key.RIGHT, true).frame();
            assertThat(rig.one().value(STEER)).isEqualTo(1);
        }
    }

    @Test
    void aChordSuppressesItsPartsAndTheMostSpecificWins() { // D69
        try (Rig rig = new Rig(GAME)) {
            rig.key(Key.LEFT_CONTROL, true).frame();
            assertThat(rig.one().down(CROUCH)).isTrue(); // Ctrl alone: crouch, at once
            rig.key(Key.S, true).frame();
            assertThat(rig.one().pressed(SAVE)).isTrue();
            assertThat(rig.one().y(MOVE)).isZero();        // S is the chord's: no walking back
            assertThat(rig.one().down(CROUCH)).isFalse();  // nor Ctrl crouching
            rig.key(Key.LEFT_SHIFT, true).frame();
            assertThat(rig.one().pressed(SAVE_ALL)).isTrue();
            assertThat(rig.one().down(SAVE)).isFalse();    // the more specific chord takes S and Ctrl from it
            assertThat(rig.one().released(SAVE)).isTrue();
            rig.key(Key.LEFT_SHIFT, false).key(Key.LEFT_CONTROL, false).frame();
            assertThat(rig.one().y(MOVE)).isEqualTo(-1);   // S alone again
            assertThat(rig.one().down(SAVE)).isFalse();
        }
    }

    @Test
    void aModifierOnAnAxisGatesItAndTakesNothing() {
        ActionGroup group = ActionGroup.root("looking");
        BinaryAction held = group.binary("held", Binding.mouse(com.truesim.engine.platform.MouseButton.RIGHT));
        Axis2Action look = group.axis2("look", Binding.axes2d(Controls.MOUSE_X, Controls.MOUSE_Y)
            .with(Controls.mouse(com.truesim.engine.platform.MouseButton.RIGHT)));
        try (Rig rig = new Rig(group)) {
            rig.events().mouseMoved(0, 1, 0, 0, 0, 5, 0, 0);
            rig.frame();
            assertThat(rig.one().x(look)).isZero(); // not held
            rig.events().mouseButton(0, 1, 0, com.truesim.engine.platform.MouseButton.RIGHT, true, 1, 0, 0);
            rig.events().mouseMoved(0, 1, 0, 0, 0, 5, 0, 0);
            rig.frame();
            assertThat(rig.one().x(look)).isEqualTo(5);
            assertThat(rig.one().down(held)).isTrue();
        }
    }

    @Test
    void modifiersAreLeftRightOrEitherAndAnyPadButtonCanBeOne() { // D71
        ActionGroup group = ActionGroup.root("mods");
        BinaryAction left = group.binary("left", Binding.chord(Controls.key(Key.LEFT_ALT), Controls.key(Key.X)));
        BinaryAction either = group.binary("either", Binding.chord(Controls.ALT, Controls.key(Key.Y)));
        BinaryAction padChord = group.binary("padChord",
            Binding.chord(Controls.pad(GamepadButton.LEFT_SHOULDER), Controls.pad(GamepadButton.NORTH)));
        BinaryAction north = group.binary("north", Binding.pad(GamepadButton.NORTH));
        try (Rig rig = new Rig(group)) {
            rig.plug(1).key(Key.RIGHT_ALT, true).key(Key.X, true).key(Key.Y, true).frame();
            assertThat(rig.one().down(left)).isFalse();
            assertThat(rig.one().down(either)).isTrue();
            rig.key(Key.RIGHT_ALT, false).key(Key.LEFT_ALT, true).frame();
            assertThat(rig.one().down(left)).isTrue();
            assertThat(rig.one().down(either)).isTrue();
            rig.pad(1, GamepadButton.NORTH, true).frame();
            assertThat(rig.one().down(north)).isTrue();
            assertThat(rig.one().down(padChord)).isFalse();
            rig.pad(1, GamepadButton.LEFT_SHOULDER, true).frame();
            assertThat(rig.one().down(padChord)).isTrue();
            assertThat(rig.one().down(north)).isFalse(); // the chord takes North
        }
    }

    @Test
    void analogTurnsBinaryAtAThresholdWithHysteresis() { // D77
        try (Rig rig = new Rig(GAME)) {
            rig.plug(1).frame();
            float[] steps = {0.5f, 0.65f, 0.5f, 0.41f, 0.35f, 0.55f, 0.6f};
            boolean[] down = {false, true, true, true, false, false, true};
            for (int i = 0; i < steps.length; i++) {
                rig.axis(1, GamepadAxis.RIGHT_TRIGGER, steps[i]).frame();
                assertThat(rig.one().down(FIRE)).as("at %s", steps[i]).isEqualTo(down[i]);
                assertThat(rig.one().value(THROTTLE)).isEqualTo(steps[i]); // the analog action keeps its value
            }
            // The thresholds are settings (tunables): read when they change.
            rig.engine.settings().set(InputSettings.PRESS_THRESHOLD, 0.9f);
            rig.axis(1, GamepadAxis.RIGHT_TRIGGER, 0).frame().axis(1, GamepadAxis.RIGHT_TRIGGER, 0.8f).frame();
            assertThat(rig.one().down(FIRE)).isFalse();
            rig.engine.settings().set(InputSettings.RELEASE_THRESHOLD, 0.95f); // above the press: taken as the press
            rig.axis(1, GamepadAxis.RIGHT_TRIGGER, 0.92f).frame();
            assertThat(rig.one().down(FIRE)).isTrue();
            rig.axis(1, GamepadAxis.RIGHT_TRIGGER, 0.89f).frame();
            assertThat(rig.one().down(FIRE)).isFalse();
        }
    }

    @Test
    void binaryActionsHaveNoAnalogValue() { // D76
        for (Method method : Player.class.getMethods()) {
            if (method.getName().equals("value") || method.getName().equals("x")) {
                assertThat(method.getParameterTypes()[0]).isNotEqualTo(BinaryAction.class);
            }
        }
    }

    @Test
    void tapAndHoldWaitOnlyWhereMarked() { // D70
        ActionGroup group = ActionGroup.root("timing");
        BinaryAction use = group.binary("use", Binding.key(Key.E).tap());
        BinaryAction menu = group.binary("menu", Binding.key(Key.E).hold());
        BinaryAction slow = group.binary("slow", Binding.key(Key.R).hold().holdTime(0.45f));
        BinaryAction now = group.binary("now", Binding.key(Key.E));
        try (Rig rig = new Rig(group)) {
            rig.frame(100);
            rig.key(Key.E, true).frame(100); // t = 0.1: down
            assertThat(rig.one().down(now)).isTrue();
            assertThat(rig.one().down(use)).isFalse();
            assertThat(rig.one().down(menu)).isFalse();
            rig.key(Key.E, false).frame(100); // released after 0.1 s: a tap
            assertThat(rig.one().pressed(use)).isTrue();
            assertThat(rig.one().pressed(menu)).isFalse();
            rig.frame(100);
            assertThat(rig.one().released(use)).isTrue();
            // Held: the hold fires once 0.25 s pass, and no tap on release.
            rig.key(Key.E, true).frame(100);
            rig.frame(100);
            assertThat(rig.one().down(menu)).isFalse(); // 0.1 s
            rig.frame(100);
            assertThat(rig.one().down(menu)).isFalse(); // 0.2 s
            rig.frame(100);
            assertThat(rig.one().pressed(menu)).isTrue(); // 0.3 s
            rig.key(Key.E, false).frame(100);
            assertThat(rig.one().released(menu)).isTrue();
            assertThat(rig.one().pressed(use)).isFalse();
            // A binding's own hold time, and the setting.
            rig.key(Key.R, true).frame(400);
            rig.frame(100);
            assertThat(rig.one().down(slow)).isFalse(); // 0.4 s of 0.45
            rig.frame(100);
            assertThat(rig.one().down(slow)).isTrue();
            rig.engine.settings().set(InputSettings.HOLD_TIME, 1f);
            rig.key(Key.R, false).key(Key.E, true).frame(500).frame(100);
            assertThat(rig.one().down(menu)).isFalse(); // 0.5 s of 1
            rig.key(Key.E, false).frame();
            assertThat(rig.one().pressed(use)).isTrue(); // 0.6 s: still a tap
        }
    }

    @Test
    void toggleOrHoldIsTheBindingsChoice() { // D79
        ActionGroup group = ActionGroup.root("toggles");
        BinaryAction sneak = group.binary("sneak", Binding.key(Key.C).toggle(), Binding.pad(GamepadButton.LEFT_STICK));
        try (Rig rig = new Rig(group)) {
            rig.plug(1).tap(Key.C).frame().frame();
            assertThat(rig.one().down(sneak)).isTrue();
            rig.frame();
            assertThat(rig.one().down(sneak)).isTrue();
            rig.tap(Key.C).frame();
            assertThat(rig.one().released(sneak)).isTrue();
            rig.pad(1, GamepadButton.LEFT_STICK, true).frame(); // the pad's binding holds
            assertThat(rig.one().down(sneak)).isTrue();
            rig.pad(1, GamepadButton.LEFT_STICK, false).frame();
            assertThat(rig.one().down(sneak)).isFalse();
        }
    }

    @Test
    void anActionCanBeAnothersSourceButNotInACycle() { // D80
        ActionGroup group = ActionGroup.root("sources");
        Axis1Action gas = group.axis1("gas", Binding.pad(GamepadAxis.RIGHT_TRIGGER));
        BinaryAction accelerating = group.binary("accelerating", Binding.action(gas));
        Axis1Action loop = group.axis1("loop");
        try (Rig rig = new Rig(group)) {
            rig.plug(1).axis(1, GamepadAxis.RIGHT_TRIGGER, 0.8f).frame();
            assertThat(rig.one().down(accelerating)).isTrue(); // past the threshold, the same frame
            assertThatIllegalArgumentException().isThrownBy(
                () -> rig.input.bind(rig.one(), gas, Binding.action(accelerating))).withMessageContaining("cycle");
            assertThatIllegalArgumentException().isThrownBy(
                () -> rig.input.bind(rig.one(), loop, Binding.action(loop))).withMessageContaining("cycle");
            // A cycle in saved text is cut and reported.
            rig.engine.settings().set(rig.input.bindingSetting(gas), "action(sources.accelerating)");
            rig.frame();
            assertThat(rig.input.problems()).anyMatch(p -> p.contains("makes a cycle"));
            assertThat(rig.one().value(gas)).isZero();
        }
    }

    @Test
    void bindingsCombineByTheLargestValueOrTheActionsRule() { // D87
        ActionGroup group = ActionGroup.root("combine");
        Axis1Action largest = group.axis1("largest", Binding.axis(Controls.key(Key.A), Controls.key(Key.D)),
            Binding.pad(GamepadAxis.LEFT_X));
        Axis1Action summed = group.axis1("summed", Binding.axis(Controls.key(Key.A), Controls.key(Key.D)),
            Binding.pad(GamepadAxis.LEFT_X)).combine(Combine.SUM);
        Axis1Action first = group.axis1("first", Binding.pad(GamepadAxis.LEFT_X),
            Binding.axis(Controls.key(Key.A), Controls.key(Key.D))).combine(Combine.FIRST);
        try (Rig rig = new Rig(group)) {
            rig.plug(1).axis(1, GamepadAxis.LEFT_X, -0.5f).key(Key.D, true).frame();
            assertThat(rig.one().value(largest)).isEqualTo(1);
            assertThat(rig.one().value(summed)).isEqualTo(0.5f);
            assertThat(rig.one().value(first)).isEqualTo(-0.5f);
            assertThat(largest.combine()).isEqualTo(Combine.LARGEST);
        }
    }

    @Test
    void groupsFormATreeActiveOnlyIfAllParentsAreOn() { // D57, D67, D68
        try (Rig rig = new Rig(GAME)) {
            rig.key(Key.LEFT_SHIFT, true).key(Key.SPACE, true).frame();
            assertThat(rig.one().down(BOOST)).isTrue();
            rig.input.enable(GAME, false);
            rig.frame();
            assertThat(rig.input.active(VEHICLE)).isFalse();
            assertThat(rig.input.enabled(VEHICLE)).isTrue();
            assertThat(rig.one().released(BOOST)).isTrue(); // switched off while held: released, no stale state
            assertThat(rig.one().down(JUMP)).isFalse();
            rig.input.enable(VEHICLE, false).enable(GAME, true);
            rig.frame();
            assertThat(rig.one().down(JUMP)).isTrue();
            assertThat(rig.one().down(BOOST)).isFalse(); // its own switch is still off
            rig.input.enable(VEHICLE, true);
            rig.frame();
            assertThat(rig.one().down(BOOST)).isTrue();
        }
    }

    @Test
    void devicesCanBeReadDirectly() { // D56
        try (Rig rig = new Rig(GAME)) {
            rig.key(Key.F3, true).events().mouseMoved(0, 1, 0, 5, 5, 4, -2, 0);
            rig.frame();
            assertThat(rig.input.keyboard().down(Controls.key(Key.F3))).isTrue();
            assertThat(rig.input.mouseDevice().value(Controls.MOUSE_X)).isEqualTo(4);
            assertThat(rig.input.mouseDevice().value(Controls.key(Key.F3))).isZero(); // not a mouse control
            rig.frame();
            assertThat(rig.input.mouseDevice().value(Controls.MOUSE_X)).isZero(); // movement is per frame
        }
    }

    @Test
    void aStickIsUpPositiveWithARadialDeadZone() {
        try (Rig rig = new Rig(GAME)) {
            rig.plug(1).axis(1, GamepadAxis.LEFT_X, 0.1f).axis(1, GamepadAxis.LEFT_Y, -0.05f).frame();
            assertThat(rig.one().x(MOVE)).isZero();
            rig.axis(1, GamepadAxis.LEFT_X, 0).axis(1, GamepadAxis.LEFT_Y, -1).frame();
            assertThat(rig.one().y(MOVE)).isCloseTo(1, within(1e-6f));
        }
    }
}
