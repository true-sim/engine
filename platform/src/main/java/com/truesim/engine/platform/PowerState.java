package com.truesim.engine.platform;

/** A device's power source; the battery charge is reported separately, as a percentage or -1 when unknown. */
public enum PowerState {
    UNKNOWN,
    /** Running on its battery. */
    ON_BATTERY,
    /** Powered by its connection, without a battery. */
    NO_BATTERY,
    CHARGING,
    CHARGED
}
