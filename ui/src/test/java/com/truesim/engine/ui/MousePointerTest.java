package com.truesim.engine.ui;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.input.MouseMode;
import com.truesim.engine.platform.MouseButton;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import org.junit.jupiter.api.Test;

/** The mouse read from the platform's events directly as the UI's pointer (UI22). */
class MousePointerTest {

    @Test
    void theMouseMovesClicksAndScrollsItsPointer() {
        MousePointer mouse = new MousePointer();
        UiInput ui = new UiInput(8);
        mouse.poll(ui);
        assertThat(ui.pointerCount()).as("not seen yet").isZero();
        mouse.mouseMoved(0, 1, 0, 120, 45, 3, 1, 0);
        mouse.mouseButton(0, 1, 0, MouseButton.LEFT, true, 1, 121, 46);
        mouse.mouseButton(0, 1, 0, MouseButton.RIGHT, true, 1, 121, 46);
        mouse.mouseWheel(0, 1, 0, 0, 1);
        mouse.mouseWheel(0, 1, 0, 0.5f, 1);
        mouse.poll(ui);
        Pointer pointer = ui.pointerAt(0);
        assertThat(pointer.id()).isEqualTo(MousePointer.ID);
        assertThat(pointer.kind()).isEqualTo(Pointer.Kind.MOUSE);
        assertThat(pointer.x()).isEqualTo(121);
        assertThat(pointer.y()).isEqualTo(46);
        assertThat(pointer.buttons()).isEqualTo(Pointer.PRIMARY | Pointer.SECONDARY);
        assertThat(pointer.scrollX()).isEqualTo(0.5f);
        assertThat(pointer.scrollY()).isEqualTo(2);
        mouse.mouseButton(0, 1, 0, MouseButton.LEFT, false, 1, 121, 46);
        mouse.poll(ui);
        assertThat(pointer.buttons()).isEqualTo(Pointer.SECONDARY);
        assertThat(pointer.scrollY()).as("the wheel is per frame").isZero();
        assertThat(ui.pointerCount()).isEqualTo(1);
        assertAllocationFree(() -> {
            mouse.mouseMoved(0, 1, 0, 10, 20, 1, 1, MouseButton.MIDDLE.mask());
            mouse.poll(ui);
        });
        assertThat(pointer.buttons()).isEqualTo(Pointer.MIDDLE);
    }

    @Test
    void aMouseCapturedByTheGameIsNoPointer() {
        try (InputRig rig = new InputRig()) {
            MousePointer mouse = new MousePointer(null, rig.input);
            UiInput ui = new UiInput(8);
            mouse.mouseMoved(0, 1, 0, 5, 5, 0, 0, 0);
            rig.frame();
            mouse.poll(ui);
            assertThat(ui.pointerCount()).isEqualTo(1);
            rig.input.mouse(MouseMode.CAPTURED);
            rig.frame();
            mouse.poll(ui);
            assertThat(ui.pointerCount()).isZero();
        }
    }
}
