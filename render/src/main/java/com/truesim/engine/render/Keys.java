package com.truesim.engine.render;

import com.truesim.engine.graph.Key;

/** The results renderer techniques provide and require, whoever makes them. */
public final class Keys {

    /** The scene's lit color, before post effects. */
    public static final Key SCENE_COLOR = Key.of("scene color");
    /** The scene's depth: reverse, 1 near, 0 at infinity. */
    public static final Key SCENE_DEPTH = Key.of("scene depth");
    /**
     * The frame's light list, a buffer: the lights binned for shading (e.g. by {@link ClusteredLights}), read through
     * {@code lights.slang}'s {@code ILightList}. Without one, shading lights by the sun.
     */
    public static final Key LIGHT_LIST = Key.of("light list");

    /** Towards the sun, the scene's one light, unit length; the shaders' SUN. */
    public static final float SUN_X = 0.42399915f;
    public static final float SUN_Y = 0.84799830f;
    public static final float SUN_Z = 0.31799936f;

    private Keys() {
    }
}
