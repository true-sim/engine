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
    private float projectionScale;

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

    /** Pixels a unit covers at distance 1: projected errors are error * scale / distance. */
    public float projectionScale() {
        return projectionScale;
    }
}
