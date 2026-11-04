package com.truesim.engine.interaction;

import com.truesim.engine.input.BinaryAction;
import com.truesim.engine.input.Controls;
import com.truesim.engine.input.Input;
import com.truesim.engine.input.Player;
import com.truesim.engine.input.PoseAction;
import com.truesim.engine.platform.PenState;
import com.truesim.engine.platform.PlatformListener;
import com.truesim.engine.platform.TouchPhase;
import com.truesim.engine.platform.Window;
import java.util.function.BooleanSupplier;

/**
 * The scene interaction's pointer sources: the mouse, fingers, a pen, XR controllers' rays and a gamepad's virtual
 * cursor. Positions come from the platform's events (in window pixels: the mouse's and pen's points scaled by the
 * window's pixel density), buttons of the mouse, the rays and the cursor from {@link SceneActions}, fingers and the pen
 * from their own contact. The plugin makes them; tests make pointers of their own. Polling allocates nothing.
 */
public final class ScenePointers {

    /** The mouse's pointer id. */
    public static final int MOUSE_ID = 1;
    /** The pen's pointer id. */
    public static final int PEN_ID = 2;
    /** The left XR ray's pointer id. */
    public static final int LEFT_RAY_ID = 3;
    /** The right XR ray's pointer id. */
    public static final int RIGHT_RAY_ID = 4;
    /** The virtual cursor's pointer id. */
    public static final int CURSOR_ID = 5;
    /** The first finger's pointer id; the others follow. */
    public static final int FIRST_FINGER_ID = 16;

    private static final int MOUSE_TOUCH = -1; // SDL_TOUCH_MOUSEID
    private static final int PEN_MOUSE = -2;   // SDL_PEN_MOUSEID

    private ScenePointers() {
    }

    /** The modifiers held, as {@link ScenePointer#SHIFT} and the others, read from the keyboard directly. */
    static int modifiers(Input input) {
        if (input == null) {
            return 0;
        }
        com.truesim.engine.input.Device keyboard = input.keyboard();
        return (keyboard.down(Controls.SHIFT) ? ScenePointer.SHIFT : 0)
            | (keyboard.down(Controls.CONTROL) ? ScenePointer.CTRL : 0)
            | (keyboard.down(Controls.ALT) ? ScenePointer.ALT : 0)
            | (keyboard.down(Controls.META) ? ScenePointer.META : 0);
    }

    private static boolean ours(Window window, int windowId) {
        return window == null || window.id() == windowId;
    }

    private static float scaleX(Window window) {
        return window == null || window.width() <= 0 ? 1 : (float) window.pixelWidth() / window.width();
    }

    private static float scaleY(Window window) {
        return window == null || window.height() <= 0 ? 1 : (float) window.pixelHeight() / window.height();
    }

    /** The mouse in a window: its position from the platform, its buttons from {@link SceneActions}. */
    public static final class Mouse implements ScenePointerSource, PlatformListener {

        private final Window window;
        private final Input input;
        private float x;
        private float y;
        private float wheelX;
        private float wheelY;
        private boolean seen;

        /** The mouse over {@code window} (null: any, unscaled), its buttons {@code input}'s first player's. */
        public Mouse(Window window, Input input) {
            this.window = window;
            this.input = input;
        }

        @Override
        public void mouseMoved(long timestamp, int windowId, int mouse, float mx, float my, float dx, float dy,
            int buttons) {
            if (mouse != MOUSE_TOUCH && mouse != PEN_MOUSE && ours(window, windowId)) {
                x = mx;
                y = my;
                seen = true;
            }
        }

        @Override
        public void mouseWheel(long timestamp, int windowId, int mouse, float dx, float dy) {
            if (mouse != MOUSE_TOUCH && mouse != PEN_MOUSE && ours(window, windowId)) {
                wheelX += dx;
                wheelY += dy;
            }
        }

        @Override
        public void poll(SceneInteraction pointers) {
            if (seen && !input.mouseCaptured()) {
                Player player = input.player(0);
                int buttons = (player.down(SceneActions.PRESS) ? ScenePointer.PRIMARY : 0)
                    | (player.down(SceneActions.CONTEXT) ? ScenePointer.SECONDARY : 0)
                    | (player.down(SceneActions.MIDDLE) ? ScenePointer.MIDDLE : 0);
                pointers.pointer(MOUSE_ID, PointerKind.MOUSE).moveTo(x * scaleX(window), y * scaleY(window))
                    .buttons(buttons).scroll(wheelX, wheelY).modifiers(modifiers(input));
            }
            wheelX = 0;
            wheelY = 0;
        }
    }

