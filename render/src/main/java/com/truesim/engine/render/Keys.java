package com.truesim.engine.render;

import com.truesim.engine.graph.Key;

/** The results renderer techniques provide and require, whoever makes them. */
public final class Keys {

    /** The scene's lit color, before post effects. */
    public static final Key SCENE_COLOR = Key.of("scene color");
    /** The scene's depth: reverse, 1 near, 0 at infinity. */
    public static final Key SCENE_DEPTH = Key.of("scene depth");

    private Keys() {
    }
}
