package com.truesim.engine.input;

/**
 * An action whose value is a 2D vector: moving, looking; read {@link Player#x(Axis2Action)}, {@link
 * Player#y(Axis2Action)}.
 */
public final class Axis2Action extends Action {

    Axis2Action(ActionGroup group, String name, Binding... defaults) {
        super(group, name, defaults);
    }

    @Override
    public Axis2Action combine(Combine rule) {
        super.combine(rule);
        return this;
    }

    @Override
    int dimensions() {
        return 2;
    }
}
