package com.truesim.engine.interaction;

import com.truesim.common.math.Maths;
import com.truesim.common.math.TransformArray;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.scene.core.CameraLens;
import com.truesim.scene.core.NodeHandle;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A world seen through a camera over a rectangle of a window (application-api.md D130's view, for pointers): screen
 * pointers over it pick through the camera as it is when the frame starts, so a click picks what that frame shows,
 * with no frame of delay. The camera is the renderer's {@link Camera} or a camera node (D129: its {@link CameraLens}
 * and world transform). The rectangle follows a graph view's size ({@link #port}) or is set ({@link #rect}).
 * Allocates nothing.
 */
public final class InteractionView {

    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;

    private final InteractionWorld world;
    private final Camera camera;
    private final long cameraNode;
    private ViewPort port;
    private float x;
    private float y;
    private float width;
    private float height;
    private double reach = Double.NaN;

    InteractionView(InteractionWorld world, Camera camera, long cameraNode) {
        this.world = world;
        this.camera = camera;
        this.cameraNode = cameraNode;
    }

    /** Its world. */
    public InteractionWorld world() {
        return world;
    }

    /** The renderer's camera it picks through, or null (a camera node). */
    public Camera camera() {
        return camera;
    }

    /** The camera node it picks through, or {@link NodeHandle#NONE} (the renderer's camera). */
    public long cameraNode() {
        return cameraNode;
    }

    /** Covers {@code port}'s size from the window's corner, as it is each frame; returns it. */
    public InteractionView port(ViewPort value) {
        port = value;
        return this;
    }

    /** Covers {@code w x h} window pixels from {@code left, top} (instead of a port's size); returns it. */
    public InteractionView rect(float left, float top, float w, float h) {
        port = null;
        x = left;
        y = top;
        width = w;
        height = h;
        return this;
    }

    /** How far its rays reach (null: {@code scene.interaction.far}); returns it. */
    public InteractionView reach(double metres) {
        reach = metres;
        return this;
    }

    float left() {
        return port != null ? 0 : x;
    }

    float top() {
        return port != null ? 0 : y;
    }

    float width() {
        return port != null ? port.width() : width;
    }

    float height() {
        return port != null ? port.height() : height;
    }

    /** Whether window point {@code px, py} is on it. */
    public boolean contains(float px, float py) {
        float l = left();
        float t = top();
        return px >= l && py >= t && px < l + width() && py < t + height() && width() > 0 && height() > 0;
    }

    double reachOr(double fallback) {
        return Double.isNaN(reach) ? fallback : reach;
    }

    /**
     * The world ray through window point {@code px, py} (origin, then a unit direction) into {@code out[0..5]}, as the
     * camera is now; false if it has no camera to look through (a camera node without a lens, or gone).
     */
    public boolean ray(float px, float py, double[] out) {
        double nx = (px - left()) / width() * 2 - 1;
        double ny = 1 - (py - top()) / height() * 2;
        double aspect = width() / (double) height();
        double ox;
        double oy;
        double oz;
        double qx;
        double qy;
        double qz;
        double qw;
        boolean ortho;
        double tanHalf;
        double halfHeight;
        if (camera != null) {
            ox = camera.position(0) + camera.eye(0);
            oy = camera.position(1) + camera.eye(1);
            oz = camera.position(2) + camera.eye(2);
            qx = camera.rotation(0);
            qy = camera.rotation(1);
            qz = camera.rotation(2);
            qw = camera.rotation(3);
            ortho = camera.orthographic();
            tanHalf = Maths.tan(camera.fovY() / 2);
            halfHeight = camera.halfHeight();
        } else {
            CameraLens lens = world.graph().contains(cameraNode)
                ? world.graph().components().get(cameraNode, CameraLens.TYPE) : null;
            if (lens == null) {
                return false;
            }
            TransformArray transforms = world.graph().worldTransforms();
            MemorySegment s = transforms.segment();
            long o = NodeHandle.index(cameraNode) * transforms.stride();
            ox = s.get(DOUBLE, o);
            oy = s.get(DOUBLE, o + 8);
            oz = s.get(DOUBLE, o + 16);
            qx = s.get(DOUBLE, o + 24);
            qy = s.get(DOUBLE, o + 32);
            qz = s.get(DOUBLE, o + 40);
            qw = s.get(DOUBLE, o + 48);
            ortho = lens.orthographic();
            tanHalf = Maths.tan(lens.fovY() / 2);
            halfHeight = lens.halfHeight();
        }
        double cx;
        double cy;
        double cz;
        if (ortho) {
            cx = nx * halfHeight * aspect;
            cy = ny * halfHeight;
            out[0] = ox + rotate(qx, qy, qz, qw, cx, cy, 0, 0);
            out[1] = oy + rotate(qx, qy, qz, qw, cx, cy, 0, 1);
            out[2] = oz + rotate(qx, qy, qz, qw, cx, cy, 0, 2);
            cx = 0;
            cy = 0;
            cz = -1;
        } else {
            out[0] = ox;
            out[1] = oy;
            out[2] = oz;
            cx = nx * tanHalf * aspect;
            cy = ny * tanHalf;
            double length = Math.sqrt(cx * cx + cy * cy + 1);
            cx /= length;
            cy /= length;
            cz = -1 / length;
        }
        out[3] = rotate(qx, qy, qz, qw, cx, cy, cz, 0);
        out[4] = rotate(qx, qy, qz, qw, cx, cy, cz, 1);
        out[5] = rotate(qx, qy, qz, qw, cx, cy, cz, 2);
        return true;
    }

    /** Component {@code axis} of vector {@code v} turned by unit quaternion {@code q}. */
    static double rotate(double qx, double qy, double qz, double qw, double vx, double vy, double vz, int axis) {
        double tx = 2 * (qy * vz - qz * vy);
        double ty = 2 * (qz * vx - qx * vz);
        double tz = 2 * (qx * vy - qy * vx);
        return switch (axis) {
            case 0 -> vx + qw * tx + (qy * tz - qz * ty);
            case 1 -> vy + qw * ty + (qz * tx - qx * tz);
            default -> vz + qw * tz + (qx * ty - qy * tx);
        };
    }
}