    /** Fingers on a window: each a pointer pressed while it touches. */
    public static final class Touch implements ScenePointerSource, PlatformListener {

        private static final int MAX = 10;
        private final Window window;
        private final long[] devices = new long[MAX];
        private final long[] fingers = new long[MAX];
        private final float[] xs = new float[MAX];
        private final float[] ys = new float[MAX];
        private final byte[] states = new byte[MAX]; // 0 free, 1 down, 2 lifted (released at the next poll)
        private final boolean[] polled = new boolean[MAX];
        private int width = 1;
        private int height = 1;

        /** Fingers on {@code window} (null: any, its size {@link #size}). */
        public Touch(Window window) {
            this.window = window;
        }

        /** The size fingers' normalized positions scale to without a window; returns it. */
        public Touch size(int pixelWidth, int pixelHeight) {
            width = pixelWidth;
            height = pixelHeight;
            return this;
        }

        @Override
        public void touch(long timestamp, int windowId, long device, long finger, TouchPhase phase, float tx,
            float ty, float dx, float dy, float pressure) {
            if (!ours(window, windowId)) {
                return;
            }
            int slot = -1;
            for (int i = 0; i < MAX; i++) {
                if (states[i] == 1 && devices[i] == device && fingers[i] == finger) {
                    slot = i;
                }
            }
            if (phase == TouchPhase.DOWN && slot < 0) {
                for (int i = 0; i < MAX && slot < 0; i++) {
                    if (states[i] == 0) {
                        slot = i;
                        devices[i] = device;
                        fingers[i] = finger;
                        polled[i] = false;
                    }
                }
            }
            if (slot < 0) {
                return;
            }
            states[slot] = phase == TouchPhase.UP || phase == TouchPhase.CANCELED ? (byte) 2 : 1;
            xs[slot] = tx * (window != null ? window.pixelWidth() : width); // normalized to the window
            ys[slot] = ty * (window != null ? window.pixelHeight() : height);
        }

        @Override
        public void poll(SceneInteraction pointers) {
            for (int i = 0; i < MAX; i++) {
                if (states[i] == 0) {
                    continue;
                }
                boolean down = states[i] == 1 || !polled[i]; // a tap within a frame: pressed for one poll
                pointers.pointer(FIRST_FINGER_ID + i, PointerKind.TOUCH).moveTo(xs[i], ys[i])
                    .buttons(down ? ScenePointer.PRIMARY : 0);
                if (states[i] == 2 && polled[i]) {
                    states[i] = 0; // released this poll: gone at the next
                }
                polled[i] = true;
            }
        }
    }

    /** A pen over a window: its tip the primary button, its barrel buttons the others. */
    public static final class Pen implements ScenePointerSource, PlatformListener {

        private final Window window;
        private float x;
        private float y;
        private int state;
        private boolean near;
        private boolean tapped;

        /** A pen over {@code window} (null: any, unscaled). */
        public Pen(Window window) {
            this.window = window;
        }

        @Override
        public void penProximity(long timestamp, int windowId, int pen, boolean in) {
            if (ours(window, windowId)) {
                near = in;
                state = in ? state : 0;
            }
        }

        @Override
        public void penTouch(long timestamp, int windowId, int pen, float px, float py, boolean eraser, boolean down) {
            if (ours(window, windowId)) {
                place(px, py);
                state = down ? state | PenState.DOWN : state & ~PenState.DOWN;
                tapped |= down;
            }
        }

        @Override
        public void penMoved(long timestamp, int windowId, int pen, float px, float py, int penState) {
            if (ours(window, windowId)) {
                place(px, py);
                state = penState;
                tapped |= (penState & PenState.DOWN) != 0;
            }
        }

        private void place(float px, float py) {
            x = px;
            y = py;
            near = true;
        }

