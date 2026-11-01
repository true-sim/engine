package com.truesim.engine.settings;

import java.util.Objects;

/**
 * Whether a setting can be changed now, and why not: for options screens to grey it out with a reason. Asked of
 * {@link EngineSettings#availability}; decided by the rules the owning part registers ({@link EngineSettings#availableWhen},
 * {@link EngineSettings#dependsOn}), the game's locks ({@link EngineSettings#lock}) and whether a change waits for a
 * restart.
 *
 * @param state what it is
 * @param reason why, for options screens; empty when available
 */
public record Availability(State state, String reason) {

    /** What a setting's availability is. */
    public enum State {
        /** Can be changed, and a change takes effect. */
        AVAILABLE,
        /** The hardware or platform does not support it: no HDR display, no ray tracing. */
        UNSUPPORTED,
        /** Another setting turns it off: soft shadows while shadows are off. */
        DEPENDS_ON,
        /** The game fixes its value. */
        LOCKED,
        /** Can be changed; the change takes effect when what reads it is made again. */
        RESTART_PENDING
    }

    /** Available. */
    public static final Availability AVAILABLE = new Availability(State.AVAILABLE, "");

    public Availability {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reason, "reason");
    }

    /** Not supported here, because {@code reason}. */
    public static Availability unsupported(String reason) {
        return new Availability(State.UNSUPPORTED, reason);
    }

    /** Off while another setting is, because {@code reason}. */
    public static Availability dependsOn(String reason) {
        return new Availability(State.DEPENDS_ON, reason);
    }

    /** Fixed by the game, because {@code reason}. */
    public static Availability locked(String reason) {
        return new Availability(State.LOCKED, reason);
    }

    /** Changed, waiting for a restart of what reads it. */
    public static Availability restartPending(String reason) {
        return new Availability(State.RESTART_PENDING, reason);
    }

    /** Whether a player may change it now: available, or changed and waiting for a restart. */
    public boolean changeable() {
        return state == State.AVAILABLE || state == State.RESTART_PENDING;
    }

    /** A rule deciding a setting's availability from the settings (and whatever hardware it knows of). */
    @FunctionalInterface
    public interface Rule {

        /** The availability now, read from {@code settings} (the view the rule was registered in). */
        Availability evaluate(EngineSettings settings);
    }
}
