package com.truesim.engine.app;

import java.util.List;

/**
 * An engine part installed once, at setup ({@link AppConfig#use}): rendering, input, XR, assets, worlds. A plugin
 * brings its whole slice (settings, importers, techniques, game systems), starts in the {@link Stage}s and stops in
 * reverse order. Other code reaches it by its key ({@link Engine#get}).
 *
 * <pre>{@code
 * public final class Xr implements Plugin {
 *     public static final PluginKey<Xr> KEY = PluginKey.of("xr", Xr.class);
 *     public PluginKey<Xr> key() { return KEY; }
 *     public List<PluginKey<?>> requires() { return List.of(Rendering.KEY); }
 *     public boolean optional() { return true; }                       // no headset: play on the desktop
 *     public void start(Stage stage, Startup startup) {
 *         if (stage == Stage.BEFORE_INSTANCE && !connect()) throw new StartupFailure("no headset found");
 *         if (stage == Stage.RUN) startup.driveFrames(this::frames);
 *     }
 * }
 * }</pre>
 */
public interface Plugin {

    /** Its key, always the same one: one plugin per key. */
    PluginKey<?> key();

    /** The plugins it needs installed and started before it. */
    default List<PluginKey<?>> requires() {
        return List.of();
    }

    /**
     * Whether the game can run without it: a failure to start it may be answered with
     * {@link StartupFailure.Response#CONTINUE_WITHOUT}. Code using an optional plugin reads it with
     * {@link Engine#find}.
     */
    default boolean optional() {
        return false;
    }

    /**
     * Its part of startup stage {@code stage}. Throws a {@link StartupFailure} (or anything else, wrapped in one) if it
     * cannot start.
     */
    default void start(Stage stage, Startup startup) throws Exception {
    }

    /** Before the game's update, each frame, in start order: sample input, drain what finished. */
    default void beforeUpdate(Frame frame) {
    }

    /** After the game's update, each frame, in start order: sync the scene, record the frame. */
    default void afterUpdate(Frame frame) {
    }

    /**
     * Stops it, in reverse start order: the engine shuts down, startup was given up, or it was dropped after failing to
     * start. Called after a failed start too, so it stops whatever part started.
     */
    default void stop() {
    }
}
