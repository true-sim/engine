package com.truesim.engine.input;

/**
 * An action whose value is one number: a trigger, a zoom, a throttle; read {@link Player#value(Axis1Action)}.
 */
public final class Axis1Action extends Action {

    Axis1Action(ActionGroup group, String name, Binding... defaults) {
        super(group, name, defaults);
    }

    @Override
    public Axis1Action combine(Combine rule) {
        super.combine(rule);
        return this;
    }

    @Override
    int dimensions() {
        return 1;
    }
}
