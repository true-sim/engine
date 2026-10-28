package com.truesim.engine.render;

import java.util.Objects;

/**
 * What a {@link SceneDraw} writes besides depth: nothing (shadow maps, depth prepasses), surfaces shaded as they are
 * drawn (forward), or IDs of what each pixel shows (a visibility buffer). G-buffer layers and motion vectors come
 * later.
 *
 * @param kind what is written
 * @param colorFormat the color format, for {@link Kind#SHADED}
 * @param lights the lights shading them, through light clusters (null: the sun alone), for {@link Kind#SHADED}
 * @param clear the color behind everything, read when the frame runs, for {@link Kind#SHADED}
 */
public record SurfaceOutput(Kind kind, int colorFormat, Lights lights, float[] clear) {

    /** What a draw writes besides depth. */
    public enum Kind {
        DEPTH,
        SHADED,
        IDS
    }

    public SurfaceOutput {
        Objects.requireNonNull(kind, "kind");
    }

    /** Depth only. */
    public static SurfaceOutput depth() {
        return new SurfaceOutput(Kind.DEPTH, 0, null, null);
    }

    /** Shaded color in {@code colorFormat}, lit by {@code lights} (null: the sun), cleared to {@code clear} (r, g, b). */
    public static SurfaceOutput shaded(int colorFormat, Lights lights, float[] clear) {
        return new SurfaceOutput(Kind.SHADED, colorFormat, lights, Objects.requireNonNull(clear, "clear"));
    }

    /** IDs ({@link MeshletRenderer#ID_FORMAT}): what each pixel shows, for shading afterwards. */
    public static SurfaceOutput ids() {
        return new SurfaceOutput(Kind.IDS, MeshletRenderer.ID_FORMAT, null, null);
    }
}
