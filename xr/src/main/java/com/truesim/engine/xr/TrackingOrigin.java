package com.truesim.engine.xr;

import com.truesim.common.math.Maths;

/**
 * Where the tracking space lies in the world: its origin's world position, its turn about the world's up (+y) and its
 * scale (world units per meter). Takes the runtime's poses, in the tracking space, into the world. Allocates nothing.
 */
final class TrackingOrigin {

    private final double[] position = new double[3];
    private double yaw;
    private double scale = 1;
    private double yawSin;
    private double yawCos = 1;
    private float turnSin; // the turn's quaternion: (0, sin(yaw / 2), 0, cos(yaw / 2))
    private float turnCos = 1;

    void position(double x, double y, double z) {
        position[0] = x;
        position[1] = y;
        position[2] = z;
    }

    void turn(double yaw, double scale) {
        if (!(scale > 0)) {
            throw new IllegalArgumentException("A tracking space's scale is positive: " + scale);
        }
        this.yaw = yaw;
        this.scale = scale;
        yawSin = Maths.sin(yaw);
        yawCos = Maths.cos(yaw);
        turnSin = (float) Maths.sin(yaw / 2);
        turnCos = (float) Maths.cos(yaw / 2);
    }

    double yaw() {
        return yaw;
    }

    double scale() {
        return scale;
    }

    /** Position ({@code x}, {@code y}, {@code z}) of the tracking space in the world, into {@code world}. */
    void toWorld(double x, double y, double z, double[] world) {
        world[0] = position[0] + scale * (yawCos * x + yawSin * z);
        world[1] = position[1] + scale * y;
        world[2] = position[2] + scale * (-yawSin * x + yawCos * z);
    }

    /** Orientation ({@code x}, {@code y}, {@code z}, {@code w}), a unit quaternion, in the world: into {@code world}. */
    void toWorld(float x, float y, float z, float w, float[] world, int at) {
        float s = turnSin; // (0, s, 0, c) * (x, y, z, w)
        float c = turnCos;
        world[at] = c * x + s * z;
        world[at + 1] = c * y + s * w;
        world[at + 2] = c * z - s * x;
        world[at + 3] = c * w - s * y;
    }
}
