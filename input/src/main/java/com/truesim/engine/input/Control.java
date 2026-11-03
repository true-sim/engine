package com.truesim.engine.input;

/**
 * A control of a kind of device, generic: {@code key:W} is the W key of any keyboard, {@code pad:SOUTH} the south
 * button of any of the player's gamepads (application-api.md D72). One object per control, compared by identity; its
 * text ({@link #toString()}) is how bindings save it. {@link Controls} names those of the platform's devices.
 */
public final class Control {

    private final DeviceClass type;
    private final int index;

    Control(DeviceClass type, int index) {
        this.type = type;
        this.index = index;
    }

    /** The kind of device it belongs to. */
    public DeviceClass type() {
        return type;
    }

    /** Its index among its kind's controls. */
    public int index() {
        return index;
    }

    /** What it reports. */
    public ControlKind kind() {
        return type.kind(index);
    }

    /** Its name, upper case: {@code W}, {@code SOUTH}, {@code LEFT_TRIGGER}. */
    public String name() {
        return type.name(index);
    }

    /** As bindings save it: {@code key:W}. */
    @Override
    public String toString() {
        return type.prefix() + ":" + type.name(index);
    }
}
