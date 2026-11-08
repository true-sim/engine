package com.truesim.engine.render;

import com.truesim.engine.settings.Setting;

/**
 * Where a material type's {@code displace()} ({@link Material#GEOMETRY_DISPLACE}; README, "Displacement") moves the
 * vertices of an instance drawing it. Chosen per instance by what makes it (engine-scene's {@code SceneSync}: the
 * node's {@code DisplacementMode}), else per material type ({@link Materials#displacementMode(MaterialType,
 * Displacement)}), else by the setting {@link #MODE}.
 */
public enum Displacement {

    /**
     * In the vertex and mesh stages of every pass reading the geometry (color, depth, every shadow technique drawing
     * maps, the visibility buffer's IDs and shading, motion vectors with the frame before's evaluation): no memory per
     * instance, the work done in each pass. Ray tracing (its bottom levels) sees the mesh undisplaced.
     */
    RASTER,

    /**
     * Once a frame, into the instance's copy of the mesh ({@link GeometryPool#addDeformed},
     * {@link Deformation#displace}), after morph targets and skinning: every pass draws the copy as it is, and its
     * bottom level is refitted, so ray tracing (shadows, global illumination, the reference) sees the displaced
     * surface. Costs a copy of the mesh's vertices, attributes and meshlet records per instance, and a refit a frame.
     */
    COMPUTE;

    /** The mode instances take where neither they nor their material's type say ({@link #RASTER} by default). */
    public static final Setting<Displacement> MODE = Setting.choice("render.displacement.mode", RASTER,
        "where material types' displacement runs unless the instance or its type says: RASTER, in every pass "
            + "drawing it; COMPUTE, once a frame into a copy of its mesh that ray tracing sees");
}
