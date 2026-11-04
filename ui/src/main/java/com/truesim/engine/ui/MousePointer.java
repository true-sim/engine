package com.truesim.engine.ui;

import com.truesim.engine.input.Input;
import com.truesim.engine.platform.MouseButton;
import com.truesim.engine.platform.PlatformListener;
import com.truesim.engine.platform.Window;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.PointerSource;
import com.truesim.ui.core.input.UiInput;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * The mouse as a UI pointer, read from the platform's events directly (UI22), not through actions: its position in a
 * window, in the window's pixels (the UI's), its buttons and this frame's wheel. Listen to the desktop with it
 * ({@code desktop.listen(mouse)}); {@link #poll} writes the pointer at the frame's start. While input has captured the
 * mouse (a game's mouse look), the UI has no mouse pointer.
 *
 * <pre>{@code
 * MousePointer mouse = new MousePointer(window, input);
 * desktop.listen(mouse);
 * // each frame, before the UI updates:
 * mouse.poll(uiInput);
 * }</pre>
 *
 * <p>Use it on the platform's thread, which delivers the events and runs the frames. Polling allocates nothing.
 */
public final class MousePointer implements PointerSource, PlatformListener {

    /** The pointer id the mouse has in the UI's input. */
    public static final int ID = 1;
    /** The mouse id of SDL's mouse events made from touches ({@code SDL_TOUCH_MOUSEID}). */
    public static final int TOUCH_MOUSE = -1;
    /** The mouse id of SDL's mouse events made from a pen ({@code SDL_PEN_MOUSEID}). */
    public static final int PEN_MOUSE = -2;

    /**
     * Maps the mouse's position in a window into the UI's pixels: for a UI shown in several windows (docking's OS
     * windows, {@link UiOsWindows}). Must not allocate.
     */
    @FunctionalInterface
    public interface Mapper {
        /**
         * The UI position of the mouse at {@code x, y} in window {@code windowId} (window coordinates) into
         * {@code out}; false if that window is not the UI's (the event is then the main window's or ignored).
         */
        boolean map(int windowId, float x, float y, float[] out);
    }

    private final Window window; // the window whose UI it points into, or null: any, unscaled
    private final Input input;   // whose mouse capture hides it, or null
    private float x;
    private float y;
    private int held; // MouseButton masks
    private float wheelX;
    private float wheelY;
    private boolean seen;
    private boolean mapped; // x, y are UI pixels already, from the mapper
    private Mapper mapper;
    private IntSupplier modifiers;
    private boolean ignoreEmulated;
    private final float[] at = new float[2];

    /**
     * The mouse in {@code window} (events of other windows ignored; positions scaled to its pixels), hidden while
     * {@code input} has captured it; either may be null (any window, unscaled; never hidden).
     */
    public MousePointer(Window window, Input input) {
        this.window = window;
        this.input = input;
    }

    /** The mouse in any window, unscaled, never hidden: for tests and single windows of one pixel per point. */
    public MousePointer() {
        this(null, null);
    }

    /** Maps positions of every window the UI shows in through {@code value} (null: only its own window). */
    public MousePointer mapper(Mapper value) {
        mapper = value;
        return this;
    }

    /** The modifiers the pointer carries ({@link Pointer#SHIFT} and the others), e.g. {@link UiKeyboard#pointerModifiers}. */
    public MousePointer modifiers(IntSupplier value) {
        modifiers = value;
        return this;
    }

    /**
     * Ignores the mouse events SDL makes from touches and pens (their own pointers stand for them: {@link TouchPointers},
     * {@link PenPointer}), so a finger is not also the mouse; returns this.
     */
    public MousePointer ignoreEmulated(boolean on) {
        ignoreEmulated = on;
        return this;
    }

    private boolean emulated(int mouse) {
        return ignoreEmulated && (mouse == TOUCH_MOUSE || mouse == PEN_MOUSE);
    }

    private boolean ours(int windowId) {
        return window == null || window.id() == windowId;
    }

    /** Takes {@code mx, my} in window {@code windowId} as the mouse's place; false if the event is not the UI's. */
    private boolean place(int windowId, float mx, float my) {
        if (mapper != null && mapper.map(windowId, mx, my, at)) {
            x = at[0];
            y = at[1];
            mapped = true;
            return true;
        }
        if (!ours(windowId)) {
            return false;
        }
        x = mx;
        y = my;
        mapped = false;
        return true;
    }

    private float scaleX() {
        return window == null || window.width() <= 0 ? 1 : (float) window.pixelWidth() / window.width();
    }

    private float scaleY() {
        return window == null || window.height() <= 0 ? 1 : (float) window.pixelHeight() / window.height();
    }

    @Override
    public void mouseMoved(long timestamp, int windowId, int mouse, float mx, float my, float dx, float dy,
        int buttons) {
        if (!emulated(mouse) && place(windowId, mx, my)) {
            held = buttons;
            seen = true;
        }
    }

    @Override
    public void mouseButton(long timestamp, int windowId, int mouse, MouseButton button, boolean down, int clicks,
        float mx, float my) {
        if (!emulated(mouse) && place(windowId, mx, my)) {
            held = down ? held | button.mask() : held & ~button.mask();
            seen = true;
        }
    }

    @Override
    public void mouseWheel(long timestamp, int windowId, int mouse, float dx, float dy) {
        if (emulated(mouse)) {
            return;
        }
        if (ours(windowId) || mapper != null && mapper.map(windowId, x, y, at)) {
            wheelX += dx;
            wheelY += dy;
        }
    }

    /** Writes the mouse's pointer into {@code ui}: where it is, its buttons, the wheel since the last poll. */
    @Override
    public void poll(UiInput ui) {
        if (!seen || input != null && input.mouseCaptured()) {
            ui.removePointer(ID);
            wheelX = 0;
            wheelY = 0;
            return;
        }
        int buttons = (held & MouseButton.LEFT.mask()) != 0 ? Pointer.PRIMARY : 0;
        buttons |= (held & MouseButton.RIGHT.mask()) != 0 ? Pointer.SECONDARY : 0;
        buttons |= (held & MouseButton.MIDDLE.mask()) != 0 ? Pointer.MIDDLE : 0;
        ui.pointer(ID, Pointer.Kind.MOUSE).moveTo(mapped ? x : x * scaleX(), mapped ? y : y * scaleY()).buttons(buttons)
            .scroll(wheelX, wheelY).modifiers(modifiers == null ? 0 : modifiers.getAsInt());
        wheelX = 0;
        wheelY = 0;
    }

    /** The window it points into, or null. */
    public Window window() {
        return window;
    }

    @Override
    public String toString() {
        return "mouse pointer" + (window == null ? "" : " in " + Objects.toString(window));
    }
}
