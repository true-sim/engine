package com.truesim.engine.input;

/**
 * A filter on an action's value, the game's (application-api.md D84): it may read game state (aim assist, a vehicle's
 * steering limit), is not saved, and is added in code ({@link Input#filter}). It keeps its state per player in a
 * slice of floats it is given (D85), reset to zeros on focus loss, a context switch and a disconnect (D86). Read in a
 * fixed step, it runs once per step on the step's delta; read in the frame, once per frame on the frame's delta.
 * Must allocate nothing.
 */
public interface ActionFilter {

    /** Floats of state it keeps per player. */
    default int stateSize() {
        return 0;
    }

    /**
     * Filters {@code value} in place (the action's components; nothing for binary actions, which have none) for
     * {@code player}, with its state at {@code state[at...]}, {@code seconds} since it last ran for this reader.
     */
    void apply(Player player, float[] value, float[] state, int at, double seconds);
}
