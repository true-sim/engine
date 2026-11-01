package com.truesim.engine.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AvailabilityTest {

    private static final Setting<Boolean> SHADOWS = Setting.bool("shadows.on", true, "shadows");
    private static final Setting<Boolean> SOFT = Setting.bool("shadows.soft", true, "soft edges");
    private static final Setting<Boolean> HDR = Setting.bool("present.hdr", true, "HDR output");
    private static final Setting<Integer> SAMPLES = Setting.integer("msaa.samples", 4, 1, 8, "samples").restartNeeded();
    private static final Setting<Float> FOV = Setting.decimal("camera.fov", 90, 60, 120, "field of view");

    @Test
    void rulesLocksAndRestartsDecideAvailability() {
        EngineSettings settings = new EngineSettings();
        assertThat(settings.availability(SOFT)).isEqualTo(Availability.AVAILABLE);
        // Depending on another setting.
        settings.dependsOn(SOFT, SHADOWS, on -> on, "shadows are off");
        assertThat(settings.availability(SOFT).state()).isEqualTo(Availability.State.AVAILABLE);
        settings.set(SHADOWS, false);
        assertThat(settings.availability(SOFT)).isEqualTo(Availability.dependsOn("shadows are off"));
        assertThat(settings.availability(SOFT).changeable()).isFalse();
        // Hardware: a rule reading what the owning part knows.
        boolean[] hdrDisplay = {false};
        EngineSettings.Subscription rule = settings.availableWhen(HDR,
            s -> hdrDisplay[0] ? Availability.AVAILABLE : Availability.unsupported("the display has no HDR"));
        assertThat(settings.availability(HDR).state()).isEqualTo(Availability.State.UNSUPPORTED);
        hdrDisplay[0] = true;
        assertThat(settings.availability(HDR).state()).isEqualTo(Availability.State.AVAILABLE);
        rule.close();
        // The game's lock: the value fixed, the player's kept.
        settings.set(FOV, 100f);
        EngineSettings.Subscription lock = settings.lock(FOV, 90f, "fixed in this mode");
        assertThat(settings.get(FOV)).isEqualTo(90f);
        assertThat(settings.preferred(FOV)).isEqualTo(100f);
        assertThat(settings.availability(FOV)).isEqualTo(Availability.locked("fixed in this mode"));
        lock.close();
        assertThat(settings.get(FOV)).isEqualTo(100f);
        assertThat(settings.availability(FOV)).isEqualTo(Availability.AVAILABLE);
        // Not live: pending a restart while the value differs from the one applied.
        settings.applied(SAMPLES);
        assertThat(settings.availability(SAMPLES).state()).isEqualTo(Availability.State.AVAILABLE);
        settings.set(SAMPLES, 8);
        assertThat(settings.availability(SAMPLES).state()).isEqualTo(Availability.State.RESTART_PENDING);
        assertThat(settings.availability(SAMPLES).changeable()).isTrue();
        settings.applied(SAMPLES);
        assertThat(settings.availability(SAMPLES).state()).isEqualTo(Availability.State.AVAILABLE);
        // Rules belong to their view.
        EngineSettings left = settings.scoped("window.left");
        left.dependsOn(SOFT, SHADOWS, on -> on, "left shadows are off");
        left.set(SHADOWS, false);
        assertThat(left.availability(SOFT).reason()).isEqualTo("left shadows are off");
        settings.set(SHADOWS, true);
        assertThat(settings.availability(SOFT).state()).isEqualTo(Availability.State.AVAILABLE);
        assertThat(left.availability(SOFT).state()).isEqualTo(Availability.State.DEPENDS_ON);
    }

    @Test
    void listenersHearOfChangesAtTheRefresh() {
        EngineSettings settings = new EngineSettings();
        List<String> heard = new ArrayList<>();
        settings.onAvailabilityChange(heard::add);
        settings.dependsOn(SOFT, SHADOWS, on -> on, "shadows are off");
        boolean[] hdrDisplay = {true};
        settings.availableWhen(HDR,
            s -> hdrDisplay[0] ? Availability.AVAILABLE : Availability.unsupported("the display has no HDR"));
        settings.refreshAvailability();
        assertThat(heard).as("available from the start: nothing to tell").isEmpty();
        settings.set(SHADOWS, false);
        assertThat(heard).as("not before the refresh").isEmpty();
        settings.refreshAvailability();
        assertThat(heard).containsExactly("shadows.soft");
        // Hardware changes are not seen until invalidated: refreshing costs nothing while nothing changed.
        hdrDisplay[0] = false;
        settings.refreshAvailability();
        assertThat(heard).hasSize(1);
        settings.invalidateAvailability().refreshAvailability();
        assertThat(heard).containsExactly("shadows.soft", "present.hdr");
        settings.lock(FOV, 90f, "fixed");
        settings.refreshAvailability();
        assertThat(heard).containsExactly("shadows.soft", "present.hdr", "camera.fov");
    }
}
