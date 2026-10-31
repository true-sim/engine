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
     * triangles (20,000 rocks, two eyes: cascades 0.92 ms at 1, 0.56 at 2, 0.41 at 4) but sit off the finer surfaces
     * they shadow, which then shadow themselves in spots: raise {@link #CASCADE_BIAS} with it.
     */
    public static final Setting<Float> CASTER_DETAIL = Setting.decimal("shadows.casterDetail", 1, 0.25f, 16,
        "the error in pixels shadow casters allow when choosing detail: higher draws coarser casters, faster, and needs "
            + "more cascade bias");
    /** Every shadow setting. */
    public static final List<Setting<?>> ALL = List.of(CASCADE_LAMBDA, CASCADE_BIAS, CASCADE_CASTERS, RAY_OFFSET,
        CASTER_DETAIL);

    private ShadowSettings() {
    }
}
