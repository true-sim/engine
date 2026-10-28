package com.truesim.engine.render;

import com.truesim.common.math.Maths;

/**
 * A camera at the origin, as the renderer draws: instances are camera-relative, so precision does not depend on where
 * in a large world the camera is. Builds the view-projection matrix (reverse depth with an infinite far plane: depth 1
 * at the near plane, falling towards 0 far away), the frustum planes and the projection scale for screen-space error.
 * Matrices are column-major float arrays, as shaders read them.
 */
public final class Camera {

    private final float[] viewProjection = new float[16];
    private final float[] planes = new float[24];
    private final float[] inverse = new float[16];
    private final double[] matrix = new double[16];
    private final double[] inverted = new double[16];
    private float projectionScale;
    private boolean orthographic;

    /**
     * Looks along ({@code fx}, {@code fy}, {@code fz}) with ({@code ux}, {@code uy}, {@code uz}) roughly up, with a
     * vertical field of view {@code fovY} (radians), a viewport of {@code width} by {@code height} pixels and the near
     * plane at {@code near}. Allocates nothing.
     */
    public Camera set(double fx, double fy, double fz, double ux, double uy, double uz, double fovY, int width,
        int height, double near) {
        // View: rows are right, up and back (the camera looks down -Z).
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        double bx = -fx / fl;
        double by = -fy / fl;
        double bz = -fz / fl;
        double rx = uy * bz - uz * by;
        double ry = uz * bx - ux * bz;
        double rz = ux * by - uy * bx;
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= rl;
        ry /= rl;
        rz /= rl;
        double tx = by * rz - bz * ry;
        double ty = bz * rx - bx * rz;
        double tz = bx * ry - by * rx;
        // Projection: x and y scaled by the focal length (y flipped: Vulkan's y points down), w = distance,
        // z = near, so depth = near / distance.
        double f = 1 / Maths.tan(fovY / 2);
        double sx = f * height / width;
        double sy = -f;
        // viewProjection = P * V, column-major: element (row, column) at column * 4 + row.
        set(0, sx * rx, sx * ry, sx * rz, 0);
        set(1, sy * tx, sy * ty, sy * tz, 0);
        set(2, 0, 0, 0, near);
        set(3, -bx, -by, -bz, 0);
        projectionScale = (float) (height * f / 2);
        // Frustum planes from the rows (Gribb and Hartmann): left, right, top, bottom from w +- x, w +- y; near from z
        // (depth <= 1 means w >= near); no far plane.
        plane(0, 3, 0, 1);
        plane(1, 3, 0, -1);
        plane(2, 3, 1, 1);
        plane(3, 3, 1, -1);
        plane(4, 3, 2, -1); // w - z >= 0: distance >= near
        plane(5, 3, 2, -1); // no far plane: repeat the near one
        orthographic = false;
        return this;
    }

    /**
     * An orthographic view, e.g. a directional light's for a shadow map: from ({@code ex}, {@code ey}, {@code ez})
     * (camera-relative, like instances) along ({@code fx}, {@code fy}, {@code fz}), ({@code ux}, {@code uy},
     * {@code uz}) roughly up, seeing {@code halfWidth} and {@code halfHeight} to each side and {@code depth} ahead, on
     * a {@code width} by {@code height} viewport. Reverse depth: 1 at the eye, 0 at {@code depth}. Projected errors
     * do not shrink with distance: the scale is pixels per unit. Allocates nothing.
     */
    public Camera setOrthographic(double ex, double ey, double ez, double fx, double fy, double fz, double ux,
        double uy, double uz, double halfWidth, double halfHeight, double depth, int width, int height) {
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        double bx = -fx / fl;
        double by = -fy / fl;
        double bz = -fz / fl;
        double rx = uy * bz - uz * by;
        double ry = uz * bx - ux * bz;
        double rz = ux * by - uy * bx;
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= rl;
        ry /= rl;
        rz /= rl;
        double tx = by * rz - bz * ry;
        double ty = bz * rx - bx * rz;
        double tz = bx * ry - by * rx;
        double re = rx * ex + ry * ey + rz * ez;
        double te = tx * ex + ty * ey + tz * ez;
        double be = bx * ex + by * ey + bz * ez;
        // x and y over the half extents (y flipped), depth 1 + (back . (p - eye)) / depth, w 1.
        set(0, rx / halfWidth, ry / halfWidth, rz / halfWidth, -re / halfWidth);
        set(1, -tx / halfHeight, -ty / halfHeight, -tz / halfHeight, te / halfHeight);
        set(2, bx / depth, by / depth, bz / depth, 1 - be / depth);
        set(3, 0, 0, 0, 1);
        projectionScale = (float) (height / (2 * halfHeight));
        plane(0, 3, 0, 1);
        plane(1, 3, 0, -1);
        plane(2, 3, 1, 1);
        plane(3, 3, 1, -1);
        plane(4, 3, 2, -1); // depth <= 1: not behind the eye
        plane(5, 2, 3, 0); // depth >= 0: not past the far end
        orthographic = true;
        return this;
    }

    private void set(int row, double c0, double c1, double c2, double c3) {
        viewProjection[row] = (float) c0;
        viewProjection[4 + row] = (float) c1;
        viewProjection[8 + row] = (float) c2;
        viewProjection[12 + row] = (float) c3;
    }

    /** Plane {@code index} as row {@code a} + {@code sign} * row {@code b}, normalized. */
    private void plane(int index, int a, int b, double sign) {
        double nx = viewProjection[a] + sign * viewProjection[b];
        double ny = viewProjection[4 + a] + sign * viewProjection[4 + b];
        double nz = viewProjection[8 + a] + sign * viewProjection[8 + b];
        double d = viewProjection[12 + a] + sign * viewProjection[12 + b];
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        planes[4 * index] = (float) (nx / length);
        planes[4 * index + 1] = (float) (ny / length);
        planes[4 * index + 2] = (float) (nz / length);
        planes[4 * index + 3] = (float) (d / length);
    }

    /** The view-projection matrix, column-major. */
    public float[] viewProjection() {
        return viewProjection;
    }

    /** The frustum's planes (normal x, y, z, distance), normals inward. */
    public float[] planes() {
        return planes;
    }

    /** Whether the view is orthographic ({@link #setOrthographic}). */
    public boolean orthographic() {
        return orthographic;
    }

    /**
     * The inverse of the view-projection matrix, column-major: from clip space (x, y, depth, 1) back to camera-relative
     * positions, after dividing by w. Allocates nothing.
     */
    public float[] inverseViewProjection() {
        double[] m = matrix;
        for (int i = 0; i < 16; i++) {
            m[i] = viewProjection[i];
        }
        double[] inv = inverted;
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14]
            + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14]
            - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13]
            + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13]
            - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14]
            - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14]
            + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13]
            - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13]
            + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14]
            + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14]
            - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13]
            + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13]
            - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10]
            - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10]
            + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9]
            - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9]
            + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
        double det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
        for (int i = 0; i < 16; i++) {
            inverse[i] = (float) (inv[i] / det);
        }
        return inverse;
    }

    /** Pixels a unit covers at distance 1: projected errors are error * scale / distance. */
    public float projectionScale() {
        return projectionScale;
    }
}
