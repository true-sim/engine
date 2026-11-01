package com.truesim.engine.render;

import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;

/**
 * One kind of geometry a {@link SceneGeometry} draws: meshlets with cluster LOD ({@link MeshletGeometry}), whole
 * discrete LODs per instance ({@link ClassicLodGeometry}), and later skinned meshes, particles. Each culls its own way,
 * and draws into the targets a {@link SceneDraw} asks for, so different kinds mix in one image, one visibility buffer,
 * one shadow map.
 */
public interface GeometryProvider {

    /** The renderer whose scene and views it draws: every provider of a {@link SceneGeometry} draws the same one's. */
    MeshletRenderer renderer();

    /**
     * Adds the passes drawing this geometry for {@code draw} into {@code targets}: all of it, or with occlusion culling
     * the first phase (what was visible last frame). {@code load}: the targets hold what providers before drew.
     */
    void drawFirst(GraphBuilder g, SceneDraw draw, SceneDraw.Targets targets, boolean load);

    /**
     * With occlusion culling: adds the passes drawing what the depth {@code pyramid} of the first phase shows has come
     * into view, over the targets. Nothing by default: a provider without occlusion culling drew everything first.
     */
    default void drawLate(GraphBuilder g, SceneDraw draw, SceneDraw.Targets targets, Image pyramid) {
    }

    /**
     * Adds the passes drawing this geometry's blended meshlets for {@code draw} (both phases' lists, after the late one)
     * into the transparency pass's {@code targets}: cleared, or with {@code load} as providers before left them.
     * Nothing by default, for geometry without blended materials; the engine's providers draw them.
     */
    default void drawBlended(GraphBuilder g, SceneDraw draw, Transparency.Targets targets, boolean load) {
    }
}
