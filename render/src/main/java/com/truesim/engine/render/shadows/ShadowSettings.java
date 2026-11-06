package com.truesim.engine.render.shadows;

import com.truesim.engine.settings.Setting;
import java.util.List;

/**
 * The shadow techniques' settings, read from the frame graph's settings unless a technique was given its own value:
 * cascades' split and bias (applied at their next {@code update}), and how far rays start off surfaces (applied when
 * frames record).
 */
public final class ShadowSettings {

    /** How cascades' splits blend logarithmic (1) and uniform (0) spacing. */
    public static final Setting<Float> CASCADE_LAMBDA = Setting.decimal("shadows.cascades.lambda", 0.75f, 0, 1,
        "how cascade splits blend logarithmic (1) and uniform (0) spacing");
    /** The cascades' bias, in texels: along the normal, and a third of it deep. */
    public static final Setting<Float> CASCADE_BIAS = Setting.decimal("shadows.cascades.bias", 1.5f, 0, 16,
        "cascade bias, in texels: the lookup moves that far along the normal, and a third of it deep");
    /** How far behind each cascade's slice, towards the light, casters still cast into it. */
    public static final Setting<Float> CASCADE_CASTERS = Setting.decimal("shadows.cascades.casters", 100, 0, 100_000,
        "how far behind each cascade's slice, towards the light, casters still cast into it, in units");
    /** How far rays start off the surface, per unit of distance from the camera. */
    public static final Setting<Float> RAY_OFFSET = Setting.decimal("shadows.rays.offset", 2.0e-3f, 0, 0.1f,
        "how far shadow rays start off the surface, per unit of distance from the camera");
    /**
     * The error, in pixels of its own target, that draws through a camera of their own allow when choosing detail
     * (shadow maps, a camera's depth drawn as a view of its own): 1 as a camera's view. Coarser casters draw far fewer
     * triangles (20,000 rocks, two eyes: cascades 0.92 ms at 1, 0.56 at 2, 0.41 at 4) but sit up to so many texels off
     * the finer surfaces they shadow: cascades add that much depth bias, so those surfaces do not shadow themselves in
     * spots. At 2, a sparse scene's image is all but the same (0.004 % of pixels change); in a dense one, which rocks
     * shadow which changes here and there (5 %).
     */
    public static final Setting<Float> CASTER_DETAIL = Setting.decimal("shadows.casterDetail", 1, 0.25f, 16,
        "the error in pixels shadow casters allow when choosing detail: higher draws coarser casters, faster, with "
            + "cascade bias to match");
    /**
     * The {@link Shadows} owner's shadow atlas, texels per side: every map of every light (spot and point lights' tiles,
     * directional lights' cascades) is a tile of it. Its memory is the maps' budget (32-bit depth: 256 MiB at 8192);
     * made the first time a light uses maps. Changing it rebuilds the graph and draws every tile again.
     */
    public static final Setting<Integer> ATLAS_SIZE = Setting.integer("shadows.atlas.size", 8192, 512, 16384,
        "texels per side of the shadow atlas, every light's maps' budget (a power of two)");
    /**
     * The most tiles the owner draws in one frame: tiles kept from frames before cost nothing, and those over the
     * count, least important first, wait for the next frame (their light keeps its last map, or casts none yet).
     */
    public static final Setting<Integer> ATLAS_DRAWS = Setting.integer("shadows.atlas.draws", 64, 1, 4096,
        "the most shadow atlas tiles drawn in one frame (tiles nothing changed in are kept)");
    /**
     * The draws a tile's culling lists hold to start with; a list that overflows doubles (the graph builds again and
     * every tile is drawn again).
     */
    public static final Setting<Integer> TILE_DRAWS = Setting.integer("shadows.tiles.draws", 1 << 16, 64, 1 << 22,
        "draws (meshlets) a shadow tile's culling lists hold to start with (they grow when they overflow)");
    /** The triangles a tile's culling lists hold to start with (the indexed path's index buffer); they grow too. */
    public static final Setting<Integer> TILE_TRIANGLES = Setting.integer("shadows.tiles.triangles", 1 << 20, 1024,
        1 << 26, "triangles a shadow tile's culling lists hold to start with (they grow when they overflow)");
    /** AUTO maps: texels per pixel the light's reach shows across on screen. */
    public static final Setting<Float> AUTO_SCALE = Setting.decimal("shadows.auto.scale", 1, 0.0625f, 16,
        "AUTO shadow maps: texels per pixel of the light's reach on screen");
    /**
     * AUTO maps: how far (in octaves) a light's wanted size must move past the halfway point between two sizes before
     * its tile changes size: tiles stay as the camera moves a little.
     */
    public static final Setting<Float> AUTO_HYSTERESIS = Setting.decimal("shadows.auto.hysteresis", 0.25f, 0, 4,
        "AUTO shadow maps: octaves of hysteresis before a tile changes size");
    /** AUTO maps: beyond this distance from every view (units; 0: none), a light casts no shadow. */
    public static final Setting<Float> AUTO_CUTOFF = Setting.decimal("shadows.auto.cutoff", 0, 0, 1.0e7f,
        "AUTO shadow maps: the distance from the views beyond which a light casts none (0: no cutoff)");
    /** Spot and point lights' maps: the near plane, in units. */
    public static final Setting<Float> MAP_NEAR = Setting.decimal("shadows.maps.near", 0.05f, 1.0e-4f, 100,
        "the near plane of spot and point lights' shadow maps, in units");
    /** A directional light's maps reach this far from the camera where its spec gives no distance. */
    public static final Setting<Float> MAP_DISTANCE = Setting.decimal("shadows.maps.distance", 100, 0.1f, 1.0e7f,
        "how far a directional light's maps reach where its spec gives no distance, in units");
    /**
     * The most tests of moved casters against lights a frame (moved instances times lights); past it, the tiles meeting
     * the box around every moved caster are drawn again.
     */
    public static final Setting<Integer> CHANGE_TESTS = Setting.integer("shadows.atlas.changeTests", 1 << 20, 0,
        1 << 30, "the most moved-caster-against-light tests a frame before testing their box as one");
    /** Soft ray-traced shadows: rays per pixel and light, before the denoiser. */
    public static final Setting<Integer> SOFT_RAYS = Setting.integer("shadows.soft.rays", 1, 1, 64,
        "soft ray-traced shadows: rays per pixel and light, before denoising");
    /** Soft ray-traced shadows: the denoiser's family. */
    public static final Setting<com.truesim.engine.render.Denoiser.Mode> SOFT_DENOISER = Setting.choice(
        "shadows.soft.denoiser", com.truesim.engine.render.Denoiser.Mode.SVGF,
        "soft ray-traced shadows: the denoiser (SVGF or REBLUR)");
    /**
     * Soft ray-traced shadows: SVGF's a-trous steps (the engine's denoiser's {@code render.denoise.passes} is for every
     * signal). Each step costs about 0.33 ms at 1920 x 1080 on an RX 9070 XT; penumbras measured against the reference
     * are as close with 2 as with 4 (ShadowOwnerTest).
     */
    public static final Setting<Integer> SOFT_PASSES = Setting.integer("shadows.soft.passes", 2, 0, 8,
        "soft ray-traced shadows: SVGF's a-trous filter steps");
    /**
     * Traced shadows: the shadow rays a view's queue holds while their hit's keep() is tested (material types with a
     * geometry hook); rays past it count as blocked.
     */
    public static final Setting<Integer> RAY_QUEUE = Setting.integer("shadows.rays.queue", 1 << 18, 64, 1 << 24,
        "traced shadows: rays waiting for a keep() test a view holds");
    /**
     * Traced shadows: how many holes of geometry hooks (keep()) one ray goes through; at the last, a ray meeting another
     * such triangle counts as blocked.
     */
    public static final Setting<Integer> KEEP_ROUNDS = Setting.integer("shadows.rays.keepRounds", 3, 1, 16,
        "traced shadows: holes of keep() one ray goes through, at most");
    /** Which faces of casters shadow maps draw. */
    public enum Faces {
        /**
         * Both sides of every face, as shadow rays meet them: a light inside a closed mesh is shut in, one-sided
         * surfaces cast from behind too. About twice the tiles' drawing (RX 9070 XT, 30,000 rocks).
         */
        BOTH,
        /**
         * Front faces alone, as a camera's depth: cheaper; exact for closed meshes lit from outside, but a light inside
         * one leaks, and one-sided surfaces seen from behind cast nothing.
         */
        FRONT
    }

