package com.truesim.engine.app;

/** Runs an application: the engine owns the loop. */
public final class TrueSim {

    private TrueSim() {
    }

    /**
     * Starts {@code app} with the command line {@code args}, runs frames until it quits, then shuts it down. A startup
     * failure the application answered with {@link StartupFailure.Response#QUIT} is thrown.
     */
    public static void run(Application app, String... args) {
        try (Engine engine = Engine.create(app, args)) {
            while (engine.running()) {
                engine.step();
            }
        }
    }
}
