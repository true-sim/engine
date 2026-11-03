package com.truesim.engine.input;

/**
 * An action whose value is a 3D vector: flying, a 6-DOF controller's translation; read {@link
 * Player#x(Axis3Action)} and its siblings.
 */
public final class Axis3Action extends Action {

    Axis3Action(ActionGroup group, String name, Binding... defaults) {
        super(group, name, defaults);
    }

    @Override
    public Axis3Action combine(Combine rule) {
        super.combine(rule);
        return this;
    }

    @Override
    int dimensions() {
        return 3;
    }
}
