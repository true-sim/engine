package com.truesim.engine.render;

import com.truesim.engine.render.shadows.ShadowSettings;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.vulkan.RecordBuffer;

/**
 * The render module's settings: those of no single technique, and the core renderer's registration of its settings
 * for discovery ({@link #register}). Techniques read them from the frame graph's settings unless given their own value.
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
     * MiB a frame of dynamic textures' changed regions staged and copied at the frame's start
     * ({@link Textures#update}); regions past it wait for a later frame.
     */
    public static final Setting<Integer> TEXTURE_UPDATE_BUDGET = Setting.integer("render.textureUpdateBudget", 8, 1,
        1024, "MiB a frame of dynamic textures' changed texels uploaded");

    /**
     * Milliseconds a frame the scene path spends taking in streamed content made ready on workers (meshes placed in the
     * pool and their renderables shown, textures added) and letting go of what removed renderables used (material
     * blocks, meshes, the cache's hold); the rest waits for the next frames, so no frame takes all of a cell at once.
     * 0: all in the frame it comes or goes. Removed renderables stop drawing at once either way. It runs from the
     * frame's start ({@code SceneResources.beginFrame}), the scene syncs' own work in it counted. 0.1 ms by default:
     * the samples' stream benchmark's frames taking cells out then cost what the hand-written path's do (0.74 to 0.76
     * against 0.71 to 0.76 ms over two-minute runs, interleaved), and a row of cells still arrives within a second.
     */
    public static final Setting<Float> STREAM_FRAME_BUDGET = Setting.decimal("render.streamFrameBudget", 0.1f, 0,
        100, "ms a frame spent taking in and letting go of streamed content (0: all at once)");

    /**
     * Meshes removed whose ranges of the geometry pool are kept for a mesh of the same sizes to take whole
     * ({@link GeometryPool#spares}): streaming content that comes and goes in equal sizes (generated tiles, rocks)
     * places and removes meshes without allocating and freeing their ranges. The memory they hold is given back before
     * the pool grows. 0: none kept.
     */
    public static final Setting<Integer> MESH_SPARES = Setting.integer("render.meshSpares", 64, 0, 65536,
        "removed meshes' pool ranges kept for meshes of the same sizes (0: none)");

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

    /**
     * Registers the core renderer's settings in {@code registry} for discovery ({@link SettingRegistry#all(String)}):
     * these, the transparency's, the selection's, the light clusters', the shadows', the denoiser's and the display
     * transform's, slots with their kinds' tunables, hidden. {@link RenderPlugin} calls it; a library's plugin registers
     * its own techniques' settings the same way, so nothing lists them by hand.
     */
    public static void register(SettingRegistry registry) {
        registry.addHiddenSlots(INTERPOLATION_SLOT, Transparency.SLOT, SelectionOutline.SLOT, DisplayTransform.SLOT)
            .addHidden(OCCLUSION, CHANGE_LIST, CHANGE_LIST_CHUNK, STREAM_BUDGET, TEXTURE_UPDATE_BUDGET, STREAM_FRAME_BUDGET,
                MESH_SPARES, Transparency.SORTED_RUNS, Displacement.MODE)
            .addHidden(ClusteredLights.ALL.toArray(new Setting<?>[0]))
            .addHidden(ShadowSettings.ALL.toArray(new Setting<?>[0]))
            .addHidden(Denoiser.ALL.toArray(new Setting<?>[0]))
            .addHidden(DisplayTransform.ALL.toArray(new Setting<?>[0]));
    }
}
