package com.truesim.engine.scene;

import com.truesim.common.math.TransformArray;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.scene.core.CameraLens;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * A camera node drawn through a renderer {@link Camera} (application-api.md D129): the camera follows the node's world
 * transform (it looks along the node's -Z, +Y up; the node's scale ignored) and its {@link CameraLens} (perspective or
 * orthographic), on a view of the port's size, so a camera moves, parents and animates with the scene graph. Call
 * {@link #update} once the scene's world transforms are final for the frame (after its late update), before the frame
 * records; a pointer's picking at the frame's start reads the camera as the frame before left it, as drawn.
 *
 * <pre>{@code
 * CameraSync eye = new CameraSync(scene, eyeNode, camera, port);
 * // each frame, after scene.update():
 * eye.update();
 * }</pre>
 *
 * <p>Allocates nothing. A node without a lens (or gone) leaves the camera as it was.
 */
public final class CameraSync {

    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;

    private final SceneGraph scene;
    private final Camera camera;
    private final ViewPort port;
    private long node;

    /** {@code camera} following camera node {@code cameraNode} of {@code graph}, on {@code view}'s size. */
    public CameraSync(SceneGraph graph, long cameraNode, Camera camera, ViewPort view) {
        this.scene = Objects.requireNonNull(graph, "graph");
        this.camera = Objects.requireNonNull(camera, "camera");
        this.port = Objects.requireNonNull(view, "view");
        node(cameraNode);
    }

    /** {@code camera} following camera node {@code cameraNode} of {@code graph}, sized at each {@link #update(int, int)}. */
    public CameraSync(SceneGraph graph, long cameraNode, Camera camera) {
        this.scene = Objects.requireNonNull(graph, "graph");
        this.camera = Objects.requireNonNull(camera, "camera");
        this.port = null;
        node(cameraNode);
    }

    /** Follows camera node {@code cameraNode} from now on (a cut to another camera); returns it. */
    public CameraSync node(long cameraNode) {
        if (!scene.contains(cameraNode)) {
            throw new IllegalArgumentException("Not a node of the scene: " + NodeHandle.toString(cameraNode));
        }
        node = cameraNode;
        return this;
    }

    /** The node followed. */
    public long node() {
        return node;
    }

    /** The renderer's camera. */
    public Camera camera() {
        return camera;
    }

    /**
     * Sets the camera from the node and its lens as they are now, on the port's size; false (camera unchanged) without
     * a lens.
     */
    public boolean update() {
        if (port == null) {
            throw new IllegalStateException("No port to size the view by: update(width, height)");
        }
        return update(port.width(), port.height());
    }

    /** Sets the camera from the node and its lens on a {@code width x height} view; false without a lens. */
    public boolean update(int width, int height) {
        CameraLens lens = scene.contains(node) ? scene.components().get(node, CameraLens.TYPE) : null;
        if (lens == null) {
            return false;
        }
        set(scene.worldTransforms(), NodeHandle.index(node), lens, camera, Math.max(1, width), Math.max(1, height));
        return true;
    }

    /** Sets {@code camera} from slot {@code slot}'s world transform and {@code lens} on a {@code width x height} view. */
    static void set(TransformArray world, int slot, CameraLens lens, Camera camera, int width, int height) {
        MemorySegment s = world.segment();
        long o = slot * world.stride();
        double qx = s.get(DOUBLE, o + 24);
        double qy = s.get(DOUBLE, o + 32);
        double qz = s.get(DOUBLE, o + 40);
        double qw = s.get(DOUBLE, o + 48);
        // forward: -Z turned; up: +Y turned
        double fx = -2 * (qx * qz + qw * qy);
        double fy = -2 * (qy * qz - qw * qx);
        double fz = -(1 - 2 * (qx * qx + qy * qy));
        double ux = 2 * (qx * qy - qw * qz);
        double uy = 1 - 2 * (qx * qx + qz * qz);
        double uz = 2 * (qy * qz + qw * qx);
        camera.position(s.get(DOUBLE, o), s.get(DOUBLE, o + 8), s.get(DOUBLE, o + 16));
        if (lens.orthographic()) {
            double halfHeight = lens.halfHeight();
            camera.setOrthographic(0, 0, 0, fx, fy, fz, ux, uy, uz, halfHeight * width / height, halfHeight,
                lens.depth(), width, height);
        } else {
            camera.set(fx, fy, fz, ux, uy, uz, lens.fovY(), width, height, lens.near());
        }
    }
}
