package com.truesim.engine.render;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The render module's technique slots as settings: defaults, older settings files, and reading them per frame. */
class TechniqueSettingsTest {

    @Test
    void theDefaultsAreTheTechniquesChosenBefore() {
        EngineSettings settings = new EngineSettings();
        assertThat(AmbientOcclusion.SLOT.chosen(settings)).isSameAs(AmbientOcclusion.BITMASK);
        assertThat(ScreenSpaceReflections.SLOT.chosen(settings)).isSameAs(ScreenSpaceReflections.HIZ);
        assertThat(ProbeVolumeLighting.SLOT.chosen(settings)).isSameAs(ProbeVolumeLighting.PROBES);
        assertThat(Transparency.SLOT.chosen(settings)).isSameAs(Transparency.SORTED);
        assertThat(WorldLights.SLOT.chosen(settings)).isSameAs(WorldLights.LISTS);
        assertThat(RenderSettings.interpolation(settings)).isEqualTo(Interpolation.GPU);
        assertThat(DisplayTransform.curve(settings)).isEqualTo(DisplayTransform.Curve.ACES);
        assertThat(settings.techniques().variants(AmbientOcclusion.SLOT)).containsExactly(AmbientOcclusion.GTAO,
            AmbientOcclusion.BITMASK, AmbientOcclusion.RAYS);
        assertThat(settings.get(Transparency.SORTED_RUNS)).isEqualTo(16);
        assertThat(settings.get(ProbeVolume.RAYS)).isEqualTo(128);
        assertThat(AmbientOcclusion.BITMASK.config(settings).radius()).isEqualTo(0.5f);
        assertThat(ScreenSpaceReflections.HIZ.config(settings).maxSteps()).isEqualTo(64);
        assertThat(ProbeVolume.RAYS.name()).isEqualTo("render.gi.probes.rays");
    }

    @Test
    void settingsFilesSavedBeforeTheSlotsStillLoad() {
        EngineSettings settings = new EngineSettings().load(Map.ofEntries(
            Map.entry("render.ao", "GTAO"), Map.entry("render.ao.radius", "1.5"), Map.entry("render.ao.rays", "4"),
            Map.entry("render.ao.denoiser", "REBLUR"), Map.entry("render.ssr", "OFF"),
            Map.entry("render.ssr.maxSteps", "32"), Map.entry("render.transparency", "WEIGHTED"),
            Map.entry("render.transparency.runs", "4"), Map.entry("render.gi", "PROBES"),
            Map.entry("render.gi.volume.rays", "64"), Map.entry("render.gi.response.iterations", "1"),
            Map.entry("render.gi.lights", "RESERVOIRS"), Map.entry("render.gi.lights.candidates", "8"),
            Map.entry("render.interpolation", "GPU_PRECISE"), Map.entry("display.toneCurve", "NONE")));
        assertThat(AmbientOcclusion.SLOT.chosen(settings)).isSameAs(AmbientOcclusion.GTAO);
        assertThat(AmbientOcclusion.GTAO.config(settings).radius()).isEqualTo(1.5f);
        assertThat(AmbientOcclusion.BITMASK.config(settings).radius()).isEqualTo(1.5f);
        assertThat(AmbientOcclusion.RAYS.config(settings).rays()).isEqualTo(4);
        assertThat(AmbientOcclusion.RAYS.config(settings).denoiser()).isEqualTo(Denoiser.Mode.REBLUR);
        assertThat(ScreenSpaceReflections.SLOT.chosen(settings)).isNull();
        assertThat(ScreenSpaceReflections.LINEAR.config(settings).maxSteps()).isEqualTo(32);
        assertThat(Transparency.SLOT.chosen(settings)).isSameAs(Transparency.WEIGHTED);
        assertThat(settings.get(Transparency.SORTED_RUNS)).isEqualTo(4);
        assertThat(ProbeVolumeLighting.SLOT.chosen(settings)).isSameAs(ProbeVolumeLighting.PROBES);
        assertThat(ProbeVolumeLighting.PROBES.config(settings).rays()).isEqualTo(64);
        assertThat(settings.get(ProbeVolume.ITERATIONS)).isEqualTo(1);
        assertThat(WorldLights.SLOT.chosen(settings)).isSameAs(WorldLights.RESERVOIRS);
        assertThat(WorldLights.RESERVOIRS.config(settings).candidates()).isEqualTo(8);
        assertThat(RenderSettings.interpolation(settings)).isEqualTo(Interpolation.GPU_PRECISE);
        assertThat(DisplayTransform.curve(settings)).isEqualTo(DisplayTransform.Curve.NONE);
        assertThat(settings.techniques().problems()).isEmpty();
    }

    @Test
    void theHelpersChooseByTheEnginesModes() {
        EngineSettings settings = new EngineSettings();
        for (Interpolation interpolation : Interpolation.values()) {
            assertThat(RenderSettings.interpolation(RenderSettings.interpolation(settings, interpolation)))
                .isEqualTo(interpolation);
        }
        for (DisplayTransform.Curve curve : DisplayTransform.Curve.values()) {
            assertThat(DisplayTransform.curve(DisplayTransform.curve(settings, curve))).isEqualTo(curve);
        }
        assertThat(settings.toMap()).containsEntry("render.interpolation", "off")
            .containsEntry("display.toneCurve", "aces");
    }

    @Test
    void everySlotsSettingsAreDiscovered() {
        EngineSettings settings = new EngineSettings();
        SettingRegistry registry = new SettingRegistry().techniques(settings.techniques());
        RenderSettings.register(registry);
        AmbientOcclusion.register(registry);
        ScreenSpaceReflections.register(registry);
        ProbeVolumeLighting.register(registry);
        ReferencePathTracer.register(registry);
        List<Setting<?>> all = registry.all();
        for (TechniqueSlot<?, ?> slot : List.of(AmbientOcclusion.SLOT, ScreenSpaceReflections.SLOT,
            ProbeVolumeLighting.SLOT, WorldLights.SLOT)) {
            assertThat(all).containsAll(settings.techniques().settings(slot));
        }
        assertThat(all).contains(Transparency.SLOT.setting(), Transparency.SORTED_RUNS);
        assertThat(all).doesNotHaveDuplicates();
        assertThat(registry.all("render")).contains(RenderSettings.OCCLUSION, AmbientOcclusion.DEBUG)
            .containsAll(settings.techniques().settings(AmbientOcclusion.SLOT)).doesNotContain(DisplayTransform.EXPOSURE);
        assertThat(registry.all("display")).contains(DisplayTransform.CURVE, DisplayTransform.EXPOSURE);
        assertThat(registry.all()).containsAll(DisplayTransform.ALL);
        assertThat(registry.setting("render.ao.bitmask.radius")).isNotNull();
    }

    @Test
    void readingTheChosenTechniquesEachFrameAllocatesNothing() {
        EngineSettings settings = new EngineSettings();
        AmbientOcclusion.SLOT.choose(settings, AmbientOcclusion.GTAO);
        float[] sum = new float[1];
        assertAllocationFree(() -> {
            TechniqueKind<?, AmbientOcclusion, ?> kind = AmbientOcclusion.SLOT.chosen(settings);
            sum[0] += kind == AmbientOcclusion.GTAO ? AmbientOcclusion.GTAO.config(settings).radius() : 0;
            sum[0] += ScreenSpaceReflections.HIZ.config(settings).maxDistance();
            sum[0] += DisplayTransform.curve(settings).ordinal() + RenderSettings.interpolation(settings).ordinal();
        });
        assertThat(sum[0]).isPositive();
    }
}
