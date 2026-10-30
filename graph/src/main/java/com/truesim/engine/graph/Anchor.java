package com.truesim.engine.graph;

import java.util.Objects;

/**
 * A named point in the order of the techniques that modify a result: techniques placed at an earlier anchor run first.
 * The built-in anchors follow the frame: the opaque scene, then what is drawn over it, post effects, tone mapping and
 * the UI. Plugins place their techniques at an anchor and, within it, after or before others by id ({@link Order}).
 *
 * @param name what it is called, for messages
 * @param rank where it is: lower runs first
 */
public record Anchor(String name, int rank) {

    /** Where a technique with no anchor runs: before every built-in one. */
    public static final Anchor FIRST = new Anchor("first", 0);
    /** The opaque scene's changes, such as decals. */
    public static final Anchor OPAQUE = new Anchor("opaque", 100);
    /** What is drawn over the opaque scene: transparency, particles. */
    public static final Anchor TRANSPARENT = new Anchor("transparent", 200);
    /** Post effects in scene light: exposure, bloom, depth of field. */
    public static final Anchor POST = new Anchor("post", 300);
    /** Tone mapping, from scene light to display values. */
    public static final Anchor TONE_MAP = new Anchor("tone map", 400);
    /** What is drawn over the final image: UI, debug overlays. */
    public static final Anchor UI = new Anchor("ui", 500);

    public Anchor {
        Objects.requireNonNull(name, "name");
    }

    @Override
    public String toString() {
        return "anchor " + name;
    }
}
