package com.truesim.engine.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_AUDIO;
import static org.lwjgl.sdl.SDLInit.SDL_QuitSubSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Matching an endpoint id to SDL's devices on the real driver (§2.17, step 18), behind {@code TRUESIM_AUDIO=true}:
 * the playing endpoints' names from the registry are names SDL's WASAPI driver lists, so a headset's endpoint id
 * ({@code XR_OCULUS_audio_device_guid}) finds its device.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_AUDIO", matches = "true")
@EnabledOnOs(OS.WINDOWS)
class HeadsetDeviceProbe {

    @Test
    void endpointNamesAreSdlsDeviceNames() {
        SdlOutput.startSdlAudio(null);
        try {
            String guid = WindowsEndpointsTest.firstEndpoint();
            int matched = 0;
            int named = 0;
            java.util.List<String> lines = new java.util.ArrayList<>();
            try {
                Process reg = new ProcessBuilder("reg", "query", WindowsEndpoints.RENDER.substring(0,
                    WindowsEndpoints.RENDER.length() - 1)).redirectErrorStream(true).start();
                lines.addAll(java.util.Arrays.asList(new String(reg.getInputStream().readAllBytes()).split("\\R")));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            for (String line : lines) {
                String g = WindowsEndpoints.guid(line.trim());
                String name = g.isEmpty() ? "" : WindowsEndpoints.friendlyName("{0.0.0.00000000}." + g);
                if (!name.isEmpty()) {
                    named++;
                    boolean has = SdlOutput.has(name);
                    matched += has ? 1 : 0;
                    System.out.println((has ? "SDL lists   " : "not active  ") + name);
                }
            }
            System.out.printf("%d of %d named endpoints are SDL devices (first %s)%n", matched, named, guid);
            assertThat(matched).isPositive();
        } finally {
            SDL_QuitSubSystem(SDL_INIT_AUDIO);
        }
    }
}
