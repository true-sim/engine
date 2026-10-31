package com.truesim.engine.xr;

import com.truesim.engine.settings.Setting;
import java.util.List;

/** The headset's settings, read from the frame graph's settings ({@code graph.settings()}). */
public final class XrSettings {

    /** Where the tracking space's origin is. */
    public enum Space {
        /** On the floor at the center of the play area (room scale), where the runtime has one; else seated. */
        STAGE,
        /** Where the head was when tracking started (seated). */
        LOCAL
    }

    /** The eyes' resolution, times the runtime's recommendation. */
    public static final Setting<Float> RESOLUTION_SCALE = Setting.decimal("xr.resolutionScale", 1, 0.25f, 2,
        "the eyes' resolution, times what the runtime recommends").restartNeeded();
    /** The eyes' near plane, in units. */
    public static final Setting<Float> NEAR = Setting.decimal("xr.near", 0.05f, 0.001f, 10,
        "the eyes' near plane, in units");
    /** Where the tracking space's origin is. */
    public static final Setting<Space> SPACE = Setting.choice("xr.space", Space.STAGE,
        "where the tracking origin is: STAGE (the play area's floor, room scale) or LOCAL (seated)").restartNeeded();
    /** Every headset setting. */
    public static final List<Setting<?>> ALL = List.of(RESOLUTION_SCALE, NEAR, SPACE);

    private XrSettings() {
    }
}
