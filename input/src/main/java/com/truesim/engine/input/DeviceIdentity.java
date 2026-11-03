package com.truesim.engine.input;

import com.truesim.annotations.AllowAllocation;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * What tells a device again (application-api.md D72): SDL's GUID (the bus, vendor, product and version: the same for
 * two identical controllers), the serial number and the system's path, each empty when unknown. Devices match by GUID,
 * then serial, then path: two devices with the same GUID are one if their serials agree (when both are known), else if
 * their paths agree (when both are known), else if neither tells them apart. Keyboards and mice have none: they are
 * not told apart.
 */
public record DeviceIdentity(String guid, String serial, String path) {

    /** Nothing known: matches no device. */
    public static final DeviceIdentity NONE = new DeviceIdentity("", "", "");

    public DeviceIdentity {
        Objects.requireNonNull(guid, "guid");
        Objects.requireNonNull(serial, "serial");
        Objects.requireNonNull(path, "path");
    }

    /** Whether it tells nothing: no GUID. */
    public boolean unknown() {
        return guid.isEmpty();
    }

    /** Whether {@code other} is the same device, by GUID, then serial, then path. */
    public boolean matches(DeviceIdentity other) {
        if (guid.isEmpty() || !guid.equals(other.guid)) {
            return false;
        }
        if (!serial.isEmpty() && !other.serial.isEmpty()) {
            return serial.equals(other.serial);
        }
        if (!path.isEmpty() && !other.path.isEmpty()) {
            return path.equals(other.path);
        }
        return true;
    }

    /**
     * How closely {@code other} matches: 3 by serial, 2 by path, 1 by GUID alone, 0 not at all. Replugging picks the
     * closest of the devices a player lost (D73).
     */
    public int closeness(DeviceIdentity other) {
        if (!matches(other)) {
            return 0;
        }
        if (!serial.isEmpty() && !other.serial.isEmpty()) {
            return 3;
        }
        return !path.isEmpty() && !other.path.isEmpty() ? 2 : 1;
    }

    /** As a binding saves it: {@code guid,serial,path}, each percent-encoded. */
    @AllowAllocation("saving bindings")
    public String text() {
        return encode(guid) + "," + encode(serial) + "," + encode(path);
    }

    /** {@link #text()}'s form read back; throws if it is not three parts. */
    @AllowAllocation("parsing bindings")
    public static DeviceIdentity parse(String text) {
        String[] parts = text.split(",", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("A device is guid,serial,path: '" + text + "'");
        }
        return new DeviceIdentity(decode(parts[0]), decode(parts[1]), decode(parts[2]));
    }

    private static String encode(String part) {
        return URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String decode(String part) {
        return URLDecoder.decode(part, StandardCharsets.UTF_8);
    }
}
