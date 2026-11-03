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
        "how instances and skinned joints moved in fixed steps are drawn between steps");

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

    /**
     * MiB a frame of prepared meshes and textures copied on the transfer queue, beside the frames in flight; what comes
     * after (or everything, with 0) is copied in the frame on the graphics queue. Either way it draws from the next
     * frame. Streaming 131,000-triangle tiles with 2048 x 2048 textures, frames adding them took 0.8 ms of graphics
     * queue time instead of 1.5 (frame-graph-v2.md, phase 8).
     */
    public static final Setting<Integer> STREAM_BUDGET = Setting.integer("render.streamBudget", 64, 0, 4096,
        "MiB a frame of streamed meshes and textures copied on the transfer queue (0: in the frame)");

    /**
     * Every render setting: these, transparency's and screen-space reflections', the light clusters', the world
     * light grid's, the reference path tracer's, the shadows' and the display transform's.
     */
    public static final List<Setting<?>> ALL = all();

    private RenderSettings() {
    }

    private static List<Setting<?>> all() {
        List<Setting<?>> all = new ArrayList<>();
        all.add(OCCLUSION);
        all.add(INTERPOLATION);
        all.add(CHANGE_LIST);
        all.add(CHANGE_LIST_CHUNK);
        all.add(STREAM_BUDGET);
        all.add(Transparency.MODE);
        all.add(Transparency.SORTED_RUNS);
        all.addAll(ScreenSpaceReflections.ALL);
        all.addAll(ClusteredLights.ALL);
        all.addAll(WorldLights.ALL);
        all.addAll(ReferencePathTracer.ALL);
        all.addAll(ShadowSettings.ALL);
        all.addAll(DisplayTransform.ALL);
        return List.copyOf(all);
    }
}
