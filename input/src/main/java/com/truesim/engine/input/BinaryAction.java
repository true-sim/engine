package com.truesim.engine.input;

/**
 * An action whose value is down or not: a button, a key, an analog control past a threshold (D77). No analog value
 * (D76): read {@link Player#down}, {@link Player#pressed}, {@link Player#released}.
 */
public final class BinaryAction extends Action {

    BinaryAction(ActionGroup group, String name, Binding... defaults) {
        super(group, name, defaults);
    }

    @Override
    public BinaryAction combine(Combine rule) {
        super.combine(rule);
        return this;
    }

    @Override
    int dimensions() {
        return 0;
    }
}
