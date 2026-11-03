package com.truesim.engine.render;

import java.util.Objects;

/**
 * What a {@link SceneDraw} writes besides depth: nothing (shadow maps, depth prepasses), surfaces shaded as they are
 * drawn (forward), or IDs of what each pixel shows (a visibility buffer); shaded, with the surface outputs too where a
 * technique asks for them ({@link SurfaceOutputs}). Motion vectors come later.
 *
 * @param kind what is written
 * @param colorFormat the color format, for {@link Kind#SHADED}
 * @param clear the color behind everything, read when the frame runs, for {@link Kind#SHADED}
 * @param surfaces with {@link Kind#SHADED}: the surface outputs too ({@link SurfaceOutputs})
 */
public record SurfaceOutput(Kind kind, int colorFormat, float[] clear, boolean surfaces) {

    /** What a draw writes besides depth. */
    public enum Kind {
        DEPTH,
        SHADED,
        IDS
    }

    public SurfaceOutput {
        Objects.requireNonNull(kind, "kind");
        if (surfaces && kind != Kind.SHADED) {
            throw new IllegalArgumentException("Surface outputs come with shaded color only");
        }
    }

    /** Depth only. */
    public static SurfaceOutput depth() {
        return new SurfaceOutput(Kind.DEPTH, 0, null, false);
    }

    /**
     * Shaded color in {@code colorFormat}, cleared to {@code clear} (r, g, b), lit by the frame's
     * {@link Keys#LIGHT_LIST} if there is one, else by the sun.
     */
    public static SurfaceOutput shaded(int colorFormat, float[] clear) {
        return shaded(colorFormat, clear, false);
    }

    /** {@link #shaded(int, float[])}, with {@code surfaces} the surface outputs too ({@link SurfaceOutputs}). */
    public static SurfaceOutput shaded(int colorFormat, float[] clear, boolean surfaces) {
        return new SurfaceOutput(Kind.SHADED, colorFormat, Objects.requireNonNull(clear, "clear"), surfaces);
    }

    /** IDs ({@link MeshletRenderer#ID_FORMAT}): what each pixel shows, for shading afterwards. */
    public static SurfaceOutput ids() {
        return new SurfaceOutput(Kind.IDS, MeshletRenderer.ID_FORMAT, null, false);
    }
}
