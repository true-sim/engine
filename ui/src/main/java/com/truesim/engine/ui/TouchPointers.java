package com.truesim.engine.ui;

import com.truesim.engine.platform.PlatformListener;
import com.truesim.engine.platform.TouchPhase;
import com.truesim.engine.platform.Window;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.PointerSource;
import com.truesim.ui.core.input.UiInput;

/**
 * A touchscreen's fingers as UI pointers (design 4.7): one {@link Pointer.Kind#TOUCH} pointer per finger, from the
 * platform's touch events, pressed (its primary button) while the finger is down, released the frame after it lifts
 * and gone the frame after that (fingers do not hover). Ids {@link #FIRST_ID} on, at most {@link #MAX_FINGERS} at
 * once (more are ignored). A finger that touched and lifted between two frames is still seen pressed for one frame and
 * released the next, so quick taps click. A finger the system cancels (a gesture took it) goes without a release.
 * Touches SDL makes from the mouse or a pen are ignored (their own pointers stand for them), and so are the mouse
 * events SDL makes from touches ({@link MousePointer#ignoreEmulated}).
 *
 * <p>Positions are the window's 0 to 1 times its size in pixels (the UI's). Use it on the platform's thread. Polling
 * allocates nothing.
 */
public final class TouchPointers implements PointerSource, PlatformListener {

    /** The pointer id of the first finger slot; the others follow. */
    public static final int FIRST_ID = 100;
    /** The most fingers followed at once. */
    public static final int MAX_FINGERS = 10;
    /** The touch device id of SDL's touches made from the mouse ({@code SDL_MOUSE_TOUCHID}). */
    public static final long MOUSE_TOUCH = -1;
    /** The touch device id of SDL's touches made from a pen ({@code SDL_PEN_TOUCHID}). */
    public static final long PEN_TOUCH = -2;

    private static final int FREE = 0;
    private static final int DOWN = 1;     // down, not polled yet as pressed
    private static final int PRESSED = 2;  // reported pressed
    private static final int LIFTED = 3;   // up: reported released at the next poll (pressed first if it never was)
    private static final int RELEASED = 4; // reported released: removed at the next poll
    private static final int CANCELED = 5; // removed at the next poll without a release

    private final Window window;
    private int width = 1;
    private int height = 1;
    private final long[] devices = new long[MAX_FINGERS];
    private final long[] fingers = new long[MAX_FINGERS];
    private final float[] xs = new float[MAX_FINGERS];
    private final float[] ys = new float[MAX_FINGERS];
    private final int[] states = new int[MAX_FINGERS];
    private final boolean[] seenPressed = new boolean[MAX_FINGERS];

    /** The fingers on {@code window} (null: any window, positions scaled by {@link #size}). */
    public TouchPointers(Window window) {
        this.window = window;
    }

    /** The UI's size in pixels, without a window (tests); returns this. */
    public TouchPointers size(int pixelWidth, int pixelHeight) {
        width = pixelWidth;
        height = pixelHeight;
        return this;
    }

    @Override
    public void touch(long timestamp, int windowId, long device, long finger, TouchPhase phase, float x, float y,
        float dx, float dy, float pressure) {
        if (device == MOUSE_TOUCH || device == PEN_TOUCH || window != null && window.id() != windowId) {
            return;
        }
        int slot = slot(device, finger);
        if (phase == TouchPhase.DOWN) {
            if (slot < 0) {
                slot = free();
                if (slot < 0) {
                    return; // more fingers than followed
                }
                devices[slot] = device;
                fingers[slot] = finger;
                seenPressed[slot] = false;
            }
            states[slot] = DOWN;
        } else if (slot < 0) {
            return;
        } else if (phase == TouchPhase.UP) {
            states[slot] = LIFTED;
        } else if (phase == TouchPhase.CANCELED) {
            states[slot] = CANCELED;
        }
        xs[slot] = x;
        ys[slot] = y;
    }

    private int slot(long device, long finger) {
        for (int i = 0; i < MAX_FINGERS; i++) {
            if (states[i] != FREE && states[i] != RELEASED && states[i] != CANCELED && devices[i] == device
                && fingers[i] == finger) {
                return i;
            }
        }
        return -1;
    }

    private int free() {
        for (int i = 0; i < MAX_FINGERS; i++) {
            if (states[i] == FREE) {
                return i;
            }
        }
        return -1;
    }

    /** Writes each finger's pointer into {@code ui}; call at the frame's start. */
    @Override
    public void poll(UiInput ui) {
        float w = window != null ? window.pixelWidth() : width;
        float h = window != null ? window.pixelHeight() : height;
        for (int i = 0; i < MAX_FINGERS; i++) {
            int id = FIRST_ID + i;
            switch (states[i]) {
                case DOWN, PRESSED -> {
                    states[i] = PRESSED;
                    seenPressed[i] = true;
                    write(ui, id, i, w, h, Pointer.PRIMARY);
                }
                case LIFTED -> {
                    if (seenPressed[i]) {
                        states[i] = RELEASED;
                        write(ui, id, i, w, h, 0);
                    } else {
                        seenPressed[i] = true; // a tap within a frame: pressed now, released at the next poll
                        write(ui, id, i, w, h, Pointer.PRIMARY);
                    }
                }
                case RELEASED, CANCELED -> {
                    states[i] = FREE;
                    ui.removePointer(id);
                }
                default -> {
                    // a free slot
                }
            }
        }
    }

    private void write(UiInput ui, int id, int i, float w, float h, int buttons) {
        ui.pointer(id, Pointer.Kind.TOUCH).moveTo(xs[i] * w, ys[i] * h).buttons(buttons);
    }

    /** How many fingers it follows now. */
    public int count() {
        int n = 0;
        for (int i = 0; i < MAX_FINGERS; i++) {
            n += states[i] != FREE ? 1 : 0;
        }
        return n;
    }
}
