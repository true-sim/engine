package com.truesim.engine.render;

import java.util.List;

/**
 * What a frame draws: one or more {@link GeometryProvider}s, all drawn by every {@link SceneDraw} (the camera's
 * shading, shadow maps, ...). Swapping a provider (meshlets for classic LOD, say) changes nothing else.
 *
 * <pre>{@code
 * SceneGeometry geometry = new SceneGeometry(new MeshletGeometry(renderer));
 * geometry.setView(0, camera, 1, MeshletRenderer.MODE_LIT);
 * graph.add(new ClusteredForwardShading(geometry, width, height, colorFormat, depthFormat));
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

    /** Sets view {@code index} on every provider; read when the frame runs. Allocates nothing. */
    public SceneGeometry setView(int index, Camera camera, float errorPixels, int mode) {
        for (int i = 0; i < providers.size(); i++) {
            providers.get(i).setView(index, camera, errorPixels, mode);
        }
        return this;
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
