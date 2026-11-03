package com.truesim.engine.ui;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingGroup;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.ui.core.Tunable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ui-core's tunables as engine settings: same name, range and description; changes written back each frame. */
class UiTunablesTest {

    @Test
    void tunablesBecomeSettingsAndFollowThem() {
        Tunable scale = Tunable.of("ui.scale", 1, 0.5f, 3, "how large the UI is drawn");
        Tunable blink = Tunable.of("ui.caretBlink", 0.53f, 0, 2, "seconds the caret blinks on and off");
        EngineSettings settings = new EngineSettings().load(Map.of("ui.caretBlink", "0.8"));
        SettingRegistry registry = new SettingRegistry();
        SettingGroup group = SettingGroup.root("ui", "settings.ui", 50);
        UiTunables tunables = new UiTunables(settings).add(registry, group, scale, blink);
        @SuppressWarnings("unchecked")
        Setting<Float> setting = (Setting<Float>) tunables.setting(scale);
        assertThat(setting.name()).isEqualTo("ui.scale");
        assertThat(setting.min()).isEqualTo(0.5f);
        assertThat(setting.max()).isEqualTo(3f);
        assertThat(setting.defaultValue()).isEqualTo(1f);
        assertThat(setting.description()).isEqualTo("how large the UI is drawn");
        assertThat(registry.find("ui.scale")).isNotNull();
        assertThat(registry.find("ui.scale").group()).isEqualTo(group);
        assertThat(blink.get()).as("a saved value at once").isEqualTo(0.8f);
        settings.set(setting, 2f);
        assertThat(scale.get()).as("not before the frame").isEqualTo(1f);
        tunables.update();
        assertThat(scale.get()).isEqualTo(2f);
        long version = scale.version();
        tunables.update();
        assertThat(scale.version()).as("unchanged: not set again").isEqualTo(version);
        assertThat(tunables.settings()).hasSize(2);
        // Each call finds the tunable away from its setting (moved here directly) and writes the setting back.
        assertAllocationFree(() -> {
            scale.set(scale.get() == 2 ? 1.5f : 0.75f);
            tunables.update();
        });
        assertThat(scale.get()).isEqualTo(2f);
        assertThatIllegalArgumentException().isThrownBy(() -> tunables.add(null, null,
            Tunable.of("ui.scale", 1, 0, 2, "again")));
    }

    @Test
    void choiceTunablesBecomeChoiceSettings() {
        Tunable mode = Tunable.choice("ui.text.smallText", List.of("HINTED", "MTSDF"), "HINTED", "small text");
        EngineSettings settings = new EngineSettings().load(Map.of("ui.text.smallText", "mtsdf"));
        SettingRegistry registry = new SettingRegistry();
        UiTunables tunables = new UiTunables(settings).add(registry, null, mode);
        @SuppressWarnings("unchecked")
        Setting<String> setting = (Setting<String>) tunables.setting(mode);
        assertThat(setting.kind()).isEqualTo(Setting.Kind.CHOICE);
        assertThat(setting.choices()).containsExactly("HINTED", "MTSDF");
        assertThat(setting.defaultValue()).isEqualTo("HINTED");
        assertThat(setting.description()).isEqualTo("small text");
        assertThat(registry.find("ui.text.smallText")).isNotNull();
        assertThat(mode.option()).as("the saved value at once").isEqualTo("MTSDF");
        settings.set(setting, "HINTED");
        tunables.update();
        assertThat(mode.option()).isEqualTo("HINTED");
        long version = mode.version();
        tunables.update();
        assertThat(mode.version()).isEqualTo(version);
        assertAllocationFree(() -> {
            mode.set(mode.index() == 0 ? 1 : 0);
            tunables.update();
        });
        assertThat(mode.option()).isEqualTo("HINTED");
    }
}
