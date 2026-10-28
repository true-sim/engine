package com.truesim.engine.graph;

/**
 * A reusable building block, such as a blur, a downsample chain or a min/max pyramid: it adds the passes that compute
 * its result from its inputs. Requests are values (records, as a rule): {@link GraphBuilder#request} builds each
 * distinct request once per graph build, and every equal request from any technique gets the same result, so shared
 * work runs once. Equal means the same inputs (image handles) and the same parameters.
 *
 * @param <R> the result, e.g. an {@link Image}
 */
public interface Request<R> {

    /** Adds the passes and returns the result. */
    R build(GraphBuilder g);
}
