package com.truesim.engine.platform;

/** A platform call failed; the message includes SDL's error text. */
public final class PlatformException extends RuntimeException {

    private static final long serialVersionUID = 1;

    public PlatformException(String message) {
        super(message);
    }
}
