package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.platform.Platform;
import com.truesim.engine.platform.Window;
import com.truesim.engine.platform.WindowOptions;
import com.truesim.ui.core.input.TextInputHost;
import org.junit.jupiter.api.Test;

/** The UI's text input through the input system and SDL, and the SDL clipboard. */
class UiTextInputTest {

    @Test
    void typedTextAndCompositionReachTheUiAtFrameStart() {
        try (InputRig rig = new InputRig()) {
            UiTextInput text = new UiTextInput(rig.commands, null).scale(2);
            assertThat(text.active()).isFalse();
            assertThat(text.kind()).isEqualTo(-1);
            text.start(10, 20, 4, 18, TextInputHost.PASSWORD);
            assertThat(text.active()).isTrue();
            assertThat(text.kind()).isEqualTo(TextInputHost.PASSWORD);
            assertThat(rig.commands.textContext().textKind()).isEqualTo(Window.TEXT_INPUT_PASSWORD);
            rig.frame();
            assertThat(rig.input.textContext()).isSameAs(rig.commands.textContext());
            text.text("h"); // as the input system hands the text context's listener typed text
            text.text("i");
            text.editing("\u304B", 1, 0);
            rig.ui.beginFrame();
            text.poll(rig.ui);
            assertThat(rig.ui.text().toString()).isEqualTo("hi");
            assertThat(rig.ui.composition().toString()).isEqualTo("\u304B");
            assertThat(rig.ui.compositionCursor()).isEqualTo(1);
            rig.ui.beginFrame();
            text.poll(rig.ui);
            assertThat(rig.ui.text().toString()).as("handed over once").isEmpty();
            text.area(1, 2, 3, 4);
            text.start(0, 0, 10, 10);
            assertThat(text.kind()).isEqualTo(TextInputHost.TEXT);
            text.editing("x", -1, 0);
            text.stop();
            text.poll(rig.ui);
            assertThat(rig.ui.composition().toString()).as("stopping ends the composition").isEmpty();
            for (int kind : new int[] {TextInputHost.NUMBER, TextInputHost.MULTILINE}) {
                text.start(0, 0, 1, 1, kind);
                text.stop();
            }
            assertThat(text.active()).isFalse();
            rig.frame();
            assertThat(rig.input.textContext()).isNull();
        }
    }

    @Test
    void sdlTextInputStartsWithTheKindAndTheClipboardIsTheSystems() {
        Platform platform = Platform.start();
        try (InputRig rig = new InputRig()) {
            Window window = platform.createWindow(WindowOptions.of("text", 200, 100).withHidden(true)
                .withVulkan(false));
            UiTextInput text = new UiTextInput(rig.commands, window);
            text.start(10, 10, 2, 16, TextInputHost.PASSWORD);
            assertThat(window.textInputActive()).isTrue();
            text.start(10, 10, 2, 16, TextInputHost.PASSWORD); // the same kind: not restarted
            text.area(30, 10, 2, 16);
            text.stop();
            assertThat(window.textInputActive()).isFalse();
            SdlClipboard clipboard = new SdlClipboard(platform);
            clipboard.text("copied \u2764");
            String back = clipboard.text();
            assertThat(back).isNotNull();
            if (platform.hasClipboardText()) {
                assertThat(back).isEqualTo("copied \u2764");
            }
            clipboard.text(null);
        } finally {
            platform.close();
        }
    }
}
