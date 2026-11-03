package com.truesim.engine.input;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.platform.GamepadAxis;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.Key;
import com.truesim.engine.platform.MouseButton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A kind of device and its controls, by name and index: keyboards, mice, gamepads and joysticks here, more from the
 * plugins bringing them (XR controllers with {@code Xr}, application-api.md D55). Bindings name a control as
 * {@code prefix:NAME} ({@code key:W}, {@code pad:SOUTH}, {@code xr:RIGHT_TRIGGER}): generic controls, never a device
 * (D72). Keyboards and mice are not told apart: each is one device; gamepads, joysticks and XR controllers are
 * ({@link #distinct()}).
 *
 * <p>Some controls are made from others each frame: the keyboard's {@code SHIFT}, {@code CONTROL}, {@code ALT} and
 * {@code META} are either side's key (D71: modifiers left, right or either), a gamepad's {@code LEFT_STICK_UP} and its
 * siblings are a stick's directions as half axes, for menus.
 */
@AllowAllocation("declared once")
public final class DeviceClass {

    /** The keyboard: every {@link Key} by name, then {@code SHIFT}, {@code CONTROL}, {@code ALT}, {@code META}. */
    public static final DeviceClass KEYBOARD;
    /** The mouse: its buttons by {@link MouseButton} name, then {@code X}, {@code Y}, {@code WHEEL_X}, {@code WHEEL_Y}. */
    public static final DeviceClass MOUSE;
    /** A gamepad: {@link GamepadButton}s, {@link GamepadAxis} values, then the sticks' directions. */
    public static final DeviceClass GAMEPAD;
    /** A joystick that is not a gamepad: {@code BUTTON_0..31}, {@code AXIS_0..7}, {@code HAT_n_UP..}, balls. */
    public static final DeviceClass JOYSTICK;

    /** Joystick buttons, axes, hats and trackballs that controls exist for. */
    static final int JOY_BUTTONS = 32;
    static final int JOY_AXES = 8;
    static final int JOY_HATS = 4;
    static final int JOY_BALLS = 2;

    /** Computes made controls from the others, each frame after the device's values are taken. */
    @FunctionalInterface
    public interface Derive {
        /** Writes the made controls into {@code values}, reading the others. */
        void apply(float[] values);
    }

    private final String prefix;
    private final boolean distinct;
    private final String[] names;
    private final ControlKind[] kinds;
    private final int[] coverLeft;  // for an either-side control, the left one; -1 otherwise
    private final int[] coverRight; // and the right one
    private final int[][] partners; // per control, the others it covers (either side's key and one side's)
    private final Control[] controls;
    private final Map<String, Integer> byName = new HashMap<>();
    private final Derive derive;

    private DeviceClass(Builder builder) {
        this.prefix = builder.prefix;
        this.distinct = builder.distinct;
        this.names = builder.names.toArray(new String[0]);
        this.kinds = builder.kinds.toArray(new ControlKind[0]);
        this.coverLeft = new int[names.length];
        this.coverRight = new int[names.length];
        java.util.Arrays.fill(coverLeft, -1);
        java.util.Arrays.fill(coverRight, -1);
        for (int[] either : builder.either) {
            coverLeft[either[0]] = either[1];
            coverRight[either[0]] = either[2];
        }
        this.partners = new int[names.length][];
        for (int i = 0; i < names.length; i++) {
            int count = 0;
            int[] found = new int[names.length];
            for (int j = 0; j < names.length; j++) {
                if (j != i && covers(i, j)) {
                    found[count++] = j;
                }
            }
            partners[i] = java.util.Arrays.copyOf(found, count);
        }
        this.derive = builder.derive;
        this.controls = new Control[names.length];
        for (int i = 0; i < names.length; i++) {
            if (byName.put(names[i], i) != null) {
                throw new IllegalArgumentException(prefix + " has two controls named " + names[i]);
            }
            controls[i] = new Control(this, i);
        }
    }

    /** A new kind of device whose controls bindings name as {@code prefix:NAME}; {@code distinct}: told apart. */
    public static Builder builder(String prefix, boolean distinct) {
        return new Builder(prefix, distinct);
    }

    /** The word bindings name its controls with: {@code key}, {@code mouse}, {@code pad}, {@code joy}. */
    public String prefix() {
        return prefix;
    }

    /** Whether devices of this kind are told apart (gamepads are; keyboards and mice are not, D72). */
    public boolean distinct() {
        return distinct;
    }

    /** How many controls it has. */
    public int count() {
        return names.length;
    }

    /** Control {@code index}. */
    public Control control(int index) {
        return controls[index];
    }

    /** The control named {@code name} (case ignored), or null. */
    @AllowAllocation("parsing bindings")
    public Control control(String name) {
        Integer index = byName.get(name.toUpperCase(Locale.ROOT));
        return index == null ? null : controls[index];
    }

    String name(int index) {
        return names[index];
    }

    ControlKind kind(int index) {
        return kinds[index];
    }

    /** Whether control {@code a} stands for control {@code b}: the same, or either side's key and one side's. */
    boolean covers(int a, int b) {
        return a == b || coverLeft[a] == b || coverRight[a] == b || coverLeft[b] == a || coverRight[b] == a;
    }

    /** The controls control {@code index} covers or is covered by, itself left out. */
    int[] partners(int index) {
        return partners[index];
    }

    void derive(float[] values) {
        if (derive != null) {
            derive.apply(values);
        }
    }

    @Override
    public String toString() {
        return prefix;
    }

    /** Declares a kind of device's controls, in index order. */
    @AllowAllocation("declared once")
    public static final class Builder {
        private final String prefix;
        private final boolean distinct;
        private final List<String> names = new ArrayList<>();
        private final List<ControlKind> kinds = new ArrayList<>();
        private final List<int[]> either = new ArrayList<>();
        private Derive derive;

        private Builder(String prefix, boolean distinct) {
            if (!prefix.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("A device prefix is a lower-case word: '" + prefix + "'");
            }
            this.prefix = prefix;
            this.distinct = distinct;
        }

        /** Adds a control named {@code name} (upper case) of {@code kind}; its index is the number added before. */
        public Builder add(String name, ControlKind kind) {
            names.add(name.toUpperCase(Locale.ROOT));
            kinds.add(Objects.requireNonNull(kind, "kind"));
            return this;
        }

        /** The controls made each frame from the others. */
        public Builder derive(Derive made) {
            this.derive = made;
            return this;
        }

        Builder either(String name, int left, int right) {
            either.add(new int[] {names.size(), left, right});
            return add(name, ControlKind.BUTTON);
        }

        /** The kind of device. */
        public DeviceClass build() {
            return new DeviceClass(this);
        }
    }

    // ---------------------------------------------------------------- the platform's devices

    static final Key[] KEYS = Key.values();
    static final int KEY_SHIFT = KEYS.length;
    static final int KEY_CONTROL = KEYS.length + 1;
    static final int KEY_ALT = KEYS.length + 2;
    static final int KEY_META = KEYS.length + 3;

    static final MouseButton[] MOUSE_BUTTONS = MouseButton.values();
    static final int MOUSE_X = MOUSE_BUTTONS.length;
    static final int MOUSE_Y = MOUSE_X + 1;
    static final int WHEEL_X = MOUSE_X + 2;
    static final int WHEEL_Y = MOUSE_X + 3;

    static final GamepadButton[] PAD_BUTTONS = GamepadButton.values();
    static final GamepadAxis[] PAD_AXES = GamepadAxis.values();
    static final int PAD_AXIS = PAD_BUTTONS.length;
    static final int PAD_STICKS = PAD_AXIS + PAD_AXES.length; // LEFT_STICK_UP, DOWN, LEFT, RIGHT, then RIGHT_STICK_*

    static final int JOY_AXIS = JOY_BUTTONS;
    static final int JOY_HAT = JOY_AXIS + JOY_AXES;
    static final int JOY_BALL = JOY_HAT + 4 * JOY_HATS;

    static {
        Builder keyboard = builder("key", false);
        for (Key key : KEYS) {
            keyboard.add(key.name(), ControlKind.BUTTON);
        }
        keyboard.either("SHIFT", Key.LEFT_SHIFT.ordinal(), Key.RIGHT_SHIFT.ordinal())
            .either("CONTROL", Key.LEFT_CONTROL.ordinal(), Key.RIGHT_CONTROL.ordinal())
            .either("ALT", Key.LEFT_ALT.ordinal(), Key.RIGHT_ALT.ordinal())
            .either("META", Key.LEFT_META.ordinal(), Key.RIGHT_META.ordinal())
            .derive(DeviceClass::eitherSide);
        KEYBOARD = keyboard.build();

        Builder mouse = builder("mouse", false);
        for (MouseButton button : MOUSE_BUTTONS) {
            mouse.add(button.name(), ControlKind.BUTTON);
        }
        MOUSE = mouse.add("X", ControlKind.DELTA).add("Y", ControlKind.DELTA).add("WHEEL_X", ControlKind.DELTA)
            .add("WHEEL_Y", ControlKind.DELTA).build();

        Builder pad = builder("pad", true);
        for (GamepadButton button : PAD_BUTTONS) {
            pad.add(button.name(), ControlKind.BUTTON);
        }
        for (GamepadAxis axis : PAD_AXES) {
            pad.add(axis.name(), axis == GamepadAxis.LEFT_TRIGGER || axis == GamepadAxis.RIGHT_TRIGGER
                ? ControlKind.HALF_AXIS : ControlKind.AXIS);
        }
        for (String stick : new String[] {"LEFT_STICK", "RIGHT_STICK"}) {
            for (String direction : new String[] {"UP", "DOWN", "LEFT", "RIGHT"}) {
                pad.add(stick + "_" + direction, ControlKind.HALF_AXIS);
            }
        }
        GAMEPAD = pad.derive(DeviceClass::stickDirections).build();

        Builder joystick = builder("joy", true);
        for (int i = 0; i < JOY_BUTTONS; i++) {
            joystick.add("BUTTON_" + i, ControlKind.BUTTON);
        }
        for (int i = 0; i < JOY_AXES; i++) {
            joystick.add("AXIS_" + i, ControlKind.AXIS);
        }
        for (int i = 0; i < JOY_HATS; i++) {
            for (String direction : new String[] {"UP", "RIGHT", "DOWN", "LEFT"}) {
                joystick.add("HAT_" + i + "_" + direction, ControlKind.BUTTON);
            }
        }
        for (int i = 0; i < JOY_BALLS; i++) {
            joystick.add("BALL_" + i + "_X", ControlKind.DELTA).add("BALL_" + i + "_Y", ControlKind.DELTA);
        }
        JOYSTICK = joystick.build();
    }

    private static void eitherSide(float[] values) {
        values[KEY_SHIFT] = Math.max(values[Key.LEFT_SHIFT.ordinal()], values[Key.RIGHT_SHIFT.ordinal()]);
        values[KEY_CONTROL] = Math.max(values[Key.LEFT_CONTROL.ordinal()], values[Key.RIGHT_CONTROL.ordinal()]);
        values[KEY_ALT] = Math.max(values[Key.LEFT_ALT.ordinal()], values[Key.RIGHT_ALT.ordinal()]);
        values[KEY_META] = Math.max(values[Key.LEFT_META.ordinal()], values[Key.RIGHT_META.ordinal()]);
    }

    private static void stickDirections(float[] values) {
        for (int stick = 0; stick < 2; stick++) {
            float x = values[PAD_AXIS + 2 * stick];
            float y = values[PAD_AXIS + 2 * stick + 1]; // positive is down
            int at = PAD_STICKS + 4 * stick;
            values[at] = Math.max(0, -y);
            values[at + 1] = Math.max(0, y);
            values[at + 2] = Math.max(0, -x);
            values[at + 3] = Math.max(0, x);
        }
    }
}
