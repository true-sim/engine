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
     * A view's shading hooks (hooks.slang; {@link ShadingHooks}), a token buffer: provided in every view by a
     * {@link ViewShadingHooks} of its own, which writes the view's hook block each frame. A technique adding a hook
     * ({@link ShadingHooks#add}) modifies it; shading requires it ({@link ShadingHookInputs#of}), and so builds after
     * every hook of the view.
     */
    public static final Key<Buffer> SHADING_HOOKS = Key.buffer("truesim.shadingHooks", ViewShadingHooks::new);
    /**
     * A view's UI, an image (frame-graph-v2 §4.8): premultiplied alpha, linear light, 1.0 meaning paper white, at the
     * size the view shows at (its output size), transparent where there is no UI. {@link DisplayTransform} composites it
     * over the tone-mapped scene, after the tone curve, so the UI is neither exposed nor tone mapped: SDR shows it as
     * it is, HDR at {@link DisplayTransform#PAPER_WHITE paper white} times {@link DisplayTransform#UI_BRIGHTNESS}.
     * Without a provider, nothing changes. A view presented without an output transform is copied as it is and shows
     * no UI: present through a {@code DisplayTransform} (with {@link DisplayTransform.Curve#NONE} and an exposure of 1
     * the scene is copied unchanged, clamped to white) to show one. Provided by engine-ui's renderer.
     */
    public static final Key<Image> UI = Key.image("truesim.ui");

    /**
     * A view's motion vectors, an image ({@link MotionVectors#FORMAT}): per pixel, where its surface was on screen the
     * frame before, in pixels from it (a history is read at the pixel plus this), the jitter taken out.
     */
    public static final Key<Image> MOTION = Key.image("truesim.motion");

    /**
     * The opaque surfaces' normals in view space ({@link SurfaceOutputs}), written by the view's shading where a
     * technique of the view asks for them ({@link SurfaceOutputs.Consumer}).
     */
    public static final Key<Image> SURFACE_NORMAL = Key.image("truesim.surfaceNormal");
    /** Their specular F0 and perceptual roughness ({@link SurfaceOutputs}). */
    public static final Key<Image> SURFACE_MATERIAL = Key.image("truesim.surfaceMaterial");
    /** What their color weighs the environment's reflection by ({@link SurfaceOutputs}). */
    public static final Key<Image> SURFACE_REFLECTANCE = Key.image("truesim.surfaceReflectance");
    /** What their color weighs the diffuse light from all around by ({@link SurfaceOutputs}). */
    public static final Key<Image> SURFACE_DIFFUSE = Key.image("truesim.surfaceDiffuse");
    /**
     * A view's specular occlusion ({@link AmbientOcclusion}): per pixel, what ambient occlusion dimmed the environment's
     * reflection by (8 bits, 1 where it did not), so a technique replacing that reflection after it
     * ({@link ScreenSpaceReflections}) replaces the dimmed one.
     */
    public static final Key<Image> SPECULAR_OCCLUSION = Key.image("truesim.specularOcclusion");

    /**
     * What each pixel of the opaque scene shows, as a visibility buffer draws it ({@link VisibilityBufferShading}):
     * {@code R32G32_UINT}, x the instance id plus 1 (0: nothing; the top bit tags classic LOD's), y the meshlet and
     * triangle. For GPU picking: reading the texels under pointers names what they are over.
     */
    public static final Key<Image> VISIBILITY_IDS = Key.image("truesim.visibilityIds");

    /** Towards the sun, the scene's one light, unit length; the shaders' SUN. */
    public static final float SUN_X = 0.42399915f;
    public static final float SUN_Y = 0.84799830f;
    public static final float SUN_Z = 0.31799936f;

    private Keys() {
    }
}
