package com.truesim.engine.platform;

/** What a window's task bar entry shows of a long task ({@link Window#setProgress}), where the system has one. */
public enum WindowProgress {
    /** Nothing. */
    NONE,
    /** Busy, for an unknown time. */
    INDETERMINATE,
    /** A share done. */
    NORMAL,
    /** A share done, paused. */
    PAUSED,
    /** A share done, failed. */
    ERROR
}
