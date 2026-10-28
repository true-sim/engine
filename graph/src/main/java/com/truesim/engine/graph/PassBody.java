package com.truesim.engine.graph;

/**
 * Records a pass's commands. Runs every frame the pass is needed, after the graph's barriers have put its images in the
 * declared layouts; it must not allocate.
 */
@FunctionalInterface
public interface PassBody {

    void record(PassContext context);
}
