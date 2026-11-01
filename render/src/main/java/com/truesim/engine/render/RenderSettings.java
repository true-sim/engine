package com.truesim.engine.render;

import com.truesim.engine.render.shadows.ShadowSettings;
import com.truesim.engine.settings.Setting;
import java.util.ArrayList;
import java.util.List;

/**
 * The render module's settings: those of no single technique, and every render setting together, for an options
 * screen or a console. Techniques read them from the frame graph's settings unless given their own value.
 */
public final class RenderSettings {

    /** Two-phase occlusion culling, where a view was made with it. */
    public static final Setting<Boolean> OCCLUSION = Setting.bool("render.occlusionCulling", true,
        "two-phase occlusion culling, where a view was made with it");

    /** Every render setting: these, the light clusters', the shadows' and the display transform's. */
    public static final List<Setting<?>> ALL = all();

    private RenderSettings() {
    }

    private static List<Setting<?>> all() {
        List<Setting<?>> all = new ArrayList<>();
        all.add(OCCLUSION);
        all.add(Transparency.MODE);
        all.addAll(ClusteredLights.ALL);
        all.addAll(ShadowSettings.ALL);
        all.addAll(DisplayTransform.ALL);
        return List.copyOf(all);
    }
}
