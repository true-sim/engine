package com.truesim.engine.xr;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** What the headset tells the engine without a headset: session states as visibility and focus, wide strings. */
class StateReportTest {

    @Test
    void idleIsHiddenAndAMenuOverTheAppIsFocusLost() {
        try (Engine engine = Engine.create(new Application() { })) {
            StateReport report = new StateReport();
            assertThat(report.reported()).isFalse();
            report.report(engine, true, true);
            assertThat(engine.visible()).isTrue();
            assertThat(engine.focused()).isTrue();
            report.report(engine, false, false); // idle: taken off
            assertThat(engine.visible()).isFalse();
            assertThat(engine.focused()).isFalse();
            engine.focusChanged(true); // a desktop window's focus counts until the headset changes again
            report.report(engine, false, false);
            assertThat(engine.focused()).isTrue();
            report.report(engine, true, false); // visible again; its focus unchanged: the window's stays
            assertThat(engine.visible()).isTrue();
            assertThat(engine.focused()).isTrue();
            report.report(engine, true, true);
            report.report(engine, true, false); // a system menu over the app
            assertThat(engine.focused()).isFalse();
            assertThat(report.reported()).isTrue();
        }
    }

    @Test
    void endpointIdsAreReadAsUtf16() {
        String id = "{0.0.0.00000000}.{8a1b2c3d-0000-4e5f-9a8b-123456789abc}";
        ByteBuffer buffer = ByteBuffer.allocate(256);
        buffer.put(id.getBytes(StandardCharsets.UTF_16LE));
        assertThat(Headset.wide(buffer)).isEqualTo(id);
        assertThat(Headset.wide(ByteBuffer.allocate(8))).isEmpty();
    }
}
