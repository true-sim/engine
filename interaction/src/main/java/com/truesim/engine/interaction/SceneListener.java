package com.truesim.engine.interaction;

/**
 * Hears scene interaction events on a node ({@link InteractionWorld#on}). The event is reused: read it during the call,
 * keep nothing of it. Must not allocate per call.
 */
@FunctionalInterface
public interface SceneListener {

    void handle(SceneEvent event);
}
