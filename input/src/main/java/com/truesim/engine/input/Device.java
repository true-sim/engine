package com.truesim.engine.input;

import java.util.Arrays;

/**
 * A device as input sees it: a keyboard (all keyboards together, D72), the mouse (all mice), a gamepad, a joystick, an
 * XR controller. Its controls' values are taken once per frame, at its start (D59), and can be read directly
 * ({@link #value}, D56) for debug keys and quick hacks; actions are what is rebound, saved and shown. A device belongs
 * to a player ({@link #player()}); when unplugged it is reported and kept, and plugging it back returns it to the same
 * player (D73).
 *
 * <p>The source feeding it ({@link InputSource}, or the desktop's events) writes between frames with {@link #set},
 * {@link #press}, {@link #move} and {@link #pose(int, double, double, double, float, float, float, float)}: a press and
 * release within one frame still reads as down for that frame.
 */
public final class Device {

    private final DeviceClass type;
    private final int platformId;
    private final DeviceIdentity identity;
    private final String name;
    private final InputSource source;
    final float[] values;
    private final float[] pending;
    private final boolean[] tapped;
    private final double[] positions;
    private final float[] orientations;
    private final boolean[] posed;
    private final boolean[] posedPending;
    private final double[] pendingPositions;
    private final float[] pendingOrientations;
    boolean connected = true;
    Player player;
    long activeFrame = -1;

    Device(DeviceClass type, int platformId, DeviceIdentity identity, String name, InputSource source) {
        this.type = type;
        this.platformId = platformId;
        this.identity = identity;
        this.name = name;
        this.source = source;
        int n = type.count();
        values = new float[n];
        pending = new float[n];
        tapped = new boolean[n];
        int poses = 0;
        for (int i = 0; i < n; i++) {
            poses += type.kind(i) == ControlKind.POSE ? 1 : 0;
        }
        positions = new double[poses == 0 ? 0 : 3 * n];
        orientations = new float[poses == 0 ? 0 : 4 * n];
        pendingPositions = new double[positions.length];
        pendingOrientations = new float[orientations.length];
        posed = new boolean[poses == 0 ? 0 : n];
        posedPending = new boolean[posed.length];
    }

    /** Its kind. */
    public DeviceClass type() {
        return type;
    }

    /** The id the platform's events use for it (0 for the keyboard and the mouse); a new one after replugging. */
    public int platformId() {
        return platformId;
    }

    /** What tells it again after replugging. */
    public DeviceIdentity identity() {
        return identity;
    }

    /** Its name, as the system reports it. */
    public String name() {
        return name;
    }

    /** Whether it is plugged in. */
    public boolean connected() {
        return connected;
    }

    /** The player it belongs to, or null. */
    public Player player() {
        return player;
    }

    InputSource source() {
        return source;
    }

    /** {@code control}'s value this frame: 0 or 1 for buttons, -1 to 1 for axes, movement for deltas (D56). */
    public float value(Control control) {
        return control.type() == type ? values[control.index()] : 0;
    }

    /** Whether {@code control} is down this frame: at least half pressed. */
    public boolean down(Control control) {
        return Math.abs(value(control)) >= 0.5f;
    }

    /**
     * {@code control}'s pose this frame, into {@code position} (3) and {@code orientation} (a quaternion, x y z w);
     * false, and nothing written, when it is not tracked.
     */
    public boolean pose(Control control, double[] position, float[] orientation) {
        int i = control.index();
        if (control.type() != type || posed.length == 0 || !posed[i]) {
            return false;
        }
        System.arraycopy(positions, 3 * i, position, 0, 3);
        System.arraycopy(orientations, 4 * i, orientation, 0, 4);
        return true;
    }

    boolean posed(int i) {
        return posed.length > 0 && posed[i];
    }

    double position(int i, int axis) {
        return positions[3 * i + axis];
    }

    float orientation(int i, int axis) {
        return orientations[4 * i + axis];
    }

    // ---------------------------------------------------------------- written by its source

    /** Sets control {@code index} to {@code value} (an axis, a held state) for the next frame. */
    public void set(int index, float value) {
        pending[index] = value;
    }

    /** Control {@code index} (a button) went down or up: a press and release before the next frame reads as down. */
    public void press(int index, boolean down) {
        pending[index] = down ? 1 : 0;
        tapped[index] |= down;
    }

    /** Adds {@code delta} to control {@code index} (a delta: mouse movement, the wheel) for the next frame. */
    public void move(int index, float delta) {
        pending[index] += delta;
    }

    /** Sets control {@code index}'s pose for the next frame. */
    public void pose(int index, double x, double y, double z, float qx, float qy, float qz, float qw) {
        pendingPositions[3 * index] = x;
        pendingPositions[3 * index + 1] = y;
        pendingPositions[3 * index + 2] = z;
        pendingOrientations[4 * index] = qx;
        pendingOrientations[4 * index + 1] = qy;
        pendingOrientations[4 * index + 2] = qz;
        pendingOrientations[4 * index + 3] = qw;
        posedPending[index] = true;
    }

    /** Control {@code index}'s pose is not tracked from the next frame. */
    public void lose(int index) {
        posedPending[index] = false;
    }

    /** Lets go of every control: all up, nothing moved (focus lost, unplugged). */
    void clear() {
        Arrays.fill(pending, 0);
        Arrays.fill(tapped, false);
        Arrays.fill(values, 0);
        Arrays.fill(posedPending, false);
    }

    /**
     * Takes the values written since the last frame as this frame's; returns whether a control was pushed (from
     * under half to over, or moved by a few pixels) for the last device used (D62).
     */
    boolean latch() {
        boolean active = false;
        for (int i = 0; i < values.length; i++) {
            float before = values[i];
            float now = pending[i];
            ControlKind kind = type.kind(i);
            if (kind == ControlKind.DELTA) {
                pending[i] = 0;
                active |= Math.abs(now) >= 3;
            } else if (tapped[i] && now == 0) {
                now = 1; // pressed and released since the last frame: down for this one
            }
            tapped[i] = false;
            values[i] = now;
            active |= kind != ControlKind.DELTA && Math.abs(now) >= 0.5f && Math.abs(before) < 0.5f;
        }
        type.derive(values);
        if (posed.length > 0) {
            System.arraycopy(posedPending, 0, posed, 0, posed.length);
            System.arraycopy(pendingPositions, 0, positions, 0, positions.length);
            System.arraycopy(pendingOrientations, 0, orientations, 0, orientations.length);
        }
        return active;
    }

    @Override
    public String toString() {
        return type.prefix() + " " + platformId + " " + name;
    }
}
