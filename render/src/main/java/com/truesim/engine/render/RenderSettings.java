package com.truesim.engine.render;

import com.truesim.engine.render.shadows.ShadowSettings;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
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
     * How instances moved in fixed steps are drawn between steps (D91), a kind's id: blended on the GPU ({@code gpu},
     * the default, or {@code gpu_precise}), on the CPU ({@code cpu}), or not at all ({@code off}). The ways are the
     * sync's: a game's kind of this slot makes one of them ({@link #interpolation(EngineSettings)}).
     */
    public static final TechniqueSlot<Void, Interpolation> INTERPOLATION_SLOT = TechniqueSlot.of("render.interpolation",
        "gpu", true, "how instances and skinned joints moved in fixed steps are drawn between steps",
        techniques -> techniques.register(RenderSettings.INTERPOLATION_SLOT, RenderSettings.ON_GPU)
            .register(RenderSettings.INTERPOLATION_SLOT, RenderSettings.ON_GPU_PRECISE)
            .register(RenderSettings.INTERPOLATION_SLOT, RenderSettings.ON_CPU));

    /** An interpolation's tunables: none. */
    public record Blend() implements TechniqueConfig {
    }

    /** {@link Interpolation#GPU}: poses packed, blended on the GPU. The default. */
    public static final TechniqueKind<Blend, Void, Interpolation> ON_GPU = TechniqueKind.of(INTERPOLATION_SLOT, "gpu",
        new Blend(), "blended on the GPU, poses packed", (context, config) -> Interpolation.GPU);
    /** {@link Interpolation#GPU_PRECISE}: blended on the GPU, positions in two parts. */
    public static final TechniqueKind<Blend, Void, Interpolation> ON_GPU_PRECISE = TechniqueKind.of(INTERPOLATION_SLOT,
        "gpu_precise", new Blend(), "blended on the GPU, positions in two parts",
        (context, config) -> Interpolation.GPU_PRECISE);
    /** {@link Interpolation#CPU}: blended on the CPU into the instance table. */
    public static final TechniqueKind<Blend, Void, Interpolation> ON_CPU = TechniqueKind.of(INTERPOLATION_SLOT, "cpu",
        new Blend(), "blended on the CPU", (context, config) -> Interpolation.CPU);

    /** The interpolation's setting: {@link #INTERPOLATION_SLOT}'s. */
    public static final Setting<String> INTERPOLATION = INTERPOLATION_SLOT.setting();

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

    /** The interpolation {@code settings} choose ({@code off}: {@link Interpolation#OFF}). Allocates nothing. */
    public static Interpolation interpolation(EngineSettings settings) {
        TechniqueKind<?, Void, Interpolation> kind = INTERPOLATION_SLOT.chosen(settings);
        return kind == null ? Interpolation.OFF : kind.create(null, null);
    }

    /** Chooses {@code interpolation} as the player's in {@code settings}. */
    public static EngineSettings interpolation(EngineSettings settings, Interpolation interpolation) {
        return INTERPOLATION_SLOT.choose(settings, switch (interpolation) {
            case GPU -> ON_GPU;
            case GPU_PRECISE -> ON_GPU_PRECISE;
            case CPU -> ON_CPU;
            case OFF -> null;
        });
    }

    private static List<Setting<?>> all() {
        List<Setting<?>> all = new ArrayList<>();
        all.add(OCCLUSION);
        all.add(INTERPOLATION);
        all.add(CHANGE_LIST);
        all.add(CHANGE_LIST_CHUNK);
        all.add(STREAM_BUDGET);
        all.add(Transparency.SLOT.setting());
        all.add(Transparency.SORTED_RUNS);
        all.addAll(ScreenSpaceReflections.ALL);
        all.addAll(AmbientOcclusion.ALL);
        all.addAll(Denoiser.ALL);
        all.add(ProbeVolumeLighting.SLOT.setting());
        all.addAll(ProbeVolume.ALL);
        all.addAll(ClusteredLights.ALL);
        all.addAll(WorldLights.ALL);
        all.addAll(ReferencePathTracer.ALL);
        all.addAll(ShadowSettings.ALL);
        all.addAll(DisplayTransform.ALL);
        return List.copyOf(all);
    }
}
