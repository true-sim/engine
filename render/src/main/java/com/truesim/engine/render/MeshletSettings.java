package com.truesim.engine.render;

import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.settings.Setting;
import java.util.List;

/**
 * The meshlet renderer's room for what culling finds, read by {@link MeshletCulling} from its graph's settings as the
 * graph builds. Culling grows its buffers to what frames want up to these limits; a frame wanting more than fits loses
 * nothing in the chunks (culls run in rounds) and, in a view's lists, what does not fit until the lists grew as the
 * frame ended. Whatever is left out all the same is counted ({@link MeshletRenderer#droppedInstances},
 * {@link MeshletRenderer#viewsAtLimit}, the renderer's profiler counters) and reported once in the log.
 */
public final class MeshletSettings {

    /**
     * The most chunks of 64 meshlets (16 bytes each) the views culled together may share: the room grows to what frames
     * want, up to this; 16,777,216 (256 MiB) by default. Past it, culls run in rounds
     * ({@link #CULL_ROUNDS}).
     */
    public static final Setting<Integer> CHUNK_LIMIT = Setting.integer("render.meshlets.chunkLimit", 1 << 24, 1,
        CullPasses.CHUNK_LIMIT, "the most chunks of 64 meshlets (16 bytes each) culling grows to");

    /**
     * The most rounds a cull runs while frames want more chunks than there is room for (the first frames of a large
     * scene, before the room grew): each round culls what the last had no room for, so nothing is left out until
     * this many rounds were full. Once frames fit with room to spare, culls take one round.
     */
    public static final Setting<Integer> CULL_ROUNDS = Setting.integer("render.meshlets.cullRounds", 16, 1,
        CullPasses.MAX_ROUNDS, "the most rounds a cull runs while frames want more chunks than there is room for");

    /**
     * The most meshlets a view's lists grow to, in its opaque bin and in each other raster bin (at most 4,194,304, the
     * guaranteed mesh dispatch size): a view whose lists overflowed grows them as a frame ends.
     */
    public static final Setting<Integer> VIEW_DRAW_LIMIT = Setting.integer("render.meshlets.viewDrawLimit", 1 << 22, 1,
        1 << 22, "the most meshlets a view's lists grow to, per raster bin");

    /**
     * The most triangles a view's index buffer (the indexed path, 12 bytes each) grows to in its opaque bin, and in
     * each other raster bin.
     */
    public static final Setting<Integer> VIEW_TRIANGLE_LIMIT = Setting.integer("render.meshlets.viewTriangleLimit",
        1 << 25, 1, 1 << 28, "the most triangles a view's index buffer grows to, per raster bin");

    /** Every one of them, for registration. */
    public static final List<Setting<?>> ALL = List.of(CHUNK_LIMIT, CULL_ROUNDS, VIEW_DRAW_LIMIT, VIEW_TRIANGLE_LIMIT);

    private MeshletSettings() {
    }

    /** Has {@code renderer} grow to the limits the settings of {@code g}'s graph give: it rebuilds as they change. */
    static void apply(MeshletRenderer renderer, GraphBuilder g) {
        renderer.limits(g.setting(CHUNK_LIMIT), g.setting(CULL_ROUNDS), g.setting(VIEW_DRAW_LIMIT),
            g.setting(VIEW_TRIANGLE_LIMIT));
    }
}
