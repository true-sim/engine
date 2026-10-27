package com.truesim.engine.platform;

/** A touchscreen or trackpad; {@link PlatformListener#touch} events carry its {@code id}. */
public record TouchDevice(long id, String name, TouchDeviceType type) {
}
