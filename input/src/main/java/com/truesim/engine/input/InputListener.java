package com.truesim.engine.input;

/**
 * What input reports about devices and players (application-api.md D73), at the frame's start, on the main thread.
 * Every method does nothing by default.
 */
public interface InputListener {

    /** {@code device} was plugged in and given to {@code player} (null: to no one, until assigned). */
    default void deviceConnected(Device device, Player player) {
    }

    /** {@code device}, of {@code player} (or null), was unplugged: e.g. pause and ask to plug it back. */
    default void deviceLost(Device device, Player player) {
    }

    /** {@code device} was plugged back and returned to {@code player}, who had it before. */
    default void deviceReturned(Device device, Player player) {
    }
}
