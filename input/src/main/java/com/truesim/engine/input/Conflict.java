package com.truesim.engine.input;

/**
 * A binding of another action on the same controls (application-api.md: binding capture's conflict detection):
 * {@code action}'s {@code binding}. Whether both can be active together is the game's to judge from their groups.
 */
public record Conflict(Action action, Binding binding) {
}
