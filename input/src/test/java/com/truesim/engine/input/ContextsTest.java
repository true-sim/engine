package com.truesim.engine.input;

import static com.truesim.engine.input.Rig.BACK;
import static com.truesim.engine.input.Rig.CONFIRM;
import static com.truesim.engine.input.Rig.GAME;
import static com.truesim.engine.input.Rig.JUMP;
import static com.truesim.engine.input.Rig.MOVE;
import static com.truesim.engine.input.Rig.PAUSE;
import static com.truesim.engine.input.Rig.UI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.engine.platform.GamepadAxis;
import com.truesim.engine.platform.Key;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contexts: a stack with priority, the mouse captured or released, text fields taking keys (D61); resets (D86). */
class ContextsTest {

    @Test
    void aHigherContextTakesTheControlsItBinds() {
        try (Rig rig = new Rig(GAME, UI)) {
            InputContext menu = rig.input.context("menu", 100).groups(UI);
            assertThat(menu.active()).isFalse();
            rig.key(Key.ESCAPE, true).frame();
            assertThat(rig.one().down(PAUSE)).isTrue(); // UI is in no context: read under all, both see Escape
            assertThat(rig.one().down(BACK)).isTrue();
            rig.input.push(menu);
            rig.key(Key.W, true).frame();
            assertThat(menu.active()).isTrue();
            assertThat(rig.one().down(BACK)).isTrue();
            assertThat(rig.one().released(PAUSE)).isTrue(); // the menu's Escape is not the game's
            assertThat(rig.one().y(MOVE)).isEqualTo(1);     // keys the menu does not bind still reach the game
            rig.input.pop(menu);
            rig.frame();
            assertThat(rig.one().down(PAUSE)).isTrue();
        }
    }

    @Test
    void aBlockingContextHidesThoseUnderItAndPriorityOrdersThem() {
        try (Rig rig = new Rig(GAME, UI)) {
            InputContext dialog = rig.input.context("dialog", 50).groups(UI).blocking(true);
            InputContext overlay = rig.input.context("overlay", 200).groups(GAME);
            rig.input.push(dialog).push(overlay);
            rig.key(Key.SPACE, true).key(Key.ENTER, true).frame();
            assertThat(rig.one().down(JUMP)).isTrue();   // the overlay is above the dialog
            assertThat(rig.one().down(CONFIRM)).isTrue();
            rig.input.pop(overlay);
            rig.frame();
            assertThat(rig.one().down(JUMP)).isFalse();  // now under the dialog: hidden
            assertThat(rig.one().down(CONFIRM)).isTrue();
            assertThat(dialog.toString()).isEqualTo("dialog (50)");
            assertThat(dialog.priority()).isEqualTo(50);
            assertThat(dialog.groups()).containsExactly(UI);
        }
    }

    @Test
    void contextsCaptureAndReleaseTheMouse() {
        try (Rig rig = new Rig(GAME, UI)) {
            rig.frame();
            assertThat(rig.input.mouseCaptured()).isFalse();
            rig.input.mouse(MouseMode.CAPTURED);
            rig.frame();
            assertThat(rig.input.mouseCaptured()).isTrue();
            assertThat(rig.input.desktopSource().captured()).isTrue();
            InputContext menu = rig.input.context("menu", 10).groups(UI).mouse(MouseMode.RELEASED);
            InputContext inherit = rig.input.context("hud", 20).groups(UI);
            rig.input.push(menu).push(inherit);
            rig.frame();
            assertThat(inherit.mouse()).isEqualTo(MouseMode.INHERIT);
            assertThat(rig.input.mouseCaptured()).isFalse();
            rig.input.pop(menu);
            rig.frame();
            assertThat(rig.input.mouseCaptured()).isTrue();
            assertThatIllegalArgumentException().isThrownBy(() -> rig.input.mouse(MouseMode.INHERIT));
        }
    }

    @Test
    void aTextFieldTakesTheKeys() {
        try (Rig rig = new Rig(GAME, UI)) {
            List<String> typed = new ArrayList<>();
            InputContext field = rig.input.context("chat", 500).groups(UI).takesText(new TextListener() {
                @Override
                public void text(String text) {
                    typed.add(text);
                }

                @Override
                public void editing(String text, int start, int length) {
                    typed.add("[" + text + "]");
                }
            });
            rig.input.push(field);
            rig.key(Key.W, true).key(Key.ENTER, true).frame();
            rig.events().textEditing(1, "ka", 2, 0);
            rig.events().text(1, "w");
            assertThat(typed).containsExactly("[ka]", "w");
            assertThat(rig.input.textContext()).isSameAs(field);
            assertThat(rig.input.desktopSource().takingText()).isTrue();
            assertThat(rig.input.textKind()).as("plain text unless said").isZero();
            assertThat(field.textKind(1).textKind()).isEqualTo(1); // a password: text input starts with it
            assertThat(rig.input.textKind()).isEqualTo(1);
            assertThat(rig.one().y(MOVE)).isZero();          // W types; it does not walk
            assertThat(rig.one().down(CONFIRM)).isTrue();    // the field's own binding still works
            rig.plug(1).axis(1, GamepadAxis.LEFT_Y, -1).frame();
            assertThat(rig.one().y(MOVE)).isEqualTo(1);      // only keys are taken
            field.noText();
            rig.frame();
            assertThat(rig.input.textContext()).isNull();
            assertThat(rig.input.desktopSource().takingText()).isFalse();
            assertThat(rig.input.textKind()).isZero();
            rig.events().text(1, "x");
            assertThat(typed).hasSize(2);
            rig.axis(1, GamepadAxis.LEFT_Y, 0).frame();
            assertThat(rig.one().y(MOVE)).isEqualTo(1);      // W reaches the game again
        }
    }

    @Test
    void aContextSwitchResetsTheBindingFilters() { // D86
        ActionGroup group = ActionGroup.root("smoothed");
        Axis1Action look = group.axis1("look", Binding.pad(GamepadAxis.RIGHT_X).filter(Filters.SMOOTH, 1f));
        try (Rig rig = new Rig(group, UI)) {
            rig.plug(1).frame(100);  // a smoother's first value is taken as it is: 0
            rig.axis(1, GamepadAxis.RIGHT_X, 1).frame(100);
            assertThat(rig.one().value(look)).isBetween(0.05f, 0.15f); // follows over a second
            rig.input.push(rig.input.context("menu", 1).groups(UI));
            rig.frame(100);
            assertThat(rig.one().value(look)).isEqualTo(1); // reset: starts from the value again
        }
    }
}
