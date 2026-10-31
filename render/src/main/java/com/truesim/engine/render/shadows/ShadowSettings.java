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
    /** Every shadow setting. */
    public static final List<Setting<?>> ALL = List.of(CASCADE_LAMBDA, CASCADE_BIAS, CASCADE_CASTERS, RAY_OFFSET);

    private ShadowSettings() {
    }
}
