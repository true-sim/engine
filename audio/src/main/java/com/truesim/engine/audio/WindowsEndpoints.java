package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Names of Windows audio endpoints from their ids (§2.17): SDL 3.4 knows devices by name only (no endpoint ids until
 * SDL 3.6), and {@code XR_OCULUS_audio_device_guid} gives an id ({@code {0.0.0.00000000}.{guid}}). The name SDL shows
 * is the endpoint's friendly name, "Headphones (Oculus Virtual Audio Device)": the endpoint's name and its device's
 * description, read here from the endpoint's properties in the registry through {@code reg.exe}. At setup only.
 */
@AllowAllocation("setting up a device, rarely")
final class WindowsEndpoints {

    static final String RENDER = "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\MMDevices\\Audio\\Render\\";
    /** PKEY_Device_DeviceDesc's key: the endpoint's own name ("Headphones"). */
    static final String NAME = "{a45c254e-df1c-4efd-8020-67d146a850e0},2";
    /** PKEY_DeviceInterface_FriendlyName's key: the device's ("Oculus Virtual Audio Device"). */
    static final String DEVICE = "{b3f8fa53-0004-438e-9003-51a46e139bfc},6";

    private WindowsEndpoints() {
    }

    /** The endpoint's GUID in {@code id} (its last braced part), or "". */
    static String guid(String id) {
        int end = id.lastIndexOf('}');
        int start = end < 0 ? -1 : id.lastIndexOf('{', end);
        return start < 0 ? "" : id.substring(start, end + 1);
    }

    /** The friendly name of render endpoint {@code id}, as SDL names its device; "" off Windows or when unknown. */
    static String friendlyName(String id) {
        String guid = guid(id);
        if (guid.isEmpty() || !System.getProperty("os.name", "").startsWith("Windows")) {
            return "";
        }
        String name = value(RENDER + guid + "\\Properties", NAME);
        String device = value(RENDER + guid + "\\Properties", DEVICE);
        return name.isEmpty() ? "" : device.isEmpty() ? name : name + " (" + device + ")";
    }

    /** A string value of a registry key, or "". */
    static String value(String key, String name) {
        try {
            Process reg = new ProcessBuilder("reg", "query", key, "/v", name).redirectErrorStream(true).start();
            String out = new String(reg.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!reg.waitFor(5, TimeUnit.SECONDS) || reg.exitValue() != 0) {
                return "";
            }
            return parse(out, name);
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /** The value of {@code name} in {@code reg query}'s output: the line "name REG_SZ value". */
    static String parse(String out, String name) {
        for (String line : out.split("\\R")) {
            String trimmed = line.trim();
            int type = trimmed.indexOf("REG_SZ");
            if (trimmed.startsWith(name) && type > 0) {
                return trimmed.substring(type + "REG_SZ".length()).trim();
            }
        }
        return "";
    }
}
