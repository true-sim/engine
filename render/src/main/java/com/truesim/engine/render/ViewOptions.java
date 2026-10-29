package com.truesim.engine.render;

/**
 * What a {@link RenderView} holds and draws: room for {@code draws} visible meshlets (at most 4,194,304: the guaranteed
 * mesh dispatch size) and, on the indexed path, {@code triangles} visible triangles (12 bytes each in its index
 * buffer); two-phase occlusion culling with a history of its own; the instances drawn: those on any layer of
 * {@code layers}, and with {@code castersOnly} only those casting shadows. Size each view for what it sees: a camera
 * large, a shadow cascade or a mirror smaller.
 *
 * <pre>{@code
 * renderer.createView(ViewOptions.of(1 << 18, 1 << 22).withOcclusion(true));      // the camera
 * renderer.createView(ViewOptions.shadow(1 << 16, 1 << 20));                      // a shadow map's light
 * renderer.createView(ViewOptions.of(1 << 16, 1 << 20).withLayers(~PLAYER_HEAD)); // a mirror
 * }</pre>
 */
public record ViewOptions(int draws, int triangles, boolean occlusion, int layers, boolean castersOnly) {

    public ViewOptions {
        if (draws < 1 || draws > 1 << 22 || triangles < 1) {
            throw new IllegalArgumentException("A view holds 1 to 4,194,304 draws and triangles: " + draws + ", "
                + triangles);
        }
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
        return new ViewOptions(draws, triangles, on, layers, castersOnly);
    }

    public ViewOptions withLayers(int mask) {
        return new ViewOptions(draws, triangles, occlusion, mask, castersOnly);
    }

    public ViewOptions withCastersOnly(boolean on) {
        return new ViewOptions(draws, triangles, occlusion, layers, on);
    }
}
