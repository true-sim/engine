package com.truesim.engine.xr;

/** A failure of the headset or its runtime, with the OpenXR result where there is one. */
public class HeadsetException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HeadsetException(String message) {
        super(message);
    }

    /**
     * No headset to use: no OpenXR runtime installed, or none connected. An app falls back to the desktop, a test
     * skips.
     */
    public static final class Unavailable extends HeadsetException {

        private static final long serialVersionUID = 1L;
        public Unavailable(String message) {
            super(message);
        }
    }
}