    /** Which faces of casters shadow maps draw ({@link Faces}). */
    public static final Setting<Faces> CASTER_FACES = Setting.choice("shadows.casters.faces", Faces.BOTH,
        "faces of casters shadow maps draw: BOTH (as rays meet them) or FRONT (cheaper; lights inside meshes leak)");
    /**
     * Whether maps keep their still casters apart from the moving ones: a tile a moving caster reaches is drawn again
     * from its still casters' layer (a second atlas, as large, drawn when that layer changes) and only the moving
     * casters on top, instead of every caster. Costs the second atlas's memory and a depth write per texel of each such
     * tile: it pays where still casters are costly to draw. Off by default: over ShadowsBenchmark's rock field (cheap
     * casters, the cost per tile in culling and drawing) it was slower than drawing every caster (README, pitfalls).
     */
    public static final Setting<Boolean> CASTER_LAYERS = Setting.bool("shadows.casters.layers", false,
        "maps keep still casters in a layer of their own and draw only moving ones again (a second atlas's memory)");
    /**
     * Frames a caster stays moving after its last move, before it joins its tiles' still layers (which are then drawn
     * again when next needed): fewer for casters that stop for good, more for those that pause.
     */
    public static final Setting<Integer> CASTER_SETTLE = Setting.integer("shadows.casters.settle", 30, 1, 100_000,
        "frames a caster counts as moving after its last move, before maps draw it with the still ones");
    /**
     * Ray-traced spot and point lights traced in a frame, at most: the most important (by how big their reach shows,
     * times their importance); the rest take maps (AUTO within their spec's bounds). Each traced light is a mask per
     * view, traced over the view's pixels (and soft ones denoised over them): 200 traced point lights took 220 ms a
     * frame at 1920 x 1080 (README). Directional lights are always traced.
     */
    public static final Setting<Integer> RAY_LIGHTS = Setting.integer("shadows.rays.lights", 16, 0, 256,
        "spot and point lights traced in a frame at most, the most important; the rest take maps");
    /** Every shadow setting. */
    public static final List<Setting<?>> ALL = List.of(CASCADE_LAMBDA, CASCADE_BIAS, CASCADE_CASTERS, RAY_OFFSET,
        CASTER_DETAIL, ATLAS_SIZE, ATLAS_DRAWS, TILE_DRAWS, TILE_TRIANGLES, AUTO_SCALE, AUTO_HYSTERESIS, AUTO_CUTOFF,
        MAP_NEAR, MAP_DISTANCE, CHANGE_TESTS, CASTER_FACES, CASTER_LAYERS, CASTER_SETTLE, SOFT_RAYS, SOFT_DENOISER,
        SOFT_PASSES, RAY_QUEUE, KEEP_ROUNDS, RAY_LIGHTS);

    private ShadowSettings() {
    }
}
