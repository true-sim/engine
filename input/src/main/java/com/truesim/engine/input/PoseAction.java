package com.truesim.engine.input;

/**
 * An action whose value is a position and orientation: an XR controller's aim or grip, the head; read {@link
 * Player#pose}. Read late in the frame by what draws it, never interpolated (D91).
 */
public final class PoseAction extends Action {

    PoseAction(ActionGroup group, String name, Binding... defaults) {
        super(group, name, defaults);
    }

    @Override
    public PoseAction combine(Combine rule) {
        super.combine(rule);
        return this;
    }

    @Override
    int dimensions() {
        return 7;
    }
}
