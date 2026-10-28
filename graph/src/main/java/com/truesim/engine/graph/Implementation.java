package com.truesim.engine.graph;

/**
 * How requests of one type are computed, chosen per graph with {@link FrameGraph#implement}: a request says what is
 * wanted (a 6 pixel blur of the scene at quarter size), an implementation how (Gaussian, Kawase, a cheap box filter).
 * Swapping the implementation changes it for every technique at once; without one, a request builds itself.
 *
 * @param <Q> the request type
 * @param <R> its result
 */
@FunctionalInterface
public interface Implementation<Q extends Request<R>, R> {

    /** Adds the passes computing {@code request} and returns the result. */
    R build(Q request, GraphBuilder g);
}
