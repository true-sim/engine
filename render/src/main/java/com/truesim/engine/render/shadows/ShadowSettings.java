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
    /**
     * How far behind each cascade's slice, towards the light, its depth range reaches: casters there keep their depth
     * (their penumbrae's width); those beyond are flattened onto its near plane ({@link #CASCADE_PANCAKE}), or left out.
     */
    public static final Setting<Float> CASCADE_CASTERS = Setting.decimal("shadows.cascades.casters", 100, 0, 100_000,
        "how far behind each cascade's slice, towards the light, its depth range reaches, in units: casters beyond are "
            + "flattened onto its near plane (shadows.cascades.pancake) or left out");
    /** Debugging: what each cascade lights tinted by the cascade, red, green, blue, yellow (then cyan, magenta). */
    public static final Setting<Boolean> CASCADE_DEBUG = Setting.bool("shadows.cascades.debug", false,
        "debugging: what each cascade of a directional light lights is tinted by it: red, green, blue, yellow, ...");
    /**
     * Whether casters between the sun and a cascade's depth range (farther than {@link #CASCADE_CASTERS} beyond its
     * slice, towards the light) are drawn flattened onto its near plane, where the device clamps depth: a mountain
     * shadows the valley in every cascade. Off, they are left out, and a cascade shadows only what lies within its range.
     */
    public static final Setting<Boolean> CASCADE_PANCAKE = Setting.bool("shadows.cascades.pancake", true,
        "casters between the sun and a cascade's depth range drawn flattened onto its near plane, so they still cast "
            + "(where the GPU clamps depth); off: left out");
    /**
     * Whether the masked casters (foliage) before a cascade's depth range are flattened onto it too
     * ({@link #CASCADE_PANCAKE}); off, they are culled there: a valley's forests towards a low sun filled the far
     * cascade's lists past their limits and doubled the frame (#471), while terrain and buildings cast the shapes that
     * matter from afar.
     */
    public static final Setting<Boolean> CASCADE_PANCAKE_MASKED = Setting.bool("shadows.cascades.pancakeMasked", false,
        "masked casters (foliage) before a cascade's depth range flattened onto it too; off: culled there");
    /**
     * The share of each cascade's depths over which pixels take the next cascade more and more (dithered), and past the
     * last its far field or the light: no seam where the texel size changes, no edge where the cascades end.
     */
    public static final Setting<Float> CASCADE_BLEND = Setting.decimal("shadows.cascades.blend", 0.2f, 0.01f, 0.5f,
        "the share of each cascade's depths blended (dithered) into the next, or past the last into its far field");
    /**
     * The caster detail of the farthest cascade against the nearest's ({@link #CASTER_DETAIL}), in their own texels;
     * the cascades between take it geometrically: far cascades, whose texels are many times the near ones', draw
     * coarser levels of the casters' cluster hierarchies (1: every cascade alike).
     */
    public static final Setting<Float> CASCADE_FAR_DETAIL = Setting.decimal("shadows.cascades.farDetail", 8, 1, 64,
        "the farthest cascade's caster detail (texels of error) against the nearest's; those between geometric");
    /** What shadows a directional light's pixels past its last cascade ({@link #CASCADE_FAR_FIELD}). */
    public enum FarField {
        /** Nothing: the last cascade's blend fades into the light. */
        NONE,
        /**
         * A ray per pixel towards the light from where the cascades end on, against the scene's top level (where
         * rays are traced: the owner's {@code rayTracing}): terrain and buildings at any distance.
         */
        RAYS
    }

    /** What shadows a directional light's pixels past its last cascade: {@link FarField}. */
    public static final Setting<FarField> CASCADE_FAR_FIELD = Setting.choice("shadows.cascades.farField", FarField.RAYS,
        "what shadows the pixels past a sun's last cascade: RAYS (one per pixel, where rays are traced) or NONE");
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
    /** How the owner chooses which changed tiles of the atlas a frame draws ({@link #ATLAS_UPDATES}). */
    public enum Updates {
        /**
         * The important tiles the frame they change, the rest in turns: a tile whose light shows at least
         * {@link #ATLAS_URGENT} pixels (its importance) is drawn at once, as is one that waited {@link #ATLAS_LAG}
         * frames; the others, most important and longest waiting first, take {@link #ATLAS_DRAWS} a frame, or more
         * where that many would leave tiles late all at once (an even share of the changed tiles over the lag's
         * frames).
         */
        IMPORTANCE,
        /** Every changed tile, every frame: no shadow late, at the cost of every change. */
        ALL,
        /** At most {@link #ATLAS_DRAWS} tiles a frame, the most important first; the rest wait, however long. */
        CAPPED
    }

    /** How the owner chooses which changed atlas tiles a frame draws: {@link Updates}. */
    public static final Setting<Updates> ATLAS_UPDATES = Setting.choice("shadows.atlas.updates", Updates.IMPORTANCE,
        "which changed shadow atlas tiles a frame draws: IMPORTANCE (near and large lights at once, the rest within "
            + "shadows.atlas.lag frames), ALL (every one) or CAPPED (shadows.atlas.draws, the rest wait)");
    /**
     * The tiles drawn a frame beyond those that must be ({@link Updates#IMPORTANCE}), or at most
     * ({@link Updates#CAPPED}); tiles kept from frames before cost nothing, and those over the count, least
     * important first, wait (their light keeps its last map, or casts none yet).
     */
    public static final Setting<Integer> ATLAS_DRAWS = Setting.integer("shadows.atlas.draws", 64, 1, 4096,
        "shadow atlas tiles drawn in one frame beyond the urgent and late ones (CAPPED: at most)");
    /**
     * {@link Updates#IMPORTANCE}: a changed tile whose importance is at least this is drawn the frame it changed. Its
     * importance: the pixels its light's reach shows across (radius, in the view showing it largest), times its spec's
     * importance, times its brightness against the brightest mapped light's. A light whose reach holds a view, and a
     * directional light's cascades, always count as urgent.
     */
    public static final Setting<Float> ATLAS_URGENT = Setting.decimal("shadows.atlas.urgent", 256, 0, 1.0e9f,
        "IMPORTANCE updates: a changed tile whose light shows at least so many pixels (times importance and "
            + "relative brightness) is drawn at once");
    /**
     * {@link Updates#IMPORTANCE}: the most frames a changed tile waits before it is drawn, whatever the budget (0: none
     * waits, as {@link Updates#ALL}).
     */
    public static final Setting<Integer> ATLAS_LAG = Setting.integer("shadows.atlas.lag", 2, 0, 1000,
        "IMPORTANCE updates: the most frames a changed shadow atlas tile waits before it is drawn");
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
    /** How shadow maps are filtered ({@link #MAP_FILTER}). */
    public enum Filter {
        /**
         * Percentage-closer filtering over a tent of {@link #MAP_FILTER_RADIUS} texels: every map's penumbra a fixed
         * width in its texels, whatever the light's size. The cheaper: 4 gathers a lookup at 1.5 texels.
         */
        PCF,
        /**
         * Percentage-closer soft shadows (Fernando 2005): blockers searched over {@link #MAP_PENUMBRA} texels (16
         * gathers), their depth giving the penumbra a light of its spec's softness casts there (the sun's angular
         * radius: half a degree across), filtered over a tent that wide, from {@link #MAP_FILTER_RADIUS} up. Shadows
         * harden where they touch their casters and widen away from them. Lights without softness filter as PCF;
         * a lookup with no blocker in reach ends after the search.
         */
        PCSS
    }

    /** How shadow maps are filtered: {@link Filter}. */
    public static final Setting<Filter> MAP_FILTER = Setting.choice("shadows.maps.filter", Filter.PCSS,
        "how shadow maps are filtered: PCSS (penumbrae as wide as the light's size makes them) or PCF (a fixed "
            + "kernel, cheaper)");
    /**
     * The half-width of maps' filter kernel, in texels: PCF's, and PCSS's narrowest. A tent's integral weighs each
     * texel, so edges stay smooth down to half a texel; under about one, texels' stair steps show through.
     */
    public static final Setting<Float> MAP_FILTER_RADIUS = Setting.decimal("shadows.maps.filterRadius", 1.5f, 0.5f, 32,
        "half-width of shadow maps' filter in texels: PCF's kernel, PCSS's narrowest penumbra");
    /**
     * PCSS: the widest penumbra's half-width in texels, which the blocker search covers: the lookup's cost grows with
     * its square where penumbrae reach it (about (width + 1)^2 / 4 gathers).
     */
    public static final Setting<Float> MAP_PENUMBRA = Setting.decimal("shadows.maps.penumbra", 8, 1, 64,
        "PCSS: the widest penumbra's half-width in shadow map texels, which the blocker search covers");
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
     * casters on top (culled from a list of the moving ids, not every instance), instead of every caster. Costs the
     * second atlas's memory (256 MiB at 8192) and a depth write per texel of each such tile, which replaces the tile's
     * clear. Over ShadowsBenchmark's rock field it took 5 to 37 % less culling and drawing than every caster (README);
     * on by default; turn it off to save the second atlas's memory.
     */
    public static final Setting<Boolean> CASTER_LAYERS = Setting.bool("shadows.casters.layers", true,
        "maps keep still casters in a layer of their own and draw only moving ones again (a second atlas's memory)");
    /**
     * Frames a caster stays moving after its last move, before it joins its tiles' still layers (which are then drawn
     * again when next needed): fewer for casters that stop for good, more for those that pause.
     */
    public static final Setting<Integer> CASTER_SETTLE = Setting.integer("shadows.casters.settle", 30, 1, 100_000,
        "frames a caster counts as moving after its last move, before maps draw it with the still ones");
    /**
     * How the owner takes the scene's {@link com.truesim.scene.core.Mobility} (a node's, carried to its instance or
     * light): a hint, never a promise. Whatever it says, a caster or light that changes is drawn where it is now.
     */
    public enum MobilityUse {
        /**
         * Static and dynamic both: a static caster joins its tiles' still layers at once (no settle wait; one new to
         * the scene is drawn into the current layers it reaches, over what they hold), a dynamic one never does (none
         * of the churn of one pausing); a static light skips its own change tests while nothing
         * writes it, a dynamic one's tiles rank {@link #LIGHT_MOVING_WEIGHT} times as urgent. Instances and lights
         * without a hint (made outside the scene) are detected. For applications that mark both ways: the scene's
         * default is dynamic, so a scene that never marks its static nodes keeps every caster out of the still layers.
         */
        USE_HINT,
        /**
         * Static only, the default: static casters and lights as {@link #USE_HINT}; the rest, unmarked nodes (dynamic by
         * the scene's default) among them, detected as if nothing was said.
         */
        STATIC_ONLY,
        /** No hint: every caster's motion detected ({@link #CASTER_SETTLE}), every light tested each frame. */
        DETECT
    }

    /**
     * How shadow maps' caster layers take the casters' mobility ({@link MobilityUse}): a caster marked static that
     * moves twice within {@link #CASTER_SETTLE} frames counts as moving, as detection has it, until it settles.
     */
    public static final Setting<MobilityUse> CASTER_MOBILITY = Setting.choice("shadows.casters.mobility",
        MobilityUse.STATIC_ONLY, "how shadow maps take casters' mobility: STATIC_ONLY (static casters join the "
            + "still layers at once, the rest detected), USE_HINT (dynamic ones never join) or DETECT (ignore it)");
    /** How shadow maps take their lights' mobility ({@link MobilityUse}). */
    public static final Setting<MobilityUse> LIGHT_MOBILITY = Setting.choice("shadows.lights.mobility",
        MobilityUse.STATIC_ONLY, "how shadow maps take lights' mobility: STATIC_ONLY (static lights skip their "
            + "change tests), USE_HINT (dynamic ones' tiles updated first too) or DETECT (ignore it)");
    /**
     * {@link MobilityUse#USE_HINT}: how many times as important a changed tile of a dynamic spot or point light is in
     * the update order ({@link #ATLAS_UPDATES}) as one of a light not marked dynamic: 1 ranks them alike.
     */
    public static final Setting<Float> LIGHT_MOVING_WEIGHT = Setting.decimal("shadows.lights.movingWeight", 4, 1,
        1000, "how many times as urgent a changed shadow tile of a dynamic light is in the update order");
    /**
     * Ray-traced spot and point lights traced in a frame, at most: the most important (by how big their reach shows,
     * times their importance); the rest take maps (AUTO within their spec's bounds). Each traced light is a mask per
     * view, traced over the view's pixels (and soft ones denoised over them): 200 traced point lights took 220 ms a
     * frame at 1920 x 1080 (README). Directional lights are always traced.
     */
    public static final Setting<Integer> RAY_LIGHTS = Setting.integer("shadows.rays.lights", 16, 0, 256,
        "spot and point lights traced in a frame at most, the most important; the rest take maps");
    /**
     * Soft ray-traced spot and point lights traced soft in a frame, at most (of those {@link #RAY_LIGHTS} traces, the
     * most important); the rest are traced hard. Each soft light is denoised over the view's pixels: about 1 ms each at
     * 1920 x 1080 (README). Directional lights stay soft.
     */
    public static final Setting<Integer> SOFT_LIGHTS = Setting.integer("shadows.soft.lights", 4, 0, 256,
        "soft spot and point lights traced soft in a frame at most, the most important; the rest traced hard");
    /** Every shadow setting. */
    public static final List<Setting<?>> ALL = List.of(CASCADE_LAMBDA, CASCADE_BIAS, CASCADE_CASTERS, CASCADE_DEBUG, CASCADE_PANCAKE, CASCADE_PANCAKE_MASKED, CASCADE_BLEND,
        CASCADE_FAR_DETAIL, CASCADE_FAR_FIELD,
        RAY_OFFSET,
        CASTER_DETAIL, ATLAS_SIZE, ATLAS_UPDATES, ATLAS_DRAWS, ATLAS_URGENT, ATLAS_LAG, TILE_DRAWS, TILE_TRIANGLES,
        AUTO_SCALE, AUTO_HYSTERESIS, AUTO_CUTOFF, MAP_FILTER, MAP_FILTER_RADIUS, MAP_PENUMBRA, MAP_NEAR, MAP_DISTANCE,
        CHANGE_TESTS, CASTER_FACES, CASTER_LAYERS,
        CASTER_SETTLE, CASTER_MOBILITY, LIGHT_MOBILITY, LIGHT_MOVING_WEIGHT, SOFT_RAYS, SOFT_DENOISER, SOFT_PASSES, RAY_QUEUE, KEEP_ROUNDS, RAY_LIGHTS, SOFT_LIGHTS);

    private ShadowSettings() {
    }
}
