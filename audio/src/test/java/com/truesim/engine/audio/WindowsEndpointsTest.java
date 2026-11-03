package com.truesim.engine.audio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Endpoint ids to the names SDL shows (§2.17, step 18): parsing everywhere, the registry on Windows. */
class WindowsEndpointsTest {

    @Test
    void theGuidIsTheIdsLastBracedPart() {
        assertThat(WindowsEndpoints.guid("{0.0.0.00000000}.{04eb5375-6b79-4ac3-890e-2c23e1e70852}"))
            .isEqualTo("{04eb5375-6b79-4ac3-890e-2c23e1e70852}");
        assertThat(WindowsEndpoints.guid("no braces")).isEmpty();
        assertThat(WindowsEndpoints.guid("only} closed")).isEmpty();
        assertThat(WindowsEndpoints.friendlyName("nothing")).isEmpty();
    }

    @Test
    void regsOutputIsParsed() {
        String out = "\r\nHKEY_LOCAL_MACHINE\\SOFTWARE\\Properties\r\n    " + WindowsEndpoints.NAME
            + "    REG_SZ    Headphones\r\n\r\n";
        assertThat(WindowsEndpoints.parse(out, WindowsEndpoints.NAME)).isEqualTo("Headphones");
        assertThat(WindowsEndpoints.parse(out, WindowsEndpoints.DEVICE)).isEmpty();
    }

    @Test
    void onWindowsAnEndpointHasTheNameSdlGivesIt() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            assertThat(WindowsEndpoints.friendlyName("{0.0.0.00000000}.{04eb5375-6b79-4ac3-890e-2c23e1e70852}"))
                .isEmpty();
            return;
        }
        String first = firstEndpoint();
        if (first.isEmpty()) {
            return; // a machine without playback endpoints
        }
        String name = WindowsEndpoints.friendlyName("{0.0.0.00000000}." + first);
        assertThat(name).isNotEmpty().contains(" (");
        assertThat(WindowsEndpoints.friendlyName("{0.0.0.00000000}.{00000000-0000-0000-0000-000000000000}"))
            .isEmpty();
        assertThat(WindowsEndpoints.value("HKLM\\NO\\SUCH\\KEY", "x")).isEmpty();
    }

    /** The first render endpoint's GUID in the registry, or "". */
    static String firstEndpoint() {
        try {
            Process reg = new ProcessBuilder("reg", "query", WindowsEndpoints.RENDER.substring(0,
                WindowsEndpoints.RENDER.length() - 1)).redirectErrorStream(true).start();
            for (String line : new String(reg.getInputStream().readAllBytes()).split("\\R")) {
                String guid = WindowsEndpoints.guid(line.trim());
                if (!guid.isEmpty() && !WindowsEndpoints.friendlyName("{0.0.0.00000000}." + guid).isEmpty()) {
                    return guid;
                }
            }
            return "";
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
