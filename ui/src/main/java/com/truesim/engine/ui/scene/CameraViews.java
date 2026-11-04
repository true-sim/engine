package com.truesim.engine.ui.scene;

import com.truesim.engine.render.Camera;
import com.truesim.ui.scene.SceneView;

/**
 * An engine {@link Camera} as the UI sees it: a {@link SceneView} set from the camera (its position in doubles, its
 * rotation, its perspective or orthographic projection) over a viewport in UI pixels, so the UI's rays (scene picking,
 * gizmos, anchors) go through what the renderer draws. The projection is set again only when it changed, so the view's
 * version grows only when something moved. Allocates nothing.
 *
 * <pre>{@code
 * CameraViews main = new CameraViews(camera, new SceneView());
 * main.follow(0, 0, width, height, 100_000);   // each frame, after the camera was set, before the UI's input
 * }</pre>
 */
public final class CameraViews {

    private final Camera camera;
    private final SceneView view;
    private boolean set;
    private boolean orthographic;
    private double fovY;
    private double near;
    private double far;
    private double halfHeight;

    /** {@code view} following {@code camera}. */
    public CameraViews(Camera camera, SceneView view) {
        this.camera = java.util.Objects.requireNonNull(camera, "camera");
        this.view = java.util.Objects.requireNonNull(view, "view");
    }

    /** The camera followed. */
    public Camera camera() {
        return camera;
    }

    /** The UI's view of it. */
    public SceneView view() {
        return view;
    }

    /**
     * Sets the view from the camera as it is now, on the viewport {@code x, y, width, height} (UI pixels), with
     * {@code farDistance} as its far plane (the engine's cameras have none: rays reach that far).
     */
    public void follow(float x, float y, float width, float height, double farDistance) {
        view.pose(camera.position(0) + camera.eye(0), camera.position(1) + camera.eye(1),
            camera.position(2) + camera.eye(2), camera.rotation(0), camera.rotation(1), camera.rotation(2),
            camera.rotation(3));
        boolean ortho = camera.orthographic();
        if (ortho) {
            if (!set || !orthographic || halfHeight != camera.halfHeight() || far != farDistance) {
                view.orthographic(2 * camera.halfHeight(), 0, farDistance);
                halfHeight = camera.halfHeight();
            }
        } else {
            double n = camera.near() > 0 ? camera.near() : 0.01;
            double f = Math.max(farDistance, n * 2);
            if (!set || orthographic || fovY != camera.fovY() || near != n || far != f) {
                view.perspective(camera.fovY(), n, f);
                fovY = camera.fovY();
                near = n;
            }
        }
        far = ortho ? farDistance : Math.max(farDistance, near * 2);
        orthographic = ortho;
        set = true;
        if (width > 0 && height > 0) {
            view.viewport(x, y, width, height);
        }
    }
}
