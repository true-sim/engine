package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.ViewPort;
import java.util.Objects;

/**
 * The scene as a {@link ViewPort} of the frame graph sees it: the port (its techniques and size), the
 * {@link RenderView} its culling draws (lists and occlusion history) and the camera it looks through. The scene's
 * techniques of a view (shading, light binning) take it, and are added to its port: they read the size from the port
 * when they build, and the camera when the frame runs.
 *
 * <pre>{@code
 * ViewPort port = graph.addView("main").size(1920, 1080);
 * SceneView main = geometry.createView(port, camera, ViewOptions.of(1 << 18, 1 << 22)).set(1, MeshletRenderer.MODE_LIT);
 * port.add(new ClusteredForwardShading(geometry, main, colorFormat, depthFormat))
 *     .add(new ClusteredLights(lights, main))
 *     .output(Keys.SCENE_COLOR, window);
 * }</pre>
 *
 * @param port the graph's view
 * @param view what its culling draws (made by the geometry's renderer)
 * @param camera what it looks through, read when the frame runs
 */
public record SceneView(ViewPort port, RenderView view, Camera camera) {

    public SceneView {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(camera, "camera");
    }

    /** Sets the render view's camera, error and mode ({@link RenderView#set}); read when the frame runs. */
    public SceneView set(float errorPixels, int mode) {
        view.set(camera, errorPixels, mode);
        return this;
    }

    /** The width of the port's images. */
    public int width() {
        return port.width();
    }

    /** The height of the port's images. */
    public int height() {
        return port.height();
    }

    /** Throws unless {@code technique} is building in this view's port: its keys and size are the port's. */
    @AllowAllocation("building the graph, when it changes")
    void checkBuilding(GraphBuilder g, Object technique) {
        if (g.view() != port) {
            throw new IllegalStateException(technique.getClass().getSimpleName() + " of " + port
                + " was added to " + (g.view() == null ? "the frame" : g.view()) + ": add it to its view");
        }
    }
}
