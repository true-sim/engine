package com.truesim.engine.demo;

import com.truesim.common.math.Maths;

/**
 * Where a camera is and looks at over time, from a spec ({@code -Pcamera=...} of the showcase):
 *
 * <ul>
 *   <li>{@code orbit[:radius,height,degreesPerSecond,startDegrees]}: round the arena's middle, rising and falling,
 *       closing in and out a little, looking at the middle;</li>
 *   <li>{@code keys:t,x,y,z,tx,ty,tz;t,...}: through keyframes at times {@code t} (seconds), from (x, y, z) looking at
 *       (tx, ty, tz), smoothly (Catmull-Rom); heights {@code g+h} are h above the terrain there;</li>
 *   <li>{@code follow:fox,distance,height,side}: beside fox number {@code fox} of the crowd, {@code distance} behind
 *       the way it goes and {@code side} to its left, looking at it.</li>
 * </ul>
 */
final class CameraPath {

    private enum Kind { ORBIT, KEYS, FOLLOW }

    private final Kind kind;
    private final double cx;
    private final double cz;
    private final double[] values; // the orbit's or follow's numbers; keyframes: seven per key
    private final FoxCrowd crowd;
    private double headingX = 1; // a followed fox's smoothed heading
    private double headingZ;
    private double lastX = Double.NaN;
    private double lastZ;

    /** The path {@code spec} says, about an arena centered at ({@code cx}, {@code cz}) holding {@code crowd}. */
    CameraPath(String spec, double cx, double cz, FoxCrowd crowd) {
        this.cx = cx;
        this.cz = cz;
        this.crowd = crowd;
        String[] parts = spec.split(":", 2);
        switch (parts[0]) {
            case "orbit" -> {
                kind = Kind.ORBIT;
                values = numbers(parts.length > 1 ? parts[1] : "34,6,9,200", 4);
            }
            case "follow" -> {
                kind = Kind.FOLLOW;
                values = numbers(parts.length > 1 ? parts[1] : "17,4,1.6,2", 4);
            }
            case "keys" -> {
                kind = Kind.KEYS;
                String[] keys = parts[1].split(";");
                values = new double[7 * keys.length];
                for (int k = 0; k < keys.length; k++) {
                    String[] v = keys[k].split(",");
                    if (v.length != 7) {
                        throw new IllegalArgumentException("A keyframe is t,x,y,z,tx,ty,tz: " + keys[k]);
                    }
                    values[7 * k] = Double.parseDouble(v[0]);
                    for (int i = 0; i < 2; i++) {
                        double x = Double.parseDouble(v[1 + 3 * i]);
                        double z = Double.parseDouble(v[3 + 3 * i]);
                        values[7 * k + 1 + 3 * i] = x;
                        values[7 * k + 2 + 3 * i] = height(v[2 + 3 * i], x, z);
                        values[7 * k + 3 + 3 * i] = z;
                    }
                }
            }
            default -> throw new IllegalArgumentException("Not a camera path: " + spec
                + " (orbit[:radius,height,degreesPerSecond,startDegrees], keys:t,x,y,z,tx,ty,tz;..., "
                + "follow:fox,distance,height,side)");
        }
    }

    private static double[] numbers(String text, int count) {
        String[] v = text.split(",");
        if (v.length != count) {
            throw new IllegalArgumentException("Expected " + count + " numbers: " + text);
        }
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            out[i] = Double.parseDouble(v[i]);
        }
        return out;
    }

    /** A height: a number, or g+h for h above the terrain at (x, z). */
    static double height(String value, double x, double z) {
        return value.startsWith("g") ? DemoMeshes.height(x, z) + Double.parseDouble(value.substring(1))
            : Double.parseDouble(value);
    }

    /**
     * Where the camera is at {@code t} seconds into {@code eye} and where it looks into {@code target} (x, y, z each).
     * Following a fox, call it once a frame, in order: the heading is smoothed from frame to frame.
     */
    void at(double t, double[] eye, double[] target) {
        switch (kind) {
            case ORBIT -> {
                double a = Maths.toRadians(values[3] + values[2] * t);
                double r = values[0] * (1 + 0.12 * Maths.sin(0.23 * t));
                eye[0] = cx + r * Maths.cos(a);
                eye[2] = cz + r * Maths.sin(a);
                eye[1] = DemoMeshes.height(eye[0], eye[2]) + values[1] * (1 + 0.3 * Maths.sin(0.17 * t + 1));
                target[0] = cx;
                target[1] = DemoMeshes.height(cx, cz) + 5;
                target[2] = cz;
            }
            case FOLLOW -> {
                int fox = (int) values[0];
                double x = crowd.where(fox, 0);
                double z = crowd.where(fox, 1);
                if (!Double.isNaN(lastX) && (x != lastX || z != lastZ)) {
                    double dx = x - lastX;
                    double dz = z - lastZ;
                    double length = Maths.sqrt(dx * dx + dz * dz);
                    headingX += 0.08 * (dx / length - headingX);
                    headingZ += 0.08 * (dz / length - headingZ);
                    double h = Maths.sqrt(headingX * headingX + headingZ * headingZ);
                    headingX /= h;
                    headingZ /= h;
                }
                lastX = x;
                lastZ = z;
                eye[0] = x - values[1] * headingX - values[3] * headingZ;
                eye[2] = z - values[1] * headingZ + values[3] * headingX;
                eye[1] = DemoMeshes.height(eye[0], eye[2]) + values[2];
                target[0] = x + 0.5 * headingX;
                target[1] = DemoMeshes.height(x, z) + 0.6;
                target[2] = z + 0.5 * headingZ;
            }
            default -> keys(t, eye, target);
        }
    }

    /** Between the keyframes about {@code t}: Catmull-Rom through them, held at the ends. */
    private void keys(double t, double[] eye, double[] target) {
        int n = values.length / 7;
        int k = 0;
        while (k < n - 2 && t >= values[7 * (k + 1)]) {
            k++;
        }
        double t0 = values[7 * k];
        double t1 = values[7 * Math.min(k + 1, n - 1)];
        double u = t1 > t0 ? Math.clamp((t - t0) / (t1 - t0), 0.0, 1.0) : 0;
        int a = Math.max(k - 1, 0);
        int b = k;
        int c = Math.min(k + 1, n - 1);
        int d = Math.min(k + 2, n - 1);
        for (int i = 0; i < 6; i++) {
            double p0 = values[7 * a + 1 + i];
            double p1 = values[7 * b + 1 + i];
            double p2 = values[7 * c + 1 + i];
            double p3 = values[7 * d + 1 + i];
            double v = 0.5 * (2 * p1 + (p2 - p0) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u
                + (3 * p1 - p0 - 3 * p2 + p3) * u * u * u);
            if (i < 3) {
                eye[i] = v;
            } else {
                target[i - 3] = v;
            }
        }
    }
}
