package com.truesim.engine.graph;

/**
 * What the device can do, for techniques choosing how to build: {@link GraphBuilder#capabilities()}.
 *
 * @param rayTracing ray queries and acceleration structures
 * @param meshShaders mesh (and task) shaders
 * @param primitiveIds primitive IDs in fragment shaders
 */
public record Capabilities(boolean rayTracing, boolean meshShaders, boolean primitiveIds) {
}
