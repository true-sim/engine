package com.truesim.engine.render;

import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;

/** The results renderer techniques provide and require, whoever makes them. */
public final class Keys {

    /** The scene's lit color, before post effects. */
    public static final Key<Image> SCENE_COLOR = Key.image("truesim.sceneColor");
    /** The scene's depth: reverse, 1 near, 0 at infinity. */
    public static final Key<Image> SCENE_DEPTH = Key.image("truesim.sceneDepth");
    /**
     * The frame's light list, a buffer: the lights binned for shading (e.g. by {@link ClusteredLights}), read through
     * {@code lights.slang}'s {@code ILightList}. Without one, shading lights by the sun.
     */
    public static final Key<Buffer> LIGHT_LIST = Key.buffer("truesim.lightList");
    /**
     * A view's indirect light, a buffer: the table of indirect.slang's {@code IndirectLight}, whose sections its
     * providers fill (the distant environment, {@link EnvironmentLighting}; later probe volumes, reflection probes,
     * screen-space techniques, which modify it), their images gathered by {@link IndirectInputs}. Without one, shading
     * takes the constant sky.
     */
    public static final Key<Buffer> INDIRECT_LIGHT = Key.buffer("truesim.indirectLight");

    /**
     * The opaque surfaces' normals in view space ({@link SurfaceOutputs}), written by the view's shading where a
     * technique of the view asks for them ({@link SurfaceOutputs.Consumer}).
     */
    public static final Key<Image> SURFACE_NORMAL = Key.image("truesim.surfaceNormal");
    /** Their specular F0 and perceptual roughness ({@link SurfaceOutputs}). */
    public static final Key<Image> SURFACE_MATERIAL = Key.image("truesim.surfaceMaterial");
    /** What their color weighs the environment's reflection by ({@link SurfaceOutputs}). */
    public static final Key<Image> SURFACE_REFLECTANCE = Key.image("truesim.surfaceReflectance");

    /** Towards the sun, the scene's one light, unit length; the shaders' SUN. */
    public static final float SUN_X = 0.42399915f;
    public static final float SUN_Y = 0.84799830f;
    public static final float SUN_Z = 0.31799936f;

    private Keys() {
    }
}
