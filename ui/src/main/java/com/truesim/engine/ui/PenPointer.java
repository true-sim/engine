package com.truesim.engine.ui;

import com.truesim.engine.platform.PenState;
import com.truesim.engine.platform.PlatformListener;
import com.truesim.engine.platform.Window;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.PointerSource;
import com.truesim.ui.core.input.UiInput;
import java.util.function.IntSupplier;

/**
 * A pen (a tablet's stylus) as a UI pointer of {@link Pointer.Kind#PEN}: there while the pen is near enough to be
 * tracked (it hovers), at its position in the window's pixels, its tip (or eraser) on the surface the primary button,
 * barrel button 1 the secondary and 2 the middle. ui-core's pointer has no pressure or tilt: those are not passed on.
 * A touch and lift between two frames is still seen pressed for one frame. The mouse events SDL makes from the pen are
 * ignored ({@link MousePointer#ignoreEmulated}). Use it on the platform's thread; polling allocates nothing.
 */
public final class PenPointer implements PointerSource, PlatformListener {

    /** The pointer id the pen has in the UI's input. */
    public static final int ID = 2;

    private final Window window;
    private IntSupplier modifiers;
    private boolean near;
    private boolean seen;
    private float x;
    private float y;
    private int state;         // PenState bits
    private boolean tapped;    // the tip touched since the last poll
    private boolean wasNear;

    /** The pen over {@code window} (null: any window, unscaled). */
    public PenPointer(Window window) {
        this.window = window;
    }

    /** The modifiers it carries ({@link UiKeyboard#pointerModifiers}); returns this. */
    public PenPointer modifiers(IntSupplier value) {
        modifiers = value;
        return this;
    }

    private boolean ours(int windowId) {
        return window == null || window.id() == windowId;
    }

    @Override
    public void penProximity(long timestamp, int windowId, int pen, boolean in) {
        if (ours(windowId)) {
            near = in;
            if (!in) {
                state = 0;
            }
        }
    }

    @Override
    public void penTouch(long timestamp, int windowId, int pen, float px, float py, boolean eraser, boolean down) {
        if (ours(windowId)) {
            place(px, py);
            state = down ? state | PenState.DOWN : state & ~PenState.DOWN;
            tapped |= down;
        }
    }

    @Override
    public void penButton(long timestamp, int windowId, int pen, float px, float py, int button, boolean down) {
        if (ours(windowId) && button >= 1 && button <= 5) {
            place(px, py);
            int bit = PenState.BUTTON1 << (button - 1);
            state = down ? state | bit : state & ~bit;
        }
    }

    @Override
    public void penMoved(long timestamp, int windowId, int pen, float px, float py, int penState) {
        if (ours(windowId)) {
            place(px, py);
            state = penState;
            tapped |= (penState & PenState.DOWN) != 0;
        }
    }

    private void place(float px, float py) {
        x = px;
        y = py;
        near = true;
        seen = true;
    }

    /** Writes the pen's pointer into {@code ui}, or removes it when the pen is away; call at the frame's start. */
    @Override
    public void poll(UiInput ui) {
        if (!near || !seen) {
            if (wasNear) {
                ui.removePointer(ID);
                wasNear = false;
            }
            tapped = false;
            return;
        }
        wasNear = true;
        int buttons = (state & PenState.DOWN) != 0 || tapped ? Pointer.PRIMARY : 0;
        buttons |= (state & PenState.BUTTON1) != 0 ? Pointer.SECONDARY : 0;
        buttons |= (state & PenState.BUTTON2) != 0 ? Pointer.MIDDLE : 0;
        tapped = false;
        float sx = window == null || window.width() <= 0 ? 1 : (float) window.pixelWidth() / window.width();
        float sy = window == null || window.height() <= 0 ? 1 : (float) window.pixelHeight() / window.height();
        ui.pointer(ID, Pointer.Kind.PEN).moveTo(x * sx, y * sy).buttons(buttons)
            .modifiers(modifiers == null ? 0 : modifiers.getAsInt());
    }
}
