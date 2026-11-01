package com.truesim.engine.app;

/**
 * The startup stages, in order. Every plugin is started once per stage ({@link Plugin#start}), plugins in an order
 * where each comes after those it requires, so a plugin can ask in one stage for what another provides in the next
 * (instance extensions before the instance, device features before the device).
 */
public enum Stage {
    /** Before the GPU instance exists: connect a headset, which names the instance extensions it needs. */
    BEFORE_INSTANCE,
    /** The instance is made: ask for its extensions and layers. */
    INSTANCE,
    /** Pick the GPU, or filter those that cannot do what a plugin needs. */
    GPU,
    /** The device is made: ask for its extensions and features. */
    DEVICE,
    /** The frame graph exists: add techniques, views and outputs. */
    GRAPH,
    /** Everything is up: last wiring before the first frame. */
    RUN
}
