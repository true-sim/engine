package com.truesim.engine.render;

/**
 * What a {@link RenderView} holds and draws: room for {@code draws} visible meshlets (at most 4,194,304: the guaranteed
 * mesh dispatch size) and, on the indexed path, {@code triangles} visible triangles (12 bytes each in its index
 * buffer); two-phase occlusion culling with a history of its own; the instances drawn: those on any layer of
 * {@code layers}, and with {@code castersOnly} only those casting shadows. Size each view for what it sees: a camera
 * large, a shadow cascade or a mirror smaller.
 *
 * <p>Meshlets of double-sided and of alpha-masked materials draw in raster bins of their own, each with room for
 * {@code binDraws} meshlets and {@code binTriangles} triangles: a quarter of the opaque bin's by default
 * ({@link #withBins}).
 *
 * <pre>{@code
 * renderer.createView(ViewOptions.of(1 << 18, 1 << 22).withOcclusion(true));      // the camera
 * renderer.createView(ViewOptions.shadow(1 << 16, 1 << 20));                      // a shadow map's light
 * renderer.createView(ViewOptions.of(1 << 16, 1 << 20).withLayers(~PLAYER_HEAD)); // a mirror
 * renderer.createView(ViewOptions.of(1 << 18, 1 << 22).withBins(1 << 17, 1 << 21)); // a forest: many masked leaves
 * }</pre>
 */
public record ViewOptions(int draws, int triangles, boolean occlusion, int layers, boolean castersOnly, int binDraws,
    int binTriangles) {

    public ViewOptions {
        if (draws < 1 || draws > 1 << 22 || triangles < 1 || binDraws < 1 || binDraws > 1 << 22 || binTriangles < 1
            || 3L * triangles + 6L * binTriangles > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("A view holds 1 to 4,194,304 draws and triangles, in each bin: " + draws
                + ", " + triangles + "; " + binDraws + ", " + binTriangles);
        }
    }

    /** Like the canonical constructor, the other bins a quarter of the opaque one's. */
    public ViewOptions(int draws, int triangles, boolean occlusion, int layers, boolean castersOnly) {
        this(draws, triangles, occlusion, layers, castersOnly, Math.max(1, draws / 4), Math.max(1, triangles / 4));
    }

    /** A camera's view: room for {@code draws} meshlets and {@code triangles} triangles, every layer, no occlusion. */
    public static ViewOptions of(int draws, int triangles) {
        return new ViewOptions(draws, triangles, false, -1, false);
    }

    /** A shadow map's view: casters only, every layer. */
    public static ViewOptions shadow(int draws, int triangles) {
        return new ViewOptions(draws, triangles, false, -1, true);
    }

    public ViewOptions withOcclusion(boolean on) {
        return new ViewOptions(draws, triangles, on, layers, castersOnly, binDraws, binTriangles);
    }

    public ViewOptions withLayers(int mask) {
        return new ViewOptions(draws, triangles, occlusion, mask, castersOnly, binDraws, binTriangles);
    }

    public ViewOptions withCastersOnly(boolean on) {
        return new ViewOptions(draws, triangles, occlusion, layers, on, binDraws, binTriangles);
    }

    /** Room in each of the double-sided and masked raster bins: {@code draws} meshlets, {@code triangles} triangles. */
    public ViewOptions withBins(int draws, int triangles) {
        return new ViewOptions(this.draws, this.triangles, occlusion, layers, castersOnly, draws, triangles);
    }

    /** The meshlets the view's lists hold, every bin's. */
    public long allDraws() {
        return draws + (MeshletRenderer.BINS - 1L) * binDraws;
    }

    /** The triangles the view's index buffer holds, every bin's (the indexed path). */
    public long allTriangles() {
        return triangles + (MeshletRenderer.BINS - 1L) * binTriangles;
    }
}
