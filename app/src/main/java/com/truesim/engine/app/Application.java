package com.truesim.engine.app;

/**
 * A game or application: what the engine calls, on the main thread, from setup to shutdown. Every method has a
 * default, so the bare application installs nothing and does nothing; {@code BaseGame} builds on it.
 *
 * <pre>{@code
 * public final class Hello implements Application {
 *     public static void main(String[] args) { TrueSim.run(new Hello(), args); }
 *     public void configure(AppConfig app) { app.id("hello").use(new Rendering()); }
 *     public void update(Frame frame) { ... }
 * }
 * }</pre>
 */
public interface Application {

    /** What the application answers to a quit request. */
    enum QuitResponse {
        /** Quit now. */
        ALLOW,
        /** Not yet: the game asks the player (save first?) and calls {@link Engine#quit()} when done. */
        DEFER
    }

    /** Why a quit was requested. */
    enum QuitReason {
        /** A window's close button, or the platform's close shortcut (Alt+F4, Cmd+Q). */
        WINDOW_CLOSED,
        /** The operating system: logging out, shutting down. */
        SYSTEM,
        /** A plugin: a headset session ending, a lost device. */
        PLUGIN
    }

    /** Before anything starts: the plugins to install and what the player cannot change. */
    default void configure(AppConfig app) {
    }

    /** Every plugin has started: build the first scene. */
    default void start(Engine engine) {
    }

    /** Each frame, after the plugins' {@link Plugin#beforeUpdate} and before their {@link Plugin#afterUpdate}. */
    default void update(Frame frame) {
    }

    /** The engine shuts down: before the plugins stop. */
    default void stop() {
    }

    /**
     * A plugin could not start. The default quits for a required plugin and continues without an optional one; a game
     * might ask the player to plug a headset in and retry.
     */
    default StartupFailure.Response onStartupFailure(StartupFailure failure) {
        return failure.required() ? StartupFailure.Response.QUIT : StartupFailure.Response.CONTINUE_WITHOUT;
    }

    /** A quit was requested for {@code reason}; not asked when the quit is forced. The default allows it. */
    default QuitResponse onQuitRequested(QuitReason reason) {
        return QuitResponse.ALLOW;
    }

    /** The application gained ({@code true}) or lost keyboard focus. */
    default void onFocusChanged(boolean focused) {
    }

    /** The application became visible ({@code true}) or hidden: minimised, covered, a headset taken off. */
    default void onVisibilityChanged(boolean visible) {
    }

    /** The system suspends the application (a handheld put to sleep): save what must survive. */
    default void onSuspend() {
    }

    /** The system resumed the application. */
    default void onResume() {
    }
}
