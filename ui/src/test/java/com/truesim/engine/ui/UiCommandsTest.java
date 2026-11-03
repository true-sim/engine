package com.truesim.engine.ui;

import static com.truesim.engine.ui.InputRig.JUMP;
import static com.truesim.engine.ui.InputRig.MOVE;
import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.input.Binding;
import com.truesim.engine.input.TextListener;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.Key;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The ui action group's defaults, and its edges as UI commands: rebinding, text, switches, repeats, allocation. */
class UiCommandsTest {

    @Test
    void theDefaultsResolveForKeyboardAndGamepad() {
        try (InputRig rig = new InputRig()) {
            rig.frame();
            assertThat(rig.input.bindings(rig.input.player(0), UiActions.ACCEPT)).containsExactly(
                Binding.key(Key.ENTER), Binding.key(Key.SPACE), Binding.pad(GamepadButton.SOUTH));
            assertThat(rig.input.bindingSetting(UiActions.ACCEPT).name()).isEqualTo("input.ui.navigation.accept");
            assertThat(rig.input.has(UiActions.TOGGLE_CONSOLE)).as("every sub-group added").isTrue();
            assertThat(rig.press(Key.DOWN)).containsExactly("DOWN");
            assertThat(rig.press(Key.ENTER)).containsExactly("ACCEPT");
            assertThat(rig.press(Key.ESCAPE)).containsExactly("CANCEL");
            assertThat(rig.press(Key.TAB)).containsExactly("NEXT");
            assertThat(rig.press(Key.LEFT_SHIFT, Key.TAB)).as("the chord wins over its part")
                .containsExactly("PREVIOUS");
            assertThat(rig.press(Key.LEFT_CONTROL, Key.TAB)).as("Ctrl+Tab is the next tab, not focus").isEmpty();
            assertThat(rig.press(Key.APPLICATION)).containsExactly("CONTEXT_MENU");
            rig.plug(1).pad(1, GamepadButton.SOUTH, true).frame();
            assertThat(rig.commands()).containsExactly("ACCEPT");
            rig.pad(1, GamepadButton.SOUTH, false).pad(1, GamepadButton.EAST, true).frame();
            assertThat(rig.commands()).containsExactly("CANCEL");
            rig.pad(1, GamepadButton.EAST, false).pad(1, GamepadButton.DPAD_UP, true).frame();
            assertThat(rig.commands()).containsExactly("UP");
            rig.pad(1, GamepadButton.DPAD_UP, false).pad(1, GamepadButton.START, true).frame();
            assertThat(rig.commands()).containsExactly("MENU");
            assertThat(UiCommands.ALL).containsExactly(UiCommands.CONFIRM, UiCommands.REPEAT_DELAY,
                UiCommands.REPEAT_RATE);
        }
    }

    @Test
    void rebindingAcceptThroughItsSettingChangesTheKey() {
        try (InputRig rig = new InputRig()) {
            rig.frame();
            rig.engine.settings().set(rig.input.bindingSetting(UiActions.ACCEPT), "key:F");
            assertThat(rig.press(Key.ENTER)).isEmpty();
            assertThat(rig.press(Key.F)).containsExactly("ACCEPT");
            assertThat(rig.engine.settings().toMap()).containsEntry("input.ui.navigation.accept", "key:F");
        }
    }

    @Test
    void theConfirmButtonFollowsItsSetting() {
        try (InputRig rig = new InputRig()) {
            rig.plug(1).frame();
            assertThat(UiCommands.acceptButton(UiCommands.Confirm.PLATFORM)).as("the desktop's convention")
                .isEqualTo(GamepadButton.SOUTH);
            rig.engine.settings().set(UiCommands.CONFIRM, UiCommands.Confirm.EAST);
            rig.pad(1, GamepadButton.EAST, true).frame();
            assertThat(rig.commands()).containsExactly("ACCEPT");
            rig.pad(1, GamepadButton.EAST, false).pad(1, GamepadButton.SOUTH, true).frame();
            assertThat(rig.commands()).containsExactly("CANCEL");
            rig.pad(1, GamepadButton.SOUTH, false);
            assertThat(rig.press(Key.ENTER)).as("the keys stay").containsExactly("ACCEPT");
            // A player's own binding stays over the convention.
            rig.input.bind(rig.input.player(0), UiActions.ACCEPT, Binding.key(Key.G));
            rig.engine.settings().set(UiCommands.CONFIRM, UiCommands.Confirm.SOUTH);
            rig.pad(1, GamepadButton.SOUTH, true);
            assertThat(rig.press(Key.G)).as("the player's G, not the pad's south").containsExactly("ACCEPT");
            rig.input.resetBindings(rig.input.player(0), UiActions.ACCEPT);
            rig.pad(1, GamepadButton.SOUTH, false).frame();
            rig.pad(1, GamepadButton.SOUTH, true).frame();
            assertThat(rig.commands()).as("south again").containsExactly("ACCEPT");
        }
    }

