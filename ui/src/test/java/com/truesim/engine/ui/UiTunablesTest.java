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

    /** The tunables of the UI's later modules and controls (group A3), each of which must reach the settings. */
    static final List<String> A3_TUNABLES = List.of(
        // charts (a TunableSource of ui-charts)
        "ui.chart.zoomStep", "ui.chart.decimateAbove", "ui.chart.pyramidBlock", "ui.chart.lttbPerPixel",
        "ui.chart.lttbMaxInput", "ui.chart.maxPoints", "ui.chart.gridPerCell", "ui.chart.hoverDistance",
        "ui.chart.maxTicks", "ui.chart.followLead", "ui.chart.dragPixels", "ui.chart.grabPixels", "ui.chart.arcPixels",
        "ui.chart.heatmapTexels",
        // the scene bridge and gizmos (TunableSources of ui-scene and ui-gizmo)
        "ui.pick.sceneHits", "ui.pick.rayLength", "ui.gizmo.pickTolerance", "ui.gizmo.xrPickAngle",
        "ui.gizmo.xrAngularSize", "ui.gizmo.xrGrabRadius", "ui.gizmo.snapByDefault", "ui.gizmo.snapTranslate",
        "ui.gizmo.snapRotate", "ui.gizmo.snapScale", "ui.viewCube.orbitSpeed",
        // the console (ConsoleTunables, outside Ui.tunables())
        "ui.console.logCapacity", "ui.console.maxLine", "ui.console.historyLimit", "ui.console.completions",
        "ui.console.execDepth",
        // controls, data, render, tools
        "ui.button.repeatDelay", "ui.button.repeatRate", "ui.slider.pageFraction", "ui.input.numberDragPixels",
        "ui.text.spellBudget", "ui.spell.ignoreUrls", "ui.spell.ignoreNumbers", "ui.spell.ignoreMixedCase",
        "ui.spell.ignoreCapitals", "ui.spell.suggestions", "ui.list.boxScrollRate", "ui.list.boxScrollEdge",
        "ui.files.recentLimit", "ui.draw.dirtyMergeGap", "ui.draw.dirtyRanges", "ui.l10n.pseudo",
        "ui.l10n.pseudoExpansion", "ui.textScale", "ui.palette");

    @Test
    void everyTunableOfTheUisModulesIsListedForTheSettingsOnce() {
        com.truesim.ui.controls.Ui ui = new com.truesim.ui.controls.Ui(com.truesim.ui.style.tss.Tss.defaultTheme(), null);
        List<Tunable> all = UiPlugin.tunables(ui);
        List<String> names = all.stream().map(Tunable::name).toList();
        assertThat(names).containsAll(A3_TUNABLES);
        assertThat(names).as("each once").doesNotHaveDuplicates();
        assertThat(names).containsAll(ui.tunables().stream().map(Tunable::name).toList());
        SettingRegistry registry = new SettingRegistry();
        UiTunables tunables = new UiTunables(new EngineSettings()).add(registry, null, all.toArray(new Tunable[0]));
        for (String name : A3_TUNABLES) {
            assertThat(registry.find(name)).as("the setting %s", name).isNotNull();
        }
        assertThat(tunables.settings()).hasSize(all.size());
    }
}