        @Override
        public void poll(SceneInteraction pointers) {
            if (near) {
                int buttons = ((state & PenState.DOWN) != 0 || tapped ? ScenePointer.PRIMARY : 0)
                    | ((state & PenState.BUTTON1) != 0 ? ScenePointer.SECONDARY : 0)
                    | ((state & PenState.BUTTON2) != 0 ? ScenePointer.MIDDLE : 0);
                pointers.pointer(PEN_ID, PointerKind.PEN).moveTo(x * scaleX(window), y * scaleY(window))
                    .buttons(buttons);
            }
            tapped = false;
        }
    }

    /** XR controllers' aim rays, from {@link SceneActions#AIM_LEFT} and {@link SceneActions#AIM_RIGHT}. */
    public static final class XrRays implements ScenePointerSource {

        private final Input input;
        private final double[] position = new double[3];
        private final float[] orientation = new float[4];

        /** The rays of {@code input}'s first player. */
        public XrRays(Input input) {
            this.input = input;
        }

        @Override
        public void poll(SceneInteraction pointers) {
            Player player = input.player(0);
            hand(pointers, player, LEFT_RAY_ID, SceneActions.AIM_LEFT, SceneActions.SELECT_LEFT);
            hand(pointers, player, RIGHT_RAY_ID, SceneActions.AIM_RIGHT, SceneActions.SELECT_RIGHT);
        }

        private void hand(SceneInteraction pointers, Player player, int id, PoseAction aim, BinaryAction select) {
            if (!player.pose(aim, position, orientation)) {
                return; // untracked: the pointer leaves
            }
            float qx = orientation[0];
            float qy = orientation[1];
            float qz = orientation[2];
            float qw = orientation[3];
            // -Z turned by the orientation: the negated third column of its rotation matrix
            double fx = -2 * (qx * qz + qw * qy);
            double fy = -2 * (qy * qz - qw * qx);
            double fz = -(1 - 2 * (qx * qx + qy * qy));
            pointers.pointer(id, PointerKind.XR_RAY).ray(position[0], position[1], position[2], fx, fy, fz, null)
                .buttons(player.down(select) ? ScenePointer.PRIMARY : 0).modifiers(modifiers(input));
        }
    }

    /** A gamepad's virtual cursor over a window, while its setting is on. */
    public static final class VirtualCursor implements ScenePointerSource {

        private final Input input;
        private final BooleanSupplier on;
        private final java.util.function.DoubleSupplier speed;
        private final Window window;
        private boolean shown;
        private long last = -1;
        private float x;
        private float y;
        private int width;
        private int height;

        /**
         * The cursor of {@code input}'s first player over {@code window} (null: over a {@link #size}), while {@code on}
         * says so, moving at up to {@code pixelsPerSecond}.
         */
        public VirtualCursor(Input input, BooleanSupplier on, java.util.function.DoubleSupplier pixelsPerSecond,
            Window window) {
            this.input = input;
            this.on = on;
            this.speed = pixelsPerSecond;
            this.window = window;
        }

        /** The area it moves over without a window; returns it. */
        public VirtualCursor size(int pixelWidth, int pixelHeight) {
            width = pixelWidth;
            height = pixelHeight;
            return this;
        }

        @Override
        public void poll(SceneInteraction pointers) {
            long nanos = System.nanoTime();
            if (!on.getAsBoolean()) {
                shown = false;
                last = nanos;
                return;
            }
            float w = Math.max(1, window != null ? window.pixelWidth() : width);
            float h = Math.max(1, window != null ? window.pixelHeight() : height);
            Player player = input.player(0);
            if (!shown) {
                shown = true;
                x = w / 2;
                y = h / 2;
            } else {
                float dt = last < 0 ? 0 : (float) Math.min(0.1, (nanos - last) / 1e9);
                float v = (float) speed.getAsDouble();
                x = Math.clamp(x + player.x(SceneActions.CURSOR) * v * dt, 0, w - 1);
                y = Math.clamp(y - player.y(SceneActions.CURSOR) * v * dt, 0, h - 1);
            }
            last = nanos;
            pointers.pointer(CURSOR_ID, PointerKind.VIRTUAL_CURSOR).moveTo(x, y)
                .buttons(player.down(SceneActions.CURSOR_PRESS) ? ScenePointer.PRIMARY : 0);
        }
    }
}