    @Test
    void aTextFieldTakesTheKeysFromTheGameAndGetsTextCommands() {
        try (InputRig rig = new InputRig()) {
            rig.key(Key.W, true).frame();
            assertThat(rig.input.player(0).y(MOVE)).isEqualTo(1);
            rig.key(Key.W, false);
            assertThat(rig.press(Key.LEFT_CONTROL, Key.A)).as("no text field: no text commands").isEmpty();
            List<String> typed = new ArrayList<>();
            rig.commands.startText(new TextListener() {
                @Override
                public void text(String text) {
                    typed.add(text);
                }

                @Override
                public void editing(String text, int start, int length) {
                }
            });
            rig.frame();
            assertThat(rig.input.textContext()).isSameAs(rig.commands.textContext());
            rig.key(Key.W, true).key(Key.SPACE, true).frame();
            assertThat(rig.input.player(0).y(MOVE)).as("W types").isZero();
            assertThat(rig.input.player(0).down(JUMP)).as("Space types").isFalse();
            assertThat(rig.commands()).as("Space types: no accept").isEmpty();
            rig.key(Key.W, false).key(Key.SPACE, false).frame();
            assertThat(rig.press(Key.LEFT_CONTROL, Key.A)).containsExactly("SELECT_ALL");
            assertThat(rig.press(Key.LEFT_SHIFT, Key.LEFT)).containsExactly("LEFT extend");
            assertThat(rig.press(Key.LEFT_SHIFT, Key.HOME)).as("Home is the line start in a field")
                .containsExactly("LINE_START extend");
            assertThat(rig.press(Key.LEFT_CONTROL, Key.LEFT)).as("a word, not a character")
                .containsExactly("WORD_LEFT");
            assertThat(rig.press(Key.ENTER)).containsExactly("SUBMIT");
            assertThat(rig.press(Key.LEFT_SHIFT, Key.ENTER)).containsExactly("NEWLINE");
            assertThat(rig.press(Key.LEFT_CONTROL, Key.BACKSPACE)).containsExactly("DELETE_WORD_BACK");
            assertThat(rig.press(Key.BACKSPACE, Key.DELETE)).containsExactly("DELETE_BACK", "DELETE_FORWARD");
            assertThat(rig.press(Key.LEFT_CONTROL, Key.LEFT_SHIFT, Key.Z)).containsExactly("REDO");
            rig.events().text(1, "w");
            assertThat(typed).containsExactly("w");
            rig.commands.stopText();
            rig.frame();
            assertThat(rig.input.textContext()).isNull();
            assertThat(rig.press(Key.LEFT_CONTROL, Key.A)).isEmpty();
            assertThat(rig.press(Key.HOME)).containsExactly("HOME");
            rig.key(Key.W, true).frame();
            assertThat(rig.input.player(0).y(MOVE)).as("W walks again").isEqualTo(1);
        }
    }

    @Test
    void aGameSwitchesTheGizmoGroupOffInOneCall() {
        try (InputRig rig = new InputRig()) {
            rig.key(Key.E, true).frame();
            assertThat(rig.input.player(0).down(UiActions.ROTATE)).isTrue();
            rig.input.enable(UiActions.GIZMO, false);
            rig.frame();
            assertThat(rig.input.player(0).down(UiActions.ROTATE)).isFalse();
            assertThat(rig.input.active(UiActions.NAVIGATION)).as("the rest of the UI stays").isTrue();
            rig.key(Key.E, false).frame();
        }
    }

    @Test
    void heldMovesRepeatAfterTheDelayAtTheRate() {
        try (InputRig rig = new InputRig()) {
            rig.engine.settings().set(UiCommands.REPEAT_DELAY, 0.3f).set(UiCommands.REPEAT_RATE, 10f);
            rig.frame(10);
            int[] downs = new int[60];
            rig.key(Key.DOWN, true);
            for (int f = 0; f < downs.length; f++) {
                rig.frame(10); // frames 10 ms apart: the press at 10 ms
                downs[f] = rig.ui.commandCount();
            }
            int total = java.util.Arrays.stream(downs).sum();
            assertThat(downs[0]).as("the press").isEqualTo(1);
            assertThat(java.util.Arrays.stream(downs, 1, 30).sum()).as("nothing before the delay").isZero();
            // The press at 10 ms; repeats at 310, 410, 510 ms: frames 30, 40, 50 (or the next, by rounding).
            assertThat(downs[30] + downs[31]).isEqualTo(1);
            assertThat(downs[40] + downs[41]).isEqualTo(1);
            assertThat(downs[50] + downs[51]).isEqualTo(1);
            assertThat(total).isEqualTo(4);
            rig.key(Key.DOWN, false).frame(10);
            rig.frame(500);
            rig.frame(10);
            assertThat(rig.ui.commandCount()).as("released: no more").isZero();
        }
    }

    @Test
    void pollingAllocatesNothing() {
        try (InputRig rig = new InputRig()) {
            rig.plug(1).key(Key.DOWN, true).frame();
            rig.commands.startText(null);
            rig.frame();
            rig.key(Key.LEFT_SHIFT, true).frame();
            long[] t = {rig.now};
            assertAllocationFree(() -> {
                rig.ui.beginFrame();
                rig.now += 16 * InputRig.MS; // the held key repeats
                rig.commands.poll(rig.ui);
            });
            assertThat(rig.now).isGreaterThan(t[0]);
        }
    }
}
