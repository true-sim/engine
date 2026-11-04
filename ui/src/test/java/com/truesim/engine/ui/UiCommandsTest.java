package com.truesim.engine.ui;

import static com.truesim.engine.ui.InputRig.JUMP;
import static com.truesim.engine.ui.InputRig.MOVE;
import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.input.Binding;
import com.truesim.engine.input.TextListener;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.Key;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
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
            assertThat(rig.press(Key.ESCAPE)).as("a gizmo's drag cancels too").containsExactly("CANCEL",
                "GIZMO_CANCEL");
            assertThat(rig.press(Key.TAB)).containsExactly("NEXT");
            assertThat(rig.press(Key.LEFT_SHIFT, Key.TAB)).as("the chord wins over its part")
                .containsExactly("PREVIOUS");
            assertThat(rig.press(Key.LEFT_CONTROL, Key.TAB)).as("Ctrl+Tab is the next tab, not focus")
                .containsExactly("NEXT_TAB");
            assertThat(rig.press(Key.APPLICATION)).containsExactly("CONTEXT_MENU");
            rig.plug(1).pad(1, GamepadButton.SOUTH, true).frame();
            assertThat(rig.commands()).containsExactly("ACCEPT");
            rig.pad(1, GamepadButton.SOUTH, false).pad(1, GamepadButton.EAST, true).frame();
            assertThat(rig.commands()).as("a gizmo drag too").containsExactly("CANCEL", "GIZMO_CANCEL");
            rig.pad(1, GamepadButton.EAST, false).pad(1, GamepadButton.DPAD_UP, true).frame();
            assertThat(rig.commands()).containsExactly("UP");
            rig.pad(1, GamepadButton.DPAD_UP, false).pad(1, GamepadButton.START, true).frame();
            assertThat(rig.commands()).containsExactly("MENU");
            assertThat(UiCommands.ALL).containsExactly(UiCommands.CONFIRM, UiCommands.REPEAT_DELAY,
                UiCommands.REPEAT_RATE);
        }
    }

    @Test
    void everyGizmoSwitchIsAnActionWithItsKeysNotWhileTyping() {
        try (InputRig rig = new InputRig()) {
            rig.frame();
            assertThat(rig.press(Key.B)).containsExactly("GIZMO_BOUNDS");
            assertThat(rig.press(Key.LEFT_SHIFT, Key.X)).as("the chord, not the next space")
                .containsExactly("GIZMO_PREVIOUS_SPACE");
            assertThat(rig.press(Key.PERIOD)).containsExactly("GIZMO_NEXT_PIVOT");
            assertThat(rig.press(Key.LEFT_SHIFT, Key.PERIOD)).containsExactly("GIZMO_PREVIOUS_PIVOT");
            assertThat(rig.input.bindings(rig.input.player(0), UiActions.NEXT_MODE)).as("no key: W, E, R, B, T")
                .isEmpty();
            rig.input.bind(rig.input.player(0), UiActions.NEXT_MODE, Binding.key(Key.M));
            assertThat(rig.press(Key.M)).as("rebound").containsExactly("GIZMO_NEXT_MODE");
            assertThat(rig.input.bindingSetting(UiActions.NEXT_PIVOT).name()).isEqualTo("input.ui.gizmo.nextPivot");
            for (var action : new com.truesim.engine.input.BinaryAction[] {UiActions.BOUNDS, UiActions.NEXT_MODE,
                UiActions.PREVIOUS_SPACE, UiActions.NEXT_PIVOT, UiActions.PREVIOUS_PIVOT}) {
                assertThat(UiCommands.commandOf(action)).as(action.toString()).isNotNull();
            }
            rig.commands.startText(null);
            rig.frame();
            for (Key key : new Key[] {Key.B, Key.PERIOD, Key.M, Key.W}) {
                assertThat(rig.press(key)).as("%s types", key).isEmpty();
            }
            assertThat(rig.press(Key.LEFT_SHIFT, Key.X)).as("types an X").isEmpty();
            assertThat(rig.press(Key.LEFT_SHIFT, Key.PERIOD)).isEmpty();
            rig.commands.stopText();
            rig.frame();
            assertThat(rig.press(Key.PERIOD)).containsExactly("GIZMO_NEXT_PIVOT");
        }
    }

    @Test
    void theDpadSwitchesTheModeOnlyWhileAGizmoIsFocusedAndNavigatesOtherwise() {
        try (InputRig rig = new InputRig()) {
            boolean[] focused = {false};
            rig.commands.gizmo(() -> focused[0]);
            rig.plug(1).frame();
            GamepadButton[] dpad = {GamepadButton.DPAD_UP, GamepadButton.DPAD_RIGHT, GamepadButton.DPAD_DOWN,
                GamepadButton.DPAD_LEFT};
            String[] moves = {"UP", "RIGHT", "DOWN", "LEFT"};
            String[] modes = {"GIZMO_TRANSLATE", "GIZMO_ROTATE", "GIZMO_SCALE", "GIZMO_UNIVERSAL"};
            for (int i = 0; i < 4; i++) {
                rig.pad(1, dpad[i], true).frame();
                assertThat(rig.commands()).as("no gizmo focused: it navigates").containsExactly(moves[i]);
                rig.pad(1, dpad[i], false).frame();
            }
            focused[0] = true;
            for (int i = 0; i < 4; i++) {
                rig.pad(1, dpad[i], true).frame();
                assertThat(rig.commands()).as("a focused gizmo takes it").containsExactly(modes[i]);
                rig.frame(1000);
                rig.frame();
                assertThat(rig.commands()).as("held: no navigation repeats").isEmpty();
                rig.pad(1, dpad[i], false).frame();
            }
            assertThat(rig.press(Key.UP)).as("the arrows still move focus").containsExactly("UP");
            assertThat(rig.press(Key.W)).containsExactly("GIZMO_TRANSLATE");
            focused[0] = false;
            assertThat(rig.press(Key.W)).as("the keys switch it either way").containsExactly("GIZMO_TRANSLATE");
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
            assertThat(rig.commands()).containsExactly("CANCEL", "GIZMO_CANCEL");
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
            assertThat(rig.press(Key.HOME)).containsExactly("HOME", "CHART_RESET");
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
    void tabsChartsGizmosWindowsAndTheConsoleGiveTheirCommands() {
        try (InputRig rig = new InputRig()) {
            rig.frame();
            Key shortcut = UiActions.mac() ? Key.LEFT_META : Key.LEFT_CONTROL;
            assertThat(rig.press(Key.LEFT_CONTROL, Key.LEFT_SHIFT, Key.TAB)).containsExactly("PREVIOUS_TAB");
            assertThat(rig.press(Key.LEFT_CONTROL, Key.PAGE_DOWN)).containsExactly("NEXT_TAB");
            assertThat(rig.press(Key.LEFT_CONTROL, Key.PAGE_UP)).containsExactly("PREVIOUS_TAB");
            assertThat(rig.press(shortcut, Key.W)).as("the chord, not the gizmo's W").containsExactly("CLOSE_TAB");
            assertThat(rig.press(Key.LEFT_CONTROL, Key.F4)).containsExactly("CLOSE_TAB");
            assertThat(rig.press(Key.HOME)).as("the chart under the pointer fits again").containsExactly("HOME",
                "CHART_RESET");
            assertThat(rig.press(Key.W)).containsExactly("GIZMO_TRANSLATE");
            assertThat(rig.press(Key.E)).containsExactly("GIZMO_ROTATE");
            assertThat(rig.press(Key.R)).containsExactly("GIZMO_SCALE");
            assertThat(rig.press(Key.T)).containsExactly("GIZMO_UNIVERSAL");
            assertThat(rig.press(Key.X)).containsExactly("GIZMO_TOGGLE_SPACE");
            assertThat(rig.press(Key.LEFT_CONTROL, Key.F6)).containsExactly("NEXT_WINDOW");
            assertThat(rig.press(Key.LEFT_ALT, Key.F7)).containsExactly("WINDOW_MOVE");
            assertThat(rig.press(Key.LEFT_ALT, Key.F8)).containsExactly("WINDOW_RESIZE");
            assertThat(rig.press(Key.F7)).as("F7 alone is nothing").isEmpty();
            assertThat(rig.press(Key.BACKQUOTE)).containsExactly("CONSOLE_TOGGLE");
            assertThat(rig.press(Key.TAB)).as("no console line: no completion").containsExactly("NEXT");
            assertThat(rig.press(Key.UP)).containsExactly("UP");
            rig.plug(1).pad(1, GamepadButton.LEFT_STICK, true).pad(1, GamepadButton.RIGHT_STICK, true).frame();
            assertThat(rig.commands()).as("both sticks clicked").containsExactly("CONSOLE_TOGGLE");
            rig.pad(1, GamepadButton.LEFT_STICK, false).pad(1, GamepadButton.RIGHT_STICK, false).frame();
            rig.pad(1, GamepadButton.RIGHT_SHOULDER, true).frame();
            assertThat(rig.commands()).containsExactly("NEXT_TAB");
            rig.pad(1, GamepadButton.RIGHT_SHOULDER, false).pad(1, GamepadButton.NORTH, true).frame();
            assertThat(rig.commands()).containsExactly("CHART_RESET");
            rig.pad(1, GamepadButton.NORTH, false).frame();
            assertThat(UiCommands.commandOf(UiActions.WINDOW_MOVE)).isEqualTo(UiCommand.WINDOW_MOVE);
            assertThat(rig.input.bindingSetting(UiActions.WINDOW_RESIZE).name())
                .isEqualTo("input.ui.docking.windowResize");
        }
    }

    @Test
    void whileTypingTabsWindowsAndTheConsoleToggleWorkButNotTheGizmoOrChartKeys() {
        try (InputRig rig = new InputRig()) {
            rig.commands.startText(null);
            rig.frame();
            assertThat(rig.press(Key.LEFT_CONTROL, Key.TAB)).containsExactly("NEXT_TAB");
            assertThat(rig.press(Key.LEFT_CONTROL, Key.F6)).containsExactly("NEXT_WINDOW");
            assertThat(rig.press(Key.LEFT_ALT, Key.F7)).containsExactly("WINDOW_MOVE");
            assertThat(rig.press(Key.BACKQUOTE)).containsExactly("CONSOLE_TOGGLE");
            assertThat(rig.press(Key.W)).as("W types").isEmpty();
            assertThat(rig.press(Key.HOME)).as("the line's start, no chart reset").containsExactly("LINE_START");
            assertThat(rig.press(Key.TAB)).as("not the console's line: focus moves").containsExactly("NEXT");
            assertThat(rig.press(Key.UP)).containsExactly("UP");
        }
    }

    @Test
    void theConsolesCommandLineCompletesAndWalksItsHistoryInPlaceOfMovingFocus() {
        try (InputRig rig = new InputRig()) {
            boolean[] line = {true};
            rig.commands.console(() -> line[0]);
            assertThat(rig.press(Key.TAB)).as("no text taken: the console's keys are not the line's")
                .containsExactly("NEXT");
            rig.commands.startText(null);
            rig.frame();
            assertThat(rig.press(Key.TAB)).containsExactly("COMPLETE");
            assertThat(rig.press(Key.UP)).containsExactly("HISTORY_UP");
            assertThat(rig.press(Key.DOWN)).containsExactly("HISTORY_DOWN");
            assertThat(rig.press(Key.LEFT_SHIFT, Key.TAB)).containsExactly("PREVIOUS");
            assertThat(rig.press(Key.BACKQUOTE)).containsExactly("CONSOLE_TOGGLE");
            // Held, the history repeats as the navigation does.
            rig.engine.settings().set(UiCommands.REPEAT_DELAY, 0.3f).set(UiCommands.REPEAT_RATE, 10f);
            rig.key(Key.UP, true);
            List<String> held = new ArrayList<>();
            for (int f = 0; f < 45; f++) {
                rig.frame(10);
                held.addAll(rig.commands());
            }
            rig.key(Key.UP, false).frame();
            assertThat(held).containsExactly("HISTORY_UP", "HISTORY_UP", "HISTORY_UP");
            line[0] = false;
            assertThat(rig.press(Key.TAB)).as("another field").containsExactly("NEXT");
            assertThat(rig.press(Key.UP)).containsExactly("UP");
        }
    }

    @Test
    void theGizmosSnapHeldIsTheMousePointersModifierWithTheKeysHeld() {
        try (InputRig rig = new InputRig()) {
            UiKeyboard keyboard = new UiKeyboard(rig.input, true);
            rig.keyListeners.add(keyboard);
            MousePointer mouse = new MousePointer().modifiers(keyboard::pointerModifiers);
            mouse.mouseMoved(0, 1, 0, 10, 20, 0, 0, 0);
            rig.frame();
            mouse.poll(rig.ui);
            assertThat(rig.ui.pointerAt(0).modifiers()).isZero();
            rig.key(Key.LEFT_CONTROL, true).key(Key.LEFT_SHIFT, true).frame();
            mouse.poll(rig.ui);
            int expected = Pointer.CTRL | Pointer.SHIFT | Pointer.GIZMO_SNAP | Pointer.DRAG_COPY
                | (UiActions.mac() ? 0 : Pointer.SHORTCUT);
            assertThat(rig.ui.pointerAt(0).modifiers()).as("snap and copy are Ctrl's actions").isEqualTo(expected);
            rig.key(Key.LEFT_CONTROL, false).key(Key.LEFT_SHIFT, false).key(Key.RIGHT_ALT, true).frame();
            mouse.poll(rig.ui);
            assertThat(rig.ui.pointerAt(0).modifiers()).isEqualTo(Pointer.ALT | Pointer.DRAG_LINK);
            keyboard.windowFocusChanged(1, false);
            assertThat(keyboard.pointerModifiers() & Pointer.ALT).as("forgotten when the focus leaves").isZero();
            // Rebound, the snap follows its action, not Ctrl.
            rig.key(Key.RIGHT_ALT, false);
            rig.input.bind(rig.input.player(0), UiActions.SNAP, Binding.key(Key.Q));
            rig.key(Key.Q, true).frame();
            mouse.poll(rig.ui);
            assertThat(rig.ui.pointerAt(0).modifiers()).isEqualTo(Pointer.GIZMO_SNAP);
        }
    }

    @Test
    void altWithALetterIsAMnemonicThatTypesNothingAndAltAloneShowsThem() {
        try (InputRig rig = new InputRig()) {
            UiKeyboard keyboard = new UiKeyboard(rig.input, true);
            rig.keyListeners.add(keyboard);
            UiTextInput text = new UiTextInput(rig.commands, null).keyboard(keyboard);
            text.start(0, 0, 100, 20);
            rig.frame();
            rig.key(Key.LEFT_ALT, true);
            rig.ui.beginFrame();
            keyboard.poll(rig.ui);
            assertThat(rig.ui.mnemonicsShown()).isTrue();
            assertThat(rig.ui.mnemonic()).isZero();
            rig.key(Key.F, true);
            rig.events().text(1, "f"); // a layout that types with Alt
            rig.key(Key.F, false);
            rig.ui.beginFrame();
            keyboard.poll(rig.ui);
            text.poll(rig.ui);
            assertThat(rig.ui.mnemonic()).isEqualTo('f');
            assertThat(rig.ui.text().toString()).as("the mnemonic's letter is not typed").isEmpty();
            rig.ui.beginFrame();
            keyboard.poll(rig.ui);
            assertThat(rig.ui.mnemonic()).as("once").isZero();
            rig.key(Key.LEFT_CONTROL, true).key(Key.DIGIT_2, true).key(Key.DIGIT_2, false);
            rig.ui.beginFrame();
            keyboard.poll(rig.ui);
            assertThat(rig.ui.mnemonic()).as("Ctrl+Alt is AltGr: it types").isZero();
            assertThat(rig.ui.mnemonicsShown()).isFalse();
            rig.key(Key.LEFT_CONTROL, false).key(Key.DIGIT_2, true).key(Key.DIGIT_2, false);
            rig.ui.beginFrame();
            keyboard.poll(rig.ui);
            assertThat(rig.ui.mnemonic()).isEqualTo('2');
            rig.key(Key.LEFT_ALT, false).key(Key.G, true);
            rig.events().text(1, "g");
            rig.key(Key.G, false);
            rig.ui.beginFrame();
            keyboard.poll(rig.ui);
            text.poll(rig.ui);
            assertThat(rig.ui.mnemonic()).isZero();
            assertThat(rig.ui.mnemonicsShown()).isFalse();
            assertThat(rig.ui.text().toString()).isEqualTo("g");
            UiKeyboard off = new UiKeyboard(null, false); // as on macOS
            off.key(0, 1, 0, Key.LEFT_ALT, 0, 0, true, false);
            off.key(0, 1, 0, Key.F, 'f', 0, true, false);
            UiInput ui = new UiInput(4);
            off.poll(ui);
            assertThat(ui.mnemonic()).isZero();
            assertThat(ui.mnemonicsShown()).isFalse();
            text.stop();
        }
    }

    @Test
    void theConsoleTogglesKeyTypesNoGraveIntoTheFieldItTogglesOver() {
        try (InputRig rig = new InputRig()) {
            UiKeyboard keyboard = new UiKeyboard(rig.input, true);
            rig.keyListeners.add(keyboard);
            UiTextInput text = new UiTextInput(rig.commands, null).keyboard(keyboard);
            text.start(0, 0, 100, 20);
            rig.frame();
            rig.key(Key.A, true);
            rig.events().text(1, "a");
            rig.key(Key.A, false).key(Key.BACKQUOTE, true);
            rig.events().text(1, "`");
            rig.key(Key.B, true);
            rig.events().text(1, "b");
            rig.frame(); // polls the commands into rig.ui
            keyboard.poll(rig.ui);
            text.poll(rig.ui);
            assertThat(rig.commands()).containsExactly("CONSOLE_TOGGLE");
            assertThat(rig.ui.text().toString()).isEqualTo("ab");
            rig.key(Key.BACKQUOTE, false).key(Key.B, false).frame();
            // Rebound to F1, the grave types again.
            rig.engine.settings().set(rig.input.bindingSetting(UiActions.TOGGLE_CONSOLE), "key:F1");
            rig.frame();
            rig.key(Key.BACKQUOTE, true);
            rig.events().text(1, "`");
            rig.frame();
            keyboard.poll(rig.ui);
            text.poll(rig.ui);
            assertThat(rig.commands()).isEmpty();
            assertThat(rig.ui.text().toString()).isEqualTo("`");
            text.stop();
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
