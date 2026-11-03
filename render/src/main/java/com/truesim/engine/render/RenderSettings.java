package com.truesim.engine.render;

import com.truesim.engine.render.shadows.ShadowSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.vulkan.RecordBuffer;
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

    /**
     * How instances moved in fixed steps are drawn between steps (D91): blended on the GPU, on the CPU, or not at all.
     */
    public static final Setting<Interpolation> INTERPOLATION = Setting.choice("render.interpolation", Interpolation.GPU,
        "how instances moved in fixed steps are drawn between steps");

    /**
     * Where the instances' changes are written before the GPU scatters them: into plain memory copied when the frame
     * records (the default), or in place in GPU-visible memory, which spares the thread recording the copy (0.16 ms per
     * 30,000 changed instances) but costs the writers about as much and, on a discrete GPU, the scatter more
     * (frame-graph-v2.md, #101).
     */
    public static final Setting<RecordBuffer.ChangeList> CHANGE_LIST = Setting.choice("render.changeList",
        RecordBuffer.ChangeList.COPY,
        "where instance changes are written: copied when the frame records, or in place in GPU-visible memory");

    /** KiB of each chunk of an in-place change list ({@link RecordBuffer.ChangeList#DIRECT}). */
    public static final Setting<Integer> CHANGE_LIST_CHUNK = Setting.integer("render.changeListChunk", 1024, 64, 65536,
        "KiB of each chunk of an in-place change list");

    /** Every render setting: these, the light clusters', the shadows' and the display transform's. */
    public static final List<Setting<?>> ALL = all();

    private RenderSettings() {
    }

    private static List<Setting<?>> all() {
        List<Setting<?>> all = new ArrayList<>();
        all.add(OCCLUSION);
        all.add(INTERPOLATION);
        all.add(CHANGE_LIST);
        all.add(CHANGE_LIST_CHUNK);
        all.addAll(ClusteredLights.ALL);
        all.addAll(ShadowSettings.ALL);
        all.addAll(DisplayTransform.ALL);
        return List.copyOf(all);
    }
}
