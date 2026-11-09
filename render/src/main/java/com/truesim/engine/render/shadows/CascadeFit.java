package com.truesim.engine.render.shadows;

import com.truesim.common.math.Maths;
import com.truesim.engine.render.Camera;

/**
 * Fits a directional light's cascades to a camera's view: practical splits (a blend of logarithmic and uniform,
 * {@code lambda}), each cascade the sphere around its slice of the frustum ({@link #fit}), then placed at a size
 * ({@link #place}): an orthographic light camera at the slice's center snapped to whole texels of the world along the
 * light's right and up, so its map neither grows nor shimmers as the camera turns and moves. For
 * {@link Shadows}' cascades. Allocates nothing once made.
 */
final class CascadeFit {

    private final float[] splits;
    private final double[] radius;
    private final double[] center; // camera-relative, 3 per cascade
    private final double[] corner = new double[3];
    private double x;
    private double y;
    private double z;
    // the light's axes: direction, back (towards the light), right, up
    private double dx;
    private double dy;
    private double dz;
    private double ux;
    private double uy;
    private double rx;
    private double ry;
    private double rz;
    private double tx;
    private double ty;
    private double tz;
    private int cascades;

    CascadeFit(int maxCascades) {
        splits = new float[maxCascades + 1];
        radius = new double[maxCascades];
        center = new double[3 * maxCascades];
    }

    /**
     * Fits {@code cascades} slices of {@code camera}'s view up to {@code maxDistance} for a light shining along
     * ({@code dx}, {@code dy}, {@code dz}) (unit): their splits and spheres, each reaching {@code margin} beyond.
     */
    void fit(Camera camera, int cascades, double maxDistance, double lambda, double margin, double dx, double dy,
        double dz) {
        this.cascades = cascades;
        x = camera.position(0);
        y = camera.position(1);
        z = camera.position(2);
        float[] viewProjection = camera.viewProjection();
        // The splits and corners take depth as near / distance: an infinite reverse-depth perspective, as Camera.set
        // makes; another projection would fit the cascades wrongly without a word.
        // (Its depth row is then (0, 0, 0, near) whatever the camera's turn: column-major, entries 2, 6, 10 and 14.)
        if (camera.orthographic() || viewProjection[2] != 0 || viewProjection[6] != 0 || viewProjection[10] != 0) {
            throw new IllegalStateException("Cascades fit a camera of infinite reverse-depth perspective (Camera.set)");
        }
        double near = viewProjection[14]; // reverse depth: depth = near / distance
        splits[0] = (float) near;
        double far = Math.max(maxDistance, near * 1.01);
        for (int i = 1; i <= cascades; i++) {
            double t = (double) i / cascades;
            double logarithmic = near * Maths.pow(far / near, t);
            double uniform = near + (far - near) * t;
            splits[i] = (float) (lambda * logarithmic + (1 - lambda) * uniform);
        }
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        // The light's axes: back (towards the light), right and up, as Camera.setOrthographic makes them.
        ux = Math.abs(dy) < 0.99 ? 0 : 1;
        uy = Math.abs(dy) < 0.99 ? 1 : 0;
        double bx = -dx;
        double by = -dy;
        double bz = -dz;
        rx = uy * bz;
        ry = -ux * bz;
        rz = ux * by - uy * bx;
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= rl;
        ry /= rl;
        rz /= rl;
        tx = by * rz - bz * ry;
        ty = bz * rx - bx * rz;
        tz = bx * ry - by * rx;
        float[] inverse = camera.inverseViewProjection();
        for (int i = 0; i < cascades; i++) {
            // The sphere around the frustum's slice: its corners averaged, the farthest corner's distance. From a tenth
            // of the slice before on: lights.slang's shadowFor dithers the last tenth of a cascade into the next.
            double from = i == 0 ? splits[0] : splits[i] - 0.1 * (splits[i] - splits[i - 1]);
            double cx = 0;
            double cy = 0;
            double cz = 0;
            for (int c = 0; c < 8; c++) {
                corner(inverse, near, (c >> 2) == 0 ? from : splits[i + 1], (c & 1) * 2 - 1, (c >> 1 & 1) * 2 - 1);
                cx += corner[0] / 8;
                cy += corner[1] / 8;
                cz += corner[2] / 8;
            }
            double r = 0;
            for (int c = 0; c < 8; c++) {
                corner(inverse, near, (c >> 2) == 0 ? from : splits[i + 1], (c & 1) * 2 - 1, (c >> 1 & 1) * 2 - 1);
                double ex = corner[0] - cx;
                double ey = corner[1] - cy;
                double ez = corner[2] - cz;
                r = Math.max(r, Math.sqrt(ex * ex + ey * ey + ez * ez));
            }
            radius[i] = Math.ceil((r + margin) * 16) / 16; // steady as the camera turns
            center[3 * i] = cx;
            center[3 * i + 1] = cy;
            center[3 * i + 2] = cz;
        }
    }

    /** Where cascade {@code i} ends ({@code i} 0 to cascades - 1), in view depth; {@code split(-1)}: the near plane. */
    float split(int i) {
        return splits[i + 1];
    }

    /** The radius of cascade {@code i}'s sphere, as last fitted. */
    double radius(int i) {
        return radius[i];
    }

    /**
     * Places cascade {@code i} at {@code size} texels per side into {@code out}: centered on its slice, snapped to
     * whole texels of the world, its depth range reaching {@code casters} units behind it towards the light (casters
     * farther are flattened onto its near plane, {@link Camera#openNear}, or left out). Returns its texel's size in the
     * world; its depth (in world units) is {@code 2 radius + casters}.
     */
    double place(int i, int size, double casters, Camera out) {
        double r = radius[i];
        double texel = 2 * r / size;
        double wx = x + center[3 * i];
        double wy = y + center[3 * i + 1];
        double wz = z + center[3 * i + 2];
        double alongRight = rx * wx + ry * wy + rz * wz;
        double alongUp = tx * wx + ty * wy + tz * wz;
        double shiftRight = Math.floor(alongRight / texel) * texel - alongRight;
        double shiftUp = Math.floor(alongUp / texel) * texel - alongUp;
        double cx = center[3 * i] + rx * shiftRight + tx * shiftUp;
        double cy = center[3 * i + 1] + ry * shiftRight + ty * shiftUp;
        double cz = center[3 * i + 2] + rz * shiftRight + tz * shiftUp;
        double back = r + casters;
        double depth = back + r;
        // At the slice's snapped center in the world, looking from back along the light.
        out.position(x + cx, y + cy, z + cz).setOrthographic(-dx * back, -dy * back, -dz * back, dx, dy, dz, ux, uy,
            0, r, r, depth, size, size);
        return texel;
    }

    /** The cascades last fitted. */
    int cascades() {
        return cascades;
    }

    /** Corner ({@code ndcX}, {@code ndcY}) of the view at {@code distance}, camera-relative, into {@link #corner}. */
    private void corner(float[] inverse, double near, double distance, int ndcX, int ndcY) {
        double ndcZ = near / distance;
        double w = inverse[3] * ndcX + inverse[7] * ndcY + inverse[11] * ndcZ + inverse[15];
        for (int k = 0; k < 3; k++) {
            corner[k] = (inverse[k] * ndcX + inverse[4 + k] * ndcY + inverse[8 + k] * ndcZ + inverse[12 + k]) / w;
        }
    }
}
