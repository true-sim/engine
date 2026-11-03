package com.truesim.engine.render;

/**
 * How forward shading draws the material types ({@link RenderSettings#FORWARD_SHADING}): per type, or with one shader
 * for all.
 */
public enum ForwardShading {
    /**
     * Each shading bin (a {@link MaterialType} and the features its materials show, as {@link Materials#binCount}
     * numbers them) draws with pipelines of its own type's generated entry points and only its features' code, from
     * the view's lists split by bin after culling: an indirect draw per raster bin and shading bin present. Every type
     * shades as itself; nothing branches on the type.
     */
    PER_TYPE,
    /**
     * One shader for every material, branching on its type and features: types other than metallic-roughness draw as
     * its defaults.
     */
    SINGLE
}
