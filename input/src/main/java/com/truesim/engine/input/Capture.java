package com.truesim.engine.input;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.platform.GamepadAxis;
import com.truesim.engine.platform.Key;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Listening for a player's next input to rebind an action ({@link Input#capture}): while it listens, the player's
 * actions see nothing. A button or key held while another of the same kind of device goes down becomes a chord's
 * modifier (Ctrl then S: {@code key:CONTROL+key:S}; any pad button as a modifier, D71); a button released alone is
 * bound alone; an axis pushed past half is bound at once (for a 2D action, a gamepad stick's pair). A gamepad,
 * joystick or XR controller is recorded with the binding (D72: only when the player captures); keyboards and mice are
 * not told apart. Escape cancels unless {@link #cancelOn} says otherwise. Controls already held when it starts count
 * only once released.
 *
 * <pre>{@code
 * Capture capture = input.capture(player, JUMP);
 * // frames later:
 * if (capture.done() && !capture.cancelled()) {
 *     List<Conflict> conflicts = input.conflicts(player, JUMP, capture.binding());
 *     input.rebind(player, JUMP, 0, capture.binding());   // saved with the settings
 * }
 * }</pre>
 */
public final class Capture {

    private final Player player;
    private final Action action;
    private final Map<Device, boolean[]> armed = new IdentityHashMap<>();
    private Control cancel = Controls.key(Key.ESCAPE);
    private Device firstDevice;
    private int first = -1;
    private boolean done;
    private boolean cancelled;
    private Binding binding;
    private Control control;

    @AllowAllocation("starting a capture")
    Capture(Player player, Action action) {
        this.player = player;
        this.action = action;
        float release = player.input().releaseThreshold();
        for (Device[] devices : player.byClass) {
            for (Device device : devices) { // controls up now count when pushed; those held, once released
                boolean[] ready = new boolean[device.type().count()];
                for (int i = 0; i < ready.length; i++) {
                    ready[i] = Math.abs(device.values[i]) < release;
                }
                armed.put(device, ready);
            }
        }
    }

    /** The player listened to. */
    public Player player() {
        return player;
    }

    /** The action being rebound. */
    public Action action() {
        return action;
    }

    /** Makes {@code control} cancel it (Escape unless set); null: nothing cancels but {@link #cancel()}. */
    public Capture cancelOn(Control control) {
        this.cancel = control;
        return this;
    }

    /** Whether it is over: captured or cancelled. */
    public boolean done() {
        return done;
    }

    /** Whether it was cancelled. */
    public boolean cancelled() {
        return cancelled;
    }

    /** The binding captured, or null. */
    public Binding binding() {
        return binding;
    }

    /** The main control captured (for composing an adapter's parts: one capture per part), or null. */
    public Control control() {
        return control;
    }

    /** Stops listening without a binding. */
    public void cancel() {
        if (!done) {
            cancelled = true;
            finish(null, null);
        }
    }

    /** Looks at the player's devices this frame. */
    @AllowAllocation("capturing a binding: once per press, not every frame")
    void sample(Player of) {
        Input input = player.input();
        float press = input.pressThreshold();
        float release = input.releaseThreshold();
        for (Device[] devices : of.byClass) {
            for (Device device : devices) {
                boolean[] ready = armed.computeIfAbsent(device, d -> new boolean[d.type().count()]);
                DeviceClass type = device.type();
                for (int i = 0; i < type.count(); i++) {
                    ControlKind kind = type.kind(i);
                    if (kind == ControlKind.POSE || type == DeviceClass.MOUSE && i < DeviceClass.WHEEL_X
                        && kind == ControlKind.DELTA || type == DeviceClass.JOYSTICK && i >= DeviceClass.JOY_BALL
                        || type == DeviceClass.KEYBOARD && i >= DeviceClass.KEY_SHIFT
                        || type == DeviceClass.GAMEPAD && i >= DeviceClass.PAD_STICKS) {
                        continue; // poses, pointer movement and made controls are not bound by capture
                    }
                    float v = Math.abs(device.values[i]);
                    float threshold = kind == ControlKind.DELTA ? 1 : press;
                    if (device == firstDevice && i == first && v < release) {
                        bindAlone(device, i); // the first control released alone
                        return;
                    }
                    if (v < release) {
                        ready[i] = true;
                    } else if (ready[i] && v >= threshold) {
                        ready[i] = false;
                        if (pressed(device, i)) {
                            return;
                        }
                    }
                }
            }
        }
    }

    /** A control went down: returns whether that finished the capture. */
    private boolean pressed(Device device, int i) {
        Control pushed = device.type().control(i);
        if (pushed == cancel && first < 0) {
            cancelled = true;
            finish(null, null);
            return true;
        }
        ControlKind kind = pushed.kind();
        if (first < 0 && (kind == ControlKind.AXIS || kind == ControlKind.HALF_AXIS || kind == ControlKind.DELTA)) {
            bindAxis(device, i);
            return true;
        }
        if (first < 0) {
            firstDevice = device;
            first = i;
            return false;
        }
        if (device.type() == firstDevice.type()) {
            Binding chord = Binding.of(pushed).with(firstDevice.type().control(first));
            finish(recorded(chord, device), pushed);
            return true;
        }
        return false;
    }

    private void bindAlone(Device device, int i) {
        Control alone = device.type().control(i);
        finish(recorded(Binding.of(alone), device), alone);
    }

    private void bindAxis(Device device, int i) {
        Control axis = device.type().control(i);
        Binding made = Binding.of(axis);
        if (action instanceof Axis2Action && device.type() == DeviceClass.GAMEPAD
            && i >= DeviceClass.PAD_AXIS && i < DeviceClass.PAD_AXIS + 4) {
            boolean right = i >= DeviceClass.PAD_AXIS + GamepadAxis.RIGHT_X.ordinal();
            made = Binding.stick(right);
        }
        finish(recorded(made, device), axis);
    }

    private static Binding recorded(Binding made, Device device) {
        return device.type().distinct() && !device.identity().unknown() ? made.device(device.identity()) : made;
    }

    private void finish(Binding captured, Control main) {
        done = true;
        binding = captured;
        control = main;
        player.capture = null;
    }
}
