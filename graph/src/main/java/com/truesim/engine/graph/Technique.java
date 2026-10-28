package com.truesim.engine.graph;

import java.util.List;

/**
 * A piece of the frame, such as the scene's geometry, bloom or tone mapping. It says which results it provides and
 * builds its passes, requiring the results it needs by {@link Key}; it never needs to know which technique makes them.
 * A technique is built only when something the frame outputs needs one of its results, and then once per graph build.
 *
 * <pre>{@code
 * final class Bloom implements Technique {
 *     public List<Key> provides() { return List.of(BLOOM); }
 *
 *     public void build(GraphBuilder g) {
 *         Image scene = g.require(SCENE_COLOR);
 *         Image blurred = g.request(new Blur(scene, 6, 0.25));   // shared with any identical request
 *         Image out = g.createLike("bloom", scene, 1);
 *         g.pass("bloom combine")
 *             .read(scene, Access.SAMPLED_COMPUTE).read(blurred, Access.SAMPLED_COMPUTE)
 *             .write(out, Access.STORAGE_WRITE)
 *             .record(ctx -> ...);
 *         g.provide(BLOOM, out);
 *     }
 * }
 * }</pre>
 */
public interface Technique {

    /** The keys {@link #build} provides; every one of them, each by one technique of the graph. */
    List<Key> provides();

    /**
     * The keys this technique changes after their provider: it takes each with {@link GraphBuilder#modify} and writes
     * it in place or provides a new version, e.g. transparency over the scene's color, or a post effect. Every
     * technique requiring the key gets the version after all its modifiers. None by default.
     */
    default List<Key> modifies() {
        return List.of();
    }

    /**
     * Where among the modifiers of a key this one runs: lower first, equal stages in the order the techniques were
     * added. E.g. post effects: exposure before bloom before tone mapping.
     */
    default int stage() {
        return 0;
    }

    /**
     * Adds the technique's passes; called when the graph is (re)built, not per frame, so it may allocate (annotate it
     * {@code @AllowAllocation} in allocation-free modules). The pass bodies it creates run every frame and must not.
     */
    void build(GraphBuilder g);
}
