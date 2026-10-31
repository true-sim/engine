package com.truesim.engine.render;

import com.truesim.engine.graph.ViewPort;
import java.util.List;

/**
 * What a frame draws: one or more {@link GeometryProvider}s, all drawn by every {@link SceneDraw} (the camera's
 * shading, shadow maps, ...). Swapping a provider (meshlets for classic LOD, say) changes nothing else.
 *
 * <pre>{@code
 * SceneGeometry geometry = new SceneGeometry(new MeshletGeometry(renderer));
 * ViewPort port = graph.addView("main").size(width, height);
 * SceneView view = geometry.createView(port, camera, ViewOptions.of(1 << 18, 1 << 22)).set(1, MeshletRenderer.MODE_LIT);
 * port.add(new ClusteredForwardShading(geometry, view, colorFormat, depthFormat));
 * }</pre>
 */
public final class SceneGeometry {

    private final List<GeometryProvider> providers;

    public SceneGeometry(GeometryProvider... providers) {
        if (providers.length == 0) {
            throw new IllegalArgumentException("A scene draws at least one kind of geometry");
        }
        this.providers = List.of(providers);
    }

    /** A scene of {@code renderer}'s meshlets alone. */
    public static SceneGeometry meshlets(MeshletRenderer renderer) {
        return new SceneGeometry(new MeshletGeometry(renderer));
    }

    public List<GeometryProvider> providers() {
        return providers;
    }

    /** Sets {@code view}'s camera ({@link RenderView#set}); read when the frame runs. Allocates nothing. */
    public SceneGeometry setView(RenderView view, Camera camera, float errorPixels, int mode) {
        view.set(camera, errorPixels, mode);
        return this;
    }

    /**
     * A new view of the geometry's instances, with lists and history as {@code options} say: the renderer's of its
     * meshlets ({@link MeshletRenderer#createView}), which every provider draws.
     */
    public RenderView createView(ViewOptions options) {
        return renderer().createView(options);
    }

    /**
     * A new view of the geometry's instances ({@link #createView(ViewOptions)}) for {@code port}, looking through
     * {@code camera}: what the scene's techniques of the port take.
     */
    public SceneView createView(ViewPort port, Camera camera, ViewOptions options) {
        return new SceneView(port, createView(options), camera);
    }

    /** The renderer whose scene and views the providers draw. */
    public MeshletRenderer renderer() {
        for (GeometryProvider provider : providers) {
            if (provider instanceof MeshletGeometry meshlets) {
                return meshlets.renderer();
            }
            if (provider instanceof ClassicLodGeometry classic) {
                return classic.renderer();
            }
        }
        throw new IllegalStateException("No provider of the geometry draws a renderer's views");
    }

    /** The provider of type {@code type}, e.g. for a technique needing what only it can give; null if none. */
    public <T extends GeometryProvider> T provider(Class<T> type) {
        for (GeometryProvider provider : providers) {
            if (type.isInstance(provider)) {
                return type.cast(provider);
            }
        }
        return null;
    }
}
