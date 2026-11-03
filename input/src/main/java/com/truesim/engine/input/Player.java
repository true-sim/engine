package com.truesim.engine.input;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A local player (application-api.md D63): the devices they hold, their bindings, and their actions' state. With one
 * player, that player reads every device; with more, each reads their own ({@link #assign}), and a device plugged back
 * returns to the player who had it (D73). Read actions here:
 *
 * <pre>{@code
 * Player one = input.player(0);
 * if (one.pressed(JUMP)) { ... }            // pressed since the last frame, or in a fixed step since the last step
 * float x = one.x(MOVE), y = one.y(MOVE);   // this frame's value, through the action's filters
 * }</pre>
 *
 * <p>Edges are counted (D60): {@link #pressed} in the frame's update is true when the action went down since the last
 * frame; in a fixed step ({@code FIXED}), when it went down since that world's last step, so a press is seen once by
 * each world however many steps a frame runs, and a press in a frame without a step is seen by the next step. Values and
 * states are those taken at the frame's start (D59). Reading allocates nothing.
 */
public final class Player {

    private final Input input;
    final int index;
    // The devices it reads, all of a kind together, per kind of device.
    Device[][] byClass = new Device[0][];
    Device lastDevice;
    // Its bindings, per action, and the actions in the order they are worked out (sources first, D80).
    Bound[][] bounds = new Bound[0][];
    int[] order = new int[0];
    // Its actions' state, per action.
    boolean[] down = new boolean[0];
    float[] values = new float[0];        // 3 per action
    double[] positions = new double[0];   // 3 per pose action
    float[] orientations = new float[0];  // 4 per pose action
    boolean[] posed = new boolean[0];
    long[] evaluated = new long[0];
    // This frame: controls taken by a context above, and by a chord (with its specificity).
    long[][] consumed = new long[0][];
    long[][] takenStamp = new long[0][];
    int[][] takenLevel = new int[0][];
    long stamp;
    Capture capture;
    private final float[] sum = new float[3];

    Player(Input input, int index) {
        this.input = input;
        this.index = index;
    }

    Input input() {
        return input;
    }

    /** Its number, from 0. */
    public int index() {
        return index;
    }

    // ---------------------------------------------------------------- reading actions

    /** Whether {@code action} is down this frame. */
    public boolean down(BinaryAction action) {
        int a = input.slot(action);
        return a >= 0 && a < down.length && down[a];
    }

    /** Whether {@code action} went down since the last frame, or in a fixed step since the world's last step. */
    public boolean pressed(BinaryAction action) {
        return presses(action) > 0;
    }

    /** Whether {@code action} went up since the last frame, or in a fixed step since the world's last step. */
    public boolean released(BinaryAction action) {
        return releases(action) > 0;
    }

    /** How often {@code action} went down since the last frame (or step): taps faster than steps all count. */
    public int presses(BinaryAction action) {
        int a = input.slot(action);
        return a < 0 ? 0 : input.presses(index, a);
    }

    /** How often {@code action} went up since the last frame (or step). */
    public int releases(BinaryAction action) {
        int a = input.slot(action);
        return a < 0 ? 0 : input.releases(index, a);
    }

    /** {@code action}'s value this frame, through its filters. */
    public float value(Axis1Action action) {
        return input.component(this, action, 0);
    }

    /** {@code action}'s x this frame, through its filters. */
    public float x(Axis2Action action) {
        return input.component(this, action, 0);
    }

    /** {@code action}'s y this frame. */
    public float y(Axis2Action action) {
        return input.component(this, action, 1);
    }

    /** {@code action}'s value into {@code out} (x, y). */
    public float[] value(Axis2Action action, float[] out) {
        out[0] = x(action);
        out[1] = y(action);
        return out;
    }

    /** {@code action}'s x this frame, through its filters. */
    public float x(Axis3Action action) {
        return input.component(this, action, 0);
    }

    /** {@code action}'s y this frame. */
    public float y(Axis3Action action) {
        return input.component(this, action, 1);
    }

    /** {@code action}'s z this frame. */
    public float z(Axis3Action action) {
        return input.component(this, action, 2);
    }

    /** {@code action}'s value into {@code out} (x, y, z). */
    public float[] value(Axis3Action action, float[] out) {
        out[0] = x(action);
        out[1] = y(action);
        out[2] = z(action);
        return out;
    }

    /**
     * {@code action}'s pose this frame into {@code position} (3) and {@code orientation} (x y z w); false, and nothing
     * written, when nothing bound to it is tracked.
     */
    public boolean pose(PoseAction action, double[] position, float[] orientation) {
        int a = input.slot(action);
        if (a < 0 || a >= posed.length || !posed[a]) {
            return false;
        }
        System.arraycopy(positions, 3 * a, position, 0, 3);
        System.arraycopy(orientations, 4 * a, orientation, 0, 4);
        return true;
    }

    float raw(int action, int component) {
        return values[3 * action + component];
    }

    // ---------------------------------------------------------------- devices, glyphs, rumble

    /** The devices it reads: with one player every device, else its own. Allocates the list. */
    @AllowAllocation("a snapshot")
    public List<Device> devices() {
        List<Device> all = new ArrayList<>();
        for (Device[] ofClass : byClass) {
            all.addAll(Arrays.asList(ofClass));
        }
        return all;
    }

    /** Gives {@code device} to this player (D63), from whoever had it. */
    public Player assign(Device device) {
        input.assign(device, this);
        return this;
    }

    /** The device it used last (D62): what its glyphs show. Null before any. */
    public Device lastDevice() {
        return lastDevice;
    }

    /**
     * The binding to show for {@code action} (D62): its first binding on the kind of device used last (keyboard and
     * mouse counting as one), else its first; null if it has none. Glyph art is the game's.
     */
    public Binding glyph(Action action) {
        int a = input.slot(action);
        if (a < 0 || a >= bounds.length || bounds[a].length == 0) {
            return null;
        }
        Bound[] options = bounds[a];
        if (lastDevice != null) {
            for (Bound b : options) {
                if (b.readsControls() && sameFamily(input.deviceClass(b.inputClass[0]), lastDevice.type())) {
                    return b.binding;
                }
            }
        }
        return options[0].binding;
    }

    private static boolean sameFamily(DeviceClass a, DeviceClass b) {
        boolean desktopA = a == DeviceClass.KEYBOARD || a == DeviceClass.MOUSE;
        boolean desktopB = b == DeviceClass.KEYBOARD || b == DeviceClass.MOUSE;
        return a == b || desktopA && desktopB;
    }

    /**
     * Rumbles every device of this player that can (D66): {@code low} and {@code high} frequency strengths from 0 to
     * 1, for {@code millis}; 0 stops. Returns whether one did.
     */
    public boolean rumble(float low, float high, int millis) {
        boolean any = false;
        for (Device[] ofClass : byClass) {
            for (Device device : ofClass) {
                if (device.type().distinct() && device.source() != null) {
                    any |= device.source().rumble(device, low, high, millis);
                }
            }
        }
        return any;
    }

    /** The bindings {@code action} has for this player now. Allocates the list. */
    @AllowAllocation("a snapshot")
    public List<Binding> bindings(Action action) {
        return input.bindings(this, action);
    }

    // ---------------------------------------------------------------- each frame

    /** Sized for {@code actions} actions and {@code classes} kinds of device; keeps what fits. */
    @AllowAllocation("actions or kinds of device added")
    void resize(int actions, DeviceClass[] classes) {
        down = Arrays.copyOf(down, actions);
        values = Arrays.copyOf(values, 3 * actions);
        positions = Arrays.copyOf(positions, 3 * actions);
        orientations = Arrays.copyOf(orientations, 4 * actions);
        posed = Arrays.copyOf(posed, actions);
        evaluated = Arrays.copyOf(evaluated, actions);
        if (consumed.length != classes.length) {
            consumed = new long[classes.length][];
            takenStamp = new long[classes.length][];
            takenLevel = new int[classes.length][];
            for (int c = 0; c < classes.length; c++) {
                consumed[c] = new long[classes[c].count()];
                takenStamp[c] = new long[classes[c].count()];
                takenLevel[c] = new int[classes[c].count()];
            }
            stamp = 0;
        }
    }

    /** Resets the filters of its bindings, their hysteresis and timers (D86); toggles stay. */
    void resetFilters() {
        for (Bound[] ofAction : bounds) {
            for (Bound b : ofAction) {
                b.reset();
            }
        }
    }

    /** Works out its actions for this frame from its devices: the contexts' levels top down, then values in order. */
    void evaluate(Input.Level[] levels, double now, double seconds) {
        stamp++;
        if (capture != null) {
            capture.sample(this); // its actions see nothing while it listens, nor in the frame it hears
            for (int a : order) {
                idle(a);
            }
            return;
        }
        for (int l = 0; l < levels.length; l++) {
            Input.Level level = levels[l];
            int[] actions = level.actions();
            for (int a : actions) {
                evaluated[a] = stamp;
                for (Bound b : bounds[a]) {
                    read(b);
                }
            }
            if (level.blocking() || l == levels.length - 1) {
                break; // nothing under it reads what it takes
            }
            for (int a : actions) {
                for (Bound b : bounds[a]) {
                    consume(b);
                }
            }
            if (level.text()) {
                Arrays.fill(consumed[0], stamp); // the keyboard is the first kind of device
            }
        }
        for (int a : order) {
            if (evaluated[a] == stamp) {
                finish(a, now, seconds);
            } else {
                idle(a);
            }
        }
    }

    /** Reads {@code b}'s controls, those taken by a context above as 0; a chord held takes its controls (D69). */
    private void read(Bound b) {
        b.stamp = stamp;
        boolean held = true;
        float press = input.pressThreshold();
        DeviceIdentity only = b.binding.device();
        for (int m = 0; m < b.modifierCount; m++) {
            held &= control(b.modifierClass[m], b.modifierIndex[m], only) >= press;
        }
        b.modifiersHeld = held;
        for (int k = 0; k < b.inputCount; k++) {
            b.raw[k] = control(b.inputClass[k], b.inputIndex[k], only);
        }
        if (!b.readsControls() || b.dims == 0) {
            b.satisfied = false;
            return;
        }
        b.adapt();
        b.satisfied = held && b.magnitude(b.dims) >= input.releaseThreshold();
        if (b.satisfied && b.modifierCount > 0 && b.binary) { // a button chord; held-button-and-move is not one
            for (int k = 0; k < b.inputCount; k++) {
                take(b.inputClass[k], b.inputIndex[k], b.modifierCount);
            }
            for (int m = 0; m < b.modifierCount; m++) {
                take(b.modifierClass[m], b.modifierIndex[m], b.modifierCount);
            }
        }
    }

    private float control(int type, int index, DeviceIdentity only) {
        if (consumed[type][index] == stamp) {
            return 0;
        }
        float best = 0;
        for (Device device : byClass[type]) {
            if (only == null || only.matches(device.identity())) {
                float v = device.values[index];
                if (Math.abs(v) > Math.abs(best)) {
                    best = v;
                }
            }
        }
        return best;
    }

    private void take(int type, int index, int level) {
        takeOne(type, index, level);
        for (int partner : input.deviceClass(type).partners(index)) {
            takeOne(type, partner, level);
        }
    }

    private void takeOne(int type, int index, int level) {
        if (takenStamp[type][index] != stamp || takenLevel[type][index] < level) {
            takenStamp[type][index] = stamp;
            takenLevel[type][index] = level;
        }
    }

    /** Whether a chord more specific than {@code level} modifiers took the control this frame. */
    private boolean takenAbove(int type, int index, int level) {
        return takenStamp[type][index] == stamp && takenLevel[type][index] > level;
    }

    private void consume(Bound b) {
        for (int k = 0; k < b.inputCount; k++) {
            consumeOne(b.inputClass[k], b.inputIndex[k]);
        }
        for (int m = 0; m < b.modifierCount; m++) {
            consumeOne(b.modifierClass[m], b.modifierIndex[m]);
        }
    }

    private void consumeOne(int type, int index) {
        consumed[type][index] = stamp;
        for (int partner : input.deviceClass(type).partners(index)) {
            consumed[type][partner] = stamp;
        }
    }

    /** Action {@code a}'s value from its bindings, combined (D87); a binary one's edges counted (D60). */
    private void finish(int a, double now, double seconds) {
        Action action = input.action(a);
        Bound[] options = bounds[a];
        if (action instanceof PoseAction) {
            pose(a, options);
            return;
        }
        int dims = action.dimensions();
        boolean binary = dims == 0;
        boolean any = false;
        float best = -1;
        sum[0] = 0;
        sum[1] = 0;
        sum[2] = 0;
        Combine rule = action.combine();
        boolean found = false;
        for (Bound b : options) {
            value(b, now, seconds);
            if (binary) {
                any |= b.result;
                continue;
            }
            float magnitude = b.magnitude(dims);
            switch (rule) {
                case SUM -> {
                    for (int i = 0; i < dims; i++) {
                        sum[i] += b.value[i];
                    }
                }
                case FIRST -> {
                    if (!found && magnitude > 0) {
                        found = true;
                        System.arraycopy(b.value, 0, sum, 0, dims);
                    }
                }
                default -> {
                    if (magnitude > best) {
                        best = magnitude;
                        System.arraycopy(b.value, 0, sum, 0, dims);
                    }
                }
            }
        }
        if (binary) {
            set(a, any);
        } else {
            values[3 * a] = sum[0];
            values[3 * a + 1] = sum[1];
            values[3 * a + 2] = sum[2];
        }
    }

    /** {@code b}'s value this frame: its controls (less those a more specific chord took), adapted, filtered. */
    private void value(Bound b, double now, double seconds) {
        boolean held = b.modifiersHeld;
        b.value[0] = 0;
        b.value[1] = 0;
        b.value[2] = 0;
        if (b.readsControls()) {
            int level = b.modifierCount;
            for (int m = 0; m < b.modifierCount; m++) {
                held &= !takenAbove(b.modifierClass[m], b.modifierIndex[m], level);
            }
            for (int k = 0; k < b.inputCount; k++) {
                if (!held || takenAbove(b.inputClass[k], b.inputIndex[k], level)) {
                    b.raw[k] = 0;
                }
            }
            b.adapt();
        } else if (b.source >= 0 && held) {
            if (input.action(b.source) instanceof BinaryAction) {
                b.value[0] = down[b.source] ? 1 : 0;
            } else {
                System.arraycopy(values, 3 * b.source, b.value, 0, 3);
            }
        }
        int dims = Math.max(1, b.dims);
        for (int f = 0; f < b.filters.length; f++) {
            b.filters[f].apply(b.value, dims, b.params[f], b.state, b.stateAt[f], seconds);
        }
        if (!b.binary) {
            return;
        }
        float magnitude = b.magnitude(dims);
        b.latched = b.latched ? magnitude >= input.releaseThreshold() : magnitude >= input.pressThreshold();
        boolean under = b.latched;
        double hold = b.holdSeconds > 0 ? b.holdSeconds : input.holdTime();
        boolean out = switch (b.binding.timing()) {
            case PRESS -> under;
            case HOLD -> {
                if (under && !b.under) {
                    b.downSince = now;
                }
                yield under && now - b.downSince >= hold;
            }
            case TAP -> {
                if (under && !b.under) {
                    b.downSince = now;
                }
                yield !under && b.under && now - b.downSince < hold;
            }
        };
        b.under = under;
        if (b.binding.toggles()) {
            if (out && !b.out) {
                b.toggled = !b.toggled;
            }
            b.result = b.toggled;
        } else {
            b.result = out;
        }
        b.out = out;
    }

    private void pose(int a, Bound[] options) {
        posed[a] = false;
        for (Bound b : options) {
            if (!b.readsControls() || b.dims != 0 || !b.modifiersHeld) {
                continue;
            }
            int type = b.inputClass[0];
            int i = b.inputIndex[0];
            for (Device device : byClass[type]) {
                if (device.posed(i) && (b.binding.device() == null || b.binding.device().matches(device.identity()))) {
                    for (int axis = 0; axis < 3; axis++) {
                        positions[3 * a + axis] = device.position(i, axis);
                    }
                    for (int axis = 0; axis < 4; axis++) {
                        orientations[4 * a + axis] = device.orientation(i, axis);
                    }
                    posed[a] = true;
                    return;
                }
            }
        }
    }

    /** Action {@code a} read by no context this frame (its group off, a context hiding it): released, 0. */
    private void idle(int a) {
        set(a, false);
        values[3 * a] = 0;
        values[3 * a + 1] = 0;
        values[3 * a + 2] = 0;
        posed[a] = false;
    }

    private void set(int a, boolean now) {
        if (now != down[a]) {
            down[a] = now;
            input.edge(index, a, now);
        }
    }

    @Override
    @AllowAllocation("describing")
    public String toString() {
        return "player " + index;
    }
}
